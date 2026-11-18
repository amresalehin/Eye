package com.amresalehin.emregallery.ui.ytdlnis

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.amresalehin.emregallery.ytdlnis.*
import kotlinx.coroutines.launch

/**
 * Full YTDLnis Hub screen integrated inside Emre Gallery.
 * Tabs:
 * 1. Download (URL/Search/Bulk .txt, Quick Download, Playlist bulk editor & sorter, Download Card)
 * 2. Queue & History (Active/Queued/Scheduled/Completed/Error/Saved, retry/cancel/logs/open)
 * 3. Terminal & Templates (Built-in yt-dlp terminal + reusable command templates)
 * 4. Power & Observe (Observe Sources, Cookies + WebView import, PO Token, Backup/Restore, Cache)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtdlnisScreen(
    onBack: () -> Unit,
    initialUrl: String = "",
    onOpenMediaFile: ((String) -> Unit)? = null,
    onOpenBrowserForCookies: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(context) { YtdlnisRepository.getInstance(context) }

    val downloads by repo.downloads.collectAsState()
    val templates by repo.commandTemplates.collectAsState()
    val observeSources by repo.observeSources.collectAsState()
    val cookies by repo.cookies.collectAsState()
    val searchHistory by repo.searchHistory.collectAsState()
    val incognitoMode by repo.incognitoMode.collectAsState()
    val quickDownloadMode by repo.quickDownloadMode.collectAsState()
    val poToken by repo.youtubePoToken.collectAsState()
    val defaultTemplate by repo.defaultFilenameTemplate.collectAsState()
    val defaultSubfolder by repo.defaultSubfolderMode.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var urlInput by remember(initialUrl) { mutableStateOf(initialUrl) }
    var isFetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var fetchedEntries by remember { mutableStateOf<List<YtdlnisVideoInfo>>(emptyList()) }
    var fetchedPlaylistTitle by remember { mutableStateOf("") }
    var playlistSort by remember { mutableStateOf(YtdlnisPlaylistSort.DEFAULT) }
    var selectedPlaylistUrls by remember { mutableStateOf(setOf<String>()) }
    var bulkDownloadType by remember { mutableStateOf(YtdlnisDownloadType.VIDEO) }
    var editingInfo by remember { mutableStateOf<YtdlnisVideoInfo?>(null) }
    var viewingLogsItem by remember { mutableStateOf<YtdlnisDownloadItem?>(null) }

    val txtFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.onSuccess { text ->
                if (!text.isNullOrBlank()) {
                    urlInput = text
                    Toast.makeText(context, "Loaded links from file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun triggerFetch(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return
        repo.addSearchHistory(trimmed)
        val parsedBulk = YtdlnisEngine.parseBulkInputsLocal(trimmed)

        if (quickDownloadMode && parsedBulk.isNotEmpty()) {
            parsedBulk.forEach { (_, target) ->
                val item = YtdlnisDownloadItem(
                    url = target,
                    title = target,
                    type = bulkDownloadType,
                    container = if (bulkDownloadType == YtdlnisDownloadType.AUDIO) "m4a" else "mp4",
                    filenameTemplate = defaultTemplate,
                    subfolderMode = defaultSubfolder,
                    incognito = incognitoMode
                )
                YtdlnisDownloadWorker.enqueue(context, item)
            }
            Toast.makeText(context, "Quick Download queued ${parsedBulk.size} item(s)", Toast.LENGTH_SHORT).show()
            selectedTab = 1
            return
        }

        scope.launch {
            isFetching = true
            fetchError = null
            if (parsedBulk.size > 1) {
                // Multi-line bulk mode: turn each target into a selectable entry
                val entries = parsedBulk.mapIndexed { idx, (kind, target) ->
                    YtdlnisVideoInfo(
                        id = "bulk_$idx",
                        url = target,
                        title = if (kind == "search") target.removePrefix("ytsearch1:") else target,
                        extractor = if (kind == "search") "ytsearch" else (YtdlnisRepository.extractHost(target) ?: "url")
                    )
                }
                fetchedEntries = entries
                fetchedPlaylistTitle = "Bulk Import (${entries.size} items)"
                selectedPlaylistUrls = entries.map { it.url }.toSet()
                isFetching = false
            } else {
                val target = parsedBulk.firstOrNull()?.second ?: trimmed
                val res = YtdlnisEngine.fetchInfo(context, target, allowPlaylist = true)
                isFetching = false
                if (res.success && res.entries.isNotEmpty()) {
                    if (res.entries.size == 1 && !res.isPlaylist) {
                        editingInfo = res.entries.first()
                        fetchedEntries = emptyList()
                    } else {
                        fetchedEntries = res.entries
                        fetchedPlaylistTitle = res.playlistTitle.ifBlank { "Playlist (${res.entries.size})" }
                        selectedPlaylistUrls = res.entries.map { it.url }.toSet()
                    }
                } else {
                    fetchError = res.errorMessage ?: "Could not fetch media info"
                    // Save failed fetch so user can retry later (YTDLnis feature)
                    repo.upsertDownload(
                        YtdlnisDownloadItem(
                            url = target,
                            title = target,
                            status = YtdlnisDownloadStatus.ERROR,
                            errorMessage = fetchError,
                            incognito = incognitoMode
                        )
                    )
                }
            }
        }
    }

    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank()) {
            triggerFetch(initialUrl)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("ytdlnis_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("YTDLnis Downloader", fontWeight = FontWeight.SemiBold)
                        if (incognitoMode) {
                            Text(
                                "Incognito active · no history or logs saved",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        repo.setIncognitoMode(!incognitoMode)
                        Toast.makeText(
                            context,
                            if (!incognitoMode) "Incognito mode enabled" else "Incognito mode disabled",
                            Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Icon(
                            if (incognitoMode) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle Incognito",
                            tint = if (incognitoMode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Download") })
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Queue (${downloads.size})") }
                )
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Terminal") })
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("Power") })
            }

            when (selectedTab) {
                0 -> YtdlnisDownloadTab(
                    urlInput = urlInput,
                    onUrlChange = { urlInput = it },
                    isFetching = isFetching,
                    fetchError = fetchError,
                    quickDownloadMode = quickDownloadMode,
                    onToggleQuickDownload = { repo.setQuickDownloadMode(it) },
                    bulkDownloadType = bulkDownloadType,
                    onBulkTypeChange = { bulkDownloadType = it },
                    searchHistory = searchHistory,
                    onClearHistory = { repo.clearSearchHistory() },
                    onFetchClick = { triggerFetch(urlInput) },
                    onImportTxtClick = { txtFilePicker.launch("text/plain") },
                    fetchedEntries = fetchedEntries,
                    playlistTitle = fetchedPlaylistTitle,
                    playlistSort = playlistSort,
                    onPlaylistSortChange = { playlistSort = it },
                    selectedUrls = selectedPlaylistUrls,
                    onToggleSelectUrl = { u ->
                        selectedPlaylistUrls = if (u in selectedPlaylistUrls) selectedPlaylistUrls - u else selectedPlaylistUrls + u
                    },
                    onSelectAll = { selectedPlaylistUrls = fetchedEntries.map { it.url }.toSet() },
                    onEditEntry = { editingInfo = it },
                    onDownloadSelectedBulk = {
                        val targets = fetchedEntries.filter { it.url in selectedPlaylistUrls }
                        targets.forEach { entry ->
                            val item = YtdlnisDownloadItem(
                                url = entry.url,
                                title = entry.title,
                                author = entry.author,
                                durationSec = entry.durationSec,
                                durationString = entry.durationString,
                                thumbnailUrl = entry.thumbnail,
                                uploadDate = entry.uploadDate,
                                extractor = entry.extractor,
                                playlistTitle = fetchedPlaylistTitle,
                                type = bulkDownloadType,
                                container = if (bulkDownloadType == YtdlnisDownloadType.AUDIO) "m4a" else "mp4",
                                filenameTemplate = defaultTemplate,
                                subfolderMode = defaultSubfolder,
                                incognito = incognitoMode
                            )
                            YtdlnisDownloadWorker.enqueue(context, item)
                        }
                        Toast.makeText(context, "Queued ${targets.size} item(s)", Toast.LENGTH_SHORT).show()
                        selectedTab = 1
                    }
                )

                1 -> YtdlnisQueueHistoryTab(
                    downloads = downloads,
                    onRetry = { item ->
                        YtdlnisDownloadWorker.enqueue(context, item.copy(status = YtdlnisDownloadStatus.QUEUED))
                        Toast.makeText(context, "Re-queued ${item.title}", Toast.LENGTH_SHORT).show()
                    },
                    onCancel = { item -> YtdlnisDownloadWorker.cancel(context, item) },
                    onDelete = { item -> repo.removeDownload(item.id) },
                    onClearFinished = { repo.clearCompletedAndCancelled() },
                    onViewLogs = { viewingLogsItem = it },
                    onOpenFile = { path -> onOpenMediaFile?.invoke(path) }
                )

                2 -> YtdlnisTerminalAndTemplatesTab(
                    templates = templates,
                    onSaveTemplate = { repo.upsertTemplate(it) },
                    onDeleteTemplate = { repo.deleteTemplate(it) }
                )

                3 -> YtdlnisPowerTab(
                    repo = repo,
                    observeSources = observeSources,
                    cookies = cookies,
                    poToken = poToken,
                    defaultTemplate = defaultTemplate,
                    defaultSubfolder = defaultSubfolder,
                    onOpenBrowserForCookies = onOpenBrowserForCookies
                )
            }
        }
    }

    // Download Config Modal Bottom Sheet
    editingInfo?.let { info ->
        ModalBottomSheet(onDismissRequest = { editingInfo = null }) {
            YtdlnisDownloadConfigCard(
                initialInfo = info,
                templates = templates,
                defaultFilenameTemplate = defaultTemplate,
                defaultSubfolderMode = defaultSubfolder,
                incognito = incognitoMode,
                onEnqueue = { item ->
                    val dup = repo.findDuplicate(item.url, item.type)
                    if (dup != null) {
                        Toast.makeText(context, "Duplicate detected — enqueuing anyway", Toast.LENGTH_SHORT).show()
                    }
                    YtdlnisDownloadWorker.enqueue(context, item)
                    editingInfo = null
                    selectedTab = 1
                    Toast.makeText(
                        context,
                        if (item.scheduledTimeMillis > System.currentTimeMillis()) "Download scheduled" else "Download queued",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onSaveForLater = { item ->
                    repo.upsertDownload(item)
                    editingInfo = null
                    selectedTab = 1
                    Toast.makeText(context, "Saved for later", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { editingInfo = null }
            )
        }
    }

    // Per-item Download Log Viewer Dialog
    viewingLogsItem?.let { item ->
        AlertDialog(
            onDismissRequest = { viewingLogsItem = null },
            title = { Text("Download Logs") },
            text = {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 360.dp)
                ) {
                    LazyColumn(modifier = Modifier.padding(10.dp)) {
                        item {
                            Text(
                                text = item.logOutput.ifBlank { item.errorMessage ?: "No logs recorded (or Incognito was active)." },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewingLogsItem = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun YtdlnisDownloadTab(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    isFetching: Boolean,
    fetchError: String?,
    quickDownloadMode: Boolean,
    onToggleQuickDownload: (Boolean) -> Unit,
    bulkDownloadType: YtdlnisDownloadType,
    onBulkTypeChange: (YtdlnisDownloadType) -> Unit,
    searchHistory: List<String>,
    onClearHistory: () -> Unit,
    onFetchClick: () -> Unit,
    onImportTxtClick: () -> Unit,
    fetchedEntries: List<YtdlnisVideoInfo>,
    playlistTitle: String,
    playlistSort: YtdlnisPlaylistSort,
    onPlaylistSortChange: (YtdlnisPlaylistSort) -> Unit,
    selectedUrls: Set<String>,
    onToggleSelectUrl: (String) -> Unit,
    onSelectAll: () -> Unit,
    onEditEntry: (YtdlnisVideoInfo) -> Unit,
    onDownloadSelectedBulk: () -> Unit
) {
    val sortedEntries = remember(fetchedEntries, playlistSort) {
        when (playlistSort) {
            YtdlnisPlaylistSort.DEFAULT -> fetchedEntries
            YtdlnisPlaylistSort.TITLE -> fetchedEntries.sortedBy { it.title.lowercase() }
            YtdlnisPlaylistSort.AUTHOR -> fetchedEntries.sortedBy { it.author.lowercase() }
            YtdlnisPlaylistSort.DURATION -> fetchedEntries.sortedByDescending { it.durationSec }
            YtdlnisPlaylistSort.DATE -> fetchedEntries.sortedByDescending { it.uploadDate }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlChange,
                label = { Text("URL, playlist, search query, or multi-line list") },
                placeholder = { Text("https://youtube.com/... or search term") },
                minLines = 2,
                maxLines = 5,
                trailingIcon = {
                    if (urlInput.isNotBlank()) {
                        IconButton(onClick = { onUrlChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onFetchClick,
                    enabled = !isFetching && urlInput.isNotBlank(),
                    modifier = Modifier.weight(1.4f)
                ) {
                    if (isFetching) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Fetching...")
                    } else {
                        Icon(
                            if (quickDownloadMode) Icons.Default.FlashOn else Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (quickDownloadMode) "Quick Download" else "Fetch & Configure")
                    }
                }
                OutlinedButton(
                    onClick = onImportTxtClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(".txt Bulk")
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = quickDownloadMode, onCheckedChange = onToggleQuickDownload)
                    Spacer(Modifier.width(8.dp))
                    Text("Quick Download (skip fetch card)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (fetchError != null) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = fetchError,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        if (searchHistory.isNotEmpty() && fetchedEntries.isEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Recent Searches & URLs", style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = onClearHistory) { Text("Clear") }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(searchHistory.take(10)) { q ->
                            SuggestionChip(
                                onClick = { onUrlChange(q) },
                                label = { Text(q.take(36), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
            }
        }

        // Playlist / Bulk Editor
        if (sortedEntries.isNotEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$playlistTitle · ${selectedUrls.size}/${sortedEntries.size} selected",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onSelectAll) { Text("Select All") }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(YtdlnisPlaylistSort.entries) { s ->
                                FilterChip(
                                    selected = playlistSort == s,
                                    onClick = { onPlaylistSortChange(s) },
                                    label = { Text("Sort: ${s.label}") }
                                )
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf(YtdlnisDownloadType.VIDEO, YtdlnisDownloadType.AUDIO)) { t ->
                                FilterChip(
                                    selected = bulkDownloadType == t,
                                    onClick = { onBulkTypeChange(t) },
                                    label = { Text("Bulk Type: ${t.label}") }
                                )
                            }
                        }
                        Button(
                            onClick = onDownloadSelectedBulk,
                            enabled = selectedUrls.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Download ${selectedUrls.size} Selected as ${bulkDownloadType.label}")
                        }
                    }
                }
            }

            items(sortedEntries, key = { it.url }) { entry ->
                val checked = entry.url in selectedUrls
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleSelectUrl(entry.url) }
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { onToggleSelectUrl(entry.url) })
                        if (entry.thumbnail.isNotBlank()) {
                            AsyncImage(
                                model = entry.thumbnail,
                                contentDescription = entry.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 72.dp, height = 44.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                entry.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                listOf(entry.author, entry.durationString, entry.extractor)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = { onEditEntry(entry) }) {
                            Icon(Icons.Default.Tune, contentDescription = "Configure item")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YtdlnisQueueHistoryTab(
    downloads: List<YtdlnisDownloadItem>,
    onRetry: (YtdlnisDownloadItem) -> Unit,
    onCancel: (YtdlnisDownloadItem) -> Unit,
    onDelete: (YtdlnisDownloadItem) -> Unit,
    onClearFinished: () -> Unit,
    onViewLogs: (YtdlnisDownloadItem) -> Unit,
    onOpenFile: (String) -> Unit
) {
    var filter by remember { mutableStateOf("All") }
    val filtered = remember(downloads, filter) {
        when (filter) {
            "Active" -> downloads.filter { it.status == YtdlnisDownloadStatus.ACTIVE || it.status == YtdlnisDownloadStatus.QUEUED }
            "Scheduled" -> downloads.filter { it.status == YtdlnisDownloadStatus.SCHEDULED }
            "Completed" -> downloads.filter { it.status == YtdlnisDownloadStatus.COMPLETED }
            "Error" -> downloads.filter { it.status == YtdlnisDownloadStatus.ERROR || it.status == YtdlnisDownloadStatus.CANCELLED }
            "Saved" -> downloads.filter { it.status == YtdlnisDownloadStatus.SAVED }
            else -> downloads
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(listOf("All", "Active", "Scheduled", "Completed", "Error", "Saved")) { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) })
                    }
                }
                IconButton(onClick = onClearFinished) {
                    Icon(Icons.Default.CleaningServices, contentDescription = "Clear finished")
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No downloads in $filter",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(filtered, key = { it.id }) { item ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.title.ifBlank { item.url },
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${item.type.label} · ${item.container.uppercase()} · ${item.status.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = { onViewLogs(item) }) {
                                Icon(Icons.Default.Description, contentDescription = "Logs")
                            }
                            if (item.status == YtdlnisDownloadStatus.ACTIVE || item.status == YtdlnisDownloadStatus.QUEUED || item.status == YtdlnisDownloadStatus.SCHEDULED) {
                                IconButton(onClick = { onCancel(item) }) {
                                    Icon(Icons.Default.Cancel, contentDescription = "Cancel")
                                }
                            } else {
                                IconButton(onClick = { onRetry(item) }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Retry")
                                }
                            }
                            IconButton(onClick = { onDelete(item) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Remove")
                            }
                        }
                        if (item.status == YtdlnisDownloadStatus.ACTIVE) {
                            LinearProgressIndicator(
                                progress = { item.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (!item.errorMessage.isNullOrBlank()) {
                            Text(
                                item.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (item.status == YtdlnisDownloadStatus.COMPLETED && item.outputFilePath.isNotBlank()) {
                            TextButton(onClick = { onOpenFile(item.outputFilePath) }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Open in Gallery")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YtdlnisTerminalAndTemplatesTab(
    templates: List<YtdlnisCommandTemplate>,
    onSaveTemplate: (YtdlnisCommandTemplate) -> Unit,
    onDeleteTemplate: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cmdInput by remember { mutableStateOf("yt-dlp --version") }
    var terminalLog by remember { mutableStateOf("Emre Gallery YTDLnis Terminal ready.\nType `help` or `yt-dlp [OPTIONS] URL`.\n") }
    var isRunning by remember { mutableStateOf(false) }

    var newTplTitle by remember { mutableStateOf("") }
    var newTplCmd by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Text("Built-in yt-dlp Terminal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 260.dp)
            ) {
                LazyColumn(modifier = Modifier.padding(12.dp)) {
                    item {
                        Text(
                            text = terminalLog,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = cmdInput,
                    onValueChange = { cmdInput = it },
                    label = { Text("Command") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val toRun = cmdInput.trim()
                        if (toRun.isNotBlank()) {
                            scope.launch {
                                isRunning = true
                                terminalLog += "\n$ $toRun\n"
                                val res = YtdlnisEngine.runTerminalCommand(context, toRun)
                                terminalLog += res.output + "\n"
                                isRunning = false
                            }
                        }
                    },
                    enabled = !isRunning && cmdInput.isNotBlank()
                ) {
                    Text(if (isRunning) "..." else "Run")
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf("yt-dlp --version", "status", "help", "python --version")) { quick ->
                    AssistChip(onClick = { cmdInput = quick }, label = { Text(quick) })
                }
            }
        }

        item {
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            Text("Command Templates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newTplTitle,
                        onValueChange = { newTplTitle = it },
                        label = { Text("Template Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newTplCmd,
                        onValueChange = { newTplCmd = it },
                        label = { Text("yt-dlp Flags") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            if (newTplTitle.isNotBlank() && newTplCmd.isNotBlank()) {
                                onSaveTemplate(YtdlnisCommandTemplate(title = newTplTitle.trim(), command = newTplCmd.trim()))
                                newTplTitle = ""
                                newTplCmd = ""
                                Toast.makeText(context, "Template saved", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = newTplTitle.isNotBlank() && newTplCmd.isNotBlank()
                    ) {
                        Text("Save Template")
                    }
                }
            }
        }

        items(templates, key = { it.id }) { tpl ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tpl.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            tpl.command,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { cmdInput = "yt-dlp ${tpl.command} " }) {
                        Icon(Icons.Default.Terminal, contentDescription = "Use in Terminal")
                    }
                    IconButton(onClick = { onDeleteTemplate(tpl.id) }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun YtdlnisPowerTab(
    repo: YtdlnisRepository,
    observeSources: List<YtdlnisObserveSource>,
    cookies: List<YtdlnisCookieEntry>,
    poToken: String,
    defaultTemplate: String,
    defaultSubfolder: YtdlnisSubfolderMode,
    onOpenBrowserForCookies: ((String) -> Unit)?
) {
    val context = LocalContext.current
    var obsName by remember { mutableStateOf("") }
    var obsUrl by remember { mutableStateOf("") }
    var cookieDomain by remember { mutableStateOf("youtube.com") }
    var cookieText by remember { mutableStateOf("") }
    var poTokenInput by remember(poToken) { mutableStateOf(poToken) }
    var defaultTplInput by remember(defaultTemplate) { mutableStateOf(defaultTemplate) }
    var backupText by remember { mutableStateOf("") }
    var cacheBytes by remember { mutableStateOf(repo.calculateCacheSizeBytes()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // Observe Sources
        item {
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Observe Sources (Auto-Download New Uploads)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = obsName,
                        onValueChange = { obsName = it },
                        label = { Text("Channel / Playlist Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = obsUrl,
                        onValueChange = { obsUrl = it },
                        label = { Text("Playlist or Channel URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            if (obsName.isNotBlank() && obsUrl.isNotBlank()) {
                                repo.upsertObserveSource(YtdlnisObserveSource(name = obsName.trim(), url = obsUrl.trim()))
                                YtdlnisObserveWorker.schedulePeriodic(context, 12L)
                                obsName = ""
                                obsUrl = ""
                                Toast.makeText(context, "Observe source added & scheduled", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = obsName.isNotBlank() && obsUrl.isNotBlank()
                    ) {
                        Text("Add & Watch Source")
                    }
                    observeSources.forEach { src ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(src.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    "${src.url} · ${src.downloadedVideoIds.size} tracked",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = src.enabled,
                                onCheckedChange = { repo.upsertObserveSource(src.copy(enabled = it)) }
                            )
                            IconButton(onClick = { repo.deleteObserveSource(src.id) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }

        // Cookies & WebView Import
        item {
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cookies (Private / Premium Content)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = cookieDomain,
                        onValueChange = { cookieDomain = it },
                        label = { Text("Domain (e.g. youtube.com)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            val targetUrl = if (cookieDomain.startsWith("http")) cookieDomain else "https://$cookieDomain"
                            val ok = repo.importCookiesFromWebView(targetUrl)
                            Toast.makeText(
                                context,
                                if (ok) "Imported cookies from built-in browser" else "No WebView cookies found — log in via Browser first",
                                Toast.LENGTH_SHORT
                            ).show()
                        }) {
                            Text("Import from Browser")
                        }
                        if (onOpenBrowserForCookies != null) {
                            OutlinedButton(onClick = {
                                val targetUrl = if (cookieDomain.startsWith("http")) cookieDomain else "https://$cookieDomain"
                                onOpenBrowserForCookies(targetUrl)
                            }) {
                                Text("Open Browser")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = cookieText,
                        onValueChange = { cookieText = it },
                        label = { Text("Or paste Netscape cookies.txt") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            repo.upsertCookie(cookieDomain, cookieText)
                            cookieText = ""
                            Toast.makeText(context, "Cookies saved (encrypted)", Toast.LENGTH_SHORT).show()
                        },
                        enabled = cookieDomain.isNotBlank() && cookieText.isNotBlank()
                    ) {
                        Text("Save Cookies")
                    }
                    cookies.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.domain, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { repo.deleteCookie(c.id) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete cookie")
                            }
                        }
                    }
                }
            }
        }

        // YouTube PO Token & Default Filename Template
        item {
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("YouTube PO Token & Defaults", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = poTokenInput,
                        onValueChange = { poTokenInput = it },
                        label = { Text("YouTube PO Token (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = defaultTplInput,
                        onValueChange = { defaultTplInput = it },
                        label = { Text("Default Filename Template") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(YtdlnisSubfolderMode.entries) { m ->
                            FilterChip(
                                selected = defaultSubfolder == m,
                                onClick = { repo.setDefaultSubfolderMode(m) },
                                label = { Text(m.label) }
                            )
                        }
                    }
                    Button(onClick = {
                        repo.setYoutubePoToken(poTokenInput)
                        repo.setDefaultFilenameTemplate(defaultTplInput)
                        Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Save Defaults")
                    }
                }
            }
        }

        // Backup & Restore + Cache Cleaner + Tasker Intent Guide
        item {
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Backup, Restore & Cache", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            val exported = repo.exportBackupJson()
                            backupText = exported
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("YTDLnis Backup", exported))
                            Toast.makeText(context, "Backup JSON copied to clipboard", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Export Backup")
                        }
                        OutlinedButton(
                            onClick = {
                                val res = repo.importBackupJson(backupText)
                                Toast.makeText(
                                    context,
                                    res.fold(
                                        onSuccess = { "Restored $it items" },
                                        onFailure = { "Restore failed: ${it.message}" }
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            enabled = backupText.isNotBlank()
                        ) {
                            Text("Restore JSON")
                        }
                    }
                    OutlinedTextField(
                        value = backupText,
                        onValueChange = { backupText = it },
                        label = { Text("Backup JSON payload") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Temp cache: ${formatByteSize(cacheBytes).ifBlank { "0 B" }}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = {
                            val freed = repo.clearCacheAndLeftovers()
                            cacheBytes = repo.calculateCacheSizeBytes()
                            Toast.makeText(context, "Cleared ${formatByteSize(freed).ifBlank { "0 B" }}", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Clear Cache")
                        }
                    }
                    Text(
                        "Tasker / Automation Intent: ACTION_SEND (text/plain) with EXTRA_TEXT=<url>, TYPE=audio|video|command, BACKGROUND=true",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
