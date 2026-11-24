package com.amresalehin.emregallery.ai

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages on-device VLM model files inside app-private storage:
 * - `filesDir/ai_models/vlm_gguf/` for `llama.cpp` GGUF + matching `mmproj.gguf` pairs
 *   (e.g., LFM2.5-VL-450M, SmolVLM2-500M, Qwen3.5-0.8B, Qwen3-VL-2B)
 * - `filesDir/ai_models/vlm/` for MediaPipe / LiteRT-LM `.task` / `.litertlm` files
 */
object LocalVlmModelManager {
    private const val MEDIAPIPE_SUBDIR = "ai_models/vlm"
    private const val GGUF_SUBDIR = "ai_models/vlm_gguf"
    private const val MIN_VALID_MODEL_BYTES = 20L * 1024 * 1024 // >= 20 MB sanity floor
    private const val MIN_VALID_MMPROJ_BYTES = 5L * 1024 * 1024 // >= 5 MB sanity floor

    fun getModelDir(context: Context): File = File(context.applicationContext.filesDir, MEDIAPIPE_SUBDIR)
    fun getGgufDir(context: Context): File = File(context.applicationContext.filesDir, GGUF_SUBDIR)

    // --- llama.cpp GGUF + mmproj pair ---

    fun resolveInstalledGgufModel(context: Context): File? {
        val config = AiSettings.load(context)
        if (config.ggufModelPath.isNotBlank()) {
            val f = File(config.ggufModelPath)
            if (f.isFile && f.length() >= MIN_VALID_MODEL_BYTES) return f
        }
        val dir = getGgufDir(context)
        if (!dir.exists()) return null
        return dir.listFiles()
            ?.filter { f ->
                f.isFile &&
                    f.length() >= MIN_VALID_MODEL_BYTES &&
                    f.name.endsWith(".gguf", ignoreCase = true) &&
                    !f.name.contains("mmproj", ignoreCase = true)
            }
            ?.maxByOrNull { it.lastModified() }
    }

    fun resolveInstalledMmproj(context: Context): File? {
        val config = AiSettings.load(context)
        if (config.ggufMmprojPath.isNotBlank()) {
            val f = File(config.ggufMmprojPath)
            if (f.isFile && f.length() >= MIN_VALID_MMPROJ_BYTES) return f
        }
        val dir = getGgufDir(context)
        if (!dir.exists()) return null
        return dir.listFiles()
            ?.filter { f ->
                f.isFile &&
                    f.length() >= MIN_VALID_MMPROJ_BYTES &&
                    f.name.endsWith(".gguf", ignoreCase = true) &&
                    f.name.contains("mmproj", ignoreCase = true)
            }
            ?.maxByOrNull { it.lastModified() }
    }

    fun resolveInstalledGgufPair(context: Context): Pair<File, File>? {
        val model = resolveInstalledGgufModel(context) ?: return null
        val mmproj = resolveInstalledMmproj(context) ?: return null
        return model to mmproj
    }

    fun installedGgufSummary(context: Context): String? {
        val model = resolveInstalledGgufModel(context)
        val mmproj = resolveInstalledMmproj(context)
        return when {
            model != null && mmproj != null -> "${formatFileSummary(model)} + ${formatFileSummary(mmproj)}"
            model != null -> "${formatFileSummary(model)} (mmproj.gguf missing)"
            mmproj != null -> "model.gguf missing + ${formatFileSummary(mmproj)}"
            else -> null
        }
    }

