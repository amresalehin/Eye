package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.ExtractionCoordinator
import com.amresalehin.emregallery.data.GalleryDlEngine
import com.amresalehin.emregallery.data.RecursiveSiteCrawler
import com.amresalehin.emregallery.data.TargetMediaExpectation
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for mitchlinDEV/media-scraper feature integration in Emre Gallery.
 */
class MediaScraperFeaturesTest {

    @Test
    fun sanitizeFilename_stripsWindowsAndAndroidIllegalCharacters() {
        assertEquals("My_Photo_Test_.jpg", RecursiveSiteCrawler.sanitizeFilename("My:Photo*Test?.jpg   "))
        assertEquals("trailing_dots", RecursiveSiteCrawler.sanitizeFilename("trailing_dots... "))
        assertEquals("fallback_name", RecursiveSiteCrawler.sanitizeFilename("   ...  ", fallback = "fallback_name"))
    }

    @Test
    fun detectCaptchaChallenge_flagsBotAndCaptchaPages() {
        assertTrue(RecursiveSiteCrawler.detectCaptchaChallenge("<html><body>Please complete the CAPTCHA</body></html>"))
        assertTrue(RecursiveSiteCrawler.detectCaptchaChallenge("<div class=\"cf-turnstile\"></div>"))
        assertFalse(RecursiveSiteCrawler.detectCaptchaChallenge("<html><body><img src=\"a.jpg\"/></body></html>"))
    }

    @Test
    fun normalizeHttpsUrl_upgradesHttpAndStripsFragments() {
        assertEquals(
            "https://example.com/gallery/2",
            RecursiveSiteCrawler.normalizeHttpsUrl("/gallery/2#section", "http://Example.com/index.html")
        )
        assertNull(RecursiveSiteCrawler.normalizeHttpsUrl("javascript:void(0)", "https://example.com"))
        assertNull(RecursiveSiteCrawler.normalizeHttpsUrl("mailto:user@example.com", "https://example.com"))
    }

    @Test
    fun extractCrawlableLinks_enforcesSameDomainAndSkipsBinaryAssets() {
        val html = """
            <html><body>
              <a href="/page2">Page 2</a>
              <a href="http://example.com/page3#top">Page 3</a>
              <a href="/photo.jpg">Direct Photo</a>
              <a href="https://otherdomain.org/album">External</a>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://example.com/")
        val sameDomainLinks = RecursiveSiteCrawler.extractCrawlableLinks(
            doc = doc,
            pageUrl = "https://example.com/",
            baseHost = "example.com",
            sameDomainOnly = true
        )
        assertEquals(
            listOf("https://example.com/page2", "https://example.com/page3"),
            sameDomainLinks
        )

        val crossDomainLinks = RecursiveSiteCrawler.extractCrawlableLinks(
            doc = doc,
            pageUrl = "https://example.com/",
            baseHost = "example.com",
            sameDomainOnly = false
        )
        assertTrue(crossDomainLinks.contains("https://otherdomain.org/album"))
    }

    @Test
    fun extractionCoordinator_recognizesTumblrAndPixivAsGalleries() {
        assertEquals(
            TargetMediaExpectation.GALLERY_OR_ALBUM,
            ExtractionCoordinator.determineExpectation("https://demo.tumblr.com/post/12345")
        )
        assertEquals(
            TargetMediaExpectation.GALLERY_OR_ALBUM,
            ExtractionCoordinator.determineExpectation("https://www.pixiv.net/en/artworks/98765432")
        )
        assertTrue(GalleryDlEngine.defaultPopularSites().any { it.name == "Pixiv" })
        assertTrue(GalleryDlEngine.defaultPopularSites().any { it.name == "Tumblr" })
    }
}
