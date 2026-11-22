package com.amresalehin.emregallery.data

import android.content.Context
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.annotation.Keep
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Receives Blob bytes from the originating WebView in bounded Base64 chunks.
 * Blob URLs are renderer-scoped, so completed captures are stored as app-private files
 * and exposed to the ordinary download queue as file:// media items.
 */
@Keep
class BrowserBlobCaptureStore(context: Context) {
    private val captureDirectory = File(context.applicationContext.filesDir, "browser_blob_captures")

    private data class CaptureSession(
        val temporaryFile: File,
        val output: BufferedOutputStream,
        val mimeType: String,
        val fileName: String,
        val expectedBytes: Long,
        var writtenBytes: Long = 0L
    )

    private val sessions = LinkedHashMap<String, CaptureSession>()

    @Synchronized
    fun beginCapture(captureId: String?, mimeType: String?, fileName: String?, expectedBytes: Long): Boolean {
        val id = captureId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,96}")) } ?: return false
        if (sessions.containsKey(id) || sessions.size >= MAX_CONCURRENT_CAPTURES) return false

        val safeMime = mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        val safeName = sanitizeFileName(fileName.orEmpty())
        val suppliedExtension = safeName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mimeExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(safeMime)?.lowercase(Locale.ROOT)
        val extension = when {
            suppliedExtension in MEDIA_EXTENSIONS && (mimeExtension == null || suppliedExtension == mimeExtension) -> suppliedExtension
            mimeExtension in MEDIA_EXTENSIONS -> mimeExtension!!
            suppliedExtension in MEDIA_EXTENSIONS -> suppliedExtension
            else -> return false
        }
        val isMediaMime = safeMime.startsWith("image/") || safeMime.startsWith("video/") || safeMime.startsWith("audio/")
        if (!isMediaMime && suppliedExtension !in MEDIA_EXTENSIONS && mimeExtension !in MEDIA_EXTENSIONS) return false
        if (expectedBytes < 0L || expectedBytes > MAX_CAPTURE_BYTES) return false

        val normalizedMime = if (isMediaMime) safeMime else {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }

        return try {
            if (!captureDirectory.exists() && !captureDirectory.mkdirs()) return false
            val temporaryFile = File(captureDirectory, "$" + "{id}.part")
            if (temporaryFile.exists() && !temporaryFile.delete()) return false
            val output = BufferedOutputStream(FileOutputStream(temporaryFile), IO_BUFFER_BYTES)
            val finalName = if (safeName.substringAfterLast('.', "").isBlank()) "$" + "{safeName}.$" + "{extension}" else safeName
            sessions[id] = CaptureSession(temporaryFile, output, normalizedMime, finalName, expectedBytes)
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun appendChunk(captureId: String?, base64Chunk: String?): Boolean {
        val id = captureId ?: return false
        val encoded = base64Chunk ?: return false
        if (encoded.isBlank() || encoded.length > MAX_ENCODED_CHUNK_CHARS) return false
        val session = sessions[id] ?: return false
        return try {
            val decoded = Base64.decode(encoded, Base64.NO_WRAP)
            if (decoded.isEmpty() || session.writtenBytes + decoded.size > MAX_CAPTURE_BYTES) {
                abortCaptureInternal(id)
                return false
            }
            session.output.write(decoded)
            session.writtenBytes += decoded.size
            true
        } catch (_: Exception) {
            abortCaptureInternal(id)
            false
        }
    }

    @Synchronized
    fun finishCapture(captureId: String?, pageUrl: String?): ScrapedMediaItem? {
        val id = captureId ?: return null
        val session = sessions.remove(id) ?: return null
        return try {
            session.output.flush()
            session.output.close()
            if (session.writtenBytes <= 0L ||
                (session.expectedBytes > 0L && session.expectedBytes != session.writtenBytes)
            ) {
                session.temporaryFile.delete()
                return null
            }

            val extension = session.fileName.substringAfterLast('.', "bin")
            val destination = File(captureDirectory, "$" + "{id}.$" + "{extension}")
            if (destination.exists() && !destination.delete()) {
                session.temporaryFile.delete()
                return null
            }
            if (!session.temporaryFile.renameTo(destination)) {
                session.temporaryFile.copyTo(destination, overwrite = true)
                session.temporaryFile.delete()
            }

            val mediaUrl = destination.toURI().toString()
            val classification = MediaClassifier.classify(mediaUrl, session.mimeType)
            val thumbnail = if (classification.category == MediaCategory.IMAGES) mediaUrl else null
            ScrapedMediaItem(
                id = "browser_blob_$" + "{id}",
                url = mediaUrl,
                title = destination.name,
                isVideo = classification.isVideo,
                isAudio = classification.isAudio,
                isStream = classification.isStream,
                category = classification.category,
                format = classification.format,
                thumbnailUrl = thumbnail,
                source = "Browser Blob Capture",
                mimeType = session.mimeType,
                sizeBytes = destination.length(),
                provenance = MediaProvenance(
                    extractorEngine = "WebView Blob Capture",
                    pageUrl = pageUrl.orEmpty()
                ),
                metadata = mapOf(
                    "browser_blob_capture" to "true",
                    "source_url" to pageUrl.orEmpty(),
                    "captured_mime_type" to session.mimeType
                )
            )
        } catch (_: Exception) {
            runCatching { session.output.close() }
            runCatching { session.temporaryFile.delete() }
            null
        }
    }

    @Synchronized
    fun abortCapture(captureId: String?) {
        captureId?.let(::abortCaptureInternal)
    }

    @Synchronized
    fun abortAll() {
        sessions.keys.toList().forEach(::abortCaptureInternal)
    }

    private fun abortCaptureInternal(id: String) {
        val session = sessions.remove(id) ?: return
        runCatching { session.output.close() }
        runCatching { session.temporaryFile.delete() }
    }

    private fun sanitizeFileName(value: String): String {
        val cleaned = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_')
            .take(96)
        return cleaned.ifBlank { "captured_media" }
    }

    companion object {
        private const val MAX_CONCURRENT_CAPTURES = 2
        private const val MAX_CAPTURE_BYTES = 512L * 1024L * 1024L
        private const val IO_BUFFER_BYTES = 64 * 1024
        private const val MAX_ENCODED_CHUNK_CHARS = 96 * 1024
        private val MEDIA_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "svg",
            "mp4", "m4v", "mov", "webm", "mkv", "3gp", "ts", "m4s",
            "mp3", "m4a", "aac", "wav", "ogg", "opus", "flac"
        )
    }
}
