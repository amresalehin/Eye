package com.amresalehin.emregallery

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.amresalehin.emregallery.data.BrowserSession
import com.amresalehin.emregallery.data.DownloadActivityManager
import com.amresalehin.emregallery.data.DownloadQueueManager
import com.amresalehin.emregallery.data.DownloaderSession
import com.amresalehin.emregallery.data.ExtractionCoordinator
import com.amresalehin.emregallery.data.GalleryDlEngine
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.PocketRepository
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.ui.DirectBrowserView
import com.amresalehin.emregallery.ui.WebbyDiscoveredMediaGrid
import com.amresalehin.emregallery.ui.theme.EmreGalleryTheme
import com.amresalehin.emregallery.ui.ytdlnis.YtdlnisDownloadConfigCard
import com.amresalehin.emregallery.ui.ytdlnis.YtdlnisMonochromeTheme
import com.amresalehin.emregallery.ytdlnis.YtdlnisDownloadItem
import com.amresalehin.emregallery.ytdlnis.YtdlnisDownloadStatus
import com.amresalehin.emregallery.ytdlnis.YtdlnisDownloadType
import com.amresalehin.emregallery.ytdlnis.YtdlnisDownloadWorker
import com.amresalehin.emregallery.ytdlnis.YtdlnisEngine
import com.amresalehin.emregallery.ytdlnis.YtdlnisRepository
import com.amresalehin.emregallery.ytdlnis.YtdlnisVideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Translucent share-sheet target that opens directly over the sharing app
 * (e.g. Chrome, Twitter/X, Instagram, YouTube, Reddit) without switching applications.
 *
 * Matches the app's current monochrome Downloader design (`YtdlnisMonochromeTheme`,
 * `WebbyDiscoveredMediaGrid`, and `YtdlnisDownloadConfigCard`) while remaining a fast,
 * focused URL Receiver Sheet dedicated strictly to the shared link — with NO bottom
 * navigation bar, NO past downloads history, and NO recent search clutter.
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showLiveBrowser by remember { mutableStateOf(false) }
    var showYtdlnisCard by remember { mutableStateOf(false) }
    var liveBrowserUrl by remember(url) { mutableStateOf(url) }

    val ytdlnisRepo = remember(context) { YtdlnisRepository.getInstance(context) }
    val ytdlnisTemplates by ytdlnisRepo.commandTemplates.collectAsState()
    val ytdlnisDefTpl by ytdlnisRepo.defaultFilenameTemplate.collectAsState()
    val ytdlnisDefSub by ytdlnisRepo.defaultSubfolderMode.collectAsState()
    val ytdlnisIncog by ytdlnisRepo.incognitoMode.collectAsState()

    val extractionCoordinator = remember(context) { ExtractionCoordinator(context) }
    val extractionState by extractionCoordinator.state.collectAsState()
    val discoveredItems by DownloaderSession.discoveredItems.collectAsState()
    val sessionPageTitle by DownloaderSession.pageTitle.collectAsState()
    val sessionSourceDomain by DownloaderSession.sourceDomain.collectAsState()

    var probedVideoInfo by remember(liveBrowserUrl) { mutableStateOf<YtdlnisVideoInfo?>(null) }
    var isProbingStudio by remember(liveBrowserUrl) { mutableStateOf(false) }

    // Lightweight background initialization (no storage directory walk on share sheet startup!)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            DownloadActivityManager.initialize(context)
            GalleryDlEngine.init(context)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            extractionCoordinator.cancel()
        }
    }

    fun startFastExtractionAndProbe(targetUrl: String) {
        val cleanTarget = targetUrl.trim()
        if (cleanTarget.isBlank()) return
        DownloaderSession.startSession(cleanTarget)
        extractionCoordinator.extract(cleanTarget, scope)

        // Concurrently probe rich format/stream metadata for Studio & 1-tap download
        scope.launch(Dispatchers.IO) {
            isProbingStudio = true
            GalleryDlEngine.init(context)
            val result = YtdlnisEngine.fetchInfo(
                context = context,
                urlOrQuery = cleanTarget,
                allowPlaylist = true,
                flatPlaylist = true
            )
            if (result.success && result.entries.isNotEmpty()) {
                val first = result.entries.first()
                probedVideoInfo = first
                if (first.title.isNotBlank()) {
                    DownloaderSession.setPageTitle(first.title)
                }
            }
            isProbingStudio = false
        }
    }

    fun enqueueInstantDownload(targetUrl: String, downloadType: YtdlnisDownloadType = YtdlnisDownloadType.VIDEO) {
        val info = probedVideoInfo
        val dlItem = YtdlnisDownloadItem(
            url = info?.url?.takeIf { it.isNotBlank() } ?: targetUrl,
            title = info?.title?.takeIf { it.isNotBlank() }
                ?: sessionPageTitle.ifBlank { MediaClassifier.cleanDomainFromUrl(targetUrl).ifBlank { targetUrl } },
            author = info?.author.orEmpty(),
            durationSec = info?.durationSec ?: 0,
            durationString = info?.durationString.orEmpty(),
            thumbnailUrl = info?.thumbnail?.takeIf { it.isNotBlank() }
                ?: discoveredItems.firstOrNull()?.thumbnailUrl.orEmpty(),
            extractor = info?.extractor.orEmpty(),
            type = downloadType,
            container = if (downloadType == YtdlnisDownloadType.AUDIO) "m4a" else "mp4",
            filenameTemplate = ytdlnisDefTpl,
            subfolderMode = ytdlnisDefSub,
            incognito = ytdlnisIncog
        )
        YtdlnisDownloadWorker.enqueue(context, dlItem)
        onDownloadComplete()
    }

    LaunchedEffect(url) {
        liveBrowserUrl = url
        startFastExtractionAndProbe(url)
    }

    BackHandler {
        when {
            showLiveBrowser -> showLiveBrowser = false
            showYtdlnisCard -> showYtdlnisCard = false
            else -> onDismiss()
        }
    }

    val cleanDomain = remember(liveBrowserUrl, sessionSourceDomain) {
        sessionSourceDomain.ifBlank {
            MediaClassifier.cleanDomainFromUrl(liveBrowserUrl).ifBlank { "Shared Link" }
        }
    }

    val effectiveTitle = remember(probedVideoInfo, sessionPageTitle, extractionState.pageTitle, cleanDomain) {
        probedVideoInfo?.title?.takeIf { it.isNotBlank() }
            ?: sessionPageTitle.takeIf { it.isNotBlank() }
            ?: extractionState.pageTitle.takeIf { it.isNotBlank() }
            ?: cleanDomain
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
                .padding(top = 56.dp)
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

                // Monochrome Top Pill Bar matching the Downloader's 52.dp Floating Pill aesthetic
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 4.dp,
                        shadowElevation = 6.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 12.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (extractionState.isRunning || isProbingStudio) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = effectiveTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = buildString {
                                        append(cleanDomain)
                                        if (discoveredItems.isNotEmpty()) {
                                            append(" · ${discoveredItems.size} media")
                                        } else if (extractionState.isRunning) {
                                            append(" · Scanning…")
                                        } else if (probedVideoInfo != null) {
                                            append(" · Ready")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // 1-Tap Instant Download pill button
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .height(34.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        if (discoveredItems.size == 1 && probedVideoInfo == null) {
                                            val item = discoveredItems.first()
                                            DownloadQueueManager.enqueueDownload(
                                                context = context,
                                                item = item,
                                                referer = liveBrowserUrl,
                                                stackTitle = effectiveTitle,
                                                sourceDomain = cleanDomain,
                                                sourceUrl = liveBrowserUrl
                                            )
                                            onDownloadComplete()
                                        } else {
                                            enqueueInstantDownload(liveBrowserUrl, YtdlnisDownloadType.VIDEO)
                                        }
                                    }
                                    .testTag("share_instant_download_btn")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Bolt,
                                        contentDescription = "Instant Download",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "Get",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }

                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(34.dp)
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
                    }

                    // Monochrome Action Pills Row (Grid / Studio toggle, Audio quick button, Bookmark, Browser, Library)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ReceiverActionPill(
                            label = if (discoveredItems.isNotEmpty()) "Media (${discoveredItems.size})" else "Media Grid",
                            icon = Icons.Default.GridView,
                            selected = !showYtdlnisCard,
                            onClick = { showYtdlnisCard = false },
                            modifier = Modifier.testTag("share_mode_grid_btn")
                        )

                        ReceiverActionPill(
                            label = "Studio Formats",
                            icon = Icons.Default.Tune,
                            selected = showYtdlnisCard,
                            onClick = { showYtdlnisCard = !showYtdlnisCard },
                            modifier = Modifier.testTag("share_open_ytdlnis_btn")
                        )

                        ReceiverActionPill(
                            label = "Audio",
                            icon = Icons.Default.Audiotrack,
                            selected = false,
                            onClick = { enqueueInstantDownload(liveBrowserUrl, YtdlnisDownloadType.AUDIO) },
                            modifier = Modifier.testTag("share_quick_audio_btn")
                        )

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
                                        title = effectiveTitle.ifBlank { domain.ifBlank { liveBrowserUrl } },
                                        url = liveBrowserUrl
                                    )
                                    savedToPocket = true
                                    Toast.makeText(context, "Bookmark saved to Pocket", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.testTag("share_save_bookmark_btn")
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

                    // Thin monochrome progress bar while active
                    AnimatedVisibility(visible = extractionState.isRunning || isProbingStudio) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            if (extractionState.statusMessage.isNotBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = extractionState.statusMessage,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (extractionState.isRunning) {
                                        Text(
                                            text = "Stop",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable { extractionCoordinator.cancel() }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    thickness = 0.5.dp
                )

                // Focused Share Sheet Content: ONLY the shared URL's media grid or Studio format card
                // (No bottom navigation bar, no past downloads, no recent searches)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                ) {
                    when {
                        showYtdlnisCard -> {
                            val studioInfo = remember(probedVideoInfo, liveBrowserUrl, effectiveTitle, discoveredItems) {
                                probedVideoInfo ?: YtdlnisVideoInfo(
                                    url = liveBrowserUrl,
                                    title = effectiveTitle,
                                    thumbnail = discoveredItems.firstOrNull()?.thumbnailUrl
                                        ?: discoveredItems.firstOrNull()?.url.orEmpty()
                                )
                            }
                            YtdlnisDownloadConfigCard(
                                initialInfo = studioInfo,
                                templates = ytdlnisTemplates,
                                defaultFilenameTemplate = ytdlnisDefTpl,
                                defaultSubfolderMode = ytdlnisDefSub,
                                incognito = ytdlnisIncog,
                                onEnqueue = { item ->
                                    YtdlnisDownloadWorker.enqueue(context, item)
                                    onDownloadComplete()
                                },
                                onSaveForLater = { item ->
                                    ytdlnisRepo.upsertDownload(item.copy(status = YtdlnisDownloadStatus.SAVED))
                                    Toast.makeText(context, "Saved for later in Queue", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                },
                                onDismiss = { showYtdlnisCard = false },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        discoveredItems.isNotEmpty() -> {
                            WebbyDiscoveredMediaGrid(
                                items = discoveredItems,
                                onDownloadSingle = { item ->
                                    DownloadQueueManager.enqueueDownload(
                                        context = context,
                                        item = item,
                                        referer = liveBrowserUrl,
                                        stackTitle = effectiveTitle.ifBlank { null },
                                        sourceDomain = cleanDomain.ifBlank { null },
                                        sourceUrl = liveBrowserUrl
                                    )
                                    onDownloadComplete()
                                },
                                onDownloadSelected = { selectedList ->
                                    if (selectedList.isNotEmpty()) {
                                        DownloadQueueManager.enqueueBatch(
                                            context = context,
                                            items = selectedList,
                                            referer = liveBrowserUrl,
                                            stackTitle = effectiveTitle.ifBlank { "Shared Media" },
                                            sourceDomain = cleanDomain.ifBlank { "Web" },
                                            sourceUrl = liveBrowserUrl
                                        )
                                        onDownloadComplete()
                                    }
                                },
                                onOpenInStudio = { itemUrl ->
                                    if (itemUrl != liveBrowserUrl) {
                                        liveBrowserUrl = itemUrl
                                    }
                                    showYtdlnisCard = true
                                },
                                onClearAll = null,
                                searchHistory = emptyList(),
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        else -> {
                            ShareUrlFocusedStatusCard(
                                url = liveBrowserUrl,
                                cleanDomain = cleanDomain,
                                effectiveTitle = effectiveTitle,
                                probedVideoInfo = probedVideoInfo,
                                isScanning = extractionState.isRunning || isProbingStudio,
                                statusMessage = extractionState.statusMessage,
                                errorMessage = extractionState.errorMessage,
                                requiresSignIn = extractionState.requiresSignIn,
                                onInstantDownloadVideo = {
                                    enqueueInstantDownload(liveBrowserUrl, YtdlnisDownloadType.VIDEO)
                                },
                                onInstantDownloadAudio = {
                                    enqueueInstantDownload(liveBrowserUrl, YtdlnisDownloadType.AUDIO)
                                },
                                onOpenStudio = {
                                    showYtdlnisCard = true
                                },
                                onOpenBrowser = {
                                    val signInTarget = extractionState.signInUrl?.takeIf { it.isNotBlank() } ?: liveBrowserUrl
                                    liveBrowserUrl = signInTarget
                                    BrowserSession.savedBrowserSite = signInTarget
                                    showLiveBrowser = true
                                },
                                onRetryScan = {
                                    startFastExtractionAndProbe(liveBrowserUrl)
                                }
                            )
                        }
                    }
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

/**
 * Focused monochrome card displayed in the Share Sheet before grid items arrive
 * or when a direct video/stream/sign-in action is ready — never showing past downloads
 * or recent search history.
 */
@Composable
private fun ShareUrlFocusedStatusCard(
    url: String,
    cleanDomain: String,
    effectiveTitle: String,
    probedVideoInfo: YtdlnisVideoInfo?,
    isScanning: Boolean,
    statusMessage: String,
    errorMessage: String?,
    requiresSignIn: Boolean,
    onInstantDownloadVideo: () -> Unit,
    onInstantDownloadAudio: () -> Unit,
    onOpenStudio: () -> Unit,
    onOpenBrowser: () -> Unit,
    onRetryScan: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("share_focused_status_card"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Shared URL Hero Preview Card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val thumb = probedVideoInfo?.thumbnail.orEmpty()
                    if (thumb.isNotBlank()) {
                        AsyncImage(
                            model = thumb,
                            contentDescription = effectiveTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 88.dp, height = 62.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(width = 64.dp, height = 62.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (isScanning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Link,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = effectiveTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        val metaLine = buildString {
                            append(cleanDomain)
                            probedVideoInfo?.author?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                            probedVideoInfo?.durationString?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                        }
                        Text(
                            text = metaLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Live status or diagnostic message
                val displayStatus = when {
                    !errorMessage.isNullOrBlank() && !isScanning -> errorMessage
                    isScanning && statusMessage.isNotBlank() -> statusMessage
                    isScanning -> "Extracting downloadable media from $cleanDomain…"
                    probedVideoInfo != null -> "Stream metadata ready (${probedVideoInfo.formats.size} formats)"
                    else -> "Ready to download or inspect in Studio"
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = displayStatus,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (!isScanning) {
                            IconButton(
                                onClick = onRetryScan,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Retry scan",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Direct 1-Tap Actions for the Shared URL
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onInstantDownloadVideo,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Download Video",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onInstantDownloadAudio,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Audiotrack,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Audio",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenStudio,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (probedVideoInfo != null && probedVideoInfo.formats.isNotEmpty()) {
                                "Format & Quality (${probedVideoInfo.formats.size})"
                            } else {
                                "Format & Quality (Studio)"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    OutlinedButton(
                        onClick = onOpenBrowser,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.height(42.dp)
                    ) {
                        Icon(
                            imageVector = if (requiresSignIn) Icons.Default.LockOpen else Icons.Default.Language,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (requiresSignIn) "Sign In" else "Browser",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
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
            .height(30.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
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
