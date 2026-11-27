package com.amresalehin.emregallery.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
    addDescription: String = "Create"
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp).testTag("collection_browser_back")) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor, modifier = Modifier.size(20.dp))
            }
            Icon(Icons.Default.Search, contentDescription = null, tint = secondaryColor, modifier = Modifier.padding(start = 2.dp, end = 8.dp).size(20.dp))
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), color = secondaryColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("collection_browser_search"),
                    textStyle = TextStyle(color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Normal),
                    singleLine = true,
                    cursorBrush = SolidColor(textColor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {})
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(40.dp).testTag("collection_browser_clear")) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search", tint = secondaryColor, modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = onOpenViewOptions, modifier = Modifier.size(40.dp).testTag("collection_browser_view_options")) {
                Icon(Icons.Default.Tune, contentDescription = "View and sort options", tint = textColor, modifier = Modifier.size(20.dp))
            }
            if (onAdd != null) {
                IconButton(onClick = onAdd, modifier = Modifier.size(40.dp).testTag("collection_browser_add")) {
                    Icon(Icons.Default.Add, contentDescription = addDescription, tint = textColor, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

data class CollectionBrowserGroup<T>(val key: String, val label: String, val items: List<T>)

@Composable
fun BrowserGroupHeader(label: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun <T> groupCollectionBrowserItems(
    items: List<T>,
    groupBy: String,
    title: (T) -> String,
    folder: (T) -> String,
    timestampMillis: (T) -> Long
): List<CollectionBrowserGroup<T>> {
    if (items.isEmpty()) return emptyList()
    if (groupBy == "none" || groupBy.isBlank()) return listOf(CollectionBrowserGroup("all", "", items))
    val grouped = LinkedHashMap<String, MutableList<T>>()
    val labels = LinkedHashMap<String, String>()
    val today = Calendar.getInstance().apply { normalizeToDay() }
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }

    items.forEach { item ->
        val timestamp = timestampMillis(item)
        val (key, label) = when (groupBy) {
            "folder" -> {
                val value = folder(item).ifBlank { "Other" }
                "folder:$value" to value
            }
            "initial" -> {
                val value = title(item).trim().firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() }?.toString() ?: "#"
                "initial:$value" to value
            }
            "day", "month", "year" -> {
                if (timestamp <= 0L) {
                    "date:unknown" to "Unknown date"
                } else {
                    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
                    when (groupBy) {
                        "day" -> {
                            val start = (cal.clone() as Calendar).apply { normalizeToDay() }
                            val label = when (start.timeInMillis) {
                                today.timeInMillis -> "Today"
                                yesterday.timeInMillis -> "Yesterday"
                                else -> SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(timestamp))
                            }
                            "day:${start.timeInMillis}" to label
                        }
                        "month" -> {
                            val start = (cal.clone() as Calendar).apply {
                                set(Calendar.DAY_OF_MONTH, 1)
                                normalizeToDay()
                            }
                            "month:${start.get(Calendar.YEAR)}-${start.get(Calendar.MONTH)}" to SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(timestamp))
                        }
                        else -> "year:${cal.get(Calendar.YEAR)}" to cal.get(Calendar.YEAR).toString()
                    }
                }
            }
            else -> "all" to ""
        }
        grouped.getOrPut(key) { mutableListOf() }.add(item)
        labels.putIfAbsent(key, label)
    }
    return grouped.map { (key, values) -> CollectionBrowserGroup(key, labels[key].orEmpty(), values) }
}

private fun Calendar.normalizeToDay() {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}
