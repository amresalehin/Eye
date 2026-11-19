package com.amresalehin.emregallery.ui.ytdlnis

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amresalehin.emregallery.data.DownloadActivityItem
import com.amresalehin.emregallery.data.DownloadActivityManager
import com.amresalehin.emregallery.data.DownloadActivityStatus
import com.amresalehin.emregallery.data.DownloadQueueManager
import com.amresalehin.emregallery.data.DownloaderSession
import com.amresalehin.emregallery.data.ExtractionCoordinator
import com.amresalehin.emregallery.data.GalleryDlEngine
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.PlatformHealthResult
import com.amresalehin.emregallery.data.PlatformNetworkMonitor
import com.amresalehin.emregallery.data.PlatformReachability
import com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager
import com.amresalehin.emregallery.ui.WebbyDiscoveredMediaGrid
import com.amresalehin.emregallery.ui.getDisplayFormatBadge
import com.amresalehin.emregallery.ytdlnis.*
import kotlinx.coroutines.launch

enum class UnifiedDownloaderTab(val label: String) {
    ACTIVITY("Queue"),
    STUDIO("Studio"),
    TERMINAL("Terminal"),
    POWER("Power")
}

private enum class DownloaderCanvasMode {
    QUEUE,
    GRID,
    SETTINGS
}

private enum class UtilitySheetMode {
    POWER,
    TERMINAL
}

private enum class UnifiedActivityFilter(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    SCHEDULED("Scheduled"),
    FAILED("Failed"),
    FINISHED("Done"),
    SAVED("Saved")
}

