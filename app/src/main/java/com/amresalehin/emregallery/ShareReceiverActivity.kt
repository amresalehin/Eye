package com.amresalehin.emregallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amresalehin.emregallery.data.BrowserSession
import com.amresalehin.emregallery.data.DownloaderSession
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.PocketRepository
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.ui.DirectBrowserView
import com.amresalehin.emregallery.ui.theme.EmreGalleryTheme
import com.amresalehin.emregallery.ui.ytdlnis.UnifiedDownloaderTab
import com.amresalehin.emregallery.ui.ytdlnis.YtdlnisDownloadConfigCard
import com.amresalehin.emregallery.ui.ytdlnis.YtdlnisMonochromeTheme
import com.amresalehin.emregallery.ui.ytdlnis.YtdlnisScreen
import com.amresalehin.emregallery.ytdlnis.YtdlnisDownloadItem
import com.amresalehin.emregallery.ytdlnis.YtdlnisDownloadStatus
import com.amresalehin.emregallery.ytdlnis.YtdlnisDownloadType
import com.amresalehin.emregallery.ytdlnis.YtdlnisDownloadWorker
import com.amresalehin.emregallery.ytdlnis.YtdlnisRepository
import com.amresalehin.emregallery.ytdlnis.YtdlnisVideoInfo

