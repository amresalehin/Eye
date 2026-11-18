package com.amresalehin.emregallery.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hardware-backed (AndroidKeyStore) AES-256-GCM cryptography for:
 * 1. Small metadata/credential blobs (vault metadata JSON, Reddit OAuth/password secrets).
 * 2. Large media files using the seekable chunked "EMREVLT1" format so Coil and
 *    Media3 ExoPlayer can stream & seek encrypted photos/videos without plaintext temp files.
 */
object VaultCrypto {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "emre_gallery_vault_master_v1"
    private const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val BLOB_VERSION_1: Byte = 1
    private const val ENC_STRING_PREFIX = "enc_v1:"

    // Seekable chunked vault file format ("EMREVLT1"):
    // [0..7]   8B magic "EMREVLT1"
    // [8..11]  4B int big-endian chunkSize (256 KiB)
    // [12..19] 8B long big-endian plaintextLength
    // [20..31] 12B baseNonce
    // Followed by chunks k=0..N-1 of ciphertext+16B GCM tag, encrypted with:
    //   nonce_k = baseNonce XOR bigEndian32(k) on bytes [8..11], AAD = bigEndian32(k)
    val FILE_MAGIC: ByteArray = "EMREVLT1".toByteArray(Charsets.US_ASCII)
    const val HEADER_SIZE = 32
    const val DEFAULT_CHUNK_SIZE = 256 * 1024

    private val secureRandom = SecureRandom()
    private val keyLock = Any()

    @Volatile
    private var jvmFallbackKey: SecretKey? = null

    private fun getOrCreateMasterKey(): SecretKey {
        synchronized(keyLock) {
            try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
                keyStore.load(null)
                val existing = (keyStore.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                if (existing != null) {
                    return existing
                }
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(false)
                    .build()
                keyGen.init(spec)
                return keyGen.generateKey()
            } catch (_: Throwable) {
                // Only hit when AndroidKeyStore is absent (e.g., plain JVM / Robolectric host tests).
                jvmFallbackKey?.let { return it }
                val raw = ByteArray(32)
                secureRandom.nextBytes(raw)
                return SecretKeySpec(raw, "AES").also { jvmFallbackKey = it }
            }
        }
    }

    fun encryptBytes(plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        val encrypted = cipher.doFinal(plaintext)
        val out = ByteArray(1 + NONCE_BYTES + encrypted.size)
        out[0] = BLOB_VERSION_1
        System.arraycopy(nonce, 0, out, 1, NONCE_BYTES)
        System.arraycopy(encrypted, 0, out, 1 + NONCE_BYTES, encrypted.size)
        return out
    }

    fun decryptBytes(blob: ByteArray, aad: ByteArray? = null): ByteArray {
        if (blob.size < 1 + NONCE_BYTES + GCM_TAG_BYTES) {
            throw IOException("Encrypted blob too short")
        }
        if (blob[0] != BLOB_VERSION_1) {
            throw IOException("Unsupported encrypted blob version: ${blob[0]}")
        }
        val nonce = blob.copyOfRange(1, 1 + NONCE_BYTES)
        val ciphertext = blob.copyOfRange(1 + NONCE_BYTES, blob.size)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(ciphertext)
    }

    fun encryptStringToBase64(value: String): String {
        if (value.isEmpty()) return ""
        val encrypted = encryptBytes(value.toByteArray(Charsets.UTF_8))
        return ENC_STRING_PREFIX + Base64.getEncoder().encodeToString(encrypted)
    }

