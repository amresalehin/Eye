package com.amresalehin.emregallery.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
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

@Composable
fun AiViewerInsightsCard(
    item: MediaItem,
    customAlbums: List<CustomAlbum>,
    onAddTags: (Collection<String>) -> Unit,
    onAddToNamedAlbum: (Collection<String>) -> Unit,
    onOpenAiSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { PreferencesRepository(context.applicationContext) }
    val aiStore = remember(context) { AiMetadataStore(context.applicationContext) }
    val aiClient = remember { CustomAiClient() }
    val aiConfig by prefs.aiConfig.collectAsStateWithLifecycle(initialValue = AiConfig())
    val aiProviders by prefs.aiProviders.collectAsStateWithLifecycle(initialValue = emptyList())
    val configuredProviders = aiProviders.filter { it.isConfigured }

    var analysis by remember(item.uri) { mutableStateOf<AiMediaAnalysis?>(null) }
    var isAnalyzing by remember(item.uri) { mutableStateOf(false) }
    var analysisError by remember(item.uri) { mutableStateOf<String?>(null) }
    var providerMenuExpanded by remember { mutableStateOf(false) }


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
            val result = aiClient.analyzeImage(
                context = context,
                mediaUri = item.uri,
                config = aiConfig,
                ocrContext = ocrText
            )
            result.onSuccess { parsed ->
                withContext(Dispatchers.IO) { aiStore.saveAnalysis(parsed) }
                if (aiConfig.autoApplyTags && parsed.tags.isNotEmpty()) {
                    onAddTags(parsed.tags)
                }
                if (aiConfig.autoOrganizeCollections && parsed.suggestedCollections.isNotEmpty()) {
                    onAddToNamedAlbum(parsed.suggestedCollections)
                }
                analysis = parsed
            }.onFailure { err ->
                analysisError = err.message ?: "Analysis failed"
            }
            isAnalyzing = false
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
                if (aiConfig.hasCredentials && currentAnalysis != null) {
                    IconButton(
                        onClick = ::triggerAnalysis,
                        enabled = !isAnalyzing,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("viewer_ai_reanalyze_button")
                    ) {
                        if (isAnalyzing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Re-analyze photo",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                if (configuredProviders.isNotEmpty()) {
                    Box {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .clickable(enabled = !isAnalyzing) { providerMenuExpanded = true }
                                .testTag("viewer_ai_provider_selector")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = aiConfig.providerName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 132.dp)
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Switch AI provider",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = providerMenuExpanded,
                            onDismissRequest = { providerMenuExpanded = false }
                        ) {
                            configuredProviders.forEach { provider ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = provider.name,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = provider.modelId,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    },
                                    trailingIcon = if (provider.id == aiConfig.providerId) {
                                        {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = "Active provider",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    } else null,
                                    enabled = !isAnalyzing,
                                    onClick = {
                                        providerMenuExpanded = false
                                        if (provider.id != aiConfig.providerId) {
                                            scope.launch {
                                                prefs.selectAiProvider(provider.id)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                } else if (onOpenAiSettings != null) {
                    IconButton(
                        onClick = onOpenAiSettings,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("viewer_ai_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = "AI Setup",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        if (aiConfig.hasCredentials && currentAnalysis == null) {
            Button(
                onClick = ::triggerAnalysis,
                enabled = !isAnalyzing,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("viewer_ai_analyze_button")
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(if (isAnalyzing) "Analyzing..." else "Analyze This Photo")
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = currentAnalysis.title,
                            style = MaterialTheme.typography.titleMedium,
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

                        val suggestedCollections = currentAnalysis.suggestedCollections
                            .ifEmpty { listOf(currentAnalysis.suggestedCollection).filter { it.isNotBlank() } }
                        if (suggestedCollections.isNotEmpty()) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                suggestedCollections.forEachIndexed { index, collection ->
                                    val alreadyInAlbum = customAlbums.any {
                                        it.name.equals(collection, ignoreCase = true) &&
                                            item.uri in it.mediaUris
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                        ),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable(enabled = !alreadyInAlbum) {
                                                onAddToNamedAlbum(listOf(collection))
                                            }
                                            .testTag(
                                                if (index == 0) "viewer_ai_collection_chip"
                                                else "viewer_ai_collection_chip_$index"
                                            )
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
                                                text = collection,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface,
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

        }
    }
}
