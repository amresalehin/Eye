package com.amresalehin.emregallery.data

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import com.amresalehin.emregallery.ffmpeg.FfmpegKitEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Native Offline Stream Reconstruction Engine inspired by Website Media Downloader.
 *
 * Supports:
 * - **HLS (.m3u8)**: Master playlist variant selection (respecting max height cap),
 *   media playlist parsing (`#EXTINF`, `#EXT-X-MAP`, `#EXT-X-BYTERANGE`, `#EXT-X-KEY` AES-128),
 *   parallel segment acquisition (up to 16 connections), local AES-128 CBC decryption,
 *   local segment merging, and MP4 conversion via FFmpegKit with automatic `.ts` fallback.
 * - **DASH (.mpd)**: XML manifest parsing (`AdaptationSet`, `Representation`, `BaseURL`,
 *   `SegmentList`, `SegmentTemplate` with `$Number$`/`$Time$`/`$RepresentationID$` + `SegmentTimeline`),
 *   parallel video/audio segment download, and MP4 muxing via FFmpegKit.
 * - **Manual TS -> MP4 Conversion**: Converts any downloaded `.ts` file into `.mp4`.
 */
object StreamReconstructionEngine {

    private const val TAG = "StreamReconstructionEngine"

    data class HlsVariant(
        val url: String,
        val bandwidth: Long,
        val width: Int? = null,
        val height: Int? = null,
        val codecs: String? = null
    ) {
        val label: String
            get() = when {
                height != null && height > 0 -> "${height}p"
                bandwidth > 0L -> "${bandwidth / 1000} kbps"
                else -> "Default"
            }
    }

    data class HlsSegment(
        val index: Int,
        val mediaSequence: Long,
        val url: String,
        val durationSec: Double,
        val byteRangeStart: Long? = null,
        val byteRangeLength: Long? = null,
        val keyMethod: String? = null,
        val keyUri: String? = null,
        val keyIvHex: String? = null,
        val initMapUrl: String? = null
    )

    data class DashRepresentation(
        val id: String,
        val mimeType: String,
        val isVideo: Boolean,
        val isAudio: Boolean,
        val width: Int? = null,
        val height: Int? = null,
        val bandwidth: Long = 0L,
        val codecs: String? = null,
        val initUrl: String? = null,
        val segmentUrls: List<String> = emptyList()
    )

    data class DashManifestInfo(
        val videoRepresentations: List<DashRepresentation>,
        val audioRepresentations: List<DashRepresentation>
    )

    data class StreamDownloadResult(
        val file: File?,
        val isSuccessful: Boolean,
        val usedTsFallback: Boolean = false,
        val segmentsDownloaded: Int = 0,
        val totalSegments: Int = 0,
        val errorMessage: String? = null
    )

    // -------------------------------------------------------------------------
    // HLS Parsing & AES-128 Decryption
    // -------------------------------------------------------------------------

    fun isHlsMasterPlaylist(content: String): Boolean {
        return content.contains("#EXT-X-STREAM-INF", ignoreCase = true)
    }

