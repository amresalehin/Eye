package com.amresalehin.emregallery.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.StatFs
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import coil.Coil
import coil.memory.MemoryCache
import com.amresalehin.emregallery.ui.ThumbnailUtils
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Disk-backed thumbnail preparation for the gallery.
 *
 * The UI continues to draw each cell immediately. This store keeps decoded thumbnails out of
 * long-lived RAM: requests first reuse a prepared WebP file, while a background job gradually
 * prepares every MediaStore item after permission is granted.
 */
object PreparedThumbnailCache {
    private const val DIRECTORY_NAME = "prepared_thumbnails"
    private const val FREE_SPACE_RESERVE_BYTES = 128L * 1024L * 1024L
    private const val MAX_PREPARED_CACHE_BYTES = 512L * 1024L * 1024L
    private const val PRUNE_TARGET_BYTES = 384L * 1024L * 1024L
    private const val EXPECTED_THUMBNAIL_BYTES = 512L * 1024L
    private const val PRUNE_CHECK_INTERVAL = 64
    private const val WEBP_QUALITY = 80
    private const val PREWARM_WIDTH = 420
    private const val PREWARM_HEIGHT = 420

    // Fixed stripes avoid retaining one Mutex and URI-sized key per photo for large libraries.
    private val uriLocks = Array(256) { Mutex() }
    private val pruneLock = Any()
    private val writesSincePrune = AtomicInteger(0)

    data class ThumbnailResult(val bitmap: Bitmap)

    fun hasPrepared(context: Context, uri: String, widthPx: Int = PREWARM_WIDTH, heightPx: Int = PREWARM_HEIGHT): Boolean {
        val file = cacheFile(context.applicationContext, uri, widthPx, heightPx)
        // Keep the background pass cheap: full decode/validation occurs only on a cache hit
        // that is actually requested by Coil. Corrupt entries are removed by readPrepared().
        return file.isFile && file.length() > 0L
    }

    /**
     * Makes room for another prepared thumbnail when necessary. The prepared cache is bounded
     * independently of Coil's cache, and old entries are evicted least-recently-used first.
     */
    fun hasStorageHeadroom(context: Context): Boolean =
        ensureCacheCapacity(context.applicationContext, EXPECTED_THUMBNAIL_BYTES)

    /** Run once when the media inventory is refreshed so old cache growth is reclaimed early. */
    fun maintainCache(context: Context) {
        trimPreparedCache(context.applicationContext, EXPECTED_THUMBNAIL_BYTES)
    }

