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

/**
 * Manages secure credentials, locked folder PIN hash, brute-force lockout, and biometric toggles.
 */
class VaultPreferencesManager(private val context: Context) {

    private val lockedFolderPinKey = stringPreferencesKey("locked_folder_pin_hash")
    private val lockedFolderBiometricKey = booleanPreferencesKey("locked_folder_biometric_enabled")
    private val failedAttemptsKey = intPreferencesKey("locked_folder_failed_attempts")
    private val lockoutUntilKey = longPreferencesKey("locked_folder_lockout_until_ms")

    val lockedFolderPinHash: Flow<String?> = context.galleryDataStore.data.map {
        it[lockedFolderPinKey]
    }.distinctUntilChanged()

    val lockedFolderBiometricEnabled: Flow<Boolean> = context.galleryDataStore.data.map {
        it[lockedFolderBiometricKey] ?: true
    }.distinctUntilChanged()

    val lockedFolderLockoutUntilMs: Flow<Long> = context.galleryDataStore.data.map {
        it[lockoutUntilKey] ?: 0L
    }.distinctUntilChanged()

    suspend fun setLockedFolderPin(pin: String) {
        val hash = hashPinPbkdf2(pin.trim())
        context.galleryDataStore.edit { prefs ->
            prefs[lockedFolderPinKey] = hash
            prefs[failedAttemptsKey] = 0
            prefs[lockoutUntilKey] = 0L
        }
    }

    suspend fun verifyLockedFolderPin(pin: String): Boolean {
        val now = System.currentTimeMillis()
        val prefsSnapshot = context.galleryDataStore.data.first()
        val lockoutUntil = prefsSnapshot[lockoutUntilKey] ?: 0L
        if (now < lockoutUntil) {
            return false
        }

        val stored = prefsSnapshot[lockedFolderPinKey] ?: return false
        val cleanPin = pin.trim()
        var legacyUpgrade = false

        val matched = if (stored.startsWith(PBKDF2_PREFIX)) {
            verifyPbkdf2(cleanPin, stored)
        } else {
            val legacyMatch = MessageDigest.isEqual(
                stored.toByteArray(Charsets.UTF_8),
                hashPinLegacy(cleanPin).toByteArray(Charsets.UTF_8)
            )
            legacyUpgrade = legacyMatch
            legacyMatch
        }

        if (matched) {
            val upgradedHash = if (legacyUpgrade) hashPinPbkdf2(cleanPin) else null
            context.galleryDataStore.edit { prefs ->
                if (upgradedHash != null) {
                    prefs[lockedFolderPinKey] = upgradedHash
                }
                prefs[failedAttemptsKey] = 0
                prefs[lockoutUntilKey] = 0L
            }
            return true
        } else {
            context.galleryDataStore.edit { prefs ->
                val failures = (prefs[failedAttemptsKey] ?: 0) + 1
                prefs[failedAttemptsKey] = failures
                if (failures >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
                    val tier = (failures - MAX_ATTEMPTS_BEFORE_LOCKOUT).coerceAtLeast(0)
                    val lockoutDurationMs = when (tier) {
                        0 -> 30_000L
                        1 -> 60_000L
                        2 -> 120_000L
                        else -> 300_000L
                    }
                    prefs[lockoutUntilKey] = now + lockoutDurationMs
                }
            }
            return false
        }
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
        context.galleryDataStore.edit { prefs ->
            prefs[lockedFolderBiometricKey] = enabled
        }
    }

    private fun hashPinPbkdf2(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val derived = pbkdf2(pin, salt, PBKDF2_ITERATIONS)
        return "$PBKDF2_PREFIX$PBKDF2_ITERATIONS$${salt.toHex()}$${derived.toHex()}"
    }

    private fun verifyPbkdf2(pin: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 10_000..1_000_000 } ?: return false
        val salt = hexToBytes(parts[2]) ?: return false
        val expectedHash = hexToBytes(parts[3]) ?: return false
        val actualHash = pbkdf2(pin, salt, iterations)
        return MessageDigest.isEqual(actualHash, expectedHash)
    }

    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun hashPinLegacy(pin: String): String {
        val salt = "EmreGallerySecureSalt2026"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest((salt + pin).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return runCatching {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    companion object {
        private const val PBKDF2_PREFIX = "pbkdf2$"
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 100_000
        private const val SALT_BYTES = 16
        private const val KEY_LENGTH_BITS = 256
        private const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 5
    }
}
