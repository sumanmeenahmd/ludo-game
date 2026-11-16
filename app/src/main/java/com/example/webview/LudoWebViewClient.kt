package com.example.webview

import android.content.Context
import android.content.Intent
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

class LudoWebViewClient(
  private val context: Context,
  private val offlineAssetCache: OfflineAssetCache,
  private val isOnlineProvider: () -> Boolean,
  private val onInitialLoadFinished: () -> Unit,
  private val onNavigationStateChanged: (canGoBack: Boolean) -> Unit,
  private val onOfflineNoCache: () -> Unit,
  private val onPageLoadedSuccessfully: () -> Unit,
  private val onRendererCrashed: () -> Unit,
) : WebViewClient() {

  override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
    val uri = request?.url ?: return false
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host

    if ((scheme == "https" || scheme == "http") && offlineAssetCache.isTargetHost(host)) {
      return false
    }

    return try {
      val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      true
    } catch (_: Exception) {
      true
    }
  }

  override fun shouldInterceptRequest(
    view: WebView?,
    request: WebResourceRequest?,
  ): WebResourceResponse? {
    if (request == null) return null
    return offlineAssetCache.fetchOrServeCached(
      request = request,
      isOnline = isOnlineProvider(),
    )
  }

  override fun onPageFinished(view: WebView?, url: String?) {
    super.onPageFinished(view, url)
    onNavigationStateChanged(view?.canGoBack() == true)
    onInitialLoadFinished()
    if (!url.isNullOrBlank() && url != "about:blank") {
      onPageLoadedSuccessfully()
    }
  }

  override fun onReceivedError(
    view: WebView?,
    request: WebResourceRequest?,
    error: WebResourceError?,
  ) {
    super.onReceivedError(view, request, error)
    if (request?.isForMainFrame == true) {
      onInitialLoadFinished()
      if (!offlineAssetCache.hasCachedHome()) {
        onOfflineNoCache()
      }
    }
  }

  override fun onRenderProcessGone(
    view: WebView?,
    detail: RenderProcessGoneDetail?,
  ): Boolean {
    onRendererCrashed()
    return true
  }
}
