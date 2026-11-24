package com.amresalehin.emregallery.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.data.ai.AiMediaMetadata
import com.amresalehin.emregallery.data.ai.AiMetadataEvents
import com.amresalehin.emregallery.data.ai.AiMetadataStore
import com.amresalehin.emregallery.data.ai.AiSettings
import com.amresalehin.emregallery.data.ai.AiSettingsRepository
import com.amresalehin.emregallery.data.ai.AiVisionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectAsState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiEnrichmentSection(
    item: MediaItem,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository = remember(context) { AiSettingsRepository(context.applicationContext) }
    val store = remember(context) { AiMetadataStore(context.applicationContext) }

    var metadata by remember(item.uri) { mutableStateOf<AiMediaMetadata?>(null) }
    val currentSettings by settingsRepository.settings.collectAsState(initial = AiSettings())
    val isConfigured = currentSettings.isConfigured
    var isGenerating by remember(item.uri) { mutableStateOf(false) }
    var message by remember(item.uri) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.uri) {
        metadata = withContext(Dispatchers.IO) { store.get(item.uri) }
        AiMetadataEvents.updates.collect { changedUri ->
            if (changedUri == item.uri) {
                metadata = withContext(Dispatchers.IO) { store.get(item.uri) }
            }
        }
    }

    fun generate() {
        if (isGenerating) return
        scope.launch {
            isGenerating = true
            message = null
            try {
                val settings = currentSettings
                if (!settings.isConfigured) {
                    message = "Configure a BYOK API or local vision model in Settings → AI & Models."
                    return@launch
                }
                val result = AiVisionRepository(context.applicationContext).enrich(item, settings)
                withContext(Dispatchers.IO) { store.save(result) }
                metadata = result
                message = "Description and tags updated."
            } catch (error: Exception) {
                message = error.localizedMessage ?: "AI analysis failed."
            } finally {
                isGenerating = false
            }
        }
    }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "AI description & tags",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 4.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            val saved = metadata
            if (saved != null) {
                Text(
                    saved.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                saved.tags.forEach { (category, tags) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            category.replace('_', ' ').replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            tags.forEach { tag ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        tag,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "Generated with ${saved.model}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    if (isConfigured) "No AI description has been generated for this item yet."
                    else "Set up a BYOK provider or a local MNN / llama.cpp vision endpoint to begin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (message != null) {
                Text(
                    message.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (saved == null) {
                Button(
                    onClick = { generate() },
                    enabled = !isGenerating && isConfigured
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Text("Generate description & tags", modifier = Modifier.padding(start = 8.dp))
                }
            } else {
                OutlinedButton(
                    onClick = { generate() },
                    enabled = !isGenerating
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("Regenerate", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
