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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val metadataStore = remember(context) { AiMetadataStore(context.applicationContext) }

    var initialized by remember { mutableStateOf(false) }
    var providerMode by remember { mutableStateOf(AiProviderMode.OPENAI_COMPATIBLE) }
    var baseUrl by remember { mutableStateOf(AiProviderMode.OPENAI_COMPATIBLE.defaultBaseUrl) }
    var model by remember { mutableStateOf("gpt-4.1-mini") }
    var apiKey by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var discoveredModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var connectionReady by remember { mutableStateOf(false) }

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
            currentItemName = if (isAiTask) {
                state.getString(IndexingForegroundService.KEY_CURRENT_ITEM_NAME, "").orEmpty()
            } else {
                ""
            }
            taskError = if (isAiTask) state.getString(IndexingForegroundService.KEY_ERROR, null) else null
            indexedCount = withContext(Dispatchers.IO) { metadataStore.count() }
            delay(2500)
        }
    }

    val localProvider = providerMode.isLocal
    val onDeviceProvider = providerMode.isOnDevice
    val currentSettings = AiSettings(providerMode, baseUrl, model, apiKey)
    val configured = currentSettings.isConfigured &&
        (!onDeviceProvider || com.amresalehin.emregallery.data.ai.AiModelStorage.resolveSelection(context) != null)
    val canConnect = !onDeviceProvider && initialized && !busy && baseUrl.isNotBlank() &&
        (localProvider || apiKey.isNotBlank())

    fun saveSettings() {
        if (!initialized || busy) return
        scope.launch {
            busy = true
            try {
                settingsRepository.save(currentSettings)
                message = "Saved"
                messageIsError = false
            } catch (error: Exception) {
                message = error.localizedMessage ?: "Could not save settings"
                messageIsError = true
            } finally {
                busy = false
            }
        }
    }

    fun startAiIndexing(rescanAll: Boolean) {
        if (!initialized || !configured || busy || indexingRunning) return
        scope.launch {
            busy = true
            try {
                settingsRepository.save(currentSettings)
                IndexingForegroundService.startAiIndexing(context, rescanAll = rescanAll)
            } catch (error: Exception) {
                message = error.localizedMessage ?: "Could not start AI indexing"
                messageIsError = true
            } finally {
                busy = false
            }
        }
    }

    fun discoverModels() {
        if (!canConnect) return
        scope.launch {
            busy = true
            message = null
            messageIsError = false
            try {
                val models = AiVisionRepository(context.applicationContext).testConnection(currentSettings)
                discoveredModels = models
                connectionReady = true
                if (models.isNotEmpty() && model !in models) {
                    model = models.first()
                }
                message = if (models.isEmpty()) "Connected" else "${models.size} models found"
                messageIsError = false
            } catch (error: Exception) {
                discoveredModels = emptyList()
                connectionReady = false
                message = error.localizedMessage ?: "Connection failed"
                messageIsError = true
            } finally {
                busy = false
            }
        }
    }

    fun chooseModel(modelId: String) {
        model = modelId
        modelMenuExpanded = false
        message = null
        scope.launch {
            runCatching {
                settingsRepository.save(currentSettings.copy(model = modelId))
            }.onSuccess {
                message = "Model selected"
                messageIsError = false
            }.onFailure { error ->
                message = error.localizedMessage ?: "Could not save model"
                messageIsError = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            "AI & Models",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Choose a vision service",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                LocalAiModelsSection { selectedModelId ->
                    providerMode = AiProviderMode.ON_DEVICE
                    baseUrl = ""
                    model = selectedModelId
                    discoveredModels = emptyList()
                    connectionReady = false
                    message = null
                }
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("AI service", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = providerMode == AiProviderMode.OPENAI_COMPATIBLE,
                                onClick = {
                                    providerMode = AiProviderMode.OPENAI_COMPATIBLE
                                    baseUrl = AiProviderMode.OPENAI_COMPATIBLE.defaultBaseUrl
                                    if (model.isBlank() || onDeviceProvider) model = "gpt-4.1-mini"
                                    discoveredModels = emptyList()
                                    connectionReady = false
                                    message = null
                                },
                                shape = SegmentedButtonDefaults.itemShape(0, 3),
                                icon = {}
                            ) { Text("BYOK") }
                            SegmentedButton(
                                selected = onDeviceProvider,
                                onClick = {
                                    providerMode = AiProviderMode.ON_DEVICE
                                    baseUrl = ""
                                    model = com.amresalehin.emregallery.data.ai.AiModelStorage.selectedModelId(context)
                                    discoveredModels = emptyList()
                                    connectionReady = false
                                    message = null
                                },
                                shape = SegmentedButtonDefaults.itemShape(1, 3),
                                icon = {}
                            ) { Text("On-device") }
                            SegmentedButton(
                                selected = providerMode == AiProviderMode.EXTERNAL_LOCAL,
                                onClick = {
                                    providerMode = AiProviderMode.EXTERNAL_LOCAL
                                    baseUrl = AiProviderMode.EXTERNAL_LOCAL.defaultBaseUrl
                                    model = ""
                                    discoveredModels = emptyList()
                                    connectionReady = false
                                    message = null
                                },
                                shape = SegmentedButtonDefaults.itemShape(2, 3),
                                icon = {}
                            ) { Text("Server") }
                        }
                        if (providerMode == AiProviderMode.EXTERNAL_LOCAL) {
                            Text("OpenAI-compatible server", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!onDeviceProvider) {
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it; discoveredModels = emptyList(); connectionReady = false },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(if (providerMode == AiProviderMode.EXTERNAL_LOCAL) "Server URL" else "API endpoint") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                        if (providerMode == AiProviderMode.OPENAI_COMPATIBLE) {
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("API key") },
                                singleLine = true,
                                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    TextButton(onClick = { showApiKey = !showApiKey }) { Text(if (showApiKey) "Hide" else "Show") }
                                },
                                shape = RoundedCornerShape(14.dp)
                            )
                        } else if (providerMode == AiProviderMode.EXTERNAL_LOCAL && apiKey.isNotBlank()) {
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Server token") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { saveSettings() }, enabled = initialized && !busy) { Text("Save") }
                            if (!onDeviceProvider) {
                                OutlinedButton(onClick = { discoverModels() }, enabled = canConnect) {
                                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (connectionReady) "Refresh models" else "Test connection")
                                }
                            }
                        }
                        if (message != null) Text(
                            message.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (discoveredModels.isNotEmpty()) {
                            ExposedDropdownMenuBox(
                                expanded = modelMenuExpanded,
                                onExpandedChange = { modelMenuExpanded = it },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = model,
                                    onValueChange = {},
                                    readOnly = true,
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    label = { Text("Vision model") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                ExposedDropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                                    discoveredModels.forEach { modelId ->
                                        DropdownMenuItem(
                                            text = { Text(modelId, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            leadingIcon = { if (modelId == model) Icon(Icons.Default.Check, contentDescription = null) },
                                            onClick = { chooseModel(modelId) }
                                        )
                                    }
                                }
                            }
                        } else if (!localProvider && !onDeviceProvider) {
                            OutlinedTextField(
                                value = model,
                                onValueChange = { model = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Vision model ID") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                    }
                }
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Automatic descriptions & tags",
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    "Enriched media",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    indexedCount.toString(),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "Library",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    totalMedia.toString(),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        if (indexingRunning || indexingPaused) {
                            LinearProgressIndicator(
                                progress = {
                                    if (totalMedia > 0) {
                                        (processed.toFloat() / totalMedia.toFloat()).coerceIn(0f, 1f)
                                    } else 0f
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                if (indexingPaused) "Paused · $processed / $totalMedia"
                                else "Processing · $processed / $totalMedia",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (currentItemName.isNotBlank()) {
                                Text(
                                    currentItemName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        if (indexingPaused) IndexingForegroundService.resume(context)
                                        else IndexingForegroundService.pause(context)
                                    }
                                ) {
                                    Icon(
                                        if (indexingPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                        contentDescription = null
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(if (indexingPaused) "Resume" else "Pause")
                                }
                                OutlinedButton(onClick = { IndexingForegroundService.cancel(context) }) {
                                    Icon(Icons.Default.Stop, contentDescription = null)
                                    Spacer(Modifier.width(5.dp))
                                    Text("Cancel")
                                }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { startAiIndexing(rescanAll = false) },
                                    enabled = initialized && configured && !busy
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(5.dp))
                                    Text("Index missing")
                                }
                                OutlinedButton(
                                    onClick = { startAiIndexing(rescanAll = true) },
                                    enabled = initialized && configured && !busy
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(Modifier.width(5.dp))
                                    Text("Reprocess")
                                }
                            }
                        }

                        if (taskError != null && taskError != "Cancelled") {
                            Text(
                                taskError.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}
