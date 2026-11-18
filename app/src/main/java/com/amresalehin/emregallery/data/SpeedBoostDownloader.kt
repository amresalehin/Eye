package com.amresalehin.emregallery.data

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Multi-threaded HTTP Range downloader ("Speed Boost") inspired by Website Media Downloader.
 *
 * For files larger than [MIN_FILE_SIZE_FOR_BOOST] (2 MB) hosted on servers advertising
 * HTTP Partial Content (`Accept-Ranges: bytes` / HTTP 206), splits the file into up to
 * [MAX_CONNECTIONS] (16) parallel byte-range chunks, downloads them concurrently, merges
 * them into a temporary file, validates, and commits atomically.
 *
 * Automatically falls back to standard single-stream [OkHttpDownloader.downloadMediaFileDetailed]
 * whenever the file is <= 2 MB, connections <= 1, or the server does not support HTTP Range requests.
 */
object SpeedBoostDownloader {

    private const val TAG = "SpeedBoostDownloader"
    const val MIN_FILE_SIZE_FOR_BOOST: Long = 2L * 1024L * 1024L // 2 MB
    const val MIN_CONNECTIONS: Int = 1
    const val MAX_CONNECTIONS: Int = 16
    const val DEFAULT_CONNECTIONS: Int = 4

    data class ByteRange(
        val index: Int,
        val start: Long,
        val end: Long
    ) {
        val length: Long get() = (end - start) + 1L
    }

    data class RangeProbeInfo(
        val supportsRanges: Boolean,
        val contentLength: Long,
        val mimeType: String?,
        val etag: String?,
        val lastModified: String?
    )

    data class SpeedBoostResult(
        val downloadResult: OkHttpDownloader.DownloadResult,
        val usedSpeedBoost: Boolean,
        val connectionsUsed: Int
    )

    /**
     * Splits [totalBytes] into contiguous, non-overlapping byte ranges for parallel acquisition.
     */
    fun computeChunkRanges(totalBytes: Long, connections: Int): List<ByteRange> {
        if (totalBytes <= 0L) return emptyList()
        val count = connections.coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS).toLong()
        val effectiveCount = minOf(count, totalBytes).toInt().coerceAtLeast(1)
        val baseChunk = totalBytes / effectiveCount
        val remainder = totalBytes % effectiveCount

