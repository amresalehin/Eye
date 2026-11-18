package com.amresalehin.emregallery.ytdlnis

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class YtdlnisDownloadType(val id: String, val label: String) {
    AUDIO("audio", "Audio"),
    VIDEO("video", "Video"),
    COMMAND("command", "Command");

    companion object {
        fun fromId(raw: String?): YtdlnisDownloadType =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: VIDEO
    }
}

enum class YtdlnisDownloadStatus(val id: String, val label: String) {
    QUEUED("queued", "Queued"),
    ACTIVE("active", "Downloading"),
    PAUSED("paused", "Paused"),
    SCHEDULED("scheduled", "Scheduled"),
    COMPLETED("completed", "Completed"),
    ERROR("error", "Failed"),
    CANCELLED("cancelled", "Cancelled"),
    SAVED("saved", "Saved");

    companion object {
        fun fromId(raw: String?): YtdlnisDownloadStatus =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: QUEUED
    }
}

enum class YtdlnisSponsorBlockAction(val id: String, val label: String) {
    NONE("none", "Off"),
    REMOVE("remove", "Remove Segments"),
    MARK_CHAPTER("mark_chapter", "Mark as Chapters");

    companion object {
        fun fromId(raw: String?): YtdlnisSponsorBlockAction =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: NONE
    }
}

enum class YtdlnisSubfolderMode(val id: String, val label: String) {
    NONE("none", "Flat (No Subfolder)"),
    BY_WEBSITE("website", "By Website"),
    BY_PLAYLIST("playlist", "By Playlist"),
    BY_TYPE("type", "By Download Type");

    companion object {
        fun fromId(raw: String?): YtdlnisSubfolderMode =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: NONE
    }
}

enum class YtdlnisPlaylistSort(val label: String) {
    DEFAULT("Default Order"),
    TITLE("Title"),
    AUTHOR("Author"),
    DURATION("Duration"),
    DATE("Upload Date")
}

data class YtdlnisFormat(
    val formatId: String,
    val ext: String = "",
    val vcodec: String = "none",
    val acodec: String = "none",
    val hasVideo: Boolean = false,
    val hasAudio: Boolean = false,
    val height: Int = 0,
    val width: Int = 0,
    val fps: Double = 0.0,
    val abr: Double = 0.0,
    val tbr: Double = 0.0,
    val filesize: Long = 0L,
    val resolution: String = "",
    val formatNote: String = "",
    val language: String = ""
) {
    val displayLabel: String
        get() = buildString {
            if (hasVideo) {
                append(if (height > 0) "${height}p" else resolution.ifBlank { "Video" })
                if (fps >= 50.0) append("${fps.toInt()}")
            } else {
                append(if (abr > 0) "${abr.toInt()}kbps" else "Audio")
            }
            if (ext.isNotBlank()) append(" · ${ext.uppercase()}")
            if (language.isNotBlank()) append(" [$language]")
            if (filesize > 0L) append(" · ${formatByteSize(filesize)}")
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("format_id", formatId)
        put("ext", ext)
        put("vcodec", vcodec)
        put("acodec", acodec)
        put("has_video", hasVideo)
        put("has_audio", hasAudio)
        put("height", height)
        put("width", width)
        put("fps", fps)
        put("abr", abr)
        put("tbr", tbr)
        put("filesize", filesize)
        put("resolution", resolution)
        put("format_note", formatNote)
        put("language", language)
    }

    companion object {
        fun fromJson(obj: JSONObject): YtdlnisFormat = YtdlnisFormat(
            formatId = obj.optString("format_id", ""),
            ext = obj.optString("ext", ""),
            vcodec = obj.optString("vcodec", "none"),
            acodec = obj.optString("acodec", "none"),
            hasVideo = obj.optBoolean("has_video", false),
            hasAudio = obj.optBoolean("has_audio", false),
            height = obj.optInt("height", 0),
            width = obj.optInt("width", 0),
            fps = obj.optDouble("fps", 0.0),
            abr = obj.optDouble("abr", 0.0),
            tbr = obj.optDouble("tbr", 0.0),
            filesize = obj.optLong("filesize", 0L),
            resolution = obj.optString("resolution", ""),
            formatNote = obj.optString("format_note", ""),
            language = obj.optString("language", "")
        )
    }
}

