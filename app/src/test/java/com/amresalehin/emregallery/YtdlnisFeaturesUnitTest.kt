package com.amresalehin.emregallery

import com.amresalehin.emregallery.ytdlnis.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

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
}