/**
 * Visual-First Minimal Monochrome Downloader Hub.
 * Combines DBase-style visual download queue + Studio Format Bottom Sheet with
 * Webby-Images-Downloader-style 3-column numbered thumbnail grid and fullscreen zoom viewer.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun YtdlnisScreen(
    onBack: () -> Unit,
    initialUrl: String = "",
    initialTab: UnifiedDownloaderTab = UnifiedDownloaderTab.ACTIVITY,
    onOpenBrowser: ((String?) -> Unit)? = null,
    onOpenBrowserForCookies: ((String) -> Unit)? = null,
    onOpenDiscoveredMedia: () -> Unit = {},
    onOpenMediaFile: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val repo = remember { YtdlnisRepository.getInstance(context) }
    val prefsManager = remember { DownloaderPreferencesManager(context) }
    val extractionCoordinator = remember { ExtractionCoordinator(context) }

    LaunchedEffect(Unit) {
        DownloadActivityManager.initialize(context)
        DownloadActivityManager.syncWithStorageDir(context)
        DownloadActivityManager.syncWithInboxRepository()
        GalleryDlEngine.init(context)
    }

    DisposableEffect(Unit) {
        onDispose {
            extractionCoordinator.cancel()
        }
    }

    val ytdlnisDownloads by repo.downloads.collectAsState()
    val nativeActivities by DownloadActivityManager.activities.collectAsState()
    val discoveredItems by DownloaderSession.discoveredItems.collectAsState()
    val sessionPageTitle by DownloaderSession.pageTitle.collectAsState()
    val sessionSourceDomain by DownloaderSession.sourceDomain.collectAsState()
    val sessionSourceUrl by DownloaderSession.currentUrl.collectAsState()
    val extractionState by extractionCoordinator.state.collectAsState()
    val templates by repo.commandTemplates.collectAsState()
    val cookies by repo.cookies.collectAsState()
    val observeSources by repo.observeSources.collectAsState()
    val searchHistory by repo.searchHistory.collectAsState()

    val quickDownload by repo.quickDownloadMode.collectAsState()
    val incognito by repo.incognitoMode.collectAsState()
    val filenameTemplate by repo.defaultFilenameTemplate.collectAsState()
    val subfolderMode by repo.defaultSubfolderMode.collectAsState()
    val poToken by repo.youtubePoToken.collectAsState()

    val speedBoostEnabled by prefsManager.speedBoostEnabled.collectAsState(initial = true)
    val recursiveCrawlEnabled by prefsManager.recursiveCrawlEnabled.collectAsState(initial = false)

    var canvasMode by remember(initialTab) {
        mutableStateOf(
            when (initialTab) {
                UnifiedDownloaderTab.TERMINAL, UnifiedDownloaderTab.POWER -> DownloaderCanvasMode.SETTINGS
                else -> DownloaderCanvasMode.GRID
            }
        )
    }
    var urlInput by remember(initialUrl) { mutableStateOf(initialUrl) }
    var isFetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }

    // On-demand bottom sheet states
    var activeConfigInfo by remember { mutableStateOf<YtdlnisVideoInfo?>(null) }
    var playlistEntries by remember { mutableStateOf<List<YtdlnisVideoInfo>>(emptyList()) }
    var selectedPlaylistUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var playlistFilterQuery by remember { mutableStateOf("") }
    var playlistSortOrder by remember { mutableStateOf("Default") }
    var bulkDownloadType by remember { mutableStateOf(YtdlnisDownloadType.VIDEO) }

    var utilitySheetMode by remember(initialTab) {
        mutableStateOf(
            if (initialTab == UnifiedDownloaderTab.TERMINAL) UtilitySheetMode.TERMINAL
            else UtilitySheetMode.POWER
        )
    }

    var logViewerItem by remember { mutableStateOf<YtdlnisDownloadItem?>(null) }

    fun handleBackNavigation() {
        when {
            activeConfigInfo != null -> activeConfigInfo = null
            playlistEntries.isNotEmpty() -> {
                playlistEntries = emptyList()
                selectedPlaylistUrls = emptySet()
            }
            canvasMode != DownloaderCanvasMode.GRID -> {
                canvasMode = DownloaderCanvasMode.GRID
            }
            extractionState.isRunning || isFetching -> {
                extractionCoordinator.cancel()
                isFetching = false
            }
            discoveredItems.isNotEmpty() || sessionSourceUrl.isNotBlank() || urlInput.isNotBlank() || !fetchError.isNullOrBlank() -> {
                extractionCoordinator.cancel()
                DownloaderSession.clear()
                urlInput = ""
                fetchError = null
            }
            else -> onBack()
        }
    }

    BackHandler {
        handleBackNavigation()
    }

    fun triggerStudioFetch(rawInput: String) {
        val raw = rawInput.trim()
        if (raw.isBlank()) return
        keyboardController?.hide()
        fetchError = null
        activeConfigInfo = null
        playlistEntries = emptyList()

        val bulkLines = YtdlnisEngine.parseBulkInputsLocal(raw).map { it.second }
        val normalizedSingle = if (bulkLines.size <= 1) {
            MediaClassifier.normalizeRedditShorthand(raw)
                ?: if (!raw.contains(' ') && MediaClassifier.isValidWebUrl(raw)) MediaClassifier.sanitizeUrl(raw)
                else raw
        } else raw

        repo.addSearchHistory(normalizedSingle)

        isFetching = true
        scope.launch {
            if (bulkLines.size > 1) {
                val results = mutableListOf<YtdlnisVideoInfo>()
                for (target in bulkLines.take(25)) {
                    val fetched = YtdlnisEngine.fetchInfo(context, target)
                    if (fetched.success) {
                        results.addAll(fetched.entries)
                    }
                }
                isFetching = false
                if (results.isEmpty()) {
                    fetchError = "No downloadable streams found in bulk input."
                } else if (results.size == 1) {
                    activeConfigInfo = results.first()
                } else {
                    playlistEntries = results
                    selectedPlaylistUrls = results.map { it.url }.toSet()
                }
            } else {
                val res = YtdlnisEngine.fetchInfo(context, normalizedSingle)
                isFetching = false
                if (res.success && res.entries.isNotEmpty()) {
                    val list = res.entries
                    when {
                        list.size == 1 && quickDownload -> {
                            val entry = list.first()
                            val targetUrl = entry.url.ifBlank { normalizedSingle }
                            val dup = repo.findDuplicate(targetUrl, YtdlnisDownloadType.VIDEO)
                            if (dup != null) {
                                Toast.makeText(context, "Already in queue: ${dup.title}", Toast.LENGTH_SHORT).show()
                            }
                            val item = YtdlnisDownloadItem(
                                url = targetUrl,
                                title = entry.title.ifBlank { targetUrl.substringAfterLast('/').ifBlank { "Instant Download" } },
                                author = entry.author,
                                durationSec = entry.durationSec,
                                durationString = entry.durationString,
                                thumbnailUrl = entry.thumbnail,
                                extractor = entry.extractor,
                                uploadDate = entry.uploadDate,
                                type = YtdlnisDownloadType.VIDEO,
                                filenameTemplate = filenameTemplate,
                                subfolderMode = subfolderMode,
                                incognito = incognito
                            )
                            YtdlnisDownloadWorker.enqueue(context, item)
                            Toast.makeText(context, "Instant Download queued", Toast.LENGTH_SHORT).show()
                            canvasMode = DownloaderCanvasMode.QUEUE
                        }
                        list.size == 1 -> activeConfigInfo = list.first()
                        else -> {
                            playlistEntries = list
                            selectedPlaylistUrls = list.map { it.url }.toSet()
                        }
                    }
                } else {
                    // Automatically fall back to Multi-Engine Grid Scan if yt-dlp returns an error on a gallery/board page
                    if (MediaClassifier.isValidWebUrl(normalizedSingle) || MediaClassifier.isRedditShorthand(raw)) {
                        val sanitized = MediaClassifier.normalizeRedditShorthand(raw)
                            ?: MediaClassifier.sanitizeUrl(normalizedSingle)
                        DownloaderSession.startSession(sanitized)
                        extractionCoordinator.extract(sanitized, scope)
                        canvasMode = DownloaderCanvasMode.GRID
                    } else {
                        fetchError = res.errorMessage ?: "Failed to fetch media info"
                    }
                }
            }
        }
    }

    fun triggerMultiEngineGridScan(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return
        keyboardController?.hide()
        fetchError = null

        val targetUrl = MediaClassifier.normalizeRedditShorthand(trimmed)
            ?: MediaClassifier.extractFirstUrl(trimmed)
            ?: if (MediaClassifier.isValidWebUrl(trimmed)) MediaClassifier.sanitizeUrl(trimmed) else null

        if (targetUrl == null) {
            triggerStudioFetch(trimmed)
            return
        }

        repo.addSearchHistory(targetUrl)
        DownloaderSession.startSession(targetUrl)
        extractionCoordinator.extract(targetUrl, scope)
        canvasMode = DownloaderCanvasMode.GRID
    }

    LaunchedEffect(initialUrl, initialTab) {
        if (initialUrl.isNotBlank()) {
            urlInput = initialUrl
            triggerStudioFetch(initialUrl)
        }
    }

    val txtFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (text.isNotBlank()) {
                    urlInput = text
                    triggerStudioFetch(text)
                }
            }.onFailure {
                Toast.makeText(context, "Could not read .txt file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val deduplicatedNative = remember(nativeActivities) {
        nativeActivities.distinctBy { it.id }
    }
    val activeQueueCount = remember(deduplicatedNative) {
        deduplicatedNative.count { it.isDownloading || it.isRetrying || it.isPaused }
    }
    val failedQueueCount = remember(deduplicatedNative) {
        deduplicatedNative.count { it.isFailed }
    }
    val finishedQueueCount = remember(deduplicatedNative) {
        deduplicatedNative.count { it.isFinished }
    }
    val queueBadgeCount = remember(deduplicatedNative, ytdlnisDownloads) {
        val scheduledOrSaved = ytdlnisDownloads.count {
            it.status == YtdlnisDownloadStatus.SCHEDULED || it.status == YtdlnisDownloadStatus.SAVED
        }
        deduplicatedNative.size + scheduledOrSaved
    }

    YtdlnisMonochromeTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Adaptive Top Pill Bar matching the main Home page FloatingSearchPill shape & size (52.dp CircleShape)
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 4.dp,
                        shadowElevation = 8.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        when (canvasMode) {
                            DownloaderCanvasMode.GRID -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(start = 4.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = { handleBackNavigation() },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back",
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (urlInput.isEmpty()) {
                                            Text(
                                                text = "Paste URL, search, or r/sub…",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        BasicTextField(
                                            value = urlInput,
                                            onValueChange = { urlInput = it },
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                            keyboardActions = KeyboardActions(
                                                onGo = {
                                                    if (!quickDownload) {
                                                        triggerMultiEngineGridScan(urlInput)
                                                    } else {
                                                        triggerStudioFetch(urlInput)
                                                    }
                                                }
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    if (urlInput.isNotEmpty()) {
                                        IconButton(
                                            onClick = {
                                                urlInput = ""
                                                fetchError = null
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Clear,
                                                contentDescription = "Clear",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        IconButton(
                                            onClick = { txtFilePicker.launch("text/plain") },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.PostAdd,
                                                contentDescription = "Import .txt",
                                                modifier = Modifier.size(17.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    SmallLightningToggleButton(
                                        enabled = quickDownload,
                                        onToggle = {
                                            repo.setQuickDownloadMode(!quickDownload)
                                            Toast.makeText(
                                                context,
                                                if (!quickDownload) "Instant Download On" else "Instant Download Off (Studio Mode)",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )

                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .height(36.dp)
                                            .clip(CircleShape)
                                            .clickable(enabled = urlInput.isNotBlank() && !isFetching) {
                                                if (!quickDownload) {
                                                    triggerMultiEngineGridScan(urlInput)
                                                } else {
                                                    triggerStudioFetch(urlInput)
                                                }
                                            }
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(horizontal = 14.dp)
                                        ) {
                                            Text(
                                                text = "Get",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        }
                                    }
                                }
                            }

                            DownloaderCanvasMode.QUEUE -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(start = 4.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    IconButton(
                                        onClick = { handleBackNavigation() },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back",
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Queue",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (queueBadgeCount > 0) {
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.secondaryContainer
                                            ) {
                                                Text(
                                                    text = if (activeQueueCount > 0) "$activeQueueCount active" else "$queueBadgeCount",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }

                                    if (failedQueueCount > 0) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceContainer,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                                            modifier = Modifier
                                                .height(32.dp)
                                                .clip(CircleShape)
                                                .clickable { DownloadActivityManager.retryAllFailed(context) }
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                                                Text(
                                                    text = "Retry",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }

                                    if (activeQueueCount > 0) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceContainer,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                                            modifier = Modifier
                                                .height(32.dp)
                                                .clip(CircleShape)
                                                .clickable { DownloadActivityManager.cancelAllActive(context) }
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                                                Text(
                                                    text = "Stop",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }

                                    if (finishedQueueCount > 0) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceContainer,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                                            modifier = Modifier
                                                .height(32.dp)
                                                .clip(CircleShape)
                                                .clickable {
                                                    DownloadActivityManager.clearFinished()
                                                    repo.clearCompletedAndCancelled()
                                                }
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                                                Text(
                                                    text = "Clear",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            DownloaderCanvasMode.SETTINGS -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(start = 4.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(
                                        onClick = { handleBackNavigation() },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back",
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    Text(
                                        text = "Settings",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )

                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                    ) {
                                        Row(modifier = Modifier.padding(2.dp)) {
                                            listOf(
                                                UtilitySheetMode.POWER to "Power",
                                                UtilitySheetMode.TERMINAL to "Terminal"
                                            ).forEach { (mode, label) ->
                                                val selected = utilitySheetMode == mode
                                                Surface(
                                                    shape = CircleShape,
                                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                    modifier = Modifier
                                                        .height(32.dp)
                                                        .clip(CircleShape)
                                                        .clickable { utilitySheetMode = mode }
                                                ) {
                                                    Box(
                                                        contentAlignment = Alignment.Center,
                                                        modifier = Modifier.padding(horizontal = 12.dp)
                                                    ) {
                                                        Text(
                                                            text = label,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Thin progress indicator when probing formats or scanning site in Grid
                    AnimatedVisibility(visible = canvasMode == DownloaderCanvasMode.GRID && (isFetching || extractionState.isRunning)) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            if (extractionState.isRunning && extractionState.statusMessage.isNotBlank()) {
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

                    if (canvasMode == DownloaderCanvasMode.GRID && !fetchError.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = fetchError.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "Scan Grid →",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .clickable { triggerMultiEngineGridScan(urlInput) }
                                )
                            }
                        }
                    }
                }
            },
            bottomBar = {
                DownloaderBottomNavigationBar(
                    canvasMode = canvasMode,
                    queueCount = queueBadgeCount,
                    gridCount = discoveredItems.size,
                    onSelectQueue = { canvasMode = DownloaderCanvasMode.QUEUE },
                    onSelectGrid = { canvasMode = DownloaderCanvasMode.GRID },
                    onSelectBrowser = {
                        val seed = urlInput.trim().takeIf { MediaClassifier.isValidWebUrl(it) }
                        if (onOpenBrowser != null) {
                            onOpenBrowser(seed)
                        } else {
                            onOpenDiscoveredMedia()
                        }
                    },
                    onSelectSettings = { canvasMode = DownloaderCanvasMode.SETTINGS }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (canvasMode) {
                    DownloaderCanvasMode.QUEUE -> {
                        VisualQueueCanvas(
                            nativeActivities = nativeActivities,
                            ytdlnisDownloads = ytdlnisDownloads,
                            searchHistory = searchHistory,
                            onOpenInStudio = { targetUrl ->
                                urlInput = targetUrl
                                triggerStudioFetch(targetUrl)
                            },
                            onShowLogs = { logViewerItem = it },
                            onOpenMediaFile = onOpenMediaFile,
                            onClearHistory = { repo.clearSearchHistory() }
                        )
                    }

                    DownloaderCanvasMode.GRID -> {
                        WebbyDiscoveredMediaGrid(
                            items = discoveredItems,
                            onDownloadSingle = { item ->
                                DownloadQueueManager.enqueueDownload(
                                    context = context,
                                    item = item,
                                    referer = sessionSourceUrl.ifBlank { null },
                                    stackTitle = sessionPageTitle.ifBlank { null },
                                    sourceDomain = sessionSourceDomain.ifBlank { null },
                                    sourceUrl = sessionSourceUrl.ifBlank { null }
                                )
                                Toast.makeText(context, "Queued ${item.title}", Toast.LENGTH_SHORT).show()
                            },
                            onDownloadSelected = { selectedList ->
                                if (selectedList.isNotEmpty()) {
                                    DownloadQueueManager.enqueueBatch(
                                        context = context,
                                        items = selectedList,
                                        referer = sessionSourceUrl.ifBlank { null },
                                        stackTitle = sessionPageTitle.ifBlank { "Downloaded Media" },
                                        sourceDomain = sessionSourceDomain.ifBlank { "Web" },
                                        sourceUrl = sessionSourceUrl
                                    )
                                    Toast.makeText(context, "Queued ${selectedList.size} items", Toast.LENGTH_SHORT).show()
                                    canvasMode = DownloaderCanvasMode.QUEUE
                                }
                            },
                            onOpenInStudio = { itemUrl ->
                                urlInput = itemUrl
                                triggerStudioFetch(itemUrl)
                            },
                            onClearAll = { DownloaderSession.clear() },
                            searchHistory = searchHistory,
                            onSelectHistoryUrl = { targetUrl ->
                                urlInput = targetUrl
                                triggerMultiEngineGridScan(targetUrl)
                            },
                            onClearHistory = { repo.clearSearchHistory() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    DownloaderCanvasMode.SETTINGS -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            when (utilitySheetMode) {
                                UtilitySheetMode.POWER -> UnifiedPowerSuiteTab(
                                    repo = repo,
                                    cookies = cookies,
                                    observeSources = observeSources,
                                    filenameTemplate = filenameTemplate,
                                    subfolderMode = subfolderMode,
                                    poToken = poToken,
                                    incognito = incognito,
                                    speedBoostEnabled = speedBoostEnabled,
                                    recursiveCrawlEnabled = recursiveCrawlEnabled,
                                    onFilenameTemplateChange = { repo.setDefaultFilenameTemplate(it) },
                                    onSubfolderModeChange = { repo.setDefaultSubfolderMode(it) },
                                    onPoTokenChange = { repo.setYoutubePoToken(it) },
                                    onToggleIncognito = { repo.setIncognitoMode(it) },
                                    onToggleSpeedBoost = { enabled ->
                                        scope.launch { prefsManager.setSpeedBoostEnabled(enabled) }
                                    },
                                    onToggleRecursiveCrawl = { enabled ->
                                        scope.launch { prefsManager.setRecursiveCrawlEnabled(enabled) }
                                    }
                                )

                                UtilitySheetMode.TERMINAL -> YtdlnisTerminalTab(
                                    repo = repo,
                                    templates = templates
                                )
                            }
                        }
                    }
                }
            }
        }

        // 1. On-Demand Studio Format & Quality Bottom Sheet
        if (activeConfigInfo != null) {
            ModalBottomSheet(
                onDismissRequest = { activeConfigInfo = null },
                containerColor = MaterialTheme.colorScheme.background,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                YtdlnisDownloadConfigCard(
                    initialInfo = activeConfigInfo!!,
                    templates = templates,
                    defaultFilenameTemplate = filenameTemplate,
                    defaultSubfolderMode = subfolderMode,
                    incognito = incognito,
                    onEnqueue = { item ->
                        YtdlnisDownloadWorker.enqueue(context, item)
                        Toast.makeText(
                            context,
                            if (item.scheduledTimeMillis > System.currentTimeMillis()) "Scheduled" else "Download started",
                            Toast.LENGTH_SHORT
                        ).show()
                        activeConfigInfo = null
                        canvasMode = DownloaderCanvasMode.QUEUE
                    },
                    onSaveForLater = { item ->
                        repo.upsertDownload(item.copy(status = YtdlnisDownloadStatus.SAVED))
                        Toast.makeText(context, "Saved for later", Toast.LENGTH_SHORT).show()
                        activeConfigInfo = null
                        canvasMode = DownloaderCanvasMode.QUEUE
                    },
                    onDismiss = { activeConfigInfo = null },
                    modifier = Modifier.navigationBarsPadding()
                )
            }
        }

        // 2. On-Demand Playlist / Batch Selection Bottom Sheet
        if (playlistEntries.isNotEmpty()) {
            ModalBottomSheet(
                onDismissRequest = {
                    playlistEntries = emptyList()
                    selectedPlaylistUrls = emptySet()
                },
                containerColor = MaterialTheme.colorScheme.background,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                VisualPlaylistBatchSheet(
                    playlistEntries = playlistEntries,
                    selectedPlaylistUrls = selectedPlaylistUrls,
                    playlistFilterQuery = playlistFilterQuery,
                    playlistSortOrder = playlistSortOrder,
                    bulkDownloadType = bulkDownloadType,
                    onFilterChange = { playlistFilterQuery = it },
                    onSortChange = { playlistSortOrder = it },
                    onToggleUrl = { u ->
                        selectedPlaylistUrls = if (u in selectedPlaylistUrls) selectedPlaylistUrls - u
                        else selectedPlaylistUrls + u
                    },
                    onSelectAll = { all -> selectedPlaylistUrls = all },
                    onBulkTypeChange = { bulkDownloadType = it },
                    onOpenSingleStudio = { entry ->
                        playlistEntries = emptyList()
                        selectedPlaylistUrls = emptySet()
                        activeConfigInfo = entry
                    },
                    onEnqueueSelected = { itemsToQueue ->
                        itemsToQueue.forEach { entry ->
                            val dlItem = YtdlnisDownloadItem(
                                url = entry.url,
                                title = entry.title,
                                author = entry.author,
                                durationSec = entry.durationSec,
                                durationString = entry.durationString,
                                thumbnailUrl = entry.thumbnail,
                                extractor = entry.extractor,
                                playlistTitle = entry.playlistTitle,
                                type = bulkDownloadType,
                                container = if (bulkDownloadType == YtdlnisDownloadType.AUDIO) "m4a" else "mp4",
                                filenameTemplate = filenameTemplate,
                                subfolderMode = YtdlnisSubfolderMode.BY_PLAYLIST,
                                incognito = incognito
                            )
                            YtdlnisDownloadWorker.enqueue(context, dlItem)
                        }
                        Toast.makeText(context, "Queued ${itemsToQueue.size} items", Toast.LENGTH_SHORT).show()
                        playlistEntries = emptyList()
                        selectedPlaylistUrls = emptySet()
                        canvasMode = DownloaderCanvasMode.QUEUE
                    },
                    onDismiss = {
                        playlistEntries = emptyList()
                        selectedPlaylistUrls = emptySet()
                    }
                )
            }
        }

        // Engine Log Dialog
        if (logViewerItem != null) {
            val item = logViewerItem!!
            AlertDialog(
                onDismissRequest = { logViewerItem = null },
                title = { Text(item.title.take(28), style = MaterialTheme.typography.titleSmall) },
                text = {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = item.logOutput.ifBlank { item.errorMessage ?: "No log output." },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { logViewerItem = null }) { Text("Close") }
                }
            )
        }
    }
}

@Composable
private fun SmallLightningToggleButton(
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val bgColor = if (enabled) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val iconColor = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val maskColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Surface(
        shape = CircleShape,
        color = bgColor,
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(onClick = onToggle)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    if (!enabled) {
                        val pad = size.minDimension * 0.22f
                        val start = Offset(pad, pad)
                        val end = Offset(size.width - pad, size.height - pad)
                        // Draw background mask line first so the lightning bolt looks cleanly divided by the slash
                        drawLine(
                            color = maskColor,
                            start = start,
                            end = end,
                            strokeWidth = 3.8.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = iconColor,
                            start = start,
                            end = end,
                            strokeWidth = 1.6.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
        ) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = if (enabled) "Instant Download On" else "Instant Download Off",
                modifier = Modifier.size(16.dp),
                tint = iconColor
            )
        }
    }
}

@Composable
private fun DownloaderBottomNavigationBar(
    canvasMode: DownloaderCanvasMode,
    queueCount: Int,
    gridCount: Int,
    onSelectQueue: () -> Unit,
    onSelectGrid: () -> Unit,
    onSelectBrowser: () -> Unit,
    onSelectSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomNavPillButton(
                    label = if (gridCount > 0) "Grid ($gridCount)" else "Grid",
                    icon = Icons.Default.GridView,
                    selected = canvasMode == DownloaderCanvasMode.GRID,
                    onClick = onSelectGrid,
                    modifier = Modifier.weight(1f)
                )
                BottomNavPillButton(
                    label = if (queueCount > 0) "Queue ($queueCount)" else "Queue",
                    icon = Icons.Default.Download,
                    selected = canvasMode == DownloaderCanvasMode.QUEUE,
                    onClick = onSelectQueue,
                    modifier = Modifier.weight(1f)
                )
                BottomNavPillButton(
                    label = "Browser",
                    icon = Icons.Default.Language,
                    selected = false,
                    onClick = onSelectBrowser,
                    modifier = Modifier.weight(1f)
                )
                BottomNavPillButton(
                    label = "Settings",
                    icon = Icons.Default.Settings,
                    selected = canvasMode == DownloaderCanvasMode.SETTINGS,
                    onClick = onSelectSettings,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BottomNavPillButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillWidth by animateDpAsState(
        targetValue = if (selected) 58.dp else 40.dp,
        animationSpec = snap(),
        label = "dl_nav_pill_width"
    )
    val pillColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = snap(),
        label = "dl_nav_pill_color"
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = snap(),
        label = "dl_nav_icon_tint"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = pillColor,
                modifier = Modifier
                    .height(30.dp)
                    .width(pillWidth)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = iconTint,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * DBase-Downloader style Visual Queue Canvas.
 * Prioritizes rich media thumbnails, corner format pills, compact single-line telemetry,
 * and icon-only actions without walls of text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VisualQueueCanvas(
    nativeActivities: List<DownloadActivityItem>,
    ytdlnisDownloads: List<YtdlnisDownloadItem>,
    searchHistory: List<String>,
    onOpenInStudio: (String) -> Unit,
    onShowLogs: (YtdlnisDownloadItem) -> Unit,
    onOpenMediaFile: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { YtdlnisRepository.getInstance(context) }
    var filter by remember { mutableStateOf(UnifiedActivityFilter.ALL) }

    val ytMap = remember(ytdlnisDownloads) {
        ytdlnisDownloads.associateBy { "ytdlnis_${it.id}" }
    }

    val scheduledOrSavedYt = remember(ytdlnisDownloads) {
        ytdlnisDownloads.filter {
            it.status == YtdlnisDownloadStatus.SCHEDULED || it.status == YtdlnisDownloadStatus.SAVED
        }
    }

    val deduplicatedNative = remember(nativeActivities) {
        nativeActivities.distinctBy { it.id }
    }

    val activeCount = deduplicatedNative.count { it.isDownloading || it.isRetrying || it.isPaused }
    val scheduledCount = ytdlnisDownloads.count { it.status == YtdlnisDownloadStatus.SCHEDULED }
    val failedCount = deduplicatedNative.count { it.isFailed }
    val finishedCount = deduplicatedNative.count { it.isFinished }
    val savedCount = ytdlnisDownloads.count { it.status == YtdlnisDownloadStatus.SAVED }
    val totalCount = deduplicatedNative.size + scheduledOrSavedYt.size

    val filteredNative = remember(deduplicatedNative, filter) {
        deduplicatedNative.filter { item ->
            when (filter) {
                UnifiedActivityFilter.ALL -> true
                UnifiedActivityFilter.ACTIVE -> item.isDownloading || item.isRetrying || item.isPaused
                UnifiedActivityFilter.SCHEDULED -> false
                UnifiedActivityFilter.FAILED -> item.isFailed
                UnifiedActivityFilter.FINISHED -> item.isFinished
                UnifiedActivityFilter.SAVED -> false
            }
        }
    }

    val filteredScheduledOrSaved = remember(scheduledOrSavedYt, filter) {
        scheduledOrSavedYt.filter { item ->
            when (filter) {
                UnifiedActivityFilter.ALL -> true
                UnifiedActivityFilter.SCHEDULED -> item.status == YtdlnisDownloadStatus.SCHEDULED
                UnifiedActivityFilter.SAVED -> item.status == YtdlnisDownloadStatus.SAVED
                else -> false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status Filter Pills + Batch Action Pills (only shown when queue has items)
        if (totalCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val filterCounts = mapOf(
                    UnifiedActivityFilter.ALL to totalCount,
                    UnifiedActivityFilter.ACTIVE to activeCount,
                    UnifiedActivityFilter.SCHEDULED to scheduledCount,
                    UnifiedActivityFilter.FAILED to failedCount,
                    UnifiedActivityFilter.FINISHED to finishedCount,
                    UnifiedActivityFilter.SAVED to savedCount
                )

                UnifiedActivityFilter.entries.forEach { f ->
                    val count = filterCounts[f] ?: 0
                    if (f == UnifiedActivityFilter.ALL || count > 0) {
                        val selected = filter == f
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { filter = f }
                        ) {
                            Text(
                                text = if (count > 0 && f != UnifiedActivityFilter.ALL) "${f.label} $count" else f.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }

        if (filteredNative.isEmpty() && filteredScheduledOrSaved.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier.size(60.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(26.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (searchHistory.isNotEmpty()) {
                        val distinctHistory = remember(searchHistory) {
                            searchHistory.distinctBy {
                                it.removePrefix("https://")
                                    .removePrefix("http://")
                                    .removePrefix("www.")
                                    .trimEnd('/')
                                    .take(26)
                                    .lowercase()
                            }.take(6)
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            distinctHistory.forEach { q ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable { onOpenInStudio(q) }
                                ) {
                                    Text(
                                        text = q.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/').take(26),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { onClearHistory() }
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear history",
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(
                    items = filteredScheduledOrSaved,
                    key = { "sched_saved_${it.id}" }
                ) { ytItem ->
                    MonochromeScheduledOrSavedCard(
                        item = ytItem,
                        onStartNow = {
                            YtdlnisDownloadWorker.enqueue(context, ytItem.copy(scheduledTimeMillis = 0L))
                        },
                        onOpenStudio = { onOpenInStudio(ytItem.url) },
                        onDelete = { repo.removeDownload(ytItem.id) }
                    )
                }

                items(
                    items = filteredNative,
                    key = { "activity_${it.id}" }
                ) { item ->
                    val linkedYt = ytMap[item.id]
                    MonochromeUnifiedActivityCard(
                        item = item,
                        linkedYtdlnisItem = linkedYt,
                        onPause = { DownloadActivityManager.pauseDownload(context, item.id) },
                        onResumeOrRetry = { DownloadActivityManager.retryDownload(context, item.id) },
                        onDelete = { DownloadActivityManager.deleteActivity(item.id) },
                        onOpenStudio = {
                            val target = item.sourceUrl?.takeIf { it.startsWith("http") }
                                ?: item.mediaUrl.takeIf { it.startsWith("http") }
                            if (!target.isNullOrBlank()) {
                                onOpenInStudio(target)
                            }
                        },
                        onShowLogs = { if (linkedYt != null) onShowLogs(linkedYt) },
                        onOpenMedia = {
                            val uri = item.localUri
                            if (item.isFinished && !uri.isNullOrBlank()) {
                                onOpenMediaFile(uri)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MonochromeScheduledOrSavedCard(
    item: YtdlnisDownloadItem,
    onStartNow: () -> Unit,
    onOpenStudio: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 84.dp, height = 56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (item.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = item.thumbnailUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        if (item.type == YtdlnisDownloadType.AUDIO) Icons.Default.Audiotrack else Icons.Default.VideoFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xCC101010),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                ) {
                    Text(
                        text = item.container.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title.ifBlank { item.url },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (item.status == YtdlnisDownloadStatus.SCHEDULED) "Scheduled" else "Saved",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onOpenStudio, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Tune, contentDescription = "Studio", modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onStartNow, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Start", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun MonochromeUnifiedActivityCard(
    item: DownloadActivityItem,
    linkedYtdlnisItem: YtdlnisDownloadItem?,
    onPause: () -> Unit,
    onResumeOrRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpenStudio: () -> Unit,
    onShowLogs: () -> Unit,
    onOpenMedia: () -> Unit
) {
    val badge = remember(item) { getDisplayFormatBadge(item) }
    val safeProgress = remember(item.progress, item.isRetrying) {
        val p = if (item.isRetrying) 0.05f else item.progress
        if (p.isNaN() || p.isInfinite() || p < 0f) 0.05f else p.coerceIn(0f, 1f)
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.isFinished) { onOpenMedia() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 88.dp, height = 58.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val thumbModel = item.localUri?.takeIf { item.isFinished } ?: item.thumbnailUrl ?: item.mediaUrl
                    if (!thumbModel.isNullOrBlank()) {
                        AsyncImage(
                            model = thumbModel,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = when {
                                item.isAudio -> Icons.Default.Audiotrack
                                item.isVideo -> Icons.Default.Videocam
                                else -> Icons.Default.Image
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xCC101010),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val domainOrEngine = item.sourceDomain?.takeIf { it.isNotBlank() }
                        ?: linkedYtdlnisItem?.extractor?.takeIf { it.isNotBlank() }
                        ?: "Direct"

                    val statusSubtitle = when (item.status) {
                        DownloadActivityStatus.DOWNLOADING -> buildString {
                            append("${(safeProgress * 100).toInt()}%")
                            if (item.speedBytesPerSec > 0L) {
                                append(" · ${DownloadActivityManager.formatSpeed(item.speedBytesPerSec)}")
                            }
                            if (item.isSpeedBoostActive && item.activeConnections > 1) {
                                append(" · ⚡${item.activeConnections}x")
                            }
                        }
                        DownloadActivityStatus.RETRYING -> "Retrying ${item.retryCount}/${item.maxRetries}"
                        DownloadActivityStatus.PAUSED -> "Paused · ${DownloadActivityManager.formatBytes(item.bytesRead)}"
                        DownloadActivityStatus.FAILED -> item.errorMessage?.take(36) ?: "Failed"
                        DownloadActivityStatus.FINISHED -> buildString {
                            append(domainOrEngine)
                            if (item.totalBytes > 0L) {
                                append(" · ${DownloadActivityManager.formatBytes(item.totalBytes)}")
                            }
                        }
                    }

                    Text(
                        text = statusSubtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    if (linkedYtdlnisItem != null && (linkedYtdlnisItem.logOutput.isNotBlank() || !linkedYtdlnisItem.errorMessage.isNullOrBlank())) {
                        IconButton(onClick = onShowLogs, modifier = Modifier.size(30.dp)) {
                            Icon(
                                Icons.AutoMirrored.Filled.ReceiptLong,
                                contentDescription = "Logs",
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    when (item.status) {
                        DownloadActivityStatus.DOWNLOADING, DownloadActivityStatus.RETRYING -> {
                            IconButton(onClick = onPause, modifier = Modifier.size(30.dp)) {
                                Icon(
                                    Icons.Default.Pause,
                                    contentDescription = "Pause",
                                    modifier = Modifier.size(17.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        DownloadActivityStatus.PAUSED, DownloadActivityStatus.FAILED -> {
                            IconButton(onClick = onResumeOrRetry, modifier = Modifier.size(30.dp)) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Retry",
                                    modifier = Modifier.size(17.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(onClick = onOpenStudio, modifier = Modifier.size(30.dp)) {
                                Icon(
                                    Icons.Default.Tune,
                                    contentDescription = "Studio",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        DownloadActivityStatus.FINISHED -> {
                            IconButton(onClick = onOpenStudio, modifier = Modifier.size(30.dp)) {
                                Icon(
                                    Icons.Default.Tune,
                                    contentDescription = "Studio",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (item.isDownloading || item.isRetrying || item.isPaused) {
                LinearProgressIndicator(
                    progress = { safeProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VisualPlaylistBatchSheet(
    playlistEntries: List<YtdlnisVideoInfo>,
    selectedPlaylistUrls: Set<String>,
    playlistFilterQuery: String,
    playlistSortOrder: String,
    bulkDownloadType: YtdlnisDownloadType,
    onFilterChange: (String) -> Unit,
    onSortChange: (String) -> Unit,
    onToggleUrl: (String) -> Unit,
    onSelectAll: (Set<String>) -> Unit,
    onBulkTypeChange: (YtdlnisDownloadType) -> Unit,
    onOpenSingleStudio: (YtdlnisVideoInfo) -> Unit,
    onEnqueueSelected: (List<YtdlnisVideoInfo>) -> Unit,
    onDismiss: () -> Unit
) {
    val filteredEntries = remember(playlistEntries, playlistFilterQuery, playlistSortOrder) {
        val base = if (playlistFilterQuery.isBlank()) playlistEntries else {
            playlistEntries.filter {
                it.title.contains(playlistFilterQuery, ignoreCase = true) ||
                    it.author.contains(playlistFilterQuery, ignoreCase = true)
            }
        }
        when (playlistSortOrder) {
            "A-Z" -> base.sortedBy { it.title.lowercase() }
            "Z-A" -> base.sortedByDescending { it.title.lowercase() }
            "Duration" -> base.sortedByDescending { it.durationSec }
            else -> base
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.84f)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Batch (${selectedPlaylistUrls.size}/${playlistEntries.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        if (selectedPlaylistUrls.size == filteredEntries.size) {
                            onSelectAll(emptySet())
                        } else {
                            onSelectAll(filteredEntries.map { it.url }.toSet())
                        }
                    }
                ) {
                    Text(if (selectedPlaylistUrls.size == filteredEntries.size) "Clear" else "Select All")
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = playlistFilterQuery,
                onValueChange = onFilterChange,
                placeholder = { Text("Filter...") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            listOf("Default", "A-Z", "Duration").forEach { sort ->
                FilterChip(
                    selected = playlistSortOrder == sort,
                    onClick = { onSortChange(sort) },
                    label = { Text(sort, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(filteredEntries, key = { it.url }) { entry ->
                val checked = selectedPlaylistUrls.contains(entry.url)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleUrl(entry.url) }
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onToggleUrl(entry.url) }
                        )
                        if (entry.thumbnail.isNotBlank()) {
                            AsyncImage(
                                model = entry.thumbnail,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 64.dp, height = 42.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                entry.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                listOfNotNull(
                                    entry.author.takeIf { it.isNotBlank() },
                                    entry.durationString.takeIf { it.isNotBlank() }
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { onOpenSingleStudio(entry) },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = "Configure", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = bulkDownloadType == YtdlnisDownloadType.VIDEO,
                onClick = { onBulkTypeChange(YtdlnisDownloadType.VIDEO) },
                label = { Text("Video") }
            )
            FilterChip(
                selected = bulkDownloadType == YtdlnisDownloadType.AUDIO,
                onClick = { onBulkTypeChange(YtdlnisDownloadType.AUDIO) },
                label = { Text("Audio") }
            )
            Spacer(Modifier.weight(1f))
            Button(
                enabled = selectedPlaylistUrls.isNotEmpty(),
                shape = RoundedCornerShape(50),
                onClick = {
                    val toQueue = playlistEntries.filter { selectedPlaylistUrls.contains(it.url) }
                    onEnqueueSelected(toQueue)
                }
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Download (${selectedPlaylistUrls.size})", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun YtdlnisTerminalTab(
    repo: YtdlnisRepository,
    templates: List<YtdlnisCommandTemplate>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var commandInput by remember { mutableStateOf("--version") }
    var terminalLog by remember {
        mutableStateOf("yt-dlp console ready.\n")
    }
    var isRunning by remember { mutableStateOf(false) }

    var showAddTemplateDialog by remember { mutableStateOf(false) }
    var newTplName by remember { mutableStateOf("") }
    var newTplCmd by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                AssistChip(
                    onClick = { showAddTemplateDialog = true },
                    label = { Text("+ Template") }
                )
            }
            items(templates, key = { it.id }) { tpl ->
                InputChip(
                    selected = false,
                    onClick = { commandInput = tpl.command },
                    label = { Text(tpl.title) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Delete",
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { repo.deleteTemplate(tpl.id) }
                        )
                    }
                )
            }
        }

        val shortcuts = listOf(
            "-F", "--extract-audio", "--audio-format mp3", "--embed-metadata",
            "--embed-thumbnail", "--embed-subs", "--sponsorblock-remove all"
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shortcuts) { flag ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { commandInput = "$commandInput $flag".trim() }
                ) {
                    Text(
                        text = flag,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF0E0E0E),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    item {
                        Text(
                            text = terminalLog,
                            color = Color(0xFFE0E0E0),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                ) {
                    IconButton(
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(terminalLog))
                            Toast.makeText(context, "Copied log", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = { terminalLog = "" }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                placeholder = { Text("yt-dlp arguments & URL") },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                enabled = !isRunning && commandInput.isNotBlank(),
                shape = RoundedCornerShape(50),
                onClick = {
                    val cmd = commandInput.trim()
                    isRunning = true
                    terminalLog += "\n$ yt-dlp $cmd\n"
                    scope.launch {
                        val res = YtdlnisEngine.runTerminalCommand(
                            context = context,
                            commandLine = cmd
                        )
                        isRunning = false
                        if (res.success) {
                            terminalLog += "${res.output}\n"
                        } else {
                            terminalLog += "[ERROR] ${res.output}\n"
                        }
                    }
                }
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Run", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showAddTemplateDialog) {
        AlertDialog(
            onDismissRequest = { showAddTemplateDialog = false },
            title = { Text("New Command Template") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newTplName,
                        onValueChange = { newTplName = it },
                        label = { Text("Name") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newTplCmd,
                        onValueChange = { newTplCmd = it },
                        label = { Text("Flags") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newTplName.isNotBlank() && newTplCmd.isNotBlank()) {
                            repo.upsertTemplate(
                                YtdlnisCommandTemplate(
                                    title = newTplName.trim(),
                                    command = newTplCmd.trim()
                                )
                            )
                            newTplName = ""
                            newTplCmd = ""
                            showAddTemplateDialog = false
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddTemplateDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnifiedPowerSuiteTab(
    repo: YtdlnisRepository,
    cookies: List<YtdlnisCookieEntry>,
    observeSources: List<YtdlnisObserveSource>,
    filenameTemplate: String,
    subfolderMode: YtdlnisSubfolderMode,
    poToken: String,
    incognito: Boolean,
    speedBoostEnabled: Boolean,
    recursiveCrawlEnabled: Boolean,
    onFilenameTemplateChange: (String) -> Unit,
    onSubfolderModeChange: (YtdlnisSubfolderMode) -> Unit,
    onPoTokenChange: (String) -> Unit,
    onToggleIncognito: (Boolean) -> Unit,
    onToggleSpeedBoost: (Boolean) -> Unit,
    onToggleRecursiveCrawl: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showCookieDialog by remember { mutableStateOf(false) }
    var cookieDomain by remember { mutableStateOf("youtube.com") }
    var cookieContent by remember { mutableStateOf("") }

    var showObserveDialog by remember { mutableStateOf(false) }
    var observeName by remember { mutableStateOf("") }
    var observeUrl by remember { mutableStateOf("") }

    var backupJsonText by remember { mutableStateOf("") }
    var showBackupDialog by remember { mutableStateOf(false) }

    var platformProbes by remember { mutableStateOf<List<PlatformHealthResult>>(emptyList()) }
    var isProbingNetwork by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
    ) {
        // 1. Core Toggles (Speed Boost, Recursive BFS Crawler, Incognito)
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CompactSwitchRow(
                        title = "Speed Boost Multi-Connection",
                        checked = speedBoostEnabled,
                        onCheckedChange = onToggleSpeedBoost
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CompactSwitchRow(
                        title = "Recursive BFS Site Crawler",
                        checked = recursiveCrawlEnabled,
                        onCheckedChange = onToggleRecursiveCrawl
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CompactSwitchRow(
                        title = "Incognito Mode (Skip History)",
                        checked = incognito,
                        onCheckedChange = onToggleIncognito
                    )
                }
            }
        }

        // 2. 8-Platform Network Probe
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("8-Platform Network Probe", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            onClick = {
                                if (!isProbingNetwork) {
                                    isProbingNetwork = true
                                    scope.launch {
                                        platformProbes = PlatformNetworkMonitor.checkAllPlatforms()
                                        isProbingNetwork = false
                                    }
                                }
                            },
                            enabled = !isProbingNetwork,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(if (isProbingNetwork) "Probing..." else "Probe")
                        }
                    }

                    if (platformProbes.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            platformProbes.forEach { probe ->
                                val reachable = probe.status != PlatformReachability.UNREACHABLE
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = "${probe.platform.displayName} ${if (reachable) "${probe.latencyMs}ms" else "Off"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Output Template & Subfolders
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Output & Subfolders", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = filenameTemplate,
                        onValueChange = onFilenameTemplateChange,
                        label = { Text("Filename Template") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(YtdlnisSubfolderMode.entries) { mode ->
                            FilterChip(
                                selected = subfolderMode == mode,
                                onClick = { onSubfolderModeChange(mode) },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                }
            }
        }

        // 4. Observe Sources (Auto-Downloader)
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Observe Sources", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            onClick = { showObserveDialog = true },
                            shape = RoundedCornerShape(50)
                        ) {
                            Text("+ Source")
                        }
                    }
                    observeSources.forEach { src ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(src.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    src.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = {
                                    YtdlnisObserveWorker.schedulePeriodic(context)
                                    Toast.makeText(context, "Checking ${src.name}...", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = "Sync")
                            }
                            IconButton(onClick = { repo.deleteObserveSource(src.id) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }

        // 5. Cookies & YouTube PO Token
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Cookies & PO Token", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                shape = RoundedCornerShape(50),
                                onClick = {
                                    val domains = listOf(
                                        "youtube.com", "instagram.com", "x.com", "twitter.com",
                                        "tiktok.com", "reddit.com", "pinterest.com", "soundcloud.com", "vimeo.com"
                                    )
                                    val count = domains.count { repo.importCookiesFromWebView("https://$it") }
                                    Toast.makeText(
                                        context,
                                        if (count > 0) "Synced $count domain(s)" else "No WebView cookies yet",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            ) {
                                Text("Sync WebView")
                            }
                            Button(
                                shape = RoundedCornerShape(50),
                                onClick = { showCookieDialog = true }
                            ) {
                                Text("Add")
                            }
                        }
                    }
                    cookies.forEach { c ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(c.domain, style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { repo.deleteCookie(c.id) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Remove")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = poToken,
                        onValueChange = { onPoTokenChange(it) },
                        label = { Text("YouTube PO Token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 6. Backup / Restore & Temp Cache
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        backupJsonText = repo.exportBackupJson()
                        showBackupDialog = true
                    },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Backup / Restore")
                }
                OutlinedButton(
                    onClick = {
                        val freed = repo.clearCacheAndLeftovers()
                        Toast.makeText(context, "Freed ${freed / 1024} KB", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear Cache")
                }
            }
        }
    }

    if (showCookieDialog) {
        AlertDialog(
            onDismissRequest = { showCookieDialog = false },
            title = { Text("Add Cookies") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = cookieDomain,
                        onValueChange = { cookieDomain = it },
                        label = { Text("Domain") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = cookieContent,
                        onValueChange = { cookieContent = it },
                        label = { Text("Netscape cookies.txt") },
                        minLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (cookieDomain.isNotBlank() && cookieContent.isNotBlank()) {
                            repo.upsertCookie(
                                domain = cookieDomain.trim(),
                                cookiesNetscape = cookieContent.trim()
                            )
                            cookieContent = ""
                            showCookieDialog = false
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showCookieDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showObserveDialog) {
        AlertDialog(
            onDismissRequest = { showObserveDialog = false },
            title = { Text("Add Observe Source") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = observeName,
                        onValueChange = { observeName = it },
                        label = { Text("Label") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = observeUrl,
                        onValueChange = { observeUrl = it },
                        label = { Text("Playlist or Channel URL") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (observeName.isNotBlank() && observeUrl.isNotBlank()) {
                            repo.upsertObserveSource(
                                YtdlnisObserveSource(
                                    name = observeName.trim(),
                                    url = observeUrl.trim()
                                )
                            )
                            YtdlnisObserveWorker.schedulePeriodic(context)
                            observeName = ""
                            observeUrl = ""
                            showObserveDialog = false
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showObserveDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text("Backup / Restore JSON") },
            text = {
                OutlinedTextField(
                    value = backupJsonText,
                    onValueChange = { backupJsonText = it },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val res = repo.importBackupJson(backupJsonText)
                        res.onSuccess {
                            Toast.makeText(context, "Restored configuration", Toast.LENGTH_SHORT).show()
                            showBackupDialog = false
                        }.onFailure {
                            Toast.makeText(context, "Invalid JSON", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { showBackupDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun CompactSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