data class YtdlnisChapter(
    val title: String,
    val startTimeSec: Double,
    val endTimeSec: Double
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("start_time", startTimeSec)
        put("end_time", endTimeSec)
    }

    companion object {
        fun fromJson(obj: JSONObject): YtdlnisChapter = YtdlnisChapter(
            title = obj.optString("title", "Chapter"),
            startTimeSec = obj.optDouble("start_time", 0.0),
            endTimeSec = obj.optDouble("end_time", 0.0)
        )
    }
}

data class YtdlnisCutSection(
    val startTime: String,
    val endTime: String,
    val label: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("start", startTime)
        put("end", endTime)
        put("label", label)
    }

    companion object {
        fun fromJson(obj: JSONObject): YtdlnisCutSection = YtdlnisCutSection(
            startTime = obj.optString("start", "00:00"),
            endTime = obj.optString("end", "00:30"),
            label = obj.optString("label", "")
        )
    }
}

data class YtdlnisCropConfig(
    val enabled: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val x: Int = 0,
    val y: Int = 0,
    val aspectPreset: String = "Original"
) {
    fun toFfmpegFilter(): String? {
        if (!enabled) return null
        return when (aspectPreset) {
            "16:9" -> "crop=ih*16/9:ih"
            "9:16" -> "crop=ih*9/16:ih"
            "1:1" -> "crop=min(iw\\,ih):min(iw\\,ih)"
            "4:3" -> "crop=ih*4/3:ih"
            else -> if (width > 0 && height > 0) "crop=$width:$height:$x:$y" else null
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("width", width)
        put("height", height)
        put("x", x)
        put("y", y)
        put("aspect_preset", aspectPreset)
    }

    companion object {
        fun fromJson(obj: JSONObject?): YtdlnisCropConfig {
            if (obj == null) return YtdlnisCropConfig()
            return YtdlnisCropConfig(
                enabled = obj.optBoolean("enabled", false),
                width = obj.optInt("width", 0),
                height = obj.optInt("height", 0),
                x = obj.optInt("x", 0),
                y = obj.optInt("y", 0),
                aspectPreset = obj.optString("aspect_preset", "Original")
            )
        }
    }
}

data class YtdlnisLyricsResult(
    val id: Long = 0L,
    val trackName: String,
    val artistName: String,
    val albumName: String = "",
    val durationSec: Double = 0.0,
    val syncedLyrics: String = "",
    val plainLyrics: String = ""
)

data class YtdlnisVideoInfo(
    val id: String = "",
    val url: String,
    val title: String,
    val author: String = "",
    val durationSec: Int = 0,
    val durationString: String = "00:00",
    val thumbnail: String = "",
    val description: String = "",
    val uploadDate: String = "",
    val extractor: String = "generic",
    val playlistTitle: String = "",
    val playlistIndex: Int = 0,
    val formats: List<YtdlnisFormat> = emptyList(),
    val chapters: List<YtdlnisChapter> = emptyList(),
    val subtitles: List<String> = emptyList(),
    val autoCaptions: List<String> = emptyList()
)

data class YtdlnisFetchResult(
    val success: Boolean,
    val isPlaylist: Boolean = false,
    val playlistTitle: String = "",
    val playlistUploader: String = "",
    val entries: List<YtdlnisVideoInfo> = emptyList(),
    val errorMessage: String? = null
)

data class YtdlnisDownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String = "",
    val author: String = "",
    val durationSec: Int = 0,
    val durationString: String = "",
    val thumbnailUrl: String = "",
    val uploadDate: String = "",
    val extractor: String = "",
    val playlistTitle: String = "",
    val type: YtdlnisDownloadType = YtdlnisDownloadType.VIDEO,
    val selectedFormatId: String = "best",
    val selectedAudioFormatIds: List<String> = emptyList(),
    val container: String = "mp4",
    val filenameTemplate: String = "%(title)s.%(ext)s",
    val subfolderMode: YtdlnisSubfolderMode = YtdlnisSubfolderMode.NONE,
    val cutSections: List<YtdlnisCutSection> = emptyList(),
    val mergeCutSections: Boolean = true,
    val cropConfig: YtdlnisCropConfig = YtdlnisCropConfig(),
    val splitByChapters: Boolean = false,
    val sponsorBlockAction: YtdlnisSponsorBlockAction = YtdlnisSponsorBlockAction.NONE,
    val sponsorBlockCategories: Set<String> = emptySet(),
    val embedMetadata: Boolean = true,
    val embedThumbnail: Boolean = true,
    val embedChapters: Boolean = true,
    val downloadSubtitles: Boolean = false,
    val embedSubtitles: Boolean = false,
    val burnSubtitles: Boolean = false,
    val includeAutoSubtitles: Boolean = true,
    val subtitleLanguages: List<String> = listOf("en"),
    val compatibleMode: Boolean = false,
    val lyricsText: String = "",
    val saveLrcFile: Boolean = false,
    val customCommand: String = "",
    val scheduledTimeMillis: Long = 0L,
    val incognito: Boolean = false,
    val status: YtdlnisDownloadStatus = YtdlnisDownloadStatus.QUEUED,
    val progress: Float = 0f,
    val outputFilePath: String = "",
    val logOutput: String = "",
    val errorMessage: String? = null,
    val workId: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("title", title)
        put("author", author)
        put("duration_sec", durationSec)
        put("duration_string", durationString)
        put("thumbnail_url", thumbnailUrl)
        put("upload_date", uploadDate)
        put("extractor", extractor)
        put("playlist_title", playlistTitle)
        put("type", type.id)
        put("format_id", selectedFormatId)
        put("audio_format_ids", JSONArray(selectedAudioFormatIds))
        put("container", container)
        put("filename_template", filenameTemplate)
        put("subfolder_mode", subfolderMode.id)
        put("cut_sections", JSONArray().apply { cutSections.forEach { put(it.toJson()) } })
        put("merge_cut_sections", mergeCutSections)
        put("crop_config", cropConfig.toJson())
        put("split_by_chapters", splitByChapters)
        put("sponsorblock_action", sponsorBlockAction.id)
        put("sponsorblock_categories", JSONArray(sponsorBlockCategories.toList()))
        put("embed_metadata", embedMetadata)
        put("embed_thumbnail", embedThumbnail)
        put("embed_chapters", embedChapters)
        put("download_subtitles", downloadSubtitles)
        put("embed_subtitles", embedSubtitles)
        put("burn_subtitles", burnSubtitles)
        put("include_auto_subtitles", includeAutoSubtitles)
        put("subtitle_languages", JSONArray(subtitleLanguages))
        put("compatible_mode", compatibleMode)
        put("lyrics_text", lyricsText)
        put("save_lrc_file", saveLrcFile)
        put("custom_command", customCommand)
        put("scheduled_time_millis", scheduledTimeMillis)
        put("incognito", incognito)
        put("status", status.id)
        put("progress", progress.toDouble())
        put("output_file_path", outputFilePath)
        put("log_output", logOutput)
        put("error_message", errorMessage ?: "")
        put("work_id", workId ?: "")
        put("created_at_millis", createdAtMillis)
    }

    companion object {
        fun fromJson(obj: JSONObject): YtdlnisDownloadItem {
            val audioIds = mutableListOf<String>()
            obj.optJSONArray("audio_format_ids")?.let { arr ->
                for (i in 0 until arr.length()) audioIds.add(arr.optString(i))
            }
            val cuts = mutableListOf<YtdlnisCutSection>()
            obj.optJSONArray("cut_sections")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { cuts.add(YtdlnisCutSection.fromJson(it)) }
                }
            }
            val sbCats = mutableSetOf<String>()
            obj.optJSONArray("sponsorblock_categories")?.let { arr ->
                for (i in 0 until arr.length()) sbCats.add(arr.optString(i))
            }
            val subLangs = mutableListOf<String>()
            obj.optJSONArray("subtitle_languages")?.let { arr ->
                for (i in 0 until arr.length()) subLangs.add(arr.optString(i))
            }
            return YtdlnisDownloadItem(
                id = obj.optString("id", UUID.randomUUID().toString()),
                url = obj.optString("url", ""),
                title = obj.optString("title", ""),
                author = obj.optString("author", ""),
                durationSec = obj.optInt("duration_sec", 0),
                durationString = obj.optString("duration_string", ""),
                thumbnailUrl = obj.optString("thumbnail_url", ""),
                uploadDate = obj.optString("upload_date", ""),
                extractor = obj.optString("extractor", ""),
                playlistTitle = obj.optString("playlist_title", ""),
                type = YtdlnisDownloadType.fromId(obj.optString("type", "video")),
                selectedFormatId = obj.optString("format_id", "best"),
                selectedAudioFormatIds = audioIds,
                container = obj.optString("container", "mp4"),
                filenameTemplate = obj.optString("filename_template", "%(title)s.%(ext)s"),
                subfolderMode = YtdlnisSubfolderMode.fromId(obj.optString("subfolder_mode", "none")),
                cutSections = cuts,
                mergeCutSections = obj.optBoolean("merge_cut_sections", true),
                cropConfig = YtdlnisCropConfig.fromJson(obj.optJSONObject("crop_config")),
                splitByChapters = obj.optBoolean("split_by_chapters", false),
                sponsorBlockAction = YtdlnisSponsorBlockAction.fromId(obj.optString("sponsorblock_action", "none")),
                sponsorBlockCategories = sbCats,
                embedMetadata = obj.optBoolean("embed_metadata", true),
                embedThumbnail = obj.optBoolean("embed_thumbnail", true),
                embedChapters = obj.optBoolean("embed_chapters", true),
                downloadSubtitles = obj.optBoolean("download_subtitles", false),
                embedSubtitles = obj.optBoolean("embed_subtitles", false),
                burnSubtitles = obj.optBoolean("burn_subtitles", false),
                includeAutoSubtitles = obj.optBoolean("include_auto_subtitles", true),
                subtitleLanguages = subLangs.ifEmpty { listOf("en") },
                compatibleMode = obj.optBoolean("compatible_mode", false),
                lyricsText = obj.optString("lyrics_text", ""),
                saveLrcFile = obj.optBoolean("save_lrc_file", false),
                customCommand = obj.optString("custom_command", ""),
                scheduledTimeMillis = obj.optLong("scheduled_time_millis", 0L),
                incognito = obj.optBoolean("incognito", false),
                status = YtdlnisDownloadStatus.fromId(obj.optString("status", "queued")),
                progress = obj.optDouble("progress", 0.0).toFloat(),
                outputFilePath = obj.optString("output_file_path", ""),
                logOutput = obj.optString("log_output", ""),
                errorMessage = obj.optString("error_message", "").ifBlank { null },
                workId = obj.optString("work_id", "").ifBlank { null },
                createdAtMillis = obj.optLong("created_at_millis", System.currentTimeMillis())
            )
        }
    }
}

