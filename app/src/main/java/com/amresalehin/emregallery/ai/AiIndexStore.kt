package com.amresalehin.emregallery.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class AiMediaRecord(
    val mediaUri: String,
    val title: String = "",
    val caption: String = "",
    val tags: List<String> = emptyList(),
    val colors: List<DominantColorInfo> = emptyList(),
    val embedding: FloatArray = FloatArray(0),
    val embeddingDim: Int = 0,
    val embeddingModel: String = "",
    val captionProvider: String = "",
    val captionModel: String = "",
    val status: String = STATUS_LOCAL,
    val errorMessage: String? = null,
    val updatedAt: Long = 0L
) {
    val hasRichCaption: Boolean
        get() = caption.isNotBlank() && captionProvider.isNotBlank()

    companion object {
        const val STATUS_LOCAL = "indexed_local"
        const val STATUS_FULL = "indexed_full"
        const val STATUS_FAILED = "failed"
    }
}

data class AiIndexStats(
    val totalIndexed: Int = 0,
    val richCaptionedCount: Int = 0,
    val clipEmbeddedCount: Int = 0,
    val failedCount: Int = 0,
    val distinctTagCount: Int = 0
)

/**
 * Reactive event bus so ViewerDetailsDrawer, SearchScreen, and IndexingStatusScreen
 * stay in sync when a photo is analyzed or batch indexing progresses.
 */
object AiStoreEvents {
    private val _mediaUpdates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val mediaUpdates: SharedFlow<String> = _mediaUpdates.asSharedFlow()

    fun notifyMediaUpdated(mediaUri: String = "") {
        _mediaUpdates.tryEmit(mediaUri)
    }
}

/**
 * WAL-enabled SQLite store for AI titles, descriptions, auto-tags, dominant colors,
 * and semantic/visual embeddings (`ai_index.db`).
 */
class AiIndexStore private constructor(context: Context) {

    private val helper = AiDbHelper(context.applicationContext)

