package com.amresalehin.emregallery

import com.amresalehin.emregallery.ai.AiIndexingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFolderSelectionTest {

    private data class TestMedia(
        val uri: String,
        val relativePath: String
    )

    @Test
    fun normalizeFolderPathMatchesFaceFolderBehavior() {
        assertEquals("DCIM/Camera", AiIndexingSettings.normalizeFolderPath("DCIM/Camera/"))
        assertEquals("DCIM/Camera", AiIndexingSettings.normalizeFolderPath("/DCIM/Camera"))
        assertEquals("DCIM/Camera", AiIndexingSettings.normalizeFolderPath("  DCIM/Camera/  "))
        assertEquals("Internal storage", AiIndexingSettings.normalizeFolderPath(""))
        assertEquals("Internal storage", AiIndexingSettings.normalizeFolderPath("///"))
    }

    @Test
    fun selectedFoldersFilterOnlyMatchingMedia() {
        val media = listOf(
            TestMedia("uri-camera", "DCIM/Camera/"),
            TestMedia("uri-family", "Pictures/Family"),
            TestMedia("uri-download", "Download/")
        )

        val selected = AiIndexingSettings.filterMediaByFolders(
            media = media,
            allFolders = false,
            selectedFolders = setOf("DCIM/Camera", "Download")
        ) { it.relativePath }

        assertEquals(listOf("uri-camera", "uri-download"), selected.map { it.uri })
    }

    @Test
    fun allFoldersOrEmptySelectionKeepsAllMedia() {
        val media = listOf(
            TestMedia("uri-camera", "DCIM/Camera"),
            TestMedia("uri-family", "Pictures/Family")
        )

        val allSelected = AiIndexingSettings.filterMediaByFolders(
            media = media,
            allFolders = true,
            selectedFolders = emptySet()
        ) { it.relativePath }

        val emptySelectionFallback = AiIndexingSettings.filterMediaByFolders(
            media = media,
            allFolders = false,
            selectedFolders = emptySet()
        ) { it.relativePath }

        assertEquals(media, allSelected)
        assertEquals(media, emptySelectionFallback)
        assertTrue(allSelected.size == media.size)
    }
}