data class YtdlnisCommandTemplate(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val command: String,
    val useAsExtraCommand: Boolean = false,
    val preferredType: YtdlnisDownloadType = YtdlnisDownloadType.COMMAND
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("command", command)
        put("use_as_extra", useAsExtraCommand)
        put("preferred_type", preferredType.id)
    }

    companion object {
        fun fromJson(obj: JSONObject): YtdlnisCommandTemplate = YtdlnisCommandTemplate(
            id = obj.optString("id", UUID.randomUUID().toString()),
            title = obj.optString("title", "Template"),
            command = obj.optString("command", ""),
            useAsExtraCommand = obj.optBoolean("use_as_extra", false),
            preferredType = YtdlnisDownloadType.fromId(obj.optString("preferred_type", "command"))
        )

        fun defaultTemplates(): List<YtdlnisCommandTemplate> = listOf(
            YtdlnisCommandTemplate(
                id = "tpl_best_mp4",
                title = "Best Compatible MP4",
                command = "-f \"bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b\" --merge-output-format mp4",
                preferredType = YtdlnisDownloadType.VIDEO
            ),
            YtdlnisCommandTemplate(
                id = "tpl_audio_mp3_320",
                title = "Audio 320kbps MP3 + Metadata",
                command = "-x --audio-format mp3 --audio-quality 0 --embed-metadata --embed-thumbnail",
                preferredType = YtdlnisDownloadType.AUDIO
            ),
            YtdlnisCommandTemplate(
                id = "tpl_subs_all",
                title = "Download All English Subtitles",
                command = "--write-subs --write-auto-subs --sub-langs \"en.*\" --sub-format srt",
                useAsExtraCommand = true,
                preferredType = YtdlnisDownloadType.VIDEO
            ),
            YtdlnisCommandTemplate(
                id = "tpl_sponsorblock",
                title = "SponsorBlock Remove Sponsors & Intros",
                command = "--sponsorblock-remove sponsor,intro,outro,selfpromo",
                useAsExtraCommand = true,
                preferredType = YtdlnisDownloadType.VIDEO
            )
        )
    }
}

