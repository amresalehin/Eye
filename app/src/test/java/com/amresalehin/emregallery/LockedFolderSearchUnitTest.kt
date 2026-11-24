package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.ui.filterLockedFolderItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LockedFolderSearchUnitTest {

    private fun mediaItem(id: Long, name: String, uri: String, mimeType: String?, relativePath: String, isVideo: Boolean) = MediaItem(
        id = id, uri = uri, name = name, mimeType = mimeType,
        dateAddedSeconds = 1_700_000_000L + id, dateTakenMillis = 1_700_000_000_000L + id,
        sizeBytes = 1024L, width = 1920, height = 1080, relativePath = relativePath, isVideo = isVideo
    )

    private val photo = mediaItem(1L, "Private Portrait.jpg", "file:///data/user/0/app/locked_vault/vault_1_Private_Portrait.jpg", "image/jpeg", "Locked", false)
    private val video = mediaItem(2L, "Family Video.mp4", "file:///data/user/0/app/locked_vault/vault_2_Family_Video.mp4", "video/mp4", "Locked", true)

    @Test
    fun emptyOrWhitespaceQueryPreservesOriginalItemsAndOrder() {
        val items = listOf(photo, video)
        assertEquals(items, filterLockedFolderItems(items, ""))
        assertEquals(items, filterLockedFolderItems(items, "   "))
    }

    @Test
    fun searchMatchesNamesCaseInsensitively() {
        val items = listOf(photo, video)
        assertEquals(listOf(photo), filterLockedFolderItems(items, "pOrTrAiT"))
        assertEquals(listOf(video), filterLockedFolderItems(items, "FAMILY VIDEO"))
    }

    @Test
    fun searchMatchesMimeTypeUriAndRelativePath() {
        val items = listOf(photo, video)
        assertEquals(listOf(photo), filterLockedFolderItems(items, "image/jpeg"))
        assertEquals(listOf(video), filterLockedFolderItems(items, "vault_2"))
        assertEquals(items, filterLockedFolderItems(items, "locked"))
    }

    @Test
    fun searchCanFilterByMediaKindAndReturnsNoFalseMatches() {
        val items = listOf(photo, video)
        assertEquals(listOf(photo), filterLockedFolderItems(items, "image"))
        assertEquals(listOf(video), filterLockedFolderItems(items, "video"))
        assertTrue(filterLockedFolderItems(items, "not-present").isEmpty())
    }

    @Test
    fun emptyQueryReturnsOriginalListInstance() {
        val items = listOf(photo, video)
        assertSame(items, filterLockedFolderItems(items, ""))
    }
}
