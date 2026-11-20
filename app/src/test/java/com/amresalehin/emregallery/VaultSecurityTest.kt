package com.amresalehin.emregallery

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.data.BrowserSession
import com.amresalehin.emregallery.data.LockedVaultManager
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.data.VaultCrypto
import com.amresalehin.emregallery.data.galleryDataStore
import com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager
import com.amresalehin.emregallery.data.preferences.RedditDlConfig
import com.amresalehin.emregallery.data.preferences.VaultPreferencesManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config
class VaultSecurityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "locked_vault").deleteRecursively()
        context.getSharedPreferences(DownloaderPreferencesManager.SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        runBlocking {
            context.galleryDataStore.edit { it.clear() }
        }
    }

    @Test
    fun vaultCrypto_encryptsAndDecryptsBytesAndStrings() {
        val secret = "reddit_client_secret_987654"
        val encryptedStr = VaultCrypto.encryptStringToBase64(secret)
        assertTrue(encryptedStr.startsWith("enc_v1:"))
        assertFalse(encryptedStr.contains(secret))
        assertEquals(secret, VaultCrypto.decryptStringFromBase64(encryptedStr))

        // Legacy plaintext strings still decode cleanly for migration compatibility
        assertEquals("legacy_plain_token", VaultCrypto.decryptStringFromBase64("legacy_plain_token"))

        val bytes = "Vault Metadata Payload".toByteArray(Charsets.UTF_8)
        val blob = VaultCrypto.encryptBytes(bytes)
        assertFalse(bytes.contentEquals(blob))
        assertArrayEquals(bytes, VaultCrypto.decryptBytes(blob))
    }

    @Test
    fun vaultCrypto_chunkedStreamAndRandomAccessAcrossChunkBoundaries() {
        // 600 KiB payload (> 2 chunks of 256 KiB each)
        val payload = Random(42).nextBytes(600 * 1024 + 137)
        val encFile = File(context.cacheDir, "test_vault_media.enc").apply { delete() }

        VaultCrypto.encryptStreamToFile(ByteArrayInputStream(payload), encFile)
        assertTrue(encFile.exists())
        assertTrue(VaultCrypto.isEncryptedVaultFile(encFile))
        assertEquals(payload.size.toLong(), VaultCrypto.getVaultFilePlaintextLength(encFile))

        // Full sequential stream decryption
        val decryptedAll = VaultCrypto.openDecryptedInputStream(encFile).use { it.readBytes() }
        assertArrayEquals(payload, decryptedAll)

        // Random-access seek spanning across chunk 0 -> chunk 1 boundary (256 KiB = 262144)
        VaultCrypto.openChunkReader(encFile)!!.use { reader ->
            val crossBoundaryOffset = (VaultCrypto.DEFAULT_CHUNK_SIZE - 512).toLong()
            val sliceLen = 2048
            val actualSlice = ByteArray(sliceLen)
            val n = reader.readAt(crossBoundaryOffset, actualSlice, 0, sliceLen)
            assertEquals(sliceLen, n)
            assertArrayEquals(
                payload.copyOfRange(crossBoundaryOffset.toInt(), crossBoundaryOffset.toInt() + sliceLen),
                actualSlice
            )

            // Tail read past final chunk boundary
            val tailOffset = (payload.size - 100).toLong()
            val tailBuf = ByteArray(256)
            val tailRead = reader.readAt(tailOffset, tailBuf, 0, tailBuf.size)
            assertEquals(100, tailRead)
            assertArrayEquals(
                payload.copyOfRange(payload.size - 100, payload.size),
                tailBuf.copyOfRange(0, 100)
            )
        }

        encFile.delete()
    }

    @Test
    fun vaultPreferences_pbkdf2PinUpgradeAndBruteForceLockout() = runBlocking {
        val prefs = VaultPreferencesManager(context)

        // 1. New PIN stored with salted PBKDF2
        prefs.setLockedFolderPin("123456")
        val storedHash = prefs.lockedFolderPinHash.first()
        assertNotNull(storedHash)
        assertTrue(storedHash!!.startsWith("pbkdf2$100000$"))
        assertTrue(prefs.verifyLockedFolderPin("123456"))
        assertFalse(prefs.verifyLockedFolderPin("000000"))

        // Reset attempts after a single failure check
        assertTrue(prefs.verifyLockedFolderPin("123456"))

        // 2. Legacy salted SHA-256 hash verifies and auto-upgrades to PBKDF2
        val md = MessageDigest.getInstance("SHA-256")
        val legacyDigest = md.digest(("EmreGallerySecureSalt2026" + "654321").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        context.galleryDataStore.edit {
            it[stringPreferencesKey("locked_folder_pin_hash")] = legacyDigest
        }
        assertTrue(prefs.verifyLockedFolderPin("654321"))
        val upgradedHash = prefs.lockedFolderPinHash.first()
        assertNotNull(upgradedHash)
        assertTrue(upgradedHash!!.startsWith("pbkdf2$"))

        // 3. 5 consecutive wrong guesses trigger lockout blocking even the correct PIN
        repeat(5) {
            assertFalse(prefs.verifyLockedFolderPin("999999"))
        }
        val lockoutUntil = prefs.lockedFolderLockoutUntilMs.first()
        assertTrue(lockoutUntil > System.currentTimeMillis())
        assertFalse(prefs.verifyLockedFolderPin("654321"))

        // Explicit reset clears lockout cleanly
        prefs.resetLockedFolderLockout()
        assertEquals(0L, prefs.lockedFolderLockoutUntilMs.first())
        assertTrue(prefs.verifyLockedFolderPin("654321"))
    }

    @Test
    fun downloaderPreferences_encryptsRedditSecretsAtRest() = runBlocking {
        val mgr = DownloaderPreferencesManager(context)
        mgr.setRedditConfig(
            RedditDlConfig(
                clientId = "cid_123",
                clientSecret = "super_secret_value",
                username = "emre_user",
                password = "super_secret_password",
                refreshToken = "refresh_tok_xyz"
            )
        )

        val sp = context.getSharedPreferences(DownloaderPreferencesManager.SYNC_PREFS_NAME, Context.MODE_PRIVATE)
        val rawSecret = sp.getString("reddit_dl_client_secret", "") ?: ""
        val rawPass = sp.getString("reddit_dl_password", "") ?: ""
        val rawToken = sp.getString("reddit_dl_refresh_token", "") ?: ""

        assertTrue(rawSecret.startsWith("enc_v1:"))
        assertTrue(rawPass.startsWith("enc_v1:"))
        assertTrue(rawToken.startsWith("enc_v1:"))
        assertNotEquals("super_secret_value", rawSecret)
        assertNotEquals("super_secret_password", rawPass)
        assertNotEquals("refresh_tok_xyz", rawToken)

        val loaded = DownloaderPreferencesManager.getRedditConfigSync(context)
        assertEquals("super_secret_value", loaded.clientSecret)
        assertEquals("super_secret_password", loaded.password)
        assertEquals("refresh_tok_xyz", loaded.refreshToken)
    }

    @Test
    fun lockedVaultManager_encryptsLockedFilesAndMetadataAndUnlocksPlaintext() {
        runBlocking {
            val srcFile = File(context.cacheDir, "sample_photo.jpg").apply {
                writeBytes("JPEG_PLAIN_HEADER_CONTENT_123456".toByteArray(Charsets.UTF_8))
            }
            val vaultManager = LockedVaultManager(context)
            val item = MediaItem(
                id = 42L,
                uri = Uri.fromFile(srcFile).toString(),
                name = "sample_photo.jpg",
                mimeType = "image/jpeg",
                dateAddedSeconds = 1700000000L,
                dateTakenMillis = 1700000000000L,
                sizeBytes = srcFile.length(),
                width = 100,
                height = 100,
                relativePath = "DCIM",
                isVideo = false
            )

            val lockResult = vaultManager.lockItemsWithPendingDeletes(listOf(item))
            assertEquals(1, lockResult.lockedItems.size)
            val lockedItem = lockResult.lockedItems.first()
            assertTrue(LockedVaultManager.isVaultFileUri(context, lockedItem.uri))

            val vaultFile = File(Uri.parse(lockedItem.uri).path!!)
            assertTrue(vaultFile.exists())
            assertTrue(VaultCrypto.isEncryptedVaultFile(vaultFile))
            assertFalse(vaultFile.readBytes().decodeToString().contains("JPEG_PLAIN_HEADER_CONTENT"))

            // Plaintext vault_metadata.json must NOT exist on disk; only .enc
            val vaultDir = File(context.filesDir, "locked_vault")
            assertFalse(File(vaultDir, "vault_metadata.json").exists())
            assertTrue(File(vaultDir, "vault_metadata.json.enc").exists())

            val reloaded = vaultManager.getVaultItems()
            assertEquals(1, reloaded.size)
            assertEquals(item.name, reloaded.first().name)
            assertEquals(32L, reloaded.first().sizeBytes)

            srcFile.delete()
        }
    }

    @Test
    fun jsBridgeValidation() {
        val captured = mutableListOf<String>()
        val bridge = BrowserSession.EmreMediaBridge { captured.add(it) }
        bridge.reportMedia("javascript:alert(1)")
        bridge.reportMedia("file:///etc/passwd")
        bridge.reportMedia("content://com.android.contacts/contacts")
        bridge.reportMedia("   ")
        bridge.reportMedia("https://cdn.example.com/video.mp4")
        bridge.reportMedia("data:image/png;base64,iVBORw0KGgo=")

        assertEquals(2, captured.size)
        assertEquals("https://cdn.example.com/video.mp4", captured[0])
        assertEquals("data:image/png;base64,iVBORw0KGgo=", captured[1])
    }
}
