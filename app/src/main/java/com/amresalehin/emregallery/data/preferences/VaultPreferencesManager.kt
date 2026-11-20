package com.amresalehin.emregallery.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.amresalehin.emregallery.data.galleryDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Manages secure credentials, locked folder PIN hash (PBKDF2-HMAC-SHA256 with brute-force lockout),
 * and biometric toggles.
 */
class VaultPreferencesManager(private val context: Context) {

    private val lockedFolderPinKey = stringPreferencesKey("locked_folder_pin_hash")
    private val lockedFolderBiometricKey = booleanPreferencesKey("locked_folder_biometric_enabled")
    private val failedAttemptsKey = intPreferencesKey("locked_folder_failed_attempts")
    private val lockoutUntilMsKey = longPreferencesKey("locked_folder_lockout_until_ms")

    val lockedFolderPinHash: Flow<String?> = context.galleryDataStore.data.map {
        it[lockedFolderPinKey]
    }.distinctUntilChanged()

    val lockedFolderBiometricEnabled: Flow<Boolean> = context.galleryDataStore.data.map {
        it[lockedFolderBiometricKey] ?: true
    }.distinctUntilChanged()

    val lockedFolderLockoutUntilMs: Flow<Long> = context.galleryDataStore.data.map {
        it[lockoutUntilMsKey] ?: 0L
    }.distinctUntilChanged()

    suspend fun setLockedFolderPin(pin: String) {
        val hash = hashPinPbkdf2(pin.trim())
        context.galleryDataStore.edit { prefs ->
            prefs[lockedFolderPinKey] = hash
            prefs[failedAttemptsKey] = 0
            prefs[lockoutUntilMsKey] = 0L
        }
    }

    suspend fun verifyLockedFolderPin(pin: String): Boolean {
        val prefsSnapshot = context.galleryDataStore.data.first()
        val now = System.currentTimeMillis()
        val lockoutUntil = prefsSnapshot[lockoutUntilMsKey] ?: 0L
        if (lockoutUntil > now) {
            return false
        }

        val stored = prefsSnapshot[lockedFolderPinKey] ?: return false
        val trimmed = pin.trim()
        val isMatch: Boolean
        var needsUpgrade = false

        if (stored.startsWith(PBKDF2_PREFIX)) {
            isMatch = verifyPbkdf2(trimmed, stored)
        } else {
            val legacyDigest = hashPinLegacySha256(trimmed)
            isMatch = MessageDigest.isEqual(
                stored.toByteArray(Charsets.UTF_8),
                legacyDigest.toByteArray(Charsets.UTF_8)
            )
            needsUpgrade = isMatch
        }

        if (isMatch) {
            context.galleryDataStore.edit { prefs ->
                prefs[failedAttemptsKey] = 0
                prefs[lockoutUntilMsKey] = 0L
                if (needsUpgrade) {
                    prefs[lockedFolderPinKey] = hashPinPbkdf2(trimmed)
                }
            }
            return true
        } else {
            context.galleryDataStore.edit { prefs ->
                val attempts = (prefs[failedAttemptsKey] ?: 0) + 1
                if (attempts >= MAX_FAILED_ATTEMPTS) {
                    prefs[failedAttemptsKey] = 0
                    prefs[lockoutUntilMsKey] = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                } else {
                    prefs[failedAttemptsKey] = attempts
                }
            }
            return false
        }
    }

    suspend fun resetLockedFolderLockout() {
        context.galleryDataStore.edit { prefs ->
            prefs[failedAttemptsKey] = 0
            prefs[lockoutUntilMsKey] = 0L
        }
    }

    suspend fun clearLockedFolderPin() {
        context.galleryDataStore.edit { prefs ->
            prefs.remove(lockedFolderPinKey)
            prefs[failedAttemptsKey] = 0
            prefs[lockoutUntilMsKey] = 0L
        }
    }

    suspend fun setLockedFolderBiometricEnabled(enabled: Boolean) {
        context.galleryDataStore.edit { prefs ->
            prefs[lockedFolderBiometricKey] = enabled
        }
    }

    private fun hashPinPbkdf2(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val derived = derivePbkdf2(pin, salt, PBKDF2_ITERATIONS)
        val saltB64 = Base64.getEncoder().encodeToString(salt)
        val hashB64 = Base64.getEncoder().encodeToString(derived)
        return "${PBKDF2_PREFIX}${PBKDF2_ITERATIONS}$${saltB64}$${hashB64}"
    }

    private fun verifyPbkdf2(pin: String, stored: String): Boolean {
        val parts = stored.split("$")
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations <= 0) return false
        val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
        val actual = derivePbkdf2(pin, salt, iterations)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derivePbkdf2(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun hashPinLegacySha256(pin: String): String {
        val salt = "EmreGallerySecureSalt2026"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest((salt + pin).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PBKDF2_PREFIX = "pbkdf2$"
        private const val PBKDF2_ITERATIONS = 100_000
        private const val SALT_BYTES = 16
        private const val KEY_LENGTH_BITS = 256
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L
        private val secureRandom = SecureRandom()
    }
}
