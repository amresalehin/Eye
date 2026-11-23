package com.amresalehin.emregallery.data

import kotlin.math.*

/** A GPS point already present in the app's persistent location cache. */
data class StoryLocation(val latitude: Double, val longitude: Double)

/**
 * Builds event-like memory stories from real signals already available in the library:
 * capture-session continuity, cached GPS, people labels and user tags.
 *
 * Calendar dates are never used as story boundaries or titles. Items without a meaningful
 * event signal are intentionally left out instead of being presented as arbitrary date piles.
 */
object MemoryStoryBuilder {
    private const val MIN_STORY_ITEMS = 5
    private const val SHORT_SESSION_GAP_MS = 6L * 60L * 60L * 1000L
    private const val SAME_PLACE_GAP_MS = 36L * 60L * 60L * 1000L
    private const val MAX_GEO_EVENT_SPAN_MS = 4L * 24L * 60L * 60L * 1000L
    private const val MAX_UNLOCATED_EVENT_SPAN_MS = 18L * 60L * 60L * 1000L
    private const val SAME_PLACE_KM = 25.0
    private const val FAR_TRAVEL_KM = 100.0

    fun build(
        source: List<MediaItem>,
        locations: Map<String, StoryLocation>,
        peopleByUri: Map<String, Set<String>> = emptyMap(),
        tagsByUri: Map<String, Set<String>> = emptyMap(),
        cachedPlaceNames: Map<String, String> = emptyMap()
    ): List<StoryGroup> {
        if (source.isEmpty()) return emptyList()

        val sorted = source.asSequence()
            .filterNot { it.isTrashed }
            .mapNotNull { item ->
                val timestamp = timestampOf(item)
                if (timestamp > 0L) item to timestamp else null
            }
            .sortedBy { it.second }
            .toList()
        if (sorted.size < MIN_STORY_ITEMS) return emptyList()

        val stories = mutableListOf<StoryGroup>()

        fun appendStory(cluster: List<Pair<MediaItem, Long>>) {
            if (cluster.size < MIN_STORY_ITEMS) return
            val items = cluster.map { it.first }
            val uriSet = items.mapTo(HashSet()) { it.uri }
            val people = countSignals(uriSet, peopleByUri)
            val tags = countSignals(uriSet, tagsByUri)
            val hasLocationStory = items.count { item ->
                locations[item.uri]?.let(::isValidLocation) == true
            } >= 3

            val signalThreshold = maxOf(3, ceil(items.size * 0.30).toInt())
            val person = people.entries
                .filter { it.value >= signalThreshold }
                .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                ?.key
            val tag = tags.entries
                .filter { it.value >= signalThreshold }
                .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                ?.key

            if (!hasLocationStory && person == null && tag == null) return

            val knownPlace = items.asSequence()
                .mapNotNull { cachedPlaceNames[it.uri]?.trim()?.takeIf(String::isNotBlank) }
                .groupingBy { it }
                .eachCount()
                .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                ?.key

            val duration = cluster.last().second - cluster.first().second
            val title = when {
                person != null -> "With $person"
                knownPlace != null && duration >= 24L * 60L * 60L * 1000L -> "Trip to $knownPlace"
                knownPlace != null -> "At $knownPlace"
                hasLocationStory -> "Location story"
                tag != null -> displayTag(tag)
                duration >= 24L * 60L * 60L * 1000L -> "Out and about"
                else -> "A day out"
            }

            val storyItems = items.sortedByDescending(::timestampOf)
            val candidate = StoryGroup(title = title, items = storyItems)
            if (stories.size < 8) {
                stories += candidate
            } else {
                val oldestIndex = stories.indices.minByOrNull { index ->
                    stories[index].items.firstOrNull()?.let(::timestampOf) ?: 0L
                } ?: return
                val oldestTimestamp = stories[oldestIndex].items.firstOrNull()?.let(::timestampOf) ?: 0L
                val candidateTimestamp = storyItems.firstOrNull()?.let(::timestampOf) ?: 0L
                if (candidateTimestamp > oldestTimestamp) stories[oldestIndex] = candidate
            }
        }

        var current = mutableListOf<Pair<MediaItem, Long>>()
        var currentEventHasGps = false
        for (entry in sorted) {
            val previous = current.lastOrNull()
            if (previous != null && !belongsToSameEvent(
                    previous = previous.first,
                    previousTimestamp = previous.second,
                    next = entry.first,
                    nextTimestamp = entry.second,
                    currentEventHasGps = currentEventHasGps,
                    locations = locations,
                    peopleByUri = peopleByUri,
                    tagsByUri = tagsByUri
                )
            ) {
                appendStory(current)
                current = mutableListOf()
                currentEventHasGps = false
            }
            current += entry
            if (locations[entry.first.uri]?.let(::isValidLocation) == true) {
                currentEventHasGps = true
            }
        }
        appendStory(current)

        return stories.sortedByDescending { story ->
            story.items.firstOrNull()?.let(::timestampOf) ?: 0L
        }
    }

