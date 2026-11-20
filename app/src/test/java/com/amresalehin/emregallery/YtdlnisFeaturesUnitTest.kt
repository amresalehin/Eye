package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.DownloadActivityItem
import com.amresalehin.emregallery.data.DownloadActivityStatus
import com.amresalehin.emregallery.ui.ytdlnis.*
import com.amresalehin.emregallery.ytdlnis.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YtdlnisFeaturesUnitTest {

    @Test
    fun parseBulkInputsLocal_extractsUrlsAndSearchQueries() {
        val raw = """
            # Sample bulk file
            https://youtube.com/watch?v=abc123
            https://youtube.com/watch?v=abc123
            ambient study music
            https://vimeo.com/987654,
        """.trimIndent()

        val parsed = YtdlnisEngine.parseBulkInputsLocal(raw)
        assertEquals(3, parsed.size)
        assertEquals("url" to "https://youtube.com/watch?v=abc123", parsed[0])
        assertEquals("search" to "ytsearch1:ambient study music", parsed[1])
        assertEquals("url" to "https://vimeo.com/987654", parsed[2])
    }

    @Test
    fun previewFilenameTemplate_replacesTokensAndSanitizes() {
        val rendered = YtdlnisEngine.previewFilenameTemplate(
            template = "%(upload_date)s_%(author)s_%(title)s.%(ext)s",
            title = "Test: Video/Clip?",
            author = "Creator Name",
            ext = "MP4",
            uploadDate = "20261119"
        )
        assertEquals("20261119_Creator Name_Test_ Video_Clip.mp4", rendered)
    }

    @Test
    fun adjustLrcTimingLocal_shiftsAndClamps() {
        val lrc = "[00:02.50]First\n[00:59.50]Second"
        val forward = YtdlnisEngine.adjustLrcTimingLocal(lrc, 1000L)
        assertTrue(forward.contains("[00:03.50]First"))
        assertTrue(forward.contains("[01:00.50]Second"))

        val clamped = YtdlnisEngine.adjustLrcTimingLocal(lrc, -10_000L)
        assertTrue(clamped.contains("[00:00.00]First"))
    }

    @Test
    fun buildPostProcessCommand_appliesCropCompatibleAndMetadata() {
        val item = YtdlnisDownloadItem(
            url = "https://youtube.com/watch?v=test",
            title = "Demo Video",
            author = "Amre",
            type = YtdlnisDownloadType.VIDEO,
            container = "mp4",
            cropConfig = YtdlnisCropConfig(enabled = true, aspectPreset = "9:16"),
            compatibleMode = true,
            embedMetadata = true
        )
        val cmd = YtdlnisEngine.buildPostProcessCommand("/tmp/in.webm", "/tmp/out.mp4", item)
        assertNotNull(cmd)
        assertTrue(cmd!!.contains("crop=ih*9/16:ih"))
        assertTrue(cmd.contains("libx264"))
        assertTrue(cmd.contains("title=\"Demo Video\""))
        assertTrue(cmd.contains("artist=\"Amre\""))
    }

    @Test
    fun downloadItem_jsonRoundTripPreservesAllFields() {
        val original = YtdlnisDownloadItem(
            url = "https://youtube.com/watch?v=xyz",
            title = "Roundtrip",
            author = "Author",
            type = YtdlnisDownloadType.AUDIO,
            selectedFormatId = "251",
            selectedAudioFormatIds = listOf("140", "251"),
            container = "mp3",
            cutSections = listOf(YtdlnisCutSection("00:10", "00:45", "Chorus")),
            sponsorBlockAction = YtdlnisSponsorBlockAction.REMOVE,
            sponsorBlockCategories = setOf("sponsor", "intro"),
            saveLrcFile = true,
            lyricsText = "[00:01.00]Hello"
        )
        val restored = YtdlnisDownloadItem.fromJson(original.toJson())
        assertEquals(original.url, restored.url)
        assertEquals(original.type, restored.type)
        assertEquals(original.selectedAudioFormatIds, restored.selectedAudioFormatIds)
        assertEquals(1, restored.cutSections.size)
        assertEquals("00:10", restored.cutSections.first().startTime)
        assertEquals(YtdlnisSponsorBlockAction.REMOVE, restored.sponsorBlockAction)
        assertTrue(restored.saveLrcFile)
    }

    @Test
    fun resolveTargetExtension_respectsAudioAndCompatibleMode() {
        val audioItem = YtdlnisDownloadItem(url = "https://example.com", type = YtdlnisDownloadType.AUDIO, container = "opus")
        assertEquals("opus", YtdlnisEngine.resolveTargetExtension(audioItem, File("in.webm")))

        val compatVideo = YtdlnisDownloadItem(url = "https://example.com", type = YtdlnisDownloadType.VIDEO, container = "webm", compatibleMode = true)
        assertEquals("mp4", YtdlnisEngine.resolveTargetExtension(compatVideo, File("in.webm")))
    }

    @Test
    fun downloaderCanvasMode_usesSearchAsHomeTab() {
        val modes = DownloaderCanvasMode.entries.map { it.name }
        assertEquals(listOf("SEARCH", "QUEUE", "SETTINGS"), modes)
        assertFalse("Grid is a media layout, not a canvas mode tab", modes.contains("GRID"))
    }

    @Test
    fun getSearchHomeHistoryPreview_capsPastDownloadsAtFiveMostRecent() {
        val allDownloads = (1..20).map { idx ->
            DownloadActivityItem(
                id = "dl-$idx",
                title = "Media File $idx",
                mediaUrl = "https://example.com/file_$idx.mp4",
                isVideo = true,
                format = "MP4",
                status = DownloadActivityStatus.FINISHED,
                timestampMillis = idx * 1000L
            )
        }
        val preview = getSearchHomeHistoryPreview(allDownloads)
        assertEquals(MAX_SEARCH_HOME_HISTORY_PREVIEW, preview.size)
        assertEquals(listOf("dl-20", "dl-19", "dl-18", "dl-17", "dl-16"), preview.map { it.id })
    }

    @Test
    fun queueFormatAndKindResolution_correctlyClassifiesMediaKindsAndFileTypes() {
        val videoItem = DownloadActivityItem(
            id = "v1",
            title = "Trailer.mp4",
            mediaUrl = "https://cdn.example.com/trailer.mp4",
            isVideo = true,
            format = "MP4"
        )
        assertEquals("MP4", resolveQueueItemFormat(videoItem))
        assertEquals(QueueMediaKindFilter.VIDEO, resolveQueueItemKind(videoItem))

        val audioItem = DownloadActivityItem(
            id = "a1",
            title = "Podcast Episode",
            mediaUrl = "https://cdn.example.com/ep1.m4a",
            isAudio = true,
            format = "M4A"
        )
        assertEquals("M4A", resolveQueueItemFormat(audioItem))
        assertEquals(QueueMediaKindFilter.AUDIO, resolveQueueItemKind(audioItem))

        val imageItem = DownloadActivityItem(
            id = "i1",
            title = "Wallpaper",
            mediaUrl = "https://cdn.example.com/wall.webp",
            isVideo = false,
            isAudio = false,
            format = "WEBP"
        )
        assertEquals("WEBP", resolveQueueItemFormat(imageItem))
        assertEquals(QueueMediaKindFilter.IMAGE, resolveQueueItemKind(imageItem))

        val streamItem = DownloadActivityItem(
            id = "s1",
            title = "Live Stream",
            mediaUrl = "https://cdn.example.com/master.m3u8",
            isVideo = true,
            isStream = true,
            format = "HLS"
        )
        assertEquals("HLS", resolveQueueItemFormat(streamItem))
        assertEquals(QueueMediaKindFilter.STREAM, resolveQueueItemKind(streamItem))
    }

    @Test
    fun matchesQueueSearch_filtersByTitleDomainFormatAndAuthor() {
        val item = DownloadActivityItem(
            id = "q1",
            title = "Synthwave Night Drive",
            mediaUrl = "https://media.example.org/synthwave.mp3",
            isAudio = true,
            format = "MP3",
            sourceDomain = "soundcloud.com",
            status = DownloadActivityStatus.FINISHED
        )
        val linkedYt = YtdlnisDownloadItem(
            url = item.mediaUrl,
            title = item.title,
            author = "RetroWaveOfficial",
            type = YtdlnisDownloadType.AUDIO,
            container = "mp3"
        )

        assertTrue(matchesQueueActivitySearch(item, linkedYt, "synthwave"))
        assertTrue(matchesQueueActivitySearch(item, linkedYt, "soundcloud"))
        assertTrue(matchesQueueActivitySearch(item, linkedYt, "mp3"))
        assertTrue(matchesQueueActivitySearch(item, linkedYt, "retrowave"))
        assertFalse(matchesQueueActivitySearch(item, linkedYt, "unrelated_query_xyz"))
    }

    @Test
    fun youtubeUrlNormalization_convertsMobileAndShortUrlsToCanonicalWatchUrl() {
        assertEquals(
            "https://www.youtube.com/watch?v=S4Q0sI2wkq4",
            com.amresalehin.emregallery.data.GalleryDlEngine.normalizeYouTubeUrl("https://m.youtube.com/watch?v=S4Q0sI2wkq4&pp=ygUFdGVzdA%3D%3D")
        )
        assertEquals(
            "https://www.youtube.com/watch?v=S4Q0sI2wkq4",
            com.amresalehin.emregallery.data.GalleryDlEngine.normalizeYouTubeUrl("https://youtu.be/S4Q0sI2wkq4?si=12345")
        )
        assertEquals(
            "https://www.youtube.com/watch?v=S4Q0sI2wkq4",
            com.amresalehin.emregallery.data.GalleryDlEngine.normalizeYouTubeUrl("https://m.youtube.com/shorts/S4Q0sI2wkq4")
        )
    }

    @Test
    fun youtubeCookieSanitization_stripsAnonymousGuestCookiesAndPreservesAuthenticatedCookies() {
        val ytUrl = "https://m.youtube.com/watch?v=S4Q0sI2wkq4"
        val guestCookies = "VISITOR_INFO1_LIVE=abc123xyz; YSC=def456; GPS=1; PREF=f4=4000000"
        assertNull(
            "Anonymous YouTube guest cookies must be stripped so yt-dlp does not disable android_vr/ios clients",
            com.amresalehin.emregallery.data.GalleryDlEngine.sanitizeCookiesForUrl(ytUrl, guestCookies)
        )

        val authCookies = "VISITOR_INFO1_LIVE=abc123xyz; SAPISID=123456/abcdef; __Secure-3PSID=token"
        assertEquals(
            authCookies,
            com.amresalehin.emregallery.data.GalleryDlEngine.sanitizeCookiesForUrl(ytUrl, authCookies)
        )

        val nonYtCookies = "session_id=guest123"
        assertEquals(
            nonYtCookies,
            com.amresalehin.emregallery.data.GalleryDlEngine.sanitizeCookiesForUrl("https://x.com/user/status/123", nonYtCookies)
        )
    }
}

