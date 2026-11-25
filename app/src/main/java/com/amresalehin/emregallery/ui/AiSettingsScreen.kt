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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amresalehin.emregallery.ai.CapabilityCheckResult
import com.amresalehin.emregallery.ai.CustomAiClient
import com.amresalehin.emregallery.ai.EndpointCapabilityReport
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.data.preferences.AiConfig
import kotlinx.coroutines.launch

@Composable
fun AiSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesRepository(context.applicationContext) }
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
    var availableModels by remember(config.providerId, config.baseUrl, config.apiKey) { mutableStateOf(emptyList<String>()) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var isModelMenuExpanded by remember { mutableStateOf(false) }
    var modelFetchError by remember { mutableStateOf<String?>(null) }
    var promptInput by remember(config.customPromptInstructions) { mutableStateOf(config.customPromptInstructions) }
    var requestDelayMs by remember(config.batchDelayMs) { mutableFloatStateOf(config.batchDelayMs.toFloat()) }

    var isTestingCapabilities by remember { mutableStateOf(false) }
    var capabilityReport by remember { mutableStateOf<EndpointCapabilityReport?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var testedEndpointSignature by remember { mutableStateOf<String?>(null) }
    var isSavingConfig by remember { mutableStateOf(false) }
    var providerToDelete by remember { mutableStateOf<Pair<String, String>?>(null) }
    val hasConfiguredProviders = providers.any { it.isConfigured }
    var isCreatingProvider by remember(hasConfiguredProviders) { mutableStateOf(!hasConfiguredProviders) }
    var isAiSetupExpanded by remember(providers.isEmpty()) { mutableStateOf(providers.none { it.isConfigured }) }

    val lightAppearance = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val pageBackground = if (lightAppearance) Color(0xFFF7F7F7) else MaterialTheme.colorScheme.background
    val cardBackground = if (lightAppearance) Color.White else MaterialTheme.colorScheme.surfaceContainer
    val cardBorder = BorderStroke(
        1.dp,
        if (lightAppearance) Color(0xFFE5E7EB) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    )
    val endpointSignature = "${if (isCreatingProvider) "__new_provider__" else config.providerId}|${baseUrlInput.trim().trimEnd('/')}| ${apiKeyInput.trim()} | ${modelInput.trim()}"
    val endpointTestPassed = testedEndpointSignature == endpointSignature
    val canTest = !isTestingCapabilities && !isSavingConfig &&
        providerNameInput.isNotBlank() && baseUrlInput.isNotBlank() && apiKeyInput.isNotBlank() && modelInput.isNotBlank()
    val testActionLabel = when {
        isTestingCapabilities -> "Testing…"
        testError != null || capabilityReport?.fullyReady == false -> "Retry test"
        endpointTestPassed -> "Tested"
        else -> "Test"
    }
    val saveActionLabel = when {
        isSavingConfig -> "Saving…"
        actionFeedback == "Saved" -> "Saved"
        saveError != null -> "Retry save"
        else -> "Save"
    }
    val testButtonEnabled = canTest && actionFeedback != "Saved"
    val saveButtonEnabled = endpointTestPassed && !isTestingCapabilities &&
        !isSavingConfig && actionFeedback != "Saved"

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
                    if (configuredProviders.isNotEmpty()) {
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
                                        isCreatingProvider = false
                                        scope.launch {
                                            prefs.selectAiProvider(provider.id)
                                            capabilityReport = null
                                            testError = null
                                            actionFeedback = null
                                            saveError = null
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
                                            isCreatingProvider = false
                                            providerNameInput = provider.name
                                            baseUrlInput = provider.baseUrl
                                            apiKeyInput = provider.apiKey
                                            modelInput = provider.modelId
                                            availableModels = emptyList()
                                            modelFetchError = null
                                            isAiSetupExpanded = true
                                            scope.launch {
                                                prefs.selectAiProvider(provider.id)
                                                capabilityReport = null
                                                testError = null
                                                actionFeedback = null
                                                saveError = null
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
                if (!isAiSetupExpanded) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = {
                                isCreatingProvider = true
                                providerNameInput = ""
                                baseUrlInput = ""
                                apiKeyInput = ""
                                modelInput = ""
                                availableModels = emptyList()
                                modelFetchError = null
                                capabilityReport = null
                                testError = null
                                actionFeedback = null
                                saveError = null
                                testedEndpointSignature = null
                                isAiSetupExpanded = true
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("ai_setup_expand_bar")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add provider",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                    if (isAiSetupExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                        ) {
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
                                    saveError = null
                                },
                                label = { Text("Name") },
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
                                    saveError = null
                                },
                                label = { Text("Base URL") },
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
                                    saveError = null
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
    
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = modelInput.takeIf { availableModels.isNotEmpty() }.orEmpty(),
                                    onValueChange = {},
                                    readOnly = true,
                                    singleLine = true,
                                    label = { Text("Model") },
                                    trailingIcon = {
                                        when {
                                            isFetchingModels -> CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                            else -> Icon(
                                                imageVector = if (availableModels.isEmpty()) Icons.Default.Search else Icons.Default.ArrowDropDown,
                                                contentDescription = if (availableModels.isEmpty()) "Fetch models" else "Select model"
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            enabled = !isFetchingModels && (
                                                availableModels.isNotEmpty() ||
                                                    (baseUrlInput.isNotBlank() && apiKeyInput.isNotBlank())
                                                )
                                        ) {
                                            if (availableModels.isEmpty()) {
                                                isFetchingModels = true
                                                modelFetchError = null
                                                scope.launch {
                                                    val fetchConfig = config.copy(
                                                        baseUrl = baseUrlInput.trim().trimEnd('/'),
                                                        apiKey = apiKeyInput.trim()
                                                    )
                                                    try {
                                                        aiClient.fetchAvailableModels(fetchConfig)
                                                            .onSuccess { models ->
                                                                availableModels = models
                                                                if (modelInput !in models) modelInput = ""
                                                                modelFetchError = null
                                                                isModelMenuExpanded = models.isNotEmpty()
                                                            }
                                                            .onFailure { error ->
                                                                modelFetchError = error.message ?: "Could not fetch models"
                                                            }
                                                    } finally {
                                                        isFetchingModels = false
                                                    }
                                                }
                                            } else {
                                                isModelMenuExpanded = true
                                            }
                                        }
                                        .testTag("ai_model_id_input")
                                )
                                DropdownMenu(
                                    expanded = isModelMenuExpanded,
                                    onDismissRequest = { isModelMenuExpanded = false },
                                    modifier = Modifier.heightIn(max = 320.dp)
                                ) {
                                    availableModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            onClick = {
                                                modelInput = model
                                                isModelMenuExpanded = false
                                                capabilityReport = null
                                                testError = null
                                                actionFeedback = null
                                                saveError = null
                                                testedEndpointSignature = null
                                            }
                                        )
                                    }
                                }
                            }
                                modelFetchError?.let { message ->
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
    
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        isTestingCapabilities = true
                                        capabilityReport = null
                                        testError = null
                                        saveError = null
                                        actionFeedback = null
                                        val testConfig = config.copy(
                                            baseUrl = baseUrlInput.trim().trimEnd('/'),
                                            apiKey = apiKeyInput.trim(),
                                            modelId = modelInput.trim()
                                        )
                                        scope.launch {
                                            try {
                                                aiClient.testEndpointCapabilities(testConfig)
                                                    .onSuccess { report ->
                                                        capabilityReport = report
                                                        testedEndpointSignature = if (report.fullyReady) {
                                                            endpointSignature
                                                        } else {
                                                            null
                                                        }
                                                    }
                                                    .onFailure { err ->
                                                        testError = err.message ?: "Endpoint test failed"
                                                    }
                                            } finally {
                                                isTestingCapabilities = false
                                            }
                                        }
                                    },
                                    enabled = testButtonEnabled,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp)
                                        .testTag("ai_primary_config_action_button")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        val testStatusIcon = when {
                                            endpointTestPassed && capabilityReport?.fullyReady == true ->
                                                Icons.Default.CheckCircle
                                            testError != null || capabilityReport?.fullyReady == false ->
                                                Icons.Default.ErrorOutline
                                            else -> null
                                        }
                                        testStatusIcon?.let { icon ->
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(
                                            text = testActionLabel,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        isSavingConfig = true
                                        saveError = null
                                        scope.launch {
                                            try {
                                                runCatching {
                                                    prefs.saveAiProviderConfig(
                                                        providerId = if (isCreatingProvider) "__new_provider__" else config.providerId,
                                                        name = providerNameInput,
                                                        baseUrl = baseUrlInput.trim().trimEnd('/'),
                                                        apiKey = apiKeyInput.trim(),
                                                        modelId = modelInput.trim()
                                                    )
                                                    prefs.setAiCustomPromptInstructions(promptInput.trim())
                                                }.onSuccess {
                                                    isCreatingProvider = false
                                                    actionFeedback = "Saved"
                                                    testError = null
                                                }.onFailure { error ->
                                                    actionFeedback = null
                                                    saveError = error.message ?: "Could not save AI provider"
                                                }
                                            } finally {
                                                isSavingConfig = false
                                            }
                                        }
                                    },
                                    enabled = saveButtonEnabled,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp)
                                        .testTag("ai_save_config_action_button")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        if (actionFeedback == "Saved") {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        } else if (saveError != null) {
                                            Icon(
                                                imageVector = Icons.Default.ErrorOutline,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(
                                            text = saveActionLabel,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            // Capability details remain visible only when a capability check fails.
                            capabilityReport?.takeUnless { it.fullyReady }?.let { report ->
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
    
                            (testError ?: saveError)?.let { errMsg ->
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
    
                    }
                }

            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackground),
                border = cardBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_request_delay_card")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Request delay",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        val selectedDelayMs = requestDelayMs.toLong()
                        Text(
                            text = if (selectedDelayMs == 0L) "Off" else "${selectedDelayMs / 1000.0} s",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("ai_request_delay_value")
                        )
                    }
                    Slider(
                        value = requestDelayMs,
                        onValueChange = { requestDelayMs = it },
                        valueRange = 0f..5000f,
                        steps = 19,
                        onValueChangeFinished = {
                            val savedDelay = (requestDelayMs / 250f).toLong() * 250L
                            requestDelayMs = savedDelay.toFloat()
                            scope.launch { prefs.setAiBatchDelayMs(savedDelay) }
                        },
                        modifier = Modifier.testTag("ai_request_delay_slider")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("No delay", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("5 s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        saveError = null
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
                            saveError = null
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
