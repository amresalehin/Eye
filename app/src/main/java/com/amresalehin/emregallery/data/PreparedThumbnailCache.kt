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
object ThumbnailPrewarmManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var worker: Job? = null
    private data class MediaIdentity(val size: Int, val fingerprint: Long)

    private var activeMediaIdentity: MediaIdentity? = null
    private var completedMediaIdentity: MediaIdentity? = null
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

    /**
     * Promote only the bounded set of media rows predicted by Home's scroll position.
     * Visible thumbnails are requested by their AsyncImage composables; this queue is for
     * background disk preparation and does not create competing Coil memory-cache requests.
     */
    fun prioritize(items: List<MediaItem>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            if (worker?.isActive != true || (activeMediaIdentity?.size ?: 0) == 0) return
            priorityQueue.clear()
            queuedPriorityUris.clear()
            items.asSequence().distinctBy { it.uri }.take(120).forEach { item ->
                if (queuedPriorityUris.add(item.uri)) priorityQueue.addLast(item)
            }
        }
    }

    fun start(context: Context, media: List<MediaItem>) {
        val appContext = context.applicationContext
        val identity = mediaIdentity(media)

        synchronized(lock) {
            if (media.isEmpty()) {
                worker?.cancel()
                worker = null
                priorityQueue.clear()
                queuedPriorityUris.clear()
                activeMediaIdentity = identity
                completedMediaIdentity = identity
                return
            }
            if (activeMediaIdentity == identity && worker?.isActive == true) return
            if (completedMediaIdentity == identity) return

            worker?.cancel()
            priorityQueue.clear()
            queuedPriorityUris.clear()
            activeMediaIdentity = identity
            completedMediaIdentity = null
            worker = scope.launch {
                var completedAll = true
                var naturalIndex = 0
                var processedCount = 0
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val item = synchronized(lock) {
                            when {
                                activeMediaIdentity != identity -> null
                                priorityQueue.isNotEmpty() -> {
                                    val prioritized = priorityQueue.removeFirst()
                                    queuedPriorityUris.remove(prioritized.uri)
                                    prioritized
                                }
                                naturalIndex < media.size -> media[naturalIndex++]
                                else -> null
                            }
                        } ?: break

                        if (!PreparedThumbnailCache.hasPrepared(appContext, item.uri)) {
                            if (!PreparedThumbnailCache.hasStorageHeadroom(appContext)) {
                                completedAll = false
                                break
                            }
                            val prepared = try {
                                PreparedThumbnailCache.prepareIfMissing(appContext, item.uri)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            if (!prepared) completedAll = false
                        }
                        processedCount++
                        // Yield regularly so visible requests and other IO work can run promptly.
                        if (processedCount % 8 == 0) yield()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } finally {
                    synchronized(lock) {
                        if (activeMediaIdentity == identity) {
                            worker = null
                            priorityQueue.clear()
                            queuedPriorityUris.clear()
                            if (completedAll) completedMediaIdentity = identity
                        }
                    }
                }
            }
        }
    }
}
