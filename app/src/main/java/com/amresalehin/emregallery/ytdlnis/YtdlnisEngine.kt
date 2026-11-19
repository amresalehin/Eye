package com.amresalehin.emregallery.ytdlnis

import android.content.Context
import android.os.Environment
import android.util.Log
import com.amresalehin.emregallery.data.AndroidMediaMuxer
import com.amresalehin.emregallery.data.GalleryDlEngine
import com.amresalehin.emregallery.data.HostRateLimiter
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.MediaDownloadWorker
import com.amresalehin.emregallery.data.OkHttpDownloader
import com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager
import com.amresalehin.emregallery.ffmpeg.FfmpegKitEngine
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class YtdlnisExecutionOutput(
    val success: Boolean,
    val outputFiles: List<File> = emptyList(),
    val logs: String = "",
    val errorMessage: String? = null
)

/**
 * Core YTDLnis engine bridging Chaquopy yt-dlp (`ytdlnis_engine.py`) and native
 * `FfmpegKitEngine` for post-processing (cut/merge, crop, chapter splitting,
 * compatible H.264/AAC mode, subtitle burning/embedding, metadata/thumbnail/lyrics).
 */
object YtdlnisEngine {
    private const val TAG = "YtdlnisEngine"

    suspend fun fetchInfo(
        context: Context,
        urlOrQuery: String,
        allowPlaylist: Boolean = true,
        flatPlaylist: Boolean = true
    ): YtdlnisFetchResult = withContext(Dispatchers.IO) {
        GalleryDlEngine.init(context)
        val effectiveUrlOrQuery = if (GalleryDlEngine.isYouTubeUrl(urlOrQuery)) {
            GalleryDlEngine.normalizeYouTubeUrl(urlOrQuery)
        } else {
            urlOrQuery
        }
        if (effectiveUrlOrQuery.startsWith("http", ignoreCase = true) && !HostRateLimiter.acquire(effectiveUrlOrQuery)) {
            val waitSec = (HostRateLimiter.remainingCooldownMs(effectiveUrlOrQuery) / 1000L).coerceAtLeast(1L)
            return@withContext YtdlnisFetchResult(
                success = false,
                errorMessage = "Rate-limit cooldown active for host (${waitSec}s remaining)"
            )
        }
        if (!GalleryDlEngine.isPythonStarted()) {
            return@withContext YtdlnisFetchResult(
                success = false,
                errorMessage = "Python runtime is not initialized yet"
            )
        }
        val repo = YtdlnisRepository.getInstance(context)
        val options = JSONObject().apply {
            put("allow_playlist", allowPlaylist)
            put("flat_playlist", flatPlaylist)
            put("timeout", DownloaderPreferencesManager.getNetworkTimeoutSync(context))
            put("retries", DownloaderPreferencesManager.getRetryCountSync(context))
            DownloaderPreferencesManager.getActiveProxyUrlSync(context)?.let { put("proxy", it) }
            GalleryDlEngine.sanitizeCookiesForUrl(effectiveUrlOrQuery, repo.findCookiesForUrl(effectiveUrlOrQuery))?.let {
                put("cookies", it)
            }
            repo.youtubePoToken.value.takeIf { it.isNotBlank() }?.let { put("po_token", it) }
        }
        try {
            val py = Python.getInstance()
            val mod = py.getModule("ytdlnis_engine")
            val raw = mod.callAttr("fetch_video_info", effectiveUrlOrQuery, options.toString()).toString()
            val parsed = parseFetchResultJson(raw)
            if (effectiveUrlOrQuery.startsWith("http", ignoreCase = true)) {
                if (parsed.success) {
                    HostRateLimiter.recordSuccess(effectiveUrlOrQuery)
                } else if (HostRateLimiter.classifyError(errorMessage = parsed.errorMessage) == HostRateLimiter.ErrorCategory.RATE_LIMITED) {
                    HostRateLimiter.recordRateLimitOrBotChallenge(effectiveUrlOrQuery)
                }
            }
            parsed
        } catch (t: Throwable) {
            Log.e(TAG, "fetchInfo failed", t)
            YtdlnisFetchResult(success = false, errorMessage = t.message ?: "Failed to fetch media info")
        }
    }

