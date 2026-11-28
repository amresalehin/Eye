package com.amresalehin.emregallery.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.data.SearchFilterState
import com.amresalehin.emregallery.data.MediaTypeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import com.amresalehin.emregallery.data.ThumbnailPrewarmManager
import java.util.Locale

private const val PREFETCH_LOOK_AHEAD_ROWS = 14
private const val PREFETCH_IDLE_LOOK_AHEAD_ROWS = 6
private const val PREFETCH_MAX_MEDIA_ITEMS = 120

@Immutable
private data class HomeGroupedData(
    val sortedMedia: List<MediaItem> = emptyList(),
    val groups: List<DayGroup> = emptyList()
)

@Immutable
private data class HomeGridData(
    val sortedMedia: List<MediaItem> = emptyList(),
    // Exact flattened order of the rows rendered on Home; viewer navigation must use this
    // rather than the global sort when grouping changes the visible sequence.
    val viewerOrderedMedia: List<MediaItem> = emptyList(),
    val groupedRows: List<Pair<DayGroup, List<List<MediaItem>>>> = emptyList(),
    val predictivePrefetchRows: List<Pair<String, List<MediaItem>>> = emptyList(),
    val predictivePrefetchIndex: Map<String, Int> = emptyMap()
)

private fun buildHomeGroupedData(
    media: List<MediaItem>,
    sortOrder: String,
    groupBy: String
): HomeGroupedData {
    val sortedMedia = when (sortOrder) {
        "date_asc", "date_taken_asc" -> media.sortedWith(compareBy({ if (it.dateTakenMillis > 0) it.dateTakenMillis else it.dateAddedSeconds * 1000L }, { it.id }))
        "date_added_asc" -> media.sortedWith(compareBy({ it.dateAddedSeconds * 1000L }, { it.id }))
        "date_added_desc" -> media.sortedWith(compareByDescending<MediaItem> { it.dateAddedSeconds * 1000L }.thenBy { it.id })
        "name_asc" -> media.sortedWith(compareBy({ it.name.lowercase(Locale.getDefault()) }, { it.id }))
        "name_desc" -> media.sortedWith(compareByDescending<MediaItem> { it.name.lowercase(Locale.getDefault()) }.thenBy { it.id })
        "size_asc" -> media.sortedWith(compareBy<MediaItem> { it.sizeBytes }.thenBy { it.id })
        "size_desc" -> media.sortedWith(compareByDescending<MediaItem> { it.sizeBytes }.thenBy { it.id })
        else -> media.sortedWith(compareByDescending<MediaItem> { if (it.dateTakenMillis > 0) it.dateTakenMillis else it.dateAddedSeconds * 1000L }.thenBy { it.id })
    }
    val groups = groupMediaBy(sortedMedia, groupBy)
    return HomeGroupedData(sortedMedia = sortedMedia, groups = groups)
}

