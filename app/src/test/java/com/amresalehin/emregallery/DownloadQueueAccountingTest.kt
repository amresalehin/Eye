package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.DownloadQueueManager
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config
class DownloadQueueAccountingTest {

    @Test
    fun completingSameWorkTwiceDoesNotReleaseAnotherDownloadsReservation() {
        val firstWork = UUID.randomUUID()
        val secondWork = UUID.randomUUID()

        DownloadQueueManager.markDownloadStarted(itemId = "accounting-first", workId = firstWork)
        DownloadQueueManager.markDownloadStarted(itemId = "accounting-second", workId = secondWork)
        assertTrue(DownloadQueueManager.inMemoryDownloading.value)

        DownloadQueueManager.markDownloadCompleted("accounting-first", firstWork)
        DownloadQueueManager.markDownloadCompleted("accounting-first", firstWork)

        // The duplicate completion must not decrement the second item's reservation.
        assertTrue(DownloadQueueManager.inMemoryDownloading.value)

        DownloadQueueManager.markDownloadCompleted("accounting-second", secondWork)
        assertFalse(DownloadQueueManager.inMemoryDownloading.value)
    }

    @Test
    fun completionOfReplacedWorkDoesNotClearItsNewerRetry() {
        val originalWork = UUID.randomUUID()
        val retryWork = UUID.randomUUID()

        DownloadQueueManager.markDownloadStarted(itemId = "accounting-retry", workId = originalWork)
        DownloadQueueManager.markDownloadStarted(itemId = "accounting-retry", workId = retryWork)
        assertTrue(DownloadQueueManager.inMemoryDownloading.value)

        DownloadQueueManager.markDownloadCompleted("accounting-retry", originalWork)
        assertTrue(DownloadQueueManager.inMemoryDownloading.value)

        DownloadQueueManager.markDownloadCompleted("accounting-retry", retryWork)
        assertFalse(DownloadQueueManager.inMemoryDownloading.value)
    }
}
