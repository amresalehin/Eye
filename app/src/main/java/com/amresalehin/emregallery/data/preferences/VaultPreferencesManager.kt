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
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN storage and brute-force throttling for the Locked Folder. */
class VaultPreferencesManager(private val context: Context) {

    private val lockedFolderPinKey = stringPreferencesKey("locked_folder_pin_hash")
    private val lockedFolderBiometricKey = booleanPreferencesKey("locked_folder_biometric_enabled")
    private val failedAttemptsKey = intPreferencesKey("locked_folder_failed_attempts")
    private val lockoutUntilKey = longPreferencesKey("locked_folder_lockout_until_ms")

    val lockedFolderPinHash: Flow<String?> = context.galleryDataStore.data
        .map { it[lockedFolderPinKey] }.distinctUntilChanged()

    val lockedFolderBiometricEnabled: Flow<Boolean> = context.galleryDataStore.data
        .map { it[lockedFolderBiometricKey] ?: true }.distinctUntilChanged()

    val lockedFolderLockoutUntilMs: Flow<Long> = context.galleryDataStore.data
        .map { it[lockoutUntilKey] ?: 0L }.distinctUntilChanged()

    suspend fun setLockedFolderPin(pin: String) {
        require(pin.isNotBlank()) { "PIN must not be blank" }
        val encoded = createPbkdf2Hash(pin.trim())
        context.galleryDataStore.edit { prefs ->
            prefs[lockedFolderPinKey] = encoded
            prefs[failedAttemptsKey] = 0
            prefs[lockoutUntilKey] = 0L
        }
    }

    suspend fun verifyLockedFolderPin(pin: String): Boolean {
        val now = System.currentTimeMillis()
        val snapshot = context.galleryDataStore.data.first()
        val lockoutUntil = snapshot[lockoutUntilKey] ?: 0L
        if (lockoutUntil > now) return false

        val stored = snapshot[lockedFolderPinKey] ?: return false
        val candidate = pin.trim()
        val valid = if (stored.startsWith(PBKDF2_PREFIX)) {
            verifyPbkdf2(candidate, stored)
        } else {
            verifyLegacy(candidate, stored)
        }

        if (valid) {
            context.galleryDataStore.edit { prefs ->
                // Upgrade legacy hashes after successful authentication.
                if (!stored.startsWith(PBKDF2_PREFIX)) {
                    prefs[lockedFolderPinKey] = createPbkdf2Hash(candidate)
                }
                prefs[failedAttemptsKey] = 0
                prefs[lockoutUntilKey] = 0L
            }
            return true
        }

        context.galleryDataStore.edit { prefs ->
            val failures = (prefs[failedAttemptsKey] ?: 0) + 1
            prefs[failedAttemptsKey] = failures
            if (failures >= MAX_FAILED_ATTEMPTS) {
                prefs[lockoutUntilKey] = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                prefs[failedAttemptsKey] = 0
            }
        }
        return false
    }

    suspend fun resetLockedFolderLockout() {
        context.galleryDataStore.edit { prefs ->
            prefs[failedAttemptsKey] = 0
            prefs[lockoutUntilKey] = 0L
        }
    }

    suspend fun clearLockedFolderPin() {
        context.galleryDataStore.edit { prefs ->
            prefs.remove(lockedFolderPinKey)
            prefs[failedAttemptsKey] = 0
            prefs[lockoutUntilKey] = 0L
        }
    }

    suspend fun setLockedFolderBiometricEnabled(enabled: Boolean) {
        context.galleryDataStore.edit { prefs -> prefs[lockedFolderBiometricKey] = enabled }
    }

    private fun createPbkdf2Hash(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = derive(pin, salt, PBKDF2_ITERATIONS)
        return "$PBKDF2_PREFIX$PBKDF2_ITERATIONS$${b64(salt)}$${b64(derived)}"
    }

    private fun verifyPbkdf2(pin: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations !in MIN_ACCEPTED_ITERATIONS..MAX_ACCEPTED_ITERATIONS) return false
        val salt = runCatching { android.util.Base64.decode(parts[2], android.util.Base64.NO_WRAP) }.getOrNull() ?: return false
        val expected = runCatching { android.util.Base64.decode(parts[3], android.util.Base64.NO_WRAP) }.getOrNull() ?: return false
        if (salt.size != SALT_BYTES || expected.size != HASH_BYTES) return false
        val actual = derive(pin, salt, iterations)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun verifyLegacy(pin: String, stored: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((LEGACY_SALT + pin).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(
            digest.toByteArray(Charsets.US_ASCII),
            stored.lowercase().toByteArray(Charsets.US_ASCII)
        )
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, HASH_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(value: ByteArray): String =
        android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)

    companion object {
        private const val PBKDF2_PREFIX = "pbkdf2"
        private const val PBKDF2_ITERATIONS = 100_000
        private const val MIN_ACCEPTED_ITERATIONS = 50_000
        private const val MAX_ACCEPTED_ITERATIONS = 1_000_000
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30_000L
        private const val LEGACY_SALT = "EmreGallerySecureSalt2026"
        private val random = SecureRandom()
    }
}
