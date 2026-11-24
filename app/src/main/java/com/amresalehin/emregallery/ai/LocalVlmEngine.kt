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
 * - [MediaPipeVlmEngine]: Google MediaPipe / LiteRT-LM (`tasks-genai`) for Gemma 3n / Gemma 4 `.task` models.
 * - [LlamaCppVlmEngineStub]: Drop-in extension point for `llama.cpp` (`libmtmd` GGUF + `mmproj.gguf`)
 *   such as LFM2.5-VL-450M, SmolVLM2-500M, or Qwen3-VL-2B.
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
    const val ENGINE_MEDIAPIPE = "mediapipe"
    const val ENGINE_LLAMACPP = "llamacpp"

    private val engines: Map<String, LocalVlmEngine> = linkedMapOf(
        ENGINE_MEDIAPIPE to MediaPipeVlmEngine,
        ENGINE_LLAMACPP to LlamaCppVlmEngineStub
    )

    fun get(engineId: String?): LocalVlmEngine {
        return engines[engineId?.lowercase()] ?: MediaPipeVlmEngine
    }

    fun all(): List<LocalVlmEngine> = engines.values.toList()

    fun releaseAll() {
        engines.values.forEach { runCatching { it.release() } }
    }
}

/**
 * Pluggable stub for a future `llama.cpp` (`libmtmd`) GGUF + `mmproj.gguf` JNI runtime
 * (e.g., LFM2.5-VL-450M, SmolVLM2-500M, Qwen3-VL-2B).
 *
 * Keeps the engine contract identical so adding native JNI bindings later requires zero UI
 * or orchestrator rewrites.
 */
object LlamaCppVlmEngineStub : LocalVlmEngine {
    override val engineId: String = LocalVlmRegistry.ENGINE_LLAMACPP
    override val displayName: String = "llama.cpp GGUF (Coming Soon)"
    override val supportedExtensions: List<String> = listOf("gguf")

    override fun isModelReady(context: Context): Boolean = false

    override fun activeModelFile(context: Context): File? = null

    override suspend fun analyzeImage(
        context: Context,
        bitmap: Bitmap,
        ocrText: String?,
        includeOcrContext: Boolean
    ): Result<VlmAnalysisOutput> = Result.failure(
        UnsupportedOperationException(
            "llama.cpp libmtmd JNI runtime is not bundled yet. Switch to MediaPipe / LiteRT-LM or Ollama in AI Settings."
        )
    )

    override suspend fun askQuestion(
        context: Context,
        bitmap: Bitmap,
        question: String,
        ocrText: String?
    ): Result<String> = Result.failure(
        UnsupportedOperationException(
            "llama.cpp libmtmd JNI runtime is not bundled yet. Switch to MediaPipe / LiteRT-LM or Ollama in AI Settings."
        )
    )

    override fun release() = Unit
}