        val ranges = ArrayList<ByteRange>(effectiveCount)
        var offset = 0L
        for (i in 0 until effectiveCount) {
            val size = baseChunk + if (i < remainder) 1L else 0L
            val end = offset + size - 1L
            ranges.add(ByteRange(index = i, start = offset, end = end))
            offset = end + 1L
        }
        return ranges
    }

    /**
     * Probes the remote server to determine whether HTTP 206 Partial Content Range requests
     * are supported and what the exact Content-Length is.
     */
    suspend fun probeRangeSupport(
        mediaUrl: String,
        referer: String? = null,
        headers: Map<String, String>? = null
    ): RangeProbeInfo = withContext(Dispatchers.IO) {
        try {
            val headReq = buildBaseRequest(mediaUrl, referer, headers)
                .head()
                .build()
            OkHttpDownloader.client.newCall(headReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val acceptRanges = resp.header("Accept-Ranges")?.lowercase(Locale.ROOT) ?: ""
                    val contentLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    val mime = resp.header("Content-Type")?.substringBefore(";")?.trim()?.lowercase(Locale.ROOT)
                    if (acceptRanges.contains("bytes") && contentLength > 0L) {
                        return@withContext RangeProbeInfo(
                            supportsRanges = true,
                            contentLength = contentLength,
                            mimeType = mime,
                            etag = resp.header("ETag"),
                            lastModified = resp.header("Last-Modified")
                        )
                    }
                }
            }

            // Fallback probe: Range: bytes=0-0 (some CDNs omit Accept-Ranges on HEAD or reject HEAD)
            val rangeReq = buildBaseRequest(mediaUrl, referer, headers)
                .header("Range", "bytes=0-0")
                .build()
            OkHttpDownloader.client.newCall(rangeReq).execute().use { resp ->
                if (resp.code == 206) {
                    val parsed = OkHttpDownloader.parseContentRangeHeader(resp.header("Content-Range"))
                    val total = parsed?.total ?: -1L
                    val mime = resp.header("Content-Type")?.substringBefore(";")?.trim()?.lowercase(Locale.ROOT)
                    if (total > 0L) {
                        return@withContext RangeProbeInfo(
                            supportsRanges = true,
                            contentLength = total,
                            mimeType = mime,
                            etag = resp.header("ETag"),
                            lastModified = resp.header("Last-Modified")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Range probe failed for $mediaUrl: ${e.message}")
        }
        RangeProbeInfo(
            supportsRanges = false,
            contentLength = -1L,
            mimeType = null,
            etag = null,
            lastModified = null
        )
    }

    /**
     * Downloads [mediaUrl] using parallel HTTP Range requests when [speedBoostEnabled] is true,
     * [connections] > 1, the server supports HTTP 206, and the file size exceeds 2 MB.
     * Falls back seamlessly to standard sequential [OkHttpDownloader] otherwise.
     */
    suspend fun downloadWithSpeedBoost(
        context: Context,
        mediaUrl: String,
        fileName: String,
        referer: String? = null,
        downloadId: String? = null,
        headers: Map<String, String>? = null,
        domain: String? = null,
        speedBoostEnabled: Boolean = true,
        connections: Int = DEFAULT_CONNECTIONS,
        onProgress: ((bytesRead: Long, totalBytes: Long, speedBytesPerSec: Long, isSpeedBoostActive: Boolean) -> Unit)? = null
    ): SpeedBoostResult = withContext(Dispatchers.IO) {
        val clampedConnections = connections.coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS)
        val isDataUri = mediaUrl.startsWith("data:", ignoreCase = true)
        val isManifest = mediaUrl.contains(".m3u8", ignoreCase = true) || mediaUrl.contains(".mpd", ignoreCase = true)

        if (!speedBoostEnabled || clampedConnections <= 1 || isDataUri || isManifest) {
            val startTime = System.currentTimeMillis()
            val fallback = OkHttpDownloader.downloadMediaFileDetailed(
                context = context,
                mediaUrl = mediaUrl,
                fileName = fileName,
                referer = referer,
                downloadId = downloadId,
                headers = headers,
                domain = domain,
                onProgress = { read, total ->
                    val elapsedMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    val speed = (read * 1000L) / elapsedMs
                    onProgress?.invoke(read, total, speed, false)
                }
            )
            return@withContext SpeedBoostResult(fallback, usedSpeedBoost = false, connectionsUsed = 1)
        }

        val probe = probeRangeSupport(mediaUrl, referer, headers)
        if (!probe.supportsRanges || probe.contentLength < MIN_FILE_SIZE_FOR_BOOST) {
            val startTime = System.currentTimeMillis()
            val fallback = OkHttpDownloader.downloadMediaFileDetailed(
                context = context,
                mediaUrl = mediaUrl,
                fileName = fileName,
                referer = referer,
                downloadId = downloadId,
                headers = headers,
                domain = domain,
                onProgress = { read, total ->
                    val elapsedMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    val speed = (read * 1000L) / elapsedMs
                    onProgress?.invoke(read, total, speed, false)
                }
            )
            return@withContext SpeedBoostResult(fallback, usedSpeedBoost = false, connectionsUsed = 1)
        }

        // Reject HTML/JSON responses early
        val mime = probe.mimeType
        if (mime != null && (mime.startsWith("text/html") || mime.startsWith("application/json"))) {
            return@withContext SpeedBoostResult(
                downloadResult = OkHttpDownloader.DownloadResult(
                    file = null,
                    isSuccessful = false,
                    statusCode = 200,
                    bytesDownloaded = 0L,
                    totalBytes = probe.contentLength,
                    errorMessage = "Server returned $mime instead of downloadable media"
                ),
                usedSpeedBoost = false,
                connectionsUsed = 1
            )
        }

        val effectiveDomain = domain?.takeIf { it.isNotBlank() }
            ?: OkHttpDownloader.extractDomain(referer ?: mediaUrl).takeIf { it != "general" && it != "localhost" }
        val targetDir = OkHttpDownloader.getTargetStorageDir(context, effectiveDomain)
        val sanitizedName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val deterministicBase = OkHttpDownloader.stableDownloadBaseName(mediaUrl, sanitizedName, downloadId)
        val finalFile = File(targetDir, deterministicBase)
        val mergedTempFile = File(targetDir, "$deterministicBase.speedboost.tmp")
        val chunkDir = File(context.cacheDir, "speedboost_${deterministicBase.hashCode()}_${System.currentTimeMillis()}")
        chunkDir.mkdirs()

        val ranges = computeChunkRanges(probe.contentLength, clampedConnections)
        val totalDownloaded = AtomicLong(0L)
        val startTime = System.currentTimeMillis()
        val lastProgressUpdate = AtomicLong(0L)
        val semaphore = Semaphore(clampedConnections)

        try {
            val chunkFiles = coroutineScope {
                ranges.map { range ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val chunkFile = File(chunkDir, "chunk_${range.index}.part")
                            downloadChunk(
                                mediaUrl = mediaUrl,
                                range = range,
                                chunkFile = chunkFile,
                                referer = referer,
                                headers = headers,
                                expectedEtag = probe.etag,
                                onBytesRead = { delta ->
                                    val nowTotal = totalDownloaded.addAndGet(delta)
                                    val now = System.currentTimeMillis()
                                    val prev = lastProgressUpdate.get()
                                    if (now - prev >= 120L && lastProgressUpdate.compareAndSet(prev, now)) {
                                        val elapsedMs = (now - startTime).coerceAtLeast(1L)
                                        val speed = (nowTotal * 1000L) / elapsedMs
                                        onProgress?.invoke(nowTotal, probe.contentLength, speed, true)
                                    }
                                }
                            )
                            chunkFile
                        }
                    }
                }.awaitAll()
            }

            // Merge ordered chunks into mergedTempFile
            FileOutputStream(mergedTempFile).use { out ->
                val buffer = ByteArray(64 * 1024)
                for (chunkFile in chunkFiles) {
                    FileInputStream(chunkFile).use { input ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                        }
                    }
                }
                out.flush()
                out.fd.sync()
            }

            if (mergedTempFile.length() != probe.contentLength) {
                throw IllegalStateException(
                    "Speed Boost merged size mismatch: expected ${probe.contentLength}, got ${mergedTempFile.length()}"
                )
            }

            // Atomic move to finalFile
            try {
                Files.move(
                    mergedTempFile.toPath(),
                    finalFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                Files.move(
                    mergedTempFile.toPath(),
                    finalFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }

            val elapsedMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
            val finalSpeed = (probe.contentLength * 1000L) / elapsedMs
            onProgress?.invoke(probe.contentLength, probe.contentLength, finalSpeed, true)

            runCatching {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(finalFile.absolutePath),
                    arrayOf(probe.mimeType),
                    null
                )
            }

            SpeedBoostResult(
                downloadResult = OkHttpDownloader.DownloadResult(
                    file = finalFile,
                    isSuccessful = true,
                    statusCode = 206,
                    bytesDownloaded = finalFile.length(),
                    totalBytes = probe.contentLength,
                    mimeType = probe.mimeType,
                    isResumed = false
                ),
                usedSpeedBoost = true,
                connectionsUsed = ranges.size
            )
        } catch (e: Exception) {
            Log.w(TAG, "Speed Boost parallel download failed for $mediaUrl (${e.message}); falling back to standard download")
            runCatching { mergedTempFile.delete() }
            val fallbackStart = System.currentTimeMillis()
            val fallback = OkHttpDownloader.downloadMediaFileDetailed(
                context = context,
                mediaUrl = mediaUrl,
                fileName = fileName,
                referer = referer,
                downloadId = downloadId,
                headers = headers,
                domain = domain,
                onProgress = { read, total ->
                    val elapsedMs = (System.currentTimeMillis() - fallbackStart).coerceAtLeast(1L)
                    val speed = (read * 1000L) / elapsedMs
                    onProgress?.invoke(read, total, speed, false)
                }
            )
            SpeedBoostResult(fallback, usedSpeedBoost = false, connectionsUsed = 1)
        } finally {
            runCatching { chunkDir.deleteRecursively() }
        }
    }

    private fun downloadChunk(
        mediaUrl: String,
        range: ByteRange,
        chunkFile: File,
        referer: String?,
        headers: Map<String, String>?,
        expectedEtag: String?,
        onBytesRead: (Long) -> Unit
    ) {
        val maxAttempts = 3
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                val reqBuilder = buildBaseRequest(mediaUrl, referer, headers)
                    .header("Range", "bytes=${range.start}-${range.end}")
                if (!expectedEtag.isNullOrBlank()) {
                    reqBuilder.header("If-Range", expectedEtag)
                }

                OkHttpDownloader.client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.code != 206) {
                        throw IllegalStateException("Expected HTTP 206 for chunk ${range.index}, got ${resp.code}")
                    }
                    val body = resp.body ?: throw IllegalStateException("Empty body for chunk ${range.index}")
                    var written = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(chunkFile).use { out ->
                            val buf = ByteArray(32 * 1024)
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                out.write(buf, 0, read)
                                written += read
                                onBytesRead(read.toLong())
                            }
                            out.flush()
                        }
                    }
                    if (written != range.length) {
                        throw IllegalStateException("Chunk ${range.index} length mismatch: expected ${range.length}, got $written")
                    }
                    return
                }
            } catch (e: Exception) {
                lastError = e
                runCatching { chunkFile.delete() }
                if (attempt < maxAttempts) {
                    Thread.sleep(250L * attempt)
                }
            }
        }
        throw lastError ?: IllegalStateException("Failed to download chunk ${range.index}")
    }

    private fun buildBaseRequest(
        mediaUrl: String,
        referer: String?,
        headers: Map<String, String>?
    ): Request.Builder {
        val builder = Request.Builder()
            .url(mediaUrl)
            .header("Accept", "*/*")
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
            val origin = extractOrigin(referer)
            if (origin != null) {
                builder.header("Origin", origin)
            }
        }
        headers?.forEach { (k, v) ->
            if (!k.equals("Range", ignoreCase = true) && !k.equals("If-Range", ignoreCase = true)) {
                builder.header(k, v)
            }
        }
        return builder
    }

    internal fun extractOrigin(url: String): String? {
        return runCatching {
            val uri = java.net.URI(url)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val portPart = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            "$scheme://$host$portPart"
        }.getOrNull()
    }
}
