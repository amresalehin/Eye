package com.amresalehin.emregallery.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * On-device Vision-Language Model (VLM) runtime powered by Google MediaPipe / LiteRT-LM
 * (`com.google.mediapipe:tasks-genai`).
 *
 * Runs multimodal `.task` / `.litertlm` models (e.g., Gemma 3n E2B / Gemma 4 E2B) fully offline
 * for detailed photo descriptions, intelligent Karakeep/mymind-style tagging, OCR-contextual
 * understanding, and visual Q&A inside the Viewer.
 */
object MediaPipeVlmEngine : LocalVlmEngine {

    override val engineId: String = LocalVlmRegistry.ENGINE_MEDIAPIPE
    override val displayName: String = "MediaPipe / LiteRT-LM (Gemma 3n / 4)"
    override val supportedExtensions: List<String> = listOf("task", "litertlm")

    private const val MAX_IMAGE_SIDE = 512
    private const val MAX_TOKENS = 1024

    private val inferenceMutex = Mutex()

    @Volatile
    private var llmInference: LlmInference? = null

    @Volatile
    private var loadedModelPath: String? = null

    @Volatile
    private var loadedUseGpu: Boolean = true

    override fun isModelReady(context: Context): Boolean = LocalVlmModelManager.isInstalled(context)

    override fun activeModelFile(context: Context): File? = LocalVlmModelManager.resolveInstalledModel(context)

    override suspend fun analyzeImage(
        context: Context,
        bitmap: Bitmap,
        ocrText: String?,
        includeOcrContext: Boolean
    ): Result<VlmAnalysisOutput> = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            runCatching {
                val appContext = context.applicationContext
                val modelFile = activeModelFile(appContext)
                    ?: error("No on-device VLM model installed. Import or download a Gemma 3n / Gemma 4 .task file in Settings → AI & Semantic Search.")

                val config = AiSettings.load(appContext)
                val engine = ensureInference(appContext, modelFile, preferGpu = config.vlmUseGpu)

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

                val rawText = runSingleTurnVisionSession(engine, bitmap, prompt)
                val parsed = VisionApiClient.parseStructuredResponse(
                    rawText = rawText,
                    config = AiConfig(
                        provider = AiProviderType.ON_DEVICE_VLM,
                        modelName = modelFile.name
                    )
                )

                VlmAnalysisOutput(
                    title = parsed.title,
                    caption = parsed.caption,
                    tags = parsed.tags,
                    rawResponse = rawText,
                    engineId = engineId,
                    modelName = modelFile.name
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
                val cleanQuestion = question.trim()
                require(cleanQuestion.isNotBlank()) { "Please enter a question about this image." }

                val appContext = context.applicationContext
                val modelFile = activeModelFile(appContext)
                    ?: error("No on-device VLM model installed. Import or download a .task file in AI Settings.")

                val config = AiSettings.load(appContext)
                val engine = ensureInference(appContext, modelFile, preferGpu = config.vlmUseGpu)

                val prompt = buildString {
                    append(cleanQuestion)
                    if (config.includeOcrContext && !ocrText.isNullOrBlank()) {
                        append("\n\nExtracted OCR text from this image:\n")
                        append(ocrText.trim().take(1000))
                    }
                }

                runSingleTurnVisionSession(engine, bitmap, prompt).trim()
            }
        }
    }

    override fun release() {
        runCatching { llmInference?.close() }
        llmInference = null
        loadedModelPath = null
    }

    private fun ensureInference(context: Context, modelFile: File, preferGpu: Boolean): LlmInference {
        val current = llmInference
        if (current != null && loadedModelPath == modelFile.absolutePath && loadedUseGpu == preferGpu) {
            return current
        }
        release()

        val created = if (preferGpu) {
            runCatching {
                buildInference(context, modelFile, LlmInference.Backend.GPU)
            }.getOrElse {
                // Automatic CPU fallback on devices where OpenCL/GPU delegate is unsupported
                buildInference(context, modelFile, LlmInference.Backend.CPU)
            }
        } else {
            buildInference(context, modelFile, LlmInference.Backend.CPU)
        }

        llmInference = created
        loadedModelPath = modelFile.absolutePath
        loadedUseGpu = preferGpu
        return created
    }

    private fun buildInference(
        context: Context,
        modelFile: File,
        backend: LlmInference.Backend
    ): LlmInference {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(MAX_TOKENS)
            .setMaxNumImages(1)
            .setPreferredBackend(backend)
            .build()
        return LlmInference.createFromOptions(context, options)
    }

    private fun runSingleTurnVisionSession(
        engine: LlmInference,
        bitmap: Bitmap,
        prompt: String
    ): String {
        val preparedBitmap = prepareVisionBitmap(bitmap)
        val shouldRecycle = preparedBitmap !== bitmap
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(0.2f)
            .setTopK(40)
            .setGraphOptions(
                GraphOptions.builder()
                    .setEnableVisionModality(true)
                    .build()
            )
            .build()

        val session = LlmInferenceSession.createFromOptions(engine, sessionOptions)
        return try {
            val mpImage = BitmapImageBuilder(preparedBitmap).build()
            session.addQueryChunk(prompt)
            session.addImage(mpImage)
            session.generateResponse().orEmpty()
        } finally {
            runCatching { session.close() }
            if (shouldRecycle && !preparedBitmap.isRecycled) {
                preparedBitmap.recycle()
            }
        }
    }

    private fun prepareVisionBitmap(bitmap: Bitmap): Bitmap {
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        val longest = max(argb.width, argb.height)
        if (longest <= MAX_IMAGE_SIDE) return argb

        val scale = MAX_IMAGE_SIDE.toFloat() / longest.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            argb,
            max(1, (argb.width * scale).toInt()),
            max(1, (argb.height * scale).toInt()),
            true
        )
        if (argb !== bitmap && !argb.isRecycled) argb.recycle()
        return scaled
    }
}
