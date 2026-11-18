package com.amresalehin.emregallery.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.amresalehin.emregallery.data.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Renders a compact colored tag pill modeled after denho/faved.
 */
@Composable
fun FavedTagPill(
    tag: FavedTag,
    isDark: Boolean,
    showFullPath: Boolean = false,
    allTags: List<FavedTag> = emptyList(),
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val dotColor = remember(tag.colorHex) { Color(FavedTagColorPalette.parseColorLong(tag.colorHex)) }
    val label = remember(tag, showFullPath, allTags) {
        if (showFullPath && allTags.isNotEmpty()) {
            FavedTagTreeHelper.buildHierarchyPath(tag.id, allTags)
        } else {
            tag.title
        }
    }
    val textColor = if (selected) {
        if (isDark) Color(0xFF121212) else Color(0xFFFFFFFF)
    } else {
        PocketThemeColors.primaryText(isDark)
    }
    val bgColor = if (selected) {
        if (isDark) Color(0xFFEEEEEE) else Color(0xFF202020)
    } else {
        PocketThemeColors.tagChipBg(isDark)
    }
    val borderColor = if (selected) {
        Color.Transparent
    } else {
        dotColor.copy(alpha = 0.45f)
    }

    Surface(
        shape = CircleShape,
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                ),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (onRemove != null) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove tag ${tag.title}",
                    tint = textColor.copy(alpha = 0.75f),
                    modifier = Modifier
                        .size(13.dp)
                        .clickable(onClick = onRemove)
                )
            }
        }
    }
}

/**
 * Quick-access horizontal bar below the Pocket search capsule showing:
 * Tags Manager button, All, Untagged, Pinned tags, active tag pill, Layout switcher, and Import/Export.
 */
