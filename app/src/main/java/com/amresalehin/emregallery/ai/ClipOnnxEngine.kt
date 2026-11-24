package com.amresalehin.emregallery.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale

enum class ClipModelState {
    NOT_DOWNLOADED,
    INSTALLED
}

data class ClipArtifactSpec(
    val filename: String,
    val minBytes: Long,
    val downloadUrl: String
)

/**
 * Manages optional on-device CLIP ViT-B/32 quantized ONNX weights (~154 MB total)
 * for 100% offline semantic text-to-image search, visually similar search,
 * and zero-shot auto-tagging using the app's existing ONNX Runtime Android dependency.
 */
object ClipOnnxModelManager {
    const val MODEL_ID = "clip_vit_b32_q8"
    const val EMBEDDING_DIM = 512
    private const val MODEL_SUBDIR = "ai_models/clip_vit_b32"
    private const val MIN_FREE_SPACE_BYTES = 200L * 1024 * 1024

    const val VISION_FILENAME = "vision_model_quantized.onnx"
    const val TEXT_FILENAME = "text_model_quantized.onnx"
    const val VOCAB_FILENAME = "vocab.json"

    val REQUIRED_ARTIFACTS = listOf(
        ClipArtifactSpec(
            filename = VISION_FILENAME,
            minBytes = 40_000_000L,
            downloadUrl = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/onnx/vision_model_quantized.onnx"
        ),
        ClipArtifactSpec(
            filename = TEXT_FILENAME,
            minBytes = 30_000_000L,
            downloadUrl = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/onnx/text_model_quantized.onnx"
        ),
        ClipArtifactSpec(
            filename = VOCAB_FILENAME,
            minBytes = 100_000L,
            downloadUrl = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/vocab.json"
        )
    )

    fun getModelDir(context: Context): File = File(context.applicationContext.filesDir, MODEL_SUBDIR)

    fun getState(context: Context): ClipModelState {
        val dir = getModelDir(context)
        if (!dir.exists()) return ClipModelState.NOT_DOWNLOADED
        val allPresent = REQUIRED_ARTIFACTS.all { spec ->
            val f = File(dir, spec.filename)
            f.isFile && f.length() >= spec.minBytes
        }
        return if (allPresent) ClipModelState.INSTALLED else ClipModelState.NOT_DOWNLOADED
    }

    fun isInstalled(context: Context): Boolean = getState(context) == ClipModelState.INSTALLED

    fun deleteModels(context: Context) {
        ClipOnnxEngine.closeSessions()
        getModelDir(context).deleteRecursively()
    }

    suspend fun downloadModels(
        context: Context,
        force: Boolean = false,
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
        awaitIfResumed: suspend () -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val dir = getModelDir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            error("Unable to create CLIP model directory.")
        }
        if (!force && isInstalled(context)) {
            onProgress(154_000_000L, 154_000_000L, "ready")
            return@withContext
        }

        val freeBytes = StatFs(dir.absolutePath).availableBytes
        check(freeBytes >= MIN_FREE_SPACE_BYTES) {
            "Insufficient storage. At least 200 MB free space is required for CLIP ONNX models."
        }

        val totalEstimated = 155L * 1024 * 1024
        var cumulative = 0L

        for (spec in REQUIRED_ARTIFACTS) {
            awaitIfResumed()
            val target = File(dir, spec.filename)
            if (!force && target.isFile && target.length() >= spec.minBytes) {
                cumulative += target.length()
                onProgress(cumulative, totalEstimated, "downloading")
                continue
            }

            val partFile = File(dir, "${spec.filename}.part")
            val conn = (URL(spec.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 45_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    error("Failed downloading ${spec.filename}: HTTP $code")
                }
                BufferedInputStream(conn.inputStream).use { input ->
                    partFile.outputStream().buffered().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var lastEmit = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            awaitIfResumed()
                            if (Thread.currentThread().isInterrupted) {
                                throw CancellationException("Download cancelled")
                            }
                            output.write(buf, 0, read)
                            cumulative += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= 250L) {
                                lastEmit = now
                                onProgress(cumulative, totalEstimated, "downloading")
                            }
                        }
                    }
                }
                if (partFile.length() < spec.minBytes) {
                    partFile.delete()
                    error("Downloaded file ${spec.filename} appears incomplete.")
                }
                if (target.exists()) target.delete()
                if (!partFile.renameTo(target)) {
                    partFile.copyTo(target, overwrite = true)
                    partFile.delete()
                }
            } finally {
                conn.disconnect()
                if (partFile.exists()) partFile.delete()
            }
        }
        onProgress(totalEstimated, totalEstimated, "ready")
    }
}

/**
 * On-device ONNX Runtime CLIP ViT-B/32 image & text encoder with zero-shot auto-tagging.
 * Gracefully returns null if the optional CLIP models are not yet downloaded.
 */
