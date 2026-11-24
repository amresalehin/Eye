package com.amresalehin.emregallery.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amresalehin.emregallery.IndexingForegroundService
import com.amresalehin.emregallery.ai.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    onOpenIndexingStatus: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(AiSettings.load(context)) }
    var showSecret by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var clipInstalled by remember { mutableStateOf(ClipOnnxModelManager.isInstalled(context)) }
    var mediaPipeSummary by remember { mutableStateOf(LocalVlmModelManager.installedModelSummary(context)) }
    var ggufSummary by remember { mutableStateOf(LocalVlmModelManager.installedGgufSummary(context)) }
    var importing by remember { mutableStateOf(false) }

    val pickMediaPipeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            statusIsError = false
            statusText = "Importing…"
            scope.launch {
                val res = LocalVlmModelManager.importModelFromUri(context, uri)
                importing = false
                res.onSuccess {
                    config = AiSettings.load(context)
                    mediaPipeSummary = LocalVlmModelManager.installedModelSummary(context)
                    statusText = "Imported"
                }.onFailure { err ->
                    statusIsError = true
                    statusText = err.localizedMessage ?: "Import failed"
                }
            }
        }
    }

    val pickGgufModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            statusIsError = false
            statusText = "Importing…"
            scope.launch {
                val res = LocalVlmModelManager.importGgufFromUri(context, uri, isMmproj = false)
                importing = false
                res.onSuccess {
                    config = AiSettings.load(context)
                    ggufSummary = LocalVlmModelManager.installedGgufSummary(context)
                    statusText = "Imported"
                }.onFailure { err ->
                    statusIsError = true
                    statusText = err.localizedMessage ?: "Import failed"
                }
            }
        }
    }

    val pickGgufMmprojLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            statusIsError = false
            statusText = "Importing…"
            scope.launch {
                val res = LocalVlmModelManager.importGgufFromUri(context, uri, isMmproj = true)
                importing = false
                res.onSuccess {
                    config = AiSettings.load(context)
                    ggufSummary = LocalVlmModelManager.installedGgufSummary(context)
                    statusText = "Imported"
                }.onFailure { err ->
                    statusIsError = true
                    statusText = err.localizedMessage ?: "Import failed"
                }
            }
        }
    }

    val lightAppearance = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val pageBackground = if (lightAppearance) Color(0xFFF7F7F7) else MaterialTheme.colorScheme.background
    val cardBackground = if (lightAppearance) Color.White else MaterialTheme.colorScheme.surfaceContainer
    val cardBorder = BorderStroke(
        1.dp,
        if (lightAppearance) Color(0xFFE5E7EB) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    )

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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.statusBarsPadding())
            Spacer(Modifier.height(54.dp))
            Text(
                text = "Configure Cloud or Local Models",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )

            // 1. CLIP Card
            Card(
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackground),
                border = cardBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "CLIP",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Text(
                            text = if (clipInstalled) "ViT-B/32 · Ready" else "ViT-B/32 · 154 MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    MinimalPillButton(
                        text = if (clipInstalled) "Remove" else "Download",
                        onClick = {
                            if (clipInstalled) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { ClipOnnxModelManager.deleteModels(context) }
                                    clipInstalled = false
                                }
                            } else {
                                IndexingForegroundService.downloadAiModel(context, force = false)
                                onOpenIndexingStatus()
                            }
                        }
                    )
                }
            }

            // 2. VLM Card
            Card(
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackground),
                border = cardBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Vision model",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )

                    MinimalSegmentedBar(
                        options = listOf(
                            AiProviderType.ON_DEVICE_VLM to "Local",
                            AiProviderType.OLLAMA to "Cloud / BYOK"
                        ),
                        selected = if (config.provider == AiProviderType.ON_DEVICE_VLM) {
                            AiProviderType.ON_DEVICE_VLM
                        } else {
                            AiProviderType.OLLAMA
                        },
                        onSelect = { selected ->
                            val nextProvider = if (selected == AiProviderType.ON_DEVICE_VLM) {
                                AiProviderType.ON_DEVICE_VLM
                            } else {
                                config.provider.takeIf { it != AiProviderType.ON_DEVICE_VLM } ?: AiProviderType.OLLAMA
                            }
                            val switchingToCloud = config.provider == AiProviderType.ON_DEVICE_VLM &&
                                nextProvider != AiProviderType.ON_DEVICE_VLM
                            config = config.copy(
                                provider = nextProvider,
                                baseUrl = if (switchingToCloud) AiProviderType.OLLAMA.defaultBaseUrl else config.baseUrl,
                                modelName = if (switchingToCloud) AiProviderType.OLLAMA.defaultModel else config.modelName
                            )
                            AiSettings.save(context, config)
                            statusText = null
                        }
                    )

                    if (config.provider == AiProviderType.ON_DEVICE_VLM) {
                        MinimalSegmentedBar(
                            options = listOf(
                                LocalVlmRegistry.ENGINE_LLAMACPP to "llama.cpp",
                                LocalVlmRegistry.ENGINE_MEDIAPIPE to "MediaPipe"
                            ),
                            selected = config.localVlmEngineId,
                            onSelect = { engineId ->
                                config = config.copy(localVlmEngineId = engineId)
                                AiSettings.save(context, config)
                                statusText = null
                            }
                        )

                        if (config.localVlmEngineId == LocalVlmRegistry.ENGINE_LLAMACPP) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = ggufSummary ?: "No GGUF + mmproj",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (LlamaCppNativeBridge.isNativeLibraryLoaded) "Native OK" else "No .so",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (LlamaCppNativeBridge.isNativeLibraryLoaded) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MinimalPillButton(
                                    text = "Model .gguf",
                                    enabled = !importing,
                                    onClick = { pickGgufModelLauncher.launch(arrayOf("*/*")) },
                                    modifier = Modifier.weight(1f)
                                )
                                MinimalPillButton(
                                    text = "mmproj .gguf",
                                    enabled = !importing,
                                    onClick = { pickGgufMmprojLauncher.launch(arrayOf("*/*")) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            OutlinedTextField(
                                value = config.ggufModelDownloadUrl,
                                onValueChange = { config = config.copy(ggufModelDownloadUrl = it) },
                                label = { Text("Model URL") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = config.ggufMmprojDownloadUrl,
                                onValueChange = { config = config.copy(ggufMmprojDownloadUrl = it) },
                                label = { Text("mmproj URL") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = config.hfToken,
                                onValueChange = { config = config.copy(hfToken = it) },
                                label = { Text("HF Token") },
                                singleLine = true,
                                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showSecret = !showSecret }) {
                                        Icon(
                                            if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MinimalPillButton(
                                    text = "Download",
                                    enabled = config.ggufModelDownloadUrl.isNotBlank() &&
                                        config.ggufMmprojDownloadUrl.isNotBlank() && !importing,
                                    onClick = {
                                        AiSettings.save(context, config)
                                        IndexingForegroundService.downloadGgufVlmPair(context)
                                        onOpenIndexingStatus()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                if (ggufSummary != null) {
                                    MinimalPillButton(
                                        text = "Remove",
                                        enabled = !importing,
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) { LocalVlmModelManager.deleteGgufModels(context) }
                                                ggufSummary = null
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                MinimalPillButton(
                                    text = "Save",
                                    emphasized = true,
                                    onClick = {
                                        AiSettings.save(context, config)
                                        statusIsError = false
                                        statusText = "Saved"
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = mediaPipeSummary ?: "No .task model",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("GPU", style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.width(6.dp))
                                Switch(
                                    checked = config.vlmUseGpu,
                                    onCheckedChange = {
                                        config = config.copy(vlmUseGpu = it)
                                        AiSettings.save(context, config)
                                    }
                                )
                            }

                            OutlinedTextField(
                                value = config.localVlmDownloadUrl,
                                onValueChange = { config = config.copy(localVlmDownloadUrl = it) },
                                label = { Text(".task URL") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MinimalPillButton(
                                    text = "Import .task",
                                    enabled = !importing,
                                    onClick = { pickMediaPipeLauncher.launch(arrayOf("*/*")) },
                                    modifier = Modifier.weight(1f)
                                )
                                MinimalPillButton(
                                    text = "Download",
                                    enabled = config.localVlmDownloadUrl.isNotBlank() && !importing,
                                    onClick = {
                                        AiSettings.save(context, config)
                                        IndexingForegroundService.downloadVlmModel(context)
                                        onOpenIndexingStatus()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                if (mediaPipeSummary != null) {
                                    MinimalPillButton(
                                        text = "Remove",
                                        enabled = !importing,
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) { LocalVlmModelManager.deleteInstalledModels(context) }
                                                mediaPipeSummary = null
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    } else {
                        MinimalSegmentedBar(
                            options = listOf(
                                AiProviderType.OLLAMA to "Ollama",
                                AiProviderType.GEMINI to "Gemini",
                                AiProviderType.OPENAI_COMPATIBLE to "OpenAI / Custom"
                            ),
                            selected = config.provider,
                            onSelect = { provider ->
                                config = config.copy(
                                    provider = provider,
                                    baseUrl = provider.defaultBaseUrl,
                                    modelName = provider.defaultModel
                                )
                                AiSettings.save(context, config)
                                statusText = null
                            }
                        )
                        OutlinedTextField(
                            value = config.baseUrl,
                            onValueChange = { config = config.copy(baseUrl = it) },
                            label = { Text("Base URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = config.modelName,
                            onValueChange = { config = config.copy(modelName = it) },
                            label = { Text("Model") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = config.apiKey,
                            onValueChange = { config = config.copy(apiKey = it) },
                            label = { Text("API Key") },
                            singleLine = true,
                            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showSecret = !showSecret }) {
                                    Icon(
                                        if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MinimalPillButton(
                                text = if (testing) "Testing…" else "Test connection",
                                enabled = !testing && config.baseUrl.isNotBlank() && config.modelName.isNotBlank() &&
                                    (!config.provider.requiresApiKey || config.apiKey.isNotBlank()),
                                onClick = {
                                    testing = true
                                    statusText = null
                                    scope.launch {
                                        val res = VisionApiClient.testConnection(config)
                                        testing = false
                                        res.onSuccess {
                                            statusIsError = false
                                            statusText = "Connected"
                                        }.onFailure { err ->
                                            statusIsError = true
                                            statusText = err.localizedMessage ?: "Failed"
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            MinimalPillButton(
                                text = "Save",
                                emphasized = true,
                                enabled = config.baseUrl.isNotBlank() && config.modelName.isNotBlank() &&
                                    (!config.provider.requiresApiKey || config.apiKey.isNotBlank()),
                                onClick = {
                                    AiSettings.save(context, config)
                                    statusIsError = false
                                    statusText = "Saved"
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (!statusText.isNullOrBlank()) {
                        Text(
                            text = statusText.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 3. Compact Toggle Card (No explanatory text)
            Card(
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackground),
                border = cardBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "OCR context",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = config.includeOcrContext,
                            onCheckedChange = {
                                config = config.copy(includeOcrContext = it)
                                AiSettings.save(context, config)
                            }
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Vault local only",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = config.excludeLockedVaultFromCloud,
                            onCheckedChange = {
                                config = config.copy(excludeLockedVaultFromCloud = it)
                                AiSettings.save(context, config)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.navigationBarsPadding())
            Spacer(Modifier.height(14.dp))
        }

        // Minimal Floating Top Bar matching IndexingStatusScreen
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
                color = cardBackground,
                border = cardBorder,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "AI Setup",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
    }
}

@Composable
private fun <T> MinimalSegmentedBar(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEachIndexed { idx, (value, label) ->
                val active = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.surface else Color.Transparent
                        )
                        .clickable { onSelect(value) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (idx < options.lastIndex) {
                    VerticalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxHeight(0.55f).width(1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MinimalPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = if (emphasized) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            1.dp,
            if (emphasized) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = modifier.height(38.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