@Composable
fun FavedQuickBar(
    tags: List<FavedTag>,
    selectedTagId: String?,
    untaggedOnly: Boolean,
    tagRollupEnabled: Boolean,
    layoutMode: BookmarkLayoutMode,
    onSelectAll: () -> Unit,
    onSelectUntagged: () -> Unit,
    onSelectTag: (String) -> Unit,
    onOpenTagsSheet: () -> Unit,
    onCycleLayout: () -> Unit,
    onOpenImportExport: () -> Unit,
    onOpenAddDialog: () -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    val pillBg = PocketThemeColors.pillSurface(isDark)
    val dividerCol = PocketThemeColors.divider(isDark)

    val pinnedTags = remember(tags) {
        tags.filter { it.isPinned }.sortedBy { it.title.lowercase(Locale.ROOT) }
    }
    val activeTag = remember(tags, selectedTagId) {
        selectedTagId?.let { id -> tags.firstOrNull { it.id == id } }
    }

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag("faved_quick_bar"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Tags Manager Sheet trigger
        item(key = "tags_manager_btn") {
            Surface(
                shape = CircleShape,
                color = if (selectedTagId != null || untaggedOnly) {
                    if (isDark) Color(0xFFEEEEEE) else Color(0xFF202020)
                } else pillBg,
                border = BorderStroke(1.dp, dividerCol),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onOpenTagsSheet)
                    .testTag("faved_open_tags_btn")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    val fg = if (selectedTagId != null || untaggedOnly) {
                        if (isDark) Color(0xFF141414) else Color(0xFFFFFFFF)
                    } else textColor
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = "Manage nested tags",
                        tint = fg,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = if (tags.isEmpty()) "Tags" else "Tags (${tags.size})",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = fg
                    )
                }
            }
        }

        // 2. All chip
        item(key = "filter_all") {
            val isAll = selectedTagId == null && !untaggedOnly
            Surface(
                shape = CircleShape,
                color = if (isAll) (if (isDark) Color(0xFFEEEEEE) else Color(0xFF202020)) else pillBg,
                border = BorderStroke(1.dp, if (isAll) Color.Transparent else dividerCol),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onSelectAll)
            ) {
                Text(
                    text = "All",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (isAll) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = if (isAll) (if (isDark) Color(0xFF141414) else Color(0xFFFFFFFF)) else secondaryText,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                )
            }
        }

        // 3. Active non-pinned tag indicator (with rollup badge)
        if (activeTag != null && !activeTag.isPinned) {
            item(key = "active_${activeTag.id}") {
                FavedTagPill(
                    tag = activeTag,
                    isDark = isDark,
                    showFullPath = true,
                    allTags = tags,
                    selected = true,
                    onClick = onSelectAll,
                    onRemove = onSelectAll
                )
            }
        }

        // 4. Pinned tags
        items(pinnedTags, key = { "pin_${it.id}" }) { tag ->
            val isSelected = selectedTagId == tag.id && !untaggedOnly
            FavedTagPill(
                tag = tag,
                isDark = isDark,
                selected = isSelected,
                onClick = { if (isSelected) onSelectAll() else onSelectTag(tag.id) }
            )
        }

        // 5. Untagged chip (shown when user has at least 1 tag)
        if (tags.isNotEmpty()) {
            item(key = "filter_untagged") {
                Surface(
                    shape = CircleShape,
                    color = if (untaggedOnly) (if (isDark) Color(0xFFEEEEEE) else Color(0xFF202020)) else pillBg,
                    border = BorderStroke(1.dp, if (untaggedOnly) Color.Transparent else dividerCol),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = { if (untaggedOnly) onSelectAll() else onSelectUntagged() })
                ) {
                    Text(
                        text = "Untagged",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (untaggedOnly) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (untaggedOnly) (if (isDark) Color(0xFF141414) else Color(0xFFFFFFFF)) else secondaryText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // 6. Layout cycle button (Card / List / Table)
        item(key = "layout_toggle_btn") {
            val layoutIcon = when (layoutMode) {
                BookmarkLayoutMode.CARD -> Icons.Default.Dashboard
                BookmarkLayoutMode.LIST -> Icons.Default.ViewAgenda
                BookmarkLayoutMode.TABLE -> Icons.Default.TableRows
            }
            Surface(
                shape = CircleShape,
                color = pillBg,
                border = BorderStroke(1.dp, dividerCol),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onCycleLayout)
                    .testTag("faved_layout_cycle_btn")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = layoutIcon,
                        contentDescription = "Switch bookmark layout",
                        tint = textColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = layoutMode.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor
                    )
                }
            }
        }

        // 7. Import / Export button
        item(key = "import_export_btn") {
            Surface(
                shape = CircleShape,
                color = pillBg,
                border = BorderStroke(1.dp, dividerCol),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onOpenImportExport)
                    .testTag("faved_import_export_btn")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ImportExport,
                        contentDescription = "Import or export bookmarks",
                        tint = textColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Import/Export",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor
                    )
                }
            }
        }

        // 8. Add Bookmark with Tags button
        item(key = "add_bookmark_btn") {
            Surface(
                shape = CircleShape,
                color = pillBg,
                border = BorderStroke(1.dp, dividerCol),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onOpenAddDialog)
                    .testTag("faved_add_bookmark_btn")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add bookmark",
                        tint = textColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Add",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor
                    )
                }
            }
        }
    }
}

