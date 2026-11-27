package com.amresalehin.emregallery.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amresalehin.emregallery.data.MediaItem

@Composable
fun CollectionBrowserSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onBack: () -> Unit,
    onOpenViewOptions: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onAdd: (() -> Unit)? = null,
    addDescription: String = "Add photos"
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp).testTag("collection_media_back")) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor, modifier = Modifier.size(20.dp))
            }
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = secondaryColor,
                modifier = Modifier.padding(start = 2.dp, end = 8.dp).size(20.dp)
            )
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("collection_media_search"),
                    textStyle = TextStyle(color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Normal),
                    singleLine = true,
                    cursorBrush = SolidColor(textColor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {})
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(40.dp).testTag("collection_media_clear")) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search", tint = secondaryColor, modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = onOpenViewOptions, modifier = Modifier.size(40.dp).testTag("collection_media_view_options")) {
                Icon(Icons.Default.Tune, contentDescription = "View and sort options", tint = textColor, modifier = Modifier.size(20.dp))
            }
            if (onAdd != null) {
                IconButton(onClick = onAdd, modifier = Modifier.size(40.dp).testTag("collection_media_add")) {
                    Icon(Icons.Default.Add, contentDescription = addDescription, tint = textColor, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionMediaListRow(
    item: MediaItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag("collection_media_row_" + item.id),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = remember(item.uri, context) {
                    ThumbnailUtils.buildThumbnailRequest(context, item.uri)
                },
                contentDescription = item.name,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    item.relativePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onSurface)
            } else if (item.isVideo) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Video", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
