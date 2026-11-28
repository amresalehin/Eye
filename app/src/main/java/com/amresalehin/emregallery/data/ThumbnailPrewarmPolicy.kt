package com.amresalehin.emregallery.data

/**
 * Background thumbnail preparation is allowed only when the gallery is idle and no visible
 * MediaStore thumbnail fetch is active.
 */
internal fun shouldAllowBackgroundThumbnailPreparation(
    isScrolling: Boolean,
    activeVisibleRequests: Int
): Boolean = !isScrolling && activeVisibleRequests == 0


/**
 * Restores durable progress. An incomplete checkpoint at the end means the previous pass
 * finished with at least one failed item; restart from the beginning to recheck missing files.
 */
internal fun restoreThumbnailPrewarmIndex(
    mediaSize: Int,
    savedNextIndex: Int?,
    savedCompleted: Boolean
): Int {
    if (mediaSize <= 0) return 0
    val saved = savedNextIndex?.takeIf { it in 0..mediaSize } ?: return 0
    return when {
        savedCompleted && saved == mediaSize -> mediaSize
        saved < mediaSize -> saved
        else -> 0
    }
}
