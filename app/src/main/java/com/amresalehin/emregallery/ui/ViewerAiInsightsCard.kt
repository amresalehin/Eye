package com.amresalehin.emregallery.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amresalehin.emregallery.ai.*
import com.amresalehin.emregallery.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Karakeep / mymind-style AI Insights card rendered inside ViewerDetailsDrawer:
 * - On-demand single-photo analysis (local colors/CLIP + optional Vision API)
 * - Displays AI title, description/summary, auto-tags, and dominant color swatches
 * - "Find Similar" action launches visual similarity search
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ViewerAiInsightsCard(
    item: MediaItem,
    isLockedVaultItem: Boolean = false,
    onFindSimilar: ((MediaItem) -> Unit)? = null,
    onSearchTag: ((String) -> Unit)? = null,
    onSearchColor: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (item.isVideo) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { AiIndexStore.getInstance(context) }

    var record by remember(item.uri) { mutableStateOf<AiMediaRecord?>(null) }
    var isAnalyzing by remember(item.uri) { mutableStateOf(false) }
    var errorMsg by remember(item.uri) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.uri) {
        suspend fun load() {
            record = withContext(Dispatchers.IO) { store.getRecord(item.uri) }
        }
        load()
        AiStoreEvents.mediaUpdates.collect { updatedUri ->
            if (updatedUri.isBlank() || updatedUri == item.uri) load()
        }
    }

    DetailCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "AI Insights",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (record != null && !isAnalyzing) {
                    TextButton(
                        onClick = {
                            isAnalyzing = true
                            errorMsg = null
                            scope.launch {
                                val res = AiIndexingOrchestrator.analyzePhoto(
                                    context = context,
                                    item = item,
                                    allowCloudApi = true,
                                    isLockedVaultItem = isLockedVaultItem
                                )
                                res.onSuccess { rec ->
                                    record = rec
                                    errorMsg = rec.errorMessage
                                }.onFailure { err ->
                                    errorMsg = err.localizedMessage ?: "AI analysis failed"
                                }
                                isAnalyzing = false
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Re-analyze", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (isAnalyzing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "Analyzing image…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val rec = record
                if (rec == null) {
                Text(
                    "Extract AI tags, description, color palette, and visual embedding for semantic search.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        isAnalyzing = true
                        errorMsg = null
                        scope.launch {
                            val res = AiIndexingOrchestrator.analyzePhoto(
                                context = context,
                                item = item,
                                allowCloudApi = true,
                                isLockedVaultItem = isLockedVaultItem
                            )
                            res.onSuccess { rec ->
                                record = rec
                                errorMsg = rec.errorMessage
                            }.onFailure { err ->
                                errorMsg = err.localizedMessage ?: "AI analysis failed"
                            }
                            isAnalyzing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Analyze with AI")
                }
                } else {
                if (rec.title.isNotBlank()) {
                    Text(
                        text = rec.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (rec.caption.isNotBlank()) {
                    Text(
                        text = rec.caption,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (rec.colors.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Palette",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        rec.colors.take(5).forEach { colorInfo ->
                            val parsedColor = remember(colorInfo.hex) {
                                runCatching { Color(android.graphics.Color.parseColor(colorInfo.hex)) }
                                    .getOrDefault(Color.Gray)
                            }
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(parsedColor)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .clickable { onSearchColor?.invoke(colorInfo.name) }
                            )
                        }
                    }
                }

                if (rec.tags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rec.tags.take(12).forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                                modifier = Modifier.clickable { onSearchTag?.invoke(tag) }
                            ) {
                                Text(
                                    text = "#$tag",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                if (onFindSimilar != null && rec.embeddingDim > 0) {
                    OutlinedButton(
                        onClick = { onFindSimilar(item) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ImageSearch, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Find Visually Similar")
                    }
                }
                }
            }

            if (!errorMsg.isNullOrBlank()) {
                Text(
                    text = errorMsg.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
