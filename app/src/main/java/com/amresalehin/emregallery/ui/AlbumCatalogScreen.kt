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
import androidx.compose.material.icons.filled.PhotoAlbum
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
import com.amresalehin.emregallery.data.CustomAlbum
import com.amresalehin.emregallery.data.MediaItem
import java.util.Locale

private data class AlbumCatalogEntry(
    val album: CustomAlbum,
    val media: List<MediaItem>,
    val latestTakenMillis: Long,
    val latestAddedMillis: Long,
    val totalSizeBytes: Long
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AlbumCatalogScreen(
    albums: List<CustomAlbum>,
    media: List<MediaItem>,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onBack: () -> Unit,
    onCreateAlbum: (String) -> Unit,
    onOpenAlbum: (CustomAlbum) -> Unit,
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

    val albumEntries = remember(albums, media, sortOrder) {
        val mediaByUri = media.associateBy { it.uri }
        val entries = albums.map { album ->
            val albumMedia = album.mediaUris.mapNotNull(mediaByUri::get)
            AlbumCatalogEntry(
                album = album,
                media = albumMedia,
                latestTakenMillis = albumMedia.maxOfOrNull {
                    if (it.dateTakenMillis > 0L) it.dateTakenMillis else it.dateAddedSeconds * 1000L
                } ?: 0L,
                latestAddedMillis = albumMedia.maxOfOrNull { it.dateAddedSeconds * 1000L } ?: 0L,
                totalSizeBytes = albumMedia.sumOf { it.sizeBytes.coerceAtLeast(0L) }
            )
        }
        when (sortOrder) {
            "date_taken_asc", "date_asc" -> entries.sortedBy { it.latestTakenMillis }
            "date_added_asc" -> entries.sortedBy { it.latestAddedMillis }
            "date_added_desc" -> entries.sortedByDescending { it.latestAddedMillis }
            "name_desc" -> entries.sortedByDescending { it.album.fullPath.lowercase(Locale.getDefault()) }
            "size_asc" -> entries.sortedBy { it.totalSizeBytes }
            "size_desc" -> entries.sortedByDescending { it.totalSizeBytes }
            "date_taken_desc", "date_desc" -> entries.sortedByDescending { it.latestTakenMillis }
            else -> entries.sortedBy { it.album.fullPath.lowercase(Locale.getDefault()) }
        }
    }
    val filteredEntries = remember(albumEntries, query) {
        if (query.isBlank()) albumEntries else albumEntries.filter {
            it.album.fullPath.contains(query.trim(), ignoreCase = true)
        }
    }
    val groups = remember(filteredEntries, groupBy) {
        groupCollectionBrowserItems(
            items = filteredEntries,
            groupBy = groupBy,
            title = { it.album.fullPath },
            folder = { it.album.parentPath },
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
                placeholder = "Search albums…",
                onBack = onBack,
                onOpenViewOptions = { showViewOptions = true },
                onAdd = { onCreateAlbum("") },
                addDescription = "Create album",
                focusRequester = focusRequester
            )
            Text(
                filteredEntries.size.toString() + if (filteredEntries.size == 1) " album" else " albums",
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
                        modifier = Modifier.fillMaxSize().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.PhotoAlbum, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (query.isBlank()) "No albums yet" else "No matching albums",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (query.isBlank()) "Create an album to collect and organise your photos and videos." else "Try a different album name or folder.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (query.isBlank()) {
                            Spacer(Modifier.height(18.dp))
                            Button(onClick = { onCreateAlbum("") }) { Text("Create album") }
                        }
                    }
                }
                listView -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        groups.forEach { group ->
                            if (group.label.isNotBlank()) {
                                item(key = "album-group-" + group.key) {
                                    BrowserGroupHeader(group.label, group.items.size)
                                }
                            }
                            items(group.items, key = { it.album.id }) { entry ->
                                AlbumCatalogEntryCard(entry, listView = true, masonry = false) {
                                    onOpenAlbum(entry.album)
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
                                    key = "album-group-" + group.key,
                                    span = StaggeredGridItemSpan.FullLine
                                ) {
                                    BrowserGroupHeader(group.label, group.items.size)
                                }
                            }
                            staggeredItems(group.items, key = { it.album.id }) { entry ->
                                AlbumCatalogEntryCard(entry, listView = false, masonry = true) {
                                    onOpenAlbum(entry.album)
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
                                    key = "album-group-" + group.key,
                                    span = { GridItemSpan(maxLineSpan) }
                                ) {
                                    BrowserGroupHeader(group.label, group.items.size)
                                }
                            }
                            gridItems(group.items, key = { it.album.id }) { entry ->
                                AlbumCatalogEntryCard(entry, listView = false, masonry = false) {
                                    onOpenAlbum(entry.album)
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
private fun AlbumCatalogEntryCard(
    entry: AlbumCatalogEntry,
    listView: Boolean,
    masonry: Boolean,
    onClick: () -> Unit
) {
    val album = entry.album
    val cover = entry.media.maxByOrNull {
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
                AlbumCover(cover, context, Modifier.size(76.dp))
                Spacer(Modifier.width(14.dp))
                AlbumEntryText(entry, Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, contentDescription = "Open album", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column {
                AlbumCover(cover, context, Modifier.fillMaxWidth().aspectRatio(if (masonry) imageRatio else 1f))
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text(album.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(entry.media.size.toString() + if (entry.media.size == 1) " item" else " items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (album.parentPath.isNotBlank()) {
                        Text(album.parentPath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumCover(cover: MediaItem?, context: android.content.Context, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (cover != null) {
            AsyncImage(
                model = remember(cover.uri, context) { ThumbnailUtils.buildThumbnailRequest(context, cover.uri, 360, 360) },
                contentDescription = "Album cover",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(Icons.Default.PhotoAlbum, contentDescription = null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AlbumEntryText(entry: AlbumCatalogEntry, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(entry.album.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (entry.album.parentPath.isNotBlank()) {
            Text(entry.album.parentPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Text(entry.media.size.toString() + if (entry.media.size == 1) " item" else " items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
