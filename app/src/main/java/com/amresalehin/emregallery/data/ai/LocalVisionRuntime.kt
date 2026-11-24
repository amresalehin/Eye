package com.amresalehin.emregallery.data.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Owns one lazily-loaded llama.cpp vision model for a complete indexing run.
 * The native model is never loaded on the gallery/rendering path.
 */
class LocalVisionRuntime(private val context: Context) : AutoCloseable {
    private var loadedModelPath: String? = null
    private var loadedProjectorPath: String? = null

    suspend fun generate(
        selection: AiModelSelection,
        imageFrames: List<ByteArray>,
        prompt: String
    ): String = withContext(Dispatchers.IO) {
        if (imageFrames.isEmpty()) throw IOException("No image frames were prepared for local AI.")
        if (imageFrames.size > 4) throw IOException("Local AI accepts up to four frames per media item.")

        val modelPath = selection.modelFile.absolutePath
        val projectorPath = selection.projectorFile.absolutePath
        if (loadedModelPath != modelPath || loadedProjectorPath != projectorPath) {
            NativeVisionEngine.close()
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            NativeVisionEngine.loadModel(modelPath, projectorPath, 4096, threads)
            loadedModelPath = modelPath
            loadedProjectorPath = projectorPath
        }
        NativeVisionEngine.generate(imageFrames.toTypedArray(), prompt, 700)
            .takeIf { it.isNotBlank() }
            ?: throw IOException("The local vision model returned an empty response.")
    }

    fun cancel() {
        runCatching { NativeVisionEngine.cancel() }
    }

    override fun close() {
        if (loadedModelPath != null) {
            runCatching { NativeVisionEngine.close() }
            loadedModelPath = null
            loadedProjectorPath = null
        }
    }
}
