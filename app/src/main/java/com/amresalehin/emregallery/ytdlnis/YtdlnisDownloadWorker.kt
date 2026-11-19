package com.amresalehin.emregallery.ytdlnis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.amresalehin.emregallery.data.DownloadActivityManager
import com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * WorkManager worker for executing YTDLnis downloads (immediate, queued, or scheduled
 * by date/time) with foreground notifications and Gallery MediaScanner integration.
 */
class YtdlnisDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: id.toString()
        val title = inputData.getString(KEY_TITLE) ?: "YTDLnis Download"
        return buildForegroundInfo(itemId, title, 0)
    }

    private fun buildForegroundInfo(itemId: String, title: String, progressPercent: Int): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "YTDLnis Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Live progress for YTDLnis audio/video downloads"
                        setSound(null, null)
                        enableVibration(false)
                    }
                )
            }
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("YTDLnis · ${if (progressPercent > 0) "$progressPercent%" else "Preparing"}")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progressPercent.coerceIn(0, 100), progressPercent <= 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

        val notificationId = 7100 + (abs(itemId.hashCode()) % 10000)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    override suspend fun doWork(): Result {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return Result.failure()
        val repo = YtdlnisRepository.getInstance(applicationContext)
        val item = repo.getDownloadById(itemId) ?: return Result.failure()
        val actId = "ytdlnis_${item.id}"

        runCatching { setForeground(buildForegroundInfo(itemId, item.title.ifBlank { item.url }, 5)) }

        repo.updateDownload(itemId) {
            it.copy(status = YtdlnisDownloadStatus.ACTIVE, progress = 0.05f, errorMessage = null)
        }

        if (!item.incognito) {
            runCatching {
                DownloadActivityManager.initialize(applicationContext)
                DownloadActivityManager.recordQueued(
                    id = actId,
                    title = item.title.ifBlank { item.url },
                    mediaUrl = item.url,
                    thumbnailUrl = item.thumbnailUrl.ifBlank { null },
                    isVideo = item.type == YtdlnisDownloadType.VIDEO,
                    isAudio = item.type == YtdlnisDownloadType.AUDIO,
                    format = item.container.uppercase().ifBlank { if (item.type == YtdlnisDownloadType.AUDIO) "MP3" else "MP4" },
                    workId = id.toString(),
                    sourceDomain = item.extractor.ifBlank { YtdlnisRepository.extractHost(item.url) },
                    sourceUrl = item.url
                )
            }
        }

        val result = YtdlnisEngine.downloadAndProcess(
            context = applicationContext,
            item = item,
            onStatusUpdate = { progress, _ ->
                repo.updateDownload(itemId) {
                    it.copy(status = YtdlnisDownloadStatus.ACTIVE, progress = progress)
                }
                if (!item.incognito) {
                    runCatching {
                        DownloadActivityManager.updateProgress(
                            itemId = actId,
                            progress = progress.coerceIn(0.01f, 0.99f),
                            bytesRead = 0L,
                            totalBytes = 0L,
                            speedBytesPerSec = 0L
                        )
                    }
                }
                runCatching {
                    setForegroundAsync(buildForegroundInfo(itemId, item.title.ifBlank { item.url }, (progress * 100).toInt()))
                }
            }
        )

        return if (result.success && result.outputFiles.isNotEmpty()) {
            val primary = result.outputFiles.first()
            val paths = result.outputFiles.map { it.absolutePath }.toTypedArray()
            MediaScannerConnection.scanFile(applicationContext, paths, null, null)

            repo.updateDownload(itemId) {
                it.copy(
                    status = YtdlnisDownloadStatus.COMPLETED,
                    progress = 1f,
                    outputFilePath = primary.absolutePath,
                    logOutput = if (it.incognito) "" else result.logs,
                    errorMessage = null
                )
            }

            if (!item.incognito) {
                runCatching {
                    DownloadActivityManager.initialize(applicationContext)
                    DownloadActivityManager.recordQueued(
                        id = actId,
                        title = item.title.ifBlank { primary.name },
                        mediaUrl = item.url,
                        thumbnailUrl = item.thumbnailUrl.ifBlank { null },
                        isVideo = item.type == YtdlnisDownloadType.VIDEO,
                        isAudio = item.type == YtdlnisDownloadType.AUDIO,
                        format = primary.extension.uppercase().ifBlank { item.container.uppercase() },
                        workId = id.toString(),
                        sourceDomain = item.extractor.ifBlank { YtdlnisRepository.extractHost(item.url) },
                        sourceUrl = item.url
                    )
                    DownloadActivityManager.markFinished(
                        itemId = actId,
                        localUri = primary.absolutePath,
                        sizeBytes = primary.length()
                    )
                }
            }
            Result.success()
        } else {
            val errMsg = result.errorMessage ?: "Download failed"
            repo.updateDownload(itemId) {
                it.copy(
                    status = YtdlnisDownloadStatus.ERROR,
                    logOutput = if (it.incognito) "" else result.logs,
                    errorMessage = errMsg
                )
            }
            if (!item.incognito) {
                runCatching {
                    DownloadActivityManager.initialize(applicationContext)
                    DownloadActivityManager.markFailed(actId, errMsg)
                }
            }
            Result.failure()
        }
    }

    companion object {
        private const val CHANNEL_ID = "emre_ytdlnis_downloads"
        const val KEY_ITEM_ID = "ytdlnis_item_id"
        const val KEY_TITLE = "ytdlnis_title"

        fun enqueue(context: Context, item: YtdlnisDownloadItem): UUID {
            val repo = YtdlnisRepository.getInstance(context)
            val now = System.currentTimeMillis()
            val delayMs = (item.scheduledTimeMillis - now).coerceAtLeast(0L)
            val initialStatus = if (delayMs > 1000L) YtdlnisDownloadStatus.SCHEDULED else YtdlnisDownloadStatus.QUEUED

            val wifiOnly = DownloaderPreferencesManager.isWifiOnlySync(context)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()

            val requestBuilder = OneTimeWorkRequestBuilder<YtdlnisDownloadWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_ITEM_ID to item.id.take(256),
                        KEY_TITLE to item.title.ifBlank { item.url }.take(200)
                    )
                )
                .addTag("ytdlnis_download")
                .addTag("ytdlnis_${item.id.take(120)}")

            if (delayMs > 1000L) {
                requestBuilder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            }

            val request = requestBuilder.build()
            repo.upsertDownload(
                item.copy(
                    status = initialStatus,
                    workId = request.id.toString(),
                    errorMessage = null
                )
            )

            if (!item.incognito && delayMs <= 1000L) {
                runCatching {
                    DownloadActivityManager.initialize(context)
                    DownloadActivityManager.recordQueued(
                        id = "ytdlnis_${item.id}",
                        title = item.title.ifBlank { item.url },
                        mediaUrl = item.url,
                        thumbnailUrl = item.thumbnailUrl.ifBlank { null },
                        isVideo = item.type == YtdlnisDownloadType.VIDEO,
                        isAudio = item.type == YtdlnisDownloadType.AUDIO,
                        format = item.container.uppercase().ifBlank { if (item.type == YtdlnisDownloadType.AUDIO) "MP3" else "MP4" },
                        workId = request.id.toString(),
                        sourceDomain = item.extractor.ifBlank { YtdlnisRepository.extractHost(item.url) },
                        sourceUrl = item.url
                    )
                }
            }

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "ytdlnis_job_${item.id}",
                ExistingWorkPolicy.REPLACE,
                request
            )
            return request.id
        }

        fun cancel(context: Context, item: YtdlnisDownloadItem) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork("ytdlnis_job_${item.id}")
            YtdlnisRepository.getInstance(context).updateDownload(item.id) {
                it.copy(status = YtdlnisDownloadStatus.CANCELLED)
            }
            runCatching {
                DownloadActivityManager.initialize(context)
                DownloadActivityManager.markFailed("ytdlnis_${item.id}", "Cancelled by user")
            }
        }
    }
}

