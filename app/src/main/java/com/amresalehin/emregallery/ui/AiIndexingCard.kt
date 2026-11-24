package com.amresalehin.emregallery.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import kotlin.math.roundToInt

@Composable
fun AiIndexingCard(
    totalPhotos: Int,
    aiState: AiIndexingState,
    countsReady: Boolean,
    cardBackground: Color,
    cardBorder: BorderStroke,
    numberFormat: NumberFormat,
    onToggleClipIndexing: () -> Unit,
    onToggleVlmIndexing: () -> Unit,
    onStopAiIndexing: () -> Unit,
    onReindexClip: () -> Unit,
    onDownloadClipModel: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onClearAiIndex: () -> Unit,
    availableFolders: List<FaceFolderInfo>,
    onToggleClipFolder: (String) -> Unit,
    onSelectAllClipFolders: () -> Unit,
    onToggleVlmFolder: (String) -> Unit,
    onSelectAllVlmFolders: () -> Unit
) {
    val clipTotalPhotos = if (aiState.clipIsAllFolders || aiState.clipSelectedFolders.isEmpty()) totalPhotos
        else availableFolders.filter { it.path in aiState.clipSelectedFolders }.sumOf { it.photoCount }
    val vlmTotalPhotos = if (aiState.vlmIsAllFolders || aiState.vlmSelectedFolders.isEmpty()) totalPhotos
        else availableFolders.filter { it.path in aiState.vlmSelectedFolders }.sumOf { it.photoCount }
    val clipProgress = if (clipTotalPhotos > 0) (aiState.processed.toFloat() / clipTotalPhotos).coerceIn(0f, 1f) else 0f
    val vlmProgress = if (vlmTotalPhotos > 0) (aiState.richCaptionedCount.toFloat() / vlmTotalPhotos).coerceIn(0f, 1f) else 0f
    val animClip by animateFloatAsState(targetValue = clipProgress, label = "clip_progress")
    val animVlm by animateFloatAsState(targetValue = vlmProgress, label = "vlm_progress")
    val clipPct = (clipProgress * 100f).roundToInt()
    val vlmPct = (vlmProgress * 100f).roundToInt()

    val clipRunning = aiState.isClipRunning && !aiState.isPaused
    val clipPaused = aiState.isClipRunning && aiState.isPaused
    val clipDone = clipTotalPhotos > 0 && aiState.processed >= clipTotalPhotos

    val vlmRunning = aiState.isVlmRunning && !aiState.isPaused
    val vlmPaused = aiState.isVlmRunning && aiState.isPaused
    val vlmDone = vlmTotalPhotos > 0 && aiState.richCaptionedCount >= vlmTotalPhotos

    var showClipMenu by remember { mutableStateOf(false) }

    // CLIP semantic embeddings — match the Face Recognition card's header, pill and divider progress.
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = cardBackground),
        border = cardBorder,
        modifier = Modifier.fillMaxWidth().testTag("clip_indexing_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(23.dp)
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                Text(
                    text = "CLIP",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.weight(1f)
                )

                if (aiState.isClipRunning || aiState.isDownloadingModel) {
                    IconButton(
                        onClick = onStopAiIndexing,
                        modifier = Modifier.size(34.dp).testTag("clip_stop_button")
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop CLIP",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }

                if (!aiState.isClipInstalled && !aiState.isDownloadingModel) {
                    CompactActionPill(
                        label = "Get CLIP",
                        enabled = !aiState.isRunning,
                        onClick = onDownloadClipModel
                    )
                } else if (aiState.isDownloadingModel) {
                    CompactActionPill(
                        label = if (aiState.downloadTotalBytes > 0L) {
                            "${(aiState.downloadBytes * 100L / aiState.downloadTotalBytes).coerceIn(0L, 100L)}%"
                        } else "Downloading",
                        enabled = false,
                        onClick = {}
                    )
                } else {
                    Box {
                        CompactProgressPill(
                            isRunning = clipRunning,
                            isPaused = clipPaused,
                            isDone = clipDone,
                            percent = clipPct,
                            hasPartial = aiState.processed > 0,
                            enabled = countsReady && clipTotalPhotos > 0 && !aiState.isVlmRunning,
                            onClick = {
                                if (clipDone) showClipMenu = true else onToggleClipIndexing()
                            }
                        )
                        DropdownMenu(
                            expanded = showClipMenu,
                            onDismissRequest = { showClipMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Re-index") },
                                onClick = { showClipMenu = false; onReindexClip() }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear index") },
                                onClick = { showClipMenu = false; onClearAiIndex() }
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
            ) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    thickness = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                )
                val clipBarProgress = if (aiState.isDownloadingModel) {
                    if (aiState.downloadTotalBytes > 0L) {
                        (aiState.downloadBytes.toFloat() / aiState.downloadTotalBytes.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                } else animClip
                if (aiState.isDownloadingModel || clipProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { clipBarProgress },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = if (aiState.isDownloadingModel) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                }
            }

            if (availableFolders.isNotEmpty()) {
                AiFolderSelector(
                    folders = availableFolders,
                    isAllFolders = aiState.clipIsAllFolders,
                    selectedFolders = aiState.clipSelectedFolders,
                    onToggleFolder = onToggleClipFolder,
                    onSelectAll = onSelectAllClipFolders,
                    testTag = "clip_folders"
                )
            }

            if (clipRunning || clipPaused) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${numberFormat.format(aiState.processed)} / ${numberFormat.format(clipTotalPhotos)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!aiState.error.isNullOrBlank() &&
                (aiState.isClipRunning || aiState.isDownloadingModel)
            ) {
                Text(
                    text = aiState.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 14.dp)
                )
            }
        }
    }

    // VLM captions and tags use the same header, status action and progress treatment as OCR and Face Recognition.
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = cardBackground),
        border = cardBorder,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("vlm_indexing_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(23.dp)
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                Text(
                    text = "VLM",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.weight(1f)
                )

                if (aiState.isVlmRunning) {
                    IconButton(
                        onClick = onStopAiIndexing,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("vlm_stop_button")
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop VLM",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }

                CompactProgressPill(
                    isRunning = vlmRunning,
                    isPaused = vlmPaused,
                    isDone = vlmDone,
                    percent = vlmPct,
                    hasPartial = aiState.richCaptionedCount > 0,
                    enabled = countsReady && vlmTotalPhotos > 0 &&
                        !aiState.isClipRunning && !aiState.isDownloadingModel,
                    onClick = onToggleVlmIndexing
                )
            }

            // Match the OCR and Face cards' divider-integrated progress treatment.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
            ) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    thickness = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                )
                if (vlmProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { animVlm },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                }
            }

            if (availableFolders.isNotEmpty()) {
                AiFolderSelector(
                    folders = availableFolders,
                    isAllFolders = aiState.vlmIsAllFolders,
                    selectedFolders = aiState.vlmSelectedFolders,
                    onToggleFolder = onToggleVlmFolder,
                    onSelectAll = onSelectAllVlmFolders,
                    testTag = "vlm_folders"
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (vlmRunning || vlmPaused) {
                    Text(
                        text = "${numberFormat.format(aiState.richCaptionedCount)} / ${numberFormat.format(vlmTotalPhotos)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }

                CompactActionPill(
                    label = "Config",
                    enabled = true,
                    onClick = onOpenAiSettings,
                    modifier = Modifier.testTag("vlm_config_button")
                )
            }

            if (!aiState.error.isNullOrBlank() &&
                !aiState.isClipRunning && !aiState.isDownloadingModel
            ) {
                Text(
                    text = aiState.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 14.dp)
                )
            }
        }
    }
}


