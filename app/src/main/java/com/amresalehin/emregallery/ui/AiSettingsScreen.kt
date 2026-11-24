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

            // 1. Hybrid Provider Selection Card
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
                                "AI Vision Provider",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "On-device colors & embeddings always run locally. Optionally connect Ollama or a Vision API for Karakeep/mymind-style titles, descriptions, and rich tags.",
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
                                            AiProviderType.ON_DEVICE_ONLY -> "Zero network calls. Uses local palette & CLIP ONNX."
                                            AiProviderType.OLLAMA -> "Self-hosted vision models (e.g. qwen2.5vl:3b, llava)."
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

                    if (config.provider != AiProviderType.ON_DEVICE_ONLY) {
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
                                Text(
                                    if (config.provider.requiresApiKey) "API Key" else "API Key (optional)"
                                )
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
                    } else {
                        Button(
                            onClick = {
                                AiSettings.save(context, config)
                                savedBanner = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save Settings")
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

            // 2. Privacy & Indexing Behavior Card
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
                            Text("Include OCR text in AI prompts", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Passes extracted text from receipts, screenshots, and documents for richer summaries & tags.",
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
                            Text("Exclude Locked Vault from Cloud API", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Never upload encrypted Locked Vault media to external APIs (on-device only).",
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Use Vision API during background batch", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Turn off to run only free on-device colors/CLIP in background and use API on-demand in Viewer.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.batchUseCloudApi,
                            onCheckedChange = {
                                config = config.copy(batchUseCloudApi = it)
                                AiSettings.save(context, config)
                            }
                        )
                    }
                }
            }

            // 3. On-Device CLIP Semantic Model Card
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
                            Icons.Default.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "On-Device CLIP ViT-B/32 (ONNX)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (clipInstalled) {
                                    "Installed (~154 MB) · Offline semantic search & zero-shot tagging active."
                                } else {
                                    "Optional (~154 MB) · Enables offline natural-language visual search and CLIP similarity."
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
                                Text("Download CLIP Model")
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            ClipOnnxModelManager.deleteModels(context)
                                        }
                                        clipInstalled = false
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Remove Model")
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
