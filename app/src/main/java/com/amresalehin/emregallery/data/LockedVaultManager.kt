package com.amresalehin.emregallery.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class VaultLockResult(
    val lockedItems: List<MediaItem>,
    val pendingDeleteUris: List<String>
)

class LockedVaultManager(private val context: Context) {

    private val vaultDir: File = File(context.filesDir, VAULT_DIR_NAME).apply {
        if (!exists()) mkdirs()
    }

    private val encryptedMetaFile: File = File(vaultDir, "vault_metadata.json.enc")
    private val legacyMetaFile: File = File(vaultDir, "vault_metadata.json")

    fun getVaultItems(): List<MediaItem> {
        val jsonStr = readMetadataJson() ?: return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<MediaItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val uriStr = obj.getString("uri")
                val file = File(Uri.parse(uriStr).path ?: "")
                if (file.exists()) {
                    val actualSize = VaultCrypto.getVaultFilePlaintextLength(file)
                    list.add(
                        MediaItem(
                            id = obj.getLong("id"),
                            uri = uriStr,
                            name = obj.getString("name"),
                            mimeType = if (obj.has("mimeType") && !obj.isNull("mimeType")) obj.getString("mimeType") else null,
                            dateAddedSeconds = obj.optLong("dateAddedSeconds", System.currentTimeMillis() / 1000L),
                            dateTakenMillis = obj.optLong("dateTakenMillis", 0L),
                            sizeBytes = actualSize,
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

    private fun readMetadataJson(): String? {
        if (encryptedMetaFile.exists()) {
            return runCatching {
                val raw = encryptedMetaFile.readBytes()
                String(VaultCrypto.decryptBytes(raw), Charsets.UTF_8)
            }.getOrNull()
        }
        if (legacyMetaFile.exists()) {
            val legacyJson = runCatching { legacyMetaFile.readText() }.getOrNull()
            if (!legacyJson.isNullOrBlank()) {
                runCatching {
                    val encrypted = VaultCrypto.encryptBytes(legacyJson.toByteArray(Charsets.UTF_8))
                    encryptedMetaFile.writeBytes(encrypted)
                    legacyMetaFile.delete()
                }
            }
            return legacyJson
        }
        return null
    }

    private fun saveVaultItems(items: List<MediaItem>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("uri", item.uri)
                put("name", item.name)
                put("mimeType", item.mimeType)
                put("dateAddedSeconds", item.dateAddedSeconds)
                put("dateTakenMillis", item.dateTakenMillis)
                put("width", item.width)
                put("height", item.height)
                put("isVideo", item.isVideo)
                put("durationMillis", item.durationMillis)
            }
            array.put(obj)
        }
        val encrypted = VaultCrypto.encryptBytes(array.toString().toByteArray(Charsets.UTF_8))
        encryptedMetaFile.writeBytes(encrypted)
        if (legacyMetaFile.exists()) {
            legacyMetaFile.delete()
        }
    }

    /**
     * Encrypts and copies media from mainline storage into app-private locked vault,
     * deleting the original file from MediaStore when allowed (and returning any URIs
     * requiring user confirmation via MediaStore.createDeleteRequest on Android 11+).
     */
    suspend fun lockItemsWithPendingDeletes(items: List<MediaItem>): VaultLockResult = withContext(Dispatchers.IO) {
        val currentVaultItems = getVaultItems().toMutableList()
        val lockedList = mutableListOf<MediaItem>()
        val pendingDeletes = mutableListOf<String>()

        for (item in items) {
            try {
                val safeName = item.name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                val destFile = File(vaultDir, "vault_${item.id}_${safeName}.enc")
                val srcUri = Uri.parse(item.uri)

                context.contentResolver.openInputStream(srcUri)?.use { input ->
                    VaultCrypto.encryptStreamToFile(input, destFile)
                }

                if (destFile.exists() && destFile.length() > VaultCrypto.HEADER_SIZE) {
                    val plaintextSize = VaultCrypto.getVaultFilePlaintextLength(destFile)
                    val lockedItem = item.copy(
                        uri = Uri.fromFile(destFile).toString(),
                        relativePath = "Locked",
                        sizeBytes = plaintextSize
                    )
                    currentVaultItems.removeAll { it.id == item.id || it.uri == lockedItem.uri }
                    currentVaultItems.add(lockedItem)
                    lockedList.add(lockedItem)

                    // Remove original from device mainline storage if app owns it; otherwise request user confirmation
                    val deletedDirectly = runCatching {
                        context.contentResolver.delete(srcUri, null, null) > 0
                    }.getOrDefault(false)
                    if (!deletedDirectly && item.uri.startsWith("content://")) {
                        pendingDeletes.add(item.uri)
                    }
                }
            } catch (_: Exception) {
            }
        }

        saveVaultItems(currentVaultItems)
        VaultLockResult(lockedItems = lockedList, pendingDeleteUris = pendingDeletes)
    }

    /**
     * Copies media from mainline storage into encrypted app-private locked vault
     * and attempts to remove originals from mainline storage.
     */
    suspend fun lockItems(items: List<MediaItem>): List<MediaItem> =
        lockItemsWithPendingDeletes(items).lockedItems

    /**
     * Restores media items back from locked vault to mainline storage, decrypting transparently.
     */
    suspend fun unlockItems(items: List<MediaItem>): List<String> = withContext(Dispatchers.IO) {
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

                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType ?: if (item.isVideo) "video/mp4" else "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Restored")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val insertedUri = context.contentResolver.insert(collection, cv)
                if (insertedUri != null) {
                    VaultCrypto.openDecryptedInputStream(srcFile).use { input ->
                        context.contentResolver.openOutputStream(insertedUri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cv.clear()
                        cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        context.contentResolver.update(insertedUri, cv, null, null)
                    }
                    srcFile.delete()
                    currentVaultItems.removeAll { it.uri == item.uri || it.id == item.id }
                    restoredUris.add(insertedUri.toString())
                }
            } catch (_: Exception) {
            }
        }

        saveVaultItems(currentVaultItems)
        restoredUris
    }

    /**
     * Permanently deletes items from the locked vault.
     */
    suspend fun deletePermanently(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        val currentVaultItems = getVaultItems().toMutableList()
        for (item in items) {
            try {
                val file = File(Uri.parse(item.uri).path ?: "")
                if (file.exists()) file.delete()
                currentVaultItems.removeAll { it.uri == item.uri || it.id == item.id }
            } catch (_: Exception) {
            }
        }
        saveVaultItems(currentVaultItems)
    }

    companion object {
        private const val VAULT_DIR_NAME = "locked_vault"

        fun isVaultFileUri(context: Context, uriString: String?): Boolean {
            if (uriString.isNullOrBlank()) return false
            val path = runCatching { Uri.parse(uriString).path }.getOrNull() ?: return false
            return isVaultFile(context, File(path))
        }

        fun isVaultFile(context: Context, file: File): Boolean {
            val vaultRoot = runCatching { File(context.filesDir, VAULT_DIR_NAME).canonicalPath }.getOrNull()
                ?: return false
            val targetPath = runCatching { file.canonicalPath }.getOrNull() ?: return false
            return targetPath.startsWith(vaultRoot + File.separator) || targetPath == vaultRoot
        }
    }
}
