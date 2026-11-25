package com.amresalehin.emregallery.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoAwesome
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
                analysisError = err.message ?: "AI analysis failed"
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
                askError = err.message ?: "Could not answer question"
            }
            isAsking = false
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "AI Insights",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (onOpenAiSettings != null) {
                TextButton(
                    onClick = onOpenAiSettings,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("viewer_ai_settings_button")
                ) {
                    Text(
                        text = "AI Setup",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("viewer_ai_insights_card")
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    item.isVideo -> {
                        Text(
                            text = "AI vision analysis is available for photos and screenshots.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    !aiConfig.isConfigured -> {
                        Text(
                            text = "Configure an API endpoint, key and model to generate titles, descriptions, smart tags, collections, and visual Q&A.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (onOpenAiSettings != null) {
                            Button(
                                onClick = onOpenAiSettings,
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("viewer_ai_setup_key_button")
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Open AI Setup")
                            }
                        }
                    }
                    else -> {
                        val currentAnalysis = analysis?.takeIf { it.isCompleted }
                        if (currentAnalysis != null) {
                            Text(
                                text = currentAnalysis.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (currentAnalysis.description.isNotBlank()) {
                                Text(
                                    text = currentAnalysis.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (currentAnalysis.keyDetails.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = currentAnalysis.keyDetails,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }

                            if (currentAnalysis.suggestedCollection.isNotBlank()) {
                                val alreadyInAlbum = customAlbums.any {
                                    it.name.equals(currentAnalysis.suggestedCollection, ignoreCase = true) &&
                                        item.uri in it.mediaUris
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
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
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (alreadyInAlbum) Icons.Default.Check else Icons.Default.CreateNewFolder,
                                                contentDescription = null,
                                                modifier = Modifier.size(15.dp),
                                                tint = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = if (alreadyInAlbum) {
                                                    "In ${currentAnalysis.suggestedCollection}"
                                                } else {
                                                    "Add to ${currentAnalysis.suggestedCollection}"
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }

                            if (currentAnalysis.tags.isNotEmpty()) {
                                val unsavedTags = currentAnalysis.tags.filterNot { tag ->
                                    customTags.any { it.equals(tag, ignoreCase = true) }
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    currentAnalysis.tags.forEach { tag ->
                                        val isSaved = customTags.any { it.equals(tag, ignoreCase = true) }
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable(enabled = !isSaved) { onAddTags(listOf(tag)) }
                                        ) {
                                            Text(
                                                text = "#$tag",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (isSaved) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                            )
                                        }
                                    }
                                }
                                if (unsavedTags.isNotEmpty()) {
                                    TextButton(
                                        onClick = { onAddTags(unsavedTags) },
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 48.dp)
                                            .testTag("viewer_ai_save_all_tags_button")
                                    ) {
                                        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Save ${unsavedTags.size} AI tags")
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "Run AI analysis to generate a searchable title, description, tags, and smart collection.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = ::triggerAnalysis,
                                enabled = !isAnalyzing,
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("viewer_ai_analyze_button")
                            ) {
                                if (isAnalyzing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Analyzing…")
                                } else {
                                    Icon(
                                        imageVector = if (currentAnalysis != null) Icons.Default.Refresh else Icons.Outlined.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (currentAnalysis != null) "Re-analyze" else "Analyze with AI")
                                }
                            }
                        }

                        analysisError?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                        // Ask AI about this photo Q&A
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = questionInput,
                                onValueChange = { questionInput = it },
                                label = { Text("Ask AI about this photo") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("viewer_ai_question_input")
                            )
                            IconButton(
                                onClick = ::submitQuestion,
                                enabled = questionInput.isNotBlank() && !isAsking,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("viewer_ai_ask_button")
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
                        }

                        askError?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        answerText?.let { answer ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "AI Answer",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
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
                                                contentDescription = "Copy answer",
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = answer,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
