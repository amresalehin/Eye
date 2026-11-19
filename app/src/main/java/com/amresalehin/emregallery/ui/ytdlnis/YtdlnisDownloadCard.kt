package com.amresalehin.emregallery.ui.ytdlnis

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amresalehin.emregallery.ytdlnis.*
import kotlinx.coroutines.launch

private val MonochromeDarkScheme = darkColorScheme(
    primary = Color(0xFFF5F5F5),
    onPrimary = Color(0xFF101010),
    primaryContainer = Color(0xFF262626),
    onPrimaryContainer = Color(0xFFF5F5F5),
    secondary = Color(0xFFD4D4D4),
    onSecondary = Color(0xFF121212),
    secondaryContainer = Color(0xFF222222),
    onSecondaryContainer = Color(0xFFE5E5E5),
    tertiary = Color(0xFFA3A3A3),
    onTertiary = Color(0xFF121212),
    background = Color(0xFF101010),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF161616),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF202020),
    onSurfaceVariant = Color(0xFF9E9E9E),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF2C2C2C),
    error = Color(0xFFE57373),
    onError = Color(0xFF141414)
)

private val MonochromeLightScheme = lightColorScheme(
    primary = Color(0xFF141414),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEAEAEA),
    onPrimaryContainer = Color(0xFF141414),
    secondary = Color(0xFF333333),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Color(0xFF555555),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF141414),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141414),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF616161),
    outline = Color(0xFFD0D0D0),
    outlineVariant = Color(0xFFE2E2E2),
    error = Color(0xFFB00020),
    onError = Color(0xFFFFFFFF)
)

/**
 * Theme wrapper that preserves Emre Gallery's active light/dark MaterialTheme
 * (so the Downloader never overrides the user's selected app theme).
 */
@Composable
fun YtdlnisMonochromeTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}

private enum class StudioDisclosureChip(val label: String, val icon: ImageVector) {
    CUT("Cut", Icons.Default.ContentCut),
    SPONSORBLOCK("SponsorBlock", Icons.Default.Shield),
    SUBTITLES("Subtitles", Icons.Default.Subtitles),
    LYRICS("Lyrics", Icons.Default.MusicNote),
    CROP_COMPAT("Adjust", Icons.Default.Crop),
    SCHEDULE("Schedule", Icons.Default.Schedule),
    OUTPUT("Output", Icons.Default.Folder)
}