/**
 * Translucent share-sheet target that opens directly over the sharing app
 * (e.g. Chrome, Twitter/X, Instagram, YouTube, Reddit) without switching applications.
 *
 * Matches the app's unified monochrome Downloader (Search, Queue with media kind & file type
 * filters, in-queue search, fast scrollbar, Browser, and Settings).
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
        // YTDLnis-compatible Tasker / third-party automation intent support (TYPE + BACKGROUND)
        val backgroundExtra = intent?.getBooleanExtra("BACKGROUND", false) == true ||
            intent?.getStringExtra("BACKGROUND").equals("true", ignoreCase = true)
        if (backgroundExtra) {
            val typeExtra = YtdlnisDownloadType.fromId(intent?.getStringExtra("TYPE"))
            val repo = YtdlnisRepository.getInstance(applicationContext)
            val bgItem = YtdlnisDownloadItem(
                url = extracted,
                title = extracted,
                type = typeExtra,
                container = if (typeExtra == YtdlnisDownloadType.AUDIO) "m4a" else "mp4",
                filenameTemplate = repo.defaultFilenameTemplate.value,
                subfolderMode = repo.defaultSubfolderMode.value,
                incognito = repo.incognitoMode.value
            )
            YtdlnisDownloadWorker.enqueue(applicationContext, bgItem)
            Toast.makeText(this, "YTDLnis background ${typeExtra.label} download started", Toast.LENGTH_SHORT).show()
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
                YtdlnisMonochromeTheme {
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
    var showYtdlnisCard by remember { mutableStateOf(false) }
    var liveBrowserUrl by remember(url) { mutableStateOf(url) }
    val context = LocalContext.current

    LaunchedEffect(url) {
        liveBrowserUrl = url
    }

    BackHandler {
        when {
            showLiveBrowser -> showLiveBrowser = false
            showYtdlnisCard -> showYtdlnisCard = false
            else -> onDismiss()
        }
    }

    val cleanDomain = remember(liveBrowserUrl) {
        MediaClassifier.cleanDomainFromUrl(liveBrowserUrl).ifBlank { "Shared Link" }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .testTag("share_receiver_root"),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 28.dp,
                topEnd = 28.dp
            ),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 6.dp,
            shadowElevation = 20.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            ),
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 44.dp)
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
                        .padding(top = 10.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(38.dp)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                CircleShape
                            )
                    )
                }

                // Monochrome URL Receiver Quick Bar matching the Downloader pill aesthetic
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Shared domain badge
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = cleanDomain,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        var savedToPocket by remember(liveBrowserUrl) { mutableStateOf(false) }
                        ReceiverActionPill(
                            label = if (savedToPocket) "Saved" else "Bookmark",
                            icon = if (savedToPocket) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            selected = savedToPocket,
                            onClick = {
                                val pocketRepo = PocketRepository.getInstance(context)
                                val existing = pocketRepo.findDuplicateBookmark(liveBrowserUrl)
                                if (existing != null) {
                                    savedToPocket = true
                                    Toast.makeText(
                                        context,
                                        "Already saved in Pocket: ${existing.title}",
                                        Toast.LENGTH_SHORT
                                    ).show()
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
                        )

                        ReceiverActionPill(
                            label = if (showYtdlnisCard) "Downloader" else "Studio",
                            icon = Icons.Default.Tune,
                            selected = showYtdlnisCard,
                            onClick = { showYtdlnisCard = !showYtdlnisCard },
                            modifier = Modifier.testTag("share_open_ytdlnis_btn")
                        )

                        ReceiverActionPill(
                            label = "Browser",
                            icon = Icons.Default.Language,
                            selected = showLiveBrowser,
                            onClick = {
                                BrowserSession.savedBrowserSite = liveBrowserUrl
                                showLiveBrowser = true
                            },
                            modifier = Modifier.testTag("share_open_browser_btn")
                        )

                        ReceiverActionPill(
                            label = "Library",
                            icon = Icons.Default.PhotoLibrary,
                            selected = false,
                            onClick = {
                                val mainIntent = Intent(context, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                                context.startActivity(mainIntent)
                                onDismiss()
                            },
                            modifier = Modifier.testTag("share_open_library_btn")
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("share_close_sheet_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close URL receiver",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    thickness = 0.5.dp
                )

                if (showYtdlnisCard) {
                    val ytdlnisRepo = remember(context) { YtdlnisRepository.getInstance(context) }
                    val ytdlnisTemplates by ytdlnisRepo.commandTemplates.collectAsState()
                    val ytdlnisDefTpl by ytdlnisRepo.defaultFilenameTemplate.collectAsState()
                    val ytdlnisDefSub by ytdlnisRepo.defaultSubfolderMode.collectAsState()
                    val ytdlnisIncog by ytdlnisRepo.incognitoMode.collectAsState()
                    YtdlnisDownloadConfigCard(
                        initialInfo = YtdlnisVideoInfo(
                            url = liveBrowserUrl,
                            title = MediaClassifier.cleanDomainFromUrl(liveBrowserUrl).ifBlank { liveBrowserUrl }
                        ),
                        templates = ytdlnisTemplates,
                        defaultFilenameTemplate = ytdlnisDefTpl,
                        defaultSubfolderMode = ytdlnisDefSub,
                        incognito = ytdlnisIncog,
                        onEnqueue = { item ->
                            YtdlnisDownloadWorker.enqueue(context, item)
                            showYtdlnisCard = false
                            Toast.makeText(
                                context,
                                if (item.scheduledTimeMillis > System.currentTimeMillis()) "Scheduled" else "Download started",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onSaveForLater = { item ->
                            ytdlnisRepo.upsertDownload(item.copy(status = YtdlnisDownloadStatus.SAVED))
                            showYtdlnisCard = false
                            Toast.makeText(context, "Saved for later in Queue", Toast.LENGTH_SHORT).show()
                        },
                        onDismiss = { showYtdlnisCard = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                    )
                } else {
                    // Embed the app's current unified Downloader (Search, Queue with Kind & File Type pills,
                    // in-queue search bar, fast scrollbar, Browser, and Power/Terminal Settings)
                    YtdlnisScreen(
                        onBack = onDismiss,
                        initialUrl = liveBrowserUrl,
                        initialTab = UnifiedDownloaderTab.ACTIVITY,
                        isEmbeddedSheet = true,
                        onOpenBrowser = { targetUrl ->
                            val resolved = targetUrl?.takeIf { it.isNotBlank() } ?: liveBrowserUrl
                            liveBrowserUrl = resolved
                            BrowserSession.savedBrowserSite = resolved
                            showLiveBrowser = true
                        },
                        onOpenDiscoveredMedia = {
                            BrowserSession.savedBrowserSite = liveBrowserUrl
                            showLiveBrowser = true
                        },
                        onOpenMediaFile = { uriString ->
                            runCatching {
                                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                    data = Uri.parse(uriString)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(viewIntent)
                            }.onFailure {
                                val mainIntent = Intent(context, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                                context.startActivity(mainIntent)
                                onDismiss()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
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
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(25f)
            )
        }
    }
}

@Composable
private fun ReceiverActionPill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) null else BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = modifier
            .height(28.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
