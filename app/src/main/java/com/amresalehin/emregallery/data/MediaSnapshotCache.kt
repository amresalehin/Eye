package com.amresalehin.emregallery.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Small persistent first-page snapshot of MediaStore metadata.
 *
 * This stores only enough rows to paint the recent gallery during a cold start. It is not an
 * image cache: thumbnails remain in Coil and PreparedThumbnailCache.
 */
object MediaSnapshotCache {
    private const val FILE_NAME = "recent_media_snapshot.bin"
    private const val MAGIC = 0x454D5245 // EMRE
    private const val VERSION = 1
    private const val MAX_ITEMS = 180
    private const val TAG = "MediaSnapshotCache"

    @Volatile
    private var lastWrittenFingerprint: Long = Long.MIN_VALUE

    private fun computeFingerprint(snapshot: List<MediaItem>): Long {
        var hash = snapshot.size.toLong() * 31L
        for (item in snapshot) {
            hash = hash * 31L + item.id
            hash = hash * 31L + item.dateAddedSeconds
            hash = hash * 31L + item.dateTakenMillis
            if (item.isTrashed) hash += 1L
        }
        return hash
    }

    private fun snapshotFile(context: Context): File =
        File(context.noBackupFilesDir, FILE_NAME)

    @Synchronized
    fun read(context: Context): List<MediaItem>? {
        val file = snapshotFile(context)
        if (!file.isFile) {
            Log.d(TAG, "Snapshot miss: no saved file")
            return null
        }
        if (file.length() <= 0L) {
            Log.w(TAG, "Snapshot miss: saved file is empty; removing it")
            runCatching { AtomicFile(file).delete() }
            return null
        }

        return runCatching {
            val stream = AtomicFile(file).openRead()
            DataInputStream(BufferedInputStream(stream)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    throw IllegalStateException("Unsupported media snapshot")
                }
                val count = input.readInt()
                if (count !in 0..MAX_ITEMS) {
                    throw IllegalStateException("Invalid media snapshot size")
                }

                buildList(count) {
                    repeat(count) {
                        val id = input.readLong()
                        val uri = input.readUTF()
                        val name = input.readUTF()
                        val mimeType = if (input.readBoolean()) input.readUTF() else null
                        val dateAddedSeconds = input.readLong()
                        val dateTakenMillis = input.readLong()
                        val sizeBytes = input.readLong()
                        val width = input.readInt()
                        val height = input.readInt()
                        val relativePath = input.readUTF()
                        val isVideo = input.readBoolean()
                        val durationMillis = input.readLong()
                        val isTrashed = input.readBoolean()
                        add(
                            MediaItem(
                                id = id,
                                uri = uri,
                                name = name,
                                mimeType = mimeType,
                                dateAddedSeconds = dateAddedSeconds,
                                dateTakenMillis = dateTakenMillis,
                                sizeBytes = sizeBytes,
                                width = width,
                                height = height,
                                relativePath = relativePath,
                                isVideo = isVideo,
                                durationMillis = durationMillis,
                                isTrashed = isTrashed
                            )
                        )
                    }
                }
            }
        }.onSuccess { snapshot ->
            lastWrittenFingerprint = computeFingerprint(snapshot)
            Log.d(TAG, "Snapshot restored: ${snapshot.size} media records")
        }.onFailure { error ->
            Log.w(TAG, "Snapshot read failed; removing invalid cache", error)
            runCatching { AtomicFile(file).delete() }
        }.getOrNull()
    }

    @Synchronized
    fun write(context: Context, media: List<MediaItem>) {
        val file = snapshotFile(context)
        val snapshot = media.take(MAX_ITEMS)
        val fingerprint = computeFingerprint(snapshot)
        if (fingerprint == lastWrittenFingerprint && file.isFile && file.length() > 0L) return
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "Snapshot write skipped: cannot create cache directory")
            return
        }

        val atomicFile = AtomicFile(file)
        runCatching {
            val stream = atomicFile.startWrite()
            try {
                val output = DataOutputStream(BufferedOutputStream(stream))
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(snapshot.size)
                snapshot.forEach { item ->
                    output.writeLong(item.id)
                    output.writeUTF(item.uri)
                    output.writeUTF(item.name)
                    output.writeBoolean(item.mimeType != null)
                    item.mimeType?.let { output.writeUTF(it) }
                    output.writeLong(item.dateAddedSeconds)
                    output.writeLong(item.dateTakenMillis)
                    output.writeLong(item.sizeBytes)
                    output.writeInt(item.width)
                    output.writeInt(item.height)
                    output.writeUTF(item.relativePath)
                    output.writeBoolean(item.isVideo)
                    output.writeLong(item.durationMillis)
                    output.writeBoolean(item.isTrashed)
                }
                output.flush()
                atomicFile.finishWrite(stream)
                lastWrittenFingerprint = fingerprint
            } catch (error: Throwable) {
                atomicFile.failWrite(stream)
                throw error
            }
        }.onSuccess {
            Log.d(TAG, "Snapshot saved: ${snapshot.size} media records")
        }.onFailure { error ->
            Log.e(TAG, "Snapshot write failed", error)
        }
    }
}
