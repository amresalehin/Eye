package com.amresalehin.emregallery.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
    val errorMessage: String? = null
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
        onCreate(db)
    }

    @Synchronized
    fun saveAnalysis(analysis: AiMediaAnalysis) {
        val tagsArr = JSONArray()
        analysis.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { tagsArr.put(it) }
        val cv = ContentValues().apply {
            put("media_uri", analysis.mediaUri)
            put("title", analysis.title.trim())
            put("description", analysis.description.trim())
            put("tags_json", tagsArr.toString())
            put("suggested_collection", analysis.suggestedCollection.trim())
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
            "SELECT media_uri, title, description, tags_json, suggested_collection, key_details, model_used, analyzed_at_millis, status, error_message FROM $TABLE_ANALYSIS WHERE media_uri=?",
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
            "SELECT media_uri, title, description, tags_json, suggested_collection, key_details, model_used, analyzed_at_millis, status, error_message FROM $TABLE_ANALYSIS WHERE status=?",
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
            "SELECT media_uri, suggested_collection FROM $TABLE_ANALYSIS WHERE status=? AND suggested_collection != '' ORDER BY analyzed_at_millis DESC",
            arrayOf(AiMediaAnalysis.STATUS_COMPLETED)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val uri = cursor.getString(0)
                if (allowedUris != null && uri !in allowedUris) continue
                val collection = cursor.getString(1).trim()
                if (collection.isNotEmpty()) {
                    grouped.getOrPut(collection) { mutableListOf() }.add(uri)
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

    private fun readCursorRow(cursor: android.database.Cursor): AiMediaAnalysis {
        val tagsRaw = cursor.getString(3) ?: "[]"
        val tags = mutableListOf<String>()
        runCatching {
            val arr = JSONArray(tagsRaw)
            for (i in 0 until arr.length()) {
                val tag = arr.optString(i).trim()
                if (tag.isNotEmpty()) tags.add(tag)
            }
        }
        return AiMediaAnalysis(
            mediaUri = cursor.getString(0),
            title = cursor.getString(1) ?: "",
            description = cursor.getString(2) ?: "",
            tags = tags,
            suggestedCollection = cursor.getString(4) ?: "",
            keyDetails = cursor.getString(5) ?: "",
            modelUsed = cursor.getString(6) ?: "",
            analyzedAtMillis = cursor.getLong(7),
            status = cursor.getString(8) ?: AiMediaAnalysis.STATUS_COMPLETED,
            errorMessage = cursor.getString(9)
        )
    }

    companion object {
        private const val DB_NAME = "emre_ai_metadata.db"
        private const val DB_VERSION = 1
        private const val TABLE_ANALYSIS = "ai_media_analysis"
    }
}
