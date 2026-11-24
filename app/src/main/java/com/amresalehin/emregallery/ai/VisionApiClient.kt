package com.amresalehin.emregallery.ai

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

data class VisionAnalysisResult(
    val title: String,
    val caption: String,
    val tags: List<String>,
    val provider: String,
    val model: String
)

/**
 * Hybrid Vision API client supporting:
 * - Self-hosted Ollama (OpenAI-compatible `/v1/chat/completions` or native `/api/chat`)
 * - Google Gemini (OpenAI-compatible endpoint)
 * - OpenAI / OpenRouter / LM Studio / any OpenAI-compatible vision endpoint
 *
 * Produces Karakeep & mymind-style structured JSON: short title, searchable description, and concise tags.
 */
object VisionApiClient {

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private const val SYSTEM_PROMPT = """You are an image cataloging assistant for a private personal gallery (inspired by Karakeep and mymind).
Analyze the provided image (and any extracted OCR text if given) and return ONLY a valid JSON object with no markdown fences and no extra text:
{
  "title": "Short 3-6 word descriptive title",
  "caption": "Concise 1-2 sentence searchable description of the scene, subject, and context.",
  "tags": ["tag1", "tag2", "tag3", "tag4", "tag5", "tag6"]
}
Rules for tags:
- Return 5 to 10 concise lowercase tags (1-2 words each).
- Include objects, scene/setting, content type (e.g., screenshot, receipt, document, meme, portrait, landscape, food, whiteboard), mood/lighting, and notable concepts.
- Avoid generic filler tags like "image", "photo", or "picture"."""

