package com.amresalehin.emregallery.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.dp

/**
 * Tracks thumbnail bounds for shared-element viewer transitions.
 *
 * Registrations belong to individual composed modifiers and are removed when their lazy item
 * leaves composition. Multiple screens may show the same URI, so registrations use owner tokens
 * rather than retaining one coordinate reference per URI indefinitely.
 */
object ThumbnailBoundsTracker {
    private val coordinatesMap = mutableMapOf<String, LinkedHashMap<Any, LayoutCoordinates>>()
    private val cachedBounds = mutableMapOf<String, Rect>()

    /** URI whose source thumbnail is hidden behind the fullscreen viewer. */
    var activeViewerUri by mutableStateOf<String?>(null)

    fun registerCoordinates(uri: String, owner: Any, coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        coordinatesMap.getOrPut(uri) { LinkedHashMap() }[owner] = coordinates
    }

    fun unregisterCoordinates(uri: String, owner: Any) {
        val owners = coordinatesMap[uri] ?: return
        owners.remove(owner)
        if (owners.isEmpty()) {
            coordinatesMap.remove(uri)
            cachedBounds.remove(uri)
        }
    }

    fun updateBounds(uri: String, bounds: Rect) {
        if (bounds.width > 5f && bounds.height > 5f) cachedBounds[uri] = bounds
    }

    fun getBounds(uri: String): Rect? {
        val owners = coordinatesMap[uri]
        if (owners != null) {
            val entries = owners.entries.toList()
            for (index in entries.indices.reversed()) {
                val entry = entries[index]
                val coordinates = entry.value
                if (!coordinates.isAttached) {
                    owners.remove(entry.key)
                    continue
                }
                val rootBounds = runCatching { coordinates.boundsInRoot() }.getOrNull()
                if (rootBounds != null && rootBounds.width > 5f && rootBounds.height > 5f) {
                    cachedBounds[uri] = rootBounds
                    return rootBounds
                }
            }
            if (owners.isEmpty()) {
                coordinatesMap.remove(uri)
                cachedBounds.remove(uri)
            }
        }
        return cachedBounds[uri]
    }

    /** Remove all registrations for a URI after explicit media deletion. */
    fun removeBounds(uri: String) {
        coordinatesMap.remove(uri)
        cachedBounds.remove(uri)
    }

    fun clear() {
        coordinatesMap.clear()
        cachedBounds.clear()
        activeViewerUri = null
    }
}

/**
 * The registration is tied to this particular composed thumbnail instance. Disposing one screen
 * cannot unregister a second screen that is displaying the same media URI.
 */
fun Modifier.trackThumbnailBounds(uri: String): Modifier = composed {
    val owner = remember(uri) { Any() }
    DisposableEffect(uri, owner) {
        onDispose { ThumbnailBoundsTracker.unregisterCoordinates(uri, owner) }
    }
    this.onPlaced { coordinates ->
        if (coordinates.isAttached) {
            ThumbnailBoundsTracker.registerCoordinates(uri, owner, coordinates)
        }
    }
}

/**
 * Coordinated top bar entrance modifier with progress provider to avoid recomposing caller.
 */
fun Modifier.destinationTopBarEntrance(progressProvider: () -> Float): Modifier = this.graphicsLayer {
    val progress = progressProvider()
    if (progress > 0.001f) {
        translationY = -(size.height + 60.dp.toPx()) * progress
        alpha = (1f - progress * 1.15f).coerceIn(0f, 1f)
    }
}

fun Modifier.destinationTopBarEntrance(progress: Float): Modifier = this.graphicsLayer {
    if (progress > 0.001f) {
        translationY = -(size.height + 60.dp.toPx()) * progress
        alpha = (1f - progress * 1.15f).coerceIn(0f, 1f)
    }
}

/**
 * Coordinated bottom bar entrance modifier with progress provider.
 */
fun Modifier.destinationBottomBarEntrance(progressProvider: () -> Float): Modifier = this.graphicsLayer {
    val progress = progressProvider()
    if (progress > 0.001f) {
        translationY = (size.height + 60.dp.toPx()) * progress
        alpha = (1f - progress * 1.15f).coerceIn(0f, 1f)
    }
}

fun Modifier.destinationBottomBarEntrance(progress: Float): Modifier = this.graphicsLayer {
    if (progress > 0.001f) {
        translationY = (size.height + 60.dp.toPx()) * progress
        alpha = (1f - progress * 1.15f).coerceIn(0f, 1f)
    }
}
