package com.amresalehin.emregallery.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.data.PhotoMapLocationCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

private val AI_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

data class ParsedAiEnrichment(
    val description: String,
    val tags: Map<String, List<String>>
)

data class AiMediaMetadata(
    val mediaUri: String,
    val description: String,
    val tags: Map<String, List<String>>,
    val providerMode: String,
    val model: String,
    val sourceSignature: String,
    val generatedAtMillis: Long
) {
    companion object {
        val TAG_CATEGORIES = listOf(
            "people",
            "objects",
            "activities",
            "locations",
            "events",
            "scenes",
            "visual_attributes"
        )

        fun signature(item: MediaItem): String =
            listOf(item.uri, item.sizeBytes, item.dateTakenMillis, item.dateAddedSeconds, item.durationMillis)
                .joinToString("|")
    }
}

/**
 * Durable AI metadata cache. Descriptions and tags are stored separately from user-authored
 * tags and from EXIF metadata, so AI can never overwrite the user's original media metadata.
 */
class AiMetadataStore(context: Context) :
    android.database.sqlite.SQLiteOpenHelper(context.applicationContext, "ai_media_metadata.db", null, 1) {

    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE ai_media_metadata (
                media_uri TEXT PRIMARY KEY NOT NULL,
                description TEXT NOT NULL,
                tags_json TEXT NOT NULL,
                provider_mode TEXT NOT NULL,
                model TEXT NOT NULL,
                source_signature TEXT NOT NULL,
                generated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_ai_metadata_generated ON ai_media_metadata(generated_at)")
    }

    override fun onUpgrade(
        db: android.database.sqlite.SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // Version 1 is the first schema. Future upgrades must preserve cached descriptions.
    }

    @Synchronized
    fun get(mediaUri: String): AiMediaMetadata? {
        return readableDatabase.query(
            "ai_media_metadata",
            arrayOf("media_uri", "description", "tags_json", "provider_mode", "model", "source_signature", "generated_at"),
            "media_uri=?",
            arrayOf(mediaUri),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val tags = runCatching {
                val json = JSONObject(cursor.getString(2))
                AiMediaMetadata.TAG_CATEGORIES.associateWith { category ->
                    val array = json.optJSONArray(category) ?: JSONArray()
                    buildList {
                        for (index in 0 until array.length()) add(array.optString(index))
                    }
                }.filterValues { it.isNotEmpty() }
            }.getOrDefault(emptyMap())
            AiMediaMetadata(
                mediaUri = cursor.getString(0),
                description = cursor.getString(1),
                tags = tags,
                providerMode = cursor.getString(3),
                model = cursor.getString(4),
                sourceSignature = cursor.getString(5),
                generatedAtMillis = cursor.getLong(6)
            )
        }
    }

    @Synchronized
    fun hasCurrentResult(item: MediaItem): Boolean =
        get(item.uri)?.sourceSignature == AiMediaMetadata.signature(item)

    @Synchronized
    fun save(metadata: AiMediaMetadata) {
        val tagsJson = JSONObject()
        metadata.tags.forEach { (category, tags) -> tagsJson.put(category, JSONArray(tags)) }
        val values = android.content.ContentValues().apply {
            put("media_uri", metadata.mediaUri)
            put("description", metadata.description)
            put("tags_json", tagsJson.toString())
            put("provider_mode", metadata.providerMode)
            put("model", metadata.model)
            put("source_signature", metadata.sourceSignature)
            put("generated_at", metadata.generatedAtMillis)
        }
        writableDatabase.insertWithOnConflict(
            "ai_media_metadata",
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
        AiMetadataEvents.notifyUpdated(metadata.mediaUri)
    }

    @Synchronized
    fun count(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM ai_media_metadata",
        null
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    @Synchronized
    fun delete(mediaUri: String): Int =
        writableDatabase.delete("ai_media_metadata", "media_uri=?", arrayOf(mediaUri))

    @Synchronized
    fun clear(): Int = writableDatabase.delete("ai_media_metadata", null, null)
}

object AiMetadataEvents {
    private val updatesFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val updates = updatesFlow.asSharedFlow()
    fun notifyUpdated(uri: String) { updatesFlow.tryEmit(uri) }
}

/**
 * Parses structured JSON independently of HTTP. Handles fenced JSON and removes duplicate,
 * empty, or excessively long tags before they reach the database or UI.
 */
object AiResponseParser {
    private val categoryAliases = mapOf(
        "visual attributes" to "visual_attributes",
        "visual_attributes" to "visual_attributes",
        "attributes" to "visual_attributes",
        "people" to "people",
        "persons" to "people",
        "objects" to "objects",
        "activities" to "activities",
        "actions" to "activities",
        "locations" to "locations",
        "places" to "locations",
        "events" to "events",
        "scenes" to "scenes"
    )

    fun parse(raw: String): ParsedAiEnrichment {
        val jsonText = extractJsonObject(raw)
        val root = try {
            JSONObject(jsonText)
        } catch (error: Exception) {
            throw IOException("The AI provider returned invalid JSON.", error)
        }
        val description = root.optString("description").trim().take(700)
        if (description.isBlank()) throw IOException("The AI provider returned an empty description.")

        val tagsObject = root.optJSONObject("tags") ?: root
        val tags = linkedMapOf<String, List<String>>()
        for (category in AiMediaMetadata.TAG_CATEGORIES) {
            val array = tagsObject.optJSONArray(category) ?: JSONArray()
            val unique = linkedMapOf<String, String>()
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                    .replace(Regex("\\s+"), " ")
                    .trim(',', ';', '.', ' ')
                if (value.isNotBlank() && value.length <= 64) {
                    unique.putIfAbsent(value.lowercase(), value)
                }
                if (unique.size >= 12) break
            }
            if (unique.isNotEmpty()) tags[category] = unique.values.toList()
        }

        val flatTags = root.optJSONArray("tag_list")
        if (flatTags != null) {
            for (index in 0 until flatTags.length()) {
                val entry = flatTags.optJSONObject(index) ?: continue
                val categoryKey = entry.optString("category").trim().lowercase()
                val category = categoryAliases[categoryKey] ?: continue
                val value = entry.optString("name").trim()
                if (value.isBlank() || value.length > 64) continue
                tags[category] = (tags[category].orEmpty() + value)
                    .distinctBy { it.lowercase() }
                    .take(12)
            }
        }
        return ParsedAiEnrichment(description, tags)
    }

    private fun extractJsonObject(raw: String): String {
        val fence = 96.toChar().toString().repeat(3)
        val cleaned = raw.trim()
            .removePrefix(fence + "json")
            .removePrefix(fence + "JSON")
            .removePrefix(fence)
            .removeSuffix(fence)
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) throw IOException("The AI provider did not return a JSON object.")
        return cleaned.substring(start, end + 1)
    }
}