object ClipOnnxEngine {
    private const val IMAGE_SIZE = 224
    private const val MAX_TOKENS = 77
    private const val SOT_TOKEN = 49406L
    private const val EOT_TOKEN = 49407L

    private val CLIP_MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val CLIP_STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    @Volatile
    private var visionSession: OrtSession? = null
    @Volatile
    private var textSession: OrtSession? = null
    @Volatile
    private var vocabMap: Map<String, Long>? = null
    @Volatile
    private var conceptEmbeddings: List<Pair<String, FloatArray>>? = null

    private val lock = Any()

    private val ZERO_SHOT_CONCEPTS = listOf(
        "sunset", "sunrise", "beach", "ocean", "mountains", "forest", "nature", "flowers", "sky", "night",
        "city", "architecture", "street", "building", "bridge", "car", "bike", "train", "airplane", "travel",
        "food", "coffee", "restaurant", "dessert", "fruit", "cooking", "drink",
        "portrait", "selfie", "group photo", "wedding", "party", "concert", "sports", "fitness",
        "cat", "dog", "bird", "animal", "wildlife",
        "screenshot", "receipt", "document", "handwriting", "whiteboard", "book", "code", "chart", "diagram",
        "meme", "art", "painting", "illustration", "design", "minimalist", "interior", "workspace", "desk", "laptop"
    )

    fun isReady(context: Context): Boolean = ClipOnnxModelManager.isInstalled(context)

    fun closeSessions() {
        synchronized(lock) {
            runCatching { visionSession?.close() }
            runCatching { textSession?.close() }
            visionSession = null
            textSession = null
            conceptEmbeddings = null
        }
    }

