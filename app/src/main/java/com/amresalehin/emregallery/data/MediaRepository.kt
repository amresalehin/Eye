package com.amresalehin.emregallery.data

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext

data class MediaLoadResult(
    val items: List<MediaItem>,
    val successfulCollections: Int
)

sealed class MediaLoadEvent {
    data class Batch(val items: List<MediaItem>) : MediaLoadEvent()
    data class Completed(val result: MediaLoadResult) : MediaLoadEvent()
}

class MediaRepository(private val resolver: ContentResolver) {

    suspend fun loadMediaInitial(limitPerCollection: Int = 60): List<MediaItem> =
        loadMediaInitialResult(limitPerCollection).items

    suspend fun loadMedia(): List<MediaItem> = loadMediaResult().items

    suspend fun loadMediaInitialResult(limitPerCollection: Int = 60): MediaLoadResult =
        queryMedia(limitPerCollection)

    suspend fun loadMediaResult(): MediaLoadResult = queryMedia(null)

    /**
     * Emits real MediaStore rows as they are read, instead of waiting for both full cursors.
     * The channel is deliberately bounded so a slow UI cannot accumulate an unbounded list
     * of pending batches in memory.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun loadMediaBatches(
        firstBatchSize: Int = 72,
        batchSize: Int = 1536
    ): Flow<MediaLoadEvent> = channelFlow {
        val firstSize = firstBatchSize.coerceAtLeast(1)
        val laterSize = batchSize.coerceAtLeast(firstSize)
        val result = queryMedia(
            limitPerCollection = null,
            firstBatchSize = firstSize,
            batchSize = laterSize,
            onBatch = { batch -> send(MediaLoadEvent.Batch(batch)) }
        )
        send(MediaLoadEvent.Completed(result))
    }.buffer(Channel.RENDEZVOUS)

    private suspend fun queryMedia(
        limitPerCollection: Int?,
        firstBatchSize: Int? = null,
        batchSize: Int? = null,
        onBatch: (suspend (List<MediaItem>) -> Unit)? = null
    ): MediaLoadResult = withContext(Dispatchers.IO) {
        val rows = ArrayList<MediaItem>(if (limitPerCollection != null) limitPerCollection * 2 else 256)
        var successfulCollections = 0

        // Keep image and video collection permissions independent. Each collection emits
        // batches immediately, so images can appear while the video cursor is still pending.
        if (queryCollection(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                false,
                rows,
                limitPerCollection,
                firstBatchSize,
                batchSize,
                onBatch
            )
        ) {
            successfulCollections++
        }
        if (queryCollection(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                rows,
                limitPerCollection,
                firstBatchSize,
                batchSize,
                onBatch
            )
        ) {
            successfulCollections++
        }

        // Home applies the selected sort order over the accumulated batches and the final list.
        MediaLoadResult(rows, successfulCollections)
    }

    private suspend fun queryCollection(
        collection: Uri,
        video: Boolean,
        rows: MutableList<MediaItem>,
        limit: Int? = null,
        firstBatchSize: Int? = null,
        batchSize: Int? = null,
        onBatch: (suspend (List<MediaItem>) -> Unit)? = null
    ): Boolean {
        val projectionList = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            if (video) add(MediaStore.Video.VideoColumns.DURATION)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.IS_TRASHED)
            }
        }
        val projection = projectionList.toTypedArray()
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        val cursor = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val queryArgs = android.os.Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                    putString(
                        android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                        "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                    )
                    if (limit != null && limit > 0) {
                        putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
                    }
                }
                runCatching { resolver.query(collection, projection, queryArgs, null) }.getOrNull()
                    ?: resolver.query(collection, projection, null, null, sort)
            } else {
                resolver.query(collection, projection, null, null, sort)
            }
        } catch (_: SecurityException) {
            return false
        } catch (_: Exception) {
            return false
        } ?: return false

        return try {
            cursor.use { c ->
                val idCol = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val addedCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val widthCol = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val heightCol = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val pathCol = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val takenCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                val durationCol = if (video) c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1
                val trashedCol = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                } else -1

                var addedCount = 0
                val pendingBatch = if (onBatch != null) {
                    ArrayList<MediaItem>(firstBatchSize ?: 72)
                } else null
                var nextBatchSize = firstBatchSize ?: batchSize ?: Int.MAX_VALUE

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val itemUri = ContentUris.withAppendedId(collection, id)
                    val name = c.getString(nameCol) ?: "Untitled"
                    val mimeType = if (mimeCol >= 0) c.getString(mimeCol) else null
                    val added = if (addedCol >= 0) c.getLong(addedCol) else 0L
                    val taken = if (takenCol >= 0 && !c.isNull(takenCol)) c.getLong(takenCol) else 0L
                    val size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                    val width = if (widthCol >= 0) c.getInt(widthCol) else 0
                    val height = if (heightCol >= 0) c.getInt(heightCol) else 0
                    val path = if (pathCol >= 0) c.getString(pathCol) ?: "" else ""
                    val duration = if (durationCol >= 0 && !c.isNull(durationCol)) c.getLong(durationCol) else 0L
                    val isTrashed = if (trashedCol >= 0 && !c.isNull(trashedCol)) c.getInt(trashedCol) != 0 else false

                    if (!isSupportedMedia(name, mimeType, video)) continue

                    val item = MediaItem(
                        id = id,
                        uri = itemUri.toString(),
                        name = name,
                        mimeType = mimeType,
                        dateAddedSeconds = added,
                        dateTakenMillis = taken,
                        sizeBytes = size,
                        width = width,
                        height = height,
                        relativePath = path,
                        isVideo = video,
                        durationMillis = duration,
                        isTrashed = isTrashed
                    )
                    rows.add(item)
                    pendingBatch?.add(item)
                    addedCount++

                    if (pendingBatch != null && pendingBatch.size >= nextBatchSize) {
                        onBatch?.invoke(pendingBatch.toList())
                        pendingBatch.clear()
                        nextBatchSize = batchSize ?: Int.MAX_VALUE
                    }
                    if (limit != null && addedCount >= limit) break
                }
                if (!pendingBatch.isNullOrEmpty()) {
                    onBatch?.invoke(pendingBatch.toList())
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val NON_MEDIA_EXTENSIONS = setOf(
            "xml", "txt", "json", "html", "htm", "css", "js", "bin", "pdf",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "rar", "7z", "tar", "gz",
            "apk", "dat", "db", "log", "sqlite", "properties", "yaml", "yml", "ini", "cfg",
            "sh", "py", "java", "kt", "class", "dex", "tmp", "temp", "bak", "nomedia",
            "srt", "vtt", "sub", "lrc", "meta", "downloading", "part", "crdownload"
        )

        private val VALID_IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
            "dng", "cr2", "nef", "arw", "raw", "ico", "svg"
        )

        private val VALID_VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "avi", "mov", "3gp", "flv", "m4v", "ts", "wmv", "mpg", "mpeg"
        )

        fun isSupportedMedia(name: String, mimeType: String?, isVideo: Boolean): Boolean {
            val dotIdx = name.lastIndexOf('.')
            val ext = if (dotIdx >= 0 && dotIdx < name.length - 1) {
                name.substring(dotIdx + 1).trim().lowercase(java.util.Locale.ROOT)
            } else ""
            if (ext.isNotEmpty() && ext in NON_MEDIA_EXTENSIONS) return false

            val mime = mimeType?.trim()
            if (mime != null) {
                if (mime.equals("text/xml", true) || mime.equals("application/xml", true) ||
                    (mime.contains("xml", ignoreCase = true) && !mime.contains("svg", ignoreCase = true)) ||
                    mime.equals("text/plain", true) || mime.equals("application/json", true) ||
                    mime.equals("text/html", true) || mime.equals("application/zip", true) ||
                    mime.equals("application/pdf", true) || mime.startsWith("text/", ignoreCase = true)) {
                    return false
                }
            }

            return if (isVideo) {
                (ext.isNotEmpty() && ext in VALID_VIDEO_EXTENSIONS) || (mime != null && mime.startsWith("video/", ignoreCase = true))
            } else {
                (ext.isNotEmpty() && ext in VALID_IMAGE_EXTENSIONS) || (mime != null && mime.startsWith("image/", ignoreCase = true))
            }
        }
    }
}