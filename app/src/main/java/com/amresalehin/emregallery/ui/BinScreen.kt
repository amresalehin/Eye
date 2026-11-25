package com.amresalehin.emregallery.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import com.amresalehin.emregallery.data.MediaItem

/**
 * Filters Bin contents by filename, path, URI, MIME type, and media kind.
 * An empty query preserves the original item order.
 */
fun filterBinItems(items: List<MediaItem>, query: String): List<MediaItem> {
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
fun BinScreen(
    items: List<MediaItem>,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onBack: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onRestore: (MediaItem) -> Unit,
    onRestoreBatch: (Set<String>) -> Unit,
    onDeletePermanently: (MediaItem) -> Unit,
    onDeletePermanentlyBatch: (List<MediaItem>) -> Unit,
    onEmptyBin: (List<MediaItem>) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var searchQuery by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    var confirmEmptyBin by remember { mutableStateOf(false) }
    var itemToPermanentlyDelete by remember { mutableStateOf<MediaItem?>(null) }
    var confirmBatchDeletePermanently by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectionMode = selectedUris.isNotEmpty()
    val visibleItems = remember(items, searchQuery) { filterBinItems(items, searchQuery) }
    val visibleUris = remember(visibleItems) { visibleItems.map { it.uri }.toSet() }
    val allVisibleItemsSelected = visibleUris.isNotEmpty() && visibleUris.all { it in selectedUris }

    fun handleBack() {
        when {
            selectionMode -> selectedUris = emptySet()
            isSearchFocused || searchQuery.isNotEmpty() -> {
                searchQuery = ""
                isSearchFocused = false
                focusManager.clearFocus()
                keyboardController?.hide()
            }
            else -> onBack()
        }
    }

    BackHandler(enabled = selectionMode || isSearchFocused || searchQuery.isNotEmpty()) {
        handleBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
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
                    .testTag("bin_search_bar")
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
                            .testTag("bin_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (selectionMode) "Exit selection" else "Back to Albums",
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
                                text = "Search bin",
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
                                .testTag("bin_search_input")
                        )
                    }

                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("bin_search_clear_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { confirmEmptyBin = true },
                        enabled = items.isNotEmpty(),
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("bin_empty_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = "Empty bin",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Selection Action Bar
        AnimatedVisibility(
            visible = selectionMode,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selectedUris = emptySet() }) {
                        Icon(Icons.Default.Close, contentDescription = "Exit selection")
                    }
                    Text(
                        "${selectedUris.size} selected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            selectedUris = if (allVisibleItemsSelected) {
                                selectedUris - visibleUris
                            } else {
                                selectedUris + visibleUris
                            }
                        },
                        enabled = visibleUris.isNotEmpty()
                    ) {
                        Text(if (allVisibleItemsSelected) "Deselect all" else "Select all")
                    }
                    FilledTonalButton(
                        onClick = {
                            onRestoreBatch(selectedUris)
                            selectedUris = emptySet()
                        },
                        modifier = Modifier.padding(horizontal = 4.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Restore")
                    }
                    IconButton(
                        onClick = { confirmBatchDeletePermanently = true },
                        modifier = Modifier.testTag("bin_delete_selected_permanently")
                    ) {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = "Delete selected permanently",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
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
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(92.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                            Text(
                                "Bin is empty",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Photos and videos you move to the Bin will appear here. You can restore them or permanently remove them from your device.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                visibleItems.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
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
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        gridItems(visibleItems, key = { it.uri }) { item ->
                            val isSelected = item.uri in selectedUris
                            BinItemCard(
                                item = item,
                                isSelected = isSelected,
                                selectionMode = selectionMode,
                                onOpen = {
                                    if (selectionMode) {
                                        selectedUris = if (isSelected) selectedUris - item.uri else selectedUris + item.uri
                                    } else {
                                        onOpen(item)
                                    }
                                },
                                onLongPress = {
                                    selectedUris = selectedUris + item.uri
                                },
                                onRestore = { onRestore(item) },
                                onDeletePermanently = { itemToPermanentlyDelete = item }
                            )
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialog: Empty entire bin
    if (confirmEmptyBin) {
        AlertDialog(
            onDismissRequest = { confirmEmptyBin = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
            title = { Text("Empty Bin?") },
            text = { Text("Permanently delete all ${items.size} items in the Bin from this device? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmEmptyBin = false
                        onEmptyBin(items)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface
                    )
                ) { Text("Empty Bin") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmptyBin = false }) { Text("Cancel") }
            }
        )
    }

    // Confirmation dialog: Delete single item permanently
    val currentItemToDelete = itemToPermanentlyDelete
    if (currentItemToDelete != null) {
        val target = currentItemToDelete
        AlertDialog(
            onDismissRequest = { itemToPermanentlyDelete = null },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
            title = { Text("Delete permanently?") },
            text = { Text("Permanently delete \"${target.name}\" from this device? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        val item = target
                        itemToPermanentlyDelete = null
                        onDeletePermanently(item)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { itemToPermanentlyDelete = null }) { Text("Cancel") }
            }
        )
    }

    // Confirmation dialog: Delete selected batch permanently
    if (confirmBatchDeletePermanently) {
        AlertDialog(
            onDismissRequest = { confirmBatchDeletePermanently = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
            title = { Text("Delete permanently?") },
            text = { Text("Permanently delete ${selectedUris.size} selected items from this device? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmBatchDeletePermanently = false
                        val targets = items.filter { it.uri in selectedUris }
                        selectedUris = emptySet()
                        onDeletePermanentlyBatch(targets)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface
                    )
                ) { Text("Delete permanently") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchDeletePermanently = false }) { Text("Cancel") }
            }
        )
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BinItemCard(
    item: MediaItem,
    isSelected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            if (isSelected) 2.dp else 1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .trackThumbnailBounds(item.uri)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = onLongPress
                    )
            ) {
                val context = LocalContext.current
                AsyncImage(
                    model = remember(item.uri, context) {
                        ThumbnailUtils.buildThumbnailRequest(context, item.uri)
                    },
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        alpha = if (ThumbnailBoundsTracker.activeViewerUri == item.uri) 0f else 1f
                    },
                    contentScale = ContentScale.Crop
                )
                if (item.isVideo) {
                    Icon(
                        Icons.Default.PlayCircle,
                        contentDescription = "Video",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(36.dp)
                            .graphicsLayer {
                                alpha = if (ThumbnailBoundsTracker.activeViewerUri == item.uri) 0f else 1f
                            },
                        tint = Color.White
                    )
                }

                if (selectionMode) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                        shape = CircleShape,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f)
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = if (isSelected) "Selected" else "Not selected",
                            tint = Color.White,
                            modifier = Modifier
                                .padding(4.dp)
                                .size(18.dp)
                        )
                    }
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        onClick = onRestore,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Restore", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    IconButton(
                        onClick = onDeletePermanently,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = "Delete ${item.name} permanently",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
