package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.ScrapedMediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class WebsiteMediaDownloaderFeaturesTest {

    @Test
    fun subtitleAndFileCategoriesClassifiedCorrectly() {
        val vtt = MediaClassifier.classify("https://cdn.example.com/subs/english.vtt")
        assertEquals(MediaCategory.SUBTITLES, vtt.category)
        assertEquals("VTT", vtt.format)

        val pdf = MediaClassifier.classify("https://example.com/docs/manual.pdf")
        assertEquals(MediaCategory.FILES, pdf.category)
        assertEquals("PDF", pdf.format)

        val zip = MediaClassifier.classify("https://example.com/archive/bundle.zip")
        assertEquals(MediaCategory.FILES, zip.category)
        assertEquals("ZIP", zip.format)
    }

    @Test
    fun intelligentDeduplicationCollapsesRedundantEntries() {
        val item1 = ScrapedMediaItem(
            id = "1",
            url = "https://cdn1.example.com/a/concert_clip.mp4",
            title = "concert_clip.mp4",
            isVideo = true,
            category = MediaCategory.VIDEOS,
            sizeBytes = 5_000_000L,
            width = 1280,
            height = 720
        )
        val item2 = ScrapedMediaItem(
            id = "2",
            url = "https://cdn2.example.com/b/concert-clip.mp4",
            title = "concert-clip.mp4",
            isVideo = true,
            category = MediaCategory.VIDEOS,
            sizeBytes = 5_000_000L,
            width = 1920,
            height = 1080
        )
        val deduped = MediaClassifier.intelligentDeduplicate(listOf(item1, item2))
        assertEquals(1, deduped.size)
        assertEquals("2", deduped.first().id)
    }
}
