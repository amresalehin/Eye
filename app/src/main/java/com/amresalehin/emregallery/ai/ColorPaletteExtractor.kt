package com.amresalehin.emregallery.ai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class DominantColorInfo(
    val hex: String,
    val name: String,
    val ratio: Float
)

data class LocalVisualFeatures(
    val colors: List<DominantColorInfo>,
    val fallbackEmbedding: FloatArray,
    val heuristicTags: List<String>
)

/**
 * Fast, 100% on-device color palette extractor and visual fingerprint generator.
 * - Extracts top dominant colors with human-readable names (mymind-style color search).
 * - Computes a compact 64-dim L2-normalized HSV + spatial grid descriptor for
 *   zero-download visually similar image search.
 */
object ColorPaletteExtractor {

    const val FALLBACK_EMBEDDING_MODEL = "visual_color_hist_v1"
    const val FALLBACK_EMBEDDING_DIM = 64

    fun extract(bitmap: Bitmap, hasOcrText: Boolean = false, isScreenshotHint: Boolean = false): LocalVisualFeatures {
        val small = if (bitmap.width > 64 || bitmap.height > 64) {
            Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        } else {
            bitmap
        }
        val shouldRecycle = small !== bitmap
        try {
            val w = small.width
            val h = small.height
            val pixels = IntArray(w * h)
            small.getPixels(pixels, 0, w, 0, 0, w, h)

            // 1. Quantized color buckets (4 bits per channel -> 4096 buckets)
            val bucketCounts = HashMap<Int, Int>()
            val bucketR = HashMap<Int, Long>()
            val bucketG = HashMap<Int, Long>()
            val bucketB = HashMap<Int, Long>()

            // 2. 64-dim visual descriptor:
            //    - 32 dims: global HSV histogram (16 hue bins + 8 sat bins + 8 val bins)
            //    - 32 dims: 2x2 spatial grid mean RGB+HSV stats (8 values x 4 quadrants)
            val descriptor = FloatArray(FALLBACK_EMBEDDING_DIM)
            val hsv = FloatArray(3)
            val quadCounts = IntArray(4)

            var totalPixels = 0
            var totalBrightness = 0.0
            var totalSaturation = 0.0

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val px = pixels[y * w + x]
                    val a = Color.alpha(px)
                    if (a < 128) continue
                    val r = Color.red(px)
                    val g = Color.green(px)
                    val b = Color.blue(px)

                    val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                    bucketCounts[key] = (bucketCounts[key] ?: 0) + 1
                    bucketR[key] = (bucketR[key] ?: 0L) + r
                    bucketG[key] = (bucketG[key] ?: 0L) + g
                    bucketB[key] = (bucketB[key] ?: 0L) + b

                    Color.RGBToHSV(r, g, b, hsv)
                    val hueBin = ((hsv[0] / 360f) * 16f).toInt().coerceIn(0, 15)
                    val satBin = (hsv[1] * 8f).toInt().coerceIn(0, 7)
                    val valBin = (hsv[2] * 8f).toInt().coerceIn(0, 7)
                    descriptor[hueBin] += 1f
                    descriptor[16 + satBin] += 1f
                    descriptor[24 + valBin] += 1f

                    val qx = if (x < w / 2) 0 else 1
                    val qy = if (y < h / 2) 0 else 1
                    val qIdx = qy * 2 + qx
                    quadCounts[qIdx]++
                    val base = 32 + qIdx * 8
                    descriptor[base] += r / 255f
                    descriptor[base + 1] += g / 255f
                    descriptor[base + 2] += b / 255f
                    descriptor[base + 3] += hsv[0] / 360f
                    descriptor[base + 4] += hsv[1]
                    descriptor[base + 5] += hsv[2]
                    // Rough contrast/warmth cues
                    descriptor[base + 6] += (r - b) / 255f
                    descriptor[base + 7] += (g - ((r + b) * 0.5f)) / 255f

                    totalBrightness += hsv[2]
                    totalSaturation += hsv[1]
                    totalPixels++
                }
            }

