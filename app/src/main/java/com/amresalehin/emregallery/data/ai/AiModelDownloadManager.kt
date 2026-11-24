package com.amresalehin.emregallery.data.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AiModelAsset(
    val id: String,
    val modelName: String,
    val fileName: String,
    val repository: String,
    val sizeLabel: String,
    val kind: String = "Model",
    val pairedProjectorId: String? = null
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/main/$fileName"
}

object AiModelCatalog {
    val assets = listOf(
        AiModelAsset("qwen25vl-q4", "Qwen2.5-VL 3B · Q4_K_M", "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf", "ggml-org/Qwen2.5-VL-3B-Instruct-GGUF", "~1.93 GB", pairedProjectorId = "qwen25vl-mmproj"),
        AiModelAsset("qwen25vl-mmproj", "Qwen2.5-VL 3B · Vision projector", "mmproj-Qwen2.5-VL-3B-Instruct-f16.gguf", "ggml-org/Qwen2.5-VL-3B-Instruct-GGUF", "~1.34 GB", "Vision projector"),
        AiModelAsset("qwen3vl-q4", "Qwen3-VL 2B · Q4_K_M", "Qwen3VL-2B-Instruct-Q4_K_M.gguf", "Qwen/Qwen3-VL-2B-Instruct-GGUF", "~1.11 GB", pairedProjectorId = "qwen3vl-mmproj"),
        AiModelAsset("qwen3vl-q8", "Qwen3-VL 2B · Q8_0", "Qwen3VL-2B-Instruct-Q8_0.gguf", "Qwen/Qwen3-VL-2B-Instruct-GGUF", "~1.83 GB", pairedProjectorId = "qwen3vl-mmproj"),
        AiModelAsset("qwen3vl-mmproj", "Qwen3-VL 2B · Vision projector", "mmproj-Qwen3VL-2B-Instruct-F16.gguf", "Qwen/Qwen3-VL-2B-Instruct-GGUF", "~819 MB", "Vision projector"),
        AiModelAsset("smol22-q4", "SmolVLM2 2.2B · Q4_K_M", "SmolVLM2-2.2B-Instruct-Q4_K_M.gguf", "second-state/SmolVLM2-2.2B-Instruct-GGUF", "~1.11 GB", pairedProjectorId = "smol22-mmproj"),
        AiModelAsset("smol22-mmproj", "SmolVLM2 2.2B · Vision projector", "SmolVLM2-2.2B-Instruct-mmproj-f16.gguf", "second-state/SmolVLM2-2.2B-Instruct-GGUF", "~872 MB", "Vision projector"),
        AiModelAsset("smol500-video-q8", "SmolVLM2 500M Video · Q8_0", "SmolVLM2-500M-Video-Instruct-Q8_0.gguf", "second-state/SmolVLM2-500M-Video-Instruct-GGUF", "~437 MB", pairedProjectorId = "smol500-video-mmproj"),
        AiModelAsset("smol500-video-mmproj", "SmolVLM2 500M Video · Vision projector", "SmolVLM2-500M-Video-Instruct-mmproj-f16.gguf", "second-state/SmolVLM2-500M-Video-Instruct-GGUF", "~199 MB", "Vision projector"),
        AiModelAsset("gemma4-e2b-q8", "Gemma 4 E2B · Q8_0", "gemma-4-E2B-it-Q8_0.gguf", "ggml-org/gemma-4-E2B-it-GGUF", "~4.97 GB")
    )
    fun byId(id: String) = assets.firstOrNull { it.id == id }
}

