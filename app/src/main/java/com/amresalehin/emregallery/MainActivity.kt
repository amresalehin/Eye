package com.amresalehin.emregallery

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.ui.GalleryApp
import com.amresalehin.emregallery.ui.theme.EmreGalleryTheme

class MainActivity : FragmentActivity() {

    private val incomingBrowserRequest = mutableStateOf<BrowserTabOpenRequest?>(null)
    private var nextBrowserRequestId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyHighRefreshRate()
        handleBrowserOpenIntent(intent)

        val preferences = PreferencesRepository(applicationContext)
        // Install Compose immediately; the persisted theme is collected asynchronously.
        setContent {
            val theme by preferences.theme.collectAsStateWithLifecycle(initialValue = "system")
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
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return

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

    override fun onPause() {
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyHighRefreshRate()
        }
    }

    /**
     * Requests continuous 120Hz / 120 FPS for the app window across all supported Android display APIs.
     * Ensures Emre Gallery renders at maximum fluidity without artificial frame rate capping.
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

            val supportedModes = currentDisplay?.supportedModes ?: emptyArray()
            val preferredMode = supportedModes
                .filter { it.refreshRate >= 60f }
                .maxByOrNull { it.refreshRate }

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
        }
    }

}


