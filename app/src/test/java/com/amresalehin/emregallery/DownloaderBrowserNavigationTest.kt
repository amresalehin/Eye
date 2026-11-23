package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.normalizeAndDeduplicateMediaInboxSearches
import com.amresalehin.emregallery.data.normalizeMediaInboxSearchEntry
import com.amresalehin.emregallery.ui.normalizeDownloaderTargetUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloaderBrowserNavigationTest {

    @Test
    fun addsHttpsToBareDomains() {
        assertEquals("https://example.com", normalizeDownloaderTargetUrl("example.com"))
    }

    @Test
    fun preservesExplicitHttpSchemesAndPaths() {
        assertEquals(
            "https://example.com/watch?v=1",
            normalizeDownloaderTargetUrl("https://example.com/watch?v=1")
        )
        assertEquals(
            "http://example.com/page",
            normalizeDownloaderTargetUrl("http://example.com/page")
        )
    }


    @Test
    fun canonicalizesHistoryUrlsWithoutChangingCaseSensitivePaths() {
        assertEquals(
            "https://example.com/video?id=1#section",
            normalizeMediaInboxSearchEntry(" HTTPS://Example.COM:443/a/../video?id=1#section ")
        )
        assertEquals(
            "http://example.com/Path#fragment",
            normalizeMediaInboxSearchEntry("HTTP://EXAMPLE.COM:80/Path#fragment")
        )
        assertEquals(
            "https://example.com/Video",
            normalizeMediaInboxSearchEntry("example.com/Video")
        )
    }

    @Test
    fun deduplicatesEquivalentHistoryEntriesAndKeepsNewestFirst() {
        assertEquals(
            listOf(
                "https://example.com/video?id=1#top",
                "https://example.com/other"
            ),
            normalizeAndDeduplicateMediaInboxSearches(
                listOf(
                    "HTTPS://EXAMPLE.COM:443/video?id=1#top",
                    "https://example.com/video?id=1#top",
                    "https://example.com/other"
                )
            )
        )
    }

    @Test
    fun historyNormalizationKeepsBlankEntriesOut() {
        assertEquals("", normalizeMediaInboxSearchEntry("   "))
        assertEquals(
            listOf("https://example.com/"),
            normalizeAndDeduplicateMediaInboxSearches(listOf("", "  ", "example.com"))
        )
    }

    @Test
    fun trimsWhitespaceAndKeepsBlankInputBlank() {
        assertEquals("https://example.com", normalizeDownloaderTargetUrl("  example.com  "))
        assertEquals("", normalizeDownloaderTargetUrl("   "))
    }
}
