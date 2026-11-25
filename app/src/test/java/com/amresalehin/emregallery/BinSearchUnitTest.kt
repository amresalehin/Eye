package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.ui.filterBinItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BinSearchUnitTest {

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
        name = "Sunset Beach.jpg",
        uri = "content://media/external/images/media/1",
        mimeType = "image/jpeg",
        relativePath = "Pictures/Travel/",
        isVideo = false
    )

    private val video = mediaItem(
        id = 2L,
        name = "Concert Final Cut.mp4",
        uri = "content://media/external/video/media/2",
        mimeType = "video/mp4",
        relativePath = "Movies/Concert/",
        isVideo = true
    )

    @Test
    fun emptyOrWhitespaceQueryPreservesAllItemsAndOrder() {
        val items = listOf(photo, video)

        assertEquals(items, filterBinItems(items, ""))
        assertEquals(items, filterBinItems(items, "   "))
    }

    @Test
    fun searchMatchesNamesCaseInsensitively() {
        val items = listOf(photo, video)

        assertEquals(listOf(photo), filterBinItems(items, "sUnSeT"))
        assertEquals(listOf(video), filterBinItems(items, "FINAL CUT"))
    }

    @Test
    fun searchMatchesRelativePathMimeTypeAndUri() {
        val items = listOf(photo, video)

        assertEquals(listOf(photo), filterBinItems(items, "travel"))
        assertEquals(listOf(video), filterBinItems(items, "VIDEO/MP4"))
        assertEquals(listOf(video), filterBinItems(items, "video/media/2"))
    }

    @Test
    fun searchCanFilterByMediaKindAndReturnsNoFalseMatches() {
        val items = listOf(photo, video)

        assertEquals(listOf(photo), filterBinItems(items, "image"))
        assertEquals(listOf(video), filterBinItems(items, "video"))
        assertTrue(filterBinItems(items, "does-not-exist").isEmpty())
    }

    @Test
    fun emptyQueryReturnsOriginalListInstance() {
        val items = listOf(photo, video)
        assertSame(items, filterBinItems(items, ""))
    }
}
