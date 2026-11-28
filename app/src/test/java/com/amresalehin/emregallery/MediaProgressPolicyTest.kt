package com.amresalehin.emregallery.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProgressPolicyTest {
    @Test
    fun publishesInitialPageImmediately() {
        assertTrue(
            shouldPublishMediaProgress(
                hasPublishedProgress = false,
                pendingItemCount = 72,
                isAtLoadedEnd = false,
                isScrollingNearLoadedEnd = false
            )
        )
    }

    @Test
    fun keepsBackgroundUpdatesCoarseAwayFromLoadedEnd() {
        assertFalse(
            shouldPublishMediaProgress(
                hasPublishedProgress = true,
                pendingItemCount = MEDIA_TAIL_PROGRESS_PUBLISH_THRESHOLD,
                isAtLoadedEnd = false,
                isScrollingNearLoadedEnd = false
            )
        )
    }

    @Test
    fun publishesImmediatelyWhileScrollingNearLoadedEnd() {
        assertTrue(
            shouldPublishMediaProgress(
                hasPublishedProgress = true,
                pendingItemCount = MEDIA_TAIL_PROGRESS_PUBLISH_THRESHOLD,
                isAtLoadedEnd = false,
                isScrollingNearLoadedEnd = true
            )
        )
    }

    @Test
    fun publishesImmediatelyAtLoadedEndEvenAfterFingerStops() {
        assertTrue(
            shouldPublishMediaProgress(
                hasPublishedProgress = true,
                pendingItemCount = MEDIA_TAIL_PROGRESS_PUBLISH_THRESHOLD,
                isAtLoadedEnd = true,
                isScrollingNearLoadedEnd = false
            )
        )
    }

    @Test
    fun publishesRegularLargeBackgroundBatchesWithoutScrolling() {
        assertTrue(
            shouldPublishMediaProgress(
                hasPublishedProgress = true,
                pendingItemCount = MEDIA_PROGRESS_PUBLISH_THRESHOLD,
                isAtLoadedEnd = false,
                isScrollingNearLoadedEnd = false
            )
        )
    }

    @Test
    fun doesNotPublishBeforeOneTailBatchArrives() {
        assertFalse(
            shouldPublishMediaProgress(
                hasPublishedProgress = true,
                pendingItemCount = MEDIA_TAIL_PROGRESS_PUBLISH_THRESHOLD - 1,
                isAtLoadedEnd = true,
                isScrollingNearLoadedEnd = true
            )
        )
    }
}
