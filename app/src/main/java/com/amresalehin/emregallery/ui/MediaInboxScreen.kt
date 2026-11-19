package com.amresalehin.emregallery.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.amresalehin.emregallery.data.DownloadActivityManager
import com.amresalehin.emregallery.data.DownloadQueueManager
import com.amresalehin.emregallery.data.DownloaderSession
import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.MediaInboxStack
import com.amresalehin.emregallery.data.ScrapedMediaItem
import com.amresalehin.emregallery.ui.ytdlnis.UnifiedDownloaderTab
import com.amresalehin.emregallery.ui.ytdlnis.YtdlnisMonochromeTheme
import com.amresalehin.emregallery.ui.ytdlnis.YtdlnisScreen

enum class DownloaderViewMode {
    SEARCH_PAGE,
    DOWNLOADER_PAGE,
    SAVED_LIBRARY,
    ACTIVITY_PAGE,
    DISCOVERED_MEDIA
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun MediaInboxScreen(
    initialViewMode: DownloaderViewMode = DownloaderViewMode.ACTIVITY_PAGE,
    initialStackForDetail: MediaInboxStack? = null,
    initialOpenBrowser: Boolean = false,
    onBack: () -> Unit,
    onOpenBrowser: ((String?) -> Unit)? = null,
    onOpenMediaItem: (String) -> Unit = {},
    onOpenMediaWithList: ((String, List<String>) -> Unit)? = null
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            DownloadActivityManager.initialize(context)
            DownloadActivityManager.syncWithStorageDir(context)
            DownloadActivityManager.syncWithInboxRepository()
        }
    }

    var viewMode by remember(initialViewMode) {
        mutableStateOf(
            when (initialViewMode) {
                DownloaderViewMode.DISCOVERED_MEDIA -> DownloaderViewMode.DISCOVERED_MEDIA
                else -> DownloaderViewMode.ACTIVITY_PAGE
            }
        )
    }

    var studioSeedUrl by remember { mutableStateOf("") }
    var studioSeedTab by remember { mutableStateOf(UnifiedDownloaderTab.ACTIVITY) }

    val discoveredItems by DownloaderSession.discoveredItems.collectAsState()
    val sessionPageTitle by DownloaderSession.pageTitle.collectAsState()
    val sessionSourceDomain by DownloaderSession.sourceDomain.collectAsState()
    val sessionSourceUrl by DownloaderSession.currentUrl.collectAsState()

    BackHandler(enabled = viewMode == DownloaderViewMode.DISCOVERED_MEDIA) {
        if (initialViewMode != DownloaderViewMode.DISCOVERED_MEDIA) {
            viewMode = DownloaderViewMode.ACTIVITY_PAGE
        } else {
            onBack()
        }
    }

    YtdlnisMonochromeTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            when (viewMode) {
                DownloaderViewMode.DISCOVERED_MEDIA -> {
                    DiscoveredMediaPage(
                        items = discoveredItems,
                        pageTitle = sessionPageTitle,
                        sourceDomain = sessionSourceDomain,
                        sourceUrl = sessionSourceUrl,
                        onBack = {
                            if (initialViewMode == DownloaderViewMode.DISCOVERED_MEDIA) {
                                onBack()
                            } else {
                                viewMode = DownloaderViewMode.ACTIVITY_PAGE
                            }
                        },
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
                            }
                        },
                        onOpenInStudio = { itemUrl ->
                            studioSeedUrl = itemUrl
                            studioSeedTab = UnifiedDownloaderTab.STUDIO
                            viewMode = DownloaderViewMode.ACTIVITY_PAGE
                        },
                        onNavigateToActivities = {
                            studioSeedTab = UnifiedDownloaderTab.ACTIVITY
                            viewMode = DownloaderViewMode.ACTIVITY_PAGE
                        },
                        onClearAll = {
                            DownloaderSession.clear()
                        }
                    )
                }
                else -> {
                    YtdlnisScreen(
                        onBack = onBack,
                        initialUrl = studioSeedUrl,
                        initialTab = studioSeedTab,
                        onOpenBrowser = { targetUrl ->
                            onOpenBrowser?.invoke(targetUrl)
                        },
                        onOpenDiscoveredMedia = {
                            viewMode = DownloaderViewMode.DISCOVERED_MEDIA
                        },
                        onOpenMediaFile = { uri ->
                            onOpenMediaItem(uri)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Full-screen Webby-Images-Downloader style visual media grid page.
 * Also embeds cleanly inside ShareReceiverActivity, DirectBrowserView, and MediaScraperDrawer.
 */
@Composable
fun DiscoveredMediaPage(
    onBack: () -> Unit,
    onDeepScanClick: (() -> Unit)? = null,
    isDeepScanning: Boolean = false,
    modifier: Modifier = Modifier,
    items: List<ScrapedMediaItem>? = null,
    pageTitle: String? = null,
    sourceDomain: String? = null,
    sourceUrl: String? = null,
    onDownloadSingle: ((ScrapedMediaItem) -> Unit)? = null,
    onDownloadSelected: ((List<ScrapedMediaItem>) -> Unit)? = null,
    onOpenInStudio: ((String) -> Unit)? = null,
    onNavigateToActivities: (() -> Unit)? = null,
    onClearAll: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val sessionItems by DownloaderSession.discoveredItems.collectAsState()
    val sessionTitle by DownloaderSession.pageTitle.collectAsState()
    val sessionDomain by DownloaderSession.sourceDomain.collectAsState()
    val sessionUrl by DownloaderSession.currentUrl.collectAsState()

    val effectiveItems = items ?: sessionItems
    val effectiveTitle = pageTitle ?: sessionTitle
    val effectiveDomain = sourceDomain ?: sessionDomain
    val effectiveUrl = sourceUrl ?: sessionUrl

    val handleDownloadSingle: (ScrapedMediaItem) -> Unit = { item ->
        if (onDownloadSingle != null) {
            onDownloadSingle(item)
        } else {
            DownloadQueueManager.enqueueDownload(
                context = context,
                item = item,
                referer = effectiveUrl.ifBlank { null },
                stackTitle = effectiveTitle.ifBlank { null },
                sourceDomain = effectiveDomain.ifBlank { null },
                sourceUrl = effectiveUrl.ifBlank { null }
            )
            Toast.makeText(context, "Queued ${item.title}", Toast.LENGTH_SHORT).show()
        }
    }

    val handleDownloadSelected: (List<ScrapedMediaItem>) -> Unit = { selectedList ->
        if (onDownloadSelected != null) {
            onDownloadSelected(selectedList)
        } else if (selectedList.isNotEmpty()) {
            DownloadQueueManager.enqueueBatch(
                context = context,
                items = selectedList,
                referer = effectiveUrl.ifBlank { null },
                stackTitle = effectiveTitle.ifBlank { "Downloaded Media" },
                sourceDomain = effectiveDomain.ifBlank { "Web" },
                sourceUrl = effectiveUrl
            )
            Toast.makeText(context, "Queued ${selectedList.size} items", Toast.LENGTH_SHORT).show()
        }
    }

    val handleClearAll: () -> Unit = {
        if (onClearAll != null) onClearAll() else DownloaderSession.clear()
    }

    YtdlnisMonochromeTheme {
        Scaffold(
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Text(
                                text = effectiveTitle.ifBlank { effectiveDomain.ifBlank { "Discovered Media" } },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${effectiveItems.size} items · ${effectiveDomain.ifBlank { "Scan" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onDeepScanClick != null) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (isDeepScanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                border = if (isDeepScanning) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { onDeepScanClick() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (isDeepScanning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 1.5.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    } else {
                                        Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(14.dp))
                                    }
                                    Text(
                                        text = if (isDeepScanning) "Scanning" else "Deep Scan",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDeepScanning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        if (effectiveUrl.isNotBlank() && onOpenInStudio != null) {
                            IconButton(
                                onClick = { onOpenInStudio(effectiveUrl) },
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = "Studio", modifier = Modifier.size(16.dp))
                            }
                        }

                        if (effectiveItems.isNotEmpty()) {
                            IconButton(
                                onClick = handleClearAll,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        ) { padding ->
            WebbyDiscoveredMediaGrid(
                items = effectiveItems,
                onDownloadSingle = handleDownloadSingle,
                onDownloadSelected = { selected ->
                    handleDownloadSelected(selected)
                    onNavigateToActivities?.invoke()
                },
                onOpenInStudio = onOpenInStudio,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }
}

/**
 * Webby-Images-Downloader style 3-column numbered visual thumbnail grid with:
 * - Sequential #1, #2, #3... order badges
 * - Corner format/resolution pill overlay
 * - Tap-to-select check ring
 * - Long-press or expand button to open the Built-in Swipeable Zoom Viewer with 1-tap metadata HUD
 * - Floating bottom dock for category filtering, Select All / Clear, and 1-tap batch download
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun WebbyDiscoveredMediaGrid(
    items: List<ScrapedMediaItem>,
    onDownloadSingle: (ScrapedMediaItem) -> Unit,
    onDownloadSelected: (List<ScrapedMediaItem>) -> Unit,
    onOpenInStudio: ((String) -> Unit)? = null,
    onClearAll: (() -> Unit)? = null,
    searchHistory: List<String> = emptyList(),
    onSelectHistoryUrl: ((String) -> Unit)? = null,
    onClearHistory: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf<MediaCategory?>(null) }
    var selectedIds by remember(items) { mutableStateOf(items.map { it.id }.toSet()) }
    var previewIndex by remember { mutableStateOf<Int?>(null) }

    val filteredItems = remember(items, selectedCategory) {
        if (selectedCategory == null) items
        else items.filter { it.category == selectedCategory }
    }

    // Map each item ID to its 1-based page appearance index (#1, #2, #3...)
    val globalOrderMap = remember(items) {
        items.mapIndexed { idx, item -> item.id to (idx + 1) }.toMap()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Minimal category & Select All checkbox strip
            if (items.isNotEmpty()) {
                val filteredIds = remember(filteredItems) { filteredItems.map { it.id }.toSet() }
                val allFilteredChecked = filteredIds.isNotEmpty() && selectedIds.containsAll(filteredIds)
                val toggleSelectAll = {
                    selectedIds = if (allFilteredChecked) {
                        selectedIds - filteredIds
                    } else {
                        selectedIds + filteredIds
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Select All Checkbox Pill
                    Surface(
                        shape = CircleShape,
                        color = if (allFilteredChecked) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .height(34.dp)
                            .clip(CircleShape)
                            .clickable { toggleSelectAll() }
                            .testTag("grid_select_all_checkbox")
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 6.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Checkbox(
                                checked = allFilteredChecked,
                                onCheckedChange = { toggleSelectAll() },
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "Select all (${selectedIds.count { it in filteredIds }}/${filteredIds.size})",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (allFilteredChecked) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    val allSelected = selectedCategory == null
                    MonochromeFilterPill(
                        label = "All ${items.size}",
                        selected = allSelected,
                        onClick = { selectedCategory = null }
                    )

                    MediaCategory.entries.forEach { cat ->
                        val count = items.count { it.category == cat }
                        if (count > 0 && cat != MediaCategory.ALL) {
                            MonochromeFilterPill(
                                label = "${cat.label} $count",
                                selected = selectedCategory == cat,
                                onClick = { selectedCategory = cat }
                            )
                        }
                    }

                    if (onClearAll != null) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .height(34.dp)
                                .clip(CircleShape)
                                .clickable { onClearAll() }
                                .testTag("grid_back_to_search_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Back to Search",
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Back to Search",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (filteredItems.isEmpty()) {
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
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(26.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            text = "No fetched media to display",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        if (searchHistory.isNotEmpty() && onSelectHistoryUrl != null) {
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
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .clickable { onSelectHistoryUrl(q) }
                                    ) {
                                        Text(
                                            text = q.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/').take(26),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                                if (onClearHistory != null) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .clickable { onClearHistory() }
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Clear history",
                                            modifier = Modifier
                                                .padding(7.dp)
                                                .size(12.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(
                        start = 2.dp,
                        end = 2.dp,
                        top = 2.dp,
                        bottom = 72.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(
                        items = filteredItems,
                        key = { _, item -> item.id }
                    ) { idx, item ->
                        val isSelected = item.id in selectedIds
                        val seqNum = globalOrderMap[item.id] ?: (idx + 1)
                        val badgeLabel = remember(item) {
                            val fmt = item.format.ifBlank {
                                when {
                                    item.isStream -> "HLS"
                                    item.isVideo -> "MP4"
                                    item.isAudio -> "M4A"
                                    else -> "JPG"
                                }
                            }.take(4).uppercase()
                            val res = item.resolutionHint?.takeIf { it.isNotBlank() }
                            if (res != null) "$fmt · $res" else fmt
                        }

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    width = if (isSelected) 2.dp else 0.5.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                                .combinedClickable(
                                    onClick = {
                                        selectedIds = if (isSelected) selectedIds - item.id else selectedIds + item.id
                                    },
                                    onLongClick = {
                                        previewIndex = idx
                                    }
                                )
                                .testTag("download_single_${item.id}")
                        ) {
                            val previewModel = remember(item.thumbnailUrl, item.url, item.isVideo, item.isAudio, item.isStream) {
                                ThumbnailUtils.resolvePreviewImageModel(
                                    thumbnailUrl = item.thumbnailUrl,
                                    mediaUrl = item.url,
                                    isVideo = item.isVideo,
                                    isAudio = item.isAudio,
                                    isStream = item.isStream
                                )
                            }
                            if (previewModel != null) {
                                AsyncImage(
                                    model = previewModel,
                                    contentDescription = item.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            alpha = if (isSelected) 1f else 0.72f
                                        }
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = when {
                                            item.isAudio -> Icons.Default.Audiotrack
                                            item.isVideo || item.isStream -> Icons.Default.Videocam
                                            else -> Icons.Default.Image
                                        },
                                        contentDescription = item.title,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isSelected) 0.85f else 0.5f),
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }

                            // Top-left Webby sequential order badge (#1, #2, #3...)
                            Surface(
                                color = Color(0xCC0D0D0D),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(5.dp)
                            ) {
                                Text(
                                    text = "#$seqNum",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }

                            // Top-right selection ring
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(5.dp)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else Color(0x88000000)
                                    )
                                    .border(
                                        width = 1.5.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }

                            // Bottom-left format / resolution badge
                            Surface(
                                color = Color(0xCC0D0D0D),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(5.dp)
                            ) {
                                Text(
                                    text = badgeLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }

                            // Bottom-right quick zoom / preview button
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x990D0D0D))
                                    .clickable { previewIndex = idx },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (item.isVideo || item.isStream) Icons.Default.PlayArrow else Icons.Default.Fullscreen,
                                    contentDescription = "Preview",
                                    modifier = Modifier.size(14.dp),
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }

        // Compact Floating Download Pill (single count, small footprint)
        if (filteredItems.isNotEmpty() && selectedIds.isNotEmpty()) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .height(38.dp)
                    .clip(CircleShape)
                    .clickable {
                        val toDownload = items.filter { it.id in selectedIds }
                        onDownloadSelected(toDownload)
                    }
                    .testTag("grid_batch_download_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Download (${selectedIds.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        // Built-in Webby Fullscreen Swipeable Zoom Viewer with 1-Tap Metadata Overlay
        if (previewIndex != null && filteredItems.isNotEmpty()) {
            val safeInitial = previewIndex!!.coerceIn(0, filteredItems.lastIndex)
            WebbyFullscreenMediaViewer(
                items = filteredItems,
                initialIndex = safeInitial,
                globalOrderMap = globalOrderMap,
                selectedIds = selectedIds,
                onToggleSelect = { id ->
                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                },
                onDownloadSingle = onDownloadSingle,
                onOpenInStudio = onOpenInStudio,
                onDismiss = { previewIndex = null }
            )
        }
    }
}

@Composable
private fun MonochromeFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
        )
    }
}

/**
 * Webby-Images-Downloader style Built-in Fullscreen Viewer:
 * - Swipe left/right across discovered items at 1x zoom
 * - Pinch-to-zoom and pan, or double-tap to toggle 1x / 2.5x zoom
 * - Single tap toggles the metadata HUD (page index, resolution, engine, direct URL, Download & Studio actions)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WebbyFullscreenMediaViewer(
    items: List<ScrapedMediaItem>,
    initialIndex: Int,
    globalOrderMap: Map<String, Int>,
    selectedIds: Set<String>,
    onToggleSelect: (String) -> Unit,
    onDownloadSingle: (ScrapedMediaItem) -> Unit,
    onOpenInStudio: ((String) -> Unit)?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { items.size })
    var showMetadataHud by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF50A0A0A))
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = items[page]
                var scale by remember(page) { mutableFloatStateOf(1f) }
                var offset by remember(page) { mutableStateOf(Offset.Zero) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(page) {
                            detectTapGestures(
                                onTap = { showMetadataHud = !showMetadataHud },
                                onDoubleTap = {
                                    if (scale > 1.05f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                }
                            )
                        }
                        .pointerInput(page) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = newScale
                                offset = if (newScale > 1.02f) {
                                    Offset(offset.x + pan.x, offset.y + pan.y)
                                } else {
                                    Offset.Zero
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = item.url.takeIf { !item.isVideo && !item.isStream } ?: item.thumbnailUrl ?: item.url,
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                    )
                }
            }

            val currentItem = items.getOrNull(pagerState.currentPage) ?: items.first()
            val seqNum = globalOrderMap[currentItem.id] ?: (pagerState.currentPage + 1)
            val isChecked = currentItem.id in selectedIds

            // Top HUD bar (toggled by single tap)
            AnimatedVisibility(
                visible = showMetadataHud,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xBB0A0A0A))
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }

                    Text(
                        text = "#$seqNum of ${items.size}",
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    IconButton(onClick = { onToggleSelect(currentItem.id) }) {
                        Icon(
                            if (isChecked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = "Select",
                            tint = Color.White
                        )
                    }
                }
            }

            // Bottom Metadata & Quick Actions HUD (toggled by single tap)
            AnimatedVisibility(
                visible = showMetadataHud,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xDD0F0F0F))
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = currentItem.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = listOfNotNull(
                            currentItem.format.takeIf { it.isNotBlank() },
                            currentItem.resolutionHint?.takeIf { it.isNotBlank() },
                            currentItem.provenance.extractorEngine.takeIf { it.isNotBlank() },
                            currentItem.sizeBytes?.takeIf { it > 0L }?.let { DownloadActivityManager.formatBytes(it) }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFBDBDBD)
                    )

                    Text(
                        text = currentItem.url,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        ),
                        color = Color(0xFF9E9E9E),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            clipboard.setText(AnnotatedString(currentItem.url))
                            Toast.makeText(context, "Copied direct URL", Toast.LENGTH_SHORT).show()
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if ((currentItem.isVideo || currentItem.isAudio || currentItem.isStream) && onOpenInStudio != null) {
                            OutlinedButton(
                                onClick = {
                                    onDismiss()
                                    onOpenInStudio(currentItem.url)
                                },
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = BorderStroke(1.dp, Color(0xFF424242)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Studio")
                            }
                        }

                        Button(
                            onClick = { onDownloadSingle(currentItem) },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier.weight(1.3f)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Download", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
