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
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persistent media download queue.
 *
 * Work identity is derived from a SHA-256 digest of the canonical media URL. Multiple
 * requesting items can reference one WorkManager job, but they are registered as aliases
 * so progress/completion can be reconciled without creating a second worker.
 */
object DownloadQueueManager {

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private val _inMemoryDownloading = kotlinx.coroutines.flow.MutableStateFlow(false)
    val inMemoryDownloading: kotlinx.coroutines.flow.StateFlow<Boolean> = _inMemoryDownloading

    private val activeDownloadCount = AtomicInteger(0)
    private val activeItemIds = ConcurrentHashMap.newKeySet<String>()
    private val activeWorkIds = ConcurrentHashMap.newKeySet<String>()
    private val itemWorkIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val workItemsById = ConcurrentHashMap<String, MutableSet<String>>()
    private val uniqueWorkLocks = Array(64) { Any() }

    fun isDownloading(itemId: String): Boolean {
        if (activeItemIds.contains(itemId)) return true
        val activity = DownloadActivityManager.activities.value.firstOrNull { it.id == itemId }
        return activity?.status == DownloadActivityStatus.DOWNLOADING ||
            activity?.status == DownloadActivityStatus.RETRYING
    }

    /**
     * A collision-resistant, deterministic WorkManager key. Keep the full digest: a 32-bit
     * String.hashCode() can collide and abs(Int.MIN_VALUE) remains negative.
     */
    internal fun requestItemIdForMode(itemId: String, mediaModeOverride: String): String {
        val mode = mediaModeOverride.trim().lowercase()
        if (mode !in setOf("audio", "video", "video_only")) return itemId
        val suffix = "_$mode"
        return if (itemId.endsWith(suffix, ignoreCase = true)) itemId else "$itemId$suffix"
    }

    internal fun uniqueWorkNameForUrl(url: String, mediaModeOverride: String? = null): String {
        val canonical = MediaClassifier.deduplicationKey(url).ifBlank { url.trim() }
        val mode = mediaModeOverride?.trim()?.lowercase()?.takeIf { it in setOf("audio", "video", "video_only") }
        val identity = if (mode == null) canonical else "$canonical\nmode=$mode"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "download_$digest"
    }

    private fun isActive(state: WorkInfo.State): Boolean {
        return state == WorkInfo.State.ENQUEUED ||
            state == WorkInfo.State.RUNNING ||
            state == WorkInfo.State.BLOCKED
    }

    private fun registerActiveWork(workId: UUID, itemId: String) {
        val workKey = workId.toString()
        workItemsById.computeIfAbsent(workKey) { ConcurrentHashMap.newKeySet() }.add(itemId)
        itemWorkIds.computeIfAbsent(itemId) { ConcurrentHashMap.newKeySet() }.add(workKey)
        activeItemIds.add(itemId)

        // Count a WorkManager job once even when several item IDs alias that same job.
        if (activeWorkIds.add(workKey)) {
            activeDownloadCount.incrementAndGet()
            _inMemoryDownloading.value = true
        }
    }

    private fun unregisterActiveWork(workId: UUID) {
        val workKey = workId.toString()
        val itemIds = workItemsById.remove(workKey).orEmpty()
        itemIds.forEach { itemId ->
            itemWorkIds[itemId]?.let { ids ->
                ids.remove(workKey)
                if (ids.isEmpty()) {
                    itemWorkIds.remove(itemId, ids)
                    activeItemIds.remove(itemId)
                }
            }
        }

        if (activeWorkIds.remove(workKey)) {
            val remaining = activeDownloadCount.decrementAndGet().coerceAtLeast(0)
            activeDownloadCount.set(remaining)
            _inMemoryDownloading.value = remaining > 0
        }
    }

