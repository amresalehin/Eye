package com.amresalehin.emregallery.ui

internal const val MEDIA_PROGRESS_PUBLISH_THRESHOLD = 3072
internal const val MEDIA_TAIL_PROGRESS_PUBLISH_THRESHOLD = 512
internal const val MEDIA_TAIL_PROGRESS_MIN_INTERVAL_MILLIS = 250L

/**
 * Publishes the initial page immediately, keeps ordinary background updates coarse, and
 * temporarily switches to smaller updates when the user reaches the currently loaded end.
 */
internal fun shouldPublishMediaProgress(
    hasPublishedProgress: Boolean,
    pendingItemCount: Int,
    isAtLoadedEnd: Boolean,
    elapsedSinceLastPublishMillis: Long
): Boolean {
    if (!hasPublishedProgress) return true
    if (pendingItemCount >= MEDIA_PROGRESS_PUBLISH_THRESHOLD) return true
    return isAtLoadedEnd &&
        pendingItemCount >= MEDIA_TAIL_PROGRESS_PUBLISH_THRESHOLD &&
        elapsedSinceLastPublishMillis >= MEDIA_TAIL_PROGRESS_MIN_INTERVAL_MILLIS
}
