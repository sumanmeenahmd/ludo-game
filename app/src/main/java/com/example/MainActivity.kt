package com.example

import android.animation.ObjectAnimator
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.ui.LudoScreen
import com.example.ui.theme.LudoPlayTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  private val isDarkThemeFlow = MutableStateFlow(false)
  @Volatile private var canDismissSplash = false

  override fun onCreate(savedInstanceState: Bundle?) {
    val splashScreen = installSplashScreen()
    super.onCreate(savedInstanceState)

    isDarkThemeFlow.value = isNightMode(resources.configuration)

    splashScreen.setKeepOnScreenCondition { !canDismissSplash }
    splashScreen.setOnExitAnimationListener { splashViewProvider ->
      val fadeOut =
        ObjectAnimator.ofFloat(splashViewProvider.view, View.ALPHA, 1f, 0f).apply {
          interpolator = DecelerateInterpolator()
          duration = 220L
          doOnEnd { splashViewProvider.remove() }
        }
      fadeOut.start()
    }

    lifecycleScope.launch {
      delay(1800L)
      canDismissSplash = true
    }

    enableEdgeToEdge()
    applyFullscreenSystemBars()

    setContent {
      val isDarkTheme by isDarkThemeFlow.collectAsStateWithLifecycle()
      LudoPlayTheme(darkTheme = isDarkTheme) {
        LudoScreen(
          isDarkTheme = isDarkTheme,
          onInitialWebReady = { canDismissSplash = true },
        )
      }
    }
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    isDarkThemeFlow.value = isNightMode(newConfig)
    applyFullscreenSystemBars()
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      applyFullscreenSystemBars()
    }
  }

  private fun applyFullscreenSystemBars() {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior =
      WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
  }

  private fun isNightMode(config: Configuration): Boolean {
    return (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
  }
}