    internal fun parseFetchResultJson(raw: String): YtdlnisFetchResult {
        return try {
            val obj = JSONObject(raw)
            val success = obj.optBoolean("success", false)
            if (!success) {
                return YtdlnisFetchResult(
                    success = false,
                    errorMessage = obj.optString("error", "Unknown extraction error")
                )
            }
            val entriesArr = obj.optJSONArray("entries") ?: JSONArray()
            val entries = buildList {
                for (i in 0 until entriesArr.length()) {
                    val e = entriesArr.optJSONObject(i) ?: continue
                    val fmts = buildList {
                        e.optJSONArray("formats")?.let { fa ->
                            for (j in 0 until fa.length()) {
                                fa.optJSONObject(j)?.let { add(YtdlnisFormat.fromJson(it)) }
                            }
                        }
                    }
                    val chaps = buildList {
                        e.optJSONArray("chapters")?.let { ca ->
                            for (j in 0 until ca.length()) {
                                ca.optJSONObject(j)?.let { add(YtdlnisChapter.fromJson(it)) }
                            }
                        }
                    }
                    val subs = buildList {
                        e.optJSONArray("subtitles")?.let { sa ->
                            for (j in 0 until sa.length()) add(sa.optString(j))
                        }
                    }
                    val autoSubs = buildList {
                        e.optJSONArray("auto_captions")?.let { sa ->
                            for (j in 0 until sa.length()) add(sa.optString(j))
                        }
                    }
                    add(
                        YtdlnisVideoInfo(
                            id = e.optString("id", ""),
                            url = e.optString("url", ""),
                            title = e.optString("title", "Untitled"),
                            author = e.optString("author", ""),
                            durationSec = e.optInt("duration", 0),
                            durationString = e.optString("duration_string", "00:00"),
                            thumbnail = e.optString("thumbnail", ""),
                            description = e.optString("description", ""),
                            uploadDate = e.optString("upload_date", ""),
                            extractor = e.optString("extractor", "generic"),
                            playlistTitle = e.optString("playlist_title", ""),
                            playlistIndex = e.optInt("playlist_index", 0),
                            formats = fmts,
                            chapters = chaps,
                            subtitles = subs,
                            autoCaptions = autoSubs
                        )
                    )
                }
            }
            YtdlnisFetchResult(
                success = true,
                isPlaylist = obj.optBoolean("is_playlist", false),
                playlistTitle = obj.optString("playlist_title", ""),
                playlistUploader = obj.optString("playlist_uploader", ""),
                entries = entries
            )
        } catch (t: Throwable) {
            YtdlnisFetchResult(success = false, errorMessage = t.message ?: "Malformed response")
        }
    }