/**
 * OpenAI Chat Completions-compatible vision client. BYOK providers and local MNN / llama.cpp
 * servers use the same transport. Local models remain on the device when the endpoint is
 * loopback; this class never uploads media to a cloud service unless a cloud endpoint is chosen.
 */
class AiVisionRepository(private val context: Context) {
    private val localRuntime = LocalVisionRuntime(context.applicationContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(210, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun testConnection(settings: AiSettings): List<String> = withContext(Dispatchers.IO) {
        validateEndpoint(settings)
        val request = Request.Builder()
            .url(modelsUrl(settings.baseUrl))
            .get()
            .apply { applyAuthorization(settings) }
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Connection failed (HTTP ${response.code}): ${extractError(body)}")
            }
            val array = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull()
            buildList {
                if (array != null) {
                    for (index in 0 until array.length()) {
                        val id = array.optJSONObject(index)?.optString("id").orEmpty().trim()
                        if (id.isNotBlank()) add(id)
                    }
                }
            }.distinct()
        }
    }

    suspend fun enrich(item: MediaItem, settings: AiSettings): AiMediaMetadata = withContext(Dispatchers.IO) {
        val localSelection = if (settings.providerMode.isOnDevice) {
            AiModelStorage.resolveSelection(context)
                ?: throw IOException("Select and install a GGUF model with its matching vision projector in AI & Models.")
        } else {
            validateSettings(settings)
            null
        }
        val frames = prepareFrames(item)
        if (frames.isEmpty()) throw IOException("Eye could not decode this media item.")

        val gpsPoint = runCatching {
            PhotoMapLocationCache.extractLocation(context, item.uri, isVideo = item.isVideo)
        }.getOrNull()

        val prompt = buildString {
            append(
                """
                Describe this ${if (item.isVideo) "video" else "photo"} for a personal media library.
                Return ONLY one valid JSON object, with no markdown, using exactly this structure:
                {
                  "description": "A natural, concise, factual description.",
                  "tags": {
                    "people": ["broad visible people-related tags; never identify a person by name"],
                    "objects": ["visible objects"],
                    "activities": ["visible actions or activities"],
                    "locations": ["clearly identifiable place or setting; never guess"],
                    "events": ["recognizable event type, only when supported by evidence"],
                    "scenes": ["scene and environment"],
                    "visual_attributes": ["colors, composition, lighting, weather, and other visible attributes"]
                  }
                }
                Rules:
                - Only describe details actually visible in the supplied image or video frames.
                - Do not infer identities, names, relationships, sensitive traits, or exact ages.
                - Do not invent a city, venue, landmark, or event. Use an empty array when uncertain.
                - Use short, useful tags, not sentences. Omit empty categories.
                - For video, describe the overall clip across the supplied frames, not just one frame.
                - Keep the description to one or two natural sentences.
                """.trimIndent()
            )
            if (gpsPoint != null) {
                append('\n')
                append("Trusted EXIF/video GPS coordinates are available: ")
                append(String.format(java.util.Locale.US, "%.5f, %.5f", gpsPoint.latitude, gpsPoint.longitude))
                append(". Do not convert these coordinates into a place name unless you can verify it.")
            }
        }

        val responseText = if (localSelection != null) {
            localRuntime.generate(localSelection, frames, prompt)
        } else {
            val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
            frames.forEach { bytes ->
                val dataUrl = "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                content.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", dataUrl).put("detail", "auto"))
                )
            }

            val requestJson = JSONObject()
                .put("model", settings.model.trim())
                .put("messages", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", content)
                ))
                .put("temperature", 0.2)
                .put("max_tokens", 700)

            val request = Request.Builder()
                .url(completionsUrl(settings.baseUrl))
                .post(requestJson.toString().toRequestBody(AI_JSON_MEDIA_TYPE))
                .apply { applyAuthorization(settings) }
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("AI request failed (HTTP ${response.code}): ${extractError(body)}")
                }
                val root = try {
                    JSONObject(body)
                } catch (error: Exception) {
                    throw IOException("The AI endpoint returned an unreadable response.", error)
                }
                val message = root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?: throw IOException("The AI endpoint did not return a chat completion.")
                extractMessageText(message.opt("content"))
            }
        }

        val parsed = AiResponseParser.parse(responseText)
        AiMediaMetadata(
            mediaUri = item.uri,
            description = parsed.description,
            tags = parsed.tags,
            providerMode = settings.providerMode.id,
            model = localSelection?.displayName ?: settings.model.trim(),
            sourceSignature = AiMediaMetadata.signature(item),
            generatedAtMillis = System.currentTimeMillis()
        )
    }

    fun close() {
        localRuntime.close()
    }

    fun cancelLocalInference() {
        localRuntime.cancel()
    }

    private fun validateEndpoint(settings: AiSettings) {
        if (settings.providerMode.isOnDevice) return
        if (settings.baseUrl.isBlank()) throw IOException("Enter an AI endpoint URL.")
        if (!settings.providerMode.isLocal && settings.apiKey.isBlank()) {
            throw IOException("Enter your API key, or select a local model provider.")
        }
        val parsed = normalizeBaseUrl(settings.baseUrl).toHttpUrlOrNull()
            ?: throw IOException("The AI endpoint URL is invalid.")
        if (!parsed.isHttps && !isLoopback(parsed.host)) {
            throw IOException("Use HTTPS for remote endpoints. HTTP is enabled only for localhost.")
        }
    }

    private fun validateSettings(settings: AiSettings) {
        validateEndpoint(settings)
        if (settings.model.isBlank()) throw IOException("Enter or select a vision model name.")
    }

    private fun isLoopback(host: String): Boolean =
        host.equals("localhost", true) || host == "127.0.0.1" || host == "::1"

    private fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    private fun completionsUrl(baseUrl: String): String {
        val root = normalizeBaseUrl(baseUrl)
        return when {
            root.endsWith("/chat/completions", true) -> root
            root.endsWith("/v1", true) -> "$root/chat/completions"
            else -> "$root/v1/chat/completions"
        }
    }

    private fun modelsUrl(baseUrl: String): String {
        val root = normalizeBaseUrl(baseUrl)
        return when {
            root.endsWith("/models", true) -> root
            root.endsWith("/v1", true) -> "$root/models"
            else -> "$root/v1/models"
        }
    }

    private fun Request.Builder.applyAuthorization(settings: AiSettings): Request.Builder {
        if (settings.apiKey.isNotBlank()) header("Authorization", "Bearer ${settings.apiKey.trim()}")
        header("Accept", "application/json")
        return this
    }

    private fun extractMessageText(content: Any?): String {
        if (content is String) return content
        if (content is JSONArray) {
            return buildString {
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
                    if (item.optString("type") == "text") append(item.optString("text"))
                }
            }
        }
        return ""
    }

    private fun extractError(body: String): String {
        val message = runCatching {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message") ?: json.optString("message")
        }.getOrNull().orEmpty()
        return (message.ifBlank { body.take(240).ifBlank { "Unknown provider error." } }).take(320)
    }

    private fun prepareFrames(item: MediaItem): List<ByteArray> =
        if (item.isVideo) loadVideoFrames(item.uri) else listOfNotNull(loadImage(item.uri, 1024))

    private fun loadImage(uriString: String, maxDimension: Int): ByteArray {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: throw IOException("Eye could not open the selected photo.")

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("The selected photo has an unsupported image format.")
        }
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension * 2 ||
            bounds.outHeight / sampleSize > maxDimension * 2
        ) sampleSize *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        var bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("Eye could not decode the selected photo.")

        val orientation = readExifOrientation(uri)
        if (orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED) {
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            }
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (oriented !== bitmap) bitmap.recycle()
            bitmap = oriented
        }
        return bitmapToJpeg(bitmap, maxDimension)
    }

    private fun readExifOrientation(uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    @Suppress("DEPRECATION")
    private fun loadVideoFrames(uriString: String): List<ByteArray> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(uriString))
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val sampleTimes = when {
                durationMs >= 6000L -> listOf(0.1, 0.5, 0.9).map { (durationMs * it).toLong() }
                durationMs >= 1500L -> listOf(0.15, 0.85).map { (durationMs * it).toLong() }
                else -> listOf(0L)
            }
            val output = mutableListOf<ByteArray>()
            for (timeMs in sampleTimes.distinct()) {
                val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        768,
                        768
                    )
                } else {
                    retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } ?: continue
                output += bitmapToJpeg(frame, 768)
            }
            return output
        } catch (error: Exception) {
            throw IOException("Eye could not extract preview frames from this video.", error)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun bitmapToJpeg(source: Bitmap, maxDimension: Int): ByteArray {
        var bitmap = source
        val largest = maxOf(source.width, source.height)
        if (largest > maxDimension) {
            val scale = maxDimension.toFloat() / largest.toFloat()
            val resized = Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (resized !== source) source.recycle()
            bitmap = resized
        }
        return try {
            ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
                    throw IOException("Eye could not encode a preview image.")
                }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