/**
 * Visual-first, minimal monochrome Studio Format & Quality Sheet.
 * Uses progressive disclosure chips so advanced YTDLnis tools (Cut, SponsorBlock, Subtitles,
 * Synced Lyrics, Crop/Compatible Mode, Schedule, and Output Template) expand only when tapped.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun YtdlnisDownloadConfigCard(
    initialInfo: YtdlnisVideoInfo,
    templates: List<YtdlnisCommandTemplate> = emptyList(),
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
    var isEditingMetadata by remember { mutableStateOf(false) }

    var selectedFormatId by remember(initialInfo.url) { mutableStateOf("best") }
    var selectedAudioIds by remember(initialInfo.url) { mutableStateOf(setOf<String>()) }
    var container by remember(initialInfo.url) { mutableStateOf("mp4") }
    var filenameTemplate by remember(initialInfo.url) { mutableStateOf(defaultFilenameTemplate) }
    var subfolderMode by remember(initialInfo.url) { mutableStateOf(defaultSubfolderMode) }
    var customCommand by remember(initialInfo.url) { mutableStateOf("") }

    // Progressive disclosure chip selection (null = all collapsed for clean visual view)
    var activeChip by remember { mutableStateOf<StudioDisclosureChip?>(null) }

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
    var lyricsText by remember { mutableStateOf("") }
    var lyricsOffsetMs by remember { mutableStateOf(0L) }
    var saveLrcFile by remember { mutableStateOf(false) }
    var isFetchingLyrics by remember { mutableStateOf(false) }

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
            author = author.trim().ifBlank { initialInfo.author },
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
            customCommand = customCommand.trim(),
            cutSections = cutSections,
            mergeCutSections = mergeCuts,
            splitByChapters = splitByChapters,
            cropConfig = YtdlnisCropConfig(
                enabled = cropPreset != "Original",
                aspectPreset = cropPreset
            ),
            sponsorBlockAction = sbAction,
            sponsorBlockCategories = sbCategories,
            embedMetadata = embedMetadata,
            embedThumbnail = embedThumbnail,
            embedChapters = embedChapters,
            downloadSubtitles = downloadSubtitles,
            embedSubtitles = embedSubtitles,
            burnSubtitles = burnSubtitles,
            subtitleLanguages = subtitleLangsInput.split(",").map { it.trim() }.filter { it.isNotBlank() },
            compatibleMode = compatibleMode,
            scheduledTimeMillis = scheduledAt,
            incognito = incognito,
            lyricsText = lyricsText,
            saveLrcFile = saveLrcFile
        )
    }

    YtdlnisMonochromeTheme {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Visual Media Header Card (Thumbnail + Title/Author + Edit Toggle)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 96.dp, height = 64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (initialInfo.thumbnail.isNotBlank()) {
                        AsyncImage(
                            model = initialInfo.thumbnail,
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Default.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (initialInfo.durationString.isNotBlank()) {
                        Surface(
                            color = Color(0xCC101010),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                        ) {
                            Text(
                                text = initialInfo.durationString,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title.ifBlank { "Untitled Media" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = listOfNotNull(
                            author.takeIf { it.isNotBlank() },
                            initialInfo.extractor.takeIf { it.isNotBlank() }
                        ).joinToString(" · ").ifBlank { initialInfo.url },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = { isEditingMetadata = !isEditingMetadata },
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            if (isEditingMetadata) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit title and author",
                        modifier = Modifier.size(16.dp),
                        tint = if (isEditingMetadata) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Collapsible inline Title / Author edit fields
            AnimatedVisibility(visible = isEditingMetadata) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f)
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

            // 2. Segmented Mode Switcher (Video | Audio | Command) + Container Pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(modifier = Modifier.padding(3.dp)) {
                        YtdlnisDownloadType.entries.forEach { type ->
                            val selected = downloadType == type
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        downloadType = type
                                        container = when (type) {
                                            YtdlnisDownloadType.AUDIO -> "m4a"
                                            YtdlnisDownloadType.VIDEO -> "mp4"
                                            YtdlnisDownloadType.COMMAND -> "mp4"
                                        }
                                        selectedFormatId = "best"
                                    }
                            ) {
                                Text(
                                    text = type.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                if (downloadType != YtdlnisDownloadType.COMMAND) {
                    val containers = if (downloadType == YtdlnisDownloadType.AUDIO) {
                        listOf("m4a", "mp3", "opus", "flac")
                    } else {
                        listOf("mp4", "mkv", "webm")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        containers.forEach { c ->
                            val selected = container == c
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { container = c }
                            ) {
                                Text(
                                    text = c.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 3. Scrollable Main Area: Visual Format Cards + Progressive Disclosure Chips
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (downloadType == YtdlnisDownloadType.COMMAND) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = customCommand,
                                onValueChange = { customCommand = it },
                                label = { Text("Custom yt-dlp Flags") },
                                placeholder = { Text("-f bestvideo+bestaudio --embed-subs") },
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (templates.isNotEmpty()) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(templates) { tpl ->
                                        AssistChip(
                                            onClick = { customCommand = tpl.command },
                                            label = { Text(tpl.title) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Visual Horizontal Format Cards (DBase Downloader style)
                    item {
                        val listToRender = if (downloadType == YtdlnisDownloadType.AUDIO) audioFormats else videoFormats
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    VisualFormatTile(
                                        title = if (downloadType == YtdlnisDownloadType.AUDIO) "Best Audio" else "Best Quality",
                                        subtitle = "Auto Select",
                                        badge = container.uppercase(),
                                        selected = selectedFormatId == "best",
                                        onClick = { selectedFormatId = "best" }
                                    )
                                }
                                items(listToRender.take(14)) { fmt ->
                                    val primaryLabel = if (downloadType == YtdlnisDownloadType.AUDIO) {
                                        if (fmt.abr > 0) "${fmt.abr.toInt()} kbps" else fmt.ext.uppercase()
                                    } else {
                                        if (fmt.height > 0) "${fmt.height}p" else fmt.resolution.ifBlank { fmt.formatId }
                                    }
                                    val sizeLabel = formatByteSize(fmt.filesize).ifBlank { fmt.ext.uppercase() }
                                    VisualFormatTile(
                                        title = primaryLabel,
                                        subtitle = sizeLabel,
                                        badge = fmt.vcodec.takeIf { it != "none" && it.isNotBlank() }?.substringBefore(".")?.uppercase()
                                            ?: fmt.acodec.takeIf { it != "none" && it.isNotBlank() }?.substringBefore(".")?.uppercase()
                                            ?: fmt.ext.uppercase(),
                                        selected = selectedFormatId == fmt.formatId,
                                        onClick = { selectedFormatId = fmt.formatId }
                                    )
                                }
                            }

                            // Multi-Audio stream chips (only when video mode has multiple distinct audio tracks)
                            if (downloadType == YtdlnisDownloadType.VIDEO && audioFormats.size > 1) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(audioFormats.take(8)) { af ->
                                        val isChosen = selectedAudioIds.contains(af.formatId)
                                        FilterChip(
                                            selected = isChosen,
                                            onClick = {
                                                selectedAudioIds = if (isChosen) selectedAudioIds - af.formatId
                                                else selectedAudioIds + af.formatId
                                            },
                                            label = {
                                                val lang = af.language.ifBlank { af.formatId }
                                                Text("Audio: $lang (${af.abr.toInt()}k)", style = MaterialTheme.typography.labelSmall)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Progressive Disclosure Chips Row
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            StudioDisclosureChip.entries.forEach { chip ->
                                val isExpanded = activeChip == chip
                                val badgeText = when (chip) {
                                    StudioDisclosureChip.CUT -> if (cutSections.isNotEmpty()) "${cutSections.size}" else if (splitByChapters) "Ch" else null
                                    StudioDisclosureChip.SPONSORBLOCK -> if (sbAction != YtdlnisSponsorBlockAction.NONE) sbAction.label else null
                                    StudioDisclosureChip.SUBTITLES -> if (downloadSubtitles) subtitleLangsInput else null
                                    StudioDisclosureChip.LYRICS -> if (saveLrcFile) "LRC" else null
                                    StudioDisclosureChip.CROP_COMPAT -> if (cropPreset != "Original") cropPreset else if (compatibleMode) "H.264" else null
                                    StudioDisclosureChip.SCHEDULE -> if (scheduleDelayMinutes > 0) "+${scheduleDelayMinutes}m" else null
                                    StudioDisclosureChip.OUTPUT -> if (subfolderMode != YtdlnisSubfolderMode.NONE) subfolderMode.label else null
                                }
                                val isConfigured = badgeText != null

                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = when {
                                        isExpanded -> MaterialTheme.colorScheme.primary
                                        isConfigured -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> MaterialTheme.colorScheme.surface
                                    },
                                    border = if (isExpanded) null else BorderStroke(
                                        1.dp,
                                        if (isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable {
                                            activeChip = if (isExpanded) null else chip
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            chip.icon,
                                            contentDescription = chip.label,
                                            modifier = Modifier.size(14.dp),
                                            tint = if (isExpanded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (badgeText != null) "${chip.label} · $badgeText" else chip.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isExpanded || isConfigured) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isExpanded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        // Expandable Progressive Disclosure Panel for the active chip
                        AnimatedVisibility(visible = activeChip != null) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    when (activeChip) {
                                        StudioDisclosureChip.CUT -> {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                                        val s = newCutStart.trim()
                                                        val e = newCutEnd.trim()
                                                        if (s.isNotBlank() && e.isNotBlank()) {
                                                            cutSections = cutSections + YtdlnisCutSection(startTime = s, endTime = e)
                                                        } else {
                                                            Toast.makeText(context, "Enter start and end times", Toast.LENGTH_SHORT).show()
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
                                                                val sMin = (ch.startTimeSec / 60).toInt()
                                                                val sSec = (ch.startTimeSec % 60).toInt()
                                                                val eMin = (ch.endTimeSec / 60).toInt()
                                                                val eSec = (ch.endTimeSec % 60).toInt()
                                                                cutSections = cutSections + YtdlnisCutSection(
                                                                    startTime = String.format("%02d:%02d", sMin, sSec),
                                                                    endTime = String.format("%02d:%02d", eMin, eSec),
                                                                    label = ch.title
                                                                )
                                                            },
                                                            label = { Text(ch.title, maxLines = 1) }
                                                        )
                                                    }
                                                }
                                            }

                                            if (cutSections.isNotEmpty()) {
                                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    cutSections.forEachIndexed { idx, cut ->
                                                        InputChip(
                                                            selected = true,
                                                            onClick = {
                                                                cutSections = cutSections.filterIndexed { i, _ -> i != idx }
                                                            },
                                                            label = {
                                                                Text("${cut.startTime}-${cut.endTime} ✕")
                                                            }
                                                        )
                                                    }
                                                }
                                            }

                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                if (cutSections.size > 1) {
                                                    FilterChip(
                                                        selected = mergeCuts,
                                                        onClick = { mergeCuts = !mergeCuts },
                                                        label = { Text("Merge Cuts") }
                                                    )
                                                }
                                                if (initialInfo.chapters.isNotEmpty()) {
                                                    FilterChip(
                                                        selected = splitByChapters,
                                                        onClick = { splitByChapters = !splitByChapters },
                                                        label = { Text("Split Chapters") }
                                                    )
                                                }
                                            }
                                        }

                                        StudioDisclosureChip.SPONSORBLOCK -> {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                YtdlnisSponsorBlockAction.entries.forEach { act ->
                                                    FilterChip(
                                                        selected = sbAction == act,
                                                        onClick = { sbAction = act },
                                                        label = { Text(act.label) }
                                                    )
                                                }
                                            }
                                            if (sbAction != YtdlnisSponsorBlockAction.NONE) {
                                                val allCats = listOf("sponsor", "intro", "outro", "selfpromo", "interaction", "music_offtopic")
                                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    allCats.forEach { cat ->
                                                        val sel = sbCategories.contains(cat)
                                                        FilterChip(
                                                            selected = sel,
                                                            onClick = {
                                                                sbCategories = if (sel) sbCategories - cat else sbCategories + cat
                                                            },
                                                            label = { Text(cat) }
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        StudioDisclosureChip.SUBTITLES -> {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                FilterChip(
                                                    selected = downloadSubtitles,
                                                    onClick = { downloadSubtitles = !downloadSubtitles },
                                                    label = { Text("Download") }
                                                )
                                                FilterChip(
                                                    selected = embedSubtitles,
                                                    onClick = {
                                                        embedSubtitles = !embedSubtitles
                                                        if (embedSubtitles) downloadSubtitles = true
                                                    },
                                                    label = { Text("Embed") }
                                                )
                                                FilterChip(
                                                    selected = burnSubtitles,
                                                    onClick = {
                                                        burnSubtitles = !burnSubtitles
                                                        if (burnSubtitles) downloadSubtitles = true
                                                    },
                                                    label = { Text("Burn-In") }
                                                )
                                            }
                                            if (downloadSubtitles) {
                                                OutlinedTextField(
                                                    value = subtitleLangsInput,
                                                    onValueChange = { subtitleLangsInput = it },
                                                    label = { Text("Languages (e.g. en,es,ja)") },
                                                    singleLine = true,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }

                                        StudioDisclosureChip.LYRICS -> {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                FilledTonalButton(
                                                    onClick = {
                                                        isFetchingLyrics = true
                                                        scope.launch {
                                                            val list = YtdlnisEngine.searchLyrics(context, title, author)
                                                            isFetchingLyrics = false
                                                            val best = list.firstOrNull { it.syncedLyrics.isNotBlank() } ?: list.firstOrNull()
                                                            if (best != null) {
                                                                lyricsText = best.syncedLyrics.ifBlank { best.plainLyrics }
                                                                saveLrcFile = true
                                                            } else {
                                                                Toast.makeText(context, "No synced lyrics found", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    },
                                                    enabled = !isFetchingLyrics
                                                ) {
                                                    Text(if (isFetchingLyrics) "Searching..." else "Fetch lrclib.net")
                                                }
                                                FilterChip(
                                                    selected = saveLrcFile,
                                                    onClick = { saveLrcFile = !saveLrcFile },
                                                    label = { Text("Save .lrc") }
                                                )
                                            }
                                            if (lyricsText.isNotBlank()) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    listOf(-500L to "-500ms", -100L to "-100ms", 100L to "+100ms", 500L to "+500ms").forEach { (delta, lbl) ->
                                                        AssistChip(
                                                            onClick = {
                                                                lyricsOffsetMs += delta
                                                                lyricsText = YtdlnisEngine.adjustLrcTimingLocal(lyricsText, delta)
                                                            },
                                                            label = { Text(lbl, style = MaterialTheme.typography.labelSmall) }
                                                        )
                                                    }
                                                }
                                                OutlinedTextField(
                                                    value = lyricsText,
                                                    onValueChange = { lyricsText = it },
                                                    maxLines = 4,
                                                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }

                                        StudioDisclosureChip.CROP_COMPAT -> {
                                            if (downloadType == YtdlnisDownloadType.VIDEO) {
                                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    val presets = listOf("Original", "1:1", "4:5", "16:9", "9:16")
                                                    items(presets) { preset ->
                                                        FilterChip(
                                                            selected = cropPreset == preset,
                                                            onClick = { cropPreset = preset },
                                                            label = { Text("Crop: $preset") }
                                                        )
                                                    }
                                                }
                                            }
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                FilterChip(
                                                    selected = compatibleMode,
                                                    onClick = { compatibleMode = !compatibleMode },
                                                    label = { Text("H.264 Compatible") }
                                                )
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
                                            }
                                        }

                                        StudioDisclosureChip.SCHEDULE -> {
                                            val delays = listOf(0 to "Now", 15 to "+15m", 60 to "+1h", 180 to "+3h", 360 to "+6h", 720 to "+12h")
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                items(delays) { (mins, label) ->
                                                    FilterChip(
                                                        selected = scheduleDelayMinutes == mins,
                                                        onClick = { scheduleDelayMinutes = mins },
                                                        label = { Text(label) }
                                                    )
                                                }
                                            }
                                        }

                                        StudioDisclosureChip.OUTPUT -> {
                                            OutlinedTextField(
                                                value = filenameTemplate,
                                                onValueChange = { filenameTemplate = it },
                                                label = { Text("Filename Template") },
                                                supportingText = { Text("Preview: $livePreviewName", maxLines = 1) },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                items(YtdlnisSubfolderMode.entries) { mode ->
                                                    FilterChip(
                                                        selected = subfolderMode == mode,
                                                        onClick = { subfolderMode = mode },
                                                        label = { Text(mode.label) }
                                                    )
                                                }
                                            }
                                        }

                                        null -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. Sticky Bottom Action Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onSaveForLater(buildDownloadItem()) },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.height(46.dp)
                ) {
                    Icon(Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save")
                }

                Button(
                    onClick = { onEnqueue(buildDownloadItem()) },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                ) {
                    Icon(
                        if (scheduleDelayMinutes > 0) Icons.Default.Schedule else Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (scheduleDelayMinutes > 0) "Schedule (+${scheduleDelayMinutes}m)"
                        else "Download · ${container.uppercase()}",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun VisualFormatTile(
    title: String,
    subtitle: String,
    badge: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .widthIn(min = 104.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
