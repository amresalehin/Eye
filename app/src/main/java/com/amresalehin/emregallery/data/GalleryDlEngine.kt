package com.amresalehin.emregallery.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.Keep
import android.os.Environment
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class GalleryDlStatus(
    val available: Boolean,
    val version: String,
    val engineName: String,
    val supportedExtractorsCount: Int,
    val popularSites: List<GalleryDlSite>
)

data class GalleryDlSite(
    val name: String,
    val domains: List<String>,
    val icon: String
)

data class GalleryDlSupportInfo(
    val isSupported: Boolean,
    val category: String,
    val subcategory: String,
    val extractorName: String,
    val displayName: String,
    val engine: String = "",
    val supportedEngines: Set<String> = emptySet()
)

data class GalleryDlOptions(
    val userAgent: String? = null,
    val cookies: String? = null,
    val timeoutSeconds: Int = 30
)

data class GalleryDlExtractResult(
    val success: Boolean,
    val extractorName: String = "",
    val category: String = "",
    val pageTitle: String = "",
    val items: List<ScrapedMediaItem> = emptyList(),
    val errorMessage: String? = null
)

data class GalleryDlDownloadResult(
    val file: File?,
    val isSuccessful: Boolean,
    val statusCode: Int = -1,
    val bytesDownloaded: Long = 0L,
    val mimeType: String? = null,
    val errorMessage: String? = null,
    val retryAfterSeconds: Long? = null,
    val engineUsed: String? = null
)

data class PythonPageResponse(
    val isSuccessful: Boolean,
    val statusCode: Int,
    val contentType: String,
    val finalUrl: String,
    val bodyString: String? = null,
    val errorMessage: String? = null,
    val engineUsed: String = ""
)

/**
 * Callback bridge invoked from Python (`gallery_dl_runner.py`) during yt-dlp and requests downloads.
 */
