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
                elapsedSinceLastPublishMillis = 0L
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
                elapsedSinceLastPublishMillis = 1_000L
            )
        )
    }

    @Test
    fun publishesSmallerBatchesWhenUserReachesLoadedEnd() {
        assertTrue(
            shouldPublishMediaProgress(
                hasPublishedProgress = true,
                pendingItemCount = MEDIA_TAIL_PROGRESS_PUBLISH_THRESHOLD,
                isAtLoadedEnd = true,
                elapsedSinceLastPublishMillis = MEDIA_TAIL_PROGRESS_MIN_INTERVAL_MILLIS
            )
        )
    }

    @Test
    fun rateLimitsTailUpdates() {
        assertFalse(
            shouldPublishMediaProgress(
                hasPublishedProgress = true,
                pendingItemCount = MEDIA_TAIL_PROGRESS_PUBLISH_THRESHOLD,
                isAtLoadedEnd = true,
                elapsedSinceLastPublishMillis = MEDIA_TAIL_PROGRESS_MIN_INTERVAL_MILLIS - 1L
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
                elapsedSinceLastPublishMillis = 0L
            )
        )
    }
}