    fun parseHlsMasterPlaylist(content: String, baseUrl: String): List<HlsVariant> {
        val lines = content.lines().map { it.trim() }
        val variants = mutableListOf<HlsVariant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                val attrs = line.substringAfter(":")
                val bandwidth = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE).find(attrs)
                val width = resMatch?.groupValues?.get(1)?.toIntOrNull()
                val height = resMatch?.groupValues?.get(2)?.toIntOrNull()
                val codecs = Regex("CODECS=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1)

                var nextIdx = i + 1
                while (nextIdx < lines.size && (lines[nextIdx].isBlank() || lines[nextIdx].startsWith("#"))) {
                    nextIdx++
                }
                if (nextIdx < lines.size) {
                    val resolved = resolveRelativeUrl(lines[nextIdx], baseUrl)
                    if (resolved != null) {
                        variants.add(
                            HlsVariant(
                                url = resolved,
                                bandwidth = bandwidth,
                                width = width,
                                height = height,
                                codecs = codecs
                            )
                        )
                    }
                    i = nextIdx
                }
            }
            i++
        }
        return variants.sortedWith(
            compareByDescending<HlsVariant> { it.height ?: 0 }.thenByDescending { it.bandwidth }
        )
    }

    fun selectBestVariant(variants: List<HlsVariant>, maxHeight: Int): HlsVariant? {
        if (variants.isEmpty()) return null
        if (maxHeight <= 0) return variants.first()
        val underCap = variants.filter { (it.height ?: Int.MAX_VALUE) <= maxHeight }
        return underCap.firstOrNull() ?: variants.last()
    }

    fun parseHlsMediaPlaylist(content: String, baseUrl: String): List<HlsSegment> {
        val lines = content.lines().map { it.trim() }
        val segments = mutableListOf<HlsSegment>()

        var mediaSequence = 0L
        var currentKeyMethod: String? = null
        var currentKeyUri: String? = null
        var currentKeyIv: String? = null
        var currentInitMapUrl: String? = null
        var pendingDuration = 0.0
        var pendingByteRangeLength: Long? = null
        var pendingByteRangeStart: Long? = null
        var lastByteRangeEnd = 0L

        for (line in lines) {
            if (line.isBlank()) continue
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) -> {
                    mediaSequence = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    val attrs = line.substringAfter(":")
                    val method = Regex("""METHOD=([A-Z0-9-]+)""", RegexOption.IGNORE_CASE)
                        .find(attrs)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
                    if (method == "NONE") {
                        currentKeyMethod = null
                        currentKeyUri = null
                        currentKeyIv = null
                    } else {
                        currentKeyMethod = method
                        val rawUri = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                            .find(attrs)?.groupValues?.get(1)
                        currentKeyUri = rawUri?.let { resolveRelativeUrl(it, baseUrl) }
                        currentKeyIv = Regex("""IV=0x([0-9a-fA-F]+)""", RegexOption.IGNORE_CASE)
                            .find(attrs)?.groupValues?.get(1)
                    }
                }
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    val attrs = line.substringAfter(":")
                    val rawUri = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                        .find(attrs)?.groupValues?.get(1)
                    currentInitMapUrl = rawUri?.let { resolveRelativeUrl(it, baseUrl) }
                }
                line.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true) -> {
                    val spec = line.substringAfter(":").trim()
                    val parts = spec.split("@")
                    val len = parts[0].toLongOrNull()
                    val start = parts.getOrNull(1)?.toLongOrNull() ?: lastByteRangeEnd
                    if (len != null && len > 0L) {
                        pendingByteRangeLength = len
                        pendingByteRangeStart = start
                        lastByteRangeEnd = start + len
                    }
                }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    pendingDuration = line.substringAfter(":").substringBefore(",").trim().toDoubleOrNull() ?: 0.0
                }
                !line.startsWith("#") -> {
                    val resolved = resolveRelativeUrl(line, baseUrl)
                    if (resolved != null) {
                        val idx = segments.size
                        segments.add(
                            HlsSegment(
                                index = idx,
                                mediaSequence = mediaSequence + idx,
                                url = resolved,
                                durationSec = pendingDuration,
                                byteRangeStart = pendingByteRangeStart,
                                byteRangeLength = pendingByteRangeLength,
                                keyMethod = currentKeyMethod,
                                keyUri = currentKeyUri,
                                keyIvHex = currentKeyIv,
                                initMapUrl = currentInitMapUrl
                            )
                        )
                    }
                    pendingDuration = 0.0
                    pendingByteRangeLength = null
                    pendingByteRangeStart = null
                }
            }
        }
        return segments
    }

    /**
     * Decrypts an AES-128 CBC encrypted HLS segment per RFC 8216 Section 5.2.
     */
    fun decryptAes128Segment(
        encryptedBytes: ByteArray,
        keyBytes: ByteArray,
        ivHex: String?,
        mediaSequence: Long
    ): ByteArray {
        val ivBytes = if (!ivHex.isNullOrBlank()) {
            hexTo16ByteIv(ivHex)
        } else {
            // Default IV is the 128-bit big-endian media sequence number
            ByteBuffer.allocate(16).putLong(0L).putLong(mediaSequence).array()
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        return cipher.doFinal(encryptedBytes)
    }

    private fun hexTo16ByteIv(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
        return ByteArray(16) { idx ->
            clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
    }

    // -------------------------------------------------------------------------
    // DASH (.mpd) XML Manifest Parsing
    // -------------------------------------------------------------------------

    fun parseDashManifest(xmlContent: String, baseUrl: String): DashManifestInfo {
        val videoReps = mutableListOf<DashRepresentation>()
        val audioReps = mutableListOf<DashRepresentation>()

        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlContent.toByteArray(Charsets.UTF_8)))
            val mpdBaseUrl = directChildText(doc.documentElement, "BaseURL")?.let { resolveRelativeUrl(it, baseUrl) } ?: baseUrl

            val adaptationSets = doc.getElementsByTagName("AdaptationSet")
            for (i in 0 until adaptationSets.length) {
                val asEl = adaptationSets.item(i) as? Element ?: continue
                val asMime = asEl.getAttribute("mimeType").lowercase(Locale.ROOT)
                val asContentType = asEl.getAttribute("contentType").lowercase(Locale.ROOT)
                val asBaseUrl = directChildText(asEl, "BaseURL")?.let { resolveRelativeUrl(it, mpdBaseUrl) } ?: mpdBaseUrl
                val asSegTemplate = directChildElement(asEl, "SegmentTemplate")

                val reps = asEl.getElementsByTagName("Representation")
                for (r in 0 until reps.length) {
                    val repEl = reps.item(r) as? Element ?: continue
                    val id = repEl.getAttribute("id").ifBlank { "rep_$r" }
                    val repMime = repEl.getAttribute("mimeType").lowercase(Locale.ROOT).ifBlank { asMime }
                    val isVideo = repMime.startsWith("video/") || asContentType == "video"
                    val isAudio = repMime.startsWith("audio/") || asContentType == "audio"
                    if (!isVideo && !isAudio) continue

                    val width = repEl.getAttribute("width").toIntOrNull()
                    val height = repEl.getAttribute("height").toIntOrNull()
                    val bandwidth = repEl.getAttribute("bandwidth").toLongOrNull() ?: 0L
                    val codecs = repEl.getAttribute("codecs").ifBlank { null }
                    val repBaseUrl = directChildText(repEl, "BaseURL")?.let { resolveRelativeUrl(it, asBaseUrl) } ?: asBaseUrl

                    val segTemplate = directChildElement(repEl, "SegmentTemplate") ?: asSegTemplate
                    val segList = directChildElement(repEl, "SegmentList")

                    var initUrl: String? = null
                    val segmentUrls = mutableListOf<String>()

                    if (segTemplate != null) {
                        val initAttr = segTemplate.getAttribute("initialization")
                        if (initAttr.isNotBlank()) {
                            val expanded = expandDashTemplate(initAttr, id, bandwidth, null, null)
                            initUrl = resolveRelativeUrl(expanded, repBaseUrl)
                        }
                        val mediaAttr = segTemplate.getAttribute("media")
                        val startNumber = segTemplate.getAttribute("startNumber").toLongOrNull() ?: 1L
                        val timeline = directChildElement(segTemplate, "SegmentTimeline")
                        if (timeline != null && mediaAttr.isNotBlank()) {
                            val sNodes = timeline.getElementsByTagName("S")
                            var currentTime = 0L
                            var currentNumber = startNumber
                            for (sIdx in 0 until sNodes.length) {
                                val sEl = sNodes.item(sIdx) as? Element ?: continue
                                val tAttr = sEl.getAttribute("t").toLongOrNull()
                                if (tAttr != null) currentTime = tAttr
                                val dAttr = sEl.getAttribute("d").toLongOrNull() ?: 0L
                                val rAttr = sEl.getAttribute("r").toIntOrNull() ?: 0
                                val repeats = (rAttr + 1).coerceIn(1, 5000)
                                repeat(repeats) {
                                    val expanded = expandDashTemplate(mediaAttr, id, bandwidth, currentNumber, currentTime)
                                    resolveRelativeUrl(expanded, repBaseUrl)?.let { segmentUrls.add(it) }
                                    currentNumber++
                                    currentTime += dAttr
                                }
                            }
                        }
                    } else if (segList != null) {
                        val initEl = directChildElement(segList, "Initialization")
                        val srcUrl = initEl?.getAttribute("sourceURL")
                        if (!srcUrl.isNullOrBlank()) {
                            initUrl = resolveRelativeUrl(srcUrl, repBaseUrl)
                        }
                        val segUrlNodes = segList.getElementsByTagName("SegmentURL")
                        for (uIdx in 0 until segUrlNodes.length) {
                            val uEl = segUrlNodes.item(uIdx) as? Element ?: continue
                            val media = uEl.getAttribute("media")
                            if (media.isNotBlank()) {
                                resolveRelativeUrl(media, repBaseUrl)?.let { segmentUrls.add(it) }
                            }
                        }
                    } else if (repBaseUrl != baseUrl) {
                        // Single-file BaseURL representation
                        segmentUrls.add(repBaseUrl)
                    }

                    val rep = DashRepresentation(
                        id = id,
                        mimeType = repMime,
                        isVideo = isVideo,
                        isAudio = isAudio,
                        width = width,
                        height = height,
                        bandwidth = bandwidth,
                        codecs = codecs,
                        initUrl = initUrl,
                        segmentUrls = segmentUrls
                    )
                    if (isVideo) videoReps.add(rep) else audioReps.add(rep)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse DASH MPD manifest: ${e.message}")
        }

        return DashManifestInfo(
            videoRepresentations = videoReps.sortedWith(
                compareByDescending<DashRepresentation> { it.height ?: 0 }.thenByDescending { it.bandwidth }
            ),
            audioRepresentations = audioReps.sortedByDescending { it.bandwidth }
        )
    }

    private fun expandDashTemplate(
        template: String,
        repId: String,
        bandwidth: Long,
        number: Long?,
        time: Long?
    ): String {
        var result = template
            .replace("\$RepresentationID\$", repId)
            .replace("\$Bandwidth\$", bandwidth.toString())
        if (number != null) {
            result = result.replace("\$Number\$", number.toString())
            result = Regex("\\\$Number%0(\\d+)d\\\$").replace(result) { match ->
                val pad = match.groupValues[1].toIntOrNull() ?: 1
                number.toString().padStart(pad, '0')
            }
        }
        if (time != null) {
            result = result.replace("\$Time\$", time.toString())
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Parallel Stream Download & Local Reconstruction
    // -------------------------------------------------------------------------

    suspend fun downloadHlsStream(
        context: Context,
        manifestUrl: String,
        fileName: String,
        referer: String? = null,
        headers: Map<String, String>? = null,
        domain: String? = null,
        maxHeight: Int = 0,
        parallelConnections: Int = SpeedBoostDownloader.DEFAULT_CONNECTIONS,
        onProgress: ((completedSegments: Int, totalSegments: Int, bytesDownloaded: Long, speedBytesPerSec: Long) -> Unit)? = null
    ): StreamDownloadResult = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "hls_recon_${System.currentTimeMillis()}_${manifestUrl.hashCode()}")
        workDir.mkdirs()
        try {
            var activeManifestUrl = manifestUrl
            var manifestText = fetchText(activeManifestUrl, referer, headers)
                ?: return@withContext StreamDownloadResult(null, false, errorMessage = "Failed to fetch HLS manifest")

            if (isHlsMasterPlaylist(manifestText)) {
                val variants = parseHlsMasterPlaylist(manifestText, activeManifestUrl)
                val chosen = selectBestVariant(variants, maxHeight)
                    ?: return@withContext StreamDownloadResult(null, false, errorMessage = "No playable variants found in HLS master playlist")
                activeManifestUrl = chosen.url
                manifestText = fetchText(activeManifestUrl, referer, headers)
                    ?: return@withContext StreamDownloadResult(null, false, errorMessage = "Failed to fetch HLS variant playlist")
            }

            val segments = parseHlsMediaPlaylist(manifestText, activeManifestUrl)
            if (segments.isEmpty()) {
                return@withContext StreamDownloadResult(null, false, errorMessage = "HLS playlist contains no media segments")
            }

            val keyCache = ConcurrentHashMap<String, ByteArray>()
            val connections = parallelConnections.coerceIn(1, SpeedBoostDownloader.MAX_CONNECTIONS)
            val semaphore = Semaphore(connections)
            val completedCount = AtomicInteger(0)
            val totalBytes = AtomicLong(0L)
            val startTime = System.currentTimeMillis()

            // Optional init map (fMP4 HLS)
            val initMapUrl = segments.firstOrNull()?.initMapUrl
            val initBytes = if (!initMapUrl.isNullOrBlank()) {
                fetchBytes(initMapUrl, referer, headers, null, null)
            } else null

            val segFiles = coroutineScope {
                segments.map { seg ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val rawBytes = fetchBytes(
                                url = seg.url,
                                referer = referer,
                                headers = headers,
                                byteRangeStart = seg.byteRangeStart,
                                byteRangeLength = seg.byteRangeLength
                            ) ?: throw IllegalStateException("Failed to fetch HLS segment #${seg.index}")

                            val finalBytes = if (seg.keyMethod == "AES-128" && !seg.keyUri.isNullOrBlank()) {
                                val keyBytes = keyCache[seg.keyUri] ?: (
                                    fetchBytes(seg.keyUri, referer, headers, null, null)?.also { keyCache[seg.keyUri] = it }
                                        ?: throw IllegalStateException("Failed to fetch AES-128 decryption key")
                                    )
                                decryptAes128Segment(rawBytes, keyBytes, seg.keyIvHex, seg.mediaSequence)
                            } else {
                                rawBytes
                            }

                            val segFile = File(workDir, "seg_${seg.index.toString().padStart(6, '0')}.ts")
                            segFile.writeBytes(finalBytes)

                            val done = completedCount.incrementAndGet()
                            val bytesNow = totalBytes.addAndGet(finalBytes.size.toLong())
                            val elapsedMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                            onProgress?.invoke(done, segments.size, bytesNow, (bytesNow * 1000L) / elapsedMs)
                            segFile
                        }
                    }
                }.awaitAll()
            }

            val targetDir = OkHttpDownloader.getTargetStorageDir(context, domain)
            val safeBase = fileName.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "stream_${System.currentTimeMillis()}" }
            val mergedTsFile = File(workDir, "${safeBase}_merged.ts")

            FileOutputStream(mergedTsFile).use { out ->
                if (initBytes != null) {
                    out.write(initBytes)
                }
                val buf = ByteArray(64 * 1024)
                for (sf in segFiles) {
                    FileInputStream(sf).use { input ->
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                        }
                    }
                }
                out.flush()
            }

            // Convert merged TS to MP4 locally via FFmpegKit, with automatic TS fallback
            val finalMp4 = File(targetDir, "${safeBase}.mp4")
            val remuxRes = FfmpegKitEngine.remuxStream(mergedTsFile.absolutePath, finalMp4, context)
            if (remuxRes.isSuccess && finalMp4.exists() && finalMp4.length() > 0L) {
                OkHttpDownloader.publishToDownloadsMediaStoreIfAppPrivate(context, finalMp4, "video/mp4", domain)
                return@withContext StreamDownloadResult(
                    file = finalMp4,
                    isSuccessful = true,
                    usedTsFallback = false,
                    segmentsDownloaded = segments.size,
                    totalSegments = segments.size
                )
            }

            // Automatic TS fallback when MP4 conversion cannot finish
            val fallbackTs = File(targetDir, "${safeBase}.ts")
            mergedTsFile.copyTo(fallbackTs, overwrite = true)
            OkHttpDownloader.publishToDownloadsMediaStoreIfAppPrivate(context, fallbackTs, "video/mp2t", domain)
            StreamDownloadResult(
                file = fallbackTs,
                isSuccessful = fallbackTs.exists() && fallbackTs.length() > 0L,
                usedTsFallback = true,
                segmentsDownloaded = segments.size,
                totalSegments = segments.size
            )
        } catch (e: Exception) {
            Log.w(TAG, "HLS stream reconstruction failed: ${e.message}")
            StreamDownloadResult(null, false, errorMessage = e.message ?: "HLS reconstruction failed")
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    suspend fun downloadDashStream(
        context: Context,
        manifestUrl: String,
        fileName: String,
        referer: String? = null,
        headers: Map<String, String>? = null,
        domain: String? = null,
        maxHeight: Int = 0,
        parallelConnections: Int = SpeedBoostDownloader.DEFAULT_CONNECTIONS,
        onProgress: ((completedSegments: Int, totalSegments: Int, bytesDownloaded: Long, speedBytesPerSec: Long) -> Unit)? = null
    ): StreamDownloadResult = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "dash_recon_${System.currentTimeMillis()}_${manifestUrl.hashCode()}")
        workDir.mkdirs()
        try {
            val xml = fetchText(manifestUrl, referer, headers)
                ?: return@withContext StreamDownloadResult(null, false, errorMessage = "Failed to fetch DASH MPD manifest")
            val parsed = parseDashManifest(xml, manifestUrl)

            val selectedVideo = if (maxHeight > 0) {
                parsed.videoRepresentations.firstOrNull { (it.height ?: Int.MAX_VALUE) <= maxHeight }
                    ?: parsed.videoRepresentations.lastOrNull()
            } else {
                parsed.videoRepresentations.firstOrNull()
            }
            val selectedAudio = parsed.audioRepresentations.firstOrNull()

            if (selectedVideo == null && selectedAudio == null) {
                return@withContext StreamDownloadResult(null, false, errorMessage = "No video or audio representations found in DASH manifest")
            }

            val totalSegs = (selectedVideo?.segmentUrls?.size ?: 0) + (selectedAudio?.segmentUrls?.size ?: 0)
            if (totalSegs == 0) {
                return@withContext StreamDownloadResult(null, false, errorMessage = "DASH manifest contained no downloadable segment URLs")
            }

            val connections = parallelConnections.coerceIn(1, SpeedBoostDownloader.MAX_CONNECTIONS)
            val semaphore = Semaphore(connections)
            val completed = AtomicInteger(0)
            val totalBytes = AtomicLong(0L)
            val startTime = System.currentTimeMillis()

            suspend fun downloadTrack(rep: DashRepresentation, outFile: File): File {
                val initBytes = rep.initUrl?.let { fetchBytes(it, referer, headers, null, null) }
                val segFiles = coroutineScope {
                    rep.segmentUrls.mapIndexed { idx, segUrl ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                val bytes = fetchBytes(segUrl, referer, headers, null, null)
                                    ?: throw IllegalStateException("Failed to fetch DASH segment $segUrl")
                                val f = File(workDir, "${rep.id}_$idx.m4s")
                                f.writeBytes(bytes)
                                val done = completed.incrementAndGet()
                                val bytesNow = totalBytes.addAndGet(bytes.size.toLong())
                                val elapsedMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                                onProgress?.invoke(done, totalSegs, bytesNow, (bytesNow * 1000L) / elapsedMs)
                                f
                            }
                        }
                    }.awaitAll()
                }
                FileOutputStream(outFile).use { out ->
                    if (initBytes != null) out.write(initBytes)
                    val buf = ByteArray(64 * 1024)
                    for (sf in segFiles) {
                        FileInputStream(sf).use { input ->
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                out.write(buf, 0, read)
                            }
                        }
                    }
                    out.flush()
                }
                return outFile
            }

            val videoFile = selectedVideo?.let { downloadTrack(it, File(workDir, "video_track.mp4")) }
            val audioFile = selectedAudio?.let { downloadTrack(it, File(workDir, "audio_track.m4a")) }

            val targetDir = OkHttpDownloader.getTargetStorageDir(context, domain)
            val safeBase = fileName.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "dash_${System.currentTimeMillis()}" }
            val finalFile = File(targetDir, "$safeBase.mp4")

            val resultFile: File? = when {
                videoFile != null && audioFile != null -> {
                    val muxRes = FfmpegKitEngine.muxAudioAndVideo(videoFile, audioFile, finalFile)
                    if (muxRes.isSuccess && finalFile.exists() && finalFile.length() > 0L) finalFile else null
                }
                videoFile != null -> {
                    videoFile.copyTo(finalFile, overwrite = true)
                    finalFile
                }
                audioFile != null -> {
                    val audioOut = File(targetDir, "$safeBase.m4a")
                    audioFile.copyTo(audioOut, overwrite = true)
                    audioOut
                }
                else -> null
            }

            if (resultFile != null && resultFile.exists() && resultFile.length() > 0L) {
                val outMime = if (resultFile.extension.equals("m4a", ignoreCase = true)) "audio/mp4" else "video/mp4"
                OkHttpDownloader.publishToDownloadsMediaStoreIfAppPrivate(context, resultFile, outMime, domain)
                StreamDownloadResult(
                    file = resultFile,
                    isSuccessful = true,
                    segmentsDownloaded = totalSegs,
                    totalSegments = totalSegs
                )
            } else {
                StreamDownloadResult(null, false, errorMessage = "Failed to mux DASH streams into MP4")
            }
        } catch (e: Exception) {
            Log.w(TAG, "DASH stream reconstruction failed: ${e.message}")
            StreamDownloadResult(null, false, errorMessage = e.message ?: "DASH reconstruction failed")
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    /**
     * Manual TS-to-MP4 converter for local `.ts` files (WMD Settings utility).
     */
    suspend fun convertTsToMp4(tsFile: File, context: Context? = null): File? {
        if (!tsFile.exists() || tsFile.length() <= 0L) return null
        val res = FfmpegKitEngine.convertToMp4(tsFile, context)
        return if (res.isSuccess) res.outputFile else null
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun fetchText(url: String, referer: String?, headers: Map<String, String>?): String? {
        val bytes = fetchBytes(url, referer, headers, null, null) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun fetchBytes(
        url: String,
        referer: String?,
        headers: Map<String, String>?,
        byteRangeStart: Long?,
        byteRangeLength: Long?
    ): ByteArray? {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", referer)
            SpeedBoostDownloader.extractOrigin(referer)?.let { reqBuilder.header("Origin", it) }
        }
        headers?.forEach { (k, v) ->
            if (!k.equals("Range", ignoreCase = true)) reqBuilder.header(k, v)
        }
        if (byteRangeStart != null && byteRangeLength != null && byteRangeLength > 0L) {
            val end = byteRangeStart + byteRangeLength - 1L
            reqBuilder.header("Range", "bytes=$byteRangeStart-$end")
        }
        return try {
            OkHttpDownloader.client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun resolveRelativeUrl(raw: String, baseUrl: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.startsWith("//")) return "https:$trimmed"
        return runCatching { URI(baseUrl).resolve(trimmed).toString() }.getOrNull()
    }

    private fun directChildElement(parent: Element, tagName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName.equals(tagName, ignoreCase = true)) {
                return node
            }
        }
        return null
    }

    private fun directChildText(parent: Element, tagName: String): String? {
        return directChildElement(parent, tagName)?.textContent?.trim()?.takeIf { it.isNotBlank() }
    }
}