    /**
     * Pure Kotlin bulk parser mirroring ytdlnis_engine.parse_bulk_inputs so it works
     * immediately in Compose UI without waiting on Python initialization.
     */
    fun parseBulkInputsLocal(rawText: String): List<Pair<String, String>> {
        if (rawText.isBlank()) return emptyList()
        val urlRegex = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<Pair<String, String>>()
        rawText.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("//")) return@forEach
            val matches = urlRegex.findAll(line).toList()
            if (matches.isNotEmpty()) {
                matches.forEach { m ->
                    val cleaned = m.value.trimEnd('.', ',', ';', ')', ']', '}')
                    if (seen.add(cleaned)) {
                        results.add("url" to cleaned)
                    }
                }
            } else if (!line.contains(' ') && MediaClassifier.isValidWebUrl(line)) {
                val sanitized = MediaClassifier.sanitizeUrl(line)
                if (seen.add(sanitized)) {
                    results.add("url" to sanitized)
                }
            } else {
                val query = if (line.startsWith("ytsearch")) line else "ytsearch1:$line"
                if (seen.add(query)) {
                    results.add("search" to query)
                }
            }
        }
        return results
    }

    /**
     * Live filename template preview in pure Kotlin (also used before downloading).
     */
    fun previewFilenameTemplate(
        template: String,
        title: String = "Sample Video Title",
        author: String = "Creator",
        ext: String = "mp4",
        extractor: String = "youtube",
        playlistTitle: String = "Playlist",
        uploadDate: String = "20261119"
    ): String {
        val tmpl = template.trim().ifBlank { "%(title)s.%(ext)s" }
        val map = mapOf(
            "title" to sanitizeFileName(title.ifBlank { "Sample Video Title" }),
            "uploader" to sanitizeFileName(author.ifBlank { "Creator" }),
            "author" to sanitizeFileName(author.ifBlank { "Creator" }),
            "channel" to sanitizeFileName(author.ifBlank { "Creator" }),
            "ext" to ext.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "").ifBlank { "mp4" },
            "extractor" to sanitizeFileName(extractor.ifBlank { "youtube" }),
            "playlist_title" to sanitizeFileName(playlistTitle.ifBlank { "Playlist" }),
            "playlist_index" to "01",
            "upload_date" to sanitizeFileName(uploadDate.ifBlank { "20261119" }),
            "resolution" to "1080p",
            "id" to "video_id"
        )
        var out = Regex("%\\(([a-zA-Z0-9_]+)\\)s").replace(tmpl) { match ->
            map[match.groupValues[1]] ?: ""
        }
        map.forEach { (k, v) -> out = out.replace("{$k}", v) }
        return sanitizeFileName(out)
    }

    fun sanitizeFileName(raw: String, maxLen: Int = 140): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]+"), "_")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.', '_', '-')
        return (if (cleaned.isBlank()) "untitled" else cleaned).take(maxLen)
    }

    fun resolveDestinationDir(item: YtdlnisDownloadItem, context: Context? = null): File {
        val publicBase = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Emre Gallery/YTDLnis"
        )
        val sub = when (item.subfolderMode) {
            YtdlnisSubfolderMode.NONE -> null
            YtdlnisSubfolderMode.BY_WEBSITE -> sanitizeFileName(item.extractor.ifBlank { YtdlnisRepository.extractHost(item.url) ?: "web" }, 40)
            YtdlnisSubfolderMode.BY_PLAYLIST -> sanitizeFileName(item.playlistTitle.ifBlank { "Single" }, 48)
            YtdlnisSubfolderMode.BY_TYPE -> item.type.label
        }
        val publicTarget = if (sub != null) File(publicBase, sub) else publicBase
        val publicWritable = runCatching {
            if (!publicTarget.exists()) publicTarget.mkdirs()
            publicTarget.exists() && publicTarget.isDirectory && publicTarget.canWrite()
        }.getOrDefault(false)
        if (publicWritable || context == null) {
            return publicTarget
        }
        val appExternalBase = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
            "Emre Gallery/YTDLnis"
        )
        val fallbackTarget = if (sub != null) File(appExternalBase, sub) else appExternalBase
        if (!fallbackTarget.exists()) fallbackTarget.mkdirs()
        return fallbackTarget
    }

    suspend fun downloadAndProcess(
        context: Context,
        item: YtdlnisDownloadItem,
        onStatusUpdate: ((Float, String) -> Unit)? = null,
        cookiesOverride: String? = null,
        proxyOverride: String? = null,
        maxHeightOverride: Int? = null,
        videoOnlyOverride: Boolean = false,
        destinationDirOverride: File? = null
    ): YtdlnisExecutionOutput = withContext(Dispatchers.IO) {
        GalleryDlEngine.init(context)
        if (!GalleryDlEngine.isPythonStarted()) {
            return@withContext YtdlnisExecutionOutput(false, errorMessage = "Python runtime unavailable")
        }

        if (!HostRateLimiter.acquire(item.url)) {
            val waitSec = (HostRateLimiter.remainingCooldownMs(item.url) / 1000L).coerceAtLeast(1L)
            return@withContext YtdlnisExecutionOutput(
                success = false,
                errorMessage = "Host rate-limit cooldown active (${waitSec}s remaining)"
            )
        }
        val repo = YtdlnisRepository.getInstance(context)
        // Purge stale abandoned temp directories (>24h) while preserving active .part files for resume
        runCatching {
            val cutoff = System.currentTimeMillis() - 86_400_000L
            repo.getTempWorkDir().listFiles()?.forEach { f ->
                if (f.isDirectory && f.name != "job_${item.id}" && f.lastModified() < cutoff) {
                    f.deleteRecursively()
                }
            }
        }
        val workDir = File(repo.getTempWorkDir(), "job_${item.id}")
        if (!workDir.exists()) workDir.mkdirs()
        var succeeded = false

        val logBuilder = StringBuilder()
        onStatusUpdate?.invoke(0.1f, "Starting yt-dlp download...")

        val effectiveDownloadUrl = if (GalleryDlEngine.isYouTubeUrl(item.url)) {
            GalleryDlEngine.normalizeYouTubeUrl(item.url)
        } else {
            item.url
        }
        val configJson = JSONObject().apply {
            put("url", effectiveDownloadUrl)
            put("type", item.type.id)
            put("title", item.title)
            put("author", item.author)
            put("format_id", item.selectedFormatId)
            put("audio_format_ids", JSONArray(item.selectedAudioFormatIds))
            put("container", item.container)
            put("filename_template", item.filenameTemplate)
            put("video_only", videoOnlyOverride)
            put("max_height", (maxHeightOverride ?: 0).coerceAtLeast(0))
            put("download_subtitles", item.downloadSubtitles)
            put("embed_subtitles", item.embedSubtitles)
            put("burn_subtitles", item.burnSubtitles)
            put("include_auto_subtitles", item.includeAutoSubtitles)
            put("subtitle_languages", JSONArray(item.subtitleLanguages))
            put("embed_thumbnail", item.embedThumbnail)
            put("sponsorblock_action", item.sponsorBlockAction.id)
            put("sponsorblock_categories", JSONArray(item.sponsorBlockCategories.toList()))
            put("cut_sections", JSONArray().apply { item.cutSections.forEach { put(it.toJson()) } })
            put("custom_command", item.customCommand)
            put("timeout", DownloaderPreferencesManager.getNetworkTimeoutSync(context))
            put("retries", DownloaderPreferencesManager.getRetryCountSync(context))
<<<<<<< Updated upstream
            DownloaderPreferencesManager.getActiveProxyUrlSync(context)?.let { put("proxy", it) }
            GalleryDlEngine.sanitizeCookiesForUrl(effectiveDownloadUrl, repo.findCookiesForUrl(effectiveDownloadUrl))?.let {
=======
            (proxyOverride ?: DownloaderPreferencesManager.getActiveProxyUrlSync(context))
                ?.takeIf { it.isNotBlank() }?.let { put("proxy", it) }
            val rawCookies = cookiesOverride?.takeIf { it.isNotBlank() }
                ?: repo.findCookiesForUrl(effectiveDownloadUrl)
            GalleryDlEngine.sanitizeCookiesForUrl(effectiveDownloadUrl, rawCookies)?.let {
>>>>>>> Stashed changes
                put("cookies", it)
            }
            repo.youtubePoToken.value.takeIf { it.isNotBlank() }?.let { put("po_token", it) }
        }

        try {
            val py = Python.getInstance()
            val mod = py.getModule("ytdlnis_engine")
            val rawResult = mod.callAttr("download_item", configJson.toString(), workDir.absolutePath).toString()
            val resObj = JSONObject(rawResult)
            logBuilder.appendLine(resObj.optString("logs", ""))

            if (!resObj.optBoolean("success", false)) {
                return@withContext YtdlnisExecutionOutput(
                    success = false,
                    logs = logBuilder.toString(),
                    errorMessage = resObj.optString("error", "yt-dlp download failed")
                )
            }

            val primaryPath = resObj.optString("primary_file", "")
            var currentFile = File(primaryPath)
            if (!currentFile.exists()) {
                return@withContext YtdlnisExecutionOutput(
                    success = false,
                    logs = logBuilder.toString(),
                    errorMessage = "Downloaded file not found on disk"
                )
            }

<<<<<<< Updated upstream
            val destDir = resolveDestinationDir(item, context)
=======
            val destDir = destinationDirOverride ?: resolveDestinationDir(item, context)
            if (!destDir.exists()) destDir.mkdirs()
>>>>>>> Stashed changes
            val mergeAudioFiles = buildList {
                resObj.optJSONArray("merge_audio_files")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val f = File(arr.optString(i))
                        if (f.exists() && f.length() > 0L) add(f)
                    }
                }
            }
            val subtitleFiles = buildList {
                resObj.optJSONArray("subtitle_files")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val f = File(arr.optString(i))
                        if (f.exists()) add(f)
                    }
                }
            }
            val chapters = buildList {
                resObj.optJSONArray("chapters")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { add(YtdlnisChapter.fromJson(it)) }
                    }
                }
            }

            // 0. In-process video + companion audio stream muxing (replaces yt-dlp subprocess ffmpeg merger)
            if (mergeAudioFiles.isNotEmpty() && item.type == YtdlnisDownloadType.VIDEO) {
                onStatusUpdate?.invoke(0.55f, "Muxing video & audio streams...")
                val muxExt = resolveTargetExtension(item, currentFile)
                val muxedFile = File(workDir, "muxed_${currentFile.nameWithoutExtension}.$muxExt")
                var muxSucceeded = false

                if (FfmpegKitEngine.getStatus().isAvailable) {
                    val inputs = buildString {
                        append("-y -i \"${currentFile.absolutePath}\" ")
                        mergeAudioFiles.forEach { af ->
                            append("-i \"${af.absolutePath}\" ")
                        }
                        append("-map 0:v:0 ")
                        mergeAudioFiles.indices.forEach { idx ->
                            append("-map ${idx + 1}:a:0 ")
                        }
                        if (muxExt.equals("mp4", ignoreCase = true)) {
                            append("-c:v copy -c:a aac -b:a 192k -movflags +faststart ")
                        } else {
                            append("-c copy ")
                        }
                        append("\"${muxedFile.absolutePath}\"")
                    }
                    val muxRes = FfmpegKitEngine.executeCommand(inputs)
                    logBuilder.appendLine("[FFmpeg Stream Mux] rc=${muxRes.returnCode}")
                    if (muxRes.isSuccess && muxedFile.exists() && muxedFile.length() > 0L) {
                        currentFile = muxedFile
                        muxSucceeded = true
                    } else {
                        runCatching { if (muxedFile.exists()) muxedFile.delete() }
                    }
                }

                if (!muxSucceeded) {
                    val nativeMuxOut = File(workDir, "nativemux_${currentFile.nameWithoutExtension}.mp4")
                    val nativeOk = runCatching {
                        AndroidMediaMuxer.muxVideoAndAudio(currentFile, mergeAudioFiles.first(), nativeMuxOut)
                    }.getOrDefault(false)
                    logBuilder.appendLine("[AndroidMediaMuxer Fallback] ok=$nativeOk")
                    if (nativeOk && nativeMuxOut.exists() && nativeMuxOut.length() > 0L) {
                        currentFile = nativeMuxOut
                    }
                }
            }

            onStatusUpdate?.invoke(0.65f, "Applying FFmpeg post-processing...")
            val finalOutputs = mutableListOf<File>()

            // 1. Cut sections (with optional merge into single output)
            if (item.cutSections.isNotEmpty() && FfmpegKitEngine.getStatus().isAvailable) {
                val cutFiles = mutableListOf<File>()
                item.cutSections.forEachIndexed { idx, section ->
                    val cutOut = File(workDir, "cut_${idx}_${currentFile.name}")
                    val cmd = buildCutCommand(currentFile.absolutePath, cutOut.absolutePath, section)
                    val res = FfmpegKitEngine.executeCommand(cmd)
                    logBuilder.appendLine("[FFmpeg Cut #$idx] rc=${res.returnCode}")
                    if (res.isSuccess && cutOut.exists()) {
                        cutFiles.add(cutOut)
                    }
                }
                if (cutFiles.size > 1 && item.mergeCutSections) {
                    val concatList = File(workDir, "concat.txt").apply {
                        writeText(cutFiles.joinToString("\n") { "file '${it.absolutePath.replace("'", "'\\''")}'" })
                    }
                    val merged = File(workDir, "merged_${currentFile.name}")
                    val mergeCmd = "-y -f concat -safe 0 -i \"${concatList.absolutePath}\" -c copy \"${merged.absolutePath}\""
                    val mergeRes = FfmpegKitEngine.executeCommand(mergeCmd)
                    logBuilder.appendLine("[FFmpeg Merge Cuts] rc=${mergeRes.returnCode}")
                    if (mergeRes.isSuccess && merged.exists()) {
                        currentFile = merged
                    }
                } else if (cutFiles.size == 1) {
                    currentFile = cutFiles.first()
                } else if (cutFiles.size > 1) {
                    cutFiles.forEach { cf ->
                        val dest = uniqueDestFile(destDir, cf.name)
                        cf.copyTo(dest, overwrite = true)
                        finalOutputs.add(dest)
                    }
                }
            }

            // 2. Split by chapters into separate files
            if (item.splitByChapters && chapters.isNotEmpty() && FfmpegKitEngine.getStatus().isAvailable) {
                chapters.forEachIndexed { idx, ch ->
                    val safeChTitle = sanitizeFileName(ch.title, 60)
                    val ext = currentFile.extension.ifBlank { item.container.ifBlank { "mp4" } }
                    val chDest = uniqueDestFile(destDir, "${String.format("%02d", idx + 1)}_$safeChTitle.$ext")
                    val cmd = "-y -ss ${ch.startTimeSec} -to ${ch.endTimeSec} -i \"${currentFile.absolutePath}\" -c copy \"${chDest.absolutePath}\""
                    val res = FfmpegKitEngine.executeCommand(cmd)
                    logBuilder.appendLine("[FFmpeg Split Chapter ${idx + 1}] rc=${res.returnCode}")
                    if (res.isSuccess && chDest.exists()) {
                        finalOutputs.add(chDest)
                    }
                }
            }

            // 3. Crop / Compatible mode / Burn subtitles / Audio conversion / Metadata embedding
            if (finalOutputs.isEmpty()) {
                val targetExt = resolveTargetExtension(item, currentFile)
                val targetName = previewFilenameTemplate(
                    template = item.filenameTemplate,
                    title = item.title.ifBlank { resObj.optString("title", currentFile.nameWithoutExtension) },
                    author = item.author.ifBlank { resObj.optString("author", "") },
                    ext = targetExt,
                    extractor = item.extractor,
                    playlistTitle = item.playlistTitle,
                    uploadDate = item.uploadDate
                ).let { name -> if (name.endsWith(".$targetExt", ignoreCase = true)) name else "$name.$targetExt" }

                val finalDest = uniqueDestFile(destDir, targetName)
                val postCmd = buildPostProcessCommand(
                    inputPath = currentFile.absolutePath,
                    outputPath = finalDest.absolutePath,
                    item = item,
                    subtitleFile = subtitleFiles.firstOrNull()
                )
                var savedDest: File? = null
                if (postCmd != null && FfmpegKitEngine.getStatus().isAvailable) {
                    val res = FfmpegKitEngine.executeCommand(postCmd)
                    logBuilder.appendLine("[FFmpeg PostProcess] rc=${res.returnCode}")
                    if (res.isSuccess && finalDest.exists() && finalDest.length() > 0L) {
                        savedDest = finalDest
                    } else {
                        runCatching { if (finalDest.exists()) finalDest.delete() }
                        val requiresTransform = item.cropConfig.toFfmpegFilter() != null ||
                            item.burnSubtitles || item.compatibleMode ||
                            (item.type == YtdlnisDownloadType.AUDIO && !currentFile.extension.equals(targetExt, ignoreCase = true))
                        if (requiresTransform) {
                            return@withContext YtdlnisExecutionOutput(
                                success = false,
                                logs = logBuilder.toString(),
                                errorMessage = "FFmpeg post-processing failed (exit code ${res.returnCode})"
                            )
                        }
                        val actualExt = currentFile.extension.ifBlank { targetExt }
                        val safeDest = uniqueDestFile(destDir, "${finalDest.nameWithoutExtension}.$actualExt")
                        currentFile.copyTo(safeDest, overwrite = true)
                        logBuilder.appendLine("[FFmpeg Fallback] Preserved original .$actualExt container at ${safeDest.name}")
                        savedDest = safeDest
                    }
                } else {
                    val actualExt = currentFile.extension.ifBlank { targetExt }
                    val safeDest = if (actualExt.equals(targetExt, ignoreCase = true)) {
                        finalDest
                    } else {
                        uniqueDestFile(destDir, "${finalDest.nameWithoutExtension}.$actualExt")
                    }
                    currentFile.copyTo(safeDest, overwrite = true)
                    savedDest = safeDest
                }

                // Validate playable audio/video tracks via FFprobe before marking success
                if (savedDest != null && savedDest.exists() && savedDest.length() > 0L && FfmpegKitEngine.getStatus().isAvailable) {
                    val mediaInfo = FfmpegKitEngine.probeMedia(savedDest.absolutePath, context = context)
                    val isVideoOut = item.type == YtdlnisDownloadType.VIDEO
                    val isAudioOut = item.type == YtdlnisDownloadType.AUDIO
                    val validationErr = MediaDownloadWorker.validateMediaStreams(
                        isVideo = isVideoOut,
                        isAudio = isAudioOut,
                        isStream = false,
                        requireAudioWithVideo = isVideoOut && item.selectedAudioFormatIds.isNotEmpty(),
                        mediaInfo = mediaInfo
                    )
                    if (validationErr != null) {
                        logBuilder.appendLine("[Validation Failed] $validationErr")
                        runCatching { savedDest.delete() }
                        return@withContext YtdlnisExecutionOutput(
                            success = false,
                            logs = logBuilder.toString(),
                            errorMessage = validationErr
                        )
                    }
                }

                if (savedDest != null) {
                    val mime = if (item.type == YtdlnisDownloadType.AUDIO) "audio/*" else "video/*"
                    OkHttpDownloader.publishToDownloadsMediaStoreIfAppPrivate(
                        context = context,
                        file = savedDest,
                        mimeType = mime,
                        domain = "YTDLnis"
                    )
                    finalOutputs.add(savedDest)

                    if (item.saveLrcFile && item.lyricsText.isNotBlank()) {
                        val lrcFile = File(destDir, "${savedDest.nameWithoutExtension}.lrc")
                        lrcFile.writeText(item.lyricsText)
                        logBuilder.appendLine("[Lyrics] Saved ${lrcFile.name}")
                    }
                }
            }

            succeeded = finalOutputs.isNotEmpty()
            if (succeeded) {
                HostRateLimiter.recordSuccess(item.url)
            }
            onStatusUpdate?.invoke(1.0f, "Completed")
            YtdlnisExecutionOutput(
                success = succeeded,
                outputFiles = finalOutputs,
                logs = logBuilder.toString()
            )
        } catch (t: Throwable) {
            Log.e(TAG, "downloadAndProcess failed", t)
            if (HostRateLimiter.classifyError(errorMessage = t.message) == HostRateLimiter.ErrorCategory.RATE_LIMITED) {
                HostRateLimiter.recordRateLimitOrBotChallenge(item.url)
            }
            YtdlnisExecutionOutput(
                success = false,
                logs = logBuilder.toString(),
                errorMessage = t.message ?: "Download failed"
            )
        } finally {
            if (succeeded) {
                workDir.deleteRecursively()
            }
        }
    }

    internal fun buildCutCommand(inputPath: String, outputPath: String, section: YtdlnisCutSection): String {
        val start = section.startTime.trim().ifBlank { "00:00" }
        val end = section.endTime.trim()
        return if (end.isNotBlank()) {
            "-y -ss $start -to $end -i \"$inputPath\" -c copy \"$outputPath\""
        } else {
            "-y -ss $start -i \"$inputPath\" -c copy \"$outputPath\""
        }
    }

    internal fun resolveTargetExtension(item: YtdlnisDownloadItem, currentFile: File): String {
        val requested = item.container.trim().lowercase(Locale.ROOT)
        if (item.type == YtdlnisDownloadType.AUDIO) {
            return if (requested in setOf("mp3", "m4a", "opus", "flac", "wav", "ogg")) requested else "m4a"
        }
        if (item.compatibleMode) return "mp4"
        if (requested in setOf("mp4", "mkv", "webm")) return requested
        return currentFile.extension.ifBlank { "mp4" }
    }

    internal fun buildPostProcessCommand(
        inputPath: String,
        outputPath: String,
        item: YtdlnisDownloadItem,
        subtitleFile: File? = null
    ): String? {
        val vfFilters = mutableListOf<String>()
        item.cropConfig.toFfmpegFilter()?.let { vfFilters.add(it) }
        if (item.burnSubtitles && subtitleFile != null && item.type == YtdlnisDownloadType.VIDEO) {
            val escaped = subtitleFile.absolutePath.replace("\\", "/").replace(":", "\\:")
            vfFilters.add("subtitles='$escaped'")
        }

        val needsAudioEncode = item.type == YtdlnisDownloadType.AUDIO &&
            !inputPath.endsWith(".${item.container}", ignoreCase = true)
        val needsVideoEncode = vfFilters.isNotEmpty() || item.compatibleMode
        val needsMetadata = item.embedMetadata && (item.title.isNotBlank() || item.author.isNotBlank() || item.lyricsText.isNotBlank())
        val needsSubEmbed = item.embedSubtitles && !item.burnSubtitles && subtitleFile != null && item.type == YtdlnisDownloadType.VIDEO

        if (!needsAudioEncode && !needsVideoEncode && !needsMetadata && !needsSubEmbed) {
            return null
        }

        val args = mutableListOf("-y", "-i", "\"$inputPath\"")
        if (needsSubEmbed && subtitleFile != null) {
            args.addAll(listOf("-i", "\"${subtitleFile.absolutePath}\""))
        }

        if (item.type == YtdlnisDownloadType.AUDIO) {
            args.add("-vn")
            when (item.container.lowercase(Locale.ROOT)) {
                "mp3" -> args.addAll(listOf("-c:a", "libmp3lame", "-q:a", "0"))
                "opus" -> args.addAll(listOf("-c:a", "libopus", "-b:a", "192k"))
                "flac" -> args.addAll(listOf("-c:a", "flac"))
                "wav" -> args.addAll(listOf("-c:a", "pcm_s16le"))
                else -> args.addAll(listOf("-c:a", "aac", "-b:a", "256k"))
            }
        } else {
            if (vfFilters.isNotEmpty()) {
                args.addAll(listOf("-vf", "\"${vfFilters.joinToString(",")}\""))
            }
            if (needsVideoEncode) {
                args.addAll(listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart"))
            } else {
                args.addAll(listOf("-c:v", "copy", "-c:a", "copy"))
            }
            if (needsSubEmbed) {
                val subCodec = if (outputPath.endsWith(".mp4", ignoreCase = true)) "mov_text" else "srt"
                args.addAll(listOf("-c:s", subCodec))
            }
        }

        if (needsMetadata) {
            if (item.title.isNotBlank()) {
                args.addAll(listOf("-metadata", "title=\"${item.title.replace("\"", "'")}\""))
            }
            if (item.author.isNotBlank()) {
                args.addAll(listOf("-metadata", "artist=\"${item.author.replace("\"", "'")}\""))
            }
            if (item.lyricsText.isNotBlank()) {
                val cleanLyrics = item.lyricsText.take(4000).replace("\"", "'")
                args.addAll(listOf("-metadata", "lyrics=\"$cleanLyrics\""))
            }
        }

        args.add("\"$outputPath\"")
        return args.joinToString(" ")
    }

    suspend fun runTerminalCommand(context: Context, commandLine: String): YtdlnisTerminalResult =
        withContext(Dispatchers.IO) {
            GalleryDlEngine.init(context)
            if (!GalleryDlEngine.isPythonStarted()) {
                return@withContext YtdlnisTerminalResult(false, "Python runtime not initialized", 1)
            }
            try {
                val repo = YtdlnisRepository.getInstance(context)
                val destDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Emre Gallery/YTDLnis/Terminal"
                ).apply { mkdirs() }
                val py = Python.getInstance()
                val mod = py.getModule("ytdlnis_engine")
                val raw = mod.callAttr("run_terminal_command", commandLine, destDir.absolutePath).toString()
                val obj = JSONObject(raw)
                val result = YtdlnisTerminalResult(
                    success = obj.optBoolean("success", false),
                    output = obj.optString("output", ""),
                    exitCode = obj.optInt("exit_code", 1)
                )
                if (!repo.incognitoMode.value) {
                    repo.addSearchHistory(commandLine)
                }
                result
            } catch (t: Throwable) {
                YtdlnisTerminalResult(false, t.message ?: "Terminal execution error", 1)
            }
        }

    suspend fun searchLyrics(
        context: Context,
        trackName: String,
        artistName: String = ""
    ): List<YtdlnisLyricsResult> = withContext(Dispatchers.IO) {
        GalleryDlEngine.init(context)
        if (!GalleryDlEngine.isPythonStarted()) return@withContext emptyList()
        try {
            val py = Python.getInstance()
            val mod = py.getModule("ytdlnis_engine")
            val raw = mod.callAttr("search_lyrics_lrclib", trackName, artistName).toString()
            val obj = JSONObject(raw)
            if (!obj.optBoolean("success", false)) return@withContext emptyList()
            val arr = obj.optJSONArray("results") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    add(
                        YtdlnisLyricsResult(
                            id = r.optLong("id", 0L),
                            trackName = r.optString("track_name", ""),
                            artistName = r.optString("artist_name", ""),
                            albumName = r.optString("album_name", ""),
                            durationSec = r.optDouble("duration", 0.0),
                            syncedLyrics = r.optString("synced_lyrics", ""),
                            plainLyrics = r.optString("plain_lyrics", "")
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "searchLyrics failed: ${t.message}")
            emptyList()
        }
    }

    /**
     * Pure Kotlin LRC timing shifter (mirrors ytdlnis_engine.adjust_lrc_timing) for instant
     * live preview in the Lyrics Timing Editor without Python roundtrips.
     */
    fun adjustLrcTimingLocal(lrcText: String, offsetMs: Long): String {
        if (lrcText.isBlank()) return ""
        val offsetSec = offsetMs / 1000.0
        val regex = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]")
        return regex.replace(lrcText) { m ->
            val mins = m.groupValues[1].toInt()
            val secs = m.groupValues[2].toInt()
            val fracStr = m.groupValues[3].ifBlank { "00" }
            val fracSec = fracStr.toInt() / when (fracStr.length) {
                3 -> 1000.0
                2 -> 100.0
                else -> 10.0
            }
            val total = (mins * 60.0 + secs + fracSec + offsetSec).coerceAtLeast(0.0)
            var newM = (total / 60.0).toInt()
            val rem = total - newM * 60.0
            var newS = rem.toInt()
            var newCs = kotlin.math.round((rem - newS) * 100.0).toInt()
            if (newCs >= 100) {
                newS += 1
                newCs -= 100
            }
            if (newS >= 60) {
                newM += 1
                newS -= 60
            }
            String.format(Locale.US, "[%02d:%02d.%02d]", newM, newS, newCs)
        }
    }

    private fun uniqueDestFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "${stem}_$index$ext")
            index++
        }
        return candidate
    }
}
