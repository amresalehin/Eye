package com.amresalehin.emregallery.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Manages persistent media downloads through Android Jetpack WorkManager.
 * Schedules, tracks, and cancels background downloads with network constraints.
 * Keys unique work by deduped URL and avoids blocking the Main thread on WorkManager futures.
 */
object DownloadQueueManager {

    fun buildNetworkConstraints(context: Context): Constraints {
        val wifiOnly = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.isWifiOnlySync(context)
        return Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
    }

    private val _inMemoryDownloading = kotlinx.coroutines.flow.MutableStateFlow(false)
    val inMemoryDownloading: kotlinx.coroutines.flow.StateFlow<Boolean> = _inMemoryDownloading

    private val activeDownloadCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var configuredMaxConcurrent = 3
    @Volatile
    private var downloadConcurrencySemaphore = kotlinx.coroutines.sync.Semaphore(3)
    private val semaphoreLock = Any()

    suspend fun <T> withConcurrencyLimit(context: Context, block: suspend () -> T): T {
        val desiredMax = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.getMaxConcurrentDownloadsSync(context)
        val sem = synchronized(semaphoreLock) {
            if (desiredMax != configuredMaxConcurrent && activeDownloadCount.get() <= 1) {
                configuredMaxConcurrent = desiredMax
                downloadConcurrencySemaphore = kotlinx.coroutines.sync.Semaphore(desiredMax)
            }
            downloadConcurrencySemaphore
        }
        return sem.withPermit { block() }
    }
    private val activeItemIds = ConcurrentHashMap.newKeySet<String>()
    private val activeWorkIdsByUniqueName = ConcurrentHashMap<String, UUID>()
    private val uniqueNameByItemId = ConcurrentHashMap<String, String>()
    // Allows cancellation of work that has not started yet to release its reservation.
    // Worker completion and cancellation race through activeItemIds so only one wins.
    private val itemIdByWorkId = ConcurrentHashMap<UUID, String>()

    fun isDownloading(itemId: String): Boolean {
        if (activeItemIds.contains(itemId)) return true
        val activity = DownloadActivityManager.activities.value.firstOrNull { it.id == itemId }
        return activity?.status == DownloadActivityStatus.DOWNLOADING || activity?.status == DownloadActivityStatus.RETRYING
    }

    fun markDownloadStarted(count: Int = 1, itemId: String? = null, workId: UUID? = null) {
        if (itemId != null) {
            // A repeated enqueue for the same item must not reserve another active slot.
            if (!activeItemIds.add(itemId)) return
            if (workId != null) itemIdByWorkId[workId] = itemId
        }
        activeDownloadCount.addAndGet(if (itemId == null) count else 1)
        _inMemoryDownloading.value = activeDownloadCount.get() > 0
    }

    fun markDownloadCompleted(itemId: String? = null) {
        if (itemId != null) {
            // Cancellation and the Worker's finally block may both complete the same item.
            // Only the call that removes the active reservation is allowed to decrement.
            if (!activeItemIds.remove(itemId)) return
            itemIdByWorkId.entries.removeIf { it.value == itemId }
            uniqueNameByItemId.remove(itemId)?.let { uniqueName ->
                activeWorkIdsByUniqueName.remove(uniqueName)
            }
        }
        val remaining = activeDownloadCount.decrementAndGet().coerceAtLeast(0)
        activeDownloadCount.set(remaining)
        _inMemoryDownloading.value = remaining > 0
    }

    private fun serializeHeaders(headers: Map<String, String>): String? {
        if (headers.isEmpty()) return null
        return runCatching {
            val json = JSONObject()
            headers.forEach { (k, v) ->
                // Guard against exceeding the 10KB WorkManager Data limit by capping individual header values
                if (v.length <= 2048) {
                    json.put(k, v)
                }
            }
            json.toString().takeIf { it.length <= 4096 }
        }.getOrNull()
    }

