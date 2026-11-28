package com.amresalehin.emregallery.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailPrewarmPolicyTest {
    @Test
    fun allowsPrewarmWhenGalleryIsIdleAndNoVisibleFetchIsRunning() {
        assertTrue(shouldAllowBackgroundThumbnailPreparation(isScrolling = false, activeVisibleRequests = 0))
    }

    @Test
    fun pausesPrewarmDuringScrolling() {
        assertFalse(shouldAllowBackgroundThumbnailPreparation(isScrolling = true, activeVisibleRequests = 0))
    }

    @Test
    fun pausesPrewarmWhileAnyVisibleFetchIsRunning() {
        assertFalse(shouldAllowBackgroundThumbnailPreparation(isScrolling = false, activeVisibleRequests = 1))
    }

    @Test
    fun pausesPrewarmWhenScrollingAndVisibleFetchesOverlap() {
        assertFalse(shouldAllowBackgroundThumbnailPreparation(isScrolling = true, activeVisibleRequests = 3))
    }
}