    fun upsertRecord(record: AiMediaRecord) {
        val db = helper.writableDatabase
        val normalizedTags = record.tags
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()

        val tagsJson = JSONArray().apply {
            normalizedTags.forEach { put(it) }
        }.toString()

        val colorsJson = JSONArray().apply {
            record.colors.forEach { c ->
                put(
                    JSONObject().apply {
                        put("hex", c.hex)
                        put("name", c.name)
                        put("ratio", c.ratio.toDouble())
                    }
                )
            }
        }.toString()

        val values = ContentValues().apply {
            put("media_uri", record.mediaUri)
            put("title", record.title.trim())
            put("caption", record.caption.trim())
            put("tags_json", tagsJson)
            put("colors_json", colorsJson)
            if (record.embedding.isNotEmpty()) {
                put("embedding", AiVectorMath.toBytes(record.embedding))
                put("embedding_dim", record.embedding.size)
            } else {
                putNull("embedding")
                put("embedding_dim", 0)
            }
            put("embedding_model", record.embeddingModel)
            put("caption_provider", record.captionProvider)
            put("caption_model", record.captionModel)
            put("status", record.status)
            put("error_message", record.errorMessage)
            put("updated_at", if (record.updatedAt > 0L) record.updatedAt else System.currentTimeMillis())
        }

        db.beginTransaction()
        try {
            db.insertWithOnConflict(TABLE_MEDIA, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.delete(TABLE_TAGS, "media_uri = ?", arrayOf(record.mediaUri))
            for (tag in normalizedTags) {
                val tv = ContentValues().apply {
                    put("media_uri", record.mediaUri)
                    put("tag", tag)
                }
                db.insertWithOnConflict(TABLE_TAGS, null, tv, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        AiStoreEvents.notifyMediaUpdated(record.mediaUri)
    }

    fun getRecord(mediaUri: String): AiMediaRecord? {
        val db = helper.readableDatabase
        db.rawQuery(
            """SELECT media_uri, title, caption, tags_json, colors_json, embedding, embedding_dim,
                      embedding_model, caption_provider, caption_model, status, error_message, updated_at
               FROM $TABLE_MEDIA WHERE media_uri = ? LIMIT 1""".trimIndent(),
            arrayOf(mediaUri)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursorToRecord(cursor, includeEmbedding = true)
        }
    }

    /**
     * Loads lightweight metadata (title, caption, tags, colors, status) for all indexed items
     * without deserializing embedding BLOBs, for fast SearchEngine scoring & discovery.
     */
    fun loadAllMetadataMap(): Map<String, AiMediaRecord> {
        val db = helper.readableDatabase
        val result = HashMap<String, AiMediaRecord>()
        db.rawQuery(
            """SELECT media_uri, title, caption, tags_json, colors_json, NULL as embedding, embedding_dim,
                      embedding_model, caption_provider, caption_model, status, error_message, updated_at
               FROM $TABLE_MEDIA""".trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val rec = cursorToRecord(cursor, includeEmbedding = false)
                result[rec.mediaUri] = rec
            }
        }
        return result
    }

    /**
     * Loads embeddings for a specific embedding model (e.g., CLIP or fallback visual descriptor)
     * to compute cosine similarity for semantic or visually similar search.
     */
    fun loadEmbeddingsForModel(embeddingModel: String): Map<String, FloatArray> {
        val db = helper.readableDatabase
        val result = HashMap<String, FloatArray>()
        db.rawQuery(
            """SELECT media_uri, embedding FROM $TABLE_MEDIA
               WHERE embedding_model = ? AND embedding IS NOT NULL AND embedding_dim > 0""".trimIndent(),
            arrayOf(embeddingModel)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val uri = cursor.getString(0) ?: continue
                val vec = AiVectorMath.fromBytes(cursor.getBlob(1))
                if (vec.isNotEmpty()) {
                    result[uri] = vec
                }
            }
        }
        return result
    }

    /**
     * Finds top visually similar media URIs to [targetUri] using cosine similarity on
     * matching embedding spaces.
     */
    fun findSimilarMedia(targetUri: String, limit: Int = 60, minScore: Float = 0.55f): List<Pair<String, Float>> {
        val target = getRecord(targetUri) ?: return emptyList()
        if (target.embedding.isEmpty() || target.embeddingModel.isBlank()) return emptyList()
        val pool = loadEmbeddingsForModel(target.embeddingModel)
        val scored = ArrayList<Pair<String, Float>>()
        for ((uri, vec) in pool) {
            if (uri == targetUri) continue
            if (vec.size != target.embedding.size) continue
            val sim = AiVectorMath.cosineSimilarity(target.embedding, vec)
            if (sim >= minScore) {
                scored.add(uri to sim)
            }
        }
        scored.sortByDescending { it.second }
        return if (scored.size > limit) scored.subList(0, limit) else scored
    }

    /**
     * Semantic search against all CLIP-embedded images using a precomputed L2-normalized text vector.
     */
    fun searchByTextEmbedding(
        queryEmbedding: FloatArray,
        embeddingModel: String,
        minScore: Float = 0.20f,
        limit: Int = 100
    ): List<Pair<String, Float>> {
        if (queryEmbedding.isEmpty()) return emptyList()
        val pool = loadEmbeddingsForModel(embeddingModel)
        val scored = ArrayList<Pair<String, Float>>()
        for ((uri, vec) in pool) {
            if (vec.size != queryEmbedding.size) continue
            val sim = AiVectorMath.cosineSimilarity(queryEmbedding, vec)
            if (sim >= minScore) {
                scored.add(uri to sim)
            }
        }
        scored.sortByDescending { it.second }
        return if (scored.size > limit) scored.subList(0, limit) else scored
    }

    fun getIndexedUris(): Set<String> {
        val db = helper.readableDatabase
        val set = HashSet<String>()
        db.rawQuery("SELECT media_uri FROM $TABLE_MEDIA WHERE status != ?", arrayOf(AiMediaRecord.STATUS_FAILED)).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.let { set.add(it) }
            }
        }
        return set
    }

