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

data class AiConfig(
    val enabled: Boolean = true,
    val apiKey: String = "",
    val modelId: String = DEFAULT_MODEL_ID,
    val customBaseUrl: String = DEFAULT_API_BASE_URL,
    val autoApplyTags: Boolean = true,
    val autoOrganizeCollections: Boolean = true,
    val includeOcrContext: Boolean = true,
    val customPromptInstructions: String = "",
    val batchDelayMs: Long = 500L
) {
    val isConfigured: Boolean
        get() = enabled && apiKey.isNotBlank() && modelId.isNotBlank() && customBaseUrl.isNotBlank()

    val hasApiKey: Boolean
        get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL_ID = "gpt-4o-mini"
        const val DEFAULT_API_BASE_URL = "https://api.openai.com/v1"

        // Legacy constants only support migration from earlier native-Gemini configuration.
        const val LEGACY_GEMINI_MODEL = "gemini-2.5-flash"
        const val LEGACY_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
        const val GEMINI_OPENAI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val UNIFIED_API_PROVIDER_ID = "unified"
    }
}

/**
 * Stores one provider-independent API configuration. The old ai_provider preference is read
 * only to migrate older installations; it is not part of the current UI or request client.
 */
class AiPreferencesManager(private val context: Context) {

    private val aiEnabledKey = booleanPreferencesKey("ai_enabled")
    private val legacyAiProviderKey = stringPreferencesKey("ai_provider")
    private val aiApiKeyKey = stringPreferencesKey("ai_api_key")
    private val aiModelIdKey = stringPreferencesKey("ai_model_id")
    private val aiCustomBaseUrlKey = stringPreferencesKey("ai_custom_base_url")
    private val aiAutoApplyTagsKey = booleanPreferencesKey("ai_auto_apply_tags")
    private val aiAutoOrganizeCollectionsKey = booleanPreferencesKey("ai_auto_organize_collections")
    private val aiIncludeOcrContextKey = booleanPreferencesKey("ai_include_ocr_context")
    private val aiCustomPromptKey = stringPreferencesKey("ai_custom_prompt_instructions")
    private val aiBatchDelayMsKey = longPreferencesKey("ai_batch_delay_ms")

    val aiConfig: Flow<AiConfig> = context.galleryDataStore.data.map { prefs ->
        val legacyProvider = prefs[legacyAiProviderKey]?.trim()?.lowercase()
        val savedApiKey = prefs[aiApiKeyKey]?.trim().orEmpty()
        val savedModelId = prefs[aiModelIdKey]?.trim().orEmpty()
        val savedBaseUrl = prefs[aiCustomBaseUrlKey]?.trim()?.trimEnd('/').orEmpty()

        // Earlier versions defaulted to Gemini without always saving ai_provider.
        val isLegacyGemini = legacyProvider == "gemini" ||
            (legacyProvider.isNullOrBlank() && (
                savedModelId.startsWith("gemini-", ignoreCase = true) ||
                    (savedModelId.isBlank() && savedApiKey.isNotBlank())
                ))

        val effectiveBaseUrl = if (isLegacyGemini) {
            when {
                savedBaseUrl.isBlank() || savedBaseUrl == AiConfig.LEGACY_GEMINI_BASE_URL ->
                    AiConfig.GEMINI_OPENAI_BASE_URL
                else -> savedBaseUrl
            }
        } else {
            savedBaseUrl.ifBlank { AiConfig.DEFAULT_API_BASE_URL }
        }

        AiConfig(
            enabled = prefs[aiEnabledKey] ?: true,
            apiKey = savedApiKey,
            modelId = savedModelId.ifBlank {
                if (isLegacyGemini) AiConfig.LEGACY_GEMINI_MODEL else AiConfig.DEFAULT_MODEL_ID
            },
            customBaseUrl = effectiveBaseUrl,
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

    suspend fun setConfiguration(apiKey: String, modelId: String, baseUrl: String) {
        context.galleryDataStore.edit { prefs ->
            prefs[aiApiKeyKey] = apiKey.trim()
            prefs[aiModelIdKey] = modelId.trim().ifBlank { AiConfig.DEFAULT_MODEL_ID }
            prefs[aiCustomBaseUrlKey] = baseUrl.trim().trimEnd('/').ifBlank { AiConfig.DEFAULT_API_BASE_URL }
            prefs[legacyAiProviderKey] = AiConfig.UNIFIED_API_PROVIDER_ID
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
