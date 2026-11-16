package com.example.webview

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

class OfflineAssetCache(context: Context) {

  companion object {
    const val TARGET_HOST = "my-game-ludo.pages.dev"
    const val HOME_URL = "https://my-game-ludo.pages.dev/"

    private val STRIPPED_HEADERS =
      setOf(
        "content-encoding",
        "content-length",
        "transfer-encoding",
        "connection",
        "keep-alive",
        "x-frame-options",
      )

    private val TEXT_MIME_PREFIXES = listOf("text/")
    private val TEXT_EXACT_MIMES =
      setOf(
        "application/javascript",
        "text/javascript",
        "application/x-javascript",
        "application/json",
        "application/manifest+json",
        "application/xml",
        "image/svg+xml",
      )
  }

  private val cacheDir = File(context.applicationContext.filesDir, "ludo_offline_cache").apply {
    mkdirs()
  }

  private val client =
    OkHttpClient.Builder()
      .connectTimeout(8, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .build()

  private val writeLock = Any()

  fun isTargetHost(host: String?): Boolean {
    if (host.isNullOrBlank()) return false
    return host.equals(TARGET_HOST, ignoreCase = true) ||
      host.endsWith(".$TARGET_HOST", ignoreCase = true)
  }

  fun hasCachedHome(): Boolean {
    val key = cacheKey(HOME_URL)
    return metaFile(key).exists() && bodyFile(key).exists() && bodyFile(key).length() > 0L
  }

  fun fetchOrServeCached(
    request: WebResourceRequest,
    isOnline: Boolean,
  ): WebResourceResponse? {
    if (!request.method.equals("GET", ignoreCase = true)) return null
    val uri = request.url ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "https" && scheme != "http") return null
    if (!isTargetHost(uri.host)) return null

    // Let WebView handle partial Range requests (e.g., streaming audio/video chunks) natively.
    val reqHeaders = request.requestHeaders.orEmpty()
    if (reqHeaders.keys.any { it.equals("Range", ignoreCase = true) }) {
      return null
    }

    val normalizedUrl = normalizeUrl(uri)

    if (isOnline) {
      val networkResponse = fetchFromNetworkAndCache(normalizedUrl, reqHeaders)
      if (networkResponse != null) {
        return networkResponse
      }
    }

    return readFromDiskCache(normalizedUrl, isMainFrame = request.isForMainFrame)
  }

  private fun fetchFromNetworkAndCache(
    url: String,
    requestHeaders: Map<String, String>,
  ): WebResourceResponse? {
    return try {
      val builder = Request.Builder().url(url).get()
      requestHeaders.forEach { (key, value) ->
        if (
          !key.equals("Accept-Encoding", ignoreCase = true) &&
            !key.equals("If-None-Match", ignoreCase = true) &&
            !key.equals("If-Modified-Since", ignoreCase = true) &&
            !key.equals("Host", ignoreCase = true)
        ) {
          builder.header(key, value)
        }
      }

      val cookieManager = CookieManager.getInstance()
      val existingCookie = runCatching { cookieManager.getCookie(url) }.getOrNull()
      if (!existingCookie.isNullOrBlank() && requestHeaders.keys.none { it.equals("Cookie", true) }) {
        builder.header("Cookie", existingCookie)
      }

      client.newCall(builder.build()).execute().use { response ->
        response.headers("Set-Cookie").forEach { setCookie ->
          runCatching { cookieManager.setCookie(url, setCookie) }
        }

        val bodyBytes = response.body?.bytes() ?: ByteArray(0)
        val statusCode = response.code
        val reasonPhrase = response.message.ifBlank { defaultReasonPhrase(statusCode) }
        val rawContentType = response.header("Content-Type")
        val (mimeType, encoding) = parseContentType(rawContentType, url)

        val responseHeaders = linkedMapOf<String, String>()
        for (i in 0 until response.headers.size) {
          val name = response.headers.name(i)
          val value = response.headers.value(i)
          if (name.lowercase() !in STRIPPED_HEADERS) {
            responseHeaders[name] = value
          }
        }
        if (!responseHeaders.keys.any { it.equals("Access-Control-Allow-Origin", true) }) {
          responseHeaders["Access-Control-Allow-Origin"] = "*"
        }

        if (statusCode == 200 && bodyBytes.isNotEmpty()) {
          saveToDiskCache(
            url = url,
            statusCode = statusCode,
            reasonPhrase = reasonPhrase,
            mimeType = mimeType,
            encoding = encoding,
            headers = responseHeaders,
            body = bodyBytes,
          )
        }

        WebResourceResponse(
          mimeType,
          encoding,
          statusCode,
          reasonPhrase,
          responseHeaders,
          bodyBytes.inputStream(),
        )
      }
    } catch (_: IOException) {
      null
    } catch (_: Exception) {
      null
    }
  }

