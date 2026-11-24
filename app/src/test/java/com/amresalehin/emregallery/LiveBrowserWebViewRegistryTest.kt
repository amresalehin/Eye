package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.LiveBrowserWebViewRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveBrowserWebViewRegistryTest {

    @Test
    fun pageKeyIgnoresFragmentAndNormalizesSchemeAndHost() {
        assertEquals(
            "https://example.com/watch?id=42",
            LiveBrowserWebViewRegistry.pageKey("HTTPS://Example.COM/watch?id=42#comments")
        )
    }

    @Test
    fun pageKeyTreatsEmptyPathAsRoot() {
        assertEquals(
            LiveBrowserWebViewRegistry.pageKey("https://example.com/"),
            LiveBrowserWebViewRegistry.pageKey("https://example.com")
        )
    }

    @Test
    fun pageKeyKeepsQueryStringSignificant() {
        val first = LiveBrowserWebViewRegistry.pageKey("https://example.com/watch?id=1")
        val second = LiveBrowserWebViewRegistry.pageKey("https://example.com/watch?id=2")

        org.junit.Assert.assertNotEquals(first, second)
    }

    @Test
    fun pageKeyRejectsNonWebUrls() {
        assertNull(LiveBrowserWebViewRegistry.pageKey("file:///storage/emulated/0/video.mp4"))
        assertNull(LiveBrowserWebViewRegistry.pageKey("blob:https://example.com/resource"))
    }
}
