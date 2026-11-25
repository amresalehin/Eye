package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.ui.filterArchiveItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveSearchUnitTest {

    private fun mediaItem(
        id: Long,
        name: String,
        uri: String,
        mimeType: String?,
        relativePath: String,
        isVideo: Boolean
    ) = MediaItem(
        id = id,
        uri = uri,
        name = name,
        mimeType = mimeType,
        dateAddedSeconds = 1_700_000_000L + id,
        dateTakenMillis = 1_700_000_000_000L + id,
        sizeBytes = 1024L,
        width = 1920,
        height = 1080,
        relativePath = relativePath,
        isVideo = isVideo
    )

    private val photo = mediaItem(
        id = 1L,
        name = "Old Town.jpg",
        uri = "content://media/external/images/media/11",
        mimeType = "image/jpeg",
        relativePath = "Pictures/Archive/",
        isVideo = false
    )

    private val video = mediaItem(
        id = 2L,
        name = "Family Trip.mp4",
        uri = "content://media/external/video/media/22",
        mimeType = "video/mp4",
        relativePath = "Movies/Trips/",
        isVideo = true
    )

    @Test
    fun emptyOrWhitespaceQueryPreservesAllItemsAndOrder() {
        val items = listOf(photo, video)

        assertEquals(items, filterArchiveItems(items, ""))
        assertEquals(items, filterArchiveItems(items, "   "))
    }

    @Test
    fun searchMatchesNamesCaseInsensitively() {
        val items = listOf(photo, video)

        assertEquals(listOf(photo), filterArchiveItems(items, "oLd ToWn"))
        assertEquals(listOf(video), filterArchiveItems(items, "FAMILY TRIP"))
    }

    @Test
    fun searchMatchesRelativePathMimeTypeAndUri() {
        val items = listOf(photo, video)

        assertEquals(listOf(photo), filterArchiveItems(items, "archive"))
        assertEquals(listOf(video), filterArchiveItems(items, "VIDEO/MP4"))
        assertEquals(listOf(video), filterArchiveItems(items, "video/media/22"))
    }

    @Test
    fun searchCanFilterByMediaKindAndReturnsNoFalseMatches() {
        val items = listOf(photo, video)

        assertEquals(listOf(photo), filterArchiveItems(items, "image"))
        assertEquals(listOf(video), filterArchiveItems(items, "video"))
        assertTrue(filterArchiveItems(items, "not-present").isEmpty())
    }

    @Test
    fun emptyQueryReturnsOriginalListInstance() {
        val items = listOf(photo, video)
        assertSame(items, filterArchiveItems(items, ""))
    }
}
