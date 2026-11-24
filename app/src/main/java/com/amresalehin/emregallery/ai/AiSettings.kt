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
        label = "On-Device VLM (llama.cpp GGUF / MediaPipe)",
        defaultBaseUrl = "",
        defaultModel = "gguf-vlm",
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
    val localVlmEngineId: String = LocalVlmRegistry.ENGINE_LLAMACPP,
    // llama.cpp GGUF + mmproj settings
    val ggufModelPath: String = "",
    val ggufMmprojPath: String = "",
    val ggufModelDownloadUrl: String = "",
    val ggufMmprojDownloadUrl: String = "",
    val ggufThreads: Int = 4,
    val ggufContextLength: Int = 2048,
    // MediaPipe / LiteRT-LM .task settings
    val localVlmModelPath: String = "",
    val localVlmDownloadUrl: String = "",
    val vlmUseGpu: Boolean = true,
    // Shared auth & remote Vision API settings
    val hfToken: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelName: String = "gguf-vlm",
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
 * - System 2: Real VLM (on-device llama.cpp GGUF+mmproj, MediaPipe .task, or optional Ollama/Cloud Vision API)
 */
object AiSettings {
    private const val PREFS_NAME = "emre_ai_settings"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_CLOUD_PROVIDER = "cloud_provider"
    private const val KEY_OLLAMA_BASE_URL = "ollama_base_url"
    private const val KEY_OLLAMA_MODEL = "ollama_model"
    private const val KEY_GEMINI_BASE_URL = "gemini_base_url"
    private const val KEY_GEMINI_MODEL = "gemini_model"
    private const val KEY_GEMINI_KEY = "gemini_key"
    private const val KEY_OPENAI_BASE_URL = "openai_base_url"
    private const val KEY_OPENAI_MODEL = "openai_model"
    private const val KEY_OPENAI_KEY = "openai_key"
    private const val KEY_LOCAL_VLM_ENGINE = "local_vlm_engine"
    private const val KEY_GGUF_MODEL_PATH = "gguf_model_path"
    private const val KEY_GGUF_MMPROJ_PATH = "gguf_mmproj_path"
    private const val KEY_GGUF_MODEL_URL = "gguf_model_download_url"
    private const val KEY_GGUF_MMPROJ_URL = "gguf_mmproj_download_url"
    private const val KEY_GGUF_THREADS = "gguf_threads"
    private const val KEY_GGUF_CTX = "gguf_context_length"
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
        val cloudProvider = AiProviderType.fromId(p.getString(KEY_CLOUD_PROVIDER, AiProviderType.OLLAMA.id))
            .takeIf { it != AiProviderType.ON_DEVICE_VLM } ?: AiProviderType.OLLAMA
        val activeProvider = if (provider == AiProviderType.ON_DEVICE_VLM) provider else cloudProvider
        return AiConfig(
            provider = activeProvider,
            localVlmEngineId = p.getString(KEY_LOCAL_VLM_ENGINE, LocalVlmRegistry.ENGINE_LLAMACPP)
                ?: LocalVlmRegistry.ENGINE_LLAMACPP,
            ggufModelPath = p.getString(KEY_GGUF_MODEL_PATH, "") ?: "",
            ggufMmprojPath = p.getString(KEY_GGUF_MMPROJ_PATH, "") ?: "",
            ggufModelDownloadUrl = p.getString(KEY_GGUF_MODEL_URL, "") ?: "",
            ggufMmprojDownloadUrl = p.getString(KEY_GGUF_MMPROJ_URL, "") ?: "",
            ggufThreads = p.getInt(KEY_GGUF_THREADS, 4).coerceIn(1, 8),
            ggufContextLength = p.getInt(KEY_GGUF_CTX, 2048).coerceIn(1024, 8192),
            localVlmModelPath = p.getString(KEY_LOCAL_VLM_PATH, "") ?: "",
            localVlmDownloadUrl = p.getString(KEY_LOCAL_VLM_URL, "") ?: "",
            hfToken = p.getString(KEY_HF_TOKEN, "") ?: "",
            vlmUseGpu = p.getBoolean(KEY_VLM_USE_GPU, true),
            baseUrl = when (activeProvider) {
                AiProviderType.OLLAMA -> p.getString(KEY_OLLAMA_BASE_URL, null) ?: p.getString(KEY_BASE_URL, null) ?: activeProvider.defaultBaseUrl
                AiProviderType.GEMINI -> p.getString(KEY_GEMINI_BASE_URL, null) ?: activeProvider.defaultBaseUrl
                AiProviderType.OPENAI_COMPATIBLE -> p.getString(KEY_OPENAI_BASE_URL, null) ?: activeProvider.defaultBaseUrl
                else -> activeProvider.defaultBaseUrl
            },
            apiKey = when (activeProvider) {
                AiProviderType.GEMINI -> p.getString(KEY_GEMINI_KEY, null) ?: p.getString(KEY_API_KEY, "") ?: ""
                AiProviderType.OPENAI_COMPATIBLE -> p.getString(KEY_OPENAI_KEY, null) ?: p.getString(KEY_API_KEY, "") ?: ""
                else -> ""
            },
            modelName = when (activeProvider) {
                AiProviderType.OLLAMA -> p.getString(KEY_OLLAMA_MODEL, null) ?: p.getString(KEY_MODEL_NAME, null) ?: activeProvider.defaultModel
                AiProviderType.GEMINI -> p.getString(KEY_GEMINI_MODEL, null) ?: activeProvider.defaultModel
                AiProviderType.OPENAI_COMPATIBLE -> p.getString(KEY_OPENAI_MODEL, null) ?: activeProvider.defaultModel
                else -> activeProvider.defaultModel
            },
            includeOcrContext = p.getBoolean(KEY_INCLUDE_OCR, true),
            excludeLockedVaultFromCloud = p.getBoolean(KEY_EXCLUDE_VAULT, true),
            batchUseCloudApi = p.getBoolean(KEY_BATCH_USE_CLOUD, true)
        )
    }

    fun save(context: Context, config: AiConfig) {
        val p = prefs(context)
        val previousCloud = AiProviderType.fromId(p.getString(KEY_CLOUD_PROVIDER, AiProviderType.OLLAMA.id))
        val currentCloud = if (config.provider == AiProviderType.ON_DEVICE_VLM) previousCloud else config.provider
        val oldCloudConfig = AiConfig(
            provider = currentCloud,
            baseUrl = when (currentCloud) {
                AiProviderType.OLLAMA -> p.getString(KEY_OLLAMA_BASE_URL, null) ?: AiProviderType.OLLAMA.defaultBaseUrl
                AiProviderType.GEMINI -> p.getString(KEY_GEMINI_BASE_URL, null) ?: AiProviderType.GEMINI.defaultBaseUrl
                else -> p.getString(KEY_OPENAI_BASE_URL, null) ?: AiProviderType.OPENAI_COMPATIBLE.defaultBaseUrl
            },
            modelName = when (currentCloud) {
                AiProviderType.OLLAMA -> p.getString(KEY_OLLAMA_MODEL, null) ?: AiProviderType.OLLAMA.defaultModel
                AiProviderType.GEMINI -> p.getString(KEY_GEMINI_MODEL, null) ?: AiProviderType.GEMINI.defaultModel
                else -> p.getString(KEY_OPENAI_MODEL, null) ?: AiProviderType.OPENAI_COMPATIBLE.defaultModel
            },
            apiKey = when (currentCloud) {
                AiProviderType.GEMINI -> p.getString(KEY_GEMINI_KEY, "") ?: ""
                AiProviderType.OPENAI_COMPATIBLE -> p.getString(KEY_OPENAI_KEY, "") ?: ""
                else -> ""
            }
        )
        val editor = p.edit()
            .putString(KEY_PROVIDER, config.provider.id)
            .putString(KEY_CLOUD_PROVIDER, currentCloud.id)
            .putString(KEY_OLLAMA_BASE_URL, if (currentCloud == AiProviderType.OLLAMA) config.baseUrl.trim() else oldCloudConfig.baseUrl)
            .putString(KEY_OLLAMA_MODEL, if (currentCloud == AiProviderType.OLLAMA) config.modelName.trim() else oldCloudConfig.modelName)
            .putString(KEY_GEMINI_BASE_URL, if (currentCloud == AiProviderType.GEMINI) config.baseUrl.trim() else oldCloudConfig.baseUrl)
            .putString(KEY_GEMINI_MODEL, if (currentCloud == AiProviderType.GEMINI) config.modelName.trim() else oldCloudConfig.modelName)
            .putString(KEY_GEMINI_KEY, if (currentCloud == AiProviderType.GEMINI) config.apiKey.trim() else oldCloudConfig.apiKey)
            .putString(KEY_OPENAI_BASE_URL, if (currentCloud == AiProviderType.OPENAI_COMPATIBLE) config.baseUrl.trim() else oldCloudConfig.baseUrl)
            .putString(KEY_OPENAI_MODEL, if (currentCloud == AiProviderType.OPENAI_COMPATIBLE) config.modelName.trim() else oldCloudConfig.modelName)
            .putString(KEY_OPENAI_KEY, if (currentCloud == AiProviderType.OPENAI_COMPATIBLE) config.apiKey.trim() else oldCloudConfig.apiKey)
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putString(KEY_LOCAL_VLM_ENGINE, config.localVlmEngineId)
            .putString(KEY_GGUF_MODEL_PATH, config.ggufModelPath.trim())
            .putString(KEY_GGUF_MMPROJ_PATH, config.ggufMmprojPath.trim())
            .putString(KEY_GGUF_MODEL_URL, config.ggufModelDownloadUrl.trim())
            .putString(KEY_GGUF_MMPROJ_URL, config.ggufMmprojDownloadUrl.trim())
            .putInt(KEY_GGUF_THREADS, config.ggufThreads.coerceIn(1, 8))
            .putInt(KEY_GGUF_CTX, config.ggufContextLength.coerceIn(1024, 8192))
            .putString(KEY_LOCAL_VLM_PATH, config.localVlmModelPath.trim())
            .putString(KEY_LOCAL_VLM_URL, config.localVlmDownloadUrl.trim())
            .putString(KEY_HF_TOKEN, config.hfToken.trim())
            .putBoolean(KEY_VLM_USE_GPU, config.vlmUseGpu)
            .putBoolean(KEY_INCLUDE_OCR, config.includeOcrContext)
            .putBoolean(KEY_EXCLUDE_VAULT, config.excludeLockedVaultFromCloud)
            .putBoolean(KEY_BATCH_USE_CLOUD, config.batchUseCloudApi)
        editor.apply()
    }
}
