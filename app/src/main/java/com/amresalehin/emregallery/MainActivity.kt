package com.amresalehin.emregallery

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.ui.GalleryApp
import com.amresalehin.emregallery.ui.theme.EmreGalleryTheme
import kotlinx.coroutines.flow.catch

class MainActivity : FragmentActivity() {

    private val incomingBrowserRequest = mutableStateOf<BrowserTabOpenRequest?>(null)
    private var nextBrowserRequestId = 0L
    private var lastAppliedDisplayId: Int = -1
    private var lastAppliedModeId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyHighRefreshRate()
        handleBrowserOpenIntent(intent)

        val preferences = PreferencesRepository(applicationContext)
        // Install Compose immediately. Waiting for DataStore before setContent meant a
        // preference-read failure could leave the Activity without its home UI at all.
        setContent {
            val themeFlow = remember(preferences) {
                preferences.theme.catch { error ->
                    Log.e("MainActivity", "Unable to read saved theme; using system theme", error)
                    emit("system")
                }
            }
            val theme by themeFlow.collectAsStateWithLifecycle(initialValue = "system")
            SideEffect { applyStartupTheme(theme) }
            EmreGalleryTheme(theme) {
                GalleryApp(
                    externalBrowserOpenRequest = incomingBrowserRequest.value,
                    onExternalBrowserOpenRequestConsumed = { requestId ->
                        if (incomingBrowserRequest.value?.requestId == requestId) {
                            incomingBrowserRequest.value = null
                        }
                    }
                )
            }
        }
    }


    /**
     * Match the Activity's first visible window frame to the saved in-app theme.
     * Compose will take over the full content surface once setContent is installed.
     */
    private fun applyStartupTheme(theme: String) {
        val darkTheme = when (theme) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        window.setBackgroundDrawable(
            ColorDrawable(if (darkTheme) Color.rgb(16, 16, 16) else Color.WHITE)
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleBrowserOpenIntent(intent)
    }

    private fun handleBrowserOpenIntent(intent: Intent?) {
        if (intent?.action != BrowserTabIntent.ACTION_OPEN_TAB) return
        val rawUrl = intent.getStringExtra(BrowserTabIntent.EXTRA_URL)?.trim().orEmpty()
        if (rawUrl.isBlank()) return

        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return
        val scheme = uri.scheme
        if ((!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) || uri.host.isNullOrBlank()) return

        incomingBrowserRequest.value = BrowserTabOpenRequest(
            requestId = ++nextBrowserRequestId,
            url = rawUrl
        )
        intent.removeExtra(BrowserTabIntent.EXTRA_URL)
    }

    override fun onResume() {
        super.onResume()
        // Re-apply the display's supported refresh mode when returning to the foreground.
        applyHighRefreshRate()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyHighRefreshRate()
        }
    }

    /**
     * Requests the highest refresh rate reported by the active display, while allowing Android
     * to select its normal mode when no suitable display mode is available.
     */
    private fun applyHighRefreshRate() {
        val currentWindow = window ?: return

        // Request a refresh rate the attached display actually reports as supported.
        // Hard-coding 120Hz can leave preferredRefreshRate inconsistent with the
        // selected display mode on 60Hz emulators and devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }

            val displayId = currentDisplay?.displayId ?: -1
            val supportedModes = currentDisplay?.supportedModes ?: emptyArray()
            var preferredMode: android.view.Display.Mode? = null
            for (mode in supportedModes) {
                if (mode.refreshRate >= 60f && (preferredMode == null || mode.refreshRate > preferredMode.refreshRate)) {
                    preferredMode = mode
                }
            }
            val targetModeId = preferredMode?.modeId ?: 0
            if (displayId == lastAppliedDisplayId && targetModeId == lastAppliedModeId) return

            val params = currentWindow.attributes
            if (preferredMode != null) {
                params.preferredRefreshRate = preferredMode.refreshRate
                params.preferredDisplayModeId = preferredMode.modeId
            } else {
                // Leave mode selection to Android when the display reports no usable modes.
                params.preferredRefreshRate = 0f
                params.preferredDisplayModeId = 0
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                params.setFrameRateBoostOnTouchEnabled(
                    preferredMode != null && preferredMode.refreshRate > 60f
                )
            }

            currentWindow.attributes = params
            lastAppliedDisplayId = displayId
            lastAppliedModeId = targetModeId
        }
    }

}