    /**
     * Legacy start hook retained for callers/tests that do not have a WorkManager ID.
     * New enqueue paths register against a concrete WorkManager ID instead.
     */
    fun markDownloadStarted(count: Int = 1, itemId: String? = null) {
        if (itemId != null) activeItemIds.add(itemId)
        if (count > 0) {
            activeDownloadCount.addAndGet(count)
            _inMemoryDownloading.value = true
        }
    }

    fun markDownloadCompleted(itemId: String? = null, workId: UUID? = null) {
        val resolvedWorkId = workId ?: itemId?.let { id ->
            itemWorkIds[id]?.firstOrNull()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        }
        if (resolvedWorkId != null) {
            unregisterActiveWork(resolvedWorkId)
            return
        }

        if (itemId != null) {
            // Preserve the legacy itemId counter API, but decrement only when this ID is
            // still registered. A late callback after cancellation is therefore idempotent.
            if (activeItemIds.remove(itemId)) {
                val remaining = activeDownloadCount.decrementAndGet().coerceAtLeast(0)
                activeDownloadCount.set(remaining)
                _inMemoryDownloading.value = remaining > 0
            }
            return
        }

        // Compatibility path for work started through the legacy counter API.
        val remaining = activeDownloadCount.decrementAndGet().coerceAtLeast(0)
        activeDownloadCount.set(remaining)
        _inMemoryDownloading.value = remaining > 0
    }

    data class QueuedDownloadPayload(
        val itemId: String,
        val mediaUrl: String,
        val fileName: String,
        val referer: String?,
        val title: String,
        val isVideo: Boolean,
        val isAudio: Boolean,
        val isStream: Boolean,
        val format: String,
        val stackId: String,
        val stackTitle: String,
        val sourceDomain: String,
        val sourceUrl: String,
        val sourceEngine: String,
        val downloadHeaders: Map<String, String>,
        val mediaModeOverride: String? = null
    )

