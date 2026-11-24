package com.amresalehin.emregallery.ai

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * Low-level JNI bridge to `libemre_llama_vlm.so` (built via CMake + Android NDK from
 * `app/src/main/cpp/emre_llama_vlm_jni.cpp` linking upstream `llama.cpp` + `libmtmd`).
 */
object LlamaCppNativeBridge {

    val isNativeLibraryLoaded: Boolean = runCatching {
        System.loadLibrary("emre_llama_vlm")
        true
    }.getOrDefault(false)

    external fun nativeInit(
        modelPath: String,
        mmprojPath: String,
        nCtx: Int,
        nThreads: Int
    ): Long

    external fun nativeAnalyzeImage(
        handle: Long,
        imageBytes: ByteArray,
        prompt: String,
        maxTokens: Int
    ): String

    external fun nativeRelease(handle: Long)
}

/**
 * Real on-device `llama.cpp` (`libmtmd`) Vision-Language Model engine using a
 * **GGUF language model + matching `mmproj.gguf` vision projector** pair
 * (e.g., LFM2.5-VL-450M, SmolVLM2-500M, Qwen3.5-0.8B, Qwen3-VL-2B).
 */
object LlamaCppVlmEngine : LocalVlmEngine {

    override val engineId: String = LocalVlmRegistry.ENGINE_LLAMACPP
    override val displayName: String = "llama.cpp (GGUF + mmproj)"
    override val supportedExtensions: List<String> = listOf("gguf")

    private const val MAX_IMAGE_SIDE = 512

    private val inferenceMutex = Mutex()

    @Volatile
    private var nativeHandle: Long = 0L
    @Volatile
    private var loadedModelPath: String = ""
    @Volatile
    private var loadedMmprojPath: String = ""

    override fun isModelReady(context: Context): Boolean {
        return LocalVlmModelManager.resolveInstalledGgufPair(context) != null
    }

    override fun activeModelFile(context: Context): File? {
        return LocalVlmModelManager.resolveInstalledGgufPair(context)?.first
    }

    override suspend fun analyzeImage(
        context: Context,
        bitmap: Bitmap,
        ocrText: String?,
        includeOcrContext: Boolean
    ): Result<VlmAnalysisOutput> = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            runCatching {
                ensureNativeLibraryAvailable()
                val appContext = context.applicationContext
                val pair = LocalVlmModelManager.resolveInstalledGgufPair(appContext)
                    ?: error("GGUF model and matching mmproj.gguf are both required. Import or download both in Settings → AI & Semantic Search.")

                val handle = ensureSession(appContext, pair.first, pair.second)
                val jpegBytes = compressBitmapToJpeg(bitmap)

                val prompt = buildString {
                    append("You are an offline personal photo organizer (like Karakeep and mymind).\n")
                    append("Describe this image accurately and return ONLY a valid JSON object (no markdown fences):\n")
                    append("{\"title\":\"3-6 word title\",\"caption\":\"1-2 sentence detailed searchable description\",\"tags\":[\"tag1\",\"tag2\",\"tag3\",\"tag4\",\"tag5\",\"tag6\"]}\n")
                    append("Use 5-10 concise lowercase tags covering subjects, scene, content type (screenshot, receipt, document, whiteboard, portrait, landscape, food, etc.), and mood.")
                    if (includeOcrContext && !ocrText.isNullOrBlank()) {
                        append("\n\nText already extracted via OCR from this image:\n")
                        append(ocrText.trim().take(1000))
                    }
                }

                val rawText = LlamaCppNativeBridge.nativeAnalyzeImage(handle, jpegBytes, prompt, 280).trim()
                if (rawText.startsWith("ERROR:")) {
                    error(rawText)
                }

                val parsed = VisionApiClient.parseStructuredResponse(
                    rawText = rawText,
                    config = AiConfig(
                        provider = AiProviderType.ON_DEVICE_VLM,
                        modelName = pair.first.name
                    )
                )

                VlmAnalysisOutput(
                    title = parsed.title,
                    caption = parsed.caption,
                    tags = parsed.tags,
                    rawResponse = rawText,
                    engineId = engineId,
                    modelName = pair.first.name
                )
            }
        }
    }

    override suspend fun askQuestion(
        context: Context,
        bitmap: Bitmap,
        question: String,
        ocrText: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            runCatching {
                ensureNativeLibraryAvailable()
                val cleanQuestion = question.trim()
                require(cleanQuestion.isNotBlank()) { "Please enter a question about this image." }

                val appContext = context.applicationContext
                val pair = LocalVlmModelManager.resolveInstalledGgufPair(appContext)
                    ?: error("GGUF model and matching mmproj.gguf are both required. Import or download both in AI Settings.")

                val config = AiSettings.load(appContext)
                val handle = ensureSession(appContext, pair.first, pair.second)
                val jpegBytes = compressBitmapToJpeg(bitmap)

                val prompt = buildString {
                    append(cleanQuestion)
                    if (config.includeOcrContext && !ocrText.isNullOrBlank()) {
                        append("\n\nExtracted OCR text from this image:\n")
                        append(ocrText.trim().take(1000))
                    }
                }

                val rawText = LlamaCppNativeBridge.nativeAnalyzeImage(handle, jpegBytes, prompt, 320).trim()
                if (rawText.startsWith("ERROR:")) {
                    error(rawText)
                }
                rawText
            }
        }
    }

    override fun release() {
        val h = nativeHandle
        nativeHandle = 0L
        loadedModelPath = ""
        loadedMmprojPath = ""
        if (h != 0L && LlamaCppNativeBridge.isNativeLibraryLoaded) {
            runCatching { LlamaCppNativeBridge.nativeRelease(h) }
        }
    }

    private fun ensureNativeLibraryAvailable() {
        check(LlamaCppNativeBridge.isNativeLibraryLoaded) {
            "libemre_llama_vlm.so is not bundled in this APK. Build with `-PbuildLlamaNative=true` (Android NDK + CMake) to enable llama.cpp GGUF + mmproj inference, or switch to MediaPipe in AI Settings."
        }
    }

    private fun ensureSession(context: Context, modelFile: File, mmprojFile: File): Long {
        if (nativeHandle != 0L &&
            loadedModelPath == modelFile.absolutePath &&
            loadedMmprojPath == mmprojFile.absolutePath
        ) {
            return nativeHandle
        }
        release()
        val config = AiSettings.load(context)
        val handle = LlamaCppNativeBridge.nativeInit(
            modelPath = modelFile.absolutePath,
            mmprojPath = mmprojFile.absolutePath,
            nCtx = config.ggufContextLength.coerceIn(1024, 8192),
            nThreads = config.ggufThreads.coerceIn(1, 8)
        )
        check(handle != 0L) {
            "Failed to initialize llama.cpp + mtmd with ${modelFile.name} and ${mmprojFile.name}."
        }
        nativeHandle = handle
        loadedModelPath = modelFile.absolutePath
        loadedMmprojPath = mmprojFile.absolutePath
        return handle
    }

    private fun compressBitmapToJpeg(bitmap: Bitmap): ByteArray {
        val longest = max(bitmap.width, bitmap.height)
        val scaled = if (longest > MAX_IMAGE_SIDE) {
            val ratio = MAX_IMAGE_SIDE.toFloat() / longest.toFloat()
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * ratio).toInt()),
                max(1, (bitmap.height * ratio).toInt()),
                true
            )
        } else {
            bitmap
        }
        val shouldRecycle = scaled !== bitmap
        return try {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        } finally {
            if (shouldRecycle && !scaled.isRecycled) scaled.recycle()
        }
    }
}
