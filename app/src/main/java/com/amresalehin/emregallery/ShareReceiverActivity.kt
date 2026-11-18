package com.amresalehin.emregallery

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amresalehin.emregallery.data.BrowserSession
import com.amresalehin.emregallery.data.DownloaderSession
import com.amresalehin.emregallery.data.ExtractionCoordinator
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.PocketRepository
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.ui.DirectBrowserView
import com.amresalehin.emregallery.ui.DiscoveredMediaPage
import com.amresalehin.emregallery.ui.theme.EmreGalleryTheme

/**
 * Translucent share-sheet target that opens directly over the sharing app
 * (e.g. Chrome, Twitter, Instagram, YouTube) without switching applications.
 *
 * It immediately extracts media from the shared link and displays a bottom
 * drawer for selecting and downloading media in the background.
 */
class ShareReceiverActivity : ComponentActivity() {

    private val sharedUrlState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window?.decorView?.post {
            GalleryApplication.warmupChromium(this)
        }

        val extracted = extractUrlFromIntent(intent)
        if (extracted == null) {
            Toast.makeText(this, "No valid link found in shared content", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        sharedUrlState.value = extracted

        setContent {
            val preferences = remember { PreferencesRepository(applicationContext) }
            val theme by preferences.theme.collectAsStateWithLifecycle(initialValue = "system")
            val colorTheme by preferences.colorTheme.collectAsStateWithLifecycle(initialValue = preferences.getColorThemeSync())
            val currentUrl by sharedUrlState

            EmreGalleryTheme(theme = theme, colorTheme = colorTheme, surfaceColor = Color.Transparent) {
                if (currentUrl != null) {
                    ShareReceiverScreen(
                        url = currentUrl!!,
                        onDismiss = {
                            finishWithFade()
                        },
                        onDownloadComplete = {
                            Toast.makeText(
                                applicationContext,
                                "Download queued in background",
                                Toast.LENGTH_SHORT
                            ).show()
                            finishWithFade()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val extracted = extractUrlFromIntent(intent)
        if (extracted != null) {
            sharedUrlState.value = extracted
        }
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW) return null

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.dataString
            ?: intent.clipData?.let { cd ->
                if (cd.itemCount > 0) cd.getItemAt(0).text?.toString() ?: cd.getItemAt(0).uri?.toString() else null
            }

        return MediaClassifier.extractFirstUrl(text)
    }

    private fun finishWithFade() {
        finish()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, android.R.anim.fade_out)
        }
    }
}

@Composable
private fun ShareReceiverScreen(
    url: String,
    onDismiss: () -> Unit,
    onDownloadComplete: () -> Unit
) {
    var showLiveBrowser by remember { mutableStateOf(false) }
    var liveBrowserUrl by remember { mutableStateOf(url) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coordinator = remember(context) { ExtractionCoordinator(context) }
    val extractionState by coordinator.state.collectAsState()

    LaunchedEffect(url) {
        liveBrowserUrl = url
        DownloaderSession.startSession(url)
        coordinator.extract(url, scope)
    }

    DisposableEffect(Unit) {
        onDispose { coordinator.cancel() }
    }

    BackHandler {
        if (showLiveBrowser) {
            showLiveBrowser = false
        } else {
            onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .testTag("share_receiver_root"),
        contentAlignment = Alignment.BottomCenter
    ) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(
                topStart = 28.dp,
                topEnd = 28.dp
            ),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 6.dp,
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 96.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* consume clicks inside bottom sheet */ }
                )
                .testTag("share_receiver_bottom_sheet")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Drag handle pill at top of translucent bottom sheet
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(38.dp)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                androidx.compose.foundation.shape.CircleShape
                            )
                    )
                }

                // Live extraction status bar with direct Browser and Library actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (extractionState.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(
                        text = extractionState.errorMessage
                            ?: extractionState.statusMessage.ifBlank { "Scanning shared link..." },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (extractionState.errorMessage != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    var savedToPocket by remember(liveBrowserUrl) { mutableStateOf(false) }
                    TextButton(
                        onClick = {
                            val pocketRepo = PocketRepository.getInstance(context)
                            val existing = pocketRepo.findDuplicateBookmark(liveBrowserUrl)
                            if (existing != null) {
                                savedToPocket = true
                                Toast.makeText(context, "Already saved in Pocket: ${existing.title}", Toast.LENGTH_SHORT).show()
                            } else {
                                val domain = MediaClassifier.cleanDomainFromUrl(liveBrowserUrl)
                                pocketRepo.addBookmark(
                                    title = domain.ifBlank { liveBrowserUrl },
                                    url = liveBrowserUrl
                                )
                                savedToPocket = true
                                Toast.makeText(context, "Bookmark saved to Pocket", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.testTag("share_save_bookmark_btn")
                    ) {
                        Text(if (savedToPocket) "Saved" else "Bookmark")
                    }
                    TextButton(
                        onClick = {
                            BrowserSession.savedBrowserSite = liveBrowserUrl
                            showLiveBrowser = true
                        },
                        modifier = Modifier.testTag("share_open_browser_btn")
                    ) {
                        Text("Browser")
                    }
                    TextButton(
                        onClick = {
                            val mainIntent = Intent(context, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            context.startActivity(mainIntent)
                            onDismiss()
                        },
                        modifier = Modifier.testTag("share_open_library_btn")
                    ) {
                        Text("Library")
                    }
                }
                if (extractionState.isRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                DiscoveredMediaPage(
                    onBack = onDismiss,
                    onDeepScanClick = {
                        BrowserSession.savedBrowserSite = liveBrowserUrl
                        showLiveBrowser = true
                    },
                    isDeepScanning = extractionState.isRunning,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (showLiveBrowser) {
            DirectBrowserView(
                initialUrl = liveBrowserUrl,
                onClose = {
                    showLiveBrowser = false
                },
                onMediaExtracted = { item ->
                    DownloaderSession.addDiscoveredItem(item)
                },
                onBatchExtracted = { batch, title, _ ->
                    DownloaderSession.addDiscoveredItems(batch, title)
                },
                onOpenDownloads = {
                    onDownloadComplete()
                },
                onOpenDiscoveredMedia = {
                    showLiveBrowser = false
                },
                modifier = Modifier.fillMaxSize().zIndex(25f)
            )
        }
    }
}
