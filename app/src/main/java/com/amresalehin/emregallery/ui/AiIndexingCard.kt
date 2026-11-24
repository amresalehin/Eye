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
    onToggleAiIndexing: () -> Unit,
    onStopAiIndexing: () -> Unit,
    onReindexAi: () -> Unit,
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
                            "AI Tagging & Semantic Search",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Provider: ${aiState.providerLabel}" +
                                if (aiState.isClipInstalled) " · CLIP ONNX ready" else " · Color + visual index",
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
                        "Downloading CLIP ONNX model (${(frac * 100).toInt()}%)…",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                val progress = if (totalPhotos > 0) {
                    (aiState.processed.toFloat() / totalPhotos.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (countsReady) {
                                "${numberFormat.format(aiState.processed)} / ${numberFormat.format(totalPhotos)} photos indexed"
                            } else "Calculating…",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        if (aiState.isRunning && aiState.itemsPerMinute > 0) {
                            Text(
                                "${aiState.itemsPerMinute}/min",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        "${numberFormat.format(aiState.richCaptionedCount)} AI descriptions · " +
                            "${numberFormat.format(aiState.clipEmbeddedCount)} CLIP vectors · " +
                            "${numberFormat.format(aiState.distinctTagCount)} tags",
                        style = MaterialTheme.typography.bodySmall,
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
                    onClick = onToggleAiIndexing,
                    enabled = !aiState.isDownloadingModel && totalPhotos > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        when {
                            aiState.isRunning && !aiState.isPaused -> Icons.Default.Pause
                            aiState.isRunning && aiState.isPaused -> Icons.Default.PlayArrow
                            else -> Icons.Default.AutoAwesome
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            aiState.isRunning && !aiState.isPaused -> "Pause"
                            aiState.isRunning && aiState.isPaused -> "Resume"
                            aiState.processed in 1 until totalPhotos -> "Continue"
                            else -> "Index AI"
                        }
                    )
                }

                if (aiState.isRunning) {
                    OutlinedButton(onClick = onStopAiIndexing) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop")
                    }
                } else {
                    OutlinedButton(
                        onClick = onReindexAi,
                        enabled = totalPhotos > 0 && !aiState.isDownloadingModel
                    ) {
                        Text("Re-index")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!aiState.isClipInstalled) {
                    OutlinedButton(
                        onClick = onDownloadClipModel,
                        enabled = !aiState.isDownloadingModel && !aiState.isRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download CLIP (~154 MB)")
                    }
                }
                if (aiState.processed > 0 && !aiState.isRunning) {
                    TextButton(onClick = onClearAiIndex) {
                        Text("Clear AI Index")
                    }
                }
            }
        }
    }
}