data class ModelDownloadState(
    val assetId: String,
    val status: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val error: String? = null
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

object AiModelStorage {
    private const val PREFS = "ai_model_downloads"
    private const val KEY_ASSET = "asset"
    private const val KEY_STATUS = "status"
    private const val KEY_DOWNLOADED = "downloaded"
    private const val KEY_TOTAL = "total"
    private const val KEY_ERROR = "error"
    private const val KEY_PAUSE = "pause_requested"
    private const val KEY_ETAG = "download_etag"

    fun directory(context: Context): File =
        File(context.applicationContext.filesDir, "ai_models").apply { mkdirs() }

    fun finalFile(context: Context, asset: AiModelAsset) = File(directory(context), asset.fileName)
    fun partialFile(context: Context, asset: AiModelAsset) = File(directory(context), asset.fileName + ".part")

    fun state(context: Context): ModelDownloadState? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getString(KEY_ASSET, null) ?: return null
        return ModelDownloadState(
            id, p.getString(KEY_STATUS, "queued") ?: "queued",
            p.getLong(KEY_DOWNLOADED, 0L), p.getLong(KEY_TOTAL, 0L),
            p.getString(KEY_ERROR, null)
        )
    }

    fun writeState(context: Context, state: ModelDownloadState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ASSET, state.assetId)
            .putString(KEY_STATUS, state.status)
            .putLong(KEY_DOWNLOADED, state.downloadedBytes)
            .putLong(KEY_TOTAL, state.totalBytes)
            .putString(KEY_ERROR, state.error)
            .apply()
    }

    fun pauseRequested(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAUSE, false)

    fun setPauseRequested(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PAUSE, value).apply()
    }

    fun isInstalled(context: Context, asset: AiModelAsset) = finalFile(context, asset).let { it.exists() && it.length() > 0L }

    fun importFile(context: Context, source: java.io.InputStream, displayName: String): File {
        require(displayName.endsWith(".gguf", true) || displayName.endsWith(".task", true) || displayName.endsWith(".bin", true)) {
            "Choose a GGUF, TASK or BIN model file."
        }
        val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120)
        require(safeName.isNotBlank()) { "Invalid model file name." }
        val target = File(directory(context), safeName)
        val temp = File(directory(context), "$safeName.importing")
        source.use { input -> temp.outputStream().buffered().use { output -> input.copyTo(output) } }
        if (temp.length() <= 0L) {
            temp.delete()
            throw IOException("The selected model file is empty.")
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    suspend fun start(context: Context, asset: AiModelAsset) {
        val app = context.applicationContext
        setPauseRequested(app, false)
        writeState(app, ModelDownloadState(asset.id, "queued", partialFile(app, asset).length(), 0L))
        val request = OneTimeWorkRequestBuilder<AiModelDownloadWorker>()
            .setInputData(workDataOf(KEY_ASSET to asset.id))
            .addTag("ai-model-download")
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork("ai-model-download", androidx.work.ExistingWorkPolicy.REPLACE, request).await()
    }

    suspend fun pause(context: Context) {
        setPauseRequested(context, true)
        state(context)?.let { old ->
            val bytes = AiModelCatalog.byId(old.assetId)?.let { partialFile(context, it).length() } ?: old.downloadedBytes
            writeState(context, old.copy(status = "paused", downloadedBytes = bytes))
        }
        WorkManager.getInstance(context).cancelUniqueWork("ai-model-download").await()
    }

    suspend fun resume(context: Context, asset: AiModelAsset) = start(context, asset)

    suspend fun cancel(context: Context) {
        setPauseRequested(context, false)
        WorkManager.getInstance(context).cancelUniqueWork("ai-model-download").await()
        state(context)?.let { old ->
            AiModelCatalog.byId(old.assetId)?.let { partialFile(context, it).delete() }
            writeState(context, old.copy(status = "cancelled", downloadedBytes = 0L, error = null))
        }
    }
}

class AiModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val asset = AiModelCatalog.byId(inputData.getString("asset") ?: return@withContext Result.failure())
            ?: return@withContext Result.failure()
        val target = AiModelStorage.finalFile(applicationContext, asset)
        if (target.exists() && target.length() > 0L) {
            AiModelStorage.writeState(applicationContext, ModelDownloadState(asset.id, "complete", target.length(), target.length()))
            return@withContext Result.success()
        }
        val partial = AiModelStorage.partialFile(applicationContext, asset)
        val existing = partial.length()
        try {
            setForeground(createForegroundInfo(asset, existing, 0L))
            val request = Request.Builder().url(asset.downloadUrl)
                .header("User-Agent", "Eye-Android/1.0")
                .apply {
                    if (existing > 0L) {
                        header("Range", "bytes=$existing-")
                        applicationContext.getSharedPreferences("ai_model_downloads", Context.MODE_PRIVATE)
                            .getString("download_etag", null)
                            ?.takeIf { it.isNotBlank() }?.let { header("If-Range", it) }
                    }
                }
                .build()
            client.newCall(request).execute().use { response ->
                if (existing > 0L && response.code != 206) {
                    partial.delete()
                    applicationContext.getSharedPreferences("ai_model_downloads", Context.MODE_PRIVATE)
                        .edit().remove("download_etag").apply()
                    throw IOException("Hugging Face did not honor the resume request; the partial file was discarded safely.")
                }
                if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code}).")
                if (existing > 0L) {
                    val contentRange = response.header("Content-Range").orEmpty()
                    if (!contentRange.startsWith("bytes $existing-", ignoreCase = true)) {
                        partial.delete()
                        throw IOException("The server returned an invalid byte range; the partial file was discarded.")
                    }
                }
                response.header("ETag")?.let { etag ->
                    applicationContext.getSharedPreferences("ai_model_downloads", Context.MODE_PRIVATE)
                        .edit().putString("download_etag", etag).apply()
                }
                val body = response.body ?: throw IOException("Hugging Face returned an empty response.")
                val total = if (response.code == 206) {
                    val range = response.header("Content-Range")
                    range?.substringAfterLast('/')?.toLongOrNull() ?: (existing + body.contentLength().coerceAtLeast(0L))
                } else body.contentLength().coerceAtLeast(0L)
                AiModelStorage.writeState(applicationContext, ModelDownloadState(asset.id, "downloading", existing, total))
                body.byteStream().use { input ->
                    val output = if (existing > 0L) {
                        java.io.FileOutputStream(partial, true).buffered(128 * 1024)
                    } else {
                        partial.outputStream().buffered(128 * 1024)
                    }
                    output.use { sink ->
                            val buffer = ByteArray(128 * 1024)
                            var downloaded = existing
                            var lastUpdate = 0L
                            while (true) {
                                if (isStopped || AiModelStorage.pauseRequested(applicationContext)) {
                                    AiModelStorage.writeState(applicationContext, ModelDownloadState(asset.id, "paused", downloaded, total))
                                    return@withContext Result.success()
                                }
                                val count = input.read(buffer)
                                if (count < 0) break
                                sink.write(buffer, 0, count)
                                downloaded += count
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate >= 500L) {
                                    AiModelStorage.writeState(applicationContext, ModelDownloadState(asset.id, "downloading", downloaded, total))
                                    setProgress(workDataOf("downloaded" to downloaded, "total" to total))
                                    setForeground(createForegroundInfo(asset, downloaded, total))
                                    lastUpdate = now
                                }
                            }
                            sink.flush()
                            if (total > 0L && downloaded != total) throw IOException("Download was incomplete ($downloaded of $total bytes).")
                    }
                }
            }
            if (partial.length() <= 0L) throw IOException("The downloaded model is empty.")
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            AiModelStorage.writeState(applicationContext, ModelDownloadState(asset.id, "complete", target.length(), target.length()))
            applicationContext.getSharedPreferences("ai_model_downloads", Context.MODE_PRIVATE)
                .edit().remove("download_etag").apply()
            Result.success()
        } catch (error: Exception) {
            if (AiModelStorage.pauseRequested(applicationContext) || isStopped) {
                AiModelStorage.writeState(applicationContext, ModelDownloadState(asset.id, "paused", partial.length(), 0L))
                Result.success()
            } else {
                AiModelStorage.writeState(applicationContext, ModelDownloadState(asset.id, "error", partial.length(), 0L, error.message))
                Result.retry()
            }
        }
    }

    private fun createForegroundInfo(asset: AiModelAsset, downloaded: Long, total: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel("ai_model_downloads", "AI model downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
        val notification: Notification = NotificationCompat.Builder(applicationContext, "ai_model_downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${asset.modelName}")
            .setContentText(if (total > 0L) "$percent% · ${formatBytes(downloaded)} / ${formatBytes(total)}" else formatBytes(downloaded))
            .setProgress(100, percent, total <= 0L)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(4107, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(4107, notification)
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024L) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var index = -1
        do { size /= 1024.0; index++ } while (size >= 1024.0 && index < units.lastIndex)
        return String.format(java.util.Locale.US, "%.1f %s", size, units[index])
    }
}
