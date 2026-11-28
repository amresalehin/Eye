package com.amresalehin.emregallery.data

/**
 * Background thumbnail preparation is allowed only when the gallery is idle and no visible
 * MediaStore thumbnail fetch is active.
 */
internal fun shouldAllowBackgroundThumbnailPreparation(
    isScrolling: Boolean,
    activeVisibleRequests: Int
): Boolean = !isScrolling && activeVisibleRequests == 0
