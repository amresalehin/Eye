package com.amresalehin.emregallery.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.amresalehin.emregallery.data.preferences.AiConfig
import com.amresalehin.emregallery.data.preferences.AiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

data class AiSearchMatchResult(
    val matchingUris: List<String>,
    val summary: String
)

/**
 * Shots Studio-inspired multimodal AI client supporting user-supplied custom API keys
 * for both Google Gemini API and OpenAI-compatible endpoints.
 */
class CustomAiClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    suspend fun testConnection(config: AiConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.apiKey.isNotBlank()) { "Please enter an API key first." }
            require(config.modelId.isNotBlank()) { "Please specify a model ID." }

            val started = System.currentTimeMillis()
            val text = executeTextPrompt(
                prompt = "Respond with exactly: OK",
                config = config,
                expectJson = false
            )
            val latencyMs = (System.currentTimeMillis() - started).coerceAtLeast(1L)
            val preview = text.trim().take(48).ifBlank { "OK" }
            "Text API connected (${latencyMs}ms) · $preview"
        }
    }

    /**
     * Sends a tiny generated JPEG to verify that the configured model accepts image input.
     * A text-only connection test must not be treated as proof of vision capability.
     */
    suspend fun testVisionConnection(config: AiConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.apiKey.isNotBlank()) { "Please enter an API key first." }
            require(config.modelId.isNotBlank()) { "Please specify a model ID." }

            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            val jpegBase64 = try {
                bitmap.eraseColor(Color.BLUE)
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
                        "Could not prepare the vision test image."
                    }
                    Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                }
            } finally {
                bitmap.recycle()
            }

            val started = System.currentTimeMillis()
            val response = executeVisionPrompt(
                prompt = "Identify the dominant color in the attached image. Reply with a single color word only.",
                jpegBase64 = jpegBase64,
                config = config,
                expectJson = false
            )
            if (!Regex("\\bblue\\b", RegexOption.IGNORE_CASE).containsMatchIn(response)) {
                error("The endpoint accepted an image request but did not identify the test image. Vision capability could not be confirmed. Model response: ${response.trim().take(180)}")
            }
            val latencyMs = (System.currentTimeMillis() - started).coerceAtLeast(1L)
            "Vision API verified (${latencyMs}ms) · image input recognized"
        }
    }

    suspend fun analyzeImage(
        context: Context,
        mediaUri: String,
        config: AiConfig,
        existingCollections: List<String> = emptyList(),
        ocrContext: String? = null
    ): Result<AiMediaAnalysis> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.enabled) { "AI features are currently disabled in settings." }
            require(config.apiKey.isNotBlank()) { "API key is missing. Add your custom API key in AI Settings." }

            val jpegBase64 = encodeImageToJpegBase64(context, mediaUri)
                ?: error("Could not decode image for AI analysis.")

            val prompt = buildAnalysisPrompt(
                existingCollections = existingCollections,
                ocrContext = ocrContext,
                customInstructions = config.customPromptInstructions
            )

            val rawResponse = executeVisionPrompt(
                prompt = prompt,
                jpegBase64 = jpegBase64,
                config = config,
                expectJson = true
            )

            parseAnalysisJson(
                mediaUri = mediaUri,
                rawText = rawResponse,
                modelUsed = config.modelId
            )
        }
    }

    suspend fun askAboutImage(
        context: Context,
        mediaUri: String,
        question: String,
        config: AiConfig,
        ocrContext: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.enabled) { "AI features are disabled in settings." }
            require(config.apiKey.isNotBlank()) { "API key is missing. Add your custom API key in AI Settings." }
            val trimmedQuestion = question.trim()
            require(trimmedQuestion.isNotEmpty()) { "Enter a question first." }

            val jpegBase64 = encodeImageToJpegBase64(context, mediaUri)
                ?: error("Could not decode image.")

            val prompt = buildString {
                append("Answer the user's question about this image accurately and concisely.\n")
                if (!ocrContext.isNullOrBlank()) {
                    append("\nExtracted on-device OCR text (may help with small text):\n")
                    append(ocrContext.take(2000))
                    append("\n")
                }
                if (config.customPromptInstructions.isNotBlank()) {
                    append("\nUser preferences: ")
                    append(config.customPromptInstructions.take(400))
                    append("\n")
                }
                append("\nQuestion: ")
                append(trimmedQuestion)
            }

            executeVisionPrompt(
                prompt = prompt,
                jpegBase64 = jpegBase64,
                config = config,
                expectJson = false
            ).trim()
        }
    }

    suspend fun semanticSearch(
        query: String,
        analyses: List<AiMediaAnalysis>,
        config: AiConfig
    ): Result<AiSearchMatchResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.enabled) { "AI features are disabled in settings." }
            require(config.apiKey.isNotBlank()) { "API key is missing. Add your custom API key in AI Settings." }
            val trimmedQuery = query.trim()
            require(trimmedQuery.isNotEmpty()) { "Enter a search query." }
            if (analyses.isEmpty()) {
                return@runCatching AiSearchMatchResult(
                    matchingUris = emptyList(),
                    summary = "No AI-analyzed photos yet. Run AI indexing or analyze photos first."
                )
            }

            val indexedItems = analyses.take(120)
            val catalogJson = JSONArray()
            indexedItems.forEachIndexed { idx, item ->
                catalogJson.put(
                    JSONObject().apply {
                        put("i", idx)
                        put("title", item.title)
                        put("desc", item.description.take(180))
                        put("tags", JSONArray(item.tags.take(8)))
                        if (item.suggestedCollection.isNotBlank()) {
                            put("col", item.suggestedCollection)
                        }
                        if (item.keyDetails.isNotBlank()) {
                            put("details", item.keyDetails.take(120))
                        }
                    }
                )
            }

            val prompt = """
                You are a smart gallery search engine. Given the user's natural language search query and a catalog of analyzed photos/screenshots (indexed by "i"), return the matching item indices ranked from best match to weakest match.
                Return ONLY valid JSON with this exact schema:
                {
                  "matched_indices": [0, 3],
                  "summary": "Brief 1-sentence summary of what was matched"
                }
                If nothing matches, return {"matched_indices": [], "summary": "No matching analyzed photos found."}.

                Query: "$trimmedQuery"
                Catalog:
                $catalogJson
            """.trimIndent()

            val raw = executeTextPrompt(prompt = prompt, config = config, expectJson = true)
            val obj = extractJsonObject(raw)
            val indicesArr = obj.optJSONArray("matched_indices") ?: JSONArray()
            val uris = mutableListOf<String>()
            for (k in 0 until indicesArr.length()) {
                val idx = indicesArr.optInt(k, -1)
                if (idx in indexedItems.indices) {
                    val uri = indexedItems[idx].mediaUri
                    if (uri !in uris) uris.add(uri)
                }
            }
            val summary = obj.optString("summary", "").trim()
                .ifBlank { if (uris.isEmpty()) "No AI matches found." else "Found ${uris.size} matching items." }
            AiSearchMatchResult(matchingUris = uris, summary = summary)
        }
    }

    private fun buildAnalysisPrompt(
        existingCollections: List<String>,
        ocrContext: String?,
        customInstructions: String
    ): String = buildString {
        append(
            """
            Analyze this image or screenshot for a smart photo & screenshot organizer.
            Return ONLY a valid JSON object with this exact schema:
            {
              "title": "Short 2-5 word descriptive title",
              "description": "Clear 1-2 sentence summary of the visual content, scene, app/UI, or subject.",
              "tags": ["tag1", "tag2", "tag3"],
              "collection": "Single concise collection name",
              "key_details": "Important extracted details like dates, prices, names, code language, or place (or empty string)"
            }
            Rules:
            - Provide 3 to 7 concise, lowercase, searchable tags (1-2 words each, no # prefix).
            - Choose a clean collection name such as Receipts, Documents, Code, Chats, Notes, Travel, Food, Shopping, Design, Memes, Nature, People, or Devices.
            """.trimIndent()
        )
        if (existingCollections.isNotEmpty()) {
            append("\n- Prefer reusing one of these existing collections if it fits well: ")
            append(existingCollections.distinct().take(20).joinToString(", "))
        }
        if (!ocrContext.isNullOrBlank()) {
            append("\n\nOn-device OCR text detected in this image:\n")
            append(ocrContext.take(1500))
        }
        if (customInstructions.isNotBlank()) {
            append("\n\nAdditional user instructions:\n")
            append(customInstructions.take(500))
        }
    }

    internal fun parseAnalysisJson(
        mediaUri: String,
        rawText: String,
        modelUsed: String
    ): AiMediaAnalysis {
        val obj = extractJsonObject(rawText)
        val title = obj.optString("title", "").trim().ifBlank { "Analyzed Image" }
        val description = obj.optString("description", "").trim()
        val collection = obj.optString("collection", "").trim()
        val keyDetails = obj.optString("key_details", "").trim()
        val tagsArr = obj.optJSONArray("tags") ?: JSONArray()
        val tags = mutableListOf<String>()
        for (i in 0 until tagsArr.length()) {
            val cleaned = tagsArr.optString(i, "").trim().removePrefix("#").lowercase()
            if (cleaned.isNotEmpty() && cleaned !in tags) {
                tags.add(cleaned)
            }
        }
        return AiMediaAnalysis(
            mediaUri = mediaUri,
            title = title,
            description = description,
            tags = tags,
            suggestedCollection = collection,
            keyDetails = keyDetails,
            modelUsed = modelUsed,
            analyzedAtMillis = System.currentTimeMillis(),
            status = AiMediaAnalysis.STATUS_COMPLETED
        )
    }

    internal fun extractJsonObject(rawText: String): JSONObject {
        val cleaned = rawText.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        val slice = if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
        return JSONObject(slice)
    }

    private fun executeTextPrompt(
        prompt: String,
        config: AiConfig,
        expectJson: Boolean
    ): String {
        return when (config.provider) {
            AiProvider.GEMINI -> callGeminiApi(
                prompt = prompt,
                jpegBase64 = null,
                config = config,
                expectJson = expectJson
            )
            AiProvider.OPENAI_COMPATIBLE -> callOpenAiCompatibleApi(
                prompt = prompt,
                jpegBase64 = null,
                config = config,
                expectJson = expectJson
            )
        }
    }

    private fun executeVisionPrompt(
        prompt: String,
        jpegBase64: String,
        config: AiConfig,
        expectJson: Boolean
    ): String {
        return when (config.provider) {
            AiProvider.GEMINI -> callGeminiApi(
                prompt = prompt,
                jpegBase64 = jpegBase64,
                config = config,
                expectJson = expectJson
            )
            AiProvider.OPENAI_COMPATIBLE -> callOpenAiCompatibleApi(
                prompt = prompt,
                jpegBase64 = jpegBase64,
                config = config,
                expectJson = expectJson
            )
        }
    }

    private fun callGeminiApi(
        prompt: String,
        jpegBase64: String?,
        config: AiConfig,
        expectJson: Boolean
    ): String {
        val baseUrl = config.customBaseUrl.ifBlank { AiConfig.DEFAULT_GEMINI_BASE_URL }.trimEnd('/')
        val model = config.modelId.removePrefix("models/")
        val url = "$baseUrl/v1beta/models/$model:generateContent"

        val parts = JSONArray().apply {
            if (jpegBase64 != null) {
                put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", jpegBase64)
                        }
                    )
                )
            }
            put(JSONObject().put("text", prompt))
        }

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", parts)
                    }
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.2)
                    if (expectJson) {
                        put("responseMimeType", "application/json")
                    }
                }
            )
        }

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", config.apiKey)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(formatHttpError("Gemini", response.code, bodyStr))
            }
            val root = JSONObject(bodyStr)
            val candidates = root.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val respParts = firstCandidate?.optJSONObject("content")?.optJSONArray("parts")
            val textBuilder = StringBuilder()
            if (respParts != null) {
                for (i in 0 until respParts.length()) {
                    val partText = respParts.optJSONObject(i)?.optString("text").orEmpty()
                    if (partText.isNotEmpty()) textBuilder.append(partText)
                }
            }
            val resultText = textBuilder.toString().trim()
            if (resultText.isEmpty()) {
                val finishReason = firstCandidate?.optString("finishReason").orEmpty()
                error(
                    if (finishReason.isNotBlank()) "Empty AI response ($finishReason)"
                    else "Empty response from Gemini API"
                )
            }
            return resultText
        }
    }

    private fun callOpenAiCompatibleApi(
        prompt: String,
        jpegBase64: String?,
        config: AiConfig,
        expectJson: Boolean
    ): String {
        val rawBase = config.customBaseUrl.ifBlank { AiConfig.DEFAULT_OPENAI_BASE_URL }.trimEnd('/')
        val url = if (rawBase.endsWith("/chat/completions")) rawBase else "$rawBase/chat/completions"

        val contentValue: Any = if (jpegBase64 != null) {
            JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", prompt)
                })
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64"))
                })
            }
        } else {
            prompt
        }

        fun buildRequest(includeJsonMode: Boolean): Request {
            val payload = JSONObject().apply {
                put("model", config.modelId)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", contentValue)
                }))
                put("stream", false)
                put("temperature", 0.2)
                if (includeJsonMode) put("response_format", JSONObject().put("type", "json_object"))
            }
            return Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }

        val (statusCode, bodyStr) = httpClient.newCall(buildRequest(expectJson)).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
        if (statusCode !in 200..299) {
            if (expectJson && isJsonModeUnsupported(statusCode, bodyStr)) {
                return callOpenAiCompatibleApi(
                    prompt = prompt,
                    jpegBase64 = jpegBase64,
                    config = config,
                    expectJson = false
                )
            }
            error(formatHttpError("OpenAI-compatible", statusCode, bodyStr))
        }
        if (bodyStr.trimStart().startsWith("data:")) {
            error("The endpoint returned a streamed response despite stream=false. This provider must return a non-streaming chat completion.")
        }
        val root = runCatching { JSONObject(bodyStr) }.getOrElse {
            error("The endpoint returned a non-JSON response (HTTP $statusCode): ${bodyStr.take(800)}")
        }
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        val responseContent = message?.opt("content")
        val content = when (responseContent) {
            is String -> responseContent.trim()
            is JSONArray -> buildString {
                for (index in 0 until responseContent.length()) {
                    val text = responseContent.optJSONObject(index)?.optString("text").orEmpty()
                    if (text.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append(text)
                    }
                }
            }.trim()
            else -> ""
        }
        if (content.isEmpty()) error("Empty response from AI endpoint. The provider returned no assistant message content.")
        return content
    }

    private fun isJsonModeUnsupported(statusCode: Int, responseBody: String): Boolean {
        if (statusCode !in 400..499) return false
        val message = responseBody.lowercase()
        if (!message.contains("response_format")) return false
        return listOf(
            "not supported", "does not support", "unsupported", "unknown parameter",
            "unknown field", "unrecognized", "not allowed", "invalid parameter", "extra fields"
        ).any(message::contains)
    }

    internal fun formatHttpError(providerName: String, statusCode: Int, body: String): String {
        val parsedDetail = runCatching {
            val root = JSONObject(body)
            val errorValue = root.opt("error")
            val errorObject = errorValue as? JSONObject
            val message = errorObject?.optString("message")?.takeIf { it.isNotBlank() }
                ?: errorObject?.optString("detail")?.takeIf { it.isNotBlank() }
                ?: (errorValue as? String)?.takeIf { it.isNotBlank() }
                ?: root.optString("detail").takeIf { it.isNotBlank() }
                ?: root.optString("message").takeIf { it.isNotBlank() }
            val code = errorObject?.optString("code")?.takeIf { it.isNotBlank() }
            when {
                message == null -> null
                code != null && !message.contains(code, ignoreCase = true) -> "$message (code: $code)"
                else -> message
            }
        }.getOrNull()
        val detail = parsedDetail ?: body.trim().take(1200).ifBlank { "No response body returned." }
        return "$providerName API error (HTTP $statusCode): $detail"
    }

    private fun encodeImageToJpegBase64(context: Context, uriString: String, maxDimension: Int = 1024): String? {
        val uri = Uri.parse(uriString)
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, boundsOpts)
        }
        val origW = boundsOpts.outWidth
        val origH = boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) return null

        var sampleSize = 1
        while (origW / sampleSize > maxDimension * 2 || origH / sampleSize > maxDimension * 2) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOpts)
        } ?: return null

        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val rotated = applyExifOrientation(decoded, orientation)
        val longest = max(rotated.width, rotated.height)
        val scaled = if (longest > maxDimension) {
            val scale = maxDimension.toFloat() / longest.toFloat()
            val targetW = (rotated.width * scale).roundToInt().coerceAtLeast(1)
            val targetH = (rotated.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(rotated, targetW, targetH, true).also {
                if (it !== rotated) rotated.recycle()
            }
        } else {
            rotated
        }

        return try {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            scaled.recycle()
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        }.getOrDefault(bitmap)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