/**
 * Full-featured Nested Tags Manager Sheet modeled after denho/faved's sidebar:
 * - Tag search & instant filtering
 * - Tag Rollup toggle (include items from child tags)
 * - Pinned tags quick section
 * - Collapsible nested tag hierarchy tree with color dots, counts, and descriptions
 * - Create new top-level or nested child tag, edit, pin/unpin, delete
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavedTagsManagerSheet(
    tags: List<FavedTag>,
    bookmarks: List<MediaInboxStack>,
    selectedTagId: String?,
    untaggedOnly: Boolean,
    tagRollupEnabled: Boolean,
    onToggleRollup: (Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onSelectUntagged: () -> Unit,
    onSelectTag: (String) -> Unit,
    onCreateTag: (parentId: String?) -> Unit,
    onEditTag: (FavedTag) -> Unit,
    onTogglePin: (String) -> Unit,
    onDeleteTag: (FavedTag) -> Unit,
    onDismiss: () -> Unit,
    isDark: Boolean
) {
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    val sheetSurface = if (isDark) Color(0xFF1C1C1C) else Color(0xFFFFFFFF)
    val cardSurface = PocketThemeColors.barSurface(isDark)
    val dividerCol = PocketThemeColors.divider(isDark)

    var tagSearch by remember { mutableStateOf("") }
    var collapsedTagIds by remember { mutableStateOf(setOf<String>()) }

    val treeNodes = remember(tags, bookmarks, tagSearch, collapsedTagIds) {
        FavedTagTreeHelper.buildOrderedTreeNodes(
            allTags = tags,
            bookmarks = bookmarks,
            searchQuery = tagSearch,
            collapsedTagIds = collapsedTagIds
        )
    }
    val untaggedCount = remember(bookmarks) { bookmarks.count { it.tagIds.isEmpty() } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = sheetSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Nested Tags",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                    Text(
                        text = "${tags.size} tags · ${bookmarks.size} bookmarks",
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryText
                    )
                }
                Button(
                    onClick = { onCreateTag(null) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDark) Color(0xFFEEEEEE) else Color(0xFF202020),
                        contentColor = if (isDark) Color(0xFF141414) else Color(0xFFFFFFFF)
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New Tag", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Search tags input
            OutlinedTextField(
                value = tagSearch,
                onValueChange = { tagSearch = it },
                placeholder = { Text("Search tags...", color = secondaryText) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = secondaryText, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (tagSearch.isNotEmpty()) {
                        IconButton(onClick = { tagSearch = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = secondaryText, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = textColor,
                    unfocusedBorderColor = dividerCol,
                    focusedTextColor = textColor,
                    unfocusedTextColor = textColor,
                    cursorColor = textColor
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            // Rollup toggle row
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cardSurface,
                border = BorderStroke(1.dp, dividerCol),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tag Rollup",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor
                        )
                        Text(
                            text = "Include bookmarks from nested child tags",
                            style = MaterialTheme.typography.labelSmall,
                            color = secondaryText
                        )
                    }
                    Switch(
                        checked = tagRollupEnabled,
                        onCheckedChange = onToggleRollup,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = if (isDark) Color(0xFF161616) else Color.White,
                            checkedTrackColor = if (isDark) Color(0xFFEEEEEE) else Color(0xFF202020)
                        )
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Quick filters: All / Untagged
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val allSelected = selectedTagId == null && !untaggedOnly
                FilterChip(
                    selected = allSelected,
                    onClick = {
                        onSelectAll()
                        onDismiss()
                    },
                    label = { Text("All (${bookmarks.size})") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = untaggedOnly,
                    onClick = {
                        onSelectUntagged()
                        onDismiss()
                    },
                    label = { Text("Untagged ($untaggedCount)") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Tag Tree List
            if (treeNodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Sell,
                            contentDescription = null,
                            tint = secondaryText,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (tagSearch.isNotBlank()) "No matching tags" else "No tags yet",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor
                        )
                        Text(
                            text = "Create nested tags like Programming → Backend → Kotlin",
                            style = MaterialTheme.typography.labelSmall,
                            color = secondaryText
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    items(treeNodes, key = { it.tag.id }) { node ->
                        val isSelected = selectedTagId == node.tag.id && !untaggedOnly
                        val isCollapsed = node.tag.id in collapsedTagIds
                        val dotColor = Color(FavedTagColorPalette.parseColorLong(node.tag.colorHex))
                        val count = if (tagRollupEnabled) node.rolledUpBookmarkCount else node.directBookmarkCount

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) (if (isDark) Color(0xFF2D2D2D) else Color(0xFFEAEAEA)) else Color.Transparent,
                            border = if (isSelected) BorderStroke(1.dp, dotColor.copy(alpha = 0.7f)) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (node.depth * 18).dp)
                                .clickable {
                                    onSelectTag(node.tag.id)
                                    onDismiss()
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (node.hasChildren) {
                                    IconButton(
                                        onClick = {
                                            collapsedTagIds = if (isCollapsed) collapsedTagIds - node.tag.id else collapsedTagIds + node.tag.id
                                        },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isCollapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
                                            contentDescription = if (isCollapsed) "Expand" else "Collapse",
                                            tint = secondaryText,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else {
                                    Spacer(Modifier.width(26.dp))
                                }

                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Spacer(Modifier.width(9.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = node.tag.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected || node.tag.isPinned) FontWeight.Bold else FontWeight.Medium,
                                            color = textColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (node.tag.isPinned) {
                                            Spacer(Modifier.width(4.dp))
                                            Icon(
                                                imageVector = Icons.Default.PushPin,
                                                contentDescription = "Pinned",
                                                tint = secondaryText,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                    if (node.tag.description.isNotBlank()) {
                                        Text(
                                            text = node.tag.description,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = secondaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Text(
                                    text = "$count",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = secondaryText,
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )

                                // Add child tag
                                IconButton(
                                    onClick = { onCreateTag(node.tag.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CreateNewFolder,
                                        contentDescription = "Add sub-tag",
                                        tint = secondaryText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                // Pin / Unpin
                                IconButton(
                                    onClick = { onTogglePin(node.tag.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PushPin,
                                        contentDescription = if (node.tag.isPinned) "Unpin tag" else "Pin tag",
                                        tint = if (node.tag.isPinned) textColor else secondaryText.copy(alpha = 0.5f),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }

                                // Edit
                                IconButton(
                                    onClick = { onEditTag(node.tag) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit tag",
                                        tint = secondaryText,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }

                                // Delete
                                IconButton(
                                    onClick = { onDeleteTag(node.tag) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete tag",
                                        tint = secondaryText,
                                        modifier = Modifier.size(16.dp)
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

/**
 * Dialog to create or edit a Faved nested tag (title, parent tag, color, description, pin).
 */