    fun encodeImage(context: Context, bitmap: Bitmap): FloatArray? {
        if (!isReady(context)) return null
        return runCatching {
            val env = OrtEnvironment.getEnvironment()
            val session = ensureVisionSession(context, env) ?: return null
            val inputName = session.inputNames.firstOrNull() ?: "pixel_values"
            val tensorData = preprocessBitmap(bitmap)
            val shape = longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(tensorData), shape).use { inputTensor ->
                session.run(mapOf(inputName to inputTensor)).use { result ->
                    val raw = extractEmbeddingFromResult(result)
                    if (raw.isNotEmpty()) AiVectorMath.l2Normalize(raw) else null
                }
            }
        }.getOrNull()
    }

    fun encodeText(context: Context, query: String): FloatArray? {
        if (!isReady(context) || query.isBlank()) return null
        return runCatching {
            val env = OrtEnvironment.getEnvironment()
            val session = ensureTextSession(context, env) ?: return null
            val vocab = ensureVocab(context) ?: return null
            val tokens = tokenize(query, vocab)
            val shape = longArrayOf(1, MAX_TOKENS.toLong())

            val inputs = HashMap<String, OnnxTensor>()
            val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), shape)
            val inputNames = session.inputNames
            val idsName = inputNames.firstOrNull { it.contains("input_ids", ignoreCase = true) }
                ?: inputNames.first()
            inputs[idsName] = inputIdsTensor

            var maskTensor: OnnxTensor? = null
            val maskName = inputNames.firstOrNull { it.contains("attention_mask", ignoreCase = true) }
            if (maskName != null) {
                val mask = LongArray(MAX_TOKENS) { idx -> if (tokens[idx] != 0L) 1L else 0L }
                maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)
                inputs[maskName] = maskTensor
            }

            try {
                session.run(inputs).use { result ->
                    val raw = extractEmbeddingFromResult(result)
                    if (raw.isNotEmpty()) AiVectorMath.l2Normalize(raw) else null
                }
            } finally {
                inputIdsTensor.close()
                maskTensor?.close()
            }
        }.getOrNull()
    }

    /**
     * Zero-shot on-device auto-tagging using CLIP cosine similarity against curated visual concepts.
     */
    fun predictZeroShotTags(
        context: Context,
        imageEmbedding: FloatArray,
        topK: Int = 5,
        minSimilarity: Float = 0.24f
    ): List<String> {
        if (imageEmbedding.isEmpty() || !isReady(context)) return emptyList()
        val concepts = ensureConceptEmbeddings(context)
        if (concepts.isEmpty()) return emptyList()
        return concepts
            .map { (label, vec) -> label to AiVectorMath.cosineSimilarity(imageEmbedding, vec) }
            .filter { it.second >= minSimilarity }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    private fun ensureConceptEmbeddings(context: Context): List<Pair<String, FloatArray>> {
        conceptEmbeddings?.let { return it }
        synchronized(lock) {
            conceptEmbeddings?.let { return it }
            val computed = ZERO_SHOT_CONCEPTS.mapNotNull { label ->
                val vec = encodeText(context, "a photo of $label")
                if (vec != null && vec.isNotEmpty()) label to vec else null
            }
            conceptEmbeddings = computed
            return computed
        }
    }

    private fun ensureVisionSession(context: Context, env: OrtEnvironment): OrtSession? {
        visionSession?.let { return it }
        synchronized(lock) {
            visionSession?.let { return it }
            val file = File(ClipOnnxModelManager.getModelDir(context), ClipOnnxModelManager.VISION_FILENAME)
            if (!file.isFile) return null
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            return env.createSession(file.absolutePath, opts).also { visionSession = it }
        }
    }

    private fun ensureTextSession(context: Context, env: OrtEnvironment): OrtSession? {
        textSession?.let { return it }
        synchronized(lock) {
            textSession?.let { return it }
            val file = File(ClipOnnxModelManager.getModelDir(context), ClipOnnxModelManager.TEXT_FILENAME)
            if (!file.isFile) return null
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            return env.createSession(file.absolutePath, opts).also { textSession = it }
        }
    }

    private fun ensureVocab(context: Context): Map<String, Long>? {
        vocabMap?.let { return it }
        synchronized(lock) {
            vocabMap?.let { return it }
            val file = File(ClipOnnxModelManager.getModelDir(context), ClipOnnxModelManager.VOCAB_FILENAME)
            if (!file.isFile) return null
            val json = JSONObject(file.readText())
            val map = HashMap<String, Long>(json.length())
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = json.optLong(k, 0L)
            }
            vocabMap = map
            return map
        }
    }

    /**
     * Lightweight word/subword tokenizer matching CLIP's `</w>` vocab entries.
     */
    private fun tokenize(text: String, vocab: Map<String, Long>): LongArray {
        val out = LongArray(MAX_TOKENS)
        out[0] = SOT_TOKEN
        var pos = 1
        val words = text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s'-]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        for (word in words) {
            if (pos >= MAX_TOKENS - 1) break
            val fullToken = vocab["$word</w>"] ?: vocab[word]
            if (fullToken != null) {
                out[pos++] = fullToken
            } else {
                // Greedy longest-prefix subword fallback
                var start = 0
                while (start < word.length && pos < MAX_TOKENS - 1) {
                    var matched: Long? = null
                    var end = word.length
                    while (end > start) {
                        val sub = word.substring(start, end)
                        val key = if (end == word.length) "$sub</w>" else sub
                        val id = vocab[key] ?: vocab[sub]
                        if (id != null) {
                            matched = id
                            break
                        }
                        end--
                    }
                    if (matched != null) {
                        out[pos++] = matched
                        start = end
                    } else {
                        start++
                    }
                }
            }
        }
        out[pos.coerceAtMost(MAX_TOKENS - 1)] = EOT_TOKEN
        return out
    }

    private fun preprocessBitmap(bitmap: Bitmap): FloatArray {
        val scaled = if (bitmap.width == IMAGE_SIZE && bitmap.height == IMAGE_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
        }
        val shouldRecycle = scaled !== bitmap
        try {
            val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
            scaled.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
            val data = FloatArray(3 * IMAGE_SIZE * IMAGE_SIZE)
            val stride = IMAGE_SIZE * IMAGE_SIZE
            for (i in pixels.indices) {
                val px = pixels[i]
                val r = ((px shr 16) and 0xFF) / 255f
                val g = ((px shr 8) and 0xFF) / 255f
                val b = (px and 0xFF) / 255f
                data[i] = (r - CLIP_MEAN[0]) / CLIP_STD[0]
                data[stride + i] = (g - CLIP_MEAN[1]) / CLIP_STD[1]
                data[2 * stride + i] = (b - CLIP_MEAN[2]) / CLIP_STD[2]
            }
            return data
        } finally {
            if (shouldRecycle && !scaled.isRecycled) scaled.recycle()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractEmbeddingFromResult(result: OrtSession.Result): FloatArray {
        // Prefer named projection outputs (`image_embeds` / `text_embeds`), else first 2D float tensor
        val preferred = listOf("image_embeds", "text_embeds", "pooler_output")
        for (name in preferred) {
            val opt = result.get(name)
            if (opt.isPresent) {
                val arr = flattenFloatOutput(opt.get().value)
                if (arr.isNotEmpty()) return arr
            }
        }
        for (entry in result) {
            val arr = flattenFloatOutput(entry.value.value)
            if (arr.isNotEmpty()) return arr
        }
        return FloatArray(0)
    }

    private fun flattenFloatOutput(value: Any?): FloatArray {
        return when (value) {
            is FloatArray -> value
            is Array<*> -> {
                val first = value.firstOrNull()
                when (first) {
                    is FloatArray -> first
                    is Array<*> -> (first.firstOrNull() as? FloatArray) ?: FloatArray(0)
                    else -> FloatArray(0)
                }
            }
            else -> FloatArray(0)
        }
    }
}
