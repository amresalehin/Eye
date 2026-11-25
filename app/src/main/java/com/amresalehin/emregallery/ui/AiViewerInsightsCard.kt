package com.amresalehin.emregallery.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amresalehin.emregallery.ai.AiMediaAnalysis
import com.amresalehin.emregallery.ai.AiMetadataStore
import com.amresalehin.emregallery.ai.AiStoreEvents
import com.amresalehin.emregallery.ai.CustomAiClient
import com.amresalehin.emregallery.data.CustomAlbum
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.data.preferences.AiConfig
import com.amresalehin.emregallery.ocr.TesseractOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiViewerInsightsCard(
    item: MediaItem,
    customAlbums: List<CustomAlbum>,
    customTags: Set<String>,
    onAddTags: (Collection<String>) -> Unit,
    onAddToNamedAlbum: (String) -> Unit,
    onOpenAiSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { PreferencesRepository(context.applicationContext) }
    val aiStore = remember(context) { AiMetadataStore(context.applicationContext) }
    val aiClient = remember { CustomAiClient() }
    val aiConfig by prefs.aiConfig.collectAsStateWithLifecycle(initialValue = AiConfig())

    var analysis by remember(item.uri) { mutableStateOf<AiMediaAnalysis?>(null) }
    var isAnalyzing by remember(item.uri) { mutableStateOf(false) }
    var analysisError by remember(item.uri) { mutableStateOf<String?>(null) }

    var questionInput by remember(item.uri) { mutableStateOf("") }
    var isAsking by remember(item.uri) { mutableStateOf(false) }
    var answerText by remember(item.uri) { mutableStateOf<String?>(null) }
    var askError by remember(item.uri) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.uri) {
        suspend fun loadCached() {
            analysis = withContext(Dispatchers.IO) { aiStore.getAnalysis(item.uri) }
        }
        loadCached()
        AiStoreEvents.updates.collect { updatedUri ->
            if (updatedUri.isBlank() || updatedUri == item.uri) {
                loadCached()
            }
        }
    }

    fun triggerAnalysis() {
        if (item.isVideo || isAnalyzing) return
        if (!aiConfig.isConfigured) {
            onOpenAiSettings?.invoke()
            return
        }
        isAnalyzing = true
        analysisError = null
        scope.launch {
            val ocrText = if (aiConfig.includeOcrContext) {
                withContext(Dispatchers.IO) {
                    TesseractOcr.readCachedResult(context, Uri.parse(item.uri))
                }
            } else null
            val existingCollections = customAlbums.map { it.name }.distinct()
            val result = aiClient.analyzeImage(
                context = context,
                mediaUri = item.uri,
                config = aiConfig,
                existingCollections = existingCollections,
                ocrContext = ocrText
            )
            result.onSuccess { parsed ->
                withContext(Dispatchers.IO) { aiStore.saveAnalysis(parsed) }
                if (aiConfig.autoApplyTags && parsed.tags.isNotEmpty()) {
                    onAddTags(parsed.tags)
                }
                if (aiConfig.autoOrganizeCollections && parsed.suggestedCollection.isNotBlank()) {
                    onAddToNamedAlbum(parsed.suggestedCollection)
                }
                analysis = parsed
            }.onFailure { err ->
                analysisError = err.message ?: "Analysis failed"
            }
            isAnalyzing = false
        }
    }

    fun submitQuestion() {
        val q = questionInput.trim()
        if (q.isEmpty() || item.isVideo || isAsking) return
        if (!aiConfig.isConfigured) {
            onOpenAiSettings?.invoke()
            return
        }
        isAsking = true
        askError = null
        scope.launch {
            val ocrText = if (aiConfig.includeOcrContext) {
                withContext(Dispatchers.IO) {
                    TesseractOcr.readCachedResult(context, Uri.parse(item.uri))
                }
            } else null
            val result = aiClient.askAboutImage(
                context = context,
                mediaUri = item.uri,
                question = q,
                config = aiConfig,
                ocrContext = ocrText
            )
            result.onSuccess { answer ->
                answerText = answer
            }.onFailure { err ->
                askError = err.message ?: "Request failed"
            }
            isAsking = false
        }
    }

    if (item.isVideo) return

    val currentAnalysis = analysis?.takeIf { it.isCompleted }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("viewer_ai_insights_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Section Header matching Albums / Tags / People / Location / Metadata
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "AI Insights",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (aiConfig.hasCredentials) {
                    // Compact pill matching People section's engine selector pill
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = !isAnalyzing) { triggerAnalysis() }
                            .testTag("viewer_ai_analyze_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (isAnalyzing) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.8.dp)
                            } else {
                                Icon(
                                    imageVector = if (currentAnalysis != null) Icons.Default.Refresh else Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                text = when {
                                    isAnalyzing -> "Analyzing…"
                                    currentAnalysis != null -> "Re-analyze"
                                    else -> "Analyze"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                if (onOpenAiSettings != null) {
                    IconButton(
                        onClick = onOpenAiSettings,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("viewer_ai_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = "AI Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        if (!aiConfig.hasCredentials) {
            // Empty-state pill identical to "Add to album" / "Add tag" / "Add location"
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                modifier = Modifier
                    .clickable(enabled = onOpenAiSettings != null) { onOpenAiSettings?.invoke() }
                    .testTag("viewer_ai_setup_key_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Configure AI",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else {
            if (currentAnalysis != null) {
                DetailCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = currentAnalysis.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (currentAnalysis.description.isNotBlank()) {
                            Text(
                                text = currentAnalysis.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (currentAnalysis.keyDetails.isNotBlank()) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                            )
                            Text(
                                text = currentAnalysis.keyDetails,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (currentAnalysis.suggestedCollection.isNotBlank() || currentAnalysis.tags.isNotEmpty()) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                            )
                        }

                        if (currentAnalysis.suggestedCollection.isNotBlank()) {
                            val alreadyInAlbum = customAlbums.any {
                                it.name.equals(currentAnalysis.suggestedCollection, ignoreCase = true) &&
                                    item.uri in it.mediaUris
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(enabled = !alreadyInAlbum) {
                                        onAddToNamedAlbum(currentAnalysis.suggestedCollection)
                                    }
                                    .testTag("viewer_ai_collection_chip")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (alreadyInAlbum) Icons.Default.Check else Icons.Default.PhotoAlbum,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = currentAnalysis.suggestedCollection,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        if (currentAnalysis.tags.isNotEmpty()) {
                            val unsavedTags = currentAnalysis.tags.filterNot { tag ->
                                customTags.any { it.equals(tag, ignoreCase = true) }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                currentAnalysis.tags.forEach { tag ->
                                    val isSaved = customTags.any { it.equals(tag, ignoreCase = true) }
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                        ),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable(enabled = !isSaved) { onAddTags(listOf(tag)) }
                                    ) {
                                        Text(
                                            text = tag,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isSaved) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                                if (unsavedTags.isNotEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { onAddTags(unsavedTags) }
                                            .testTag("viewer_ai_save_all_tags_button")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Add,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = "Save tags",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            analysisError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Compact Q&A field matching ActionsTabContent's OutlinedTextField styling
            OutlinedTextField(
                value = questionInput,
                onValueChange = { questionInput = it },
                placeholder = { Text("Ask AI…") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                trailingIcon = {
                    IconButton(
                        onClick = ::submitQuestion,
                        enabled = questionInput.isNotBlank() && !isAsking,
                        modifier = Modifier.testTag("viewer_ai_ask_button")
                    ) {
                        if (isAsking) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Ask AI"
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("viewer_ai_question_input")
            )

            askError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            answerText?.let { answer ->
                DetailCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("AI Answer", answer))
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
