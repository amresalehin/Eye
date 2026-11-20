package com.amresalehin.emregallery.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class VaultLockResult(
    val lockedItems: List<MediaItem>,
    val pendingDeleteUris: List<Uri> = emptyList()
)

class LockedVaultManager(private val context: Context) {

    private val vaultDir: File = File(context.filesDir, VAULT_DIR_NAME).apply {
        if (!exists()) mkdirs()
    }

    private val encryptedMetaFile: File = File(vaultDir, "vault_metadata.json.enc")
    private val legacyMetaFile: File = File(vaultDir, "vault_metadata.json")

    fun getVaultItems(): List<MediaItem> = synchronized(metaLock) {
        val jsonStr = readMetadataJsonLocked() ?: return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<MediaItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val uriStr = obj.getString("uri")
                val file = File(Uri.parse(uriStr).path ?: "")
                if (file.exists()) {
                    val plaintextSize = if (VaultCrypto.isEncryptedVaultFile(file)) {
                        VaultCrypto.getVaultFilePlaintextLength(file).takeIf { it >= 0L }
                            ?: obj.optLong("sizeBytes", file.length())
                    } else {
                        file.length()
                    }
                    list.add(
                        MediaItem(
                            id = obj.getLong("id"),
                            uri = uriStr,
                            name = obj.getString("name"),
                            mimeType = if (obj.has("mimeType") && !obj.isNull("mimeType")) obj.getString("mimeType") else null,
                            dateAddedSeconds = obj.optLong("dateAddedSeconds", System.currentTimeMillis() / 1000L),
                            dateTakenMillis = obj.optLong("dateTakenMillis", 0L),
                            sizeBytes = plaintextSize,
                            width = obj.optInt("width", 0),
                            height = obj.optInt("height", 0),
                            relativePath = "Locked",
                            isVideo = obj.optBoolean("isVideo", false),
                            durationMillis = obj.optLong("durationMillis", 0L),
                            isTrashed = false
                        )
                    )
                }
            }
            list.sortedByDescending { if (it.dateTakenMillis > 0) it.dateTakenMillis else it.dateAddedSeconds * 1000L }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readMetadataJsonLocked(): String? {
        if (encryptedMetaFile.exists()) {
            return runCatching {
                val encrypted = encryptedMetaFile.readBytes()
                VaultCrypto.decryptBytes(encrypted, META_AAD).toString(Charsets.UTF_8)
            }.getOrNull()
        }
        if (legacyMetaFile.exists()) {
            return runCatching {
                val legacyText = legacyMetaFile.readText(Charsets.UTF_8)
                writeMetadataJsonAtomicLocked(legacyText)
                legacyMetaFile.delete()
                legacyText
            }.getOrNull()
        }
        return null
    }

