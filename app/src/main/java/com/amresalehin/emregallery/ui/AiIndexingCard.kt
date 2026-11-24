package com.amresalehin.emregallery.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

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
    onClearAiIndex: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cardBackground,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Dual AI: CLIP Search + VLM",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "CLIP: ${if (aiState.isClipInstalled) "ONNX ready" else "not downloaded"} · " +
                                "VLM: ${aiState.vlmStatusLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onOpenAiSettings) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = "AI settings",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (aiState.isDownloadingModel) {
                val frac = if (aiState.downloadTotalBytes > 0L) {
                    (aiState.downloadBytes.toFloat() / aiState.downloadTotalBytes.toFloat()).coerceIn(0f, 1f)
                } else 0f
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Downloading AI model (${(frac * 100).toInt()}%)…",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                // System 1 Progress: CLIP Semantic Index
                val clipProgress = if (totalPhotos > 0) {
                    (aiState.processed.toFloat() / totalPhotos.toFloat()).coerceIn(0f, 1f)
                } else 0f
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (countsReady) {
                                "1. CLIP Semantic: ${numberFormat.format(aiState.processed)} / ${numberFormat.format(totalPhotos)}"
                            } else "Calculating…",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        if (aiState.isClipRunning && aiState.itemsPerMinute > 0) {
                            Text(
                                "${aiState.itemsPerMinute}/min",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = { clipProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // System 2 Progress: VLM Descriptions & Tags
                val vlmProgress = if (totalPhotos > 0) {
                    (aiState.richCaptionedCount.toFloat() / totalPhotos.toFloat()).coerceIn(0f, 1f)
                } else 0f
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (countsReady) {
                                "2. VLM Descriptions & Tags: ${numberFormat.format(aiState.richCaptionedCount)} / ${numberFormat.format(totalPhotos)}"
                            } else "Calculating…",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        if (aiState.isVlmRunning && aiState.itemsPerMinute > 0) {
                            Text(
                                "${aiState.itemsPerMinute}/min",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = { vlmProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${numberFormat.format(aiState.clipEmbeddedCount)} CLIP vectors · ${numberFormat.format(aiState.distinctTagCount)} VLM tags",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!aiState.error.isNullOrBlank()) {
                Text(
                    aiState.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onToggleClipIndexing,
                    enabled = !aiState.isDownloadingModel && !aiState.isVlmRunning && totalPhotos > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (aiState.isClipRunning && !aiState.isPaused) Icons.Default.Pause else Icons.Default.ImageSearch,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            aiState.isClipRunning && !aiState.isPaused -> "Pause CLIP"
                            aiState.isClipRunning && aiState.isPaused -> "Resume CLIP"
                            else -> "Index CLIP"
                        }
                    )
                }

                Button(
                    onClick = onToggleVlmIndexing,
                    enabled = !aiState.isDownloadingModel && !aiState.isClipRunning && totalPhotos > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (aiState.isVlmRunning && !aiState.isPaused) Icons.Default.Pause else Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            aiState.isVlmRunning && !aiState.isPaused -> "Pause VLM"
                            aiState.isVlmRunning && aiState.isPaused -> "Resume VLM"
                            else -> "Index VLM"
                        }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (aiState.isClipRunning || aiState.isVlmRunning) {
                    OutlinedButton(onClick = onStopAiIndexing, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop")
                    }
                } else {
                    if (!aiState.isClipInstalled) {
                        OutlinedButton(
                            onClick = onDownloadClipModel,
                            enabled = !aiState.isDownloadingModel,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Get CLIP")
                        }
                    } else {
                        OutlinedButton(
                            onClick = onReindexClip,
                            enabled = !aiState.isDownloadingModel && totalPhotos > 0,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Re-index CLIP")
                        }
                    }
                    if (aiState.processed > 0) {
                        TextButton(onClick = onClearAiIndex) {
                            Text("Clear Index")
                        }
                    }
                }
            }
        }
    }
}