    fun decryptStringFromBase64(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(ENC_STRING_PREFIX)) {
            // Legacy plaintext value prior to encryption migration
            return stored
        }
        return try {
            val raw = Base64.getDecoder().decode(stored.removePrefix(ENC_STRING_PREFIX))
            String(decryptBytes(raw), Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    fun isEncryptedVaultFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < HEADER_SIZE) return false
        return try {
            FileInputStream(file).use { fis ->
                val magic = ByteArray(FILE_MAGIC.size)
                var readTotal = 0
                while (readTotal < magic.size) {
                    val r = fis.read(magic, readTotal, magic.size - readTotal)
                    if (r <= 0) return false
                    readTotal += r
                }
                magic.contentEquals(FILE_MAGIC)
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun getVaultFilePlaintextLength(file: File): Long {
        if (!file.exists()) return 0L
        if (!isEncryptedVaultFile(file)) return file.length()
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(HEADER_SIZE)
                raf.readFully(header)
                parseHeader(header).plaintextLength
            }
        } catch (_: Throwable) {
            file.length()
        }
    }

    fun encryptStreamToFile(input: InputStream, destFile: File) {
        destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val tempFile = File(destFile.parentFile, "${destFile.name}.enc.tmp")
        tempFile.delete()
        val baseNonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
        val key = getOrCreateMasterKey()
        val buffer = ByteArray(DEFAULT_CHUNK_SIZE)
        var totalPlaintext = 0L
        var chunkIndex = 0

        try {
            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.setLength(0L)
                // Write placeholder header; plaintextLength is back-filled before closing.
                raf.write(buildHeader(DEFAULT_CHUNK_SIZE, 0L, baseNonce))

                val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
                while (true) {
                    var filled = 0
                    while (filled < DEFAULT_CHUNK_SIZE) {
                        val n = input.read(buffer, filled, DEFAULT_CHUNK_SIZE - filled)
                        if (n == -1) break
                        filled += n
                    }
                    if (filled <= 0) break

                    val chunkNonce = deriveChunkNonce(baseNonce, chunkIndex)
                    val aad = intToBigEndian(chunkIndex)
                    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, chunkNonce))
                    cipher.updateAAD(aad)
                    val encryptedChunk = cipher.doFinal(buffer, 0, filled)
                    raf.write(encryptedChunk)

                    totalPlaintext += filled
                    chunkIndex++
                }

                // Back-fill exact plaintextLength at header byte offset 12
                raf.seek(12L)
                val lenBytes = ByteBuffer.allocate(8).putLong(totalPlaintext).array()
                raf.write(lenBytes)
                raf.fd.sync()
            }

