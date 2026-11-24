package com.amresalehin.emregallery.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.amresalehin.emregallery.data.galleryDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AiProviderMode(
    val id: String,
    val title: String,
    val defaultBaseUrl: String,
    val description: String
) {
    OPENAI_COMPATIBLE(
        "openai_compatible",
        "BYOK API",
        "https://api.openai.com/v1",
        "Use your own API key with OpenAI or another OpenAI-compatible vision provider."
    ),
    ON_DEVICE(
        "on_device",
        "On-device",
        "",
        "Run a downloaded GGUF vision model on this phone."
    ),
    EXTERNAL_LOCAL(
        "external_local",
        "External server",
        "http://127.0.0.1:8080/v1",
        "Connect to a separately installed OpenAI-compatible vision server."
    );

    /** External local server transport; on-device inference is handled by the native runtime. */
    val isLocal: Boolean get() = this == EXTERNAL_LOCAL
    val isOnDevice: Boolean get() = this == ON_DEVICE

    companion object {
        fun fromId(id: String?): AiProviderMode =
            when (id?.lowercase()) {
                "mnn_local", "llama_cpp_local" -> EXTERNAL_LOCAL
                "on_device" -> ON_DEVICE
                else -> entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: OPENAI_COMPATIBLE
            }
    }
}

data class AiSettings(
    val providerMode: AiProviderMode = AiProviderMode.OPENAI_COMPATIBLE,
    val baseUrl: String = AiProviderMode.OPENAI_COMPATIBLE.defaultBaseUrl,
    val model: String = "gpt-4.1-mini",
    val apiKey: String = ""
) {
    val isConfigured: Boolean
        get() = if (providerMode.isOnDevice) {
            model.isNotBlank()
        } else {
            baseUrl.isNotBlank() &&
                model.isNotBlank() &&
                (providerMode.isLocal || apiKey.isNotBlank())
        }
}

/**
 * Stores provider configuration in DataStore. API credentials are encrypted with an
 * AndroidKeyStore AES-GCM key; plaintext credentials are never written to preferences.
 */
class AiSettingsRepository(private val context: Context) {
    private val modeKey = stringPreferencesKey("ai_provider_mode")
    private val baseUrlKey = stringPreferencesKey("ai_base_url")
    private val modelKey = stringPreferencesKey("ai_model")
    private val encryptedApiKey = stringPreferencesKey("ai_api_key_encrypted")

    val settings: Flow<AiSettings> = context.galleryDataStore.data.map { preferences ->
        val mode = AiProviderMode.fromId(preferences[modeKey])
        val configuredUrl = preferences[baseUrlKey].orEmpty()
        AiSettings(
            providerMode = mode,
            baseUrl = configuredUrl.ifBlank { mode.defaultBaseUrl },
            model = preferences[modelKey].orEmpty().ifBlank {
                if (mode == AiProviderMode.OPENAI_COMPATIBLE) "gpt-4.1-mini" else ""
            },
            apiKey = AiApiKeyCipher.decrypt(preferences[encryptedApiKey].orEmpty())
        )
    }.distinctUntilChanged()

    suspend fun save(settings: AiSettings) {
        val encrypted = AiApiKeyCipher.encrypt(settings.apiKey.trim())
        context.galleryDataStore.edit { preferences ->
            preferences[modeKey] = settings.providerMode.id
            preferences[baseUrlKey] = settings.baseUrl.trim().trimEnd('/')
            preferences[modelKey] = settings.model.trim()
            if (encrypted.isNullOrBlank()) {
                preferences.remove(encryptedApiKey)
            } else {
                preferences[encryptedApiKey] = encrypted
            }
        }
    }
}

private object AiApiKeyCipher {
    private const val KEY_ALIAS = "eye.ai.api-key.v1"
    private const val IV_LENGTH_BYTES = 12

    fun encrypt(value: String): String? {
        if (value.isBlank()) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        }.getOrElse { throw IllegalStateException("Could not securely store the AI API key.", it) }
    }

    fun decrypt(encoded: String): String {
        if (encoded.isBlank()) return ""
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > IV_LENGTH_BYTES) { "Invalid encrypted API key." }
            val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
            val encrypted = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
