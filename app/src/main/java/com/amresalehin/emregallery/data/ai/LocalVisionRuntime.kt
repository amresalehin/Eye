package com.amresalehin.emregallery.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Process-wide owner for the single llama.cpp context. Calls are serialized so a
 * one-off media analysis cannot invalidate a model used by background indexing.
 */
object LocalVisionRuntime {
    private val lock = Any()
    private var loadedModelPath: String? = null
    private var loadedProjectorPath: String? = null

    suspend fun generate(
        selection: AiModelSelection,
        imageFrames: List<ByteArray>,
        prompt: String
    ): String = withContext(Dispatchers.IO) {
        if (imageFrames.isEmpty()) throw IOException("No image frames were prepared for local AI.")
        if (imageFrames.size > 4) throw IOException("Local AI accepts up to four frames per media item.")

        synchronized(lock) {
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
    }

    fun cancel() {
        runCatching { NativeVisionEngine.cancel() }
    }

    fun close() {
        synchronized(lock) {
            if (loadedModelPath != null) {
                runCatching { NativeVisionEngine.close() }
                loadedModelPath = null
                loadedProjectorPath = null
            }
        }
    }
}