data class YtdlnisObserveSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val downloadType: YtdlnisDownloadType = YtdlnisDownloadType.VIDEO,
    val intervalHours: Int = 12,
    val syncWithSource: Boolean = false,
    val enabled: Boolean = true,
    val lastCheckedMillis: Long = 0L,
    val downloadedVideoIds: Set<String> = emptySet()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", url)
        put("download_type", downloadType.id)
        put("interval_hours", intervalHours)
        put("sync_with_source", syncWithSource)
        put("enabled", enabled)
        put("last_checked_millis", lastCheckedMillis)
        put("downloaded_ids", JSONArray(downloadedVideoIds.toList()))
    }

    companion object {
        fun fromJson(obj: JSONObject): YtdlnisObserveSource {
            val ids = mutableSetOf<String>()
            obj.optJSONArray("downloaded_ids")?.let { arr ->
                for (i in 0 until arr.length()) ids.add(arr.optString(i))
            }
            return YtdlnisObserveSource(
                id = obj.optString("id", UUID.randomUUID().toString()),
                name = obj.optString("name", "Observed Source"),
                url = obj.optString("url", ""),
                downloadType = YtdlnisDownloadType.fromId(obj.optString("download_type", "video")),
                intervalHours = obj.optInt("interval_hours", 12).coerceIn(1, 168),
                syncWithSource = obj.optBoolean("sync_with_source", false),
                enabled = obj.optBoolean("enabled", true),
                lastCheckedMillis = obj.optLong("last_checked_millis", 0L),
                downloadedVideoIds = ids
            )
        }
    }
}

data class YtdlnisCookieEntry(
    val id: String = UUID.randomUUID().toString(),
    val domain: String,
    val cookiesNetscape: String,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("domain", domain)
        put("cookies_netscape", cookiesNetscape)
        put("updated_at_millis", updatedAtMillis)
    }

    companion object {
        fun fromJson(obj: JSONObject): YtdlnisCookieEntry = YtdlnisCookieEntry(
            id = obj.optString("id", UUID.randomUUID().toString()),
            domain = obj.optString("domain", ""),
            cookiesNetscape = obj.optString("cookies_netscape", ""),
            updatedAtMillis = obj.optLong("updated_at_millis", System.currentTimeMillis())
        )
    }
}

data class YtdlnisTerminalResult(
    val success: Boolean,
    val output: String,
    val exitCode: Int
)

fun formatByteSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format("%.2f GB", bytes / gb)
        bytes >= mb -> String.format("%.1f MB", bytes / mb)
        bytes >= kb -> String.format("%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}