    suspend fun importGgufFromUri(
        context: Context,
        sourceUri: Uri,
        isMmproj: Boolean,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val dir = getGgufDir(appContext)
            if (!dir.exists() && !dir.mkdirs()) error("Unable to create GGUF VLM directory.")

            val defaultName = if (isMmproj) "mmproj-vlm.gguf" else "vlm-model.gguf"
            val displayName = queryDisplayName(appContext, sourceUri)?.takeIf { it.isNotBlank() } ?: defaultName
            val sanitizedName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            require(sanitizedName.endsWith(".gguf", ignoreCase = true)) {
                "Please select a .gguf file."
            }

            val minBytes = if (isMmproj) MIN_VALID_MMPROJ_BYTES else MIN_VALID_MODEL_BYTES
            val target = copyUriToFile(appContext, sourceUri, dir, sanitizedName, minBytes, onProgress)
            LocalVlmRegistry.releaseAll()
            val current = AiSettings.load(appContext)
            AiSettings.save(
                appContext,
                if (isMmproj) current.copy(ggufMmprojPath = target.absolutePath)
                else current.copy(ggufModelPath = target.absolutePath)
            )
            target
        }
    }

    suspend fun downloadGgufPair(
        context: Context,
        modelUrl: String,
        mmprojUrl: String,
        bearerToken: String = "",
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
        awaitIfResumed: suspend () -> Unit = {}
    ): Pair<File, File> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val cleanModelUrl = modelUrl.trim()
        val cleanMmprojUrl = mmprojUrl.trim()
        require(cleanModelUrl.startsWith("http", ignoreCase = true) && cleanMmprojUrl.startsWith("http", ignoreCase = true)) {
            "Enter valid http(s) URLs for both the GGUF model and matching mmproj.gguf file."
        }

        val dir = getGgufDir(appContext)
        if (!dir.exists() && !dir.mkdirs()) error("Unable to create GGUF VLM directory.")

        val freeBytes = StatFs(dir.absolutePath).availableBytes
        check(freeBytes >= 600_000_000L) {
            "At least 600 MB of free storage is recommended before downloading a GGUF + mmproj VLM pair."
        }

        val modelFile = downloadSingleFile(
            url = cleanModelUrl,
            targetDir = dir,
            fallbackName = "vlm-model.gguf",
            minBytes = MIN_VALID_MODEL_BYTES,
            bearerToken = bearerToken,
            onProgress = { rec, tot -> onProgress(rec, tot, "downloading_model") },
            awaitIfResumed = awaitIfResumed
        )

        val mmprojFile = downloadSingleFile(
            url = cleanMmprojUrl,
            targetDir = dir,
            fallbackName = "mmproj-vlm.gguf",
            minBytes = MIN_VALID_MMPROJ_BYTES,
            bearerToken = bearerToken,
            onProgress = { rec, tot -> onProgress(rec, tot, "downloading_mmproj") },
            awaitIfResumed = awaitIfResumed
        )

        LocalVlmRegistry.releaseAll()
        val current = AiSettings.load(appContext)
        AiSettings.save(
            appContext,
            current.copy(
                ggufModelPath = modelFile.absolutePath,
                ggufMmprojPath = mmprojFile.absolutePath
            )
        )
        onProgress(100L, 100L, "ready")
        modelFile to mmprojFile
    }

    fun deleteGgufModels(context: Context) {
        LocalVlmRegistry.releaseAll()
        getGgufDir(context).deleteRecursively()
        val current = AiSettings.load(context)
        AiSettings.save(context, current.copy(ggufModelPath = "", ggufMmprojPath = ""))
    }

    // --- MediaPipe / LiteRT-LM .task ---

    fun resolveInstalledModel(context: Context): File? {
        val config = AiSettings.load(context)
        if (config.localVlmModelPath.isNotBlank()) {
            val explicit = File(config.localVlmModelPath)
            if (explicit.isFile && explicit.length() >= MIN_VALID_MODEL_BYTES) return explicit
        }
        val dir = getModelDir(context)
        if (!dir.exists()) return null
        return dir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.length() >= MIN_VALID_MODEL_BYTES &&
                    (file.name.endsWith(".task", ignoreCase = true) ||
                        file.name.endsWith(".litertlm", ignoreCase = true))
            }
            ?.maxByOrNull { it.lastModified() }
    }

    fun isInstalled(context: Context): Boolean = resolveInstalledModel(context) != null

    fun installedModelSummary(context: Context): String? {
        val file = resolveInstalledModel(context) ?: return null
        return formatFileSummary(file)
    }

    suspend fun importModelFromUri(
        context: Context,
        sourceUri: Uri,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val dir = getModelDir(appContext)
            if (!dir.exists() && !dir.mkdirs()) error("Unable to create VLM model directory.")

            val displayName = queryDisplayName(appContext, sourceUri)?.takeIf { it.isNotBlank() } ?: "gemma_vlm.task"
            val sanitizedName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            require(
                sanitizedName.endsWith(".task", ignoreCase = true) ||
                    sanitizedName.endsWith(".litertlm", ignoreCase = true)
            ) {
                "Please select a MediaPipe/LiteRT-LM .task or .litertlm VLM file."
            }

            val target = copyUriToFile(appContext, sourceUri, dir, sanitizedName, MIN_VALID_MODEL_BYTES, onProgress)
            LocalVlmRegistry.releaseAll()
            val current = AiSettings.load(appContext)
            AiSettings.save(appContext, current.copy(localVlmModelPath = target.absolutePath))
            target
        }
    }

    suspend fun downloadModelFromUrl(
        context: Context,
        downloadUrl: String,
        bearerToken: String = "",
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
        awaitIfResumed: suspend () -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val cleanUrl = downloadUrl.trim()
        require(cleanUrl.startsWith("http", ignoreCase = true)) {
            "Enter a valid http(s) download URL for the VLM .task or .litertlm file."
        }
        val dir = getModelDir(appContext)
        if (!dir.exists() && !dir.mkdirs()) error("Unable to create VLM model directory.")

        val target = downloadSingleFile(
            url = cleanUrl,
            targetDir = dir,
            fallbackName = "local_vlm.task",
            minBytes = MIN_VALID_MODEL_BYTES,
            bearerToken = bearerToken,
            onProgress = { rec, tot -> onProgress(rec, tot, "downloading") },
            awaitIfResumed = awaitIfResumed
        )
        LocalVlmRegistry.releaseAll()
        val current = AiSettings.load(appContext)
        AiSettings.save(appContext, current.copy(localVlmModelPath = target.absolutePath))
        onProgress(target.length(), target.length(), "ready")
        target
    }

    fun deleteInstalledModels(context: Context) {
        LocalVlmRegistry.releaseAll()
        getModelDir(context).deleteRecursively()
        val current = AiSettings.load(context)
        AiSettings.save(context, current.copy(localVlmModelPath = ""))
    }

    // --- Shared Helpers ---

    private fun formatFileSummary(file: File): String {
        val sizeMb = file.length() / (1024.0 * 1024.0)
        return if (sizeMb >= 1024.0) {
            String.format("%s (%.2f GB)", file.name, sizeMb / 1024.0)
        } else {
            String.format("%s (%.0f MB)", file.name, sizeMb)
        }
    }

    private fun copyUriToFile(
        context: Context,
        sourceUri: Uri,
        dir: File,
        sanitizedName: String,
        minBytes: Long,
        onProgress: (Long, Long) -> Unit
    ): File {
        val totalSize = queryFileSize(context, sourceUri)
        val partFile = File(dir, "$sanitizedName.importing")
        val targetFile = File(dir, sanitizedName)
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                partFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var copied = 0L
                    var read: Int
                    var lastEmit = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        copied += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 250L) {
                            lastEmit = now
                            onProgress(copied, totalSize)
                        }
                    }
                    onProgress(copied, if (totalSize > 0L) totalSize else copied)
                }
            } ?: error("Unable to open selected model file.")

            if (partFile.length() < minBytes) {
                partFile.delete()
                error("Selected file is smaller than expected for a valid model.")
            }
            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }
            return targetFile
        } finally {
            if (partFile.exists()) partFile.delete()
        }
    }

    private suspend fun downloadSingleFile(
        url: String,
        targetDir: File,
        fallbackName: String,
        minBytes: Long,
        bearerToken: String,
        onProgress: (Long, Long) -> Unit,
        awaitIfResumed: suspend () -> Unit
    ): File {
        val rawName = url.substringBefore('?').substringAfterLast('/').ifBlank { fallbackName }
        val fileName = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val partFile = File(targetDir, "$fileName.part")
        val targetFile = File(targetDir, fileName)

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            if (bearerToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${bearerToken.trim()}")
            }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                error("Download failed for $fileName (HTTP $code). Add an HF token if gated, or import locally.")
            }
            val totalBytes = conn.contentLengthLong.coerceAtLeast(0L)
            var downloaded = 0L
            BufferedInputStream(conn.inputStream).use { input ->
                partFile.outputStream().buffered().use { output ->
                    val buf = ByteArray(128 * 1024)
                    var read: Int
                    var lastEmit = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        awaitIfResumed()
                        if (Thread.currentThread().isInterrupted) throw CancellationException("Download cancelled")
                        output.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 300L) {
                            lastEmit = now
                            onProgress(downloaded, totalBytes)
                        }
                    }
                }
            }
            if (partFile.length() < minBytes) {
                partFile.delete()
                error("Downloaded file $fileName appears incomplete.")
            }
            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }
            return targetFile
        } finally {
            conn.disconnect()
            if (partFile.exists()) partFile.delete()
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun queryFileSize(context: Context, uri: Uri): Long {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }
}
