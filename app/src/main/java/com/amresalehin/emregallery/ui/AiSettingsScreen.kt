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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.amresalehin.emregallery.IndexingForegroundService
import com.amresalehin.emregallery.ai.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    onOpenIndexingStatus: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(AiSettings.load(context)) }
    var showApiKey by remember { mutableStateOf(false) }
    var testingConnection by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testIsError by remember { mutableStateOf(false) }
    var savedBanner by remember { mutableStateOf(false) }
    var clipInstalled by remember { mutableStateOf(ClipOnnxModelManager.isInstalled(context)) }
    var vlmModelSummary by remember { mutableStateOf(LocalVlmModelManager.installedModelSummary(context)) }
    var ggufSummary by remember { mutableStateOf(LocalVlmModelManager.installedGgufSummary(context)) }
    var importingVlm by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf<String?>(null) }

    val pickMediaPipeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importingVlm = true
            importStatus = "Importing MediaPipe .task model…"
            scope.launch {
                val res = LocalVlmModelManager.importModelFromUri(context, uri) { copied, total ->
                    val mb = copied / (1024 * 1024)
                    importStatus = if (total > 0L) {
                        "Importing (${((copied * 100L) / total).toInt().coerceIn(0, 100)}% · ${mb} MB)…"
                    } else "Importing (${mb} MB)…"
                }
                importingVlm = false
                res.onSuccess {
                    config = AiSettings.load(context)
                    vlmModelSummary = LocalVlmModelManager.installedModelSummary(context)
                    importStatus = "Installed: ${vlmModelSummary.orEmpty()}"
                }.onFailure { err ->
                    importStatus = err.localizedMessage ?: "Import failed"
                }
            }
        }
    }

    val pickGgufModelLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importingVlm = true
            importStatus = "Importing GGUF language model…"
            scope.launch {
                val res = LocalVlmModelManager.importGgufFromUri(context, uri, isMmproj = false) { copied, total ->
                    val mb = copied / (1024 * 1024)
                    importStatus = if (total > 0L) {
                        "Importing GGUF (${((copied * 100L) / total).toInt().coerceIn(0, 100)}% · ${mb} MB)…"
                    } else "Importing GGUF (${mb} MB)…"
                }
                importingVlm = false
                res.onSuccess {
                    config = AiSettings.load(context)
                    ggufSummary = LocalVlmModelManager.installedGgufSummary(context)
                    importStatus = "GGUF model imported."
                }.onFailure { err ->
                    importStatus = err.localizedMessage ?: "GGUF import failed"
                }
            }
        }
    }

    val pickGgufMmprojLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importingVlm = true
            importStatus = "Importing mmproj.gguf vision projector…"
            scope.launch {
                val res = LocalVlmModelManager.importGgufFromUri(context, uri, isMmproj = true) { copied, total ->
                    val mb = copied / (1024 * 1024)
                    importStatus = if (total > 0L) {
                        "Importing mmproj (${((copied * 100L) / total).toInt().coerceIn(0, 100)}% · ${mb} MB)…"
                    } else "Importing mmproj (${mb} MB)…"
                }
                importingVlm = false
                res.onSuccess {
                    config = AiSettings.load(context)
                    ggufSummary = LocalVlmModelManager.installedGgufSummary(context)
                    importStatus = "mmproj.gguf imported."
                }.onFailure { err ->
                    importStatus = err.localizedMessage ?: "mmproj import failed"
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.statusBarsPadding())
            Spacer(Modifier.height(56.dp))

            // SYSTEM 1: CLIP Semantic Search & Visual Embeddings
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardBackground,
                border = cardBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.ImageSearch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "System 1: CLIP Semantic Search (ONNX)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (clipInstalled) {
                                    "CLIP ViT-B/32 installed (~154 MB) · Powers fast background natural-language & visually similar search."
                                } else {
                                    "Download CLIP ViT-B/32 (~154 MB) for fast background semantic search and visual similarity."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (!clipInstalled) {
                            Button(
                                onClick = {
                                    IndexingForegroundService.downloadAiModel(context, force = false)
                                    onOpenIndexingStatus()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Download CLIP")
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { ClipOnnxModelManager.deleteModels(context) }
                                        clipInstalled = false
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Remove CLIP")
                            }
                        }
                        OutlinedButton(
                            onClick = onOpenIndexingStatus,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Indexing Status")
                        }
                    }
                }
            }

            // SYSTEM 2: Real Vision-Language Model (VLM)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardBackground,
                border = cardBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "System 2: Vision-Language Model (VLM)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Generates detailed descriptions, intelligent Karakeep/mymind tags, OCR-aware context, and answers questions about photos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    AiProviderType.entries.forEach { provider ->
                        val selected = config.provider == provider
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
                            },
                            border = if (selected) {
                                BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    config = config.copy(
                                        provider = provider,
                                        baseUrl = provider.defaultBaseUrl,
                                        modelName = provider.defaultModel
                                    )
                                    savedBanner = false
                                    testMessage = null
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        provider.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        when (provider) {
                                            AiProviderType.ON_DEVICE_VLM -> "100% offline on-phone VLM via llama.cpp (GGUF + mmproj) or MediaPipe (.task)."
                                            AiProviderType.OLLAMA -> "Self-hosted vision models on your PC/server (e.g. qwen2.5vl:3b, llava)."
                                            AiProviderType.GEMINI -> "Google Gemini OpenAI-compatible vision API."
                                            AiProviderType.OPENAI_COMPATIBLE -> "OpenAI, OpenRouter, LM Studio, or vLLM."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (config.provider == AiProviderType.ON_DEVICE_VLM) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        // On-device runtime selector: llama.cpp (GGUF + mmproj) vs MediaPipe (.task)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LocalVlmRegistry.all().forEach { engine ->
                                FilterChip(
                                    selected = config.localVlmEngineId == engine.engineId,
                                    onClick = {
                                        config = config.copy(localVlmEngineId = engine.engineId)
                                        AiSettings.save(context, config)
                                    },
                                    label = {
                                        Text(
                                            if (engine.engineId == LocalVlmRegistry.ENGINE_LLAMACPP) {
                                                "llama.cpp (GGUF + mmproj)"
                                            } else {
                                                "MediaPipe (.task)"
                                            }
                                        )
                                    }
                                )
                            }
                        }

                        if (config.localVlmEngineId == LocalVlmRegistry.ENGINE_LLAMACPP) {
                            Text(
                                text = if (LlamaCppNativeBridge.isNativeLibraryLoaded) {
                                    "Native engine: libemre_llama_vlm.so loaded (llama.cpp + libmtmd)"
                                } else {
                                    "Native libemre_llama_vlm.so not bundled in this APK — build with `-PbuildLlamaNative=true` (NDK + CMake) to enable."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (LlamaCppNativeBridge.isNativeLibraryLoaded) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )

                            Text(
                                text = ggufSummary?.let { "Installed GGUF Pair: $it" }
                                    ?: "No GGUF + mmproj.gguf pair installed yet. Import both files or download from Hugging Face (e.g. LFM2.5-VL-450M, SmolVLM2-500M, Qwen3-VL-2B).",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (LocalVlmModelManager.resolveInstalledGgufPair(context) != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { pickGgufModelLauncher.launch(arrayOf("*/*")) },
                                    enabled = !importingVlm,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Import Model .gguf")
                                }
                                Button(
                                    onClick = { pickGgufMmprojLauncher.launch(arrayOf("*/*")) },
                                    enabled = !importingVlm,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Import mmproj .gguf")
                                }
                            }

                            OutlinedTextField(
                                value = config.ggufModelDownloadUrl,
                                onValueChange = {
                                    config = config.copy(ggufModelDownloadUrl = it)
                                    savedBanner = false
                                },
                                label = { Text("GGUF Model URL (.gguf)") },
                                placeholder = { Text("https://huggingface.co/.../model-Q4_K_M.gguf") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = config.ggufMmprojDownloadUrl,
                                onValueChange = {
                                    config = config.copy(ggufMmprojDownloadUrl = it)
                                    savedBanner = false
                                },
                                label = { Text("Vision Projector URL (mmproj-*.gguf)") },
                                placeholder = { Text("https://huggingface.co/.../mmproj-model-Q8_0.gguf") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = config.hfToken,
                                onValueChange = {
                                    config = config.copy(hfToken = it)
                                    savedBanner = false
                                },
                                label = { Text("HuggingFace Token (optional for gated repos)") },
                                singleLine = true,
                                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showApiKey = !showApiKey }) {
                                        Icon(
                                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle token visibility"
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        AiSettings.save(context, config)
                                        IndexingForegroundService.downloadGgufVlmPair(context)
                                        onOpenIndexingStatus()
                                    },
                                    enabled = config.ggufModelDownloadUrl.isNotBlank() &&
                                        config.ggufMmprojDownloadUrl.isNotBlank() &&
                                        !importingVlm,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Download Pair")
                                }
                                Button(
                                    onClick = {
                                        AiSettings.save(context, config)
                                        savedBanner = true
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Save")
                                }
                            }

                            if (ggufSummary != null) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { LocalVlmModelManager.deleteGgufModels(context) }
                                            ggufSummary = null
                                            importStatus = "Removed GGUF + mmproj models."
                                        }
                                    },
                                    enabled = !importingVlm
                                ) {
                                    Text("Remove GGUF Pair")
                                }
                            }
                        } else {
                            Text(
                                text = if (vlmModelSummary != null) {
                                    "Installed MediaPipe VLM: $vlmModelSummary"
                                } else {
                                    "No MediaPipe .task model installed yet. Import a Gemma 3n / Gemma 4 .task file or download by URL."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (vlmModelSummary != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Prefer GPU acceleration", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Automatically falls back to CPU if GPU delegate is unavailable.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = config.vlmUseGpu,
                                    onCheckedChange = {
                                        config = config.copy(vlmUseGpu = it)
                                        AiSettings.save(context, config)
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { pickMediaPipeLauncher.launch(arrayOf("*/*")) },
                                    enabled = !importingVlm,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Import .task")
                                }
                                if (vlmModelSummary != null) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) { LocalVlmModelManager.deleteInstalledModels(context) }
                                                vlmModelSummary = null
                                                importStatus = "Removed MediaPipe VLM model."
                                            }
                                        },
                                        enabled = !importingVlm,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Remove")
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = config.localVlmDownloadUrl,
                                onValueChange = {
                                    config = config.copy(localVlmDownloadUrl = it)
                                    savedBanner = false
                                },
                                label = { Text("Direct Model URL (.task / .litertlm)") },
                                placeholder = { Text("https://huggingface.co/.../gemma-3n-E2B-it-int4.task") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        AiSettings.save(context, config)
                                        IndexingForegroundService.downloadVlmModel(context)
                                        onOpenIndexingStatus()
                                    },
                                    enabled = config.localVlmDownloadUrl.isNotBlank() && !importingVlm,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Download URL")
                                }
                                Button(
                                    onClick = {
                                        AiSettings.save(context, config)
                                        savedBanner = true
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Save")
                                }
                            }
                        }

                        if (!importStatus.isNullOrBlank()) {
                            Text(
                                text = importStatus.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        OutlinedTextField(
                            value = config.baseUrl,
                            onValueChange = {
                                config = config.copy(baseUrl = it)
                                savedBanner = false
                            },
                            label = { Text("Endpoint Base URL") },
                            placeholder = { Text(config.provider.defaultBaseUrl) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = config.modelName,
                            onValueChange = {
                                config = config.copy(modelName = it)
                                savedBanner = false
                            },
                            label = { Text("Vision Model Name") },
                            placeholder = { Text(config.provider.defaultModel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = config.apiKey,
                            onValueChange = {
                                config = config.copy(apiKey = it)
                                savedBanner = false
                            },
                            label = {
                                Text(if (config.provider.requiresApiKey) "API Key" else "API Key (optional)")
                            },
                            singleLine = true,
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Icon(
                                        if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle API key visibility"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    testingConnection = true
                                    testMessage = null
                                    scope.launch {
                                        val res = VisionApiClient.testConnection(config)
                                        testingConnection = false
                                        res.onSuccess { msg ->
                                            testMessage = msg
                                            testIsError = false
                                        }.onFailure { err ->
                                            testMessage = err.localizedMessage ?: "Connection failed"
                                            testIsError = true
                                        }
                                    }
                                },
                                enabled = !testingConnection,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (testingConnection) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text("Test Connection")
                            }

                            Button(
                                onClick = {
                                    AiSettings.save(context, config)
                                    savedBanner = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Save Settings")
                            }
                        }

                        if (testMessage != null) {
                            Text(
                                text = testMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (testIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    if (savedBanner) {
                        Text(
                            "AI configuration saved.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Privacy & Context Card
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardBackground,
                border = cardBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Privacy & Context",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Include OCR text in VLM prompts", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Combines extracted OCR text with visual understanding for receipts, screenshots, and documents.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.includeOcrContext,
                            onCheckedChange = {
                                config = config.copy(includeOcrContext = it)
                                AiSettings.save(context, config)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Keep Locked Vault strictly on-device", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Never send encrypted Locked Vault media to external cloud/Ollama APIs.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
            Spacer(Modifier.height(16.dp))
        }

        // Floating Top Bar
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
                "AI & Semantic Search",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