@Composable
fun FavedTagEditorDialog(
    existingTag: FavedTag? = null,
    initialParentId: String? = null,
    allTags: List<FavedTag>,
    onDismiss: () -> Unit,
    onSave: (title: String, parentId: String?, colorHex: String, description: String, isPinned: Boolean) -> Unit,
    isDark: Boolean
) {
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    val dialogSurface = if (isDark) Color(0xFF1E1E1E) else Color(0xFFFFFFFF)
    val borderCol = PocketThemeColors.divider(isDark)

    var title by remember { mutableStateOf(existingTag?.title.orEmpty()) }
    var selectedParentId by remember { mutableStateOf(existingTag?.parentId ?: initialParentId) }
    var selectedColorHex by remember { mutableStateOf(existingTag?.colorHex ?: FavedTagColorPalette.DEFAULT_HEX) }
    var description by remember { mutableStateOf(existingTag?.description.orEmpty()) }
    var isPinned by remember { mutableStateOf(existingTag?.isPinned ?: false) }
    var parentMenuExpanded by remember { mutableStateOf(false) }

    val eligibleParents = remember(allTags, existingTag) {
        allTags.filter { candidate ->
            existingTag == null || !FavedTagTreeHelper.wouldCreateCycle(existingTag.id, candidate.id, allTags)
        }.sortedBy { FavedTagTreeHelper.buildHierarchyPath(it.id, allTags).lowercase(Locale.ROOT) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = dialogSurface,
            border = BorderStroke(1.dp, borderCol),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Text(
                    text = if (existingTag == null) "Create Nested Tag" else "Edit Tag",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )

                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Tag Name *") },
                    placeholder = { Text("e.g. Backend, Kotlin, Design") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                // Parent tag selector
                Text(
                    text = "Parent Tag (optional)",
                    style = MaterialTheme.typography.labelMedium,
                    color = secondaryText
                )
                Spacer(Modifier.height(4.dp))
                Box {
                    val parentLabel = selectedParentId?.let { pid ->
                        FavedTagTreeHelper.buildHierarchyPath(pid, allTags).ifBlank { "None (Top-level)" }
                    } ?: "None (Top-level)"
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PocketThemeColors.barSurface(isDark),
                        border = BorderStroke(1.dp, borderCol),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { parentMenuExpanded = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = parentLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = secondaryText)
                        }
                    }
                    DropdownMenu(
                        expanded = parentMenuExpanded,
                        onDismissRequest = { parentMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("None (Top-level)") },
                            onClick = {
                                selectedParentId = null
                                parentMenuExpanded = false
                            }
                        )
                        eligibleParents.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(FavedTagTreeHelper.buildHierarchyPath(candidate.id, allTags)) },
                                onClick = {
                                    selectedParentId = candidate.id
                                    parentMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Color picker
                Text(
                    text = "Tag Color",
                    style = MaterialTheme.typography.labelMedium,
                    color = secondaryText
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FavedTagColorPalette.options.forEach { opt ->
                        val isChosen = selectedColorHex.equals(opt.hex, ignoreCase = true)
                        val c = Color(FavedTagColorPalette.parseColorLong(opt.hex))
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(c)
                                .clickable { selectedColorHex = opt.hex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isChosen) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = opt.name,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Pin tag for quick access",
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor
                    )
                    Switch(
                        checked = isPinned,
                        onCheckedChange = { isPinned = it }
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = secondaryText)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(title.trim(), selectedParentId, selectedColorHex, description.trim(), isPinned) },
                        enabled = title.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDark) Color(0xFFEEEEEE) else Color(0xFF202020),
                            contentColor = if (isDark) Color(0xFF141414) else Color(0xFFFFFFFF)
                        )
                    ) {
                        Text(if (existingTag == null) "Create" else "Save")
                    }
                }
            }
        }
    }
}

