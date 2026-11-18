package com.amresalehin.emregallery

import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.ui.GalleryApp
import com.amresalehin.emregallery.ui.theme.EmreGalleryTheme

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        applyHighRefreshRate()
        warmupChromiumEngine()

        setContent {
            val preferences = remember { PreferencesRepository(applicationContext) }
            val initialTheme = remember { preferences.getThemeSync() }
            val initialColorTheme = remember { preferences.getColorThemeSync() }
            val theme by preferences.theme.collectAsStateWithLifecycle(initialValue = initialTheme)
            val colorTheme by preferences.colorTheme.collectAsStateWithLifecycle(initialValue = initialColorTheme)
            EmreGalleryTheme(theme = theme, colorTheme = colorTheme) {
                GalleryApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyHighRefreshRate()
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
        val targetFps = 120f
        val currentWindow = this.window ?: return

        // Android 6.0+ (API 23+) WindowManager.LayoutParams
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val params = currentWindow.attributes
            params.preferredRefreshRate = targetFps

            // Android 15+ (API 35) frame rate boost on touch
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                params.setFrameRateBoostOnTouchEnabled(true)
            }

            // Select physical display mode >= 119Hz if hardware supports it
            val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }

            val modes = currentDisplay?.supportedModes ?: emptyArray()
            val bestHighRateMode = modes.filter { it.refreshRate >= 119f }
                .maxByOrNull { it.refreshRate }
                ?: modes.maxByOrNull { it.refreshRate }

            if (bestHighRateMode != null && bestHighRateMode.refreshRate >= 60f) {
                params.preferredDisplayModeId = bestHighRateMode.modeId
            }

            currentWindow.attributes = params
        }
    }

    private fun warmupChromiumEngine() {
        window?.decorView?.post {
            GalleryApplication.warmupChromium(this)
        }
    }
}


