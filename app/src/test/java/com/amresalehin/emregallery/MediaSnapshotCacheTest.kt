package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.data.MediaSnapshotCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSnapshotCacheTest {

    @Test
    fun mostRecentSelectsOnlyTheNewest180RowsInDescendingOrder() {
        val media = (0 until 500).map { index ->
            MediaItem(
                id = index.toLong(),
                uri = "content://media/$index",
                name = "image_$index.jpg",
                mimeType = "image/jpeg",
                dateAddedSeconds = index.toLong(),
                dateTakenMillis = index.toLong() * 1_000L,
                sizeBytes = 1L,
                width = 1,
                height = 1,
                relativePath = "Pictures/",
                isVideo = false
            )
        }

        val result = MediaSnapshotCache.mostRecent(media)

        assertEquals(180, result.size)
        assertEquals(499L, result.first().id)
        assertEquals(320L, result.last().id)
        assertTrue(result.zipWithNext().all { (newer, older) ->
            newer.dateTakenMillis >= older.dateTakenMillis
        })
    }

    @Test
    fun mostRecentFallsBackToDateAddedWhenDateTakenIsMissing() {
        val media = listOf(
            MediaItem(1L, "content://media/1", "older.jpg", "image/jpeg", 10L, 0L, 1L, 1, 1, "", false),
            MediaItem(2L, "content://media/2", "newer.jpg", "image/jpeg", 20L, 0L, 1L, 1, 1, "", false),
            MediaItem(3L, "content://media/3", "taken.jpg", "image/jpeg", 15L, 16_000L, 1L, 1, 1, "", false)
        )

        val result = MediaSnapshotCache.mostRecent(media)

        assertEquals(listOf(2L, 3L, 1L), result.map { it.id })
    }
}
