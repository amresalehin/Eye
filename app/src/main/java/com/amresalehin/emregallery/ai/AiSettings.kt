package com.amresalehin.emregallery.ai

import android.content.Context

enum class AiProviderType(
    val id: String,
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val requiresApiKey: Boolean
) {
    ON_DEVICE_ONLY(
        id = "on_device",
        label = "On-Device Only",
        defaultBaseUrl = "",
        defaultModel = "local",
        requiresApiKey = false
    ),
    OLLAMA(
        id = "ollama",
        label = "Ollama (Local / Self-Hosted)",
        defaultBaseUrl = "http://192.168.1.100:11434/v1",
        defaultModel = "qwen2.5vl:3b",
        requiresApiKey = false
    ),
    GEMINI(
        id = "gemini",
        label = "Google Gemini (OpenAI-Compat)",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultModel = "gemini-2.5-flash",
        requiresApiKey = true
    ),
    OPENAI_COMPATIBLE(
        id = "openai_compatible",
        label = "OpenAI / OpenRouter / Custom",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        requiresApiKey = true
    );

    companion object {
        fun fromId(id: String?): AiProviderType {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ON_DEVICE_ONLY
        }
    }
}

data class AiConfig(
    val provider: AiProviderType = AiProviderType.ON_DEVICE_ONLY,
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelName: String = "local",
    val includeOcrContext: Boolean = true,
    val excludeLockedVaultFromCloud: Boolean = true,
    val batchUseCloudApi: Boolean = true
) {
    val isRemoteConfigured: Boolean
        get() {
            if (provider == AiProviderType.ON_DEVICE_ONLY) return false
            if (baseUrl.isBlank() || modelName.isBlank()) return false
            if (provider.requiresApiKey && apiKey.isBlank()) return false
            return true
        }
}

/**
 * SharedPreferences-backed configuration for Emre Gallery's hybrid AI subsystem.
 */
object AiSettings {
    private const val PREFS_NAME = "emre_ai_settings"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL_NAME = "model_name"
    private const val KEY_INCLUDE_OCR = "include_ocr_context"
    private const val KEY_EXCLUDE_VAULT = "exclude_locked_vault_from_cloud"
    private const val KEY_BATCH_USE_CLOUD = "batch_use_cloud_api"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): AiConfig {
        val p = prefs(context)
        val provider = AiProviderType.fromId(p.getString(KEY_PROVIDER, AiProviderType.ON_DEVICE_ONLY.id))
        val baseUrl = p.getString(KEY_BASE_URL, null) ?: provider.defaultBaseUrl
        val apiKey = p.getString(KEY_API_KEY, "") ?: ""
        val modelName = p.getString(KEY_MODEL_NAME, null) ?: provider.defaultModel
        val includeOcr = p.getBoolean(KEY_INCLUDE_OCR, true)
        val excludeVault = p.getBoolean(KEY_EXCLUDE_VAULT, true)
        val batchUseCloud = p.getBoolean(KEY_BATCH_USE_CLOUD, true)
        return AiConfig(
            provider = provider,
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelName = modelName,
            includeOcrContext = includeOcr,
            excludeLockedVaultFromCloud = excludeVault,
            batchUseCloudApi = batchUseCloud
        )
    }

    fun save(context: Context, config: AiConfig) {
        prefs(context).edit()
            .putString(KEY_PROVIDER, config.provider.id)
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putString(KEY_MODEL_NAME, config.modelName.trim())
            .putBoolean(KEY_INCLUDE_OCR, config.includeOcrContext)
            .putBoolean(KEY_EXCLUDE_VAULT, config.excludeLockedVaultFromCloud)
            .putBoolean(KEY_BATCH_USE_CLOUD, config.batchUseCloudApi)
            .apply()
    }
}