@Keep
class PythonProgressBridge(
    private val onUpdate: (bytesRead: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
) {
    @Keep
    @Suppress("unused")
    fun onProgress(bytesRead: Long, totalBytes: Long, speedBytesPerSec: Long) {
        onUpdate(bytesRead, totalBytes, speedBytesPerSec)
    }
}

/**
 * Gallery-dl and yt-dlp integration service powered by Chaquopy Python SDK on Android.
 * Enables extracting multi-image galleries, videos, high-resolution original artwork,
 * and direct downloads from 280+ supported websites (Reddit, Imgur, X/Twitter, Pinterest,
 * ArtStation, DeviantArt, Booru boards, Flickr, Tumblr, Wikimedia Commons, etc.).
 */
object GalleryDlEngine {
    private const val TAG = "GalleryDlEngine"
    @Volatile
    private var isInitialized = false

    /**
     * Initialize only when a download/extraction actually needs Python. Call from an IO context;
     * synchronization also protects against an extraction and a resumed download starting together.
     */
    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
            isInitialized = Python.isStarted()
            if (isInitialized) {
                Log.d(TAG, "Chaquopy Python SDK and gallery-dl runtime initialized successfully")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Python runtime initialization deferred or failed: ${t.message}")
        }
    }

    fun isAvailable(): Boolean {
        return try {
            Python.isStarted()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Retrieves runtime status, version, and supported popular sites.
     */
    fun getStatus(): GalleryDlStatus {
        if (!isAvailable()) {
            return GalleryDlStatus(
                available = false,
                version = "Unavailable",
                engineName = "gallery-dl (Chaquopy Python required)",
                supportedExtractorsCount = 0,
                popularSites = defaultPopularSites()
            )
        }

        return try {
            val py = Python.getInstance()
            val runner = py.getModule("gallery_dl_runner")
            val statusJson = runner.callAttr("get_engine_status").toString()
            val json = JSONObject(statusJson)

            val sitesList = mutableListOf<GalleryDlSite>()
            val popularArr = json.optJSONArray("popular_extractors") ?: JSONArray()
            for (i in 0 until popularArr.length()) {
                val obj = popularArr.getJSONObject(i)
                val doms = mutableListOf<String>()
                val dArr = obj.optJSONArray("domains") ?: JSONArray()
                for (j in 0 until dArr.length()) {
                    doms.add(dArr.getString(j))
                }
                sitesList.add(
                    GalleryDlSite(
                        name = obj.optString("name", "Unknown"),
                        domains = doms,
                        icon = obj.optString("icon", "public")
                    )
                )
            }

            GalleryDlStatus(
                available = json.optBoolean("available", true),
                version = json.optString("version", "Unknown"),
                engineName = json.optString("engine", "Python extraction runtime"),
                supportedExtractorsCount = json.optInt(
                    "supported_extractors_count",
                    json.optInt("supported_categories_count", 0)
                ),
                popularSites = if (sitesList.isNotEmpty()) sitesList else defaultPopularSites()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get gallery-dl status", e)
            GalleryDlStatus(
                available = true,
                version = "Unknown",
                engineName = "Python extraction runtime (status unavailable)",
                supportedExtractorsCount = 0,
                popularSites = defaultPopularSites()
            )
        }
    }

    /**
     * Checks whether the target URL matches a site-specific gallery-dl or yt-dlp extractor.
     */
    fun checkSupport(url: String): GalleryDlSupportInfo {
        if (url.isBlank()) {
            return GalleryDlSupportInfo(false, "", "", "", "")
        }

        if (!isAvailable()) {
            // Never claim site support from a hand-maintained list when the
            // actual extractor registries are not running.
            return GalleryDlSupportInfo(false, "", "", "", "")
        }

        return try {
            val py = Python.getInstance()
            val runner = py.getModule("gallery_dl_runner")
            val checkJson = runner.callAttr("check_support", url.trim()).toString()
            val json = JSONObject(checkJson)
            val enginesJson = json.optJSONArray("engines")
            val engines = buildSet {
                if (enginesJson != null) {
                    for (index in 0 until enginesJson.length()) {
                        enginesJson.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
                json.optString("engine").takeIf { it.isNotBlank() }?.let(::add)
            }
            GalleryDlSupportInfo(
                isSupported = json.optBoolean("supported", engines.isNotEmpty()),
                category = json.optString("category", ""),
                subcategory = json.optString("subcategory", ""),
                extractorName = json.optString("extractor", ""),
                displayName = json.optString("display_name", json.optString("category", "")),
                engine = json.optString("engine", ""),
                supportedEngines = engines
            )
        } catch (e: Exception) {
            Log.e(TAG, "checkSupport error", e)
            GalleryDlSupportInfo(false, "", "", "", "")
        }
    }

    /**
     * Parses extracted JSON items while preserving each item's exact source engine ("yt-dlp" or "gallery-dl").
     */
    internal fun parseExtractedItems(
        root: JSONObject,
        pageUrl: String,
        cookieHeader: String? = null
    ): Pair<String, List<ScrapedMediaItem>> {
        val pageTitle = root.optString("pageTitle", "")
        val defaultEngine = root.optString("engine", "gallery-dl").ifBlank { "gallery-dl" }
        val itemsArray = root.optJSONArray("items") ?: JSONArray()
        val itemsList = mutableListOf<ScrapedMediaItem>()

        for (i in 0 until itemsArray.length()) {
            val obj = itemsArray.getJSONObject(i)
            val catStr = obj.optString("category", "IMAGES")
            val mediaCategory = when (catStr.uppercase()) {
                "VIDEOS" -> MediaCategory.VIDEOS
                "AUDIO" -> MediaCategory.AUDIO
                "STREAMS" -> MediaCategory.STREAMS
                else -> MediaCategory.IMAGES
            }

            val isStream = obj.optBoolean("isStream", false) || mediaCategory == MediaCategory.STREAMS
            val isVideo = obj.optBoolean("isVideo", false) ||
                mediaCategory == MediaCategory.VIDEOS || mediaCategory == MediaCategory.STREAMS
            val isAudio = obj.optBoolean("isAudio", false) || mediaCategory == MediaCategory.AUDIO
            val itemSource = obj.optString("source", defaultEngine).ifBlank { defaultEngine }

            // Some Reddit extractor entries carry a GIF preview extension/MIME hint even
            // when the selected payload is a v.redd.it video. Trust the resolved media
            // category over an image-only extension so the UI and downstream download
            // metadata cannot label that video as GIF.
            val rawFormat = obj.optString("format", "IMAGE").trim()
            val rawMimeType = obj.optString("mimeType", "").trim()
            val mimeVideoFormat = rawMimeType.substringAfter("video/", "")
                .substringBefore(";").uppercase(Locale.ROOT)
            val fallbackVideoFormat = when (mimeVideoFormat) {
                "WEBM" -> "WEBM"
                "QUICKTIME" -> "MOV"
                "X-MATROSKA" -> "MKV"
                "X-MSVIDEO" -> "AVI"
                else -> "MP4"
            }
            val normalizedFormat = if (
                isVideo && !isStream && rawFormat.uppercase(Locale.ROOT) in setOf(
                    "GIF", "JPG", "JPEG", "PNG", "WEBP", "AVIF", "BMP", "SVG", "IMAGE"
                )
            ) fallbackVideoFormat else rawFormat
            val normalizedMimeType = when {
                isStream && !rawMimeType.contains("mpegurl", ignoreCase = true) &&
                    !rawMimeType.contains("dash", ignoreCase = true) -> "application/x-mpegURL"
                isVideo && !isStream && !rawMimeType.startsWith("video/", ignoreCase = true) -> "video/mp4"
                isAudio && !rawMimeType.startsWith("audio/", ignoreCase = true) -> "audio/mpeg"
                rawMimeType.isNotBlank() -> rawMimeType
                isStream -> "application/x-mpegURL"
                isVideo -> "video/mp4"
                isAudio -> "audio/mpeg"
                else -> "image/jpeg"
            }
            val rawTitle = obj.optString("title", "media_" + (i + 1))
            val titleExtension = rawTitle.substringAfterLast('.', "").uppercase(Locale.ROOT)
            val normalizedTitle = if (
                isVideo && !isStream && titleExtension in setOf(
                    "GIF", "JPG", "JPEG", "PNG", "WEBP", "AVIF", "BMP"
                )
            ) "${rawTitle.substringBeforeLast('.')}.${normalizedFormat.lowercase(Locale.ROOT)}" else rawTitle

            val durationMillis = if (obj.has("durationMillis") && !obj.isNull("durationMillis")) {
                obj.optLong("durationMillis", 0L).takeIf { it > 0L }
            } else {
                null
            }

            val itemMetadata = mutableMapOf("source_url" to pageUrl)
            obj.optString("thumbnailUrl")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let { itemMetadata["thumbnail_url"] = it }
            obj.optJSONObject("metadata")?.let { metadataJson ->
                val keys = metadataJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = metadataJson.opt(key)
                    if (value != null && value != JSONObject.NULL) {
                        itemMetadata[key] = value.toString()
                    }
                }
            }
            obj.optJSONArray("formatOptions")?.let { itemMetadata["available_formats"] = it.toString() }

            val item = ScrapedMediaItem(
                id = obj.optString("id", "gdl_${System.currentTimeMillis()}_$i"),
                url = obj.getString("url"),
                title = normalizedTitle,
                isVideo = isVideo,
                isAudio = isAudio,
                isStream = isStream,
                category = mediaCategory,
                format = normalizedFormat.ifBlank { if (isVideo && !isStream) "MP4" else "IMAGE" },
                mimeType = normalizedMimeType,
                thumbnailUrl = obj.optString("thumbnailUrl").takeIf { !it.isNullOrBlank() },
                source = itemSource,
                resolutionHint = obj.optString("resolutionHint").takeIf { !it.isNullOrBlank() },
                sizeBytes = if (obj.has("sizeBytes") && !obj.isNull("sizeBytes")) obj.getLong("sizeBytes") else null,
                durationMillis = durationMillis,
                provenance = MediaProvenance(
                    extractorEngine = itemSource,
                    pageUrl = pageUrl
                ),
                metadata = itemMetadata,
                downloadHeaders = buildMap {
                    put("User-Agent", OkHttpDownloader.DEFAULT_USER_AGENT)
                    put("Referer", pageUrl)
                    cookieHeader?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                }
            )
            itemsList.add(item)
        }
        return Pair(pageTitle, itemsList)
    }

    private suspend fun executePythonExtractor(
        methodName: String,
        url: String,
        options: GalleryDlOptions
    ): GalleryDlExtractResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext GalleryDlExtractResult(
                success = false,
                errorMessage = "Chaquopy Python runtime is not active."
            )
        }

        try {
            val py = Python.getInstance()
            val runner = py.getModule("gallery_dl_runner")

            val optsJson = JSONObject().apply {
                options.userAgent?.let { put("user_agent", it) }
                options.cookies?.let { put("cookies", it) }
                put("timeout", options.timeoutSeconds)
            }.toString()

            val rawResult = runner.callAttr(methodName, url.trim(), optsJson).toString()
            val root = JSONObject(rawResult)

            val success = root.optBoolean("success", false)
            if (!success) {
                val err = root.optString("error", "Extraction failed or produced no items")
                return@withContext GalleryDlExtractResult(
                    success = false,
                    errorMessage = err
                )
            }

            val extractorName = root.optString("extractor", root.optString("engine", methodName))
            val (pageTitle, itemsList) = parseExtractedItems(
                root = root,
                pageUrl = url.trim(),
                cookieHeader = options.cookies
            )

            GalleryDlExtractResult(
                success = true,
                extractorName = extractorName,
                pageTitle = pageTitle,
                items = itemsList
            )
        } catch (e: Exception) {
            Log.e(TAG, "$methodName execution failed", e)
            GalleryDlExtractResult(
                success = false,
                errorMessage = e.localizedMessage ?: "$methodName execution failed"
            )
        }
    }

    suspend fun extractGalleryDl(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_gallery_dl", url, options)

    suspend fun extractYtDlp(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_yt_dlp", url, options)

    suspend fun extractMedia(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_media", url, options)

    /**
     * Fetches page HTML (or direct media content headers) using the Python engines
     * (`yt-dlp` and `gallery-dl`), replacing direct OkHttp HTML scraping.
     */
    suspend fun fetchPageHtml(
        url: String,
        options: GalleryDlOptions = GalleryDlOptions(),
        maxBytes: Long = 2L * 1024L * 1024L
    ): PythonPageResponse = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext PythonPageResponse(
                isSuccessful = false,
                statusCode = -1,
                contentType = "",
                finalUrl = url,
                errorMessage = "Python yt-dlp / gallery-dl engine is not available"
            )
        }

        try {
            val py = Python.getInstance()
            val runner = py.getModule("gallery_dl_runner")
            val optsJson = JSONObject().apply {
                options.userAgent?.let { put("user_agent", it) }
                options.cookies?.let { put("cookies", it) }
                put("timeout", options.timeoutSeconds)
                put("max_bytes", maxBytes)
            }.toString()

            val raw = runner.callAttr("fetch_page_html", url.trim(), optsJson).toString()
            val json = JSONObject(raw)
            PythonPageResponse(
                isSuccessful = json.optBoolean("success", false),
                statusCode = json.optInt("status_code", -1),
                contentType = json.optString("content_type", ""),
                finalUrl = json.optString("final_url", url).ifBlank { url },
                bodyString = if (json.isNull("body")) null else json.optString("body", ""),
                errorMessage = json.optString("error").takeIf { it.isNotBlank() && it != "null" },
                engineUsed = json.optString("engine", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchPageHtml failed for $url", e)
            PythonPageResponse(
                isSuccessful = false,
                statusCode = -1,
                contentType = "",
                finalUrl = url,
                errorMessage = e.localizedMessage ?: "Python engine request failed"
            )
        }
    }

    /**
     * Decodes and stores high-resolution Base64 inline data URIs directly to persistent media storage
     * without making network requests.
     */
    fun downloadDataUriMedia(
        context: Context,
        dataUri: String,
        fileName: String,
        downloadId: String? = null,
        subfolder: String? = null
    ): GalleryDlDownloadResult {
        return try {
            val targetDir = OkHttpDownloader.getTargetStorageDir(context, subfolder)
            val dataInfo = MediaClassifier.parseDataUriInfo(dataUri)
            val ext = dataInfo?.format?.lowercase(Locale.ROOT) ?: "png"
            val mimeType = dataInfo?.mimeType ?: "image/png"
            val cleanName = if (!downloadId.isNullOrBlank()) {
                val base = downloadId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                if (base.endsWith(".$ext", ignoreCase = true)) base else "$base.$ext"
            } else {
                val sanitized = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                if (sanitized.endsWith(".$ext", ignoreCase = true)) sanitized else "$sanitized.$ext"
            }

            val finalFile = File(targetDir, cleanName)
            val base64Content = dataUri.substringAfter(";base64,", "")
            if (base64Content.isBlank()) {
                return GalleryDlDownloadResult(
                    file = null,
                    isSuccessful = false,
                    statusCode = 400,
                    errorMessage = "Invalid base64 payload in data URI"
                )
            }

            val decodedBytes = android.util.Base64.decode(base64Content, android.util.Base64.DEFAULT)
            if (decodedBytes == null || decodedBytes.isEmpty()) {
                return GalleryDlDownloadResult(
                    file = null,
                    isSuccessful = false,
                    statusCode = 400,
                    errorMessage = "Failed to decode base64 data"
                )
            }

            if (mimeType != "image/svg+xml") {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                    return GalleryDlDownloadResult(
                        file = null,
                        isSuccessful = false,
                        statusCode = 422,
                        errorMessage = "Data URI raster image is invalid or corrupt"
                    )
                }
            }

            finalFile.writeBytes(decodedBytes)
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(finalFile.absolutePath),
                arrayOf(mimeType),
                null
            )

            GalleryDlDownloadResult(
                file = finalFile,
                isSuccessful = true,
                statusCode = 200,
                bytesDownloaded = finalFile.length(),
                mimeType = mimeType,
                engineUsed = "data-uri"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode data URI", e)
            GalleryDlDownloadResult(
                file = null,
                isSuccessful = false,
                statusCode = 500,
                errorMessage = e.message ?: "Failed to write data URI"
            )
        }
    }

    /**
     * Copies a browser-captured Blob from app-private staging into the user's download directory.
     * The source path is constrained to the dedicated capture directory; arbitrary file:// URLs
     * from page content are never accepted as local download sources.
     */
    private fun copyCapturedBlobMedia(
        context: Context,
        mediaUrl: String,
        fileName: String,
        downloadId: String?,
        subfolder: String?
    ): GalleryDlDownloadResult {
        return try {
            val source = File(java.net.URI(mediaUrl)).canonicalFile
            val captureRoot = File(context.applicationContext.filesDir, "browser_blob_captures").canonicalFile
            val permittedPrefix = captureRoot.path + File.separator
            if (!source.path.startsWith(permittedPrefix) || !source.isFile || source.length() <= 0L) {
                return GalleryDlDownloadResult(
                    file = null,
                    isSuccessful = false,
                    statusCode = 400,
                    errorMessage = "Captured browser media is missing or outside the app capture directory"
                )
            }

            val targetDir = OkHttpDownloader.getTargetStorageDir(context, subfolder)
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "captured_media" }
            val extension = safeName.substringAfterLast('.', "")
            val cleanId = downloadId?.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetName = if (!cleanId.isNullOrBlank()) {
                if (extension.isNotBlank() && !cleanId.endsWith(".$extension", ignoreCase = true)) "$cleanId.$extension" else cleanId
            } else safeName
            val target = File(targetDir, targetName)
            if (source.canonicalPath == target.canonicalPath) {
                return GalleryDlDownloadResult(
                    file = target,
                    isSuccessful = true,
                    statusCode = 200,
                    bytesDownloaded = target.length(),
                    mimeType = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(target.extension.lowercase(Locale.ROOT)),
                    engineUsed = "browser-blob"
                )
            }

            source.copyTo(target, overwrite = true)
            if (!target.exists() || target.length() <= 0L) {
                return GalleryDlDownloadResult(
                    file = null,
                    isSuccessful = false,
                    statusCode = 500,
                    errorMessage = "Captured browser media could not be copied to download storage"
                )
            }
            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(target.extension.lowercase(Locale.ROOT))
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf(mimeType),
                null
            )
            GalleryDlDownloadResult(
                file = target,
                isSuccessful = true,
                statusCode = 200,
                bytesDownloaded = target.length(),
                mimeType = mimeType,
                engineUsed = "browser-blob"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize browser-captured Blob", e)
            GalleryDlDownloadResult(
                file = null,
                isSuccessful = false,
                statusCode = 500,
                errorMessage = e.localizedMessage ?: "Failed to copy browser-captured media"
            )
        }
    }

    /**
     * Ensures a downloaded Reddit `v.redd.it` video file contains its companion audio track,
     * probing and muxing the separate DASH audio stream via yt-dlp / gallery-dl if needed.
     */
    suspend fun ensureRedditAudioMuxed(
        context: Context,
        videoFile: File,
        mediaUrl: String,
        referer: String?,
        headers: Map<String, String>?
    ): File = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || videoFile.length() <= 0L) return@withContext videoFile
        if (!mediaUrl.contains("v.redd.it/", ignoreCase = true)) return@withContext videoFile
        if (AndroidMediaMuxer.hasAudioTrack(videoFile)) return@withContext videoFile
        if (!isAvailable()) return@withContext videoFile

        val userAgent = headers?.get("User-Agent") ?: OkHttpDownloader.DEFAULT_USER_AGENT
        val effectiveCookies = OkHttpDownloader.sharedCookieJar.getCookieString(mediaUrl).takeIf { it.isNotBlank() }
            ?: headers?.get("Cookie")
        val headersJson = run {
            val json = JSONObject()
            headers?.forEach { (k, v) -> json.put(k, v) }
            json.put("__is_audio", true)
            json.toString()
        }

        val candidateAudioUrls = AndroidMediaMuxer.tryResolveRedditAudioUrls(mediaUrl)
        val py = Python.getInstance()
        val runner = py.getModule("gallery_dl_runner")

        for (audioUrl in candidateAudioUrls) {
            val audioTempFile = File(videoFile.parentFile ?: context.cacheDir, "${videoFile.nameWithoutExtension}_reddit_audio.m4a")
            runCatching { if (audioTempFile.exists()) audioTempFile.delete() }
            val rawJson = runCatching {
                runner.callAttr(
                    "download_media_file_detailed",
                    audioUrl,
                    audioTempFile.absolutePath,
                    referer ?: "",
                    userAgent,
                    effectiveCookies ?: "",
                    headersJson,
                    null
                ).toString()
            }.getOrNull()
            val ok = rawJson?.let { runCatching { JSONObject(it).optBoolean("success", false) }.getOrDefault(false) } == true
            if (ok && audioTempFile.exists() && audioTempFile.length() > 0L) {
                val muxed = AndroidMediaMuxer.muxVideoAndAudio(videoFile, audioTempFile, videoFile)
                runCatching { if (audioTempFile.exists()) audioTempFile.delete() }
                if (muxed) {
                    Log.d(TAG, "Successfully muxed companion Reddit DASH audio via yt-dlp/gallery-dl from $audioUrl")
                    break
                }
            } else {
                runCatching { if (audioTempFile.exists()) audioTempFile.delete() }
            }
        }
        videoFile
    }

    /**
     * Downloads any media item exclusively through yt-dlp and gallery-dl (with split DASH video+audio
     * muxing via [AndroidMediaMuxer], live progress callbacks, and detailed failure diagnostics).
     * Does NOT perform OkHttp direct downloads.
     */
    suspend fun downloadMediaDetailed(
        context: Context,
        mediaUrl: String,
        fileName: String,
        referer: String? = null,
        downloadId: String? = null,
        headers: Map<String, String>? = null,
        cookies: String? = null,
        subfolder: String? = null,
        sourceEngine: String = "",
        isVideo: Boolean = false,
        isAudio: Boolean = false,
        isStream: Boolean = false,
        onProgress: ((bytesRead: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit)? = null
    ): GalleryDlDownloadResult = withContext(Dispatchers.IO) {
        if (mediaUrl.startsWith("file:", ignoreCase = true)) {
            return@withContext copyCapturedBlobMedia(
                context = context,
                mediaUrl = mediaUrl,
                fileName = fileName,
                downloadId = downloadId,
                subfolder = subfolder
            )
        }

        if (mediaUrl.startsWith("data:image/", ignoreCase = true)) {
            return@withContext downloadDataUriMedia(
                context = context,
                dataUri = mediaUrl,
                fileName = fileName,
                downloadId = downloadId,
                subfolder = subfolder
            )
        }

        // A resumed download may run in a fresh process before any page extraction has started.
        init(context.applicationContext)
        if (!isAvailable()) {
            return@withContext GalleryDlDownloadResult(
                file = null,
                isSuccessful = false,
                statusCode = -1,
                errorMessage = "Python yt-dlp / gallery-dl engine is not available"
            )
        }

        try {
            val dir = OkHttpDownloader.getTargetStorageDir(context, subfolder)
            val sanitizedName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val deterministicBase = if (!downloadId.isNullOrBlank()) {
                val cleanId = downloadId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val ext = sanitizedName.substringAfterLast('.', "")
                if (ext.isNotEmpty() && !cleanId.endsWith(".$ext", ignoreCase = true)) {
                    "$cleanId.$ext"
                } else {
                    cleanId
                }
            } else {
                OkHttpDownloader.generateDeterministicId(mediaUrl, sanitizedName)
            }
            val targetFile = File(dir, deterministicBase)

            val userAgent = headers?.get("User-Agent") ?: OkHttpDownloader.DEFAULT_USER_AGENT
            val effectiveCookies = cookies ?: headers?.get("Cookie")
            val qualityCap = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.getVideoQualityCapSync(context)
            val audioOnly = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.isAudioOnlySync(context)
            val headersJson = run {
                val json = JSONObject()
                headers?.forEach { (k, v) -> json.put(k, v) }
                if (qualityCap.maxHeight > 0) json.put("__max_height", qualityCap.maxHeight)
                if (audioOnly) json.put("__audio_only", true)
                if (sourceEngine.isNotBlank()) json.put("__source_engine", sourceEngine)
                if (isVideo) json.put("__is_video", true)
                if (isAudio) json.put("__is_audio", true)
                if (isStream) json.put("__is_stream", true)
                json.toString()
            }

            val progressBridge = if (onProgress != null) {
                PythonProgressBridge(onProgress)
            } else null

            val py = Python.getInstance()
            val runner = py.getModule("gallery_dl_runner")
            val rawResult = runner.callAttr(
                "download_media_file_detailed",
                mediaUrl,
                targetFile.absolutePath,
                referer ?: "",
                userAgent,
                effectiveCookies ?: "",
                headersJson,
                progressBridge
            ).toString()

            val resultJson = runCatching { JSONObject(rawResult) }.getOrNull()
            val success = resultJson?.optBoolean("success", false) == true
            val statusCode = resultJson?.optInt("status_code", -1) ?: -1
            val errorMsg = resultJson?.optString("error")?.takeIf { it.isNotBlank() && it != "null" }
            val retryAfter = if (resultJson != null && resultJson.has("retry_after_seconds") && !resultJson.isNull("retry_after_seconds")) {
                resultJson.optLong("retry_after_seconds", -1L).takeIf { it > 0L }
            } else null
            val engineUsed = resultJson?.optString("engine")?.takeIf { it.isNotBlank() && it != "null" }

            val mp4VideoPart = File("${targetFile.absolutePath}.video_part.mp4")
            val mp4AudioPart = File("${targetFile.absolutePath}.audio_part.m4a")
            val webmVideoPart = File("${targetFile.absolutePath}.video_part.webm")
            val webmAudioPart = File("${targetFile.absolutePath}.audio_part.webm")
            if (mp4VideoPart.exists() && mp4AudioPart.exists()) {
                AndroidMediaMuxer.muxVideoAndAudio(mp4VideoPart, mp4AudioPart, targetFile)
            } else if (webmVideoPart.exists() && webmAudioPart.exists()) {
                AndroidMediaMuxer.muxVideoAndAudio(webmVideoPart, webmAudioPart, targetFile)
            }

            if (success && targetFile.exists() && targetFile.length() > 0L) {
                val finalizedFile = ensureRedditAudioMuxed(context, targetFile, mediaUrl, referer, headers)
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(finalizedFile.absolutePath),
                    null,
                    null
                )
                return@withContext GalleryDlDownloadResult(
                    file = finalizedFile,
                    isSuccessful = true,
                    statusCode = 200,
                    bytesDownloaded = finalizedFile.length(),
                    engineUsed = engineUsed
                )
            }

            GalleryDlDownloadResult(
                file = null,
                isSuccessful = false,
                statusCode = statusCode,
                errorMessage = errorMsg ?: "yt-dlp / gallery-dl download failed for $mediaUrl",
                retryAfterSeconds = retryAfter,
                engineUsed = engineUsed
            )
        } catch (e: Exception) {
            Log.e(TAG, "downloadMediaDetailed failed", e)
            GalleryDlDownloadResult(
                file = null,
                isSuccessful = false,
                statusCode = -1,
                errorMessage = e.localizedMessage ?: "Download error"
            )
        }
    }

    /**
     * Downloads a media file exclusively via yt-dlp and gallery-dl.
     */
    suspend fun downloadMedia(
        context: Context,
        mediaUrl: String,
        fileName: String,
        referer: String? = null,
        downloadId: String? = null,
        headers: Map<String, String>? = null,
        cookies: String? = null,
        subfolder: String? = null,
        onProgress: ((bytesRead: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit)? = null
    ): File? = downloadMediaDetailed(
        context = context,
        mediaUrl = mediaUrl,
        fileName = fileName,
        referer = referer,
        downloadId = downloadId,
        headers = headers,
        cookies = cookies,
        subfolder = subfolder,
        onProgress = onProgress
    ).file

    fun defaultPopularSites(): List<GalleryDlSite> = listOf(
        GalleryDlSite("Reddit", listOf("reddit.com", "redd.it", "i.redd.it", "v.redd.it"), "reddit"),
        GalleryDlSite("Imgur", listOf("imgur.com", "i.imgur.com"), "image"),
        GalleryDlSite("Twitter / X", listOf("twitter.com", "x.com", "pbs.twimg.com"), "tag"),
        GalleryDlSite("Pinterest", listOf("pinterest.com", "i.pinimg.com"), "pin"),
        GalleryDlSite("ArtStation", listOf("artstation.com"), "palette"),
        GalleryDlSite("DeviantArt", listOf("deviantart.com"), "brush"),
        GalleryDlSite("Danbooru", listOf("donmai.us", "gelbooru.com", "safebooru.org", "yande.re"), "collections"),
        GalleryDlSite("Flickr", listOf("flickr.com"), "photo_camera"),
        GalleryDlSite("Tumblr", listOf("tumblr.com"), "feed"),
        GalleryDlSite("Wikimedia", listOf("wikimedia.org", "wikipedia.org"), "public"),
        GalleryDlSite("Bluesky", listOf("bsky.app"), "cloud"),
        GalleryDlSite("500px", listOf("500px.com"), "camera_alt"),
        GalleryDlSite("Behance", listOf("behance.net"), "design_services"),
        GalleryDlSite("Bilibili", listOf("bilibili.com"), "smart_display"),
        GalleryDlSite("4chan", listOf("4chan.org", "4channel.org", "i.4cdn.org"), "forum")
    )
}
