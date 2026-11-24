package com.amresalehin.emregallery.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
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

    var questionInput by remember(item.uri) { mutableStateOf("") }
    var isAsking by remember(item.uri) { mutableStateOf(false) }
    var qaAnswer by remember(item.uri) { mutableStateOf<String?>(null) }

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
                Text(
                    text = "AI",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val rec = record
                    if (onFindSimilar != null && rec != null && rec.embeddingDim > 0) {
                        Surface(
                            onClick = { onFindSimilar(item) },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                                Text("Similar", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Surface(
                        onClick = {
                            if (!isAnalyzing) {
                                isAnalyzing = true
                                errorMsg = null
                                scope.launch {
                                    val res = AiIndexingOrchestrator.analyzeWithVlm(
                                        context = context,
                                        item = item,
                                        isLockedVaultItem = isLockedVaultItem
                                    )
                                    res.onSuccess { updated -> record = updated }
                                        .onFailure { err -> errorMsg = err.localizedMessage ?: "Failed" }
                                    isAnalyzing = false
                                }
                            }
                        },
                        enabled = !isAnalyzing,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                            if (isAnalyzing) {
                                CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
                            } else {
                                Text(
                                    text = if (rec?.hasRichCaption == true) "Re-analyze" else "Analyze",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            val rec = record
            if (rec != null && rec.hasRichCaption) {
                if (rec.title.isNotBlank()) {
                    Text(
                        text = rec.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (rec.caption.isNotBlank()) {
                    Text(
                        text = rec.caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (rec != null && rec.colors.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rec.colors.take(5).forEach { colorInfo ->
                        val parsedColor = remember(colorInfo.hex) {
                            runCatching { Color(android.graphics.Color.parseColor(colorInfo.hex)) }
                                .getOrDefault(Color.Gray)
                        }
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(parsedColor)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { onSearchColor?.invoke(colorInfo.name) }
                        )
                    }
                }
            }

            if (rec != null && rec.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rec.tags.take(12).forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                            modifier = Modifier.clickable { onSearchTag?.invoke(tag) }
                        ) {
                            Text(
                                text = "#$tag",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = questionInput,
                    onValueChange = { questionInput = it },
                    placeholder = { Text("Ask…", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        val q = questionInput.trim()
                        if (q.isNotBlank() && !isAsking) {
                            isAsking = true
                            errorMsg = null
                            scope.launch {
                                val res = AiIndexingOrchestrator.askAboutPhoto(
                                    context = context,
                                    item = item,
                                    question = q,
                                    isLockedVaultItem = isLockedVaultItem
                                )
                                res.onSuccess { ans -> qaAnswer = ans }
                                    .onFailure { err -> errorMsg = err.localizedMessage ?: "Failed" }
                                isAsking = false
                            }
                        }
                    },
                    enabled = questionInput.isNotBlank() && !isAsking,
                    modifier = Modifier.size(36.dp)
                ) {
                    if (isAsking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Send, contentDescription = "Ask", modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (!qaAnswer.isNullOrBlank()) {
                Text(
                    text = qaAnswer.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (!errorMsg.isNullOrBlank()) {
                Text(
                    text = errorMsg.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
