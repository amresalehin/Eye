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
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Disk-backed thumbnail preparation for the gallery.
 *
 * The UI continues to draw each cell immediately. This store keeps decoded thumbnails out of
 * long-lived RAM: requests first reuse a prepared WebP file, while a background job gradually
 * prepares every MediaStore item after permission is granted.
 */
object PreparedThumbnailCache {
    private const val DIRECTORY_NAME = "prepared_thumbnails"
    // Keep at least 768 MiB free; the prepared cache may grow beyond an arbitrary fixed cap.
    private const val FREE_SPACE_RESERVE_BYTES = 768L * 1024L * 1024L
    private const val WEBP_QUALITY = 80
    private const val PREWARM_WIDTH = 420
    private const val PREWARM_HEIGHT = 420
    private val HEX_CHARS = "0123456789abcdef".toCharArray()
    private val sha256Local = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    // Fixed stripes avoid retaining one Mutex and URI-sized key per photo for large libraries.
    private val uriLocks = Array(256) { Mutex() }

    data class ThumbnailResult(val bitmap: Bitmap)

    fun hasPrepared(context: Context, uri: String, widthPx: Int = PREWARM_WIDTH, heightPx: Int = PREWARM_HEIGHT): Boolean {
        val file = cacheFile(context.applicationContext, uri, widthPx, heightPx)
        // Keep the background pass cheap: full decode/validation occurs only on a cache hit
        // that is actually requested by Coil. Corrupt entries are removed by readPrepared().
        return file.isFile && file.length() > 0L
    }

    fun hasStorageHeadroom(context: Context): Boolean = runCatching {
        StatFs(context.applicationContext.cacheDir.absolutePath).availableBytes > FREE_SPACE_RESERVE_BYTES
    }.getOrDefault(false)

    /**
     * Returns a prepared thumbnail when present. On a miss, creates the Android MediaStore
     * thumbnail on IO and atomically writes a compact WebP copy, unless the free-space reserve
     * would be violated. Per-URI/size locks prevent the UI request and background prewarmer from
     * generating the same thumbnail concurrently.
     */
    suspend fun loadOrCreateFile(
        context: Context,
        uri: Uri,
        widthPx: Int,
        heightPx: Int
    ): File? {
        val appContext = context.applicationContext
        val (targetWidth, targetHeight) = normalizedDimensions(widthPx, heightPx)
        val key = cacheKey(uri.toString(), targetWidth, targetHeight)
        val file = File(cacheDirectory(appContext), "$key.webp")
        // Keep cache hits read-only: the prepared cache is an opportunistic cache, not a database.
        if (file.isFile && file.length() > 0L) return file
        val result = loadOrCreate(appContext, uri, widthPx, heightPx) ?: return null
        if (!result.bitmap.isRecycled) result.bitmap.recycle()
        return if (file.isFile && file.length() > 0L) file else null
    }

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
        if (!hasStorageHeadroom(context)) return false

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
        val digest = sha256Local.get() ?: MessageDigest.getInstance("SHA-256")
        digest.reset()
        val bytes = digest.digest("$uri|$normalizedWidth|$normalizedHeight".toByteArray(Charsets.UTF_8))
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            chars[i * 2] = HEX_CHARS[v ushr 4]
            chars[i * 2 + 1] = HEX_CHARS[v and 0x0f]
        }
        return String(chars)
    }

    private fun readPrepared(file: File): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        if (bitmap == null) {
            runCatching { file.delete() }
        }
        return bitmap
    }

    private fun writePreparedIfSafe(context: Context, file: File, bitmap: Bitmap) {
        if (!hasStorageHeadroom(context)) return
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
            // A newly written file already carries its creation timestamp; no extra metadata write is needed.
        }
        if (temporary.exists()) runCatching { temporary.delete() }
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
/**
 * A single, low-priority thumbnail worker.
 *
 * It is always subordinate to interactive image loading: scrolling or any visible Coil
 * request cancels the current background operation. The worker resumes only after scrolling
 * stops and all visible fetches finish. The priority queue is bounded to 120 upcoming items.
 */
object ThumbnailPrewarmManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var worker: Job? = null
    private var appContext: Context? = null

    private data class MediaIdentity(val size: Int, val fingerprint: Long)
    private data class WorkItem(
        val item: MediaItem,
        val isPriority: Boolean,
        val naturalIndex: Int,
        val identity: MediaIdentity?
    )

    private var activeMediaIdentity: MediaIdentity? = null
    private var completedMediaIdentity: MediaIdentity? = null
    private var activeMedia: List<MediaItem> = emptyList()
    private var nextNaturalIndex = 0
    private var isScrolling = false
    private var activeVisibleRequests = 0
    private var storageBlocked = false

    private val priorityQueue = java.util.ArrayDeque<MediaItem>()
    private val queuedPriorityUris = HashSet<String>()

    // Compare gallery snapshots without retaining a second full list of every URI.
    private fun mediaIdentity(media: List<MediaItem>): MediaIdentity {
        var fingerprint = -3750763034362895579L
        for (item in media) {
            fingerprint = (fingerprint xor item.id) * 1099511628211L
            fingerprint = (fingerprint xor item.uri.hashCode().toLong()) * 1099511628211L
        }
        return MediaIdentity(media.size, fingerprint)
    }

    private fun canRunLocked(): Boolean =
        shouldAllowBackgroundThumbnailPreparation(isScrolling, activeVisibleRequests)

    private fun hasWorkLocked(): Boolean =
        priorityQueue.isNotEmpty() || nextNaturalIndex < activeMedia.size

    /**
     * A scroll transition pauses and cancels any in-flight prewarm request. The gallery's
     * visible Coil fetches then get exclusive access to MediaStore/decode/encoding resources.
     */
    fun setScrolling(context: Context, scrolling: Boolean) {
        synchronized(lock) {
            appContext = context.applicationContext
            isScrolling = scrolling
            if (scrolling) {
                worker?.cancel()
            } else {
                ensureWorkerLocked()
            }
        }
    }

    /**
     * Wrap a visible MediaStore/Coil fetch. Starting a visible request cancels background
     * preparation immediately; the worker resumes only when the final visible fetch completes.
     */
    suspend fun <T> withVisibleRequest(block: suspend () -> T): T {
        synchronized(lock) {
            activeVisibleRequests++
            worker?.cancel()
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                activeVisibleRequests = (activeVisibleRequests - 1).coerceAtLeast(0)
                ensureWorkerLocked()
            }
        }
    }

    /**
     * Keep only the latest bounded look-ahead window. This also works during the initial
     * MediaStore scan, before the full-library natural prewarm pass has started.
     */
    fun prioritize(context: Context, items: List<MediaItem>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            appContext = context.applicationContext
            priorityQueue.clear()
            queuedPriorityUris.clear()
            items.asSequence().distinctBy { it.uri }.take(120).forEach { item ->
                if (queuedPriorityUris.add(item.uri)) priorityQueue.addLast(item)
            }
            storageBlocked = false
            ensureWorkerLocked()
        }
    }

    fun start(context: Context, media: List<MediaItem>) {
        val identity = mediaIdentity(media)
        synchronized(lock) {
            appContext = context.applicationContext
            if (media.isEmpty()) {
                worker?.cancel()
                priorityQueue.clear()
                queuedPriorityUris.clear()
                activeMedia = emptyList()
                nextNaturalIndex = 0
                activeMediaIdentity = identity
                completedMediaIdentity = identity
                storageBlocked = false
                return
            }

            if (activeMediaIdentity != identity) {
                worker?.cancel()
                activeMediaIdentity = identity
                activeMedia = media
                nextNaturalIndex = 0
                completedMediaIdentity = null
            } else {
                activeMedia = media
            }
            storageBlocked = false
            if (completedMediaIdentity != identity) ensureWorkerLocked()
        }
    }

    /**
     * Launch exactly one worker. A cancelled worker remains registered until its finally block
     * runs, preventing a replacement from overlapping the cancellation teardown.
     */
    private fun ensureWorkerLocked() {
        val context = appContext ?: return
        if (!canRunLocked() || storageBlocked || worker != null || !hasWorkLocked()) return

        val launched = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            runWorker(context)
        }
        worker = launched
        launched.start()
    }

    private suspend fun runWorker(context: Context) {
        val thisJob = currentCoroutineContext()[Job]
        var completedAll = true
        var processedCount = 0

        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val work = synchronized(lock) {
                    if (!canRunLocked()) {
                        null
                    } else if (priorityQueue.isNotEmpty()) {
                        WorkItem(priorityQueue.peekFirst(), true, -1, activeMediaIdentity)
                    } else if (nextNaturalIndex < activeMedia.size) {
                        WorkItem(activeMedia[nextNaturalIndex], false, nextNaturalIndex, activeMediaIdentity)
                    } else {
                        null
                    }
                } ?: break

                if (!PreparedThumbnailCache.hasPrepared(context, work.item.uri)) {
                    if (!PreparedThumbnailCache.hasStorageHeadroom(context)) {
                        completedAll = false
                        synchronized(lock) {
                            storageBlocked = true
                            priorityQueue.clear()
                            queuedPriorityUris.clear()
                        }
                        break
                    }

                    val prepared = try {
                        PreparedThumbnailCache.prepareIfMissing(context, work.item.uri)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    if (!prepared) completedAll = false
                }

                synchronized(lock) {
                    if (work.isPriority) {
                        if (priorityQueue.peekFirst()?.uri == work.item.uri) {
                            priorityQueue.removeFirst()
                            queuedPriorityUris.remove(work.item.uri)
                        }
                    } else if (
                        activeMediaIdentity == work.identity &&
                        nextNaturalIndex == work.naturalIndex &&
                        activeMedia.getOrNull(nextNaturalIndex)?.uri == work.item.uri
                    ) {
                        nextNaturalIndex++
                    }
                }

                processedCount++
                // Keep the single worker cooperative even when it is operating during idle time.
                if (processedCount % 2 == 0) yield()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            synchronized(lock) {
                if (worker === thisJob) worker = null
                val identity = activeMediaIdentity
                if (
                    completedAll &&
                    identity != null &&
                    priorityQueue.isEmpty() &&
                    nextNaturalIndex >= activeMedia.size
                ) {
                    completedMediaIdentity = identity
                }
                ensureWorkerLocked()
            }
        }
    }
}
