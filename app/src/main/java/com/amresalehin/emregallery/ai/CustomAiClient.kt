package com.amresalehin.emregallery.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.amresalehin.emregallery.data.MetadataNameNormalizer
import com.amresalehin.emregallery.data.preferences.AiConfig
import com.amresalehin.emregallery.data.preferences.isOllamaEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

data class AiSearchMatchResult(
    val matchingUris: List<String>,
    val summary: String
)

data class CapabilityCheckResult(
    val supported: Boolean,
    val latencyMs: Long? = null,
    val detail: String = ""
)

data class EndpointCapabilityReport(
    val protocolLabel: String,
    val text: CapabilityCheckResult,
    val vision: CapabilityCheckResult
) {
    val fullyReady: Boolean get() = text.supported && vision.supported
}

internal enum class ProtocolAdapterKind(val label: String) {
    OPENAI_COMPATIBLE("OpenAI-compatible"),
    GEMINI_NATIVE("Gemini native"),
    ANTHROPIC_NATIVE("Anthropic native")
}

/**
 * Unified custom API client:
 * - Uses one common OpenAI-compatible client (`/chat/completions`) for providers exposing
 *   that protocol, including Gemini's compatibility endpoint (`/v1beta/openai`).
 * - Dispatches to internal native API adapters only when the configured endpoint URL
 *   targets a native protocol (e.g. Gemini `:generateContent` or Anthropic `/messages`),
 *   with zero model-name assumptions.
 * - Supports live verification of both text and vision capabilities.
 */
class CustomAiClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    suspend fun fetchAvailableModels(config: AiConfig): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(config.normalizedBaseUrl.isNotBlank()) { "Base URL is required." }
                val base = config.normalizedBaseUrl
                val uri = URI(base)
                require(uri.scheme == "http" || uri.scheme == "https") {
                    "Base URL must start with http:// or https://."
                }
                require(!uri.host.isNullOrBlank()) { "Base URL must include a valid host." }

                val host = uri.host.orEmpty().lowercase()
                val path = uri.path.orEmpty().trimEnd('/')
                val isGemini = host.contains("generativelanguage.googleapis.com") &&
                    !path.contains("/openai", ignoreCase = true)
                val isAnthropic = host.contains("api.anthropic.com") ||
                    path.endsWith("/v1/messages") ||
                    path.endsWith("/messages") && !path.contains("/chat")

                // Accept either a provider root or a pasted operation URL, but query
                // the model-list endpoint rather than appending /models to the operation.
                val requestPath = when {
                    isGemini -> when {
                        path.endsWith("/models") -> path
                        path.contains("/v1beta/models/") ->
                            path.substring(0, path.indexOf("/v1beta")) + "/v1beta/models"
                        path.contains("/v1/models/") ->
                            path.substring(0, path.indexOf("/v1")) + "/v1/models"
                        path.endsWith("/v1beta") || path.endsWith("/v1") -> "$path/models"
                        else -> "$path/v1beta/models"
                    }
                    isAnthropic -> when {
                        path.endsWith("/v1/models") -> path
                        path.endsWith("/v1/messages") -> path.removeSuffix("/messages") + "/models"
                        path.endsWith("/v1") -> "$path/models"
                        path.endsWith("/messages") -> path.removeSuffix("/messages") + "/models"
                        else -> "$path/v1/models"
                    }
                    else -> {
                        if (path.endsWith("/models")) {
                            path
                        } else {
                            val apiRoot = listOf(
                                "/chat/completions",
                                "/responses",
                                "/embeddings"
                            ).firstOrNull { path.endsWith(it) }
                                ?.let { path.removeSuffix(it) }
                                ?: path
                            "$apiRoot/models"
                        }
                    }
                }.replace("//", "/").let { resolvedPath ->
                    if (resolvedPath.startsWith("/")) resolvedPath else "/$resolvedPath"
                }
                val url = URI(
                    uri.scheme,
                    uri.userInfo,
                    uri.host,
                    uri.port,
                    requestPath,
                    null,
                    null
                ).toASCIIString()

                val requestBuilder = Request.Builder().url(url).get()
                    .header("Accept", "application/json")
                when {
                    isGemini && config.apiKey.isNotBlank() ->
                        requestBuilder.header("x-goog-api-key", config.apiKey.trim())
                    isAnthropic -> {
                        if (config.apiKey.isNotBlank()) {
                            requestBuilder.header("x-api-key", config.apiKey.trim())
                        }
                        requestBuilder.header("anthropic-version", "2023-06-01")
                    }
                    config.apiKey.isNotBlank() ->
                        requestBuilder.header("Authorization", "Bearer ${config.apiKey.trim()}")
                }
                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(
                            extractErrorMessage(body)
                                ?: "Model list request failed with HTTP ${response.code}."
                        )
                    }
                    val root = JSONObject(body)
                    val models = when {
                        isGemini -> {
                            val items = root.optJSONArray("models") ?: JSONArray()
                            buildList {
                                for (i in 0 until items.length()) {
                                    val item = items.optJSONObject(i) ?: continue
                                    val methods = item.optJSONArray("supportedGenerationMethods")
                                    val supportsVisionGeneration = methods == null ||
                                        (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                                    val name = item.optString("name").removePrefix("models/").trim()
                                    if (name.isNotBlank() && supportsVisionGeneration) add(name)
                                }
                            }
                        }
                        else -> {
                            val items = root.optJSONArray("data")
                                ?: root.optJSONArray("models")
                                ?: root.optJSONArray("items")
                                ?: JSONArray()
                            buildList {
                                for (i in 0 until items.length()) {
                                    val item = items.optJSONObject(i) ?: continue
                                    val id = item.optString("id").ifBlank {
                                        item.optString("name").ifBlank { item.optString("model") }
                                    }.trim()
                                    if (id.isNotBlank()) add(id)
                                }
                            }
                        }
                    }.distinct().sorted()
                    if (models.isEmpty()) {
                        error("The endpoint responded, but returned no models. Check that the Base URL points to the provider API root.")
                    }
                    models
                }
            }
        }

    suspend fun testEndpointCapabilities(config: AiConfig): Result<EndpointCapabilityReport> =
        withContext(Dispatchers.IO) {
            runCatching {
                validateCredentials(config)
                val adapter = selectAdapter(config.normalizedBaseUrl)

                val textStart = System.currentTimeMillis()
                val textCheck = runCatching {
                    val reply = executePrompt(
                        adapter = adapter,
                        prompt = "Reply with OK.",
                        jpegBase64 = null,
                        config = config,
                        expectJson = false
                    ).trim()
                    val latency = (System.currentTimeMillis() - textStart).coerceAtLeast(1L)
                    if (reply.isBlank()) error("Empty text response")
                    CapabilityCheckResult(
                        supported = true,
                        latencyMs = latency,
                        detail = "${latency}ms · ${reply.take(36)}"
                    )
                }.getOrElse { err ->
                    val latency = (System.currentTimeMillis() - textStart).coerceAtLeast(1L)
                    CapabilityCheckResult(
                        supported = false,
                        latencyMs = latency,
                        detail = err.message ?: "Text check failed"
                    )
                }

                val probeJpegBase64 = createVisionProbeJpegBase64()
                val visionStart = System.currentTimeMillis()
                val visionCheck = runCatching {
                    val reply = executePrompt(
                        adapter = adapter,
                        prompt = "What color is this solid image? Reply concisely.",
                        jpegBase64 = probeJpegBase64,
                        config = config,
                        expectJson = false
                    ).trim()
                    val latency = (System.currentTimeMillis() - visionStart).coerceAtLeast(1L)
                    if (reply.isBlank()) error("Empty vision response")
                    CapabilityCheckResult(
                        supported = true,
                        latencyMs = latency,
                        detail = "${latency}ms · ${reply.take(36)}"
                    )
                }.getOrElse { err ->
                    val latency = (System.currentTimeMillis() - visionStart).coerceAtLeast(1L)
                    CapabilityCheckResult(
                        supported = false,
                        latencyMs = latency,
                        detail = err.message ?: "Vision check failed"
                    )
                }

                EndpointCapabilityReport(
                    protocolLabel = adapter.kind.label,
                    text = textCheck,
                    vision = visionCheck
                )
            }
        }

    suspend fun analyzeImage(
        context: Context,
        mediaUri: String,
        config: AiConfig,
        ocrContext: String? = null
    ): Result<AiMediaAnalysis> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.enabled) { "AI features are currently paused." }
            validateCredentials(config)

            val jpegBase64 = encodeImageToJpegBase64(context, mediaUri)
                ?: error("Could not decode image for AI analysis.")

            val prompt = buildAnalysisPrompt(
                template = config.analysisPromptTemplate,
                ocrContext = ocrContext
            )

            val adapter = selectAdapter(config.normalizedBaseUrl)
            val rawResponse = executePrompt(
                adapter = adapter,
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
            require(config.enabled) { "AI features are currently paused." }
            validateCredentials(config)
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

            val adapter = selectAdapter(config.normalizedBaseUrl)
            executePrompt(
                adapter = adapter,
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
            require(config.enabled) { "AI features are currently paused." }
            validateCredentials(config)
            val trimmedQuery = query.trim()
            require(trimmedQuery.isNotEmpty()) { "Enter a search query." }
            if (analyses.isEmpty()) {
                return@runCatching AiSearchMatchResult(
                    matchingUris = emptyList(),
                    summary = "No AI-analyzed photos yet."
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
                        if (item.suggestedCollections.isNotEmpty()) {
                            put("collections", JSONArray(item.suggestedCollections))
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

            val adapter = selectAdapter(config.normalizedBaseUrl)
            val raw = executePrompt(
                adapter = adapter,
                prompt = prompt,
                jpegBase64 = null,
                config = config,
                expectJson = true
            )
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

    private fun validateCredentials(config: AiConfig) {
        require(config.normalizedBaseUrl.isNotBlank()) { "Base URL is required." }
        require(config.apiKey.isNotBlank() || isOllamaEndpoint(config.baseUrl)) {
            "API key is required unless this is an Ollama endpoint."
        }
        require(config.modelId.isNotBlank()) { "Model ID is required." }
    }

    internal fun selectAdapter(rawBaseUrl: String): ProtocolAdapter {
        val base = rawBaseUrl.trim().trimEnd('/')
        val lower = base.lowercase()
        val host = runCatching { URI(base).host?.lowercase().orEmpty() }.getOrDefault("")
        val path = runCatching { URI(base).path?.lowercase().orEmpty() }.getOrDefault("")

        return when {
            // Explicit OpenAI compatibility endpoints (including Gemini's /v1beta/openai) always use common client
            path.contains("/openai") || path.endsWith("/chat/completions") -> openAiAdapter
            // Native Gemini REST endpoints
            path.contains(":generatecontent") ||
                (host.contains("generativelanguage.googleapis.com") && !path.contains("/openai")) ||
                (path.contains("/v1beta/models") || path.contains("/v1/models")) && !path.contains("/chat") ->
                geminiNativeAdapter
            // Native Anthropic Messages endpoints
            host.contains("api.anthropic.com") ||
                path.endsWith("/v1/messages") ||
                (path.endsWith("/messages") && !path.contains("/chat")) ->
                anthropicNativeAdapter
            // Default: unified OpenAI-compatible protocol
            else -> openAiAdapter
        }
    }

    private fun executePrompt(
        adapter: ProtocolAdapter,
        prompt: String,
        jpegBase64: String?,
        config: AiConfig,
        expectJson: Boolean
    ): String {
        return adapter.execute(
            httpClient = httpClient,
            prompt = prompt,
            jpegBase64 = jpegBase64,
            config = config,
            expectJson = expectJson
        )
    }

    internal interface ProtocolAdapter {
        val kind: ProtocolAdapterKind
        fun execute(
            httpClient: OkHttpClient,
            prompt: String,
            jpegBase64: String?,
            config: AiConfig,
            expectJson: Boolean
        ): String
    }

    private val openAiAdapter = object : ProtocolAdapter {
        override val kind = ProtocolAdapterKind.OPENAI_COMPATIBLE

        override fun execute(
            httpClient: OkHttpClient,
            prompt: String,
            jpegBase64: String?,
            config: AiConfig,
            expectJson: Boolean
        ): String {
            val base = config.normalizedBaseUrl
            val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"

            fun runAttempt(includeJsonFormat: Boolean): Pair<Int, String> {
                val contentValue: Any = if (jpegBase64 != null) {
                    JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put(
                                "image_url",
                                JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64")
                            )
                        })
                    }
                } else {
                    prompt
                }

                val payload = JSONObject().apply {
                    put("model", config.modelId)
                    put(
                        "messages",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", contentValue)
                            }
                        )
                    )
                    put("temperature", 0.2)
                    if (includeJsonFormat) {
                        put("response_format", JSONObject().put("type", "json_object"))
                    }
                }

                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                if (config.apiKey.isNotBlank()) {
                    requestBuilder.header("Authorization", "Bearer ${config.apiKey.trim()}")
                }
                val request = requestBuilder
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    return response.code to response.body?.string().orEmpty()
                }
            }

            var (code, bodyStr) = runAttempt(includeJsonFormat = expectJson)
            if (code in listOf(400, 422) && expectJson) {
                // Retry once without response_format in case the endpoint/model rejects json_object
                val retry = runAttempt(includeJsonFormat = false)
                code = retry.first
                bodyStr = retry.second
            }

            if (code !in 200..299) {
                val apiMsg = extractErrorMessage(bodyStr)
                error(apiMsg ?: "API error ($code)")
            }

            val root = JSONObject(bodyStr)
            val messageObj = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
            val rawContent = messageObj?.opt("content")
            val text = when (rawContent) {
                is String -> rawContent.trim()
                is JSONArray -> buildString {
                    for (i in 0 until rawContent.length()) {
                        val partText = rawContent.optJSONObject(i)?.optString("text").orEmpty()
                        if (partText.isNotEmpty()) append(partText)
                    }
                }.trim()
                else -> ""
            }
            if (text.isEmpty()) error("Empty response from endpoint")
            return text
        }
    }

    private val geminiNativeAdapter = object : ProtocolAdapter {
        override val kind = ProtocolAdapterKind.GEMINI_NATIVE

        override fun execute(
            httpClient: OkHttpClient,
            prompt: String,
            jpegBase64: String?,
            config: AiConfig,
            expectJson: Boolean
        ): String {
            val base = config.normalizedBaseUrl
            val model = config.modelId.removePrefix("models/")
            val url = when {
                base.endsWith(":generateContent", ignoreCase = true) -> base
                base.endsWith("/models") -> "$base/$model:generateContent"
                base.endsWith("/v1beta") || base.endsWith("/v1") -> "$base/models/$model:generateContent"
                else -> "$base/v1beta/models/$model:generateContent"
            }

            fun runAttempt(includeJsonMime: Boolean): Pair<Int, String> {
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
                            if (includeJsonMime) {
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
                    return response.code to response.body?.string().orEmpty()
                }
            }

            var (code, bodyStr) = runAttempt(includeJsonMime = expectJson)
            if (code == 400 && expectJson) {
                val retry = runAttempt(includeJsonMime = false)
                code = retry.first
                bodyStr = retry.second
            }

            if (code !in 200..299) {
                val apiMsg = extractErrorMessage(bodyStr)
                error(apiMsg ?: "Native Gemini API error ($code)")
            }

            val root = JSONObject(bodyStr)
            val firstCandidate = root.optJSONArray("candidates")?.optJSONObject(0)
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
                    else "Empty response from Gemini native endpoint"
                )
            }
            return resultText
        }
    }

    private val anthropicNativeAdapter = object : ProtocolAdapter {
        override val kind = ProtocolAdapterKind.ANTHROPIC_NATIVE

        override fun execute(
            httpClient: OkHttpClient,
            prompt: String,
            jpegBase64: String?,
            config: AiConfig,
            expectJson: Boolean
        ): String {
            val base = config.normalizedBaseUrl
            val url = when {
                base.endsWith("/messages") -> base
                base.endsWith("/v1") -> "$base/messages"
                else -> "$base/v1/messages"
            }

            val contentArr = JSONArray().apply {
                if (jpegBase64 != null) {
                    put(
                        JSONObject().apply {
                            put("type", "image")
                            put(
                                "source",
                                JSONObject().apply {
                                    put("type", "base64")
                                    put("media_type", "image/jpeg")
                                    put("data", jpegBase64)
                                }
                            )
                        }
                    )
                }
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", prompt)
                })
            }

            val payload = JSONObject().apply {
                put("model", config.modelId)
                put("max_tokens", 1024)
                put("temperature", 0.2)
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", contentArr)
                        }
                    )
                )
            }

            val request = Request.Builder()
                .url(url)
                .header("x-api-key", config.apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val apiMsg = extractErrorMessage(bodyStr)
                    error(apiMsg ?: "Anthropic API error (${response.code})")
                }
                val root = JSONObject(bodyStr)
                val blocks = root.optJSONArray("content")
                val sb = StringBuilder()
                if (blocks != null) {
                    for (i in 0 until blocks.length()) {
                        val block = blocks.optJSONObject(i)
                        if (block?.optString("type") == "text") {
                            sb.append(block.optString("text"))
                        }
                    }
                }
                val text = sb.toString().trim()
                if (text.isEmpty()) error("Empty response from Anthropic endpoint")
                return text
            }
        }
    }

    private fun extractErrorMessage(bodyStr: String): String? {
        return runCatching {
            val json = JSONObject(bodyStr)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
                ?: json.optString("detail").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun buildAnalysisPrompt(
        template: String,
        ocrContext: String?
    ): String {
        val ocrText = ocrContext.orEmpty().take(1500)
        val editablePrompt = template.ifBlank { AiConfig.DEFAULT_ANALYSIS_PROMPT_TEMPLATE }
            .replace(
                Regex("""(?m)^[ \t]*"key_details"\s*:\s*[^\r\n]*,?[ \t]*\r?\n?"""),
                ""
            )
            .replace(Regex(""",([ \t]*\r?\n[ \t]*})""")) { it.groupValues[1] }
            .replace("{{existing_collections}}", "")
            .replace(Regex("(?im)^Existing collections:\\s*$"), "")
            .replace(
                "- Choose a clean collection name such as Receipts, Documents, Code, Chats, Notes, Travel, Food, Shopping, Design, Memes, Nature, People, or Devices.",
                ""
            )
            .replace("- Prefer reusing one of the existing collections below when it fits well.", "")
            .replace(
                "\"collection\": \"Single concise collection name\",",
                "\"collections\": [\"granular, specific collection name\"],"
            )
            .replace("{{ocr_context}}", ocrText)

        return buildString {
            append(editablePrompt.trim())
            append("\n\nMandatory output requirements:\n")
            append("Return the collections key as an array of strings, never as a single collection string. ")
            append("Generate every distinct, genuinely relevant collection for this image with no fixed maximum. ")
            append("Use granular, specific, context-rich names rather than broad catch-all categories. ")
            append("Generate names independently; no existing collection list or preset examples are provided. ")
            append("Format every tag in Title Case, preserving standard acronyms and established brand casing. ")
            append("Format each collection name in Title Case, preserving standard acronyms and established brand casing. ")
            append("Keep tags precise and grounded in visible content and OCR. ")
            append("Use the description for the complete concise visual summary, including important legible facts when relevant. ")
            append("Do not return a separate details field; keep useful facts in the description.")
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
        val collections = parseCollectionNames(obj)
        val tagsArr = obj.optJSONArray("tags") ?: JSONArray()
        val tags = MetadataNameNormalizer.normalizeTags(
            (0 until tagsArr.length()).map { tagsArr.optString(it, "") }
        )
        return AiMediaAnalysis(
            mediaUri = mediaUri,
            title = title,
            description = description,
            tags = tags,
            suggestedCollection = collections.firstOrNull().orEmpty(),
            suggestedCollections = collections,
            modelUsed = modelUsed,
            analyzedAtMillis = System.currentTimeMillis(),
            status = AiMediaAnalysis.STATUS_COMPLETED
        )
    }

    private fun parseCollectionNames(obj: JSONObject): List<String> {
        val rawCollections = obj.opt("collections") ?: obj.opt("collection")
        val candidates = when (rawCollections) {
            is JSONArray -> buildList {
                for (index in 0 until rawCollections.length()) {
                    add(rawCollections.optString(index, ""))
                }
            }
            is String -> listOf(rawCollections)
            else -> emptyList()
        }
        return MetadataNameNormalizer.normalizeCollectionNames(candidates)
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

    private fun createVisionProbeJpegBase64(): String {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(32, 96, 192))
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
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
