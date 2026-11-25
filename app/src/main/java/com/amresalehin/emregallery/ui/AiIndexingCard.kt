package com.amresalehin.emregallery.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
    onToggleAi: () -> Unit,
    onStopAi: () -> Unit,
    onReindexAi: () -> Unit,
    onRetryFailedAi: () -> Unit,
    onClearCache: () -> Unit,
    onSelectProvider: (String) -> Unit = {},
    onOpenAiSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val progress = if (totalPhotos > 0) (aiState.processed.toFloat() / totalPhotos).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "ai_progress")
    val percent = (progress * 100f).roundToInt()
    val skeletonAlpha by rememberInfiniteTransition(label = "ai_model_skeleton")
        .animateFloat(
            initialValue = 0.35f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "ai_model_skeleton_alpha"
        )

    val isRunning = aiState.isRunning && !aiState.isPaused
    val isPaused = aiState.isRunning && aiState.isPaused
    val isCompleted = aiState.hasApiKey && totalPhotos > 0 && aiState.processed >= totalPhotos

    var showActionMenu by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = cardBackground),
        border = cardBorder,
        modifier = modifier
            .fillMaxWidth()
            .testTag("ai_indexing_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row: AI Icon, Title, Dynamic Corner Box (matches OcrIndexingCard & FaceRecognitionCard)
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
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = "AI Vision",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                Text(
                    text = "AI Vision",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.weight(1f)
                )

                if (aiState.isRunning) {
                    IconButton(
                        onClick = onStopAi,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("ai_stop_button")
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }

                Box {
                    Surface(
                        onClick = {
                            when {
                                !aiState.hasApiKey -> onOpenAiSettings?.invoke()
                                isCompleted -> showActionMenu = true
                                countsReady && totalPhotos > 0 -> onToggleAi()
                            }
                        },
                        enabled = !aiState.hasApiKey || (countsReady && totalPhotos > 0),
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
                            .testTag("ai_corner_box_action")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            when {
                                !aiState.hasApiKey -> {
                                    Icon(
                                        Icons.Default.Key,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = "Configure",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
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
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = "$percent% · Resume",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    )
                                }
                                isCompleted -> {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
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
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = if (aiState.processed > 0) "Start ($percent%)" else "Start",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
                            }
                        }
                    }

                    DropdownMenu(
                        expanded = showActionMenu,
                        onDismissRequest = { showActionMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Re-analyze All Photos") },
                            onClick = {
                                showActionMenu = false
                                onReindexAi()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear AI Cache") },
                            onClick = {
                                showActionMenu = false
                                onClearCache()
                            }
                        )
                    }
                }
            }

            // Minimalist Divider with integrated Progress Indicator
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
                if (progress > 0f) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                }
            }

            // Unified Minimal Body: Active Endpoint & Model + Action Pills
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!aiState.providersLoaded || aiState.configuredProviders.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            text = "Vision model",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        if (!aiState.providersLoaded) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai_vision_model_selector_skeleton"),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(76.dp, 94.dp, 68.dp).forEachIndexed { index, width ->
                                    Box(
                                        modifier = Modifier
                                            .width(width)
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(
                                                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = skeletonAlpha)
                                            )
                                            .testTag("ai_vision_model_skeleton_$index")
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                aiState.configuredProviders.forEach { provider ->
                                    val selected = provider.id == aiState.selectedProviderId
                                    Surface(
                                        onClick = { if (!aiState.isRunning) onSelectProvider(provider.id) },
                                        enabled = !aiState.isRunning,
                                        shape = RoundedCornerShape(18.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = BorderStroke(
                                            1.dp,
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                        ),
                                        modifier = Modifier
                                            .height(36.dp)
                                            .testTag("ai_vision_provider_" + provider.id)
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(horizontal = 14.dp)
                                        ) {
                                            Text(
                                                text = provider.name,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (aiState.failedCount > 0 && !aiState.isRunning) {
                            AiHeaderPill(
                                label = "Retry (${numberFormat.format(aiState.failedCount)})",
                                isActive = false,
                                onClick = onRetryFailedAi,
                                testTag = "ai_retry_failed_button"
                            )
                        }
                    }
                }

                if (aiState.isRunning || aiState.processed > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${numberFormat.format(aiState.processed)} / ${numberFormat.format(totalPhotos)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "$percent%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    aiState.error?.takeIf { it.isNotBlank() && it != "Cancelled" }?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
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
private fun AiHeaderPill(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val lightAppearance = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val activeBg = if (lightAppearance) Color(0xFF161A20) else MaterialTheme.colorScheme.primary
    val activeText = if (lightAppearance) Color.White else MaterialTheme.colorScheme.onPrimary

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) activeBg else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            1.dp,
            if (isActive) activeBg else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isActive) activeText else MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}