    fun getFullyCaptionedUris(): Set<String> {
        val db = helper.readableDatabase
        val set = HashSet<String>()
        db.rawQuery("SELECT media_uri FROM $TABLE_MEDIA WHERE status = ?", arrayOf(AiMediaRecord.STATUS_FULL)).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.let { set.add(it) }
            }
        }
        return set
    }

    fun getStats(validUris: Set<String>? = null): AiIndexStats {
        val db = helper.readableDatabase
        var total = 0
        var rich = 0
        var clip = 0
        var failed = 0
        db.rawQuery(
            "SELECT media_uri, status, embedding_model, caption_provider FROM $TABLE_MEDIA",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val uri = cursor.getString(0) ?: continue
                if (validUris != null && uri !in validUris) continue
                val status = cursor.getString(1).orEmpty()
                val embModel = cursor.getString(2).orEmpty()
                val capProvider = cursor.getString(3).orEmpty()
                if (status == AiMediaRecord.STATUS_FAILED) {
                    failed++
                } else {
                    total++
                    if (status == AiMediaRecord.STATUS_FULL || capProvider.isNotBlank()) rich++
                    if (embModel.startsWith("clip", ignoreCase = true) || embModel.startsWith("mobileclip", ignoreCase = true)) clip++
                }
            }
        }
        var distinctTags = 0
        db.rawQuery("SELECT COUNT(DISTINCT tag) FROM $TABLE_TAGS", null).use { cursor ->
            if (cursor.moveToFirst()) distinctTags = cursor.getInt(0)
        }
        return AiIndexStats(
            totalIndexed = total,
            richCaptionedCount = rich,
            clipEmbeddedCount = clip,
            failedCount = failed,
            distinctTagCount = distinctTags
        )
    }

    fun clearAll() {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_TAGS, null, null)
            db.delete(TABLE_MEDIA, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        AiStoreEvents.notifyMediaUpdated("")
    }

    private fun cursorToRecord(cursor: android.database.Cursor, includeEmbedding: Boolean): AiMediaRecord {
        val uri = cursor.getString(0).orEmpty()
        val title = cursor.getString(1).orEmpty()
        val caption = cursor.getString(2).orEmpty()
        val tagsJson = cursor.getString(3).orEmpty()
        val colorsJson = cursor.getString(4).orEmpty()
        val embedding = if (includeEmbedding && !cursor.isNull(5)) {
            AiVectorMath.fromBytes(cursor.getBlob(5))
        } else {
            FloatArray(0)
        }
        val embeddingDim = cursor.getInt(6)
        val embeddingModel = cursor.getString(7).orEmpty()
        val captionProvider = cursor.getString(8).orEmpty()
        val captionModel = cursor.getString(9).orEmpty()
        val status = cursor.getString(10).orEmpty()
        val errorMessage = if (cursor.isNull(11)) null else cursor.getString(11)
        val updatedAt = cursor.getLong(12)

        return AiMediaRecord(
            mediaUri = uri,
            title = title,
            caption = caption,
            tags = parseTags(tagsJson),
            colors = parseColors(colorsJson),
            embedding = embedding,
            embeddingDim = embeddingDim,
            embeddingModel = embeddingModel,
            captionProvider = captionProvider,
            captionModel = captionModel,
            status = status,
            errorMessage = errorMessage,
            updatedAt = updatedAt
        )
    }

    private fun parseTags(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun parseColors(json: String): List<DominantColorInfo> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                val hex = obj.optString("hex")
                val name = obj.optString("name")
                val ratio = obj.optDouble("ratio", 0.0).toFloat()
                if (hex.isBlank()) null else DominantColorInfo(hex = hex, name = name, ratio = ratio)
            }
        }.getOrDefault(emptyList())
    }

    private class AiDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.enableWriteAheadLogging()
            db.rawQuery("PRAGMA busy_timeout = 5000", null).close()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE $TABLE_MEDIA (
                    media_uri TEXT PRIMARY KEY,
                    title TEXT NOT NULL DEFAULT '',
                    caption TEXT NOT NULL DEFAULT '',
                    tags_json TEXT NOT NULL DEFAULT '[]',
                    colors_json TEXT NOT NULL DEFAULT '[]',
                    embedding BLOB,
                    embedding_dim INTEGER NOT NULL DEFAULT 0,
                    embedding_model TEXT NOT NULL DEFAULT '',
                    caption_provider TEXT NOT NULL DEFAULT '',
                    caption_model TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'indexed_local',
                    error_message TEXT,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX idx_ai_media_model ON $TABLE_MEDIA(embedding_model)")
            db.execSQL("CREATE INDEX idx_ai_media_status ON $TABLE_MEDIA(status)")

            db.execSQL(
                """CREATE TABLE $TABLE_TAGS (
                    media_uri TEXT NOT NULL,
                    tag TEXT NOT NULL,
                    PRIMARY KEY(media_uri, tag)
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX idx_ai_tags_tag ON $TABLE_TAGS(tag)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Future migrations go here.
        }
    }

    companion object {
        private const val DB_NAME = "ai_index.db"
        private const val DB_VERSION = 1
        private const val TABLE_MEDIA = "ai_media_metadata"
        private const val TABLE_TAGS = "ai_tags"

        @Volatile
        private var instance: AiIndexStore? = null

        fun getInstance(context: Context): AiIndexStore {
            return instance ?: synchronized(this) {
                instance ?: AiIndexStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
