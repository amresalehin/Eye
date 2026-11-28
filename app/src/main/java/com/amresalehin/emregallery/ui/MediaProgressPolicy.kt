package com.amresalehin.emregallery.ui

internal const val MEDIA_PROGRESS_PUBLISH_THRESHOLD = 3072
internal const val MEDIA_TAIL_PROGRESS_PUBLISH_THRESHOLD = 64
internal const val MEDIA_TAIL_NEAR_END_ROWS = 240

/**
 * Publishes the initial page immediately, keeps background updates coarse, and switches
 * to immediate small-batch publication when the user scrolls near the loaded boundary.
 */
internal fun shouldPublishMediaProgress(
    hasPublishedProgress: Boolean,
    pendingItemCount: Int,
    isAtLoadedEnd: Boolean,
    isScrollingNearLoadedEnd: Boolean
): Boolean {
    if (!hasPublishedProgress) return true
    if (pendingItemCount >= MEDIA_PROGRESS_PUBLISH_THRESHOLD) return true
    return (isAtLoadedEnd || isScrollingNearLoadedEnd) &&
        pendingItemCount >= MEDIA_TAIL_PROGRESS_PUBLISH_THRESHOLD
}
