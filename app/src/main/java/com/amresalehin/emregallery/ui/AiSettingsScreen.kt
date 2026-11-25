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
    var providerNameInput by remember(config.providerId, config.providerName) {
        mutableStateOf(config.providerName.takeUnless {
            it == "No provider selected" || it.equals("New provider", ignoreCase = true)
        }.orEmpty())
    }
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
    var providerToDelete by remember { mutableStateOf<Pair<String, String>?>(null) }
    var isAiSetupExpanded by remember { mutableStateOf(false) }

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
        providerNameInput.isNotBlank() && baseUrlInput.isNotBlank() && apiKeyInput.isNotBlank() && modelInput.isNotBlank()
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

            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackground),
                border = cardBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_configured_models_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Models",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )

                    }

                    val configuredProviders = providers.filter { it.isConfigured }
                    if (configuredProviders.isEmpty()) {
                        Text(
                            text = "No configured models yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        configuredProviders.forEach { provider ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (provider.id == config.providerId)
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            prefs.selectAiProvider(provider.id)
                                            capabilityReport = null
                                            testError = null
                                            actionFeedback = null
                                            testedEndpointSignature = null
                                        }
                                    }
                                    .testTag("ai_configured_model_${provider.id}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = provider.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${provider.modelId} · ${provider.baseUrl}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (provider.id == config.providerId) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Active model",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                prefs.selectAiProvider(provider.id)
                                                capabilityReport = null
                                                testError = null
                                                actionFeedback = null
                                                testedEndpointSignature = null
                                            }
                                        },
                                        modifier = Modifier.testTag("ai_edit_provider_${provider.id}")
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit ${provider.name}")
                                    }
                                    IconButton(
                                        onClick = { providerToDelete = provider.id to provider.name },
                                        enabled = providers.size > 1,
                                        modifier = Modifier.testTag("ai_delete_provider_${provider.id}")
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete ${provider.name}")
                                    }
                                }
                            }
                        }
                    }
                }
            }


            Card(
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackground),
                border = cardBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_unified_config_card")
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = { isAiSetupExpanded = !isAiSetupExpanded },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("ai_setup_expand_bar")
                    ) {
                        Icon(
                            imageVector = if (isAiSetupExpanded) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = if (isAiSetupExpanded) "Close provider form" else "Add provider",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                    if (isAiSetupExpanded) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                        // Provider name is a normal editable field, matching the endpoint fields.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = providerNameInput,
                                onValueChange = {
                                    providerNameInput = it
                                    actionFeedback = null
                                },
                                placeholder = { Text("Name") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai_provider_name_input")
                            )
    
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
                                            prefs.renameAiProvider(config.providerId, providerNameInput.trim())
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

            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackground),
                border = cardBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_custom_prompt_card")
            ) {
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
                        .padding(16.dp)
                        .testTag("ai_custom_prompt_input")
                )
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

    providerToDelete?.let { (providerId, providerName) ->
        AlertDialog(
            onDismissRequest = { providerToDelete = null },
            title = { Text("Delete model?") },
            text = { Text("Delete $providerName and its saved API key? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        providerToDelete = null
                        scope.launch {
                            prefs.deleteAiProvider(providerId)
                            capabilityReport = null
                            testError = null
                            actionFeedback = null
                            testedEndpointSignature = null
                        }
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { providerToDelete = null }) { Text("Cancel") }
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