/**
 * Inline multi-tag selector with quick inline tag creation and smart suggested tags.
 * Used inside AddBookmarkDialog, EditBookmarkDialog, and BookmarkDetailScreen.
 */
@Composable
fun FavedTagSelectorSection(
    allTags: List<FavedTag>,
    selectedTagIds: List<String>,
    onToggleTag: (String) -> Unit,
    onQuickCreateTag: (String) -> Unit,
    suggestedTags: List<FavedTag> = emptyList(),
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    var quickTagName by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Tags",
            style = MaterialTheme.typography.labelMedium,
            color = secondaryText
        )
        Spacer(Modifier.height(6.dp))

        // Quick inline create tag (supports "Parent/Child" nested syntax!)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = quickTagName,
                onValueChange = { quickTagName = it },
                placeholder = { Text("Add tag (or Parent/Child)", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    val trimmed = quickTagName.trim()
                    if (trimmed.isNotBlank()) {
                        onQuickCreateTag(trimmed)
                        quickTagName = ""
                    }
                },
                enabled = quickTagName.isNotBlank()
            ) {
                Icon(Icons.Default.AddCircle, contentDescription = "Create tag", tint = textColor)
            }
        }

        // Smart auto-suggested tags
        if (suggestedTags.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Suggested:",
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryText
                )
                suggestedTags.forEach { tag ->
                    FavedTagPill(
                        tag = tag,
                        isDark = isDark,
                        selected = false,
                        onClick = { onToggleTag(tag.id) }
                    )
                }
            }
        }

        if (allTags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val sortedTags = remember(allTags) {
                allTags.sortedBy { FavedTagTreeHelper.buildHierarchyPath(it.id, allTags).lowercase(Locale.ROOT) }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                sortedTags.forEach { tag ->
                    val isSelected = tag.id in selectedTagIds
                    FavedTagPill(
                        tag = tag,
                        isDark = isDark,
                        showFullPath = true,
                        allTags = allTags,
                        selected = isSelected,
                        onClick = { onToggleTag(tag.id) }
                    )
                }
            }
        }
    }
}

/**
 * Bulk Tag Dialog for adding or removing tags across multiple selected bookmarks.
 */