    fun enqueueDownload(
        context: Context,
        item: ScrapedMediaItem,
        referer: String? = null,
        stackId: String? = null,
        stackTitle: String? = null,
        sourceDomain: String? = null,
        sourceUrl: String? = null,
        isRetry: Boolean = false
    ): UUID {
        val sId = stackId ?: "stack_${System.currentTimeMillis()}"
        val sTitle = stackTitle ?: sourceDomain ?: "Discovered Media"
        val domain = sourceDomain ?: ""
        val url = sourceUrl ?: item.url
        val headersJson = serializeHeaders(item.downloadHeaders)

        val inputData = Data.Builder()
            .putString(MediaDownloadWorker.KEY_MEDIA_URL, item.url)
            .putString(MediaDownloadWorker.KEY_FILE_NAME, item.title)
            .putString(MediaDownloadWorker.KEY_REFERER, referer)
            .putString(MediaDownloadWorker.KEY_ITEM_ID, item.id)
            .putString(MediaDownloadWorker.KEY_TITLE, item.title)
            .putBoolean(MediaDownloadWorker.KEY_IS_VIDEO, item.isVideo)
            .putBoolean(MediaDownloadWorker.KEY_IS_AUDIO, item.isAudio)
            .putBoolean(MediaDownloadWorker.KEY_IS_STREAM, item.isStream)
            .putString(MediaDownloadWorker.KEY_FORMAT, item.format)
            .putString(MediaDownloadWorker.KEY_STACK_ID, sId)
            .putString(MediaDownloadWorker.KEY_STACK_TITLE, sTitle)
            .putString(MediaDownloadWorker.KEY_SOURCE_DOMAIN, domain)
            .putString(MediaDownloadWorker.KEY_SOURCE_URL, url)
            .putString(MediaDownloadWorker.KEY_SOURCE_ENGINE, item.provenance.extractorEngine)
            .apply {
                if (!headersJson.isNullOrBlank()) {
                    putString(MediaDownloadWorker.KEY_HEADERS_JSON, headersJson)
                }
            }
            .build()

        val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setConstraints(buildNetworkConstraints(context))
            .setInputData(inputData)
            .addTag(MediaDownloadWorker.WORK_TAG_DOWNLOAD)
            .addTag("item_${item.id}")
            .build()

        val dedupKey = MediaClassifier.deduplicationKey(item.url)
        val uniqueWorkName = "download_${abs(dedupKey.hashCode())}"

        val workManager = WorkManager.getInstance(context)
        val existingWorkId = activeWorkIdsByUniqueName[uniqueWorkName]
        val isAlreadyActiveInMemory = existingWorkId != null && activeItemIds.contains(item.id) && !isRetry

        val finalWorkId: UUID = if (isAlreadyActiveInMemory && existingWorkId != null) {
            existingWorkId
        } else {
            activeWorkIdsByUniqueName[uniqueWorkName] = request.id
            uniqueNameByItemId[item.id] = uniqueWorkName
            workManager.enqueueUniqueWork(
                uniqueWorkName,
                if (isRetry) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
            markDownloadStarted(count = 1, itemId = item.id, workId = request.id)
            request.id
        }

        DownloadActivityManager.initialize(context)
        DownloadActivityManager.recordQueued(
            id = item.id,
            title = item.title,
            mediaUrl = item.url,
            thumbnailUrl = item.thumbnailUrl ?: item.url,
            isVideo = item.isVideo,
            isAudio = item.isAudio,
            isStream = item.isStream,
            format = item.format,
            workId = finalWorkId.toString(),
            isRetry = isRetry,
            stackId = sId,
            stackTitle = sTitle,
            sourceDomain = domain,
            sourceUrl = url,
            sourceEngine = item.provenance.extractorEngine,
            downloadHeaders = item.downloadHeaders
        )

        return finalWorkId
    }

    fun enqueueBatch(
        context: Context,
        items: List<ScrapedMediaItem>,
        referer: String? = null,
        stackId: String? = null,
        stackTitle: String? = null,
        sourceDomain: String? = null,
        sourceUrl: String? = null
    ): List<UUID> {
        val sId = stackId ?: "stack_${System.currentTimeMillis()}"
        val sTitle = stackTitle ?: sourceDomain ?: "Discovered Media"
        val domain = sourceDomain ?: ""
        val url = sourceUrl ?: referer ?: ""

        val workManager = WorkManager.getInstance(context)
        val distinctItems = MediaClassifier.deduplicateScrapedItems(items)

        val workIds = distinctItems.map { item ->
            val headersJson = serializeHeaders(item.downloadHeaders)
            val inputData = Data.Builder()
                .putString(MediaDownloadWorker.KEY_MEDIA_URL, item.url)
                .putString(MediaDownloadWorker.KEY_FILE_NAME, item.title)
                .putString(MediaDownloadWorker.KEY_REFERER, referer)
                .putString(MediaDownloadWorker.KEY_ITEM_ID, item.id)
                .putString(MediaDownloadWorker.KEY_TITLE, item.title)
                .putBoolean(MediaDownloadWorker.KEY_IS_VIDEO, item.isVideo)
                .putBoolean(MediaDownloadWorker.KEY_IS_AUDIO, item.isAudio)
                .putBoolean(MediaDownloadWorker.KEY_IS_STREAM, item.isStream)
                .putString(MediaDownloadWorker.KEY_FORMAT, item.format)
                .putString(MediaDownloadWorker.KEY_STACK_ID, sId)
                .putString(MediaDownloadWorker.KEY_STACK_TITLE, sTitle)
                .putString(MediaDownloadWorker.KEY_SOURCE_DOMAIN, domain)
                .putString(MediaDownloadWorker.KEY_SOURCE_URL, url)
                .putString(MediaDownloadWorker.KEY_SOURCE_ENGINE, item.provenance.extractorEngine)
                .apply {
                    if (!headersJson.isNullOrBlank()) {
                        putString(MediaDownloadWorker.KEY_HEADERS_JSON, headersJson)
                    }
                }
                .build()

            val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
                .setConstraints(buildNetworkConstraints(context))
                .setInputData(inputData)
                .addTag(MediaDownloadWorker.WORK_TAG_DOWNLOAD)
                .addTag("item_${item.id}")
                .build()

            val dedupKey = MediaClassifier.deduplicationKey(item.url)
            val uniqueWorkName = "download_${abs(dedupKey.hashCode())}"

            val existingWorkId = activeWorkIdsByUniqueName[uniqueWorkName]
            if (existingWorkId != null && activeItemIds.contains(item.id)) {
                existingWorkId
            } else {
                activeWorkIdsByUniqueName[uniqueWorkName] = request.id
                uniqueNameByItemId[item.id] = uniqueWorkName
                workManager.enqueueUniqueWork(
                    uniqueWorkName,
                    ExistingWorkPolicy.KEEP,
                    request
                )
                markDownloadStarted(count = 1, itemId = item.id, workId = request.id)
                request.id
            }
        }

        DownloadActivityManager.initialize(context)
        DownloadActivityManager.recordBatchQueued(
            items = distinctItems,
            workIds = workIds,
            stackId = sId,
            stackTitle = sTitle,
            sourceDomain = domain,
            sourceUrl = url
        )

        return workIds
    }

    fun getActiveDownloadsFlow(context: Context): Flow<List<WorkInfo>> {
        return WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(MediaDownloadWorker.WORK_TAG_DOWNLOAD)
    }

    fun cancelDownload(context: Context, workId: UUID) {
        runCatching {
            WorkManager.getInstance(context).cancelWorkById(workId)
        }
        // If the worker has already started, its finally block will complete the item.
        // If it has not started, release the reservation here. The keyed completion guard
        // makes this safe when cancellation races with worker shutdown.
        itemIdByWorkId[workId]?.let(::markDownloadCompleted)
    }

    fun cancelAll(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelAllWorkByTag(MediaDownloadWorker.WORK_TAG_DOWNLOAD)
        }
        activeItemIds.clear()
        activeWorkIdsByUniqueName.clear()
        uniqueNameByItemId.clear()
        itemIdByWorkId.clear()
        activeDownloadCount.set(0)
        _inMemoryDownloading.value = false
    }
}