    private val payloadCache = ConcurrentHashMap<String, QueuedDownloadPayload>()

    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        if (this.isEmpty() || maxBytes <= 0) return ""
        val bytes = this.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return this
        var end = minOf(this.length, maxBytes)
        while (end > 0 && this.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) {
            end = (end * 3) / 4
        }
        return this.substring(0, end)
    }

    private fun payloadFileFor(context: Context, itemId: String): java.io.File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(itemId.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }
        val dir = java.io.File(context.cacheDir, "download_work_payloads")
        if (!dir.exists()) dir.mkdirs()
        return java.io.File(dir, "payload_$digest.json")
    }

    private fun persistPayloadToDisk(context: Context, payload: QueuedDownloadPayload) {
        runCatching {
            val headersObj = JSONObject()
            payload.downloadHeaders.forEach { (k, v) -> headersObj.put(k, v) }
            val root = JSONObject().apply {
                put("itemId", payload.itemId)
                put("mediaUrl", payload.mediaUrl)
                put("fileName", payload.fileName)
                put("referer", payload.referer ?: "")
                put("title", payload.title)
                put("isVideo", payload.isVideo)
                put("isAudio", payload.isAudio)
                put("isStream", payload.isStream)
                put("format", payload.format)
                put("stackId", payload.stackId)
                put("stackTitle", payload.stackTitle)
                put("sourceDomain", payload.sourceDomain)
                put("sourceUrl", payload.sourceUrl)
                put("sourceEngine", payload.sourceEngine)
                put("mediaModeOverride", payload.mediaModeOverride ?: "")
                put("headers", headersObj)
            }
            payloadFileFor(context, payload.itemId).writeText(root.toString(), Charsets.UTF_8)
        }
    }

    fun getPayload(context: Context, itemId: String): QueuedDownloadPayload? {
        payloadCache[itemId]?.let { return it }
        return runCatching {
            val file = payloadFileFor(context, itemId)
            if (!file.exists()) return null
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val headersMap = mutableMapOf<String, String>()
            root.optJSONObject("headers")?.let { hObj ->
                val keys = hObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    headersMap[k] = hObj.optString(k, "")
                }
            }
            QueuedDownloadPayload(
                itemId = root.optString("itemId", itemId),
                mediaUrl = root.optString("mediaUrl", ""),
                fileName = root.optString("fileName", ""),
                referer = root.optString("referer", "").takeIf { it.isNotBlank() },
                title = root.optString("title", ""),
                isVideo = root.optBoolean("isVideo", false),
                isAudio = root.optBoolean("isAudio", false),
                isStream = root.optBoolean("isStream", false),
                format = root.optString("format", ""),
                stackId = root.optString("stackId", ""),
                stackTitle = root.optString("stackTitle", ""),
                sourceDomain = root.optString("sourceDomain", ""),
                sourceUrl = root.optString("sourceUrl", ""),
                sourceEngine = root.optString("sourceEngine", ""),
                downloadHeaders = headersMap,
                mediaModeOverride = root.optString("mediaModeOverride", "").takeIf { it.isNotBlank() }
            ).also {
                payloadCache[itemId] = it
            }
        }.getOrNull()
    }

    fun clearPayload(context: Context, itemId: String) {
        payloadCache.remove(itemId)
        runCatching {
            payloadFileFor(context, itemId).delete()
        }
    }

    private fun serializeHeaders(headers: Map<String, String>): String? {
        if (headers.isEmpty()) return null
        return runCatching {
            val json = JSONObject()
            headers.forEach { (k, v) ->
                // Guard against exceeding WorkManager's input Data size limit.
                if (k.length <= 128 && v.length <= 1024) json.put(k, v)
            }
            json.toString().takeIf { it.toByteArray(Charsets.UTF_8).size <= 1500 }
        }.getOrNull()
    }

    private fun buildRequest(
        item: ScrapedMediaItem,
        referer: String?,
        stackId: String,
        stackTitle: String,
        sourceDomain: String,
        sourceUrl: String,
        mediaModeOverride: String? = null
    ): androidx.work.OneTimeWorkRequest {
        val payload = QueuedDownloadPayload(
            itemId = item.id,
            mediaUrl = item.url,
            fileName = item.title,
            referer = referer,
            title = item.title,
            isVideo = item.isVideo,
            isAudio = item.isAudio,
            isStream = item.isStream,
            format = item.format,
            stackId = stackId,
            stackTitle = stackTitle,
            sourceDomain = sourceDomain,
            sourceUrl = sourceUrl,
            sourceEngine = item.provenance.extractorEngine,
            downloadHeaders = item.downloadHeaders,
            mediaModeOverride = mediaModeOverride?.trim()?.lowercase()?.takeIf { it in setOf("audio", "video", "video_only") }
        )
        payloadCache[item.id] = payload

        val headersJson = serializeHeaders(item.downloadHeaders)
        // WorkManager enforces a strict 10,240-byte limit on serialized Data.
        // Keep inline Data fields bounded; MediaDownloadWorker reads the full untruncated
        // payload from DownloadQueueManager.getPayload(context, itemId).
        val inlineMediaUrl = if (item.url.toByteArray(Charsets.UTF_8).size <= 3500) item.url else ""
        val inlineSourceUrl = if (sourceUrl.toByteArray(Charsets.UTF_8).size <= 800) sourceUrl else inlineMediaUrl.takeUtf8Bytes(800)
        val inlineReferer = referer?.takeIf { it.toByteArray(Charsets.UTF_8).size <= 800 }

        val inputData = Data.Builder()
            .putString(MediaDownloadWorker.KEY_MEDIA_URL, inlineMediaUrl)
            .putString(MediaDownloadWorker.KEY_FILE_NAME, item.title.takeUtf8Bytes(200))
            .putString(MediaDownloadWorker.KEY_REFERER, inlineReferer)
            .putString(MediaDownloadWorker.KEY_ITEM_ID, item.id.takeUtf8Bytes(256))
            .putString(MediaDownloadWorker.KEY_TITLE, item.title.takeUtf8Bytes(200))
            .putBoolean(MediaDownloadWorker.KEY_IS_VIDEO, item.isVideo)
            .putBoolean(MediaDownloadWorker.KEY_IS_AUDIO, item.isAudio)
            .putBoolean(MediaDownloadWorker.KEY_IS_STREAM, item.isStream)
            .putString(MediaDownloadWorker.KEY_FORMAT, item.format.takeUtf8Bytes(64))
            .putString(MediaDownloadWorker.KEY_STACK_ID, stackId.takeUtf8Bytes(128))
            .putString(MediaDownloadWorker.KEY_STACK_TITLE, stackTitle.takeUtf8Bytes(200))
            .putString(MediaDownloadWorker.KEY_SOURCE_DOMAIN, sourceDomain.takeUtf8Bytes(200))
            .putString(MediaDownloadWorker.KEY_SOURCE_URL, inlineSourceUrl)
            .putString(MediaDownloadWorker.KEY_SOURCE_ENGINE, item.provenance.extractorEngine.takeUtf8Bytes(128))
            .putString(MediaDownloadWorker.KEY_MEDIA_MODE_OVERRIDE, payload.mediaModeOverride)
            .apply {
                if (!headersJson.isNullOrBlank()) {
                    putString(MediaDownloadWorker.KEY_HEADERS_JSON, headersJson)
                }
            }
            .build()

        return OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setConstraints(networkConstraints)
            .setInputData(inputData)
            .addTag(MediaDownloadWorker.WORK_TAG_DOWNLOAD)
            .addTag("item_${item.id.takeUtf8Bytes(120)}")
            .build()
    }

    fun enqueueDownload(
        context: Context,
        item: ScrapedMediaItem,
        referer: String? = null,
        stackId: String? = null,
        stackTitle: String? = null,
        sourceDomain: String? = null,
        sourceUrl: String? = null,
        isRetry: Boolean = false,
        mediaModeOverride: String? = null
    ): UUID {
        val sId = stackId ?: "stack_${System.currentTimeMillis()}"
        val sTitle = stackTitle ?: sourceDomain ?: "Discovered Media"
        val domain = sourceDomain ?: ""
        val url = sourceUrl ?: item.url

        DownloadActivityManager.initialize(context)
        val workManager = WorkManager.getInstance(context)
        val normalizedModeOverride = mediaModeOverride?.trim()?.lowercase()?.takeIf { it in setOf("audio", "video", "video_only") }
        // The same discovered item can be queued once as video and once as audio.
        // Keep the mode suffix idempotent because retries pass the already-suffixed activity ID.
        val requestItem = if (normalizedModeOverride == null) item else item.copy(
            id = requestItemIdForMode(item.id, normalizedModeOverride)
        )
        val uniqueWorkName = uniqueWorkNameForUrl(requestItem.url, normalizedModeOverride)
        val lock = uniqueWorkLocks[(uniqueWorkName.hashCode() and Int.MAX_VALUE) % uniqueWorkLocks.size]

        return synchronized(lock) {
            val activeBefore = runCatching {
                workManager.getWorkInfosForUniqueWork(uniqueWorkName).get()
            }.getOrDefault(emptyList()).firstOrNull { isActive(it.state) }

            if (activeBefore != null) {
                // This request aliases the existing file download. Do not create a second
                // activity row: the existing WorkManager worker owns progress and completion.
                registerActiveWork(activeBefore.id, requestItem.id)

                // Work may complete between the unique-work query and alias registration.
                // Re-check its individual ID so a late alias cannot leave a phantom active count.
                val confirmedState = runCatching {
                    workManager.getWorkInfoById(activeBefore.id).get()?.state
                }.getOrNull()
                if (confirmedState == null || isActive(confirmedState)) {
                    return@synchronized activeBefore.id
                }

                // The worker completed between the first lookup and this confirmation.
                // Remove its aliases and fall through to enqueue a fresh request.
                unregisterActiveWork(activeBefore.id)
            }

            val request = buildRequest(requestItem, referer, sId, sTitle, domain, url, normalizedModeOverride)
            payloadCache[requestItem.id]?.let { persistPayloadToDisk(context, it) }

            val safeThumbnailUrl = requestItem.thumbnailUrl?.takeIf {
                it.isNotBlank() && !com.amresalehin.emregallery.ui.ThumbnailUtils.isRemoteNonImageUrl(it)
            } ?: requestItem.url.takeIf {
                !requestItem.isVideo && !requestItem.isAudio && !requestItem.isStream &&
                    !com.amresalehin.emregallery.ui.ThumbnailUtils.isRemoteNonImageUrl(it)
            }

            // Record the owning activity before enqueueing so a fast worker cannot finish
            // before its activity is created.
            DownloadActivityManager.recordQueued(
                id = requestItem.id,
                title = requestItem.title,
                mediaUrl = requestItem.url,
                thumbnailUrl = safeThumbnailUrl,
                isVideo = requestItem.isVideo,
                isAudio = requestItem.isAudio,
                isStream = requestItem.isStream,
                format = requestItem.format,
                workId = request.id.toString(),
                isRetry = isRetry,
                stackId = sId,
                stackTitle = sTitle,
                sourceDomain = domain,
                sourceUrl = url,
                mediaModeOverride = normalizedModeOverride
            )
            registerActiveWork(request.id, requestItem.id)

            try {
                // KEEP never cancels an unfinished worker. A completed/failed unique-work
                // chain is eligible for a new request, so REPLACE is not needed for retry.
                workManager.enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.KEEP, request)
                    .result.get()

                val actualWork = runCatching {
                    workManager.getWorkInfosForUniqueWork(uniqueWorkName).get()
                }.getOrDefault(emptyList()).firstOrNull { isActive(it.state) }

                if (actualWork != null && actualWork.id != request.id) {
                    // Another process/request won the WorkManager race. Keep the canonical
                    // worker as owner and remove the transient activity row for this request.
                    unregisterActiveWork(request.id)
                    registerActiveWork(actualWork.id, requestItem.id)
                    DownloadActivityManager.deleteActivity(requestItem.id)
                    actualWork.id
                } else {
                    if (actualWork == null) unregisterActiveWork(request.id)
                    actualWork?.id ?: request.id
                }
            } catch (e: Exception) {
                unregisterActiveWork(request.id)
                DownloadActivityManager.markFailed(requestItem.id, e.message ?: "Could not enqueue download")
                throw e
            }
        }
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
        // Use the same unique-work, alias-registration and retry semantics as single downloads.
        val distinctItems = MediaClassifier.deduplicateScrapedItems(items)
        return distinctItems.map { item ->
            enqueueDownload(
                context = context,
                item = item,
                referer = referer,
                stackId = stackId,
                stackTitle = stackTitle,
                sourceDomain = sourceDomain,
                sourceUrl = sourceUrl
            )
        }
    }

    fun getActiveDownloadsFlow(context: Context): Flow<List<WorkInfo>> {
        return WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(MediaDownloadWorker.WORK_TAG_DOWNLOAD)
    }

    fun cancelDownload(context: Context, workId: UUID) {
        runCatching {
            WorkManager.getInstance(context).cancelWorkById(workId)
        }
        // Cancellation is idempotent; the worker's finally block may call this again.
        unregisterActiveWork(workId)
    }

    fun cancelAll(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelAllWorkByTag(MediaDownloadWorker.WORK_TAG_DOWNLOAD)
        }
        activeItemIds.clear()
        activeWorkIds.clear()
        itemWorkIds.clear()
        workItemsById.clear()
        activeDownloadCount.set(0)
        _inMemoryDownloading.value = false
    }
}
