package com.amresalehin.emregallery.ai

import android.content.Context

enum class AiProviderType(
    val id: String,
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val requiresApiKey: Boolean
) {
    ON_DEVICE_VLM(
        id = "on_device_vlm",
        label = "On-Device VLM (MediaPipe / LiteRT-LM)",
        defaultBaseUrl = "",
        defaultModel = "gemma-3n-E2B",
        requiresApiKey = false
    ),
    OLLAMA(
        id = "ollama",
        label = "Ollama (Self-Hosted Vision LLM)",
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
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ON_DEVICE_VLM
        }
    }
}

data class AiConfig(
    val provider: AiProviderType = AiProviderType.ON_DEVICE_VLM,
    val localVlmEngineId: String = LocalVlmRegistry.ENGINE_MEDIAPIPE,
    val localVlmModelPath: String = "",
    val localVlmDownloadUrl: String = "",
    val hfToken: String = "",
    val vlmUseGpu: Boolean = true,
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelName: String = "gemma-3n-E2B",
    val includeOcrContext: Boolean = true,
    val excludeLockedVaultFromCloud: Boolean = true,
    val batchUseCloudApi: Boolean = true
) {
    val isRemoteConfigured: Boolean
        get() {
            if (provider == AiProviderType.ON_DEVICE_VLM) return false
            if (baseUrl.isBlank() || modelName.isBlank()) return false
            if (provider.requiresApiKey && apiKey.isBlank()) return false
            return true
        }
}

/**
 * SharedPreferences-backed configuration for Emre Gallery's dual-system AI architecture:
 * - System 1: CLIP ONNX semantic embeddings + dominant colors
 * - System 2: Real VLM (on-device MediaPipe/LiteRT-LM or optional Ollama/Cloud Vision API)
 */
object AiSettings {
    private const val PREFS_NAME = "emre_ai_settings"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_LOCAL_VLM_ENGINE = "local_vlm_engine"
    private const val KEY_LOCAL_VLM_PATH = "local_vlm_model_path"
    private const val KEY_LOCAL_VLM_URL = "local_vlm_download_url"
    private const val KEY_HF_TOKEN = "hf_token"
    private const val KEY_VLM_USE_GPU = "vlm_use_gpu"
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
        val provider = AiProviderType.fromId(p.getString(KEY_PROVIDER, AiProviderType.ON_DEVICE_VLM.id))
        return AiConfig(
            provider = provider,
            localVlmEngineId = p.getString(KEY_LOCAL_VLM_ENGINE, LocalVlmRegistry.ENGINE_MEDIAPIPE)
                ?: LocalVlmRegistry.ENGINE_MEDIAPIPE,
            localVlmModelPath = p.getString(KEY_LOCAL_VLM_PATH, "") ?: "",
            localVlmDownloadUrl = p.getString(KEY_LOCAL_VLM_URL, "") ?: "",
            hfToken = p.getString(KEY_HF_TOKEN, "") ?: "",
            vlmUseGpu = p.getBoolean(KEY_VLM_USE_GPU, true),
            baseUrl = p.getString(KEY_BASE_URL, null) ?: provider.defaultBaseUrl,
            apiKey = p.getString(KEY_API_KEY, "") ?: "",
            modelName = p.getString(KEY_MODEL_NAME, null) ?: provider.defaultModel,
            includeOcrContext = p.getBoolean(KEY_INCLUDE_OCR, true),
            excludeLockedVaultFromCloud = p.getBoolean(KEY_EXCLUDE_VAULT, true),
            batchUseCloudApi = p.getBoolean(KEY_BATCH_USE_CLOUD, true)
        )
    }

    fun save(context: Context, config: AiConfig) {
        prefs(context).edit()
            .putString(KEY_PROVIDER, config.provider.id)
            .putString(KEY_LOCAL_VLM_ENGINE, config.localVlmEngineId)
            .putString(KEY_LOCAL_VLM_PATH, config.localVlmModelPath.trim())
            .putString(KEY_LOCAL_VLM_URL, config.localVlmDownloadUrl.trim())
            .putString(KEY_HF_TOKEN, config.hfToken.trim())
            .putBoolean(KEY_VLM_USE_GPU, config.vlmUseGpu)
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putString(KEY_MODEL_NAME, config.modelName.trim())
            .putBoolean(KEY_INCLUDE_OCR, config.includeOcrContext)
            .putBoolean(KEY_EXCLUDE_VAULT, config.excludeLockedVaultFromCloud)
            .putBoolean(KEY_BATCH_USE_CLOUD, config.batchUseCloudApi)
            .apply()
    }
}
