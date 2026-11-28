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
        batchSize: Int = 128
    ): Flow<MediaLoadEvent> = channelFlow {
        val firstSize = firstBatchSize.coerceAtLeast(1)
        val laterSize = batchSize.coerceAtLeast(firstSize)
        val result = queryMedia(
            limitPerCollection = null,
            firstBatchSize = firstSize,
            batchSize = laterSize,
            retainRows = false,
            onBatch = { batch -> send(MediaLoadEvent.Batch(batch)) }
        )
        send(MediaLoadEvent.Completed(result))
    }.buffer(Channel.RENDEZVOUS)

    private suspend fun queryMedia(
        limitPerCollection: Int?,
        firstBatchSize: Int? = null,
        batchSize: Int? = null,
        retainRows: Boolean = true,
        onBatch: (suspend (List<MediaItem>) -> Unit)? = null
    ): MediaLoadResult = withContext(Dispatchers.IO) {
        // Non-streaming callers receive a complete list. The progressive path hands each
        // bounded batch to its consumer and must not also retain a second full-library list.
        val rows = if (retainRows) {
            ArrayList<MediaItem>(if (limitPerCollection != null) limitPerCollection * 2 else 256)
        } else null
        val collection = MediaStore.Files.getContentUri("external")
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            add(MediaStore.Video.VideoColumns.DURATION)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.IS_TRASHED)
            }
        }.toTypedArray()
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val cursor = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val queryArgs = android.os.Bundle().apply {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                    }
                }
                resolver.query(collection, projection, queryArgs, null)
            } else {
                resolver.query(collection, projection, selection, selectionArgs, sort)
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        } ?: return@withContext MediaLoadResult(emptyList(), 0)

        try {
            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val addedCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val widthCol = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val heightCol = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val pathCol = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val takenCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                val mediaTypeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val durationCol = c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
                val trashedCol = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                } else -1

                var imageCount = 0
                var videoCount = 0
                var nextBatchSize = firstBatchSize ?: batchSize ?: Int.MAX_VALUE
                val pendingBatch = if (onBatch != null) {
                    ArrayList<MediaItem>(firstBatchSize ?: 72)
                } else null

                while (c.moveToNext()) {
                    val mediaType = c.getInt(mediaTypeCol)
                    val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    if (!isVideo && mediaType != MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) continue
                    if (limitPerCollection != null &&
                        if (isVideo) videoCount >= limitPerCollection else imageCount >= limitPerCollection
                    ) {
                        if (imageCount >= limitPerCollection && videoCount >= limitPerCollection) break
                        continue
                    }

                    val id = c.getLong(idCol)
                    val itemCollection = if (isVideo) {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }
                    val itemUri = ContentUris.withAppendedId(itemCollection, id)
                    val name = c.getString(nameCol) ?: "Untitled"
                    val mimeType = if (mimeCol >= 0) c.getString(mimeCol) else null
                    if (!isSupportedMedia(name, mimeType, isVideo)) continue

                    val item = MediaItem(
                        id = id,
                        uri = itemUri.toString(),
                        name = name,
                        mimeType = mimeType,
                        dateAddedSeconds = if (addedCol >= 0) c.getLong(addedCol) else 0L,
                        dateTakenMillis = if (takenCol >= 0 && !c.isNull(takenCol)) c.getLong(takenCol) else 0L,
                        sizeBytes = if (sizeCol >= 0) c.getLong(sizeCol) else 0L,
                        width = if (widthCol >= 0) c.getInt(widthCol) else 0,
                        height = if (heightCol >= 0) c.getInt(heightCol) else 0,
                        relativePath = if (pathCol >= 0) c.getString(pathCol) ?: "" else "",
                        isVideo = isVideo,
                        durationMillis = if (isVideo && durationCol >= 0 && !c.isNull(durationCol)) c.getLong(durationCol) else 0L,
                        isTrashed = trashedCol >= 0 && !c.isNull(trashedCol) && c.getInt(trashedCol) != 0
                    )
                    rows?.add(item)
                    if (isVideo) videoCount++ else imageCount++
                    pendingBatch?.add(item)

                    if (pendingBatch != null && pendingBatch.size >= nextBatchSize) {
                        onBatch?.invoke(pendingBatch.toList())
                        pendingBatch.clear()
                        nextBatchSize = batchSize ?: Int.MAX_VALUE
                    }
                }
                if (!pendingBatch.isNullOrEmpty()) {
                    onBatch?.invoke(pendingBatch.toList())
                }
            }
            MediaLoadResult(rows ?: emptyList(), 1)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            MediaLoadResult(emptyList(), 0)
        } catch (_: Exception) {
            MediaLoadResult(emptyList(), 0)
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