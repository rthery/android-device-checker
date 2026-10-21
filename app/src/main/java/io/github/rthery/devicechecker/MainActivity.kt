package io.github.rthery.devicechecker

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.rthery.devicechecker.pack.LauncherIcon
import io.github.rthery.devicechecker.shell.ShizukuShell
import io.github.rthery.devicechecker.ui.App
import kotlinx.coroutines.launch

/** Ignores intent extras: adb requests arrive through [AdbLinkActivity] and [AdbLinks] only. */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var shownOverLockScreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App(viewModel) }
        holdSplashUntilThemed()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AdbLinks.pending.collect { link ->
                    if (link == null) return@collect
                    AdbLinks.consume()
                    if (link.run != null) showOverLockScreen(true)
                    viewModel.deepLink(run = link.run, test = link.test, tab = link.tab, options = link.options)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.progress.collect { running ->
                if (running != null) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if (shownOverLockScreen) showOverLockScreen(false)
                }
            }
        }
    }

    /** Keeps the splash up until the packs' theme is known, then makes the next splash show their icon. */
    private fun holdSplashUntilThemed() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val loaded = viewModel.theme.value ?: return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                if (Build.VERSION.SDK_INT >= 33) splashScreen.setSplashScreenTheme(LauncherIcon.from(loaded.hints?.icon).splashTheme)
                return true
            }
        })
    }

    override fun onResume() {
        super.onResume()
        ShizukuShell.refresh()
    }

    /** Only for a run adb started, and only until it ends. */
    @Suppress("DEPRECATION")
    private fun showOverLockScreen(show: Boolean) {
        shownOverLockScreen = show
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(show)
            setTurnScreenOn(show)
        } else {
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (show) window.addFlags(flags) else window.clearFlags(flags)
        }
    }
}
