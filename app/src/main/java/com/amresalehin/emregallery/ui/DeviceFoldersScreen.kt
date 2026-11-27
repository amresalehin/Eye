package com.amresalehin.emregallery.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.amresalehin.emregallery.data.MediaItem
import java.util.Locale

private data class DeviceFolderEntry(
    val path: String,
    val items: List<MediaItem>,
    val latestTakenMillis: Long,
    val latestAddedMillis: Long,
    val totalSizeBytes: Long
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DeviceFoldersScreen(
    media: List<MediaItem>,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    sortOrder: String = "name_asc",
    groupBy: String = "none",
    gridColumns: Int = 3,
    gridStyle: String = "square",
    listView: Boolean = true,
    onSortOrderChange: (String) -> Unit = {},
    onGroupByChange: (String) -> Unit = {},
    onGridColumnsChange: (Int) -> Unit = {},
    onGridStyleChange: (String) -> Unit = {},
    onListViewChange: (Boolean) -> Unit = {}
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showViewOptions by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val folderEntries = remember(media, sortOrder) {
        val grouped = media.groupBy { item ->
            item.relativePath.trim().trim('/').ifBlank { "Internal storage" }
        }
        val entries = grouped.map { (path, folderItems) ->
            DeviceFolderEntry(
                path = path,
                items = folderItems,
                latestTakenMillis = folderItems.maxOfOrNull {
                    if (it.dateTakenMillis > 0L) it.dateTakenMillis else it.dateAddedSeconds * 1000L
                } ?: 0L,
                latestAddedMillis = folderItems.maxOfOrNull { it.dateAddedSeconds * 1000L } ?: 0L,
                totalSizeBytes = folderItems.sumOf { it.sizeBytes.coerceAtLeast(0L) }
            )
        }
        when (sortOrder) {
            "date_taken_asc", "date_asc" -> entries.sortedBy { it.latestTakenMillis }
            "date_added_asc" -> entries.sortedBy { it.latestAddedMillis }
            "date_added_desc" -> entries.sortedByDescending { it.latestAddedMillis }
            "name_desc" -> entries.sortedByDescending { it.path.lowercase(Locale.getDefault()) }
            "size_asc" -> entries.sortedBy { it.totalSizeBytes }
            "size_desc" -> entries.sortedByDescending { it.totalSizeBytes }
            "date_taken_desc", "date_desc" -> entries.sortedByDescending { it.latestTakenMillis }
            else -> entries.sortedBy { it.path.lowercase(Locale.getDefault()) }
        }
    }
    val filteredEntries = remember(folderEntries, query) {
        if (query.isBlank()) folderEntries else folderEntries.filter {
            it.path.contains(query.trim(), ignoreCase = true)
        }
    }
    val groups = remember(filteredEntries, groupBy) {
        groupCollectionBrowserItems(
            items = filteredEntries,
            groupBy = groupBy,
            title = { it.path.substringAfterLast('/').ifBlank { "Internal storage" } },
            folder = { it.path.substringBeforeLast('/', "Internal storage").ifBlank { "Internal storage" } },
            timestampMillis = { it.latestTakenMillis }
        )
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CollectionBrowserSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Search device folders…",
                onBack = onBack,
                onOpenViewOptions = { showViewOptions = true },
                focusRequester = focusRequester
            )
            Text(
                filteredEntries.size.toString() + (if (filteredEntries.size == 1) " folder" else " folders") +
                    " · " + filteredEntries.sumOf { it.items.size } + " media items",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp)
            )
        }

        val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
        val pullRefreshState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onRefresh?.invoke()
            },
            modifier = Modifier.fillMaxSize().weight(1f),
            state = pullRefreshState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullRefreshState,
                    isRefreshing = isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        ) {
            when {
                filteredEntries.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.FolderOff, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (query.isBlank()) "No device folders found" else "No matching folders",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (query.isBlank()) "Folders appear here when accessible photos or videos are found on your device." else "Try another folder name or path.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                listView -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        groups.forEach { group ->
                            if (group.label.isNotBlank()) {
                                item(key = "folder-group-" + group.key) {
                                    BrowserGroupHeader(group.label, group.items.size)
                                }
                            }
                            items(group.items, key = { it.path }) { entry ->
                                DeviceFolderEntryCard(entry, listView = true, masonry = false) {
                                    onOpenFolder(entry.path)
                                }
                            }
                        }
                    }
                }
                gridStyle == "masonry" -> {
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(gridColumns.coerceIn(1, 6)),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalItemSpacing = 10.dp
                    ) {
                        groups.forEach { group ->
                            if (group.label.isNotBlank()) {
                                item(
                                    key = "folder-group-" + group.key,
                                    span = StaggeredGridItemSpan.FullLine
                                ) {
                                    BrowserGroupHeader(group.label, group.items.size)
                                }
                            }
                            staggeredItems(group.items, key = { it.path }) { entry ->
                                DeviceFolderEntryCard(entry, listView = false, masonry = true) {
                                    onOpenFolder(entry.path)
                                }
                            }
                        }
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(gridColumns.coerceIn(1, 6)),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        groups.forEach { group ->
                            if (group.label.isNotBlank()) {
                                item(
                                    key = "folder-group-" + group.key,
                                    span = { GridItemSpan(maxLineSpan) }
                                ) {
                                    BrowserGroupHeader(group.label, group.items.size)
                                }
                            }
                            gridItems(group.items, key = { it.path }) { entry ->
                                DeviceFolderEntryCard(entry, listView = false, masonry = false) {
                                    onOpenFolder(entry.path)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showViewOptions) {
        HomeViewOptionsBottomSheet(
            sortOrder = sortOrder,
            groupBy = groupBy,
            gridColumns = gridColumns,
            gridStyle = gridStyle,
            onSortOrderChange = onSortOrderChange,
            onGroupByChange = onGroupByChange,
            onGridColumnsChange = onGridColumnsChange,
            onGridStyleChange = onGridStyleChange,
            onDismiss = { showViewOptions = false },
            showListOption = true,
            listViewSelected = listView,
            onListViewSelected = onListViewChange
        )
    }
}

@Composable
private fun DeviceFolderEntryCard(
    entry: DeviceFolderEntry,
    listView: Boolean,
    masonry: Boolean,
    onClick: () -> Unit
) {
    val cover = entry.items.maxByOrNull {
        if (it.dateTakenMillis > 0L) it.dateTakenMillis else it.dateAddedSeconds * 1000L
    }
    val context = LocalContext.current
    val imageRatio = if (masonry && cover != null && cover.width > 0 && cover.height > 0) {
        (cover.width.toFloat() / cover.height.toFloat()).coerceIn(0.72f, 1.35f)
    } else if (masonry) 0.9f else 1f

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        if (listView) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                FolderCover(cover, context, Modifier.size(72.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.path.substringAfterLast('/').ifBlank { "Internal storage" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(entry.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        entry.items.size.toString() + " items · " + entry.items.count { it.isVideo } + " videos · " + formatFolderSize(entry.totalSizeBytes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Default.ChevronRight, contentDescription = "Open folder", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column {
                FolderCover(cover, context, Modifier.fillMaxWidth().aspectRatio(if (masonry) imageRatio else 1f))
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text(entry.path.substringAfterLast('/').ifBlank { "Internal storage" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        entry.items.size.toString() + " items · " + entry.items.count { it.isVideo } + " videos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(entry.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun FolderCover(cover: MediaItem?, context: android.content.Context, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (cover != null) {
            AsyncImage(
                model = remember(cover.uri, context) { ThumbnailUtils.buildThumbnailRequest(context, cover.uri, 360, 360) },
                contentDescription = "Folder preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatFolderSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}
