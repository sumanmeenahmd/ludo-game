package com.example.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.example.R
import com.example.network.NetworkMonitor
import com.example.webview.LudoWebViewClient
import com.example.webview.OfflineAssetCache

@Composable
fun LudoScreen(
  isDarkTheme: Boolean,
  onInitialWebReady: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val networkMonitor = remember(context) { NetworkMonitor(context) }
  val offlineAssetCache = remember(context) { OfflineAssetCache(context) }
  val isOnline by networkMonitor.observeIsOnline().collectAsStateWithLifecycle(
    initialValue = networkMonitor.isCurrentlyOnline(),
  )

  var canGoBack by remember { mutableStateOf(false) }
  var showOfflineEmptyState by remember { mutableStateOf(false) }
  var rendererResetKey by remember { mutableIntStateOf(0) }
  var webViewRef by remember { mutableStateOf<WebView?>(null) }

  BackHandler(enabled = canGoBack) {
    webViewRef?.let { webView ->
      if (webView.canGoBack()) {
        webView.goBack()
      }
    }
  }

  LaunchedEffect(isOnline, showOfflineEmptyState) {
    val webView = webViewRef ?: return@LaunchedEffect
    webView.settings.cacheMode =
      if (isOnline) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
    syncServiceWorkerCacheMode(isOnline)

    if (isOnline && showOfflineEmptyState) {
      showOfflineEmptyState = false
      webView.loadUrl(OfflineAssetCache.HOME_URL)
    }
  }

  LaunchedEffect(isDarkTheme, webViewRef) {
    webViewRef?.let { applyWebViewTheme(it, isDarkTheme) }
  }

  DisposableEffect(Unit) {
    onDispose {
      webViewRef?.apply {
        stopLoading()
        destroy()
      }
      webViewRef = null
    }
  }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background),
  ) {
    key(rendererResetKey) {
      AndroidView(
        modifier = Modifier.fillMaxSize().testTag("ludo_webview"),
        factory = { viewContext ->
          WebView(viewContext).apply {
            layoutParams =
              ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
              )
            configureWebView(
              webView = this,
              isDarkTheme = isDarkTheme,
              isOnline = isOnline,
              client =
                LudoWebViewClient(
                  context = viewContext,
                  offlineAssetCache = offlineAssetCache,
                  isOnlineProvider = { networkMonitor.isCurrentlyOnline() },
                  onInitialLoadFinished = onInitialWebReady,
                  onNavigationStateChanged = { canBack -> canGoBack = canBack },
                  onOfflineNoCache = { showOfflineEmptyState = true },
                  onPageLoadedSuccessfully = { showOfflineEmptyState = false },
                  onRendererCrashed = { rendererResetKey++ },
                ),
            )
            webViewRef = this
            loadUrl(OfflineAssetCache.HOME_URL)
          }
        },
        update = { webView ->
          applyWebViewTheme(webView, isDarkTheme)
        },
      )
    }

    if (showOfflineEmptyState) {
      OfflineFallback(
        onRetry = {
          showOfflineEmptyState = false
          val target = webViewRef
          if (target != null) {
            target.settings.cacheMode =
              if (networkMonitor.isCurrentlyOnline()) {
                WebSettings.LOAD_DEFAULT
              } else {
                WebSettings.LOAD_CACHE_ELSE_NETWORK
              }
            target.loadUrl(OfflineAssetCache.HOME_URL)
          }
        },
      )
    }
  }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureWebView(
  webView: WebView,
  isDarkTheme: Boolean,
  isOnline: Boolean,
  client: LudoWebViewClient,
) {
  webView.settings.apply {
    javaScriptEnabled = true
    domStorageEnabled = true
    databaseEnabled = true
    useWideViewPort = true
    loadWithOverviewMode = true
    mediaPlaybackRequiresUserGesture = false
    setSupportZoom(false)
    builtInZoomControls = false
    displayZoomControls = false
    allowFileAccess = false
    allowContentAccess = true
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    cacheMode = if (isOnline) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
  }

  webView.isVerticalScrollBarEnabled = false
  webView.isHorizontalScrollBarEnabled = false
  webView.overScrollMode = WebView.OVER_SCROLL_NEVER

  val cookieManager = CookieManager.getInstance()
  cookieManager.setAcceptCookie(true)
  cookieManager.setAcceptThirdPartyCookies(webView, true)

  syncServiceWorkerCacheMode(isOnline)
  applyWebViewTheme(webView, isDarkTheme)

  webView.webChromeClient = WebChromeClient()
  webView.webViewClient = client
}

private fun syncServiceWorkerCacheMode(isOnline: Boolean) {
  runCatching {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
      val swSettings = ServiceWorkerControllerCompat.getInstance().serviceWorkerWebSettings
      if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_CONTENT_ACCESS)) {
        swSettings.allowContentAccess = true
      }
      if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_FILE_ACCESS)) {
        swSettings.allowFileAccess = false
      }
      if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_CACHE_MODE)) {
        swSettings.cacheMode =
          if (isOnline) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
      }
    }
  }
}

@Suppress("DEPRECATION")
private fun applyWebViewTheme(webView: WebView, isDarkTheme: Boolean) {
  webView.setBackgroundColor(
    if (isDarkTheme) 0xFF0F172A.toInt() else 0xFFF8FAFC.toInt(),
  )

  if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
    WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
  } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
    WebSettingsCompat.setForceDark(
      webView.settings,
      if (isDarkTheme) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF,
    )
  }

  if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
    WebSettingsCompat.setForceDarkStrategy(
      webView.settings,
      WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING,
    )
  }
}

@Composable
private fun OfflineFallback(onRetry: () -> Unit) {
  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier.widthIn(max = 360.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Icon(
        imageVector = Icons.Rounded.WifiOff,
        contentDescription = stringResource(R.string.cd_offline_icon),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(56.dp),
      )
      Spacer(modifier = Modifier.height(16.dp))
      Text(
        text = stringResource(R.string.offline_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = stringResource(R.string.offline_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(24.dp))
      Button(
        onClick = onRetry,
        modifier = Modifier.defaultMinSize(minWidth = 140.dp, minHeight = 48.dp).testTag("retry_button"),
      ) {
        Text(text = stringResource(R.string.action_retry))
      }
    }
  }
}
