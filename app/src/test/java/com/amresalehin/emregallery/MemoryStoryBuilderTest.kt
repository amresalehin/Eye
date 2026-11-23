package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.data.MemoryStoryBuilder
import com.amresalehin.emregallery.data.StoryLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStoryBuilderTest {
    private fun item(id: Long, time: Long) = MediaItem(
        id = id,
        uri = "content://media/external/images/media/$id",
        name = "IMG_$id.jpg",
        mimeType = "image/jpeg",
        dateAddedSeconds = time / 1000L,
        dateTakenMillis = time,
        sizeBytes = 1024L,
        width = 1600,
        height = 1200,
        relativePath = "DCIM/Camera",
        isVideo = false
    )

    @Test
    fun createsSeparateStoriesForDistinctLocationEvents() {
        val start = 1_735_689_600_000L
        val firstEvent = (1L..6L).map { item(it, start + it * 5L * 60L * 1000L) }
        val secondEvent = (7L..12L).map { item(it, start + 7L * 24L * 60L * 60L * 1000L + it * 5L * 60L * 1000L) }
        val allItems = firstEvent + secondEvent
        val locations = buildMap {
            firstEvent.forEach { put(it.uri, StoryLocation(48.8566, 2.3522)) }
            secondEvent.forEach { put(it.uri, StoryLocation(51.5072, -0.1276)) }
        }
        val places = buildMap {
            firstEvent.forEach { put(it.uri, "Paris") }
            secondEvent.forEach { put(it.uri, "London") }
        }

        val stories = MemoryStoryBuilder.build(
            source = allItems,
            locations = locations,
            cachedPlaceNames = places
        )

        assertEquals(2, stories.size)
        assertEquals(setOf("At Paris", "At London"), stories.map { it.title }.toSet())
        assertEquals(6, stories[0].items.size)
        assertEquals(6, stories[1].items.size)
    }

    @Test
    fun doesNotTurnAnOrdinaryDateBucketIntoAStory() {
        val start = 1_735_689_600_000L
        val ordinaryDay = (1L..8L).map { item(it, start + it * 10L * 60L * 1000L) }

        val stories = MemoryStoryBuilder.build(source = ordinaryDay, locations = emptyMap())

        assertTrue(stories.isEmpty())
    }

    @Test
    fun usesNamedPeopleAsStoryContext() {
        val start = 1_735_689_600_000L
        val event = (1L..6L).map { item(it, start + it * 10L * 60L * 1000L) }
        val people = event.take(4).associate { it.uri to setOf("Alice") }

        val stories = MemoryStoryBuilder.build(
            source = event,
            locations = emptyMap(),
            peopleByUri = people
        )

        assertEquals(1, stories.size)
        assertEquals("With Alice", stories.single().title)
    }

    @Test
    fun usesMeaningfulUserTagsInsteadOfDates() {
        val start = 1_735_689_600_000L
        val event = (1L..6L).map { item(it, start + it * 10L * 60L * 1000L) }
        val tags = event.take(5).associate { it.uri to setOf("birthday_party") }

        val stories = MemoryStoryBuilder.build(
            source = event,
            locations = emptyMap(),
            tagsByUri = tags
        )

        assertEquals(1, stories.size)
        assertEquals("Birthday Party moments", stories.single().title)
    }
}
