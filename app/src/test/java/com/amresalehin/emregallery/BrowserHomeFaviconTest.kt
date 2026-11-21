package com.amresalehin.emregallery

import com.amresalehin.emregallery.ui.browserFaviconUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserHomeFaviconTest {

    @Test
    fun buildsFaviconUrlFromSiteDomain() {
        assertEquals(
            "https://www.google.com/s2/favicons?domain=example.com&sz=128",
            browserFaviconUrl("example.com")
        )
    }

    @Test
    fun stripsWwwAndNormalizesHostCase() {
        assertEquals(
            "https://www.google.com/s2/favicons?domain=example.com&sz=128",
            browserFaviconUrl("WWW.Example.COM")
        )
    }

    @Test
    fun rejectsBlankOrMalformedHosts() {
        assertEquals("", browserFaviconUrl(""))
        assertEquals("", browserFaviconUrl("   "))
        assertEquals("", browserFaviconUrl("example.com/path"))
        assertEquals("", browserFaviconUrl("example.com?query=1"))
    }
}
