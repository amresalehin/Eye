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
 * Manages on-device VLM model files (e.g., MediaPipe/LiteRT-LM `.task` or `.litertlm` files
 * such as Gemma 3n E2B or Gemma 4 E2B) inside app-private storage (`filesDir/ai_models/vlm/`).
 *
 * Supports both:
 * 1. Direct import from device storage via Android's document picker (no HuggingFace auth needed)
 * 2. Direct HTTP/HTTPS download (with optional Bearer token for gated HuggingFace repos)
 */
object LocalVlmModelManager {
    private const val MODEL_SUBDIR = "ai_models/vlm"
    private const val MIN_VALID_MODEL_BYTES = 50L * 1024 * 1024 // >= 50 MB sanity floor

    fun getModelDir(context: Context): File = File(context.applicationContext.filesDir, MODEL_SUBDIR)

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
        val sizeMb = file.length() / (1024.0 * 1024.0)
        return if (sizeMb >= 1024.0) {
            String.format("%s (%.2f GB)", file.name, sizeMb / 1024.0)
        } else {
            String.format("%s (%.0f MB)", file.name, sizeMb)
        }
    }

    /**
     * Copies a user-selected `.task` or `.litertlm` model file into app-private storage
     * so MediaPipe can memory-map it reliably without external storage permissions.
     */
    suspend fun importModelFromUri(
        context: Context,
        sourceUri: Uri,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val dir = getModelDir(appContext)
            if (!dir.exists() && !dir.mkdirs()) error("Unable to create VLM model directory.")

            val displayName = queryDisplayName(appContext, sourceUri)
                ?.takeIf { it.isNotBlank() }
                ?: "gemma_vlm.task"

            val sanitizedName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            require(
                sanitizedName.endsWith(".task", ignoreCase = true) ||
                    sanitizedName.endsWith(".litertlm", ignoreCase = true)
            ) {
                "Please select a MediaPipe/LiteRT-LM .task or .litertlm VLM file."
            }

            val totalSize = queryFileSize(appContext, sourceUri)
            val partFile = File(dir, "$sanitizedName.importing")
            val targetFile = File(dir, sanitizedName)

            try {
                appContext.contentResolver.openInputStream(sourceUri)?.use { input ->
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

                if (partFile.length() < MIN_VALID_MODEL_BYTES) {
                    partFile.delete()
                    error("Selected file is too small to be a valid VLM model.")
                }

                LocalVlmRegistry.releaseAll()
                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }

                val current = AiSettings.load(appContext)
                AiSettings.save(appContext, current.copy(localVlmModelPath = targetFile.absolutePath))
                targetFile
            } finally {
                if (partFile.exists()) partFile.delete()
            }
        }
    }

    /**
     * Downloads a `.task` or `.litertlm` VLM model from a user-configured direct URL
     * (supporting an optional Bearer token for gated HuggingFace model repositories).
     */
    suspend fun downloadModelFromUrl(
        context: Context,
        downloadUrl: String,
        bearerToken: String = "",
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
        awaitIfResumed: suspend () -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val cleanUrl = downloadUrl.trim()
        require(cleanUrl.startsWith("https://", ignoreCase = true) || cleanUrl.startsWith("http://", ignoreCase = true)) {
            "Enter a valid http(s) download URL for the VLM .task or .litertlm file."
        }

        val dir = getModelDir(appContext)
        if (!dir.exists() && !dir.mkdirs()) error("Unable to create VLM model directory.")

        val freeBytes = StatFs(dir.absolutePath).availableBytes
        check(freeBytes >= 1_500_000_000L) {
            "At least 1.5 GB of free storage is recommended before downloading an on-device VLM."
        }

        val rawName = cleanUrl.substringBefore('?').substringAfterLast('/').ifBlank { "local_vlm.task" }
        val fileName = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val partFile = File(dir, "$fileName.part")
        val targetFile = File(dir, fileName)

        val conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
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
                error("VLM download failed (HTTP $code). If this is a gated HuggingFace repo, add your HF token or import the file locally.")
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
                            onProgress(downloaded, totalBytes, "downloading")
                        }
                    }
                }
            }
            if (partFile.length() < MIN_VALID_MODEL_BYTES) {
                partFile.delete()
                error("Downloaded VLM file appears incomplete.")
            }
            LocalVlmRegistry.releaseAll()
            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }
            val current = AiSettings.load(appContext)
            AiSettings.save(appContext, current.copy(localVlmModelPath = targetFile.absolutePath))
            onProgress(downloaded, if (totalBytes > 0L) totalBytes else downloaded, "ready")
            targetFile
        } finally {
            conn.disconnect()
            if (partFile.exists()) partFile.delete()
        }
    }

    fun deleteInstalledModels(context: Context) {
        LocalVlmRegistry.releaseAll()
        getModelDir(context).deleteRecursively()
        val current = AiSettings.load(context)
        AiSettings.save(context, current.copy(localVlmModelPath = ""))
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