@Composable
fun FavedBulkTagDialog(
    selectedCount: Int,
    allTags: List<FavedTag>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onQuickCreateAndAdd: (String) -> Unit,
    onDismiss: () -> Unit,
    isDark: Boolean
) {
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    val dialogSurface = if (isDark) Color(0xFF1E1E1E) else Color(0xFFFFFFFF)
    val borderCol = PocketThemeColors.divider(isDark)
    var newTagInput by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = dialogSurface,
            border = BorderStroke(1.dp, borderCol),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Bulk Tag ($selectedCount bookmarks)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = newTagInput,
                        onValueChange = { newTagInput = it },
                        placeholder = { Text("New tag (or Parent/Child)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (newTagInput.isNotBlank()) {
                                onQuickCreateAndAdd(newTagInput.trim())
                                newTagInput = ""
                            }
                        },
                        enabled = newTagInput.isNotBlank(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text("Add")
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (allTags.isEmpty()) {
                    Text(
                        text = "No tags created yet. Enter a tag name above to create and apply it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryText
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        allTags.sortedBy { FavedTagTreeHelper.buildHierarchyPath(it.id, allTags).lowercase(Locale.ROOT) }
                            .forEach { tag ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    FavedTagPill(
                                        tag = tag,
                                        isDark = isDark,
                                        showFullPath = true,
                                        allTags = allTags,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(
                                            onClick = { onAddTag(tag.id) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("+ Apply", fontSize = 12.sp)
                                        }
                                        TextButton(
                                            onClick = { onRemoveTag(tag.id) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("− Remove", fontSize = 12.sp, color = secondaryText)
                                        }
                                    }
                                }
                            }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Done", color = textColor, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Import & Export Dialog matching denho/faved's migration capabilities:
 * - Import from Chrome, Safari, Firefox, Edge HTML (preserves folders as nested tags)
 * - Import from Raindrop.io CSV, Pocket CSV/HTML, or JSON backup
 * - Export to Netscape Bookmarks HTML, CSV, or JSON backup
 */
@Composable
fun FavedImportExportDialog(
    bookmarkCount: Int,
    tagCount: Int,
    lastImportResult: BookmarkImportResult?,
    onImportFileClick: () -> Unit,
    onExportHtmlClick: () -> Unit,
    onExportCsvClick: () -> Unit,
    onExportJsonClick: () -> Unit,
    onDismiss: () -> Unit,
    isDark: Boolean
) {
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    val dialogSurface = if (isDark) Color(0xFF1E1E1E) else Color(0xFFFFFFFF)
    val cardSurface = PocketThemeColors.barSurface(isDark)
    val borderCol = PocketThemeColors.divider(isDark)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = dialogSurface,
            border = BorderStroke(1.dp, borderCol),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ImportExport, contentDescription = null, tint = textColor, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Import & Export Bookmarks",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "$bookmarkCount bookmarks · $tagCount nested tags stored locally",
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryText
                )

                Spacer(Modifier.height(14.dp))

                // Import Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = cardSurface,
                    border = BorderStroke(1.dp, borderCol),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Import & Migrate",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Supports Chrome, Safari, Firefox, Edge HTML (preserves folders as nested tags), Raindrop.io CSV, Pocket CSV/HTML, and JSON backups.",
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryText
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = onImportFileClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDark) Color(0xFFEEEEEE) else Color(0xFF202020),
                                contentColor = if (isDark) Color(0xFF141414) else Color(0xFFFFFFFF)
                            )
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Choose File to Import", fontWeight = FontWeight.SemiBold)
                        }

                        if (lastImportResult != null) {
                            Spacer(Modifier.height(8.dp))
                            val summary = if (lastImportResult.errorMessage != null) {
                                "Import error: ${lastImportResult.errorMessage}"
                            } else {
                                "${lastImportResult.detectedFormat}: imported ${lastImportResult.importedCount}, skipped ${lastImportResult.skippedDuplicates} duplicates, created ${lastImportResult.tagsCreated} tags."
                            }
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (lastImportResult.errorMessage != null) MaterialTheme.colorScheme.error else textColor
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Export Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = cardSurface,
                    border = BorderStroke(1.dp, borderCol),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Export Library",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onExportHtmlClick,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Text("HTML", fontSize = 12.sp, color = textColor)
                            }
                            OutlinedButton(
                                onClick = onExportCsvClick,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Text("CSV", fontSize = 12.sp, color = textColor)
                            }
                            OutlinedButton(
                                onClick = onExportJsonClick,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Text("JSON", fontSize = 12.sp, color = textColor)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = textColor)
                    }
                }
            }
        }
    }
}

