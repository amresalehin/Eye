package com.amresalehin.emregallery.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import android.provider.DocumentsContract
import com.amresalehin.emregallery.data.ai.AiModelCatalog
import com.amresalehin.emregallery.data.ai.AiModelStorage
import com.amresalehin.emregallery.data.ai.AiProviderMode
import com.amresalehin.emregallery.data.ai.AiSettingsRepository
import com.amresalehin.emregallery.data.ai.ModelDownloadState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import java.io.File

@Composable
fun LocalAiModelsSection(
    onModelSelected: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloadState by remember { mutableStateOf<ModelDownloadState?>(null) }
    var selectedModel by remember { mutableStateOf("") }
    var selectedProjector by remember { mutableStateOf("") }
    var storedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var showLibrary by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }

    fun refresh() {
        downloadState = AiModelStorage.state(context)
        selectedModel = AiModelStorage.selectedModelId(context)
        selectedProjector = AiModelStorage.selectedProjectorName(context)
        val catalogNames = AiModelCatalog.assets.map { it.fileName }.toSet()
        storedFiles = AiModelStorage.directory(context).listFiles()
            ?.filter { it.isFile && it.extension.equals("gguf", true) && it.name !in catalogNames }
            ?.sortedBy { it.name.lowercase() }.orEmpty()
    }

    fun activateModel(modelId: String) {
        AiModelStorage.selectModel(context, modelId)
        scope.launch {
            runCatching {
                val repository = AiSettingsRepository(context.applicationContext)
                val saved = repository.settings.first()
                repository.save(
                    saved.copy(
                        providerMode = AiProviderMode.ON_DEVICE,
                        baseUrl = "",
                        model = modelId
                    )
                )
            }.onSuccess {
                notice = "On-device model selected"
                noticeIsError = false
                onModelSelected(modelId)
                refresh()
            }.onFailure {
                notice = it.localizedMessage ?: "Could not select model"
                noticeIsError = true
            }
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            scope.launch {
                runCatching {
                    val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
                    withContext(Dispatchers.IO) { importGgufTree(context, treeUri) }
                }.onSuccess { count ->
                    notice = if (count > 0) "$count GGUF file(s) imported" else "No GGUF files found in that folder"
                    noticeIsError = false
                    refresh()
                }.onFailure {
                    notice = it.localizedMessage ?: "Folder import failed"
                    noticeIsError = true
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val name = queryModelDisplayName(context, uri) ?: "imported-model.gguf"
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Could not open the selected file.")
                    withContext(Dispatchers.IO) { AiModelStorage.importFile(context, input, name) }
                }.onSuccess { file ->
                    if (file.name.startsWith("mmproj", true)) {
                        AiModelStorage.selectProjector(context, file.name)
                        val currentModel = AiModelStorage.selectedModelId(context)
                        if (currentModel.isNotBlank()) onModelSelected(currentModel)
                        notice = "Vision projector imported"
                    } else {
                        activateModel(file.name)
                        notice = "Model imported"
                    }
                    noticeIsError = false
                    refresh()
                }.onFailure {
                    notice = it.localizedMessage ?: "Import failed"
                    noticeIsError = true
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        while (isActive) {
            delay(900)
            refresh()
        }
    }

    val selection = AiModelStorage.resolveSelection(context)
    val activeAsset = downloadState?.let { AiModelCatalog.byId(it.assetId) }
    val downloading = downloadState?.status in listOf("queued", "downloading")
    val resumable = downloadState?.status in listOf("paused", "error")

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("On-device AI", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (selection != null) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Model ready", tint = MaterialTheme.colorScheme.primary)
                }
            }

            Text(
                selection?.displayName ?: if (selectedModel.isNotBlank()) "Vision projector required" else "No model selected",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val state = downloadState
            if (state != null && activeAsset != null && state.status in listOf("queued", "downloading", "paused", "error")) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(activeAsset.modelName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(
                            if (state.totalBytes > 0L) "${(state.progress * 100).toInt()}%" else formatModelBytes(state.downloadedBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.totalBytes > 0L) {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "${formatModelBytes(state.downloadedBytes)} / ${formatModelBytes(state.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (!state.error.isNullOrBlank()) {
                        Text(state.error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (resumable) {
                            Button(onClick = {
                                scope.launch {
                                    activeAsset?.let { AiModelStorage.resume(context, it) }
                                }
                            }) {
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
            } else if (state?.status == "complete" && activeAsset != null) {
                Text("Downloaded · ${activeAsset.modelName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }

            Text("Models", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AiModelCatalog.models().forEach { asset ->
                    val ready = AiModelStorage.isInstalled(context, asset)
                    val projector = asset.pairedProjectorId?.let { AiModelCatalog.byId(it) }
                    val projectorReady = projector?.let { AiModelStorage.isInstalled(context, it) } ?: false
                    AiModelPill(
                        label = asset.modelName.replace(" · ", " "),
                        selected = selectedModel == asset.id && selection != null,
                        status = when { selectedModel == asset.id && selection != null -> "Active"; ready && !projectorReady -> "Vision"; ready -> "Ready"; else -> "Get" },
                        onClick = {
                            scope.launch {
                                when {
                                    !ready -> AiModelStorage.start(context, asset)
                                    !projectorReady && projector != null -> AiModelStorage.start(context, projector)
                                    else -> activateModel(asset.id)
                                }
                            }
                        }
                    )
                }
                storedFiles.filterNot { it.name.startsWith("mmproj", true) }.forEach { file ->
                    AiModelPill(
                        label = file.nameWithoutExtension.take(18),
                        selected = selectedModel == file.name && selection != null,
                        status = if (selectedModel == file.name && selection != null) "Active" else "Imported",
                        onClick = { activateModel(file.name) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showLibrary = true }) { Text("Library") }
                OutlinedButton(onClick = {
                    importLauncher.launch(arrayOf("application/octet-stream", "application/*"))
                }) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("File")
                }
                OutlinedButton(onClick = { folderLauncher.launch(null) }) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Folder")
                }
            }

            if (notice != null) {
                Text(
                    notice.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    if (showLibrary) {
        AlertDialog(
            onDismissRequest = { showLibrary = false },
            title = { Text("Model library") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    AiModelCatalog.models().forEach { asset ->
                        val projector = asset.pairedProjectorId?.let { AiModelCatalog.byId(it) }
                        val modelReady = AiModelStorage.isInstalled(context, asset)
                        val projectorReady = projector?.let { AiModelStorage.isInstalled(context, it) } ?: false
                        val isSelected = selectedModel == asset.id
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 2.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    asset.modelName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    buildString {
                                        append(asset.sizeLabel)
                                        if (projector != null) append(" · Vision ").append(projector.sizeLabel)
                                        if (!modelReady) append(" · Not downloaded")
                                        else if (!projectorReady) append(" · Vision file missing")
                                        else append(" · Ready")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            when {
                                isSelected && selection != null -> Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                modelReady && projectorReady -> TextButton(
                                    onClick = {
                                        activateModel(asset.id)
                                        showLibrary = false
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) { Text("Use") }
                                !modelReady -> TextButton(
                                    enabled = !downloading && !resumable,
                                    onClick = { scope.launch { AiModelStorage.start(context, asset) } },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) { Text("Get") }
                            }
                            if (projector != null && !projectorReady) {
                                TextButton(
                                    enabled = !downloading && !resumable,
                                    onClick = { scope.launch { AiModelStorage.start(context, projector) } },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) { Text("Vision") }
                            }
                        }
                        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }

                    if (storedFiles.isNotEmpty()) {
                        Text("Imported GGUF files", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        storedFiles.forEach { file ->
                            val isProjector = file.name.startsWith("mmproj", true)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(file.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(formatModelBytes(file.length()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = {
                                    if (isProjector) {
                                        AiModelStorage.selectProjector(context, file.name)
                                        val model = AiModelStorage.selectedModelId(context)
                                        if (model.isNotBlank()) onModelSelected(model)
                                        notice = "Vision projector selected"
                                    } else {
                                        activateModel(file.name)
                                    }
                                    showLibrary = false
                                    refresh()
                                }) {
                                    Text(if (isProjector) "Use mmproj" else "Use")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLibrary = false }) { Text("Done") } }
        )
    }
}

private fun queryModelDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

private fun formatModelBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    do {
        value /= 1024.0
        index++
    } while (value >= 1024.0 && index < units.lastIndex)
    return String.format(java.util.Locale.US, "%.1f %s", value, units[index])
}


@Composable
private fun AiModelPill(
    label: String,
    selected: Boolean,
    status: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(6.dp)) {
                drawCircle(if (selected) androidx.compose.ui.graphics.Color(0xFF27865B) else androidx.compose.ui.graphics.Color.Gray)
            }
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            Text(status, style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun importGgufTree(context: Context, treeUri: Uri): Int {
    val resolver = context.contentResolver
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    var imported = 0
    fun visit(parentId: String) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idCol)
                val name = cursor.getString(nameCol).orEmpty()
                val mime = cursor.getString(mimeCol)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    visit(id)
                } else if (name.endsWith(".gguf", true)) {
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    resolver.openInputStream(documentUri)?.let { input ->
                        AiModelStorage.importFile(context, input, name)
                        imported++
                    }
                }
            }
        }
    }
    visit(rootId)
    return imported
}