            if (destFile.exists()) {
                destFile.delete()
            }
            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }
    }

    fun openChunkReader(file: File): VaultChunkReader? {
        if (!isEncryptedVaultFile(file)) return null
        return VaultChunkReader(file, getOrCreateMasterKey())
    }

    fun openDecryptedInputStream(file: File): InputStream {
        val reader = openChunkReader(file) ?: return FileInputStream(file)
        return VaultDecryptedInputStream(reader)
    }

    private data class VaultHeader(
        val chunkSize: Int,
        val plaintextLength: Long,
        val baseNonce: ByteArray
    )

    private fun buildHeader(chunkSize: Int, plaintextLength: Long, baseNonce: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE)
        buf.put(FILE_MAGIC)
        buf.putInt(chunkSize)
        buf.putLong(plaintextLength)
        buf.put(baseNonce)
        return buf.array()
    }

    private fun parseHeader(header: ByteArray): VaultHeader {
        if (header.size < HEADER_SIZE) throw IOException("Invalid vault header size")
        for (i in FILE_MAGIC.indices) {
            if (header[i] != FILE_MAGIC[i]) throw IOException("Invalid vault file magic")
        }
        val buf = ByteBuffer.wrap(header)
        buf.position(FILE_MAGIC.size)
        val chunkSize = buf.int
        val plaintextLength = buf.long
        if (chunkSize <= 0 || chunkSize > 8 * 1024 * 1024 || plaintextLength < 0L) {
            throw IOException("Corrupt vault file header metadata")
        }
        val baseNonce = ByteArray(NONCE_BYTES)
        buf.get(baseNonce)
        return VaultHeader(chunkSize = chunkSize, plaintextLength = plaintextLength, baseNonce = baseNonce)
    }

    private fun deriveChunkNonce(baseNonce: ByteArray, chunkIndex: Int): ByteArray {
        val nonce = baseNonce.copyOf()
        nonce[8] = (nonce[8].toInt() xor ((chunkIndex ushr 24) and 0xFF)).toByte()
        nonce[9] = (nonce[9].toInt() xor ((chunkIndex ushr 16) and 0xFF)).toByte()
        nonce[10] = (nonce[10].toInt() xor ((chunkIndex ushr 8) and 0xFF)).toByte()
        nonce[11] = (nonce[11].toInt() xor (chunkIndex and 0xFF)).toByte()
        return nonce
    }

    private fun intToBigEndian(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )

    /**
     * Thread-safe random-access chunk decryptor for encrypted vault files.
     * Caches the most recently decrypted 256 KiB chunk to keep sequential reads & media probing fast.
     */
    class VaultChunkReader internal constructor(
        file: File,
        private val key: SecretKey
    ) : java.io.Closeable {

        private val raf = RandomAccessFile(file, "r")
        private val header: VaultHeader
        private val cipherStride: Long
        private var cachedChunkIdx: Int = -1
        private var cachedChunkData: ByteArray = ByteArray(0)
        private val lock = Any()
        @Volatile
        private var closed = false

        val plaintextLength: Long

        init {
            val hdrBytes = ByteArray(HEADER_SIZE)
            raf.readFully(hdrBytes)
            header = parseHeader(hdrBytes)
            plaintextLength = header.plaintextLength
            cipherStride = header.chunkSize.toLong() + GCM_TAG_BYTES.toLong()
        }

        fun readAt(position: Long, dest: ByteArray, offset: Int, length: Int): Int {
            if (closed) throw IOException("VaultChunkReader closed")
            if (length <= 0) return 0
            if (position < 0L || position >= plaintextLength) return -1

            synchronized(lock) {
                if (closed) throw IOException("VaultChunkReader closed")
                val toRead = minOf(length.toLong(), plaintextLength - position).toInt()
                var totalRead = 0
                var currentPos = position

                while (totalRead < toRead) {
                    val chunkIdx = (currentPos / header.chunkSize).toInt()
                    val offsetInChunk = (currentPos % header.chunkSize).toInt()
                    val chunkPlain = loadOrDecryptChunkLocked(chunkIdx)
                    if (offsetInChunk >= chunkPlain.size) break
                    val avail = minOf(chunkPlain.size - offsetInChunk, toRead - totalRead)
                    System.arraycopy(chunkPlain, offsetInChunk, dest, offset + totalRead, avail)
                    totalRead += avail
                    currentPos += avail
                }
                return if (totalRead == 0) -1 else totalRead
            }
        }

        private fun loadOrDecryptChunkLocked(chunkIdx: Int): ByteArray {
            if (cachedChunkIdx == chunkIdx) return cachedChunkData

            val chunkStartPlain = chunkIdx.toLong() * header.chunkSize.toLong()
            val expectedPlainLen = minOf(header.chunkSize.toLong(), plaintextLength - chunkStartPlain).toInt()
            if (expectedPlainLen <= 0) {
                throw EOFException("Requested chunk $chunkIdx past plaintext EOF")
            }

            val encryptedLen = expectedPlainLen + GCM_TAG_BYTES
            val fileOffset = HEADER_SIZE.toLong() + chunkIdx.toLong() * cipherStride
            val cipherBytes = ByteArray(encryptedLen)
            raf.seek(fileOffset)
            raf.readFully(cipherBytes)

            val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, deriveChunkNonce(header.baseNonce, chunkIdx))
            )
            cipher.updateAAD(intToBigEndian(chunkIdx))
            val plain = cipher.doFinal(cipherBytes)
            cachedChunkIdx = chunkIdx
            cachedChunkData = plain
            return plain
        }

        override fun close() {
            synchronized(lock) {
                if (!closed) {
                    closed = true
                    cachedChunkIdx = -1
                    cachedChunkData = ByteArray(0)
                    runCatching { raf.close() }
                }
            }
        }
    }

    private class VaultDecryptedInputStream(
        private val reader: VaultChunkReader
    ) : InputStream() {
        private var pos = 0L
        private val singleByte = ByteArray(1)

        override fun read(): Int {
            val n = read(singleByte, 0, 1)
            return if (n <= 0) -1 else (singleByte[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
            if (len == 0) return 0
            val n = reader.readAt(pos, b, off, len)
            if (n > 0) pos += n
            return n
        }

        override fun skip(n: Long): Long {
            if (n <= 0L) return 0L
            val remaining = (reader.plaintextLength - pos).coerceAtLeast(0L)
            val skipped = minOf(n, remaining)
            pos += skipped
            return skipped
        }

        override fun available(): Int {
            return (reader.plaintextLength - pos).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }

        override fun close() {
            reader.close()
        }
    }
}
