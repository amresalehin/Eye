package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.MediaClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditDlSupportUnitTest {

    @Test
    fun recognizesAndNormalizesRedditDlShorthands() {
        assertTrue(MediaClassifier.isRedditShorthand("r/funny"))
        assertEquals("https://www.reddit.com/r/funny/", MediaClassifier.normalizeRedditShorthand("r/funny"))
        assertEquals("https://www.reddit.com/r/funny/top", MediaClassifier.normalizeRedditShorthand("r/funny/top"))

        assertTrue(MediaClassifier.isRedditShorthand("u/patrickkfkan"))
        assertEquals("https://www.reddit.com/user/patrickkfkan/submitted/", MediaClassifier.normalizeRedditShorthand("u/patrickkfkan"))

        assertTrue(MediaClassifier.isRedditShorthand("p/1n0dpkp"))
        assertEquals("https://www.reddit.com/comments/1n0dpkp/", MediaClassifier.normalizeRedditShorthand("p/1n0dpkp"))

        assertTrue(MediaClassifier.isRedditShorthand("my/saved"))
        assertEquals("https://www.reddit.com/my/saved", MediaClassifier.normalizeRedditShorthand("my/saved"))

        assertTrue(MediaClassifier.isRedditShorthand("my/joined"))
        assertEquals("https://www.reddit.com/my/joined", MediaClassifier.normalizeRedditShorthand("my/joined"))

        assertTrue(MediaClassifier.isRedditShorthand("my/following"))
        assertEquals("https://www.reddit.com/my/following", MediaClassifier.normalizeRedditShorthand("my/following"))

        assertFalse(MediaClassifier.isRedditShorthand("not a target"))
        assertFalse(MediaClassifier.isRedditShorthand("https://example.com/r/funny"))
        assertNull(MediaClassifier.normalizeRedditShorthand("example.com"))
    }

    @Test
    fun previewReddItRewritesToIReddItButExternalPreviewDoesNot() {
        val internalPreview = "https://preview.redd.it/abc123def.jpg?width=640&auto=webp"
        val resolvedInternal = MediaClassifier.resolveOriginalMedia(internalPreview)
        assertEquals("https://i.redd.it/abc123def.jpg", resolvedInternal.mediaUrl)

        val externalPreview = "https://external-preview.redd.it/xyz987.jpg?auto=webp&s=deadbeef"
        val resolvedExternal = MediaClassifier.resolveOriginalMedia(externalPreview)
        assertEquals(externalPreview, resolvedExternal.mediaUrl)
    }
}