    private fun belongsToSameEvent(
        previous: MediaItem,
        previousTimestamp: Long,
        next: MediaItem,
        nextTimestamp: Long,
        currentEventHasGps: Boolean,
        locations: Map<String, StoryLocation>,
        peopleByUri: Map<String, Set<String>>,
        tagsByUri: Map<String, Set<String>>
    ): Boolean {
        val gap = nextTimestamp - previousTimestamp
        if (gap < 0L) return false

        val firstTimestamp = currentEvent.firstOrNull()?.second ?: previousTimestamp
        val eventSpan = nextTimestamp - firstTimestamp
        val previousLocation = locations[previous.uri]?.takeIf(::isValidLocation)
        val nextLocation = locations[next.uri]?.takeIf(::isValidLocation)
        val distance = if (previousLocation != null && nextLocation != null) {
            distanceKm(previousLocation, nextLocation)
        } else null

        if (distance != null && distance > FAR_TRAVEL_KM && gap > 30L * 60L * 1000L) {
            return false
        }

        val sameNamedContext =
            peopleByUri[previous.uri].orEmpty().intersect(peopleByUri[next.uri].orEmpty()).isNotEmpty() ||
            tagsByUri[previous.uri].orEmpty().intersect(tagsByUri[next.uri].orEmpty()).isNotEmpty()

        val hasGpsContext = currentEventHasGps || nextLocation != null

        val maxSpan = if (hasGpsContext) MAX_GEO_EVENT_SPAN_MS else MAX_UNLOCATED_EVENT_SPAN_MS
        if (eventSpan > maxSpan) return false

        return when {
            gap <= SHORT_SESSION_GAP_MS -> true
            distance != null && distance <= SAME_PLACE_KM && gap <= SAME_PLACE_GAP_MS -> true
            sameNamedContext && gap <= SAME_PLACE_GAP_MS -> true
            else -> false
        }
    }

    private fun countSignals(
        uris: Set<String>,
        signalsByUri: Map<String, Set<String>>
    ): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (uri in uris) {
            for (rawSignal in signalsByUri[uri].orEmpty()) {
                val signal = rawSignal.trim()
                if (signal.isNotBlank()) counts[signal] = (counts[signal] ?: 0) + 1
            }
        }
        return counts
    }

    private fun displayTag(rawTag: String): String {
        val words = rawTag.trim()
            .removePrefix("#")
            .replace('_', ' ')
            .replace('-', ' ')
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
        val title = words.joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        return if (title.isBlank()) "Shared moments" else "$title moments"
    }

    private fun centroidOf(points: List<StoryLocation>): StoryLocation? {
        if (points.isEmpty()) return null
        return StoryLocation(
            latitude = points.map { it.latitude }.average(),
            longitude = points.map { it.longitude }.average()
        ).takeIf(::isValidLocation)
    }

    private fun isValidLocation(point: StoryLocation): Boolean =
        point.latitude.isFinite() && point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0 &&
            !(point.latitude == 0.0 && point.longitude == 0.0)

    private fun distanceKm(a: StoryLocation, b: StoryLocation): Double {
        val earthRadiusKm = 6371.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val deltaLat = Math.toRadians(b.latitude - a.latitude)
        val deltaLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(deltaLat / 2).pow(2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
        return 2.0 * earthRadiusKm * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun timestampOf(item: MediaItem): Long =
        if (item.dateTakenMillis > 0L) item.dateTakenMillis else item.dateAddedSeconds * 1000L
}
