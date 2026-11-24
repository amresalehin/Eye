package com.amresalehin.emregallery.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amresalehin.emregallery.data.ai.AiModelAsset
import com.amresalehin.emregallery.data.ai.AiModelCatalog
import com.amresalehin.emregallery.data.ai.AiModelStorage
import com.amresalehin.emregallery.data.ai.ModelDownloadState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun LocalAiModelsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloadState by remember { mutableStateOf<ModelDownloadState?>(null) }
    var selectedId by remember { mutableStateOf("") }
    var installedVersion by remember { mutableStateOf(0) }
    var importedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var hasError by remember { mutableStateOf(false) }

    fun refresh() {
        downloadState = AiModelStorage.state(context)
        val prefs = context.getSharedPreferences("ai_model_downloads", Context.MODE_PRIVATE)
        selectedId = prefs.getString("selected_model", "").orEmpty()
        importedFiles = AiModelStorage.directory(context).listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".importing") }
            ?.sortedBy { it.name.lowercase() }.orEmpty()
        installedVersion++
    }

    LaunchedEffect(Unit) {
        refresh()
        while (isActive) {
            delay(900)
            refresh()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val name = queryDisplayName(context, uri) ?: "imported-model.gguf"
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Could not open the selected file.")
                    withContextIo { AiModelStorage.importFile(context, input, name) }
                }.onSuccess {
                    notice = "Imported ${it.name}"
                    hasError = false
                    refresh()
                }.onFailure {
                    notice = it.message ?: "Import failed"
                    hasError = true
                }
            }
        }
    }

    val state = downloadState
    val activeAsset = state?.let { AiModelCatalog.byId(it.assetId) }
    val installed = remember(installedVersion) {
        AiModelCatalog.assets.filter { AiModelStorage.isInstalled(context, it) }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "On-device models",
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${installed.size} files",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                "Download GGUF models from Hugging Face or import a model file. Downloads are kept in Eye's private storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state != null && activeAsset != null && state.status in listOf("queued", "downloading", "paused", "error")) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(activeAsset.modelName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                    if (state.totalBytes > 0L) {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "${formatModelBytes(state.downloadedBytes)} / ${formatModelBytes(state.totalBytes)} · ${(state.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("${formatModelBytes(state.downloadedBytes)} downloaded", style = MaterialTheme.typography.labelSmall)
                    }
                    if (!state.error.isNullOrBlank()) {
                        Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.status == "paused" || state.status == "error") {
                            Button(onClick = { scope.launch { AiModelStorage.resume(context, activeAsset) } }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Resume")
                            }
                        } else {
                            OutlinedButton(onClick = { scope.launch { AiModelStorage.pause(context) } }) {
                                Icon(Icons.Default.Pause, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Pause")
                            }
                        }
                        OutlinedButton(onClick = { scope.launch { AiModelStorage.cancel(context); refresh() } }) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }
                }
            } else if (state?.status == "complete") {
                Text(
                    "Last download completed · ${activeAsset?.modelName.orEmpty()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text("Available models", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            AiModelCatalog.assets.filter { it.kind == "Model" }.forEach { asset ->
                val isInstalled = AiModelStorage.isInstalled(context, asset)
                val isSelected = selectedId == asset.id
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(asset.modelName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "${asset.sizeLabel} · GGUF",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            asset.pairedProjectorId?.let { projectorId ->
                                val projector = AiModelCatalog.byId(projectorId)
                                Text(
                                    "Vision projector required: ${projector?.fileName ?: "separate file"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                        } else if (isInstalled) {
                            OutlinedButton(onClick = {
                                context.getSharedPreferences("ai_model_downloads", Context.MODE_PRIVATE)
                                    .edit().putString("selected_model", asset.id).apply()
                                refresh()
                                notice = "Model selected"
                                hasError = false
                            }) { Text("Select") }
                        } else {
                            OutlinedButton(
                                enabled = state?.status !in listOf("queued", "downloading", "paused", "error"),
                                onClick = { scope.launch { AiModelStorage.start(context, asset) } }
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Download")
                            }
                        }
                    }
                    if (asset.pairedProjectorId != null) {
                        val projector = AiModelCatalog.byId(asset.pairedProjectorId)
                        if (projector != null && !AiModelStorage.isInstalled(context, projector)) {
                            OutlinedButton(
                                enabled = state?.status !in listOf("queued", "downloading"),
                                onClick = { scope.launch { AiModelStorage.start(context, projector) } }
                            ) { Text("Download vision projector · ${projector.sizeLabel}") }
                        }
                    }
                    if (asset != AiModelCatalog.assets.filter { it.kind == "Model" }.last()) {
                        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }

            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/octet-stream", "application/x-gguf", "application/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("Import model file")
            }

            if (importedFiles.isNotEmpty()) {
                Text("Stored in Eye", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                importedFiles.forEach { file ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatModelBytes(file.length()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selectedId == file.name) {
                            Icon(Icons.Default.Check, contentDescription = "Selected")
                        } else if (file.extension.equals("gguf", true)) {
                            TextButtonCompatSelect(file.name) {
                                context.getSharedPreferences("ai_model_downloads", Context.MODE_PRIVATE)
                                    .edit().putString("selected_model", file.name).apply()
                                refresh()
                            }
                        }
                    }
                }
            }

            if (notice != null) {
                Text(notice.orEmpty(), style = MaterialTheme.typography.labelSmall, color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            Text(
                "GGUF vision models may need a matching mmproj file. Model files can be managed here; on-device inference requires a compatible runtime.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TextButtonCompatSelect(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text("Select") }
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

private suspend fun withContextIo(block: () -> File): File =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

private fun formatModelBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    do { value /= 1024.0; index++ } while (value >= 1024.0 && index < units.lastIndex)
    return String.format(java.util.Locale.US, "%.1f %s", value, units[index])
}