/**
 * Compact List Layout item for bookmarks (denho/faved List view).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PocketBookmarkListCard(
    stack: MediaInboxStack,
    allTags: List<FavedTag>,
    displayFields: BookmarkDisplayFields,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTagClick: (String) -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cardSurface = PocketThemeColors.cardSurface(isDark)
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    val dividerCol = PocketThemeColors.divider(isDark)

    val resolvedTags = remember(stack.tagIds, allTags) {
        val byId = allTags.associateBy { it.id }
        stack.tagIds.mapNotNull { byId[it] }
    }
    val dateFormatted = remember(stack.timestampMillis) {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(stack.timestampMillis))
    }
    val previewModel = remember(stack) {
        stack.previewImageUrl.takeIf { it.isNotBlank() && !it.contains("google.com/s2/favicons", ignoreCase = true) }
            ?: stack.items.firstOrNull { !it.isVideo && !it.isAudio && !it.isStream && it.sourceUrl.isNotBlank() }?.let {
                it.localUri.takeIf { u -> u.isNotBlank() && (u.startsWith("content://") || File(u.removePrefix("file://")).exists()) } ?: it.sourceUrl
            }
            ?: stack.sourceDomain.takeIf { it.isNotBlank() }?.let { "https://www.google.com/s2/favicons?domain=$it&sz=128" }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cardSurface,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) textColor else dividerCol
        ),
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (displayFields.showPreview) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PocketThemeColors.thumbnailBg(isDark),
                    modifier = Modifier.size(64.dp)
                ) {
                    if (previewModel != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(previewModel).crossfade(true).build(),
                            contentDescription = stack.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Bookmark, contentDescription = null, tint = secondaryText)
                        }
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stack.title.ifBlank { stack.sourceDomain.ifBlank { "Untitled Bookmark" } },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (displayFields.showDescription && (stack.notes.isNotBlank() || stack.description.isNotBlank())) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stack.notes.ifBlank { stack.description },
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (displayFields.showDomain || displayFields.showDate) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (displayFields.showDomain && stack.sourceDomain.isNotBlank()) {
                            Text(
                                text = stack.sourceDomain,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (displayFields.showDomain && displayFields.showDate && stack.sourceDomain.isNotBlank()) {
                            Text("·", color = secondaryText, fontSize = 11.sp)
                        }
                        if (displayFields.showDate) {
                            Text(
                                text = dateFormatted,
                                style = MaterialTheme.typography.labelSmall,
                                color = secondaryText
                            )
                        }
                    }
                }

                if (displayFields.showTags && resolvedTags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        resolvedTags.forEach { tag ->
                            FavedTagPill(
                                tag = tag,
                                isDark = isDark,
                                onClick = { onTagClick(tag.id) }
                            )
                        }
                    }
                }
            }

            if (isSelectionMode) {
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "Selected" else "Not selected",
                    tint = if (isSelected) textColor else secondaryText,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * Dense Table Layout view for bookmarks (denho/faved Table view).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PocketBookmarkTableList(
    stacks: List<MediaInboxStack>,
    allTags: List<FavedTag>,
    displayFields: BookmarkDisplayFields,
    selectedStackIds: Set<String>,
    isSelectionMode: Boolean,
    onBookmarkClick: (MediaInboxStack) -> Unit,
    onBookmarkLongClick: (MediaInboxStack) -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    val barSurface = PocketThemeColors.barSurface(isDark)
    val dividerCol = PocketThemeColors.divider(isDark)
    val tagsById = remember(allTags) { allTags.associateBy { it.id } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .testTag("pocket_bookmarks_table"),
        contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp)
    ) {
        // Table Header
        item(key = "table_header") {
            Surface(
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                color = barSurface,
                border = BorderStroke(1.dp, dividerCol),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Title",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = secondaryText,
                        modifier = Modifier.weight(2.2f)
                    )
                    if (displayFields.showDomain) {
                        Text(
                            text = "Domain",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = secondaryText,
                            modifier = Modifier.weight(1.2f)
                        )
                    }
                    if (displayFields.showTags) {
                        Text(
                            text = "Tags",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = secondaryText,
                            modifier = Modifier.weight(1.3f)
                        )
                    }
                    if (displayFields.showDate) {
                        Text(
                            text = "Saved",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = secondaryText,
                            modifier = Modifier.width(68.dp)
                        )
                    }
                }
            }
        }

        itemsIndexed(stacks, key = { _, s -> s.id }) { idx, stack ->
            val isSelected = stack.id in selectedStackIds
            val dateShort = remember(stack.timestampMillis) {
                SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(stack.timestampMillis))
            }
            val tagNames = remember(stack.tagIds, tagsById) {
                stack.tagIds.mapNotNull { tagsById[it]?.title }.joinToString(", ")
            }
            val rowBg = when {
                isSelected -> if (isDark) Color(0xFF2D2D2D) else Color(0xFFEAEAEA)
                idx % 2 == 0 -> PocketThemeColors.cardSurface(isDark)
                else -> barSurface.copy(alpha = 0.5f)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(rowBg)
                    .combinedClickable(
                        onClick = { onBookmarkClick(stack) },
                        onLongClick = { onBookmarkLongClick(stack) }
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stack.title.ifBlank { stack.sourceDomain.ifBlank { "Untitled" } },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(2.2f)
                    )
                    if (displayFields.showDomain) {
                        Text(
                            text = stack.sourceDomain.removePrefix("www."),
                            style = MaterialTheme.typography.labelSmall,
                            color = secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1.2f)
                        )
                    }
                    if (displayFields.showTags) {
                        Text(
                            text = tagNames.ifBlank { "—" },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (tagNames.isNotBlank()) textColor else secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1.3f)
                        )
                    }
                    if (displayFields.showDate) {
                        Text(
                            text = dateShort,
                            style = MaterialTheme.typography.labelSmall,
                            color = secondaryText,
                            maxLines = 1,
                            modifier = Modifier.width(68.dp)
                        )
                    }
                }
                HorizontalDivider(color = dividerCol.copy(alpha = 0.6f), thickness = 0.5.dp)
            }
        }
    }
}
