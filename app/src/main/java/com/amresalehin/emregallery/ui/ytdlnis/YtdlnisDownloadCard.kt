package com.amresalehin.emregallery.ui.ytdlnis

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.amresalehin.emregallery.ytdlnis.*
import kotlinx.coroutines.launch

/**
 * Reusable YTDLnis Download Configuration Card / Bottom Sheet content.
 * Supports Audio / Video / Custom Command modes, editable title & author,
 * format & multi-audio selection, container picker, live filename template preview,
 * timestamp/chapter cuts, crop presets, SponsorBlock, subtitles, metadata/chapters,
 * compatible mode, lrclib.net synced lyrics + timing offset, and date/time scheduling.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun YtdlnisDownloadConfigCard(
    initialInfo: YtdlnisVideoInfo,
    templates: List<YtdlnisCommandTemplate>,
    defaultFilenameTemplate: String,
    defaultSubfolderMode: YtdlnisSubfolderMode,
    incognito: Boolean,
    onEnqueue: (YtdlnisDownloadItem) -> Unit,
    onSaveForLater: (YtdlnisDownloadItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var downloadType by remember(initialInfo.url) { mutableStateOf(YtdlnisDownloadType.VIDEO) }
    var title by remember(initialInfo.url) { mutableStateOf(initialInfo.title) }
    var author by remember(initialInfo.url) { mutableStateOf(initialInfo.author) }
    var selectedFormatId by remember(initialInfo.url) { mutableStateOf("best") }
    var selectedAudioIds by remember(initialInfo.url) { mutableStateOf(setOf<String>()) }
    var container by remember(initialInfo.url) { mutableStateOf("mp4") }
    var filenameTemplate by remember(initialInfo.url) { mutableStateOf(defaultFilenameTemplate) }
    var subfolderMode by remember(initialInfo.url) { mutableStateOf(defaultSubfolderMode) }
    var customCommand by remember(initialInfo.url) { mutableStateOf("") }

    // Editing options
    var cutSections by remember(initialInfo.url) { mutableStateOf(listOf<YtdlnisCutSection>()) }
    var mergeCuts by remember(initialInfo.url) { mutableStateOf(true) }
    var newCutStart by remember { mutableStateOf("00:00") }
    var newCutEnd by remember { mutableStateOf("00:30") }
    var cropPreset by remember { mutableStateOf("Original") }
    var splitByChapters by remember { mutableStateOf(false) }

    // SponsorBlock
    var sbAction by remember { mutableStateOf(YtdlnisSponsorBlockAction.NONE) }
    var sbCategories by remember { mutableStateOf(setOf("sponsor", "intro", "outro")) }

    // Metadata, Subtitles, Compatible Mode
    var embedMetadata by remember { mutableStateOf(true) }
    var embedThumbnail by remember { mutableStateOf(true) }
    var embedChapters by remember { mutableStateOf(true) }
    var downloadSubtitles by remember { mutableStateOf(false) }
    var embedSubtitles by remember { mutableStateOf(false) }
    var burnSubtitles by remember { mutableStateOf(false) }
    var subtitleLangsInput by remember { mutableStateOf("en") }
    var compatibleMode by remember { mutableStateOf(false) }

    // Schedule delay (minutes from now, 0 = immediate)
    var scheduleDelayMinutes by remember { mutableStateOf(0) }

    // Lyrics (lrclib.net)
    var showLyricsDialog by remember { mutableStateOf(false) }
    var lyricsText by remember { mutableStateOf("") }
    var saveLrcFile by remember { mutableStateOf(false) }

    val videoFormats = remember(initialInfo.formats) {
        initialInfo.formats.filter { it.hasVideo }.sortedByDescending { it.height }
    }
    val audioFormats = remember(initialInfo.formats) {
        initialInfo.formats.filter { it.hasAudio && !it.hasVideo }.sortedByDescending { it.abr }
    }

    val livePreviewName = remember(filenameTemplate, title, author, container, downloadType) {
        val ext = if (downloadType == YtdlnisDownloadType.AUDIO) {
            container.takeIf { it in setOf("mp3", "m4a", "opus", "flac", "wav") } ?: "m4a"
        } else {
            container.takeIf { it in setOf("mp4", "mkv", "webm") } ?: "mp4"
        }
        YtdlnisEngine.previewFilenameTemplate(
            template = filenameTemplate,
            title = title,
            author = author,
            ext = ext,
            extractor = initialInfo.extractor,
            playlistTitle = initialInfo.playlistTitle,
            uploadDate = initialInfo.uploadDate
        )
    }

    fun buildDownloadItem(): YtdlnisDownloadItem {
        val effContainer = when (downloadType) {
            YtdlnisDownloadType.AUDIO -> container.takeIf { it in setOf("mp3", "m4a", "opus", "flac", "wav") } ?: "m4a"
            YtdlnisDownloadType.VIDEO -> container.takeIf { it in setOf("mp4", "mkv", "webm") } ?: "mp4"
            YtdlnisDownloadType.COMMAND -> container
        }
        val scheduledAt = if (scheduleDelayMinutes > 0) {
            System.currentTimeMillis() + scheduleDelayMinutes * 60_000L
        } else 0L

        return YtdlnisDownloadItem(
            url = initialInfo.url,
            title = title.trim().ifBlank { initialInfo.title },
            author = author.trim(),
            durationSec = initialInfo.durationSec,
            durationString = initialInfo.durationString,
            thumbnailUrl = initialInfo.thumbnail,
            uploadDate = initialInfo.uploadDate,
            extractor = initialInfo.extractor,
            playlistTitle = initialInfo.playlistTitle,
            type = downloadType,
            selectedFormatId = selectedFormatId,
            selectedAudioFormatIds = selectedAudioIds.toList(),
            container = effContainer,
            filenameTemplate = filenameTemplate.trim().ifBlank { "%(title)s.%(ext)s" },
            subfolderMode = subfolderMode,
            cutSections = cutSections,
            mergeCutSections = mergeCuts,
            cropConfig = YtdlnisCropConfig(enabled = cropPreset != "Original", aspectPreset = cropPreset),
            splitByChapters = splitByChapters,
            sponsorBlockAction = sbAction,
            sponsorBlockCategories = if (sbAction == YtdlnisSponsorBlockAction.NONE) emptySet() else sbCategories,
            embedMetadata = embedMetadata,
            embedThumbnail = embedThumbnail,
            embedChapters = embedChapters,
            downloadSubtitles = downloadSubtitles || embedSubtitles || burnSubtitles,
            embedSubtitles = embedSubtitles,
            burnSubtitles = burnSubtitles,
            subtitleLanguages = subtitleLangsInput.split(",").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf("en") },
            compatibleMode = compatibleMode,
            lyricsText = lyricsText,
            saveLrcFile = saveLrcFile,
            customCommand = customCommand.trim(),
            scheduledTimeMillis = scheduledAt,
            incognito = incognito
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
    ) {
        // Header with thumbnail & metadata
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (initialInfo.thumbnail.isNotBlank()) {
                    AsyncImage(
                        model = initialInfo.thumbnail,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 104.dp, height = 64.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = initialInfo.extractor.ifBlank { "yt-dlp" } +
                            if (initialInfo.durationString.isNotBlank()) " · ${initialInfo.durationString}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = title.ifBlank { initialInfo.url },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        }

        // Mode switcher: Audio / Video / Command
        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                YtdlnisDownloadType.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = downloadType == mode,
                        onClick = {
                            downloadType = mode
                            container = when (mode) {
                                YtdlnisDownloadType.AUDIO -> "m4a"
                                YtdlnisDownloadType.VIDEO -> "mp4"
                                YtdlnisDownloadType.COMMAND -> container
                            }
                            selectedFormatId = "best"
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, YtdlnisDownloadType.entries.size),
                        icon = {
                            Icon(
                                when (mode) {
                                    YtdlnisDownloadType.AUDIO -> Icons.Default.Audiotrack
                                    YtdlnisDownloadType.VIDEO -> Icons.Default.Videocam
                                    YtdlnisDownloadType.COMMAND -> Icons.Default.Terminal
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    ) {
                        Text(mode.label)
                    }
                }
            }
        }

        // Editable Title & Author
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.weight(1.4f)
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Format & Container Selection OR Custom Command Editor
        if (downloadType == YtdlnisDownloadType.COMMAND) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Command Templates", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(templates) { tpl ->
                            SuggestionChip(
                                onClick = { customCommand = tpl.command },
                                label = { Text(tpl.title) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = customCommand,
                        onValueChange = { customCommand = it },
                        label = { Text("Custom yt-dlp flags") },
                        placeholder = { Text("-f bestvideo+bestaudio --merge-output-format mp4") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            // Container chips
            item {
                val containers = if (downloadType == YtdlnisDownloadType.AUDIO) {
                    listOf("m4a", "mp3", "opus", "flac", "wav")
                } else {
                    listOf("mp4", "mkv", "webm")
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Container & Format", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(containers) { ext ->
                            FilterChip(
                                selected = container.equals(ext, ignoreCase = true),
                                onClick = { container = ext },
                                label = { Text(ext.uppercase()) }
                            )
                        }
                    }
                    val formats = if (downloadType == YtdlnisDownloadType.AUDIO) audioFormats else videoFormats
                    if (formats.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(
                                    selected = selectedFormatId == "best",
                                    onClick = { selectedFormatId = "best" },
                                    label = { Text("Best Auto") }
                                )
                            }
                            items(formats.take(15)) { fmt ->
                                FilterChip(
                                    selected = selectedFormatId == fmt.formatId,
                                    onClick = { selectedFormatId = fmt.formatId },
                                    label = { Text(fmt.displayLabel) }
                                )
                            }
                        }
                    }
                    // Multi-audio track selection for Video mode
                    if (downloadType == YtdlnisDownloadType.VIDEO && audioFormats.isNotEmpty()) {
                        Text(
                            "Audio Tracks (optional multi-audio)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(audioFormats.take(10)) { af ->
                                val selected = af.formatId in selectedAudioIds
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        selectedAudioIds = if (selected) selectedAudioIds - af.formatId else selectedAudioIds + af.formatId
                                    },
                                    label = { Text(af.displayLabel) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Filename template with live preview & subfolder mode
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = filenameTemplate,
                    onValueChange = { filenameTemplate = it },
                    label = { Text("Filename Template") },
                    singleLine = true,
                    supportingText = { Text("Preview: $livePreviewName") },
                    modifier = Modifier.fillMaxWidth()
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(YtdlnisSubfolderMode.entries) { mode ->
                        FilterChip(
                            selected = subfolderMode == mode,
                            onClick = { subfolderMode = mode },
                            label = { Text(mode.label) }
                        )
                    }
                }
            }
        }

        // Cut Video / Audio by Timestamps or Chapters
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Cut & Chapters", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        if (cutSections.size > 1) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Merge cuts", style = MaterialTheme.typography.labelSmall)
                                Checkbox(checked = mergeCuts, onCheckedChange = { mergeCuts = it })
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newCutStart,
                            onValueChange = { newCutStart = it },
                            label = { Text("Start") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = newCutEnd,
                            onValueChange = { newCutEnd = it },
                            label = { Text("End") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(
                            onClick = {
                                if (newCutStart.isNotBlank() && newCutEnd.isNotBlank()) {
                                    cutSections = cutSections + YtdlnisCutSection(newCutStart.trim(), newCutEnd.trim())
                                }
                            }
                        ) {
                            Text("Add")
                        }
                    }
                    if (initialInfo.chapters.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(initialInfo.chapters) { ch ->
                                AssistChip(
                                    onClick = {
                                        val s = formatSecToTs(ch.startTimeSec)
                                        val e = formatSecToTs(ch.endTimeSec)
                                        cutSections = cutSections + YtdlnisCutSection(s, e, ch.title)
                                    },
                                    label = { Text("+ ${ch.title.take(22)}") }
                                )
                            }
                        }
                    }
                    if (cutSections.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            cutSections.forEachIndexed { idx, cut ->
                                InputChip(
                                    selected = true,
                                    onClick = { cutSections = cutSections.filterIndexed { i, _ -> i != idx } },
                                    label = { Text("${cut.startTime}–${cut.endTime}") },
                                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp)) }
                                )
                            }
                        }
                    }
                    if (initialInfo.chapters.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = splitByChapters, onCheckedChange = { splitByChapters = it })
                            Text("Split into separate files by chapters", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Crop, SponsorBlock, Subtitles, Metadata, Compatible Mode, Lyrics
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
                    Text("Editing & Post-Processing", style = MaterialTheme.typography.labelLarge)
                    if (downloadType == YtdlnisDownloadType.VIDEO) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf("Original", "16:9", "9:16", "1:1", "4:3")) { preset ->
                                FilterChip(
                                    selected = cropPreset == preset,
                                    onClick = { cropPreset = preset },
                                    label = { Text("Crop: $preset") }
                                )
                            }
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = embedMetadata,
                            onClick = { embedMetadata = !embedMetadata },
                            label = { Text("Metadata") }
                        )
                        FilterChip(
                            selected = embedThumbnail,
                            onClick = { embedThumbnail = !embedThumbnail },
                            label = { Text("Thumbnail") }
                        )
                        FilterChip(
                            selected = embedChapters,
                            onClick = { embedChapters = !embedChapters },
                            label = { Text("Chapters") }
                        )
                        FilterChip(
                            selected = compatibleMode,
                            onClick = { compatibleMode = !compatibleMode },
                            label = { Text("Compatible H.264") }
                        )
                        FilterChip(
                            selected = downloadSubtitles,
                            onClick = { downloadSubtitles = !downloadSubtitles },
                            label = { Text("Subtitles") }
                        )
                        if (downloadSubtitles && downloadType == YtdlnisDownloadType.VIDEO) {
                            FilterChip(
                                selected = embedSubtitles,
                                onClick = {
                                    embedSubtitles = !embedSubtitles
                                    if (embedSubtitles) burnSubtitles = false
                                },
                                label = { Text("Embed Subs") }
                            )
                            FilterChip(
                                selected = burnSubtitles,
                                onClick = {
                                    burnSubtitles = !burnSubtitles
                                    if (burnSubtitles) embedSubtitles = false
                                },
                                label = { Text("Burn Subs") }
                            )
                        }
                        AssistChip(
                            onClick = { showLyricsDialog = true },
                            label = { Text(if (lyricsText.isBlank()) "Lyrics (lrclib)" else "Lyrics Attached") },
                            leadingIcon = { Icon(Icons.Default.Lyrics, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }

                    if (downloadSubtitles) {
                        OutlinedTextField(
                            value = subtitleLangsInput,
                            onValueChange = { subtitleLangsInput = it },
                            label = { Text("Subtitle languages (comma-separated, e.g. en,es)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // SponsorBlock action
                    Text("SponsorBlock", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(YtdlnisSponsorBlockAction.entries) { action ->
                            FilterChip(
                                selected = sbAction == action,
                                onClick = { sbAction = action },
                                label = { Text(action.label) }
                            )
                        }
                    }
                    if (sbAction != YtdlnisSponsorBlockAction.NONE) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("sponsor", "intro", "outro", "selfpromo", "preview", "filler", "interaction", "music_offtopic").forEach { cat ->
                                FilterChip(
                                    selected = cat in sbCategories,
                                    onClick = {
                                        sbCategories = if (cat in sbCategories) sbCategories - cat else sbCategories + cat
                                    },
                                    label = { Text(cat) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Schedule selector
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Schedule", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Now", 15 to "+15 min", 60 to "+1 hr", 360 to "+6 hr", 720 to "+12 hr").forEach { (mins, label) ->
                        FilterChip(
                            selected = scheduleDelayMinutes == mins,
                            onClick = { scheduleDelayMinutes = mins },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        // Action Buttons
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val item = buildDownloadItem().copy(status = YtdlnisDownloadStatus.SAVED)
                        onSaveForLater(item)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save")
                }
                Button(
                    onClick = { onEnqueue(buildDownloadItem()) },
                    modifier = Modifier.weight(1.6f)
                ) {
                    Icon(
                        if (scheduleDelayMinutes > 0) Icons.Default.Schedule else Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (scheduleDelayMinutes > 0) "Schedule" else "Download")
                }
            }
        }
    }

    if (showLyricsDialog) {
        YtdlnisLyricsDialog(
            initialTrack = title,
            initialArtist = author,
            initialLyrics = lyricsText,
            initialSaveLrc = saveLrcFile,
            onDismiss = { showLyricsDialog = false },
            onApply = { updatedLyrics, shouldSaveLrc ->
                lyricsText = updatedLyrics
                saveLrcFile = shouldSaveLrc
                showLyricsDialog = false
                Toast.makeText(context, "Lyrics attached", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun YtdlnisLyricsDialog(
    initialTrack: String,
    initialArtist: String,
    initialLyrics: String,
    initialSaveLrc: Boolean,
    onDismiss: () -> Unit,
    onApply: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var track by remember { mutableStateOf(initialTrack) }
    var artist by remember { mutableStateOf(initialArtist) }
    var isSearching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<YtdlnisLyricsResult>>(emptyList()) }
    var selectedLyrics by remember { mutableStateOf(initialLyrics) }
    var offsetMs by remember { mutableStateOf(0L) }
    var saveLrc by remember { mutableStateOf(initialSaveLrc) }

    val adjustedLyrics = remember(selectedLyrics, offsetMs) {
        if (offsetMs == 0L) selectedLyrics else YtdlnisEngine.adjustLrcTimingLocal(selectedLyrics, offsetMs)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lyrics Search (lrclib.net)") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = track,
                    onValueChange = { track = it },
                    label = { Text("Track title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        scope.launch {
                            isSearching = true
                            results = YtdlnisEngine.searchLyrics(context, track, artist)
                            isSearching = false
                        }
                    },
                    enabled = !isSearching && track.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isSearching) "Searching lrclib.net..." else "Search Synced Lyrics")
                }

                if (results.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(results.take(6)) { r ->
                            SuggestionChip(
                                onClick = {
                                    selectedLyrics = r.syncedLyrics.ifBlank { r.plainLyrics }
                                    offsetMs = 0L
                                },
                                label = { Text("${r.trackName} - ${r.artistName}".take(28)) }
                            )
                        }
                    }
                }

                if (selectedLyrics.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Timing: ${offsetMs}ms", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { offsetMs -= 250L }) { Text("-250ms") }
                        TextButton(onClick = { offsetMs += 250L }) { Text("+250ms") }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                    ) {
                        Text(
                            text = adjustedLyrics.take(600),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = saveLrc, onCheckedChange = { saveLrc = it })
                        Text("Save companion .lrc file", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(adjustedLyrics, saveLrc) },
                enabled = adjustedLyrics.isNotBlank()
            ) {
                Text("Attach")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatSecToTs(sec: Double): String {
    val total = sec.toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}