    /**
     * Returns a prepared thumbnail when present. On a miss, creates the Android MediaStore
     * thumbnail on IO and atomically writes a compact WebP copy, unless the free-space reserve
     * would be violated. Per-URI/size locks prevent the UI request and background prewarmer from
     * generating the same thumbnail concurrently.
     */
    suspend fun loadOrCreate(
        context: Context,
        uri: Uri,
        widthPx: Int,
        heightPx: Int
    ): ThumbnailResult? {
        val appContext = context.applicationContext
        val (targetWidth, targetHeight) = normalizedDimensions(widthPx, heightPx)
        val key = cacheKey(uri.toString(), targetWidth, targetHeight)
        val file = File(cacheDirectory(appContext), "$key.webp")

        readPrepared(file)?.let { return ThumbnailResult(it) }

        val stripeIndex = (key.hashCode() and Int.MAX_VALUE) % uriLocks.size
        val mutex = uriLocks[stripeIndex]
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            readPrepared(file)?.let { return@withLock ThumbnailResult(it) }

            val bitmap = loadMediaStoreThumbnail(appContext, uri, targetWidth, targetHeight)
                ?: return@withLock null
            if (bitmap.width <= 0 || bitmap.height <= 0) {
                return@withLock null
            }
            writePreparedIfSafe(appContext, file, bitmap)
            ThumbnailResult(bitmap)
        }
    }

    /**
     * Prepares one item and immediately releases its bitmap. This intentionally does not warm
     * every decoded image into Coil's memory cache, which would make large libraries consume
     * unbounded RAM and cause memory pressure/GC during scrolling.
     */
    suspend fun prepareIfMissing(context: Context, uri: String): Boolean {
        if (hasPrepared(context, uri)) return true

        val result = loadOrCreate(context, Uri.parse(uri), PREWARM_WIDTH, PREWARM_HEIGHT)
        result?.bitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        return hasPrepared(context, uri)
    }

    private fun cacheDirectory(context: Context): File =
        File(context.cacheDir, DIRECTORY_NAME)

    private fun cacheFile(context: Context, uri: String, widthPx: Int, heightPx: Int): File {
        val (normalizedWidth, normalizedHeight) = normalizedDimensions(widthPx, heightPx)
        val key = cacheKey(uri, normalizedWidth, normalizedHeight)
        return File(cacheDirectory(context), "$key.webp")
    }

    /**
     * Use one shared 420px-bounded thumbnail for ordinary grid/card requests. This lets the
     * startup prewarm feed Home, Albums, Search, and other small-thumbnail layouts without
     * creating separate 240/360/420px disk copies of the same media.
     */
    private fun normalizedDimensions(widthPx: Int, heightPx: Int): Pair<Int, Int> {
        val width = widthPx.coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)
        val largest = maxOf(width, height)
        return if (largest in 160..PREWARM_WIDTH) {
            PREWARM_WIDTH to PREWARM_HEIGHT
        } else {
            width.coerceIn(96, 512) to height.coerceIn(96, 512)
        }
    }

    private fun cacheKey(uri: String, widthPx: Int, heightPx: Int): String {
        val normalizedWidth = widthPx.coerceIn(96, 512)
        val normalizedHeight = heightPx.coerceIn(96, 512)
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$uri|$normalizedWidth|$normalizedHeight".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun readPrepared(file: File): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        if (bitmap == null) {
            runCatching { file.delete() }
        } else {
            runCatching { file.setLastModified(System.currentTimeMillis()) }
        }
        return bitmap
    }

    private fun writePreparedIfSafe(context: Context, file: File, bitmap: Bitmap) {
        if (!ensureCacheCapacity(context, EXPECTED_THUMBNAIL_BYTES)) return
        val parent = file.parentFile ?: return
        if (!parent.exists() && !parent.mkdirs()) return

        val temporary = File(parent, file.nameWithoutExtension + ".tmp")
        runCatching {
            temporary.delete()
            FileOutputStream(temporary).use { stream ->
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                if (!bitmap.compress(format, WEBP_QUALITY, stream)) {
                    throw IllegalStateException("Could not encode prepared thumbnail")
                }
            }

            if (!temporary.renameTo(file)) {
                // A prior file can exist after an interrupted write or concurrent process restart.
                file.delete()
                if (!temporary.renameTo(file)) {
                    temporary.delete()
                    return@runCatching
                }
            }
            file.setLastModified(System.currentTimeMillis())
        }
        if (temporary.exists()) runCatching { temporary.delete() }
    }

    /**
     * Avoid a full directory scan for every thumbnail. Check capacity on a small cadence, but
     * prune immediately when the device approaches its free-space reserve.
     */
    private fun ensureCacheCapacity(context: Context, requiredBytes: Long): Boolean {
        val writeCount = writesSincePrune.incrementAndGet()
        val available = availableBytes(context)
        if (writeCount % PRUNE_CHECK_INTERVAL == 0 ||
            available < FREE_SPACE_RESERVE_BYTES + requiredBytes
        ) {
            trimPreparedCache(context, requiredBytes)
        }
        return availableBytes(context) >= FREE_SPACE_RESERVE_BYTES + requiredBytes
    }

    private fun availableBytes(context: Context): Long = runCatching {
        StatFs(context.cacheDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    /**
     * Enforce a 512 MiB prepared-thumbnail ceiling. When the ceiling is crossed, evict oldest
     * entries down to 384 MiB. If device storage is critically low, evict this cache more
     * aggressively rather than permanently disabling thumbnail preparation.
     */
    private fun trimPreparedCache(context: Context, requiredBytes: Long) {
        synchronized(pruneLock) {
            val directory = cacheDirectory(context)
            val files = directory.listFiles()?.filter { it.isFile } ?: return
            val thumbnailFiles = files
                .filter { it.extension.equals("webp", ignoreCase = true) }
                .sortedBy { it.lastModified() }

            var totalBytes = thumbnailFiles.sumOf { it.length() }
            var available = availableBytes(context)
            val cacheOverLimit = totalBytes > MAX_PREPARED_CACHE_BYTES
            val storageLow = available < FREE_SPACE_RESERVE_BYTES + requiredBytes
            if (!cacheOverLimit && !storageLow) return

            val targetBytes = when {
                storageLow -> 0L
                cacheOverLimit -> PRUNE_TARGET_BYTES
                else -> totalBytes
            }

            files.filter { it.name.endsWith(".tmp", ignoreCase = true) }
                .forEach { temporary -> runCatching { temporary.delete() } }

            for (file in thumbnailFiles) {
                if (totalBytes <= targetBytes &&
                    available >= FREE_SPACE_RESERVE_BYTES + requiredBytes
                ) {
                    break
                }
                val length = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    totalBytes = (totalBytes - length).coerceAtLeast(0L)
                    available += length
                }
            }
        }
    }

    private suspend fun loadMediaStoreThumbnail(
        context: Context,
        uri: Uri,
        widthPx: Int,
        heightPx: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        val targetWidth = widthPx.coerceIn(96, 512)
        val targetHeight = heightPx.coerceIn(96, 512)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val signal = CancellationSignal()
                val job = currentCoroutineContext()[Job]
                val cancellationHandle = job?.invokeOnCompletion { cause ->
                    if (cause is CancellationException) signal.cancel()
                }
                try {
                    context.contentResolver.loadThumbnail(uri, Size(targetWidth, targetHeight), signal)
                } finally {
                    cancellationHandle?.dispose()
                }
            } else {
                val id = runCatching { android.content.ContentUris.parseId(uri) }.getOrDefault(-1L)
                if (id < 0L) {
                    null
                } else {
                    val isVideo = uri.path?.contains("/video/") == true
                    if (isVideo) {
                        MediaStore.Video.Thumbnails.getThumbnail(
                            context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null
                        )
                    } else {
                        MediaStore.Images.Thumbnails.getThumbnail(
                            context.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: OperationCanceledException) {
            throw CancellationException("Thumbnail fetch cancelled", cancelled)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Process-lifetime, single-worker prewarmer. It starts after gallery permission is granted,
 * skips files already prepared, and can safely resume from the first missing thumbnail next launch.
 */
object ThumbnailPrewarmManager {
    private const val DISK_WORKER_COUNT = 2
    private const val MEMORY_PREFETCH_LIMIT = 24

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val memoryLock = Any()
    private var appContext: Context? = null
    private var worker: Job? = null
    private var activeMedia: List<MediaItem>? = null
    private var activeUris: List<String>? = null
    private var completedUris: List<String>? = null
    private var resumeCheckPending = false
    private val priorityQueue = java.util.ArrayDeque<MediaItem>()
    private val queuedPriorityUris = HashSet<String>()
    private val activeMemoryPrefetches = LinkedHashMap<String, Job>()

    /**
     * Disk preparation receives the full predictive window. RAM warming is deliberately bounded:
     * visible cells load normally, while only the next 24 distinct items are explicitly prefetched.
     * This prevents a 100-row grid window from creating hundreds of simultaneous decode requests.
     */
    fun prioritize(
        visibleItems: List<MediaItem>,
        lookAheadItems: List<MediaItem>,
        context: Context? = null
    ) {
        if (visibleItems.isEmpty() && lookAheadItems.isEmpty()) return
        val visible = visibleItems.distinctBy { it.uri }
        val visibleUris = visible.mapTo(HashSet(visible.size)) { it.uri }
        val lookAhead = lookAheadItems.asSequence()
            .filterNot { it.uri in visibleUris }
            .distinctBy { it.uri }
            .toList()

        val ctx = context?.applicationContext ?: appContext
        if (ctx != null) {
            warmMemoryCache(ctx, visible, lookAhead)
        }

        synchronized(lock) {
            if (activeUris.isNullOrEmpty()) return
            priorityQueue.clear()
            queuedPriorityUris.clear()
            (visible.asSequence() + lookAhead.asSequence())
                .distinctBy { it.uri }
                .forEach { item ->
                    if (queuedPriorityUris.add(item.uri)) priorityQueue.addLast(item)
                }
        }

        // A disk worker may have stopped after a storage check or a transient item failure.
        // Recheck off the UI thread and restart it when storage becomes available.
        if (ctx != null) requestDiskWorkerIfNeeded(ctx)
    }

    private fun warmMemoryCache(
        context: Context,
        visibleItems: List<MediaItem>,
        lookAheadItems: List<MediaItem>
    ) {
        val imageLoader = runCatching { Coil.imageLoader(context) }.getOrNull() ?: return
        val visibleUris = visibleItems.mapTo(HashSet(visibleItems.size)) { it.uri }
        val prefetchTargets = lookAheadItems.asSequence()
            .filterNot { it.uri in visibleUris }
            .distinctBy { it.uri }
            .take(MEMORY_PREFETCH_LIMIT)
            .toList()
        val retainedUris = (visibleItems.asSequence() + prefetchTargets.asSequence())
            .map { it.uri }
            .toHashSet()

        synchronized(memoryLock) {
            val iterator = activeMemoryPrefetches.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in retainedUris) {
                    entry.value.cancel()
                    iterator.remove()
                }
            }

            for (item in prefetchTargets) {
                val existing = activeMemoryPrefetches[item.uri]
                if (existing?.isActive == true) continue
                if (existing != null) activeMemoryPrefetches.remove(item.uri)

                val memoryKey = MemoryCache.Key(ThumbnailUtils.mediaThumbnailKey(item.uri))
                if (imageLoader.memoryCache?.get(memoryKey) != null) continue

                val request = ThumbnailUtils.buildThumbnailRequest(context, item.uri)
                val prefetchJob = memoryScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        // execute() suspends until the request finishes and populates Coil's cache.
                        imageLoader.execute(request)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A failed request is removed below and can be retried on the next window.
                    }
                }
                activeMemoryPrefetches[item.uri] = prefetchJob
                prefetchJob.invokeOnCompletion {
                    synchronized(memoryLock) {
                        if (activeMemoryPrefetches[item.uri] === prefetchJob) {
                            activeMemoryPrefetches.remove(item.uri)
                        }
                    }
                }
                prefetchJob.start()
            }
        }
    }

    /** Cancel outstanding speculative RAM work when the gallery screen leaves composition. */
    fun clearMemoryPrefetches() {
        val jobs = synchronized(memoryLock) {
            val pending = activeMemoryPrefetches.values.toList()
            activeMemoryPrefetches.clear()
            pending
        }
        jobs.forEach { it.cancel() }
    }

    fun start(context: Context, media: List<MediaItem>) {
        val ctx = context.applicationContext
        appContext = ctx
        val uris = media.map { it.uri }

        if (uris.isEmpty()) {
            synchronized(lock) {
                worker?.cancel()
                worker = null
                activeMedia = emptyList()
                activeUris = emptyList()
                completedUris = emptyList()
                priorityQueue.clear()
                queuedPriorityUris.clear()
            }
            clearMemoryPrefetches()
            return
        }

        synchronized(lock) {
            if (activeUris == uris && worker?.isActive == true) return
            if (completedUris == uris) return

            val preservedPriorities = if (priorityQueue.isNotEmpty()) {
                val uriSet = uris.toHashSet()
                priorityQueue.filter { it.uri in uriSet }
            } else {
                emptyList()
            }

            worker?.cancel()
            worker = null
            priorityQueue.clear()
            queuedPriorityUris.clear()
            for (item in preservedPriorities) {
                if (queuedPriorityUris.add(item.uri)) priorityQueue.addLast(item)
            }
            activeMedia = media
            activeUris = uris
            completedUris = null
        }

        // Maintenance and the free-space check run away from the main thread.
        scope.launch {
            PreparedThumbnailCache.maintainCache(ctx)
            requestDiskWorkerIfNeeded(ctx)
        }
    }

    private fun requestDiskWorkerIfNeeded(context: Context) {
        val snapshot = synchronized(lock) {
            val media = activeMedia ?: return
            val uris = activeUris ?: return
            if (uris.isEmpty() || worker?.isActive == true ||
                completedUris == uris || resumeCheckPending
            ) {
                return
            }
            resumeCheckPending = true
            media to uris
        }

        scope.launch {
            val hasSpace = PreparedThumbnailCache.hasStorageHeadroom(context)
            var retryForNewInventory = false
            synchronized(lock) {
                resumeCheckPending = false
                if (hasSpace &&
                    activeUris === snapshot.second &&
                    worker?.isActive != true &&
                    completedUris != snapshot.second
                ) {
                    launchDiskWorkerLocked(context, snapshot.first, snapshot.second)
                } else if (activeUris != null &&
                    activeUris !== snapshot.second &&
                    worker?.isActive != true
                ) {
                    retryForNewInventory = true
                }
            }
            if (retryForNewInventory) requestDiskWorkerIfNeeded(context)
        }
    }

    /**
     * Caller holds lock. The worker owns a stable media snapshot; priority updates can replace
     * queued items while its two IO workers continue consuming them.
     */
    private fun launchDiskWorkerLocked(
        context: Context,
        media: List<MediaItem>,
        uris: List<String>
    ) {
        if (worker?.isActive == true || completedUris == uris) return

        val cursorLock = Any()
        var naturalIndex = 0
        val completedAll = AtomicBoolean(true)

        worker = scope.launch {
            try {
                val workers = List(DISK_WORKER_COUNT) {
                    launch {
                        var processedCount = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val item = synchronized(lock) {
                                when {
                                    activeUris !== uris -> null
                                    priorityQueue.isNotEmpty() -> {
                                        val prioritized = priorityQueue.removeFirst()
                                        queuedPriorityUris.remove(prioritized.uri)
                                        prioritized
                                    }
                                    else -> synchronized(cursorLock) {
                                        if (naturalIndex < media.size) media[naturalIndex++] else null
                                    }
                                }
                            } ?: break

                            if (!PreparedThumbnailCache.hasPrepared(context, item.uri)) {
                                if (!PreparedThumbnailCache.hasStorageHeadroom(context)) {
                                    completedAll.set(false)
                                    break
                                }
                                val prepared = try {
                                    PreparedThumbnailCache.prepareIfMissing(context, item.uri)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    false
                                }
                                if (!prepared) {
                                    completedAll.set(false)
                                    if (!PreparedThumbnailCache.hasStorageHeadroom(context)) break
                                }
                            }

                            processedCount++
                            if (processedCount % 8 == 0) yield()
                        }
                    }
                }
                workers.forEach { it.join() }
            } finally {
                synchronized(lock) {
                    if (activeUris === uris) {
                        worker = null
                        if (completedAll.get()) {
                            completedUris = uris
                            priorityQueue.clear()
                            queuedPriorityUris.clear()
                        }
                    }
                }
            }
        }
    }
}
