package com.amresalehin.emregallery.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amresalehin.emregallery.ai.AiMetadataStore
import com.amresalehin.emregallery.ai.CustomAiClient
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.data.preferences.AiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiSettingsScreen(
    totalPhotos: Int,
    onOpenBatchIndexing: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesRepository(context.applicationContext) }
    val aiStore = remember { AiMetadataStore(context.applicationContext) }
    val aiClient = remember { CustomAiClient() }

    val config by prefs.aiConfig.collectAsStateWithLifecycle(initialValue = AiConfig())
    var analyzedCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        analyzedCount = withContext(Dispatchers.IO) { aiStore.getTotalCompletedCount() }
    }

    var apiKeyInput by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var modelInput by remember(config.modelId) { mutableStateOf(config.modelId) }
    var baseUrlInput by remember(config.customBaseUrl) { mutableStateOf(config.customBaseUrl) }
    var promptInput by remember(config.customPromptInstructions) { mutableStateOf(config.customPromptInstructions) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var isTestingVision by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }

    val lightAppearance = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val cardBackground = MaterialTheme.colorScheme.surfaceContainer
    val cardBorder = BorderStroke(
        1.dp,
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (lightAppearance) 0.5f else 0.35f)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.statusBarsPadding())
            Spacer(Modifier.height(60.dp))

            // 1. Overview & Status Card
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackground),
                border = cardBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Custom API Key AI",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = when {
                                    !config.enabled -> "AI features paused"
                                    config.isConfigured -> "Configured · ${config.modelId} · test text and vision separately"
                                    else -> "Enter your own API key to analyze & search"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { enabled -> scope.launch { prefs.setAiEnabled(enabled) } },
                            modifier = Modifier.testTag("ai_settings_enabled_switch")
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "$analyzedCount photos analyzed",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (totalPhotos > 0) "of $totalPhotos gallery photos" else "Ready for single or batch analysis",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = onOpenBatchIndexing,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("ai_settings_open_indexing_button")
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Batch Index")
                        }
                    }
                }
            }

            // 2. Provider & Custom API Key Card
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackground),
                border = cardBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "API Configuration",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = {
                            apiKeyInput = it
                            connectionStatus = null
                        },
                        label = { Text("API Key") },
                        placeholder = { Text("Paste provider API key") },
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { showApiKey = !showApiKey },
                                    modifier = Modifier.testTag("ai_api_key_visibility_toggle")
                                ) {
                                    Icon(
                                        imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showApiKey) "Hide API key" else "Show API key"
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_api_key_input")
                    )

                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = {
                            modelInput = it
                            connectionStatus = null
                        },
                        label = { Text("Model ID") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_model_id_input")
                    )

                    OutlinedTextField(
                        value = baseUrlInput,
                        onValueChange = {
                            baseUrlInput = it
                            connectionStatus = null
                        },
                        label = { Text("API Base URL") },
                        placeholder = { Text(AiConfig.DEFAULT_API_BASE_URL) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_base_url_input")
                    )

                    Text(
                        text = "OpenAI-compatible Chat Completions endpoint. Gemini (AI Studio): https://generativelanguage.googleapis.com/v1beta/openai",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val pasted = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                                if (pasted.isNotEmpty()) {
                                    apiKeyInput = pasted
                                    scope.launch {
                                        prefs.setAiConfiguration(
                                            pasted,
                                            modelInput.trim().ifBlank { config.modelId },
                                            baseUrlInput.trim().trimEnd('/')
                                        )
                                    }
                                    actionFeedback = "API key pasted and saved locally"
                                }
                            },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("ai_api_key_paste_button")
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Paste")
                        }

                        if (apiKeyInput.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    apiKeyInput = ""
                                    connectionStatus = null
                                    scope.launch { prefs.setAiApiKey("") }
                                },
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("ai_api_key_clear_button")
                            ) {
                                Text("Clear")
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        Button(
                            onClick = {
                                val cleanedKey = apiKeyInput.trim()
                                val cleanedModel = modelInput.trim().ifBlank { AiConfig.DEFAULT_MODEL_ID }
                                val cleanedUrl = baseUrlInput.trim().trimEnd('/')
                                scope.launch {
                                    prefs.setAiConfiguration(cleanedKey, cleanedModel, cleanedUrl)
                                    actionFeedback = "AI configuration saved on device"
                                }
                            },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("ai_save_config_button")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save")
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    if (isTestingConnection || isTestingVision) return@FilledTonalButton
                                    isTestingConnection = true
                                    connectionStatus = null
                                    val testConfig = config.copy(
                                        apiKey = apiKeyInput.trim(),
                                        modelId = modelInput.trim().ifBlank { config.modelId },
                                        customBaseUrl = baseUrlInput.trim().trimEnd('/')
                                    )
                                    scope.launch {
                                        prefs.setAiConfiguration(
                                            testConfig.apiKey,
                                            testConfig.modelId,
                                            testConfig.customBaseUrl
                                        )
                                        val result = aiClient.testConnection(testConfig)
                                        isTestingConnection = false
                                        connectionStatus = result.fold(
                                            onSuccess = { true to it },
                                            onFailure = { false to (it.message ?: "Text API test failed") }
                                        )
                                    }
                                },
                                enabled = !isTestingConnection && !isTestingVision && apiKeyInput.isNotBlank(),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("ai_test_connection_button")
                            ) {
                                if (isTestingConnection) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Testing…")
                                } else {
                                    Icon(Icons.Default.WifiTethering, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Test Text")
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    if (isTestingConnection || isTestingVision) return@OutlinedButton
                                    isTestingVision = true
                                    connectionStatus = null
                                    val testConfig = config.copy(
                                        apiKey = apiKeyInput.trim(),
                                        modelId = modelInput.trim().ifBlank { config.modelId },
                                        customBaseUrl = baseUrlInput.trim().trimEnd('/')
                                    )
                                    scope.launch {
                                        prefs.setAiConfiguration(
                                            testConfig.apiKey,
                                            testConfig.modelId,
                                            testConfig.customBaseUrl
                                        )
                                        val result = aiClient.testVisionConnection(testConfig)
                                        isTestingVision = false
                                        connectionStatus = result.fold(
                                            onSuccess = { true to it },
                                            onFailure = { false to (it.message ?: "Vision API test failed") }
                                        )
                                    }
                                },
                                enabled = !isTestingConnection && !isTestingVision && apiKeyInput.isNotBlank(),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("ai_test_vision_button")
                            ) {
                                if (isTestingVision) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Testing…")
                                } else {
                                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Test Vision")
                                }
                            }
                        }

                        Text(
                            text = "Test Text checks API access. Test Vision sends a small sample image to verify image input. Your key is stored locally on this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    connectionStatus?.let { (ok, message) ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (ok) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                            border = BorderStroke(
                                1.dp,
                                if (ok) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_connection_status_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // 4. Smart Organization & Prompt Preferences Card
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackground),
                border = cardBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Smart Organization",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    AiPreferenceToggleRow(
                        title = "Auto-apply AI tags",
                        subtitle = "Save AI-generated tags directly to searchable gallery tags",
                        checked = config.autoApplyTags,
                        onCheckedChange = { scope.launch { prefs.setAiAutoApplyTags(it) } },
                        testTag = "ai_auto_apply_tags_switch"
                    )

                    AiPreferenceToggleRow(
                        title = "Auto-organize smart collections",
                        subtitle = "Assign analyzed photos into matching gallery albums automatically",
                        checked = config.autoOrganizeCollections,
                        onCheckedChange = { scope.launch { prefs.setAiAutoOrganizeCollections(it) } },
                        testTag = "ai_auto_organize_collections_switch"
                    )

                    AiPreferenceToggleRow(
                        title = "Include on-device OCR text",
                        subtitle = "Share cached OCR text to improve receipt, code, and screenshot understanding",
                        checked = config.includeOcrContext,
                        onCheckedChange = { scope.launch { prefs.setAiIncludeOcrContext(it) } },
                        testTag = "ai_include_ocr_switch"
                    )

                    OutlinedTextField(
                        value = promptInput,
                        onValueChange = { promptInput = it },
                        label = { Text("Custom AI instructions (optional)") },
                        placeholder = { Text("e.g., Prefer concise English tags and group work receipts under Expenses") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_custom_prompt_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { showClearCacheConfirm = true },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("ai_clear_cache_button")
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Clear AI Cache")
                        }

                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    prefs.setAiCustomPromptInstructions(promptInput)
                                    prefs.setAiModelId(modelInput)
                                    prefs.setAiCustomBaseUrl(baseUrlInput)
                                    actionFeedback = "Prompt & organization preferences saved"
                                }
                            },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("ai_save_instructions_button")
                        ) {
                            Text("Save Preferences")
                        }
                    }

                    actionFeedback?.let { feedback ->
                        Text(
                            text = feedback,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.navigationBarsPadding())
            Spacer(Modifier.height(16.dp))
        }

        // Floating Top Back Bar
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onBack,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("ai_settings_back_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 4.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                Text(
                    text = "AI & Custom API Key",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }

    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text("Clear AI metadata cache?") },
            text = { Text("This removes all AI titles, descriptions, and collection suggestions. Your saved gallery albums and API key will remain intact.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearCacheConfirm = false
                        scope.launch {
                            withContext(Dispatchers.IO) { aiStore.clearAll() }
                            analyzedCount = 0
                            actionFeedback = "AI metadata cache cleared"
                        }
                    }
                ) {
                    Text("Clear Cache")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AiPreferenceToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
