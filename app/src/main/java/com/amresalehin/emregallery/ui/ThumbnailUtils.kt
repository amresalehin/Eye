package com.amresalehin.emregallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import java.io.File
import kotlin.math.max

/**
 * Common thumbnail and image caching utilities for Emre Gallery.
 * Unifies cache keys across all screens (Home, Albums, Favourites, Bin, Archive, Search)
 * so thumbnails are cached once, shared instantly across screens, and used as instantaneous
 * zero-latency placeholders when expanding the fullscreen media viewer.
 */
object ThumbnailUtils {
    const val THUMBNAIL_DIMENSION = 420

    fun mediaThumbnailKey(uri: String): String = "${uri}_thumb"

    /**
     * Safely parses a media URI or raw filesystem path so local files with spaces, '#', or '%'
     * never get truncated as URI fragments/queries.
     */
    fun parseMediaDataModel(uri: String): Any {
        val trimmed = uri.trim()
        if (trimmed.startsWith("/")) {
            return File(trimmed)
        }
        if (trimmed.startsWith("file://", ignoreCase = true)) {
            val filePath = runCatching { Uri.parse(trimmed).path }.getOrNull() ?: trimmed.removePrefix("file://")
            return File(filePath)
        }
        return Uri.parse(trimmed)
    }

    private fun isAnimatedFormatUri(uri: String): Boolean {
        val clean = uri.substringBefore('?').substringBefore('#').lowercase()
        return clean.endsWith(".gif") || clean.endsWith(".webp")
    }

    fun buildThumbnailRequest(
        context: Context,
        uri: String,
        widthPx: Int = THUMBNAIL_DIMENSION,
        heightPx: Int = THUMBNAIL_DIMENSION,
        useRgb565: Boolean = false
    ): ImageRequest {
        val isAnimated = isAnimatedFormatUri(uri)
        val builder = ImageRequest.Builder(context)
            .data(parseMediaDataModel(uri))
            .size(widthPx, heightPx)
            .precision(Precision.INEXACT)
            // Grid thumbnails should appear on the first available frame; the outline is already
            // visible, so a crossfade only adds perceived latency while rapidly scrolling.
            .crossfade(false)
            .diskCacheKey(mediaThumbnailKey(uri))
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCacheKey(mediaThumbnailKey(uri))
            .memoryCachePolicy(CachePolicy.ENABLED)
            .allowHardware(!isAnimated)
            .allowRgb565(!isAnimated && useRgb565)

        if (useRgb565 && !isAnimated) {
            builder.bitmapConfig(Bitmap.Config.RGB_565)
        }
        return builder.build()
    }

    fun buildViewerRequest(
        context: Context,
        uri: String,
        isVideo: Boolean
    ): ImageRequest {
        val isAnimated = isAnimatedFormatUri(uri)
        val builder = ImageRequest.Builder(context)
            .data(parseMediaDataModel(uri))
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .allowHardware(!isAnimated)
            .allowRgb565(false)

        if (isVideo) {
            builder
                .size(THUMBNAIL_DIMENSION, THUMBNAIL_DIMENSION)
                .precision(Precision.INEXACT)
                .crossfade(true)
                .crossfade(150)
                .diskCacheKey(mediaThumbnailKey(uri))
                .memoryCacheKey(mediaThumbnailKey(uri))
        } else {
            val dm = context.resources.displayMetrics
            val maxDim = max(dm.widthPixels, dm.heightPixels).coerceIn(1600, 2560)
            builder
                .size(maxDim, maxDim)
                .precision(Precision.INEXACT)
                .placeholderMemoryCacheKey(mediaThumbnailKey(uri))
                .diskCacheKey("${uri}:viewer-scaled")
                .memoryCacheKey("${uri}:viewer-scaled")
                .crossfade(!isAnimated)
                .apply {
                    if (!isAnimated) crossfade(150)
                }
        }
        return builder.build()
    }
}
