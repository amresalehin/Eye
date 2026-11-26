package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.ui.DayGroup
import com.amresalehin.emregallery.ui.flattenHomeGridRows
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeGridNavigationOrderTest {

    private fun mediaItem(id: Long, isVideo: Boolean): MediaItem = MediaItem(
        id = id,
        uri = "content://media/external/${if (isVideo) "video" else "images"}/$id",
        name = if (isVideo) "video_$id.mp4" else "photo_$id.jpg",
        mimeType = if (isVideo) "video/mp4" else "image/jpeg",
        dateAddedSeconds = id,
        dateTakenMillis = id * 1_000L,
        sizeBytes = 1_024L,
        width = 100,
        height = 100,
        relativePath = "",
        isVideo = isVideo
    )

    @Test
    fun viewerSequenceFollowsRenderedRowsAndKeepsPhotosAndVideosTogether() {
        val firstVideo = mediaItem(1L, isVideo = true)
        val interveningPhoto = mediaItem(2L, isVideo = false)
        val secondVideo = mediaItem(3L, isVideo = true)
        val finalPhoto = mediaItem(4L, isVideo = false)

        // Two display groups model a Home view grouped by folder/initial.
        // The viewer sequence must follow these actual rendered rows, not
        // regroup or filter them by media type.
        val groupedRows = listOf(
            DayGroup(
                dayKey = 10L,
                dayLabel = "Camera",
                items = listOf(firstVideo)
            ) to listOf(listOf(firstVideo)),
            DayGroup(
                dayKey = 20L,
                dayLabel = "Screenshots",
                items = listOf(interveningPhoto)
            ) to listOf(listOf(interveningPhoto)),
            DayGroup(
                dayKey = 30L,
                dayLabel = "Clips",
                items = listOf(secondVideo, finalPhoto)
            ) to listOf(listOf(secondVideo, finalPhoto))
        )

        assertEquals(
            listOf(firstVideo, interveningPhoto, secondVideo, finalPhoto),
            flattenHomeGridRows(groupedRows)
        )
    }
}
