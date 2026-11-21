package com.amresalehin.emregallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.amresalehin.emregallery.data.MediaItem

/**
 * Filters archived media by filename, path, URI, MIME type, and media kind.
 * An empty query preserves the original item order.
 */
fun filterArchiveItems(items: List<MediaItem>, query: String): List<MediaItem> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return items

    return items.filter { item ->
        listOf(
            item.name,
            item.relativePath,
            item.uri,
            item.mimeType.orEmpty(),
            if (item.isVideo) "video" else "image"
        ).any { value -> value.contains(normalizedQuery, ignoreCase = true) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    items: List<MediaItem>,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onBack: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onUnarchive: (MediaItem) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var searchQuery by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    val visibleItems = remember(items, searchQuery) { filterArchiveItems(items, searchQuery) }

    fun handleBack() {
        if (isSearchFocused || searchQuery.isNotEmpty()) {
            searchQuery = ""
            isSearchFocused = false
            focusManager.clearFocus()
            keyboardController?.hide()
        } else {
            onBack()
        }
    }

    BackHandler(enabled = isSearchFocused || searchQuery.isNotEmpty()) {
        handleBack()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .destinationTopBarEntrance(LocalViewerCoordinatedProgress.current)
                    .testTag("archive_search_bar")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = ::handleBack,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("archive_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Albums",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search archive",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                }
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focusState ->
                                    isSearchFocused = focusState.isFocused
                                }
                                .testTag("archive_search_input")
                        )
                    }

                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("archive_search_clear_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
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
                items.isEmpty() -> {
                    Box(
                        Modifier.fillMaxSize().padding(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Archive,
                                contentDescription = null,
                                modifier = Modifier.size(76.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Archive is empty",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Archived photos and videos remain in your library but are kept out of the main gallery.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                visibleItems.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "No matching items",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Try a different name, file type, or path.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        gridItems(visibleItems, key = { it.uri }) { item ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            ) {
                                Column {
                                    Box(modifier = Modifier.trackThumbnailBounds(item.uri)) {
                                        val context = LocalContext.current
                                        AsyncImage(
                                            model = remember(item.uri, context) {
                                                ThumbnailUtils.buildThumbnailRequest(context, item.uri)
                                            },
                                            contentDescription = item.name,
                                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                                                .clickable { onOpen(item) }
                                                .graphicsLayer {
                                                    alpha = if (ThumbnailBoundsTracker.activeViewerUri == item.uri) 0f else 1f
                                                },
                                            contentScale = ContentScale.Crop
                                        )
                                        if (item.isVideo) {
                                            Icon(
                                                Icons.Default.PlayCircle,
                                                contentDescription = "Video",
                                                modifier = Modifier.align(Alignment.Center).size(36.dp)
                                                    .graphicsLayer {
                                                        alpha = if (ThumbnailBoundsTracker.activeViewerUri == item.uri) 0f else 1f
                                                    },
                                                tint = Color.White
                                            )
                                        }
                                        Surface(
                                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                                            shape = CircleShape,
                                            color = Color.Black.copy(alpha = 0.55f)
                                        ) {
                                            Icon(
                                                Icons.Default.Archive,
                                                contentDescription = "Archived",
                                                modifier = Modifier.padding(6.dp).size(16.dp),
                                                tint = Color.White
                                            )
                                        }
                                    }
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
                                        Text(
                                            item.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        FilledTonalButton(
                                            onClick = { onUnarchive(item) },
                                            modifier = Modifier.fillMaxWidth().height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp)
                                        ) {
                                            Icon(Icons.Default.Unarchive, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Unarchive", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