@Composable
private fun AiFolderSelector(
    folders: List<FaceFolderInfo>,
    isAllFolders: Boolean,
    selectedFolders: Set<String>,
    onToggleFolder: (String) -> Unit,
    onSelectAll: () -> Unit,
    testTag: String
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = "Folders",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isAllFolders) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.height(32.dp).clickable(onClick = onSelectAll)
            ) {
                Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "All folders",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (isAllFolders) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (isAllFolders) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp).testTag(testTag),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            folders.forEach { folder ->
                val selected = !isAllFolders && folder.path in selectedFolders
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.height(34.dp).clickable { onToggleFolder(folder.path) }
                ) {
                    Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        Text(
                            folder.name,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactActionPill(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier.height(36.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}

@Composable
private fun CompactProgressPill(
    isRunning: Boolean,
    isPaused: Boolean,
    isDone: Boolean,
    percent: Int,
    hasPartial: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = when {
            isRunning -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            isPaused -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            1.dp,
            when {
                isRunning -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                isPaused -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            }
        ),
        modifier = Modifier
            .height(36.dp)
            .defaultMinSize(minWidth = 84.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            when {
                isRunning -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$percent% · Pause",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }
                isPaused -> {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "$percent% · Resume",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    )
                }
                isDone -> {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "100% · Done",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
                else -> {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (hasPartial) "Start ($percent%)" else "Start",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }
    }
}
