package com.amresalehin.emregallery.ui

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amresalehin.emregallery.ai.AiMetadataStore
import com.amresalehin.emregallery.ai.CapabilityCheckResult
import com.amresalehin.emregallery.ai.CustomAiClient
import com.amresalehin.emregallery.ai.EndpointCapabilityReport
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.data.preferences.AiConfig
import com.amresalehin.emregallery.data.preferences.AiProviderProfile
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
    val providers by prefs.aiProviders.collectAsStateWithLifecycle(initialValue = emptyList())
    var baseUrlInput by remember(config.providerId, config.baseUrl) { mutableStateOf(config.baseUrl) }
    var apiKeyInput by remember(config.providerId, config.apiKey) { mutableStateOf(config.apiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var modelInput by remember(config.providerId, config.modelId) { mutableStateOf(config.modelId) }
    var promptInput by remember(config.customPromptInstructions) { mutableStateOf(config.customPromptInstructions) }

    var isTestingCapabilities by remember { mutableStateOf(false) }
    var capabilityReport by remember { mutableStateOf<EndpointCapabilityReport?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var testedEndpointSignature by remember { mutableStateOf<String?>(null) }
    var isSavingConfig by remember { mutableStateOf(false) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var showAddProviderDialog by remember { mutableStateOf(false) }
    var showRenameProviderDialog by remember { mutableStateOf(false) }
    var showDeleteProviderDialog by remember { mutableStateOf(false) }
    var providerNameInput by remember { mutableStateOf("") }

    val lightAppearance = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val pageBackground = if (lightAppearance) Color(0xFFF7F7F7) else MaterialTheme.colorScheme.background
    val cardBackground = if (lightAppearance) Color.White else MaterialTheme.colorScheme.surfaceContainer
    val cardBorder = BorderStroke(
        1.dp,
        if (lightAppearance) Color(0xFFE5E7EB) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    )
    val activePillBg = if (lightAppearance) Color(0xFF161A20) else MaterialTheme.colorScheme.primary
    val activePillText = if (lightAppearance) Color.White else MaterialTheme.colorScheme.onPrimary

    val endpointSignature = "${config.providerId}|${baseUrlInput.trim().trimEnd('/')}| ${apiKeyInput.trim()} | ${modelInput.trim()}"
    val endpointTestPassed = testedEndpointSignature == endpointSignature
    val canTest = !isTestingCapabilities && !isSavingConfig &&
        baseUrlInput.isNotBlank() && apiKeyInput.isNotBlank() && modelInput.isNotBlank()
    val actionLabel = when {
        isTestingCapabilities -> "Testing…"
        isSavingConfig -> "Saving…"
        actionFeedback == "Saved" -> "Saved"
        endpointTestPassed -> "Save"
        else -> "Test"
    }
    val actionEnabled = when {
        isTestingCapabilities || isSavingConfig -> false
        actionFeedback == "Saved" -> false
        endpointTestPassed -> true
        else -> canTest
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.statusBarsPadding())
            Spacer(Modifier.height(56.dp))

            // Unified Custom API Configuration Card
            Card(
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackground),
                border = cardBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_unified_config_card")
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Named provider profiles: each profile stores its own endpoint, key and model.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { providerMenuExpanded = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai_provider_selector"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = config.providerName,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(Icons.Default.ExpandMore, contentDescription = "Choose provider")
                            }
                            DropdownMenu(
                                expanded = providerMenuExpanded,
                                onDismissRequest = { providerMenuExpanded = false }
                            ) {
                                providers.forEach { provider ->
                                    DropdownMenuItem(
                                        text = { Text(provider.name) },
                                        leadingIcon = {
                                            if (provider.id == config.providerId) {
                                                Icon(Icons.Default.Check, contentDescription = "Selected")
                                            }
                                        },
                                        onClick = {
                                            providerMenuExpanded = false
                                            capabilityReport = null
                                            testError = null
                                            actionFeedback = null
                                            testedEndpointSignature = null
                                            scope.launch { prefs.selectAiProvider(provider.id) }
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = {
                                providerNameInput = config.providerName
                                showRenameProviderDialog = true
                            },
                            enabled = providers.any { it.id == config.providerId },
                            modifier = Modifier.testTag("ai_rename_provider_button")
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename provider")
                        }
                        IconButton(
                            onClick = { showAddProviderDialog = true },
                            modifier = Modifier.testTag("ai_add_provider_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add provider")
                        }
                        IconButton(
                            onClick = { showDeleteProviderDialog = true },
                            enabled = providers.size > 1 && providers.any { it.id == config.providerId },
                            modifier = Modifier.testTag("ai_delete_provider_button")
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Remove provider")
                        }
                    }

                    // Endpoint Fields
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = baseUrlInput,
                            onValueChange = {
                                baseUrlInput = it
                                capabilityReport = null
                                testError = null
                                actionFeedback = null
                            },
                            label = { Text("Base URL") },
                            placeholder = { Text("https://api.openai.com/v1") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_base_url_input")
                        )

                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = {
                                apiKeyInput = it
                                capabilityReport = null
                                testError = null
                                actionFeedback = null
                            },
                            label = { Text("API Key") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(
                                    onClick = { showApiKey = !showApiKey },
                                    modifier = Modifier.testTag("ai_api_key_visibility_toggle")
                                ) {
                                    Icon(
                                        imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showApiKey) "Hide key" else "Show key"
                                    )
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
                                capabilityReport = null
                                testError = null
                                actionFeedback = null
                            },
                            label = { Text("Model") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_model_id_input")
                        )

                        // One full-width action advances from Test to Save after a successful test.
                        Button(
                            onClick = {
                                val cleanUrl = baseUrlInput.trim().trimEnd('/')
                                val cleanKey = apiKeyInput.trim()
                                val cleanModel = modelInput.trim()
                                val cleanPrompt = promptInput.trim()
                                if (endpointTestPassed) {
                                    isSavingConfig = true
                                    scope.launch {
                                        prefs.saveAiEndpointConfig(cleanUrl, cleanKey, cleanModel)
                                        prefs.setAiCustomPromptInstructions(cleanPrompt)
                                        isSavingConfig = false
                                        actionFeedback = "Saved"
                                    }
                                } else {
                                    isTestingCapabilities = true
                                    capabilityReport = null
                                    testError = null
                                    actionFeedback = null
                                    val testConfig = config.copy(
                                        baseUrl = cleanUrl,
                                        apiKey = cleanKey,
                                        modelId = cleanModel
                                    )
                                    scope.launch {
                                        val result = aiClient.testEndpointCapabilities(testConfig)
                                        isTestingCapabilities = false
                                        result.onSuccess { report ->
                                            capabilityReport = report
                                            testedEndpointSignature = endpointSignature
                                        }.onFailure { err ->
                                            testError = err.message ?: "Endpoint test failed"
                                        }
                                    }
                                }
                            },
                            enabled = actionEnabled,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("ai_primary_config_action_button")
                        ) {
                            Text(
                                text = actionLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Live Text & Vision Capability Test Report
                        capabilityReport?.let { report ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(
                                    1.dp,
                                    if (report.fullyReady) {
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                    } else {
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai_connection_status_banner")
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = report.protocolLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    CapabilityResultRow(
                                        label = "Text",
                                        result = report.text,
                                        testTag = "ai_test_text_status"
                                    )
                                    CapabilityResultRow(
                                        label = "Vision",
                                        result = report.vision,
                                        testTag = "ai_test_vision_status"
                                    )
                                }
                            }
                        }

                        testError?.let { errMsg ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai_connection_status_banner")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = errMsg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )

                    // Minimal Behavior Toggles & Utilities inside the same unified card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AiMinimalToggleRow(
                            title = "Auto-tag",
                            checked = config.autoApplyTags,
                            onCheckedChange = { scope.launch { prefs.setAiAutoApplyTags(it) } },
                            testTag = "ai_auto_apply_tags_switch"
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

                        AiMinimalToggleRow(
                            title = "Auto-albums",
                            checked = config.autoOrganizeCollections,
                            onCheckedChange = { scope.launch { prefs.setAiAutoOrganizeCollections(it) } },
                            testTag = "ai_auto_organize_collections_switch"
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

                        AiMinimalToggleRow(
                            title = "OCR context",
                            checked = config.includeOcrContext,
                            onCheckedChange = { scope.launch { prefs.setAiIncludeOcrContext(it) } },
                            testTag = "ai_include_ocr_switch"
                        )

                        Spacer(Modifier.height(4.dp))

                        OutlinedTextField(
                            value = promptInput,
                            onValueChange = {
                                promptInput = it
                                actionFeedback = null
                            },
                            label = { Text("Custom prompt") },
                            minLines = 2,
                            maxLines = 4,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_custom_prompt_input")
                        )

                        Spacer(Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AiActionChip(
                                label = "Clear cache",
                                onClick = { showClearCacheConfirm = true },
                                testTag = "ai_clear_cache_button"
                            )

                            Surface(
                                onClick = onOpenBatchIndexing,
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .height(38.dp)
                                    .testTag("ai_settings_open_indexing_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Bolt,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "Batch Index",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        actionFeedback?.let { feedback ->
                            Text(
                                text = feedback,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.navigationBarsPadding())
            Spacer(Modifier.height(16.dp))
        }

        // Back navigation only; the page title/header is intentionally removed.
        Surface(
            onClick = onBack,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 8.dp)
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
    }

    if (showAddProviderDialog) {
        AlertDialog(
            onDismissRequest = { showAddProviderDialog = false },
            title = { Text("Add provider") },
            text = {
                OutlinedTextField(
                    value = providerNameInput,
                    onValueChange = { providerNameInput = it },
                    label = { Text("Provider name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = providerNameInput.isNotBlank(),
                    onClick = {
                        val name = providerNameInput.trim()
                        showAddProviderDialog = false
                        providerNameInput = ""
                        scope.launch {
                            prefs.addAiProvider(name)
                            capabilityReport = null
                            testError = null
                            actionFeedback = null
                            testedEndpointSignature = null
                        }
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddProviderDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showRenameProviderDialog) {
        AlertDialog(
            onDismissRequest = { showRenameProviderDialog = false },
            title = { Text("Rename provider") },
            text = {
                OutlinedTextField(
                    value = providerNameInput,
                    onValueChange = { providerNameInput = it },
                    label = { Text("Provider name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = providerNameInput.isNotBlank(),
                    onClick = {
                        val name = providerNameInput.trim()
                        showRenameProviderDialog = false
                        scope.launch { prefs.renameAiProvider(config.providerId, name) }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameProviderDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteProviderDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteProviderDialog = false },
            title = { Text("Remove provider?") },
            text = { Text("Remove ${config.providerName} and its saved API key? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteProviderDialog = false
                        scope.launch {
                            prefs.deleteAiProvider(config.providerId)
                            capabilityReport = null
                            testError = null
                            actionFeedback = null
                            testedEndpointSignature = null
                        }
                    }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteProviderDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text("Clear AI cache?") },
            text = { Text("Remove cached AI metadata.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearCacheConfirm = false
                        scope.launch {
                            withContext(Dispatchers.IO) { aiStore.clearAll() }
                            actionFeedback = "Cache cleared"
                        }
                    }
                ) {
                    Text("Clear")
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
private fun CapabilityResultRow(
    label: String,
    result: CapabilityCheckResult,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (result.supported) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = if (result.supported) "$label supported" else "$label failed",
            tint = if (result.supported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(min = 48.dp)
        )
        Text(
            text = result.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AiActionChip(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    testTag: String
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier
            .height(38.dp)
            .testTag(testTag)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 14.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun AiMinimalToggleRow(
    title: String,
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
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
