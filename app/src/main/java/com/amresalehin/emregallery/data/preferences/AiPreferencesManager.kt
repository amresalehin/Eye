package com.amresalehin.emregallery.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.amresalehin.emregallery.data.galleryDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class AiProvider(val id: String, val displayName: String) {
    GEMINI("gemini", "Google Gemini API"),
    OPENAI_COMPATIBLE("openai_compatible", "OpenAI-Compatible Endpoint");

    companion object {
        fun fromId(id: String?): AiProvider =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GEMINI
    }
}

data class AiConfig(
    val enabled: Boolean = true,
    val provider: AiProvider = AiProvider.GEMINI,
    val apiKey: String = "",
    val modelId: String = DEFAULT_GEMINI_MODEL,
    val customBaseUrl: String = "",
    val autoApplyTags: Boolean = true,
    val autoOrganizeCollections: Boolean = true,
    val includeOcrContext: Boolean = true,
    val customPromptInstructions: String = "",
    val batchDelayMs: Long = 500L
) {
    val isConfigured: Boolean
        get() = enabled && apiKey.isNotBlank() && modelId.isNotBlank()

    val hasApiKey: Boolean
        get() = apiKey.isNotBlank()

    /**
     * Known model capability checks. Unknown custom model IDs remain eligible for a live
     * vision test because OpenAI-compatible endpoints can expose different model families.
     */
    val visionSupportWarning: String?
        get() {
            if (provider != AiProvider.OPENAI_COMPATIBLE) return null
            val normalizedModel = modelId.substringAfterLast('/').trim().lowercase()
            return when (normalizedModel) {
                "nemotron-3.5-lightning-30b-a3b" ->
                    "$modelId is a text-only model and cannot accept image input. Choose a vision-capable model for photo analysis and visual Q&A."
                else -> null
            }
        }

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        const val DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"

        val GEMINI_PRESET_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.0-flash"
        )

        val OPENAI_PRESET_MODELS = listOf(
            "gpt-4o-mini",
            "gpt-4o",
            "gpt-4.1-mini"
        )
    }
}

/**
 * Persists user-supplied custom API key AI configuration in DataStore.
 */
class AiPreferencesManager(private val context: Context) {

    private val aiEnabledKey = booleanPreferencesKey("ai_enabled")
    private val aiProviderKey = stringPreferencesKey("ai_provider")
    private val aiApiKeyKey = stringPreferencesKey("ai_api_key")
    private val aiModelIdKey = stringPreferencesKey("ai_model_id")
    private val aiCustomBaseUrlKey = stringPreferencesKey("ai_custom_base_url")
    private val aiAutoApplyTagsKey = booleanPreferencesKey("ai_auto_apply_tags")
    private val aiAutoOrganizeCollectionsKey = booleanPreferencesKey("ai_auto_organize_collections")
    private val aiIncludeOcrContextKey = booleanPreferencesKey("ai_include_ocr_context")
    private val aiCustomPromptKey = stringPreferencesKey("ai_custom_prompt_instructions")
    private val aiBatchDelayMsKey = longPreferencesKey("ai_batch_delay_ms")

    val aiConfig: Flow<AiConfig> = context.galleryDataStore.data.map { prefs ->
        val provider = AiProvider.fromId(prefs[aiProviderKey])
        val defaultModel = if (provider == AiProvider.GEMINI) {
            AiConfig.DEFAULT_GEMINI_MODEL
        } else {
            AiConfig.DEFAULT_OPENAI_MODEL
        }
        AiConfig(
            enabled = prefs[aiEnabledKey] ?: true,
            provider = provider,
            apiKey = prefs[aiApiKeyKey]?.trim() ?: "",
            modelId = prefs[aiModelIdKey]?.trim()?.ifBlank { defaultModel } ?: defaultModel,
            customBaseUrl = prefs[aiCustomBaseUrlKey]?.trim() ?: "",
            autoApplyTags = prefs[aiAutoApplyTagsKey] ?: true,
            autoOrganizeCollections = prefs[aiAutoOrganizeCollectionsKey] ?: true,
            includeOcrContext = prefs[aiIncludeOcrContextKey] ?: true,
            customPromptInstructions = prefs[aiCustomPromptKey] ?: "",
            batchDelayMs = (prefs[aiBatchDelayMsKey] ?: 500L).coerceIn(0L, 5000L)
        )
    }.distinctUntilChanged()

    suspend fun currentConfig(): AiConfig = aiConfig.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.galleryDataStore.edit { it[aiEnabledKey] = enabled }
    }

    suspend fun setProvider(provider: AiProvider) {
        context.galleryDataStore.edit { prefs ->
            prefs[aiProviderKey] = provider.id
            val currentModel = prefs[aiModelIdKey].orEmpty()
            if (provider == AiProvider.GEMINI && (currentModel.isBlank() || currentModel in AiConfig.OPENAI_PRESET_MODELS)) {
                prefs[aiModelIdKey] = AiConfig.DEFAULT_GEMINI_MODEL
            } else if (provider == AiProvider.OPENAI_COMPATIBLE && (currentModel.isBlank() || currentModel in AiConfig.GEMINI_PRESET_MODELS)) {
                prefs[aiModelIdKey] = AiConfig.DEFAULT_OPENAI_MODEL
            }
        }
    }

    suspend fun setApiKey(apiKey: String) {
        context.galleryDataStore.edit { it[aiApiKeyKey] = apiKey.trim() }
    }

    suspend fun setModelId(modelId: String) {
        context.galleryDataStore.edit { it[aiModelIdKey] = modelId.trim() }
    }

    suspend fun setCustomBaseUrl(baseUrl: String) {
        context.galleryDataStore.edit { it[aiCustomBaseUrlKey] = baseUrl.trim().trimEnd('/') }
    }

    suspend fun setAutoApplyTags(enabled: Boolean) {
        context.galleryDataStore.edit { it[aiAutoApplyTagsKey] = enabled }
    }

    suspend fun setAutoOrganizeCollections(enabled: Boolean) {
        context.galleryDataStore.edit { it[aiAutoOrganizeCollectionsKey] = enabled }
    }

    suspend fun setIncludeOcrContext(enabled: Boolean) {
        context.galleryDataStore.edit { it[aiIncludeOcrContextKey] = enabled }
    }

    suspend fun setCustomPromptInstructions(instructions: String) {
        context.galleryDataStore.edit { it[aiCustomPromptKey] = instructions.trim() }
    }

    suspend fun setBatchDelayMs(delayMs: Long) {
        context.galleryDataStore.edit { it[aiBatchDelayMsKey] = delayMs.coerceIn(0L, 5000L) }
    }
}