/**
 * Periodic worker implementing YTDLnis "Observe Sources":
 * watches configured playlists/channels and automatically enqueues newly uploaded items.
 */
class YtdlnisObserveWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = YtdlnisRepository.getInstance(applicationContext)
        val enabledSources = repo.observeSources.value.filter { it.enabled }
        for (source in enabledSources) {
            try {
                val fetched = YtdlnisEngine.fetchInfo(
                    context = applicationContext,
                    urlOrQuery = source.url,
                    allowPlaylist = true,
                    flatPlaylist = true
                )
                if (!fetched.success) continue
                val knownIds = source.downloadedVideoIds.toMutableSet()
                val newEntries = fetched.entries.filter { it.id.isNotBlank() && it.id !in knownIds }
                newEntries.take(10).forEach { entry ->
                    val item = YtdlnisDownloadItem(
                        url = entry.url,
                        title = entry.title,
                        author = entry.author,
                        durationSec = entry.durationSec,
                        durationString = entry.durationString,
                        thumbnailUrl = entry.thumbnail,
                        extractor = entry.extractor,
                        playlistTitle = source.name,
                        type = source.downloadType,
                        container = if (source.downloadType == YtdlnisDownloadType.AUDIO) "m4a" else "mp4",
                        subfolderMode = YtdlnisSubfolderMode.BY_PLAYLIST
                    )
                    YtdlnisDownloadWorker.enqueue(applicationContext, item)
                    knownIds.add(entry.id)
                }
                repo.upsertObserveSource(
                    source.copy(
                        lastCheckedMillis = System.currentTimeMillis(),
                        downloadedVideoIds = knownIds
                    )
                )
            } catch (t: Throwable) {
                Log.w("YtdlnisObserveWorker", "Observe failed for ${source.name}: ${t.message}")
            }
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "ytdlnis_observe_sources_periodic"

        fun schedulePeriodic(context: Context, intervalHours: Long = 12L) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<YtdlnisObserveWorker>(
                intervalHours.coerceAtLeast(1L),
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .addTag("ytdlnis_observe")
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }
    }
}