private fun buildHomeGridData(
    grouped: HomeGroupedData,
    groupedRows: List<Pair<DayGroup, List<List<MediaItem>>>>,
    listView: Boolean = false
): HomeGridData {
    val predictivePrefetchRows = groupedRows.flatMap { (group, rowChunks) ->
        rowChunks.map { row ->
            "day_${group.dayKey}_row_${row.first().id}" to row
        }
    }
    val predictivePrefetchIndex = HashMap<String, Int>(predictivePrefetchRows.size)
    predictivePrefetchRows.forEachIndexed { index, row ->
        predictivePrefetchIndex[row.first] = index
    }
    return HomeGridData(
        sortedMedia = grouped.sortedMedia,
        viewerOrderedMedia = if (listView) grouped.groups.flatMap { it.items } else flattenHomeGridRows(groupedRows),
        groupedRows = groupedRows,
        predictivePrefetchRows = predictivePrefetchRows,
        predictivePrefetchIndex = predictivePrefetchIndex
    )
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    media: List<MediaItem>,
    loading: Boolean,
    isRefreshing: Boolean = false,
    mediaLoadError: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    albumTitle: String? = null,
    sortOrder: String = "date_desc",
    groupBy: String = "day",
    gridColumns: Int = 3,
    gridStyle: String = "masonry",
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    onClearAlbumFilter: () -> Unit = {},
    onAddPhotosToAlbum: (() -> Unit)? = null,
    collectionMediaType: String? = null,
    collectionListView: Boolean = false,
    onCollectionListViewChange: (Boolean) -> Unit = {},
    onCollectionSortOrderChange: (String) -> Unit = {},
    onCollectionGroupByChange: (String) -> Unit = {},
    onCollectionGridColumnsChange: (Int) -> Unit = {},
    onCollectionGridStyleChange: (String) -> Unit = {},
    onPermissionGranted: () -> Unit = {},
    onMoveToBin: (Set<String>) -> Unit = {},
    onMoveToLocked: (Set<String>) -> Unit = {},
    onShareItems: (Set<String>) -> Unit = {},
    onAddToAlbum: ((Set<String>) -> Unit)? = null,
    selectedUris: Set<String> = emptySet(),
    onSelectionChange: (Set<String>) -> Unit = {},
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
    isVisible: Boolean = true
) {
    val context = LocalContext.current
    val homeScope = rememberCoroutineScope()
    var pinchGridColumns by remember(gridColumns) { mutableIntStateOf(gridColumns.coerceIn(1, 6)) }
    var pinchZoomRemainder by remember { mutableFloatStateOf(1f) }
    val selectionMode = selectedUris.isNotEmpty()
    val isCollectionMediaView = collectionMediaType == "album" || collectionMediaType == "folder"
    val showCollectionBackToTop by remember(listState, isCollectionMediaView) {
        derivedStateOf {
            isCollectionMediaView && (listState.firstVisibleItemIndex > 4 || listState.firstVisibleItemScrollOffset > 1200)
        }
    }
    // Keep collection media clear of the solid search-and-filter header overlay.
    val listTopContentPadding = if (isCollectionMediaView) topContentPadding + 42.dp else topContentPadding
    var collectionSearchQuery by rememberSaveable(collectionMediaType, albumTitle) { mutableStateOf("") }
    var collectionFilterState by remember(collectionMediaType, albumTitle) { mutableStateOf(SearchFilterState()) }
    var showCollectionViewOptions by remember { mutableStateOf(false) }
    val collectionSearchFocusRequester = remember(collectionMediaType, albumTitle) { FocusRequester() }
    val collectionDisplayMedia = remember(media, collectionSearchQuery, collectionFilterState, isCollectionMediaView) {
        if (!isCollectionMediaView) media else {
            val query = collectionSearchQuery.trim()
            media.filter { item ->
                val matchesQuery = query.isBlank() || item.name.contains(query, ignoreCase = true) ||
                    item.relativePath.contains(query, ignoreCase = true)
                val matchesType = when (collectionFilterState.mediaType) {
                    MediaTypeFilter.ALL -> true
                    MediaTypeFilter.PHOTOS -> !item.isVideo && !item.name.endsWith(".gif", ignoreCase = true)
                    MediaTypeFilter.VIDEOS -> item.isVideo
                    MediaTypeFilter.GIFS -> item.name.endsWith(".gif", ignoreCase = true)
                }
                matchesQuery && matchesType
            }
        }
    }

    BackHandler(enabled = isVisible && selectionMode) {
        onSelectionChange(emptySet())
    }
    var granted by remember { mutableStateOf(hasMediaPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val hasPermission = hasMediaPermission(context)
        granted = hasPermission
        if (hasPermission) onPermissionGranted()
    }
    LaunchedEffect(Unit) { granted = hasMediaPermission(context) }
    if (!granted) {
        val permissionsToRequest = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            )
            Build.VERSION.SDK_INT >= 29 -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            )
            else -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
        PermissionState { launcher.launch(permissionsToRequest) }
        return
    }

    var gridData by remember {
        mutableStateOf(HomeGridData())
    }
    // Build the actual gallery rows directly in the background. While this runs, Home stays
    // visually empty rather than drawing placeholder cells that are immediately discarded.
    LaunchedEffect(
        isVisible,
        collectionDisplayMedia,
        sortOrder,
        groupBy,
        pinchGridColumns,
        isCollectionMediaView,
        collectionListView
    ) {
        if (!isVisible) return@LaunchedEffect
        gridData = withContext(Dispatchers.Default) {
            val grouped = buildHomeGroupedData(collectionDisplayMedia, sortOrder, groupBy)
            val useListView = isCollectionMediaView && collectionListView
            val groupedRows = if (useListView) {
                grouped.groups.map { group -> group to emptyList<List<MediaItem>>() }
            } else {
                buildGroupedRows(grouped.groups, pinchGridColumns)
            }
            buildHomeGridData(grouped, groupedRows, useListView)
        }
    }

    LaunchedEffect(isCollectionMediaView, pinchGridColumns) {
        if (isCollectionMediaView) onCollectionGridColumnsChange(pinchGridColumns)
    }
    val sortedMedia = gridData.sortedMedia
    val viewerOrderedMedia = gridData.viewerOrderedMedia
    val groupedRows = gridData.groupedRows
    val predictivePrefetchRows = gridData.predictivePrefetchRows
    val predictivePrefetchIndex = gridData.predictivePrefetchIndex
    var observedScrollDirection by remember(listState) { mutableIntStateOf(1) }

    val predictivePrefetchConnection = remember(listState, predictivePrefetchRows, predictivePrefetchIndex) {
        object : NestedScrollConnection {
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (predictivePrefetchRows.isEmpty() || kotlin.math.abs(available.y) < 1200f) return Velocity.Zero

                val forward = observedScrollDirection > 0
                val visibleIndices = listState.layoutInfo.visibleItemsInfo.mapNotNull {
                    predictivePrefetchIndex[it.key.toString()]
                }
                if (visibleIndices.isEmpty()) return Velocity.Zero

                val anchor = (if (forward) visibleIndices.maxOrNull() else visibleIndices.minOrNull()) ?: return Velocity.Zero
                val rowsSnapshot = predictivePrefetchRows
                homeScope.launch(Dispatchers.Default) {
                    val lastIndex = rowsSnapshot.lastIndex
                    val predictedRows = (kotlin.math.abs(available.y) * 0.32f / 180f)
                        .toInt().coerceIn(4, PREFETCH_LOOK_AHEAD_ROWS)
                    val targetRange = if (forward) {
                        (anchor + 1)..(anchor + predictedRows).coerceAtMost(lastIndex)
                    } else {
                        (anchor - 1) downTo (anchor - predictedRows).coerceAtLeast(0)
                    }
                    val visibleItems = visibleIndices.flatMap { rowsSnapshot[it].second }
                    val lookAheadItems = targetRange.flatMap { rowsSnapshot[it].second }
                        .asSequence().distinctBy { it.uri }.take(PREFETCH_MAX_MEDIA_ITEMS).toList()
                    ThumbnailPrewarmManager.prioritize(
                        (visibleItems + lookAheadItems).distinctBy { it.uri }.take(PREFETCH_MAX_MEDIA_ITEMS)
                    )
                }
                return Velocity.Zero
            }
        }
    }

    // Track only visible item index range so scroll-offset pixel changes never trigger work.
    LaunchedEffect(isVisible, listState, predictivePrefetchRows, predictivePrefetchIndex) {
        if (!isVisible) return@LaunchedEffect
        var previousFirstItem = -1
        var previousAnchor = -1
        var previousDirection = 1

        snapshotFlow {
            val info = listState.layoutInfo.visibleItemsInfo
            if (info.isEmpty()) -1 to -1 else info.first().index to info.last().index
        }.distinctUntilChanged().debounce(75).collect { (firstItemIdx, lastItemIdx) ->
            if (firstItemIdx < 0 || predictivePrefetchRows.isEmpty()) {
                previousFirstItem = firstItemIdx
                return@collect
            }

            val visibleRows = listState.layoutInfo.visibleItemsInfo
                .mapNotNull { predictivePrefetchIndex[it.key.toString()] }
                .distinct()
                .sorted()
            if (visibleRows.isEmpty()) {
                previousFirstItem = firstItemIdx
                return@collect
            }

            val direction = when {
                previousFirstItem < 0 -> 1
                firstItemIdx > previousFirstItem -> 1
                firstItemIdx < previousFirstItem -> -1
                else -> previousDirection
            }
            observedScrollDirection = direction
            val anchor = if (direction > 0) visibleRows.last() else visibleRows.first()
            val movedEnough = previousAnchor < 0 || anchor != previousAnchor
            if (movedEnough || direction != previousDirection) {
                val rowsSnapshot = predictivePrefetchRows
                withContext(Dispatchers.Default) {
                    val lastIndex = rowsSnapshot.lastIndex
                    val moving = listState.isScrollInProgress
                    val forwardCount = if (moving) PREFETCH_LOOK_AHEAD_ROWS else PREFETCH_IDLE_LOOK_AHEAD_ROWS
                    val backwardCount = if (moving) 2 else 4
                    val targetIndices = if (direction > 0) {
                        (anchor..(anchor + forwardCount).coerceAtMost(lastIndex)).toList() +
                            (anchor - 1 downTo (anchor - backwardCount).coerceAtLeast(0)).toList()
                    } else {
                        (anchor downTo (anchor - forwardCount).coerceAtLeast(0)).toList() +
                            (anchor + 1..(anchor + backwardCount).coerceAtMost(lastIndex)).toList()
                    }
                    val visibleItems = visibleRows.flatMap { rowsSnapshot[it].second }
                    val lookAheadItems = targetIndices.flatMap { rowsSnapshot[it].second }
                        .asSequence().distinctBy { it.uri }.take(PREFETCH_MAX_MEDIA_ITEMS).toList()
                    ThumbnailPrewarmManager.prioritize(
                        (visibleItems + lookAheadItems).distinctBy { it.uri }.take(PREFETCH_MAX_MEDIA_ITEMS)
                    )
                }
                previousAnchor = anchor
                previousDirection = direction
            }
            previousFirstItem = firstItemIdx
        }
    }

    val hapticFeedback = LocalHapticFeedback.current
    val pullRefreshState = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            onRefresh?.invoke()
        },
        modifier = Modifier.fillMaxSize(),
        state = pullRefreshState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullRefreshState,
                isRefreshing = isRefreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = listTopContentPadding)
            )
        }
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().nestedScroll(predictivePrefetchConnection)
        ) {
        val containerWidth = maxWidth - 24.dp
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            // Pinch-to-zoom requires 2 or more touches. Single-finger vertical scrolls
                            // are NEVER consumed or intercepted, preserving native Android system scroll physics,
                            // fling decay, touch slop, and overscroll on LazyColumn.
                            if (event.changes.size >= 2) {
                                val zoomChange = event.calculateZoom()
                                if (zoomChange != 1f) {
                                    pinchZoomRemainder *= zoomChange
                                    if (pinchZoomRemainder < 0.82f) {
                                        pinchGridColumns = (pinchGridColumns + 1).coerceAtMost(6)
                                        pinchZoomRemainder = 1f
                                    } else if (pinchZoomRemainder > 1.22f) {
                                        pinchGridColumns = (pinchGridColumns - 1).coerceAtLeast(1)
                                        pinchZoomRemainder = 1f
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = listTopContentPadding,
                bottom = bottomContentPadding
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!isCollectionMediaView && albumTitle != null) {
                val isCollection = albumTitle in listOf("Locked folder", "Bin", "Archive", "Favourites", "Videos", "Recently added")
                val collectionIcon = when (albumTitle) {
                    "Favourites" -> Icons.Default.Favorite
                    "Videos" -> Icons.Default.PlayCircle
                    "Recently added" -> Icons.Default.Schedule
                    "Locked folder" -> Icons.Default.Lock
                    "Bin" -> Icons.Default.Delete
                    "Archive" -> Icons.Default.Archive
                    else -> Icons.Default.Folder
                }
                item {
                    val returnProgress = LocalViewerCoordinatedProgress.current
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .destinationTopBarEntrance(returnProgress),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(collectionIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (isCollection) "Collection" else "Album", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                                Text(albumTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (onAddPhotosToAlbum != null) {
                                FilledTonalButton(
                                    onClick = onAddPhotosToAlbum,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Add photos", style = MaterialTheme.typography.labelMedium)
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            IconButton(onClick = onClearAlbumFilter, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, "Clear filter", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }
            }

            if (sortedMedia.isEmpty() && mediaLoadError) {
                item(key = "home_media_load_error", contentType = "home_media_load_error") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp, horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Couldn't load media",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Your gallery couldn't be read. Check media access and try again.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        if (onRefresh != null) {
                            Spacer(Modifier.height(14.dp))
                            OutlinedButton(onClick = { onRefresh.invoke() }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Try again")
                            }
                        }
                    }
                }
            } else if (sortedMedia.isEmpty() && (loading || collectionDisplayMedia.isNotEmpty())) {
                // No loading skeleton: keep the gallery blank until real media rows are ready.
            } else if (sortedMedia.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp, horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (albumTitle != null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    if (collectionSearchQuery.isNotBlank()) "No matching media" else if (collectionMediaType == "folder") "No media in this folder" else "No photos in this album",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (collectionSearchQuery.isNotBlank()) "Try a different name or search term."
                                    else if (collectionMediaType == "folder") "This folder does not contain accessible photos or videos."
                                    else "Add photos from your gallery to populate this album.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (onAddPhotosToAlbum != null) {
                                    Spacer(Modifier.height(16.dp))
                                    Button(onClick = onAddPhotosToAlbum) {
                                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Add photos")
                                    }
                                }
                            }
                        } else {
                            Text("No photos or videos found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else if (sortedMedia.isNotEmpty()) {
                groupedRows.forEach { (group, rowChunks) ->
                    if (group.dayLabel.isNotBlank()) {
                        item(key = "header_${group.dayKey}", contentType = "header") {
                            HomeGroupHeader(group)
                        }
                    }

                    if (isCollectionMediaView && collectionListView) {
                        items(
                            count = group.items.size,
                            key = { itemIndex -> "collection_list_${group.dayKey}_${group.items[itemIndex].id}" },
                            contentType = { "collection_list_row" }
                        ) { itemIndex ->
                            val item = group.items[itemIndex]
                            CollectionMediaListRow(
                                item = item,
                                selected = item.uri in selectedUris,
                                onClick = {
                                    if (selectionMode) {
                                        onSelectionChange(if (item.uri in selectedUris) selectedUris - item.uri else selectedUris + item.uri)
                                    } else {
                                        onOpen(item, viewerOrderedMedia)
                                    }
                                },
                                onLongClick = { onSelectionChange(selectedUris + item.uri) }
                            )
                        }
                    } else {
                        items(
                            count = rowChunks.size,
                            key = { rIdx -> "day_${group.dayKey}_row_${rowChunks[rIdx].first().id}" },
                            contentType = { "photo_row" }
                        ) { rowIndex ->
                            JustifiedMosaicRow(
                                items = rowChunks[rowIndex],
                                containerWidth = containerWidth,
                                rowIndex = rowIndex,
                                isSquare = (gridStyle == "square"),
                                selectedUris = selectedUris,
                                onLongPress = { item -> onSelectionChange(selectedUris + item.uri) },
                                onToggleSelection = { item -> onSelectionChange(if (item.uri in selectedUris) selectedUris - item.uri else selectedUris + item.uri) },
                                onOpen = { item -> if (selectionMode) { onSelectionChange(if (item.uri in selectedUris) selectedUris - item.uri else selectedUris + item.uri) } else onOpen(item, viewerOrderedMedia) }
                            )
                        }
                    }
                }
            }
        }

        HomeFastScrollbar(
            listState = listState,
            topContentPadding = listTopContentPadding,
            bottomContentPadding = bottomContentPadding,
            thumbColor = MaterialTheme.colorScheme.onSurfaceVariant
        )

        AnimatedVisibility(
            visible = showCollectionBackToTop,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomContentPadding),
            enter = fadeIn(tween(160)) + scaleIn(tween(160)),
            exit = fadeOut(tween(120)) + scaleOut(tween(120))
        ) {
            SmallFloatingActionButton(
                onClick = {
                    homeScope.launch { listState.scrollToItem(0) }
                },
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 0.dp,
                    hoveredElevation = 0.dp
                ),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Back to top"
                )
            }
        }

        if (isCollectionMediaView) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CollectionBrowserSearchBar(
                    query = collectionSearchQuery,
                    onQueryChange = { collectionSearchQuery = it },
                    placeholder = "Search in " + (albumTitle ?: if (collectionMediaType == "album") "album" else "folder") + "…",
                    onBack = onClearAlbumFilter,
                    onOpenViewOptions = { showCollectionViewOptions = true },
                    focusRequester = collectionSearchFocusRequester,
                    onAdd = if (collectionMediaType == "album") onAddPhotosToAlbum else null
                )
                SearchFilterChipsRow(
                    filterState = collectionFilterState,
                    onFilterChange = { collectionFilterState = it },
                    dynamicChips = emptyList(),
                    onToggleDynamicChip = {},
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
    }

    if (isCollectionMediaView && showCollectionViewOptions) {
        HomeViewOptionsBottomSheet(
            sortOrder = sortOrder,
            groupBy = groupBy,
            gridColumns = gridColumns,
            gridStyle = gridStyle,
            onSortOrderChange = onCollectionSortOrderChange,
            onGroupByChange = onCollectionGroupByChange,
            onGridColumnsChange = onCollectionGridColumnsChange,
            onGridStyleChange = onCollectionGridStyleChange,
            onDismiss = { showCollectionViewOptions = false },
            showListOption = true,
            listViewSelected = collectionListView,
            onListViewSelected = onCollectionListViewChange
        )
    }
}