  private fun saveToDiskCache(
    url: String,
    statusCode: Int,
    reasonPhrase: String,
    mimeType: String,
    encoding: String?,
    headers: Map<String, String>,
    body: ByteArray,
  ) {
    synchronized(writeLock) {
      try {
        val key = cacheKey(url)
        val metaTmp = File(cacheDir, "$key.meta.tmp")
        val bodyTmp = File(cacheDir, "$key.body.tmp")

        bodyTmp.writeBytes(body)

        val metaContent = buildString {
          appendLine(statusCode.toString())
          appendLine(reasonPhrase.replace("\n", " ").replace("\r", " "))
          appendLine(mimeType.replace("\n", " ").replace("\r", " "))
          appendLine(encoding.orEmpty())
          headers.forEach { (k, v) ->
            val safeKey = k.replace(":", "").replace("\n", "").replace("\r", "")
            val safeVal = v.replace("\n", " ").replace("\r", " ")
            appendLine("$safeKey:$safeVal")
          }
        }
        metaTmp.writeText(metaContent, Charsets.UTF_8)

        bodyTmp.renameTo(bodyFile(key))
        metaTmp.renameTo(metaFile(key))
      } catch (_: Exception) {
        // Ignore non-fatal disk write failures.
      }
    }
  }

  private fun readFromDiskCache(url: String, isMainFrame: Boolean): WebResourceResponse? {
    val exact = readEntry(cacheKey(url))
    if (exact != null) return exact

    if (isMainFrame && url != HOME_URL) {
      return readEntry(cacheKey(HOME_URL))
    }
    return null
  }

  private fun readEntry(key: String): WebResourceResponse? {
    val meta = metaFile(key)
    val body = bodyFile(key)
    if (!meta.exists() || !body.exists()) return null

    return try {
      val lines = meta.readLines(Charsets.UTF_8)
      if (lines.size < 4) return null

      val statusCode = lines[0].toIntOrNull() ?: 200
      val reasonPhrase = lines[1].ifBlank { defaultReasonPhrase(statusCode) }
      val mimeType = lines[2].ifBlank { "application/octet-stream" }
      val encoding = lines[3].ifBlank { null }

      val headers = linkedMapOf<String, String>()
      for (i in 4 until lines.size) {
        val line = lines[i]
        val sep = line.indexOf(':')
        if (sep > 0) {
          headers[line.substring(0, sep)] = line.substring(sep + 1)
        }
      }

      WebResourceResponse(
        mimeType,
        encoding,
        statusCode,
        reasonPhrase,
        headers,
        FileInputStream(body),
      )
    } catch (_: Exception) {
      null
    }
  }

  private fun normalizeUrl(uri: Uri): String {
    val builder = uri.buildUpon().fragment(null)
    val path = uri.path
    if (path.isNullOrEmpty()) {
      builder.path("/")
    }
    return builder.build().toString()
  }

  private fun parseContentType(rawHeader: String?, url: String): Pair<String, String?> {
    var mime = ""
    var charset: String? = null

    if (!rawHeader.isNullOrBlank()) {
      val parts = rawHeader.split(';')
      mime = parts.firstOrNull()?.trim()?.lowercase().orEmpty()
      for (part in parts.drop(1)) {
        val trimmed = part.trim()
        if (trimmed.startsWith("charset=", ignoreCase = true)) {
          charset = trimmed.substringAfter('=').trim().trim('"', '\'')
        }
      }
    }

    if (mime.isBlank() || mime == "application/octet-stream") {
      val guessed = guessMimeFromUrl(url)
      if (guessed != null) {
        mime = guessed
      } else if (mime.isBlank()) {
        mime = "application/octet-stream"
      }
    }

    val isTextual =
      TEXT_MIME_PREFIXES.any { mime.startsWith(it) } || mime in TEXT_EXACT_MIMES
    val resolvedEncoding = if (charset.isNullOrBlank()) {
      if (isTextual) "UTF-8" else null
    } else {
      charset
    }

    return mime to resolvedEncoding
  }

  private fun guessMimeFromUrl(url: String): String? {
    val path = Uri.parse(url).path?.lowercase().orEmpty()
    return when {
      path.isEmpty() || path.endsWith("/") || path.endsWith(".html") || path.endsWith(".htm") ->
        "text/html"
      path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript"
      path.endsWith(".css") -> "text/css"
      path.endsWith(".json") || path.endsWith(".webmanifest") -> "application/json"
      path.endsWith(".svg") -> "image/svg+xml"
      path.endsWith(".png") -> "image/png"
      path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
      path.endsWith(".webp") -> "image/webp"
      path.endsWith(".ico") -> "image/x-icon"
      path.endsWith(".woff2") -> "font/woff2"
      path.endsWith(".woff") -> "font/woff"
      path.endsWith(".ttf") -> "font/ttf"
      path.endsWith(".mp3") -> "audio/mpeg"
      path.endsWith(".wav") -> "audio/wav"
      path.endsWith(".ogg") -> "audio/ogg"
      else -> null
    }
  }

  private fun defaultReasonPhrase(code: Int): String =
    when (code) {
      200 -> "OK"
      201 -> "Created"
      204 -> "No Content"
      301 -> "Moved Permanently"
      302 -> "Found"
      304 -> "Not Modified"
      400 -> "Bad Request"
      401 -> "Unauthorized"
      403 -> "Forbidden"
      404 -> "Not Found"
      500 -> "Internal Server Error"
      else -> "HTTP $code"
    }

  private fun cacheKey(url: String): String {
    val canonical = if (url == "https://$TARGET_HOST") HOME_URL else url
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }

  private fun metaFile(key: String): File = File(cacheDir, "$key.meta")
  private fun bodyFile(key: String): File = File(cacheDir, "$key.body")
}
