package com.amresalehin.emregallery.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import com.amresalehin.emregallery.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.amresalehin.emregallery.data.BrowserSession
import com.amresalehin.emregallery.data.DiscoveryFilterManager
import com.amresalehin.emregallery.data.DiscoveryFilterState
import com.amresalehin.emregallery.data.DownloadQueueManager
import com.amresalehin.emregallery.data.DownloaderSession
import com.amresalehin.emregallery.data.ExtractionSource
import com.amresalehin.emregallery.data.MediaCaptureEngine
import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.MediaInboxStack
import com.amresalehin.emregallery.data.OkHttpDownloader
import com.amresalehin.emregallery.data.ScrapedMediaItem

enum class DownloaderViewMode {
    SEARCH_PAGE,
    DOWNLOADER_PAGE,
    SAVED_LIBRARY,
    ACTIVITY_PAGE,
    DISCOVERED_MEDIA
}

/**
 * Direct entry point for download activity and discovered media.
 * Un-embedded from the unnecessary intermediate wrapper and saved library page.
 */
@Composable
fun MediaInboxScreen(
    onBack: () -> Unit,
    initialViewMode: DownloaderViewMode = DownloaderViewMode.ACTIVITY_PAGE,
    initialStackForDetail: MediaInboxStack? = null,
    initialOpenBrowser: Boolean = false,
    onOpenMediaItem: ((String) -> Unit)? = null,
    onOpenMediaWithList: ((String, List<String>) -> Unit)? = null,
    onOpenBrowser: ((String) -> Unit)? = null,
    onDeepScanClick: (() -> Unit)? = null,
    isDeepScanning: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (initialViewMode == DownloaderViewMode.DISCOVERED_MEDIA) {
        DiscoveredMediaPage(
            onBack = onBack,
            onDeepScanClick = onDeepScanClick,
            isDeepScanning = isDeepScanning,
            modifier = modifier.testTag("media_inbox_screen")
        )
    } else {
        DownloaderActivityPage(
            onBack = onBack,
            onAddNewDownload = { onOpenBrowser?.invoke("") },
            onOpenMedia = { item ->
                item.localUri?.let { uriStr ->
                    onOpenMediaItem?.invoke(uriStr)
                }
            },
            modifier = modifier.testTag("media_inbox_screen")
        )
    }
}

/**
 * Clean, standalone Discovered Media page allowing the user to choose exactly
 * what media they want to download (with multi-select, filters, and batch download).
 */
