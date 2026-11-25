package com.amresalehin.emregallery.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.amresalehin.emregallery.data.MetadataNameNormalizer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray

data class AiMediaAnalysis(
    val mediaUri: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    val suggestedCollection: String,
    val keyDetails: String = "",
    val modelUsed: String = "",
    val analyzedAtMillis: Long = System.currentTimeMillis(),
    val status: String = STATUS_COMPLETED,
    val errorMessage: String? = null,
    val suggestedCollections: List<String> = suggestedCollection
        .takeIf { it.isNotBlank() }
        ?.let { listOf(it) }
        ?: emptyList()
) {
    val isCompleted: Boolean get() = status == STATUS_COMPLETED
    val isFailed: Boolean get() = status == STATUS_FAILED

    companion object {
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
    }
}

data class AiCollectionSummary(
    val name: String,
    val mediaUris: List<String>
) {
    val count: Int get() = mediaUris.size
}

object AiStoreEvents {
    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val updates: SharedFlow<String> = _updates.asSharedFlow()

    fun notifyMediaUpdated(uri: String = "") {
        _updates.tryEmit(uri)
    }
}

/**
 * Persistent SQLite store for Shots Studio-inspired AI analysis metadata
 * (titles, descriptions, tags, suggested collections, and extracted key details).
 */
class AiMetadataStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ANALYSIS (
                media_uri TEXT PRIMARY KEY,
                title TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT '',
                tags_json TEXT NOT NULL DEFAULT '[]',
                suggested_collection TEXT NOT NULL DEFAULT '',
                suggested_collections_json TEXT NOT NULL DEFAULT '[]',
                key_details TEXT NOT NULL DEFAULT '',
                model_used TEXT NOT NULL DEFAULT '',
                analyzed_at_millis INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'completed',
                error_message TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_status ON $TABLE_ANALYSIS(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_collection ON $TABLE_ANALYSIS(suggested_collection)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE $TABLE_ANALYSIS ADD COLUMN suggested_collections_json TEXT NOT NULL DEFAULT '[]'"
            )
            val legacyRows = mutableListOf<Pair<String, String>>()
            db.query(
                TABLE_ANALYSIS,
                arrayOf("media_uri", "suggested_collection"),
                null,
                null,
                null,
                null,
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    legacyRows += cursor.getString(0) to cursor.getString(1).orEmpty().trim()
                }
            }
            legacyRows.forEach { (uri, collection) ->
                val collectionsJson = JSONArray().apply {
                    if (collection.isNotBlank()) put(collection)
                }.toString()
                db.update(
                    TABLE_ANALYSIS,
                    ContentValues().apply { put("suggested_collections_json", collectionsJson) },
                    "media_uri=?",
                    arrayOf(uri)
                )
            }
        }
    }

    @Synchronized
    fun saveAnalysis(analysis: AiMediaAnalysis) {
        val tagsArr = JSONArray().apply {
            MetadataNameNormalizer.normalizeTags(analysis.tags).forEach(::put)
        }
        val collections = normalizeCollectionNames(
            analysis.suggestedCollections.ifEmpty { listOf(analysis.suggestedCollection) }
        )
        val collectionsArr = JSONArray().apply { collections.forEach { put(it) } }
        val cv = ContentValues().apply {
            put("media_uri", analysis.mediaUri)
            put("title", analysis.title.trim())
            put("description", analysis.description.trim())
            put("tags_json", tagsArr.toString())
            put("suggested_collection", collections.firstOrNull().orEmpty())
            put("suggested_collections_json", collectionsArr.toString())
            put("key_details", analysis.keyDetails.trim())
            put("model_used", analysis.modelUsed.trim())
            put("analyzed_at_millis", analysis.analyzedAtMillis)
            put("status", analysis.status)
            put("error_message", analysis.errorMessage)
        }
        writableDatabase.insertWithOnConflict(TABLE_ANALYSIS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        AiStoreEvents.notifyMediaUpdated(analysis.mediaUri)
    }

    @Synchronized
    fun markFailed(uri: String, modelUsed: String, errorMessage: String) {
        val existing = getAnalysis(uri)
        if (existing != null && existing.isCompleted) {
            return
        }
        val cv = ContentValues().apply {
            put("media_uri", uri)
            put("title", "")
            put("description", "")
            put("tags_json", "[]")
            put("suggested_collection", "")
            put("suggested_collections_json", "[]")
            put("key_details", "")
            put("model_used", modelUsed)
            put("analyzed_at_millis", System.currentTimeMillis())
            put("status", AiMediaAnalysis.STATUS_FAILED)
            put("error_message", errorMessage.take(500))
        }
        writableDatabase.insertWithOnConflict(TABLE_ANALYSIS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        AiStoreEvents.notifyMediaUpdated(uri)
    }

    @Synchronized
    fun getAnalysis(uri: String): AiMediaAnalysis? {
        readableDatabase.rawQuery(
            "SELECT media_uri, title, description, tags_json, suggested_collection, suggested_collections_json, key_details, model_used, analyzed_at_millis, status, error_message FROM $TABLE_ANALYSIS WHERE media_uri=?",
            arrayOf(uri)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return readCursorRow(cursor)
        }
    }

    @Synchronized
    fun getAllCompleted(): Map<String, AiMediaAnalysis> {
        val map = LinkedHashMap<String, AiMediaAnalysis>()
        readableDatabase.rawQuery(
            "SELECT media_uri, title, description, tags_json, suggested_collection, suggested_collections_json, key_details, model_used, analyzed_at_millis, status, error_message FROM $TABLE_ANALYSIS WHERE status=?",
            arrayOf(AiMediaAnalysis.STATUS_COMPLETED)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val item = readCursorRow(cursor)
                map[item.mediaUri] = item
            }
        }
        return map
    }

    @Synchronized
    fun getCompletedCount(uris: Collection<String>): Int {
        if (uris.isEmpty()) return 0
        val completed = getAllCompleted().keys
        return uris.count { it in completed }
    }

    @Synchronized
    fun getTotalCompletedCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_ANALYSIS WHERE status=?",
            arrayOf(AiMediaAnalysis.STATUS_COMPLETED)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    @Synchronized
    fun getFailedItems(uris: Collection<String>? = null): List<Pair<String, String>> {
        val uriSet = uris?.toSet()
        val list = mutableListOf<Pair<String, String>>()
        readableDatabase.rawQuery(
            "SELECT media_uri, error_message FROM $TABLE_ANALYSIS WHERE status=?",
            arrayOf(AiMediaAnalysis.STATUS_FAILED)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val uri = cursor.getString(0)
                if (uriSet == null || uri in uriSet) {
                    val err = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: "Analysis failed"
                    list.add(uri to err)
                }
            }
        }
        return list
    }

    @Synchronized
    fun getFailedUris(): Set<String> = getFailedItems().map { it.first }.toSet()

    @Synchronized
    fun getCompletedUris(): Set<String> {
        val set = HashSet<String>()
        readableDatabase.rawQuery(
            "SELECT media_uri FROM $TABLE_ANALYSIS WHERE status=?",
            arrayOf(AiMediaAnalysis.STATUS_COMPLETED)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                set.add(cursor.getString(0))
            }
        }
        return set
    }

    @Synchronized
    fun getCollectionSummaries(allowedUris: Set<String>? = null): List<AiCollectionSummary> {
        val grouped = LinkedHashMap<String, MutableList<String>>()
        readableDatabase.rawQuery(
            "SELECT media_uri, suggested_collection, suggested_collections_json FROM $TABLE_ANALYSIS WHERE status=? ORDER BY analyzed_at_millis DESC",
            arrayOf(AiMediaAnalysis.STATUS_COMPLETED)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val uri = cursor.getString(0)
                if (allowedUris != null && uri !in allowedUris) continue
                val collections = decodeCollectionNames(cursor.getString(2), cursor.getString(1))
                collections.forEach { collection ->
                    val uris = grouped.getOrPut(collection) { mutableListOf() }
                    if (uri !in uris) uris.add(uri)
                }
            }
        }
        return grouped.entries
            .map { (name, uris) -> AiCollectionSummary(name = name, mediaUris = uris) }
            .sortedByDescending { it.count }
    }

    @Synchronized
    fun deleteAnalysis(uri: String) {
        writableDatabase.delete(TABLE_ANALYSIS, "media_uri=?", arrayOf(uri))
        AiStoreEvents.notifyMediaUpdated(uri)
    }

    @Synchronized
    fun deleteBatch(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (uri in uris) {
                db.delete(TABLE_ANALYSIS, "media_uri=?", arrayOf(uri))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        AiStoreEvents.notifyMediaUpdated()
    }

    @Synchronized
    fun clearFailed() {
        writableDatabase.delete(TABLE_ANALYSIS, "status=?", arrayOf(AiMediaAnalysis.STATUS_FAILED))
        AiStoreEvents.notifyMediaUpdated()
    }

    @Synchronized
    fun clearForUris(uris: Collection<String>) {
        val distinctUris = uris.distinct()
        if (distinctUris.isEmpty()) return

        val db = writableDatabase
        db.beginTransaction()
        try {
            // Keep each statement below SQLite's bind-parameter limit.
            distinctUris.chunked(900).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                db.delete(
                    TABLE_ANALYSIS,
                    "media_uri IN ($placeholders)",
                    chunk.toTypedArray()
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        AiStoreEvents.notifyMediaUpdated()
    }

    @Synchronized
    fun clearAll() {
        writableDatabase.delete(TABLE_ANALYSIS, null, null)
        AiStoreEvents.notifyMediaUpdated()
    }

    private fun decodeCollectionNames(collectionsJson: String?, legacyCollection: String?): List<String> {
        val decoded = runCatching {
            val array = JSONArray(collectionsJson ?: "[]")
            buildList {
                for (index in 0 until array.length()) {
                    add(array.optString(index, ""))
                }
            }
        }.getOrDefault(emptyList())
        return normalizeCollectionNames(decoded.ifEmpty { listOf(legacyCollection.orEmpty()) })
    }

    private fun normalizeCollectionNames(names: Collection<String>): List<String> =
        MetadataNameNormalizer.normalizeCollectionNames(names)

    private fun readCursorRow(cursor: android.database.Cursor): AiMediaAnalysis {
        val tagsRaw = cursor.getString(3) ?: "[]"
        val tags = MetadataNameNormalizer.normalizeTags(
            runCatching {
                val arr = JSONArray(tagsRaw)
                (0 until arr.length()).map { arr.optString(it, "") }
            }.getOrDefault(emptyList())
        )
        val collections = decodeCollectionNames(cursor.getString(5), cursor.getString(4))
        return AiMediaAnalysis(
            mediaUri = cursor.getString(0),
            title = cursor.getString(1) ?: "",
            description = cursor.getString(2) ?: "",
            tags = tags,
            suggestedCollection = collections.firstOrNull().orEmpty(),
            suggestedCollections = collections,
            keyDetails = cursor.getString(6) ?: "",
            modelUsed = cursor.getString(7) ?: "",
            analyzedAtMillis = cursor.getLong(8),
            status = cursor.getString(9) ?: AiMediaAnalysis.STATUS_COMPLETED,
            errorMessage = cursor.getString(10)
        )
    }

    companion object {
        private const val DB_NAME = "emre_ai_metadata.db"
        private const val DB_VERSION = 2
        private const val TABLE_ANALYSIS = "ai_media_analysis"
    }
}
