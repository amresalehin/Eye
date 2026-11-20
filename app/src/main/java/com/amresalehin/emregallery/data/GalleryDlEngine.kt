package com.amresalehin.emregallery.data

import android.content.Context
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
    val displayName: String
)

data class GalleryDlOptions(
    val userAgent: String? = null,
    val cookies: String? = null,
    val timeoutSeconds: Int = 30,
    val maxHeight: Int = 0,
    val audioOnly: Boolean = false,
    val preferredAudioFormat: String = "M4A",
    val limit: Int? = null,
    val afterDate: String? = null,
    val beforeDate: String? = null,
    val includeComments: Boolean = false,
    val includePostAuthors: Boolean = false,
    val redditClientId: String? = null,
    val redditClientSecret: String? = null,
    val redditUsername: String? = null,
    val redditPassword: String? = null,
    val redditRefreshToken: String? = null,
    val proxy: String? = null,
    val formatMode: String = "video_audio",
    val downloadPlaylist: Boolean = false,
    val downloadSubtitles: Boolean = false,
    val includeAutoSubtitles: Boolean = false,
    val subtitleLanguages: List<String> = listOf("en"),
    val retries: Int = 3
)

data class GalleryDlExtractResult(
    val success: Boolean,
    val extractorName: String = "",
    val category: String = "",
    val pageTitle: String = "",
    val pageDescription: String = "",
    val previewImageUrl: String = "",
    val items: List<ScrapedMediaItem> = emptyList(),
    val errorMessage: String? = null
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
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
            isInitialized = true
            Log.d(TAG, "Chaquopy Python SDK and gallery-dl runtime initialized successfully")
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
                version = json.optString("version", "1.32.19"),
                engineName = json.optString("engine", "gallery-dl (Chaquopy)"),
                supportedExtractorsCount = json.optInt("supported_categories_count", 287),
                popularSites = if (sitesList.isNotEmpty()) sitesList else defaultPopularSites()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get gallery-dl status", e)
            GalleryDlStatus(
                available = true,
                version = "1.32.19",
                engineName = "gallery-dl 1.32.19",
                supportedExtractorsCount = 287,
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
            // Heuristic check if Python isn't active
            val lower = url.lowercase()
            for (site in defaultPopularSites()) {
                if (site.domains.any { lower.contains(it) }) {
                    return GalleryDlSupportInfo(
                        isSupported = true,
                        category = site.name.lowercase(),
                        subcategory = "",
                        extractorName = "${site.name}Extractor",
                        displayName = site.name
                    )
                }
            }
            return GalleryDlSupportInfo(false, "", "", "", "")
        }

        val heuristic = defaultPopularSites().firstOrNull { site ->
            site.domains.any { domain -> url.contains(domain, ignoreCase = true) }
        }?.let { site ->
            GalleryDlSupportInfo(
                isSupported = true,
                category = site.name.lowercase(),
                subcategory = "",
                extractorName = if (site.name == "YouTube" || site.name == "TikTok") "yt-dlp" else "${site.name}Extractor",
                displayName = site.name
            )
        }

        return try {
            val py = Python.getInstance()
            val runner = py.getModule("gallery_dl_runner")
            val checkJson = runner.callAttr("check_support", url.trim()).toString()
            val json = JSONObject(checkJson)
            if (json.optBoolean("supported", false)) {
                GalleryDlSupportInfo(
                    isSupported = true,
                    category = json.optString("category", ""),
                    subcategory = json.optString("subcategory", ""),
                    extractorName = json.optString("extractor", ""),
                    displayName = json.optString("display_name", json.optString("category", ""))
                )
            } else {
                heuristic ?: GalleryDlSupportInfo(false, "", "", "", "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkSupport error", e)
            heuristic ?: GalleryDlSupportInfo(false, "", "", "", "")
        }
    }

    /**
     * Parses extracted JSON items while preserving each item's exact source engine ("yt-dlp" or "gallery-dl").
     */
    internal fun parseExtractedItems(root: JSONObject, pageUrl: String): Pair<String, List<ScrapedMediaItem>> {
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
                "SUBTITLES" -> MediaCategory.SUBTITLES
                "FILES" -> MediaCategory.FILES
                else -> MediaCategory.IMAGES
            }

            val isVideo = obj.optBoolean("isVideo", false)
            val isAudio = obj.optBoolean("isAudio", false)
            val isStream = obj.optBoolean("isStream", false)
            val itemSource = obj.optString("source", defaultEngine).ifBlank { defaultEngine }

            val item = ScrapedMediaItem(
                id = obj.optString("id", "gdl_${System.currentTimeMillis()}_$i"),
                url = obj.getString("url"),
                title = obj.optString("title", "media_${i + 1}"),
                isVideo = isVideo,
                isAudio = isAudio,
                isStream = isStream,
                category = mediaCategory,
                format = obj.optString("format", "IMAGE"),
                mimeType = obj.optString("mimeType", if (isVideo) "video/mp4" else "image/jpeg"),
                thumbnailUrl = obj.optString("thumbnailUrl").takeIf { !it.isNullOrBlank() },
                source = itemSource,
                resolutionHint = obj.optString("resolutionHint").takeIf { !it.isNullOrBlank() },
                sizeBytes = if (obj.has("sizeBytes") && !obj.isNull("sizeBytes")) obj.getLong("sizeBytes") else null,
                durationSeconds = if (obj.has("durationSeconds") && !obj.isNull("durationSeconds")) obj.optLong("durationSeconds", -1L).takeIf { it > 0L } else null,
                width = if (obj.has("width") && !obj.isNull("width")) obj.optInt("width").takeIf { it > 0 } else null,
                height = if (obj.has("height") && !obj.isNull("height")) obj.optInt("height").takeIf { it > 0 } else null,
                provenance = MediaProvenance(
                    extractorEngine = itemSource,
                    pageUrl = pageUrl
                ),
                metadata = mapOf("source_url" to pageUrl),
                downloadHeaders = mapOf(
                    "User-Agent" to OkHttpDownloader.DEFAULT_USER_AGENT,
                    "Referer" to pageUrl
                )
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
                if (options.maxHeight > 0) put("max_height", options.maxHeight)
                if (options.audioOnly) put("audio_only", true)
                put("preferred_audio_format", options.preferredAudioFormat)
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
            val (pageTitle, itemsList) = parseExtractedItems(root, url.trim())

            GalleryDlExtractResult(
                success = true,
                extractorName = extractorName,
                pageTitle = pageTitle,
                pageDescription = root.optString("pageDescription", ""),
                previewImageUrl = root.optString("previewImageUrl", ""),
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
     * Ensures a downloaded Reddit `v.redd.it` video file contains its companion audio track,
     * probing and muxing the separate DASH audio stream if needed.
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

        val candidateAudioUrls = AndroidMediaMuxer.tryResolveRedditAudioUrls(mediaUrl)
        for (audioUrl in candidateAudioUrls) {
            val audioTempName = "${videoFile.nameWithoutExtension}_reddit_audio.m4a"
            val audioRes = OkHttpDownloader.downloadMediaFileDetailed(
                context = context,
                mediaUrl = audioUrl,
                fileName = audioTempName,
                referer = referer,
                headers = headers
            )
            val audioFile = audioRes.file
            if (audioRes.isSuccessful && audioFile != null && audioFile.exists() && audioFile.length() > 0L) {
                val muxed = AndroidMediaMuxer.muxVideoAndAudio(videoFile, audioFile, videoFile)
                runCatching { if (audioFile.exists()) audioFile.delete() }
                if (muxed) {
                    Log.d(TAG, "Successfully muxed companion Reddit DASH audio from $audioUrl")
                    break
                }
            }
        }
        videoFile
    }

    /**
     * Downloads a media file with deterministic filename generation, live progress callbacks,
     * split DASH video+audio muxing via [AndroidMediaMuxer], and OkHttp fallback.
     */
    /**
     * Rewrites video/container extensions to the preferred audio extension (e.g., .m4a, .mp3, .opus)
     * when Audio-Only Mode is active so saved audio files never retain a misleading .mp4/.webm extension.
     */
    /**
     * Inspects the file's magic header bytes and aligns a misleading `.mp4` extension to `.webm`
     * when AndroidMediaMuxer or yt-dlp wrote an EBML/WebM container (magic bytes 0x1A 0x45 0xDF 0xA3).
     */
    fun alignContainerExtension(file: File, audioOnly: Boolean = false): File {
        if (!file.exists() || file.length() < 8L) return file
        val header = runCatching {
            val buf = ByteArray(8)
            val read = file.inputStream().use { it.read(buf) }
            if (read >= 8) buf else null
        }.getOrNull() ?: return file

        val isEbmlWebm = header[0] == 0x1A.toByte() &&
            header[1] == 0x45.toByte() &&
            header[2] == 0xDF.toByte() &&
            header[3] == 0xA3.toByte()
        val isIsoMp4 = header[4] == 'f'.code.toByte() &&
            header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() &&
            header[7] == 'p'.code.toByte()

        val ext = file.extension.lowercase(java.util.Locale.ROOT)
        val desiredExt = when {
            isEbmlWebm && ext in setOf("mp4", "m4a", "mp3") -> if (audioOnly) "opus" else "webm"
            isIsoMp4 && ext in setOf("opus", "mp3", "webm") -> if (audioOnly) "m4a" else "mp4"
            else -> return file
        }

        val alignedTarget = File(file.parentFile, "${file.nameWithoutExtension}.$desiredExt")
        return runCatching {
            if (alignedTarget.exists()) alignedTarget.delete()
            if (file.renameTo(alignedTarget)) {
                alignedTarget
            } else {
                file.copyTo(alignedTarget, overwrite = true)
                file.delete()
                alignedTarget
            }
        }.getOrDefault(file)
    }

    fun resolveDownloadFileName(
        fileName: String,
        audioOnly: Boolean,
        preferredAudioFormat: String = "M4A"
    ): String {
        val trimmed = fileName.trim().ifBlank { "media" }
        if (!audioOnly) return trimmed

        val targetExt = preferredAudioFormat.trim().lowercase(java.util.Locale.ROOT)
            .takeIf { it in setOf("m4a", "opus") } ?: "m4a"
        val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "svg", "bmp", "heic", "heif", "ico")
        val audioExts = setOf("m4a", "mp3", "opus", "aac", "wav", "ogg", "flac", "wma")
        val videoOrStreamExts = setOf("mp4", "webm", "mkv", "mov", "m4v", "avi", "flv", "3gp", "ts", "m3u8", "mpd")

        val currentExt = trimmed.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        return when {
            currentExt in imageExts -> trimmed
            currentExt in audioExts -> trimmed
            currentExt in videoOrStreamExts -> "${trimmed.substringBeforeLast('.')}.$targetExt"
            !trimmed.contains('.') -> "$trimmed.$targetExt"
            else -> trimmed
        }
    }

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
    ): File? = withContext(Dispatchers.IO) {
        try {
            val qualityCap = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.getVideoQualityCapSync(context)
            val audioOnly = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.isAudioOnlySync(context)
            val preferredAudioFormat = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.getPreferredAudioFormatSync(context)

            val effectiveFileName = resolveDownloadFileName(fileName, audioOnly, preferredAudioFormat)
            val dir = OkHttpDownloader.getTargetStorageDir(context, subfolder)
            val sanitizedName = effectiveFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
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
            val headersJson = run {
                val json = JSONObject()
                headers?.forEach { (k, v) -> json.put(k, v) }
                if (qualityCap.maxHeight > 0) json.put("__max_height", qualityCap.maxHeight)
                if (audioOnly) {
                    json.put("__audio_only", true)
                    json.put("__audio_format", preferredAudioFormat.lowercase(java.util.Locale.ROOT))
                }
                json.toString()
            }

            val progressBridge = if (onProgress != null) {
                PythonProgressBridge(onProgress)
            } else null

            // Try Python download if available
            if (isAvailable()) {
                val py = Python.getInstance()
                val runner = py.getModule("gallery_dl_runner")
                val success = runner.callAttr(
                    "download_media_file",
                    mediaUrl,
                    targetFile.absolutePath,
                    referer ?: "",
                    userAgent,
                    effectiveCookies ?: "",
                    headersJson,
                    progressBridge
                ).toBoolean()

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
                    val muxedFile = if (audioOnly) targetFile else ensureRedditAudioMuxed(context, targetFile, mediaUrl, referer, headers)
                    val finalizedFile = alignContainerExtension(muxedFile, audioOnly = audioOnly)
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        arrayOf(finalizedFile.absolutePath),
                        null,
                        null
                    )
                    return@withContext finalizedFile
                }
            }

            // Fallback to OkHttp download with shared session cookies and headers
            var lastTick = System.currentTimeMillis()
            var lastBytes = 0L
            val fallbackFile = OkHttpDownloader.downloadMediaFile(
                context = context,
                mediaUrl = mediaUrl,
                fileName = fileName,
                referer = referer,
                downloadId = downloadId,
                headers = headers,
                subfolder = subfolder,
                onProgress = if (onProgress != null) { bytesRead, totalBytes ->
                    val now = System.currentTimeMillis()
                    val dt = (now - lastTick) / 1000.0
                    val speed = if (dt > 0) ((bytesRead - lastBytes) / dt).toLong() else 0L
                    lastTick = now
                    lastBytes = bytesRead
                    onProgress(bytesRead, totalBytes, speed)
                } else null
            )
            if (fallbackFile != null && fallbackFile.exists() && fallbackFile.length() > 0L) {
                return@withContext ensureRedditAudioMuxed(context, fallbackFile, mediaUrl, referer, headers)
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "downloadMedia failed", e)
            null
        }
    }

    fun defaultPopularSites(): List<GalleryDlSite> = listOf(
        GalleryDlSite("YouTube", listOf("youtube.com", "youtu.be"), "smart_display"),
        GalleryDlSite("TikTok", listOf("tiktok.com"), "smart_display"),
        GalleryDlSite("Reddit", listOf("reddit.com", "redd.it", "i.redd.it", "v.redd.it"), "reddit"),
        GalleryDlSite("Imgur", listOf("imgur.com", "i.imgur.com"), "image"),
        GalleryDlSite("Twitter / X", listOf("twitter.com", "x.com", "pbs.twimg.com"), "tag"),
        GalleryDlSite("Pinterest", listOf("pinterest.com", "i.pinimg.com"), "pin"),
        GalleryDlSite("ArtStation", listOf("artstation.com"), "palette"),
        GalleryDlSite("DeviantArt", listOf("deviantart.com"), "brush"),
        GalleryDlSite("Danbooru", listOf("donmai.us", "gelbooru.com", "safebooru.org", "yande.re"), "collections"),
        GalleryDlSite("Flickr", listOf("flickr.com"), "photo_camera"),
        GalleryDlSite("Tumblr", listOf("tumblr.com"), "feed"),
        GalleryDlSite("Pixiv", listOf("pixiv.net", "i.pximg.net"), "palette"),
        GalleryDlSite("Wikimedia", listOf("wikimedia.org", "wikipedia.org"), "public"),
        GalleryDlSite("Bluesky", listOf("bsky.app"), "cloud"),
        GalleryDlSite("500px", listOf("500px.com"), "camera_alt"),
        GalleryDlSite("Behance", listOf("behance.net"), "design_services"),
        GalleryDlSite("Bilibili", listOf("bilibili.com"), "smart_display"),
        GalleryDlSite("4chan", listOf("4chan.org", "4channel.org", "i.4cdn.org"), "forum")
    )

    fun isPythonStarted(): Boolean = try {
        Python.isStarted()
    } catch (_: Throwable) {
        false
    }

    private fun checkRunnerBoolAttr(attrName: String): Boolean {
        if (!isPythonStarted()) return false
        return try {
            val runner = Python.getInstance().getModule("gallery_dl_runner")
            runner.callAttr(attrName).toBoolean()
        } catch (_: Throwable) {
            false
        }
    }

    fun isGalleryDlAvailable(): Boolean = checkRunnerBoolAttr("is_gallery_dl_available")
    fun isYtDlpAvailable(): Boolean = checkRunnerBoolAttr("is_yt_dlp_available")
    fun isInstaloaderAvailable(): Boolean = checkRunnerBoolAttr("is_instaloader_available")
    fun isPinterestDlAvailable(): Boolean = checkRunnerBoolAttr("is_pinterest_dl_available")
    fun isRedditDlAvailable(): Boolean = checkRunnerBoolAttr("is_reddit_dl_available")
    fun isMediaDownloaderAvailable(): Boolean = checkRunnerBoolAttr("is_media_downloader_available")
    fun isMediaScraperAvailable(): Boolean = checkRunnerBoolAttr("is_media_scraper_available")

    fun isYouTubeUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.trim().lowercase(java.util.Locale.ROOT)
        return lower.contains("youtube.com/") ||
            lower.contains("youtu.be/") ||
            lower.contains("youtube-nocookie.com/")
    }

    fun normalizeYouTubeUrl(url: String): String {
        val trimmed = url.trim()
        if (!isYouTubeUrl(trimmed)) return trimmed
        return try {
            val uri = android.net.Uri.parse(trimmed)
            val host = (uri.host ?: "").lowercase(java.util.Locale.ROOT)
            val videoId = when {
                host == "youtu.be" || host.endsWith(".youtu.be") ->
                    uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
                uri.pathSegments.firstOrNull()?.equals("shorts", ignoreCase = true) == true ->
                    uri.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() }
                uri.pathSegments.firstOrNull()?.equals("embed", ignoreCase = true) == true ->
                    uri.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() }
                uri.pathSegments.firstOrNull()?.equals("live", ignoreCase = true) == true ->
                    uri.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() }
                else -> uri.getQueryParameter("v")?.takeIf { it.isNotBlank() }
            }
            if (!videoId.isNullOrBlank()) {
                "https://www.youtube.com/watch?v=$videoId"
            } else {
                trimmed
            }
        } catch (_: Throwable) {
            trimmed
        }
    }

    private val YOUTUBE_AUTH_COOKIE_KEYS = setOf(
        "SID", "SAPISID", "__Secure-3PSID", "__Secure-1PSID",
        "__Secure-3PAPISID", "__Secure-1PAPISID", "HSID", "SSID", "APISID", "LOGIN_INFO"
    )

    fun sanitizeCookiesForUrl(url: String, cookies: String?): String? {
        val raw = cookies?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (!isYouTubeUrl(url)) return raw
        val hasAuth = if (raw.startsWith("# Netscape") || raw.contains("\t")) {
            raw.lineSequence().any { line ->
                val parts = line.split('\t')
                parts.size >= 6 && parts[5].trim() in YOUTUBE_AUTH_COOKIE_KEYS
            }
        } else {
            raw.split(';').any { pair ->
                val key = pair.substringBefore('=').trim()
                key in YOUTUBE_AUTH_COOKIE_KEYS
            }
        }
        return if (hasAuth) raw else null
    }

    suspend fun extractReddit(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_reddit", url, options)

    suspend fun extractInstagram(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_instagram", url, options)

    suspend fun extractPinterest(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_pinterest", url, options)

    suspend fun extractSpotify(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_spotify", url, options)

    suspend fun extractRadioJavan(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_radiojavan", url, options)

    suspend fun extractXSpace(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_xspace", url, options)

    suspend fun extractTumblr(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_tumblr", url, options)

    suspend fun extractPixiv(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_pixiv", url, options)

    suspend fun extractTwitterMediaScraper(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_twitter_media_scraper", url, options)

    suspend fun extractFacebookMediaScraper(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_facebook_media_scraper", url, options)
}