            val denom = max(1, totalPixels).toFloat()
            for (i in 0 until 32) {
                descriptor[i] /= denom
            }
            for (q in 0 until 4) {
                val qc = max(1, quadCounts[q]).toFloat()
                val base = 32 + q * 8
                for (k in 0 until 8) {
                    descriptor[base + k] /= qc
                }
            }

            // Pick top distinct dominant colors
            val sortedBuckets = bucketCounts.entries.sortedByDescending { it.value }
            val chosen = mutableListOf<Triple<Int, Int, Int>>() // avg R, G, B
            val resultColors = mutableListOf<DominantColorInfo>()

            for ((key, count) in sortedBuckets) {
                if (resultColors.size >= 5) break
                val avgR = ((bucketR[key] ?: 0L) / count).toInt().coerceIn(0, 255)
                val avgG = ((bucketG[key] ?: 0L) / count).toInt().coerceIn(0, 255)
                val avgB = ((bucketB[key] ?: 0L) / count).toInt().coerceIn(0, 255)

                // Ensure visual separation between palette swatches
                val tooClose = chosen.any { (cr, cg, cb) ->
                    abs(cr - avgR) + abs(cg - avgG) + abs(cb - avgB) < 65
                }
                if (tooClose) continue

                chosen.add(Triple(avgR, avgG, avgB))
                val hex = String.format("#%02X%02X%02X", avgR, avgG, avgB)
                val name = classifyColorName(avgR, avgG, avgB)
                val ratio = (count.toFloat() / denom).coerceIn(0f, 1f)
                resultColors.add(DominantColorInfo(hex = hex, name = name, ratio = ratio))
            }

            val avgBright = if (totalPixels > 0) (totalBrightness / totalPixels).toFloat() else 0.5f
            val avgSat = if (totalPixels > 0) (totalSaturation / totalPixels).toFloat() else 0.3f

            val heuristicTags = buildList {
                resultColors.take(2).map { it.name }.distinct().forEach { add(it) }
                if (avgBright < 0.28f) add("dark")
                else if (avgBright > 0.78f) add("bright")
                if (avgSat < 0.12f) add("monochrome")
                else if (avgSat > 0.55f) add("colorful")
                if (bitmap.width > bitmap.height * 1.25f) add("landscape")
                else if (bitmap.height > bitmap.width * 1.25f) add("portrait")
                if (isScreenshotHint) add("screenshot")
                if (hasOcrText) add("text")
            }.distinct()

            return LocalVisualFeatures(
                colors = resultColors,
                fallbackEmbedding = AiVectorMath.l2Normalize(descriptor),
                heuristicTags = heuristicTags
            )
        } finally {
            if (shouldRecycle && !small.isRecycled) {
                small.recycle()
            }
        }
    }

    /**
     * Classifies an RGB color into an intuitive human color name for mymind-style search.
     */
    fun classifyColorName(r: Int, g: Int, b: Int): String {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]

        if (v < 0.16f) return "black"
        if (s < 0.12f) {
            return when {
                v > 0.85f -> "white"
                v > 0.45f -> "gray"
                else -> "dark gray"
            }
        }
        if (h in 15f..45f && v < 0.52f && s < 0.75f) return "brown"
        if (s < 0.32f && v > 0.75f && (h < 35f || h > 330f)) return "beige"

        return when {
            h < 15f || h >= 345f -> "red"
            h < 42f -> "orange"
            h < 68f -> "yellow"
            h < 155f -> "green"
            h < 195f -> "teal"
            h < 250f -> "blue"
            h < 290f -> "purple"
            else -> "pink"
        }
    }

    val SUPPORTED_COLOR_NAMES = listOf(
        "red", "orange", "yellow", "green", "teal",
        "blue", "purple", "pink", "brown", "beige",
        "white", "gray", "black"
    )
}