@Composable
fun DiscoveredMediaPage(
    onBack: () -> Unit,
    onDeepScanClick: (() -> Unit)? = null,
    isDeepScanning: Boolean = false,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        DiscoveryFilterManager.init(context)
    }

    val filterState by DiscoveryFilterManager.filterState.collectAsState()
    var showFilterSheet by remember { mutableStateOf(false) }
    var showAddUrlDialog by remember { mutableStateOf(false) }
    var manualUrlInput by remember { mutableStateOf("") }
    var qrShareItem by remember { mutableStateOf<ScrapedMediaItem?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var dismissedIds by remember { mutableStateOf(setOf<String>()) }

    val tabSession = remember { BrowserSession.getTabSession("tab_main") }
    val tabItems by tabSession.detectedItems.collectAsState()
    val sessionItems by DownloaderSession.discoveredItems.collectAsState()
    val sourceUrl by DownloaderSession.currentUrl.collectAsState()
    val sourceDomain by DownloaderSession.sourceDomain.collectAsState()

    val rawDiscoveredItems = remember(tabItems, sessionItems, dismissedIds, filterState.intelligentDeduplication) {
        val combined = (sessionItems + tabItems)
            .filterNot { dismissedIds.contains(it.id.ifBlank { it.url }) }
        if (filterState.intelligentDeduplication) {
            MediaClassifier.intelligentDeduplicate(combined)
        } else {
            combined.distinctBy { it.id.ifBlank { it.url } }
        }
    }

    val discoveredItems = remember(rawDiscoveredItems, filterState, searchQuery) {
        rawDiscoveredItems.filter { item ->
            DiscoveryFilterManager.matchesFilters(item, filterState) &&
                DiscoveryFilterManager.matchesSearchQuery(item, searchQuery)
        }
    }

    var selectedFilter by remember { mutableStateOf("All") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    val videoCount = remember(discoveredItems) { discoveredItems.count { it.isVideo && !it.isStream } }
    val streamCount = remember(discoveredItems) { discoveredItems.count { it.isStream } }
    val audioCount = remember(discoveredItems) { discoveredItems.count { it.isAudio } }
    val imageCount = remember(discoveredItems) {
        discoveredItems.count { !it.isVideo && !it.isStream && !it.isAudio && !it.isSubtitle && !it.isOtherFile }
    }
    val subtitleCount = remember(discoveredItems) { discoveredItems.count { it.isSubtitle } }
    val fileCount = remember(discoveredItems) { discoveredItems.count { it.isOtherFile } }

    val filteredItems = remember(discoveredItems, selectedFilter) {
        when (selectedFilter) {
            "Videos" -> discoveredItems.filter { it.isVideo && !it.isStream }
            "Streams" -> discoveredItems.filter { it.isStream }
            "Audio" -> discoveredItems.filter { it.isAudio }
            "Images" -> discoveredItems.filter { !it.isVideo && !it.isStream && !it.isAudio && !it.isSubtitle && !it.isOtherFile }
            "Subtitles" -> discoveredItems.filter { it.isSubtitle }
            "Files" -> discoveredItems.filter { it.isOtherFile }
            else -> discoveredItems
        }
    }

    fun downloadItem(item: ScrapedMediaItem) {
        val effectiveDomain = sourceDomain.ifBlank {
            item.provenance.pageUrl.ifBlank { sourceUrl }.let { url ->
                OkHttpDownloader.extractDomain(url.ifBlank { item.url })
            }
        }.ifBlank { "general" }

        DownloadQueueManager.enqueueDownload(
            context = context,
            item = item,
            referer = sourceUrl,
            stackId = "discovered_${sourceUrl.hashCode()}",
            stackTitle = effectiveDomain,
            sourceDomain = effectiveDomain,
            sourceUrl = sourceUrl
        )
    }

    fun downloadBatch(items: List<ScrapedMediaItem>) {
        if (items.isEmpty()) return
        items.forEach { downloadItem(it) }
        Toast.makeText(
            context,
            "Queued ${items.size} item${if (items.size > 1) "s" else ""} for download",
            Toast.LENGTH_SHORT
        ).show()
        selectedIds = emptySet()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("discovered_media_page")
    ) {
        // Top app bar with back button, filter button, deep scan, and Select All toggle (completely clean & clear)
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("discovered_media_back_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Manual URL Entry button ("Add URL" from Website Media Downloader)
                IconButton(
                    onClick = { showAddUrlDialog = true },
                    modifier = Modifier.testTag("discovered_media_add_url_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddLink,
                        contentDescription = "Add direct URL",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Filter icon button to open drawer sheet for discovery filters
                IconButton(
                    onClick = { showFilterSheet = true },
                    modifier = Modifier.testTag("discovered_media_filter_btn")
                ) {
                    val isModified = filterState.minWidth != 480 ||
                            filterState.minHeight != 540 ||
                            filterState.minSizeBytes != 10 * 1024L ||
                            filterState.enabledFormats != DiscoveryFilterState.DEFAULT_ENABLED_FORMATS

                    BadgedBox(
                        badge = {
                            if (isModified) {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(6.dp)
                                )
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Filter Media Discoveries",
                            tint = if (isModified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (onDeepScanClick != null) {
                    IconButton(
                        onClick = onDeepScanClick,
                        modifier = Modifier.testTag("discovered_media_deep_scan_btn")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isDeepScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 1.5.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                                )
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_radar),
                                    contentDescription = "Stop deep scan",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(13.dp)
                                )
                            } else {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_radar),
                                    contentDescription = "Run deep scan",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                if (filteredItems.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            val filteredIds = filteredItems.map { it.id.ifBlank { it.url } }.toSet()
                            selectedIds = if (selectedIds.containsAll(filteredIds)) {
                                selectedIds - filteredIds
                            } else {
                                selectedIds + filteredIds
                            }
                        },
                        modifier = Modifier.testTag("discovered_media_select_all_btn")
                    ) {
                        val filteredIds = filteredItems.map { it.id.ifBlank { it.url } }.toSet()
                        val allFilteredSelected = selectedIds.containsAll(filteredIds)
                        Icon(
                            imageVector = if (allFilteredSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                            contentDescription = if (allFilteredSelected) "Deselect all" else "Select all",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Global real-time search bar across all media groups
        if (rawDiscoveredItems.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search filename, URL, format, or host...", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("discovered_media_search_field")
            )
        }

        // 6-Category Media Grouping pills with live counts (Videos, Audio, Streams, Images, Subtitles, Files)
        if (discoveredItems.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val filters = buildList {
                    add("All" to discoveredItems.size)
                    if (videoCount > 0) add("Videos" to videoCount)
                    if (audioCount > 0) add("Audio" to audioCount)
                    if (streamCount > 0) add("Streams" to streamCount)
                    if (imageCount > 0) add("Images" to imageCount)
                    if (subtitleCount > 0) add("Subtitles" to subtitleCount)
                    if (fileCount > 0) add("Files" to fileCount)
                }
                items(filters) { (label, count) ->
                    val isSelected = selectedFilter == label
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedFilter = label },
                        label = { Text("$label ($count)") }
                    )
                }
            }
        }

        if (discoveredItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                if (rawDiscoveredItems.isNotEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "All media hidden by active filters",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "${rawDiscoveredItems.size} items were detected on this page, but are hidden by the current filter rules (Min: ${filterState.minWidth}x${filterState.minHeight}px, PNG/SVG disabled).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { showFilterSheet = true },
                                modifier = Modifier.testTag("empty_state_adjust_filters_btn")
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Adjust Filters")
                            }
                            OutlinedButton(
                                onClick = { DiscoveryFilterManager.resetToDefaults() },
                                modifier = Modifier.testTag("empty_state_reset_filters_btn")
                            ) {
                                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Reset")
                            }
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "No media detected on this page yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Browse pages containing videos, audio, or images and detected options will dynamically appear here for selection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 92.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredItems, key = { it.id.ifBlank { it.url } }) { item ->
                        val itemId = item.id.ifBlank { item.url }
                        val isSelected = selectedIds.contains(itemId)

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    selectedIds = if (isSelected) selectedIds - itemId else selectedIds + itemId
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Checkbox for choosing
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) selectedIds + itemId else selectedIds - itemId
                                    },
                                    modifier = Modifier.size(24.dp)
                                )

                                Spacer(Modifier.width(10.dp))

                                if (item.isSubtitle || item.isOtherFile || (item.isAudio && item.thumbnailUrl.isNullOrBlank())) {
                                    Box(
                                        modifier = Modifier
                                            .size(68.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.secondaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = when {
                                                item.isSubtitle -> Icons.Default.Subtitles
                                                item.isOtherFile -> Icons.Default.InsertDriveFile
                                                else -> Icons.Default.Audiotrack
                                            },
                                            contentDescription = item.category.label,
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                } else {
                                    AsyncImage(
                                        model = item.thumbnailUrl?.takeIf { it.isNotBlank() } ?: item.url,
                                        contentDescription = item.title,
                                        modifier = Modifier
                                            .size(68.dp)
                                            .clip(RoundedCornerShape(10.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }

                                Spacer(Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title.ifBlank { item.url },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val displayFormat = DiscoveryFilterManager.normalizeFormat(item)
                                        Text(
                                            text = if (item.isVideo || item.isStream) "Video ($displayFormat)" else if (item.isAudio) "Audio ($displayFormat)" else displayFormat,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        val dim = if (item.width != null && item.height != null) {
                                            "${item.width}x${item.height} px"
                                        } else item.resolutionHint?.takeIf { it.isNotBlank() }
                                        if (dim != null) {
                                            Text(
                                                text = "• $dim",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.width(8.dp))

                                // QR Code share button for cross-device transfer
                                IconButton(
                                    onClick = { qrShareItem = item },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QrCode2,
                                        contentDescription = "Share QR code for ${item.title}",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Single item download button
                                IconButton(
                                    onClick = {
                                        downloadItem(item)
                                        Toast.makeText(context, "Download queued", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = "Download ${item.title}",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom floating action bar for downloading chosen items
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .navigationBarsPadding()
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (selectedIds.isEmpty()) {
                                "Download all (${filteredItems.size})"
                            } else {
                                "${selectedIds.size} item${if (selectedIds.size > 1) "s" else ""} chosen"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectedIds.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        dismissedIds = dismissedIds + selectedIds
                                        selectedIds = emptySet()
                                    },
                                    modifier = Modifier.testTag("discovered_media_batch_remove_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Remove selected",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    val targets = if (selectedIds.isEmpty()) {
                                        filteredItems
                                    } else {
                                        filteredItems.filter { selectedIds.contains(it.id.ifBlank { it.url }) }
                                    }
                                    downloadBatch(targets)
                                },
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                            ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (selectedIds.isEmpty()) "Download All" else "Download Selected"
                            )
                            }
                        }
                    }
                }
            }
        }

        if (showFilterSheet) {
            DiscoveryFilterBottomSheet(
                filterState = filterState,
                matchingCount = discoveredItems.size,
                onFilterChange = { newFilter ->
                    DiscoveryFilterManager.updateFilters(newFilter)
                },
                onDismissRequest = { showFilterSheet = false }
            )
        }

        // Manual Direct URL Download Dialog ("Add URL")
        if (showAddUrlDialog) {
            AlertDialog(
                onDismissRequest = { showAddUrlDialog = false },
                title = { Text("Add Direct Download URL", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Paste a direct link to any video, audio, image, HLS/DASH stream, subtitle, or file.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = manualUrlInput,
                            onValueChange = { manualUrlInput = it },
                            placeholder = { Text("https://example.com/video.mp4") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val trimmed = manualUrlInput.trim()
                            if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                                val built = MediaCaptureEngine.buildMediaItem(
                                    rawUrl = trimmed,
                                    source = ExtractionSource.STATIC_HTML,
                                    baseUrl = sourceUrl.ifBlank { trimmed }
                                ) ?: run {
                                    val cls = MediaClassifier.classify(trimmed)
                                    ScrapedMediaItem(
                                        id = "manual_${System.currentTimeMillis()}_${trimmed.hashCode()}",
                                        url = trimmed,
                                        title = MediaClassifier.deriveFileName(trimmed, cls.format, 0),
                                        isVideo = cls.isVideo,
                                        isAudio = cls.isAudio,
                                        isStream = cls.isStream,
                                        category = cls.category,
                                        format = cls.format,
                                        mimeType = cls.mimeType,
                                        source = "Manual URL"
                                    )
                                }
                                DownloaderSession.addDiscoveredItem(built)
                                downloadItem(built)
                                Toast.makeText(context, "Queued ${built.title}", Toast.LENGTH_SHORT).show()
                                manualUrlInput = ""
                                showAddUrlDialog = false
                            } else {
                                Toast.makeText(context, "Enter a valid http:// or https:// URL", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("Download")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddUrlDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // QR Code Sharing Dialog
        qrShareItem?.let { item ->
            QrCodeShareDialog(
                title = item.title,
                url = item.url,
                onDismiss = { qrShareItem = null }
            )
        }
    }
}
