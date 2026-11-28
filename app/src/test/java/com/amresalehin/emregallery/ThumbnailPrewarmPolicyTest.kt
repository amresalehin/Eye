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
    fun resumesIncompleteCheckpointAtSavedCursor() {
        assertTrue(restoreThumbnailPrewarmIndex(1000, 640, false) == 640)
    }

    @Test
    fun retriesFromBeginningWhenIncompletePassReachedEnd() {
        assertTrue(restoreThumbnailPrewarmIndex(1000, 1000, false) == 0)
    }

    @Test
    fun skipsCompletedLibraryCheckpoint() {
        assertTrue(restoreThumbnailPrewarmIndex(1000, 1000, true) == 1000)
    }

    @Test
    fun rejectsOutOfRangeCheckpoint() {
        assertTrue(restoreThumbnailPrewarmIndex(1000, 1500, true) == 0)
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
