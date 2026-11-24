package com.amresalehin.emregallery.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Vector math helpers for L2-normalized image/text embeddings and SQLite BLOB serialization.
 */
object AiVectorMath {

    fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSq = 0.0
        for (v in vector) {
            sumSq += (v * v).toDouble()
        }
        val norm = sqrt(sumSq).toFloat()
        if (norm <= 1e-8f) return vector.copyOf()
        val out = FloatArray(vector.size)
        for (i in vector.indices) {
            out[i] = vector[i] / norm
        }
        return out
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom <= 1e-8) return 0f
        return (dot / denom).toFloat().coerceIn(-1f, 1f)
    }

    fun toBytes(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vector) {
            buffer.putFloat(v)
        }
        return buffer.array()
    }

    fun fromBytes(bytes: ByteArray?): FloatArray {
        if (bytes == null || bytes.size < 4 || bytes.size % 4 != 0) return FloatArray(0)
        val count = bytes.size / 4
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(count)
        for (i in 0 until count) {
            out[i] = buffer.float
        }
        return out
    }
}
