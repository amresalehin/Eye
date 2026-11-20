package com.amresalehin.emregallery.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Encrypted, app-private media vault.
 *
 * Locking is deliberately two phase: encrypt and verify every source, then atomically
 * commit encrypted metadata. The caller receives source URIs only after that commit and
 * is responsible for deleting/hiding those originals. A failed vault commit never deletes
 * the source media.
 */
data class VaultLockResult(
    val lockedItems: List<MediaItem>,
    val pendingDeleteUris: List<String>
)

class LockedVaultManager(private val context: Context) {

    private val vaultDir = File(context.filesDir, "locked_vault").apply { mkdirs() }
    private val metaFile = File(vaultDir, "vault_metadata.json.enc")
    private val legacyMetaFile = File(vaultDir, "vault_metadata.json")

    fun getVaultItems(): List<MediaItem> {
        val array = readMetadata()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val uriString = obj.optString("uri").takeIf { it.isNotBlank() } ?: continue
                val file = vaultFileFor(uriString) ?: continue
                if (!file.isFile || file.length() <= 0L) continue
                if (VaultCrypto.isEncryptedVaultFile(file)) {
                    // Reading the header validates structure; opening the reader validates the key.
                    VaultCrypto.openChunkReader(file)?.close() ?: continue
                } else if (!legacyMetaFile.exists()) {
                    // New-format metadata must never point to an unencrypted media file.
                    continue
                }
                add(
                    MediaItem(
                        id = obj.optLong("id"),
                        uri = uriString,
                        name = obj.optString("name", file.name),
                        mimeType = obj.optString("mimeType").takeIf { it.isNotBlank() && it != "null" },
                        dateAddedSeconds = obj.optLong("dateAddedSeconds", System.currentTimeMillis() / 1000L),
                        dateTakenMillis = obj.optLong("dateTakenMillis", 0L),
                        sizeBytes = VaultCrypto.getVaultFilePlaintextLength(file),
                        width = obj.optInt("width", 0),
                        height = obj.optInt("height", 0),
                        relativePath = "Locked",
                        isVideo = obj.optBoolean("isVideo", false),
                        durationMillis = obj.optLong("durationMillis", 0L),
                        isTrashed = false
                    )
                )
            }
        }.sortedByDescending {
            if (it.dateTakenMillis > 0L) it.dateTakenMillis else it.dateAddedSeconds * 1000L
        }
    }

    private fun readMetadata(): JSONArray {
        if (metaFile.isFile) {
            val plain = VaultCrypto.decryptBytes(metaFile.readBytes(), METADATA_AAD)
            return JSONArray(plain.toString(Charsets.UTF_8))
        }
        // Backward compatibility only. The next successful mutation migrates metadata.
        if (legacyMetaFile.isFile) return JSONArray(legacyMetaFile.readText())
        return JSONArray()
    }

    private fun saveVaultItems(items: List<MediaItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
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
            })
        }
        val encrypted = VaultCrypto.encryptBytes(array.toString().toByteArray(Charsets.UTF_8), METADATA_AAD)
        val temp = File(vaultDir, "vault_metadata.${UUID.randomUUID()}.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(encrypted)
            stream.fd.sync()
        }
        // Prefer an atomic same-directory replacement; retain a checked rename fallback for
        // Android filesystem implementations that do not expose ATOMIC_MOVE.
        try {
            Files.move(
                temp.toPath(),
                metaFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (atomicFailure: Exception) {
            if (!temp.renameTo(metaFile)) {
                temp.delete()
                throw IllegalStateException("Unable to atomically commit vault metadata", atomicFailure)
            }
        }
        // Keep the legacy index until its plaintext media files have been migrated.
        // Deleting it here would make those pre-encryption vault items undiscoverable.
    }

    suspend fun lockItemsWithPendingDeletes(items: List<MediaItem>): VaultLockResult =
        withContext(Dispatchers.IO) {
            val current = getVaultItems().toMutableList()
            val createdFiles = mutableListOf<File>()
            val locked = mutableListOf<MediaItem>()
            val pendingDeleteUris = mutableListOf<String>()
            try {
                for (item in items.distinctBy { it.uri }) {
                    val sourceUri = Uri.parse(item.uri)
                    val safeName = item.name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                    val dest = File(vaultDir, "vault_${UUID.randomUUID()}_$safeName.enc")
                    val input = context.contentResolver.openInputStream(sourceUri) ?: continue
                    input.use { VaultCrypto.encryptStreamToFile(it, dest) }
                    createdFiles.add(dest)
                    if (!dest.isFile || !VaultCrypto.isEncryptedVaultFile(dest) ||
                        VaultCrypto.getVaultFilePlaintextLength(dest) <= 0L
                    ) {
                        dest.delete()
                        throw IllegalStateException("Vault encryption verification failed for ${item.name}")
                    }
                    // Authenticate every encrypted chunk before metadata commit. Checking only
                    // the header or first byte would allow a truncated/corrupt tail to be locked.
                    val expectedPlaintextLength = VaultCrypto.getVaultFilePlaintextLength(dest)
                    var verifiedPlaintextLength = 0L
                    VaultCrypto.openDecryptedInputStream(dest).use { stream ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            verifiedPlaintextLength += count
                        }
                    }
                    if (verifiedPlaintextLength <= 0L || verifiedPlaintextLength != expectedPlaintextLength) {
                        throw IllegalStateException("Encrypted media integrity check failed for ${item.name}")
                    }
                    locked.add(
                        item.copy(
                            uri = Uri.fromFile(dest).toString(),
                            relativePath = "Locked",
                            sizeBytes = VaultCrypto.getVaultFilePlaintextLength(dest)
                        )
                    )
                    pendingDeleteUris.add(item.uri)
                }

                if (locked.isNotEmpty()) {
                    current.addAll(locked)
                    saveVaultItems(current)
                }
                VaultLockResult(lockedItems = locked, pendingDeleteUris = pendingDeleteUris.distinct())
            } catch (failure: Throwable) {
                createdFiles.forEach { it.delete() }
                throw failure
            }
        }

    /** Compatibility entry point: commit to the vault before attempting source deletion. */
    suspend fun lockItems(items: List<MediaItem>): List<MediaItem> = withContext(Dispatchers.IO) {
        val result = lockItemsWithPendingDeletes(items)
        result.pendingDeleteUris.forEach { uri ->
            runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
        }
        result.lockedItems
    }

    suspend fun unlockItems(items: List<MediaItem>): List<String> = withContext(Dispatchers.IO) {
        val current = getVaultItems().toMutableList()
        val restoredUris = mutableListOf<String>()
        val successfullyRestored = mutableListOf<MediaItem>()

        for (item in items.distinctBy { it.uri }) {
            val srcFile = vaultFileFor(item.uri) ?: continue
            if (!srcFile.isFile) continue
            val collection = if (item.isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType ?: if (item.isVideo) "video/mp4" else "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Restored")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val target = context.contentResolver.insert(collection, values) ?: continue
            try {
                var copied = 0L
                VaultCrypto.openDecryptedInputStream(srcFile).use { input ->
                    val output = context.contentResolver.openOutputStream(target, "w")
                        ?: throw IllegalStateException("Unable to open restored media destination")
                    output.use { copied = input.copyTo(it) }
                }
                if (copied <= 0L) throw IllegalStateException("Restored media is empty")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    if (context.contentResolver.update(target, publish, null, null) <= 0) {
                        throw IllegalStateException("Unable to publish restored media")
                    }
                }
                restoredUris.add(target.toString())
                successfullyRestored.add(item)
            } catch (_: Exception) {
                runCatching { context.contentResolver.delete(target, null, null) }
            }
        }

        if (successfullyRestored.isNotEmpty()) {
            val remaining = current.filterNot { old -> successfullyRestored.any { it.uri == old.uri } }
            // Keep encrypted originals until metadata removal is durably committed.
            saveVaultItems(remaining)
            successfullyRestored.forEach { item -> vaultFileFor(item.uri)?.delete() }
        }
        restoredUris
    }

    suspend fun deletePermanently(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        val current = getVaultItems().toMutableList()
        val deletable = items.distinctBy { it.uri }.filter { item ->
            val file = vaultFileFor(item.uri) ?: return@filter false
            !file.exists() || file.delete()
        }
        if (deletable.isNotEmpty()) {
            saveVaultItems(current.filterNot { old -> deletable.any { it.uri == old.uri } })
        }
    }

    private fun vaultFileFor(uriString: String): File? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        if (!uri.scheme.equals("file", ignoreCase = true)) return null
        val path = uri.path ?: return null
        return runCatching {
            val root = vaultDir.canonicalFile
            val candidate = File(path).canonicalFile
            if (candidate.path.startsWith(root.path + File.separator)) candidate else null
        }.getOrNull()
    }

    companion object {
        private val METADATA_AAD = "Eye.LockedVault.Metadata.v1".toByteArray(Charsets.UTF_8)

        fun isVaultFileUri(context: Context, uriString: String): Boolean {
            val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
            if (!uri.scheme.equals("file", ignoreCase = true)) return false
            val path = uri.path ?: return false
            return runCatching {
                val root = File(context.applicationContext.filesDir, "locked_vault").canonicalFile
                File(path).canonicalFile.path.startsWith(root.path + File.separator)
            }.getOrDefault(false)
        }
    }
}
