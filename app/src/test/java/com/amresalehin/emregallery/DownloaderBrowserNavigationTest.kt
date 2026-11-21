package com.amresalehin.emregallery

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
    fun trimsWhitespaceAndKeepsBlankInputBlank() {
        assertEquals("https://example.com", normalizeDownloaderTargetUrl("  example.com  "))
        assertEquals("", normalizeDownloaderTargetUrl("   "))
    }
}