    suspend fun analyzeImage(
        bitmap: Bitmap,
        config: AiConfig,
        ocrText: String? = null
    ): Result<VisionAnalysisResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.isRemoteConfigured) { "AI API provider is not configured." }

            val base64Jpeg = encodeBitmapToBase64Jpeg(bitmap, maxSide = 1024, quality = 80)
            val userPrompt = buildString {
                append("Analyze this image and return the JSON object.")
                if (config.includeOcrContext && !ocrText.isNullOrBlank()) {
                    val trimmedOcr = ocrText.trim().take(1200)
                    append("\n\nExtracted OCR text from this image:\n")
                    append(trimmedOcr)
                }
            }

            val normalizedBase = config.baseUrl.trim().trimEnd('/')
            val isNativeOllama = config.provider == AiProviderType.OLLAMA &&
                !normalizedBase.endsWith("/v1", ignoreCase = true)

            val rawText = if (isNativeOllama) {
                callNativeOllamaChat(normalizedBase, config, userPrompt, base64Jpeg)
            } else {
                callOpenAiCompatibleChat(normalizedBase, config, userPrompt, base64Jpeg)
            }

            parseStructuredResponse(rawText, config)
        }
    }

    suspend fun askQuestion(
        bitmap: Bitmap,
        config: AiConfig,
        question: String,
        ocrText: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.isRemoteConfigured) { "AI Vision API provider is not configured." }
            val base64Jpeg = encodeBitmapToBase64Jpeg(bitmap, maxSide = 1024, quality = 80)
            val userPrompt = buildString {
                append(question.trim())
                if (config.includeOcrContext && !ocrText.isNullOrBlank()) {
                    append("\n\nExtracted OCR text from this image:\n")
                    append(ocrText.trim().take(1200))
                }
            }
            val normalizedBase = config.baseUrl.trim().trimEnd('/')
            val isNativeOllama = config.provider == AiProviderType.OLLAMA &&
                !normalizedBase.endsWith("/v1", ignoreCase = true)
            if (isNativeOllama) {
                callNativeOllamaChat(normalizedBase, config, userPrompt, base64Jpeg).trim()
            } else {
                callOpenAiCompatibleChat(normalizedBase, config, userPrompt, base64Jpeg).trim()
            }
        }
    }

    suspend fun testConnection(config: AiConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.provider != AiProviderType.ON_DEVICE_VLM) { "Select a remote or Ollama provider to test." }
            require(config.baseUrl.isNotBlank()) { "Base URL is required." }
            if (config.provider.requiresApiKey) {
                require(config.apiKey.isNotBlank()) { "API key is required for ${config.provider.label}." }
            }

            val normalizedBase = config.baseUrl.trim().trimEnd('/')
            val isNativeOllama = config.provider == AiProviderType.OLLAMA &&
                !normalizedBase.endsWith("/v1", ignoreCase = true)

            val testUrl = if (isNativeOllama) "$normalizedBase/api/tags" else "$normalizedBase/models"
            val reqBuilder = Request.Builder().url(testUrl).get()
            if (config.apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${config.apiKey.trim()}")
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    "Connected (${resp.code}) to ${config.provider.label}"
                } else {
                    val err = resp.body?.string()?.take(200).orEmpty()
                    error("HTTP ${resp.code}: ${err.ifBlank { resp.message }}")
                }
            }
        }
    }

    private fun callOpenAiCompatibleChat(
        baseUrl: String,
        config: AiConfig,
        userPrompt: String,
        base64Jpeg: String
    ): String {
        val endpoint = if (baseUrl.endsWith("/chat/completions", ignoreCase = true)) {
            baseUrl
        } else {
            "$baseUrl/chat/completions"
        }

        val userContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", userPrompt)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64Jpeg")
                })
            })
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            })
        }

        val payload = JSONObject().apply {
            put("model", config.modelName.trim())
            put("messages", messages)
            put("temperature", 0.2)
            put("max_tokens", 350)
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
        if (config.apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${config.apiKey.trim()}")
        }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("Vision API HTTP ${resp.code}: ${bodyStr.take(240)}")
            }
            val root = JSONObject(bodyStr)
            val choices = root.optJSONArray("choices")
            val firstChoice = choices?.optJSONObject(0)
            val message = firstChoice?.optJSONObject("message")
            val content = message?.optString("content").orEmpty()
            if (content.isBlank()) error("Vision API returned an empty response.")
            return content
        }
    }

    private fun callNativeOllamaChat(
        baseUrl: String,
        config: AiConfig,
        userPrompt: String,
        base64Jpeg: String
    ): String {
        val endpoint = "$baseUrl/api/chat"
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userPrompt)
                put("images", JSONArray().apply { put(base64Jpeg) })
            })
        }

        val payload = JSONObject().apply {
            put("model", config.modelName.trim())
            put("messages", messages)
            put("stream", false)
            put("format", "json")
        }

        val req = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .build()

        client.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("Ollama API HTTP ${resp.code}: ${bodyStr.take(240)}")
            }
            val root = JSONObject(bodyStr)
            val content = root.optJSONObject("message")?.optString("content").orEmpty()
            if (content.isBlank()) error("Ollama returned an empty response.")
            return content
        }
    }

    internal fun parseStructuredResponse(rawText: String, config: AiConfig): VisionAnalysisResult {
        val cleaned = extractJsonObjectString(rawText)
        val json = runCatching { JSONObject(cleaned) }.getOrNull()
        if (json != null) {
            val title = json.optString("title", "").trim()
            val caption = json.optString("caption", "").trim()
            val tagsArr = json.optJSONArray("tags")
            val tags = buildList {
                if (tagsArr != null) {
                    for (i in 0 until tagsArr.length()) {
                        val t = tagsArr.optString(i).trim().lowercase(Locale.ROOT)
                            .removePrefix("#")
                        if (t.isNotBlank() && t.length <= 32) add(t)
                    }
                }
            }.distinct().take(12)

            return VisionAnalysisResult(
                title = title,
                caption = caption.ifBlank { rawText.trim().take(240) },
                tags = tags,
                provider = config.provider.id,
                model = config.modelName.trim()
            )
        }

        // Fallback if a model ignores JSON formatting
        val fallbackCaption = rawText.trim().replace(Regex("\\s+"), " ").take(280)
        return VisionAnalysisResult(
            title = "",
            caption = fallbackCaption,
            tags = emptyList(),
            provider = config.provider.id,
            model = config.modelName.trim()
        )
    }

    internal fun extractJsonObjectString(raw: String): String {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }

    private fun encodeBitmapToBase64Jpeg(bitmap: Bitmap, maxSide: Int, quality: Int): String {
        val w = bitmap.width
        val h = bitmap.height
        val longest = max(w, h)
        val target = if (longest > maxSide) {
            val scale = maxSide.toFloat() / longest.toFloat()
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (w * scale).toInt()),
                max(1, (h * scale).toInt()),
                true
            )
        } else {
            bitmap
        }
        val shouldRecycle = target !== bitmap
        return try {
            val baos = ByteArrayOutputStream()
            target.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (shouldRecycle && !target.isRecycled) target.recycle()
        }
    }
}