    private fun saveVaultItemsLocked(items: List<MediaItem>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("uri", item.uri)
                put("name", item.name)
                put("mimeType", item.mimeType)
                put("dateAddedSeconds", item.dateAddedSeconds)
                put("dateTakenMillis", item.dateTakenMillis)
                put("sizeBytes", item.sizeBytes)
                put("width", item.width)
                put("height", item.height)
                put("isVideo", item.isVideo)
                put("durationMillis", item.durationMillis)
            }
            array.put(obj)
        }
        writeMetadataJsonAtomicLocked(array.toString())
        if (legacyMetaFile.exists()) {
            legacyMetaFile.delete()
        }
    }

    private fun writeMetadataJsonAtomicLocked(json: String) {
        if (!vaultDir.exists()) vaultDir.mkdirs()
        val encrypted = VaultCrypto.encryptBytes(json.toByteArray(Charsets.UTF_8), META_AAD)
        val tmp = File(vaultDir, "vault_metadata.json.enc.tmp")
        tmp.writeBytes(encrypted)
        if (!tmp.renameTo(encryptedMetaFile)) {
            encryptedMetaFile.writeBytes(encrypted)
            tmp.delete()
        }
    }

    /**
     * Encrypts and copies media from mainline storage into the app-private locked vault,
     * deletes originals where permitted, and returns any URIs requiring user delete confirmation.
     */
    suspend fun lockItemsWithPendingDeletes(items: List<MediaItem>): VaultLockResult =
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                val currentVaultItems = getVaultItems().toMutableList()
                val lockedList = mutableListOf<MediaItem>()
                val pendingDeletes = mutableListOf<Uri>()

                for (item in items) {
                    try {
                        val safeName = item.name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                        val destFile = File(vaultDir, "vault_${item.id}_$safeName")
                        val srcUri = Uri.parse(item.uri)

                        val inputStream = if (srcUri.scheme == "file") {
                            val srcPath = srcUri.path
                            if (srcPath != null && File(srcPath).exists()) File(srcPath).inputStream() else null
                        } else {
                            context.contentResolver.openInputStream(srcUri)
                        }

                        var plaintextBytes = 0L
                        inputStream?.use { input ->
                            plaintextBytes = VaultCrypto.encryptStreamToFile(input, destFile)
                        }

                        if (destFile.exists() && destFile.length() > 0 && plaintextBytes > 0L) {
                            val lockedItem = item.copy(
                                uri = Uri.fromFile(destFile).toString(),
                                relativePath = "Locked",
                                sizeBytes = plaintextBytes
                            )
                            currentVaultItems.removeAll { it.id == item.id || it.uri == lockedItem.uri }
                            currentVaultItems.add(lockedItem)
                            lockedList.add(lockedItem)

                            // Remove original from mainline storage; track URIs that need system consent
                            val deleted = runCatching {
                                if (srcUri.scheme == "file") {
                                    srcUri.path?.let { File(it).delete() } ?: false
                                } else {
                                    context.contentResolver.delete(srcUri, null, null) > 0
                                }
                            }.getOrDefault(false)
                            if (!deleted && srcUri.scheme == "content") {
                                pendingDeletes.add(srcUri)
                            }
                        } else {
                            runCatching { destFile.delete() }
                        }
                    } catch (_: Exception) {
                    }
                }

                synchronized(metaLock) {
                    saveVaultItemsLocked(currentVaultItems)
                }
                VaultLockResult(lockedItems = lockedList, pendingDeleteUris = pendingDeletes)
            }
        }

    /**
     * Copies media from mainline storage into app-private locked vault
     * and deletes the original file from mainline storage (MediaStore).
     */
    suspend fun lockItems(items: List<MediaItem>): List<MediaItem> =
        lockItemsWithPendingDeletes(items).lockedItems

    /**
     * Decrypts and restores media items back from locked vault to mainline storage.
     */
    suspend fun unlockItems(items: List<MediaItem>): List<String> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val currentVaultItems = getVaultItems().toMutableList()
            val restoredUris = mutableListOf<String>()

            for (item in items) {
                try {
                    val srcFile = File(Uri.parse(item.uri).path ?: "")
                    if (!srcFile.exists()) continue

                    val collection = if (item.isVideo) {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }
                    val targetRelativePath = if (item.isVideo) {
                        Environment.DIRECTORY_MOVIES + "/Restored"
                    } else {
                        Environment.DIRECTORY_PICTURES + "/Restored"
                    }

                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                        put(
                            MediaStore.MediaColumns.MIME_TYPE,
                            item.mimeType ?: if (item.isVideo) "video/mp4" else "image/jpeg"
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, targetRelativePath)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }

                    val insertedUri = context.contentResolver.insert(collection, cv)
                    if (insertedUri != null) {
                        var bytesCopied = 0L
                        val inputStream = if (VaultCrypto.isEncryptedVaultFile(srcFile)) {
                            VaultCrypto.openDecryptedInputStream(srcFile)
                        } else {
                            srcFile.inputStream()
                        }
                        inputStream.use { input ->
                            context.contentResolver.openOutputStream(insertedUri)?.use { output ->
                                bytesCopied = input.copyTo(output)
                            }
                        }
                        if (bytesCopied > 0L) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                cv.clear()
                                cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                context.contentResolver.update(insertedUri, cv, null, null)
                            }
                            srcFile.delete()
                            currentVaultItems.removeAll { it.uri == item.uri || it.id == item.id }
                            restoredUris.add(insertedUri.toString())
                        } else {
                            runCatching { context.contentResolver.delete(insertedUri, null, null) }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            synchronized(metaLock) {
                saveVaultItemsLocked(currentVaultItems)
            }
            restoredUris
        }
    }

    /**
     * Permanently deletes items from the locked vault.
     */
    suspend fun deletePermanently(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val currentVaultItems = getVaultItems().toMutableList()
            for (item in items) {
                try {
                    val file = File(Uri.parse(item.uri).path ?: "")
                    if (file.exists()) file.delete()
                    currentVaultItems.removeAll { it.uri == item.uri || it.id == item.id }
                } catch (_: Exception) {
                }
            }
            synchronized(metaLock) {
                saveVaultItemsLocked(currentVaultItems)
            }
        }
    }

    companion object {
        private const val VAULT_DIR_NAME = "locked_vault"
        private val META_AAD = "emre_vault_metadata_v1".toByteArray(Charsets.UTF_8)
        private val metaLock = Any()
        private val ioMutex = Mutex()

        fun isVaultFileUri(context: Context, uriString: String?): Boolean {
            if (uriString.isNullOrBlank()) return false
            val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
            if (parsed.scheme != "file") return false
            val path = parsed.path ?: return false
            return runCatching {
                val vaultRoot = File(context.applicationContext.filesDir, VAULT_DIR_NAME).canonicalPath
                val targetPath = File(path).canonicalPath
                targetPath == vaultRoot || targetPath.startsWith(vaultRoot + File.separator)
            }.getOrDefault(false)
        }
    }
}
