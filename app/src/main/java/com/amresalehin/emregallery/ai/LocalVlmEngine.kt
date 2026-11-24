package com.amresalehin.emregallery.ai

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * Structured output returned by any on-device or remote Vision-Language Model (VLM).
 */
data class VlmAnalysisOutput(
    val title: String,
    val caption: String,
    val tags: List<String>,
    val rawResponse: String,
    val engineId: String,
    val modelName: String
)

/**
 * Pluggable interface for on-device Vision-Language Model (VLM) runtimes.
 *
 * Implementations:
 * - [LlamaCppVlmEngine]: Direct `llama.cpp` + `libmtmd` JNI for GGUF + `mmproj.gguf` pairs
 *   (LFM2.5-VL-450M, SmolVLM2-500M, Qwen3.5-0.8B, Qwen3-VL-2B).
 * - [MediaPipeVlmEngine]: Google MediaPipe / LiteRT-LM (`tasks-genai`) for Gemma 3n / Gemma 4 `.task` models.
 */
interface LocalVlmEngine {
    val engineId: String
    val displayName: String
    val supportedExtensions: List<String>

    fun isModelReady(context: Context): Boolean
    fun activeModelFile(context: Context): File?

    suspend fun analyzeImage(
        context: Context,
        bitmap: Bitmap,
        ocrText: String? = null,
        includeOcrContext: Boolean = true
    ): Result<VlmAnalysisOutput>

    suspend fun askQuestion(
        context: Context,
        bitmap: Bitmap,
        question: String,
        ocrText: String? = null
    ): Result<String>

    fun release()
}

/**
 * Registry of available on-device VLM engines.
 */
object LocalVlmRegistry {
    const val ENGINE_LLAMACPP = "llamacpp"
    const val ENGINE_MEDIAPIPE = "mediapipe"

    private val engines: Map<String, LocalVlmEngine> = linkedMapOf(
        ENGINE_LLAMACPP to LlamaCppVlmEngine,
        ENGINE_MEDIAPIPE to MediaPipeVlmEngine
    )

    fun get(engineId: String?): LocalVlmEngine {
        return engines[engineId?.lowercase()] ?: LlamaCppVlmEngine
    }

    fun all(): List<LocalVlmEngine> = engines.values.toList()

    fun releaseAll() {
        engines.values.forEach { runCatching { it.release() } }
    }
}
