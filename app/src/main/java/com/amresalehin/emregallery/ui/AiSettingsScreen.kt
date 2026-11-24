package com.amresalehin.emregallery.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.amresalehin.emregallery.IndexingForegroundService
import com.amresalehin.emregallery.data.ai.AiMetadataStore
import com.amresalehin.emregallery.data.ai.AiProviderMode
import com.amresalehin.emregallery.data.ai.AiSettings
import com.amresalehin.emregallery.data.ai.AiSettingsRepository
import com.amresalehin.emregallery.data.ai.AiVisionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    totalMedia: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository = remember(context) { AiSettingsRepository(context.applicationContext) }

    var initialized by remember { mutableStateOf(false) }
    var providerMode by remember { mutableStateOf(AiProviderMode.OPENAI_COMPATIBLE) }
    var baseUrl by remember { mutableStateOf(AiProviderMode.OPENAI_COMPATIBLE.defaultBaseUrl) }
    var model by remember { mutableStateOf("gpt-4.1-mini") }
    var apiKey by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var discoveredModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var indexedCount by remember { mutableIntStateOf(0) }
    var processed by remember { mutableIntStateOf(0) }
    var indexingRunning by remember { mutableStateOf(false) }
    var indexingPaused by remember { mutableStateOf(false) }
    var currentItemName by remember { mutableStateOf("") }
    var taskError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val saved = settingsRepository.settings.first()
        providerMode = saved.providerMode
        baseUrl = saved.baseUrl
        model = saved.model
        apiKey = saved.apiKey
        initialized = true
        while (isActive) {
            val state = context.getSharedPreferences(
                IndexingForegroundService.STATE_PREFS,
                Context.MODE_PRIVATE
            )
            val isAiTask = state.getString(IndexingForegroundService.KEY_TASK, "") == "ai"
            indexingRunning = isAiTask && state.getBoolean(IndexingForegroundService.KEY_RUNNING, false)
            indexingPaused = isAiTask && state.getBoolean(IndexingForegroundService.KEY_PAUSED, false)
            processed = if (isAiTask) state.getInt(IndexingForegroundService.KEY_PROCESSED, 0) else 0
            currentItemName = if (isAiTask) state.getString(IndexingForegroundService.KEY_CURRENT_ITEM_NAME, "").orEmpty() else ""
            taskError = if (isAiTask) state.getString(IndexingForegroundService.KEY_ERROR, null) else null
            indexedCount = withContext(Dispatchers.IO) { AiMetadataStore(context).count() }
            delay(1800)
        }
    }

    val currentSettings = AiSettings(providerMode, baseUrl, model, apiKey)
    val configured = currentSettings.isConfigured
    val localProvider = providerMode.isLocal

    fun saveSettings() {
        if (!initialized || busy) return
        scope.launch {
            busy = true
            try {
                settingsRepository.save(currentSettings)
                message = "AI provider settings saved. API keys are encrypted in app-private storage."
            } catch (error: Exception) {
                message = error.localizedMessage ?: "Could not save AI settings."
            } finally {
                busy = false
            }
        }
    }

    fun testConnection() {
        if (busy) return
        scope.launch {
            busy = true
            message = "Connecting to the selected vision endpoint…"
            try {
                val models = AiVisionRepository(context.applicationContext).testConnection(currentSettings)
                discoveredModels = models
                message = if (models.isEmpty()) {
                    "Endpoint responded, but it did not return a model list. Enter the model ID manually."
                } else {
                    "Connected. Found ${models.size} model(s). Select a model below."
                }
            } catch (error: Exception) {
                message = error.localizedMessage ?: "Could not connect to the AI endpoint."
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI & Models", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Descriptions and automatic tags", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Inference provider", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            val modes = AiProviderMode.entries
                            modes.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = providerMode == mode,
                                    onClick = {
                                        val previousDefault = providerMode.defaultBaseUrl
                                        providerMode = mode
                                        if (baseUrl.isBlank() || baseUrl == previousDefault) baseUrl = mode.defaultBaseUrl
                                        if (mode == AiProviderMode.OPENAI_COMPATIBLE && model.isBlank()) model = "gpt-4.1-mini"
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                    icon = {}
                                ) {
                                    Text(
                                        when (mode) {
                                            AiProviderMode.OPENAI_COMPATIBLE -> "BYOK"
                                            AiProviderMode.MNN_LOCAL -> "MNN"
                                            AiProviderMode.LLAMA_CPP_LOCAL -> "llama.cpp"
                                        },
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                        Text(
                            providerMode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("API base URL") },
                            singleLine = true,
                            supportingText = {
                                Text("Use the /v1 base URL, not the full chat-completions path.")
                            }
                        )
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Vision model ID") },
                            singleLine = true,
                            supportingText = {
                                Text("Choose a model that accepts image input, not a text-only model.")
                            }
                        )
                        if (!localProvider) {
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("API key") },
                                singleLine = true,
                                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    TextButton(onClick = { showApiKey = !showApiKey }) {
                                        Text(if (showApiKey) "Hide" else "Show")
                                    }
                                },
                                supportingText = {
                                    Text("Stored encrypted using Android Keystore. Never included in logs.")
                                }
                            )
                        } else {
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Local server token (optional)") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation()
                            )
                            Text(
                                "Start the MNN Chat API service or llama.cpp server on this device. Eye connects to its local OpenAI-compatible /v1 endpoint; the vision model must be loaded by that runtime. For llama.cpp, use a multimodal GGUF model and its matching mmproj.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { saveSettings() }, enabled = initialized && !busy) {
                                Text("Save")
                            }
                            OutlinedButton(onClick = { testConnection() }, enabled = initialized && !busy && configured) {
                                if (busy) {
                                    CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(7.dp))
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(Modifier.width(5.dp))
                                }
                                Text("Test / fetch models")
                            }
                        }
                        if (discoveredModels.isNotEmpty()) {
                            Text("Models reported by endpoint", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            discoveredModels.take(20).forEach { modelId ->
                                TextButton(
                                    onClick = { model = modelId },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Text(if (model == modelId) "✓ $modelId" else modelId)
                                }
                            }
                        }
                        if (message != null) {
                            Text(message.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Automatic descriptions & tags", modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Eye analyses photos and representative video frames. It creates a natural-language description and separate tags for people, objects, activities, locations, events, scenes, and visual attributes. Original files and EXIF metadata are never modified.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Enriched media", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(indexedCount.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Library items", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(totalMedia.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (indexingRunning || indexingPaused) {
                            Text(
                                if (indexingPaused) "Paused at $processed of $totalMedia" else "Processing $processed of $totalMedia",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (currentItemName.isNotBlank()) {
                                Text(currentItemName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { if (indexingPaused) IndexingForegroundService.resume(context) else IndexingForegroundService.pause(context) }) {
                                    Icon(if (indexingPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null)
                                    Text(if (indexingPaused) "Resume" else "Pause", modifier = Modifier.padding(start = 5.dp))
                                }
                                OutlinedButton(onClick = { IndexingForegroundService.cancel(context) }) {
                                    Icon(Icons.Default.Stop, contentDescription = null)
                                    Text("Cancel", modifier = Modifier.padding(start = 5.dp))
                                }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { IndexingForegroundService.startAiIndexing(context, rescanAll = false) },
                                    enabled = initialized && configured && !busy
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Text("Index missing", modifier = Modifier.padding(start = 5.dp))
                                }
                                OutlinedButton(
                                    onClick = { IndexingForegroundService.startAiIndexing(context, rescanAll = true) },
                                    enabled = initialized && configured && !busy
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Text("Reprocess all", modifier = Modifier.padding(start = 5.dp))
                                }
                            }
                        }
                        if (taskError != null && taskError != "Cancelled") {
                            Text("Last indexing error: $taskError", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            "Indexing runs in the background and can be paused or cancelled. Eye caches results locally and skips unchanged media on the next run.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
