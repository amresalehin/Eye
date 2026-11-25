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
import java.net.URI

data class AiConfig(
    val enabled: Boolean = true,
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = "",
    val autoApplyTags: Boolean = true,
    val autoOrganizeCollections: Boolean = true,
    val includeOcrContext: Boolean = true,
    val customPromptInstructions: String = "",
    val batchDelayMs: Long = 500L
) {
    val normalizedBaseUrl: String
        get() = baseUrl.trim().trimEnd('/')

    val hasCredentials: Boolean
        get() = normalizedBaseUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank()

    val isConfigured: Boolean
        get() = enabled && hasCredentials

    val hasApiKey: Boolean
        get() = apiKey.isNotBlank()

    val endpointHost: String
        get() = runCatching {
            URI(normalizedBaseUrl).host?.removePrefix("www.")
        }.getOrNull().orEmpty().ifBlank { normalizedBaseUrl }
}

/**
 * Persists user-supplied unified custom API configuration in DataStore.
 */
class AiPreferencesManager(private val context: Context) {

    private val aiEnabledKey = booleanPreferencesKey("ai_enabled")
    private val aiApiKeyKey = stringPreferencesKey("ai_api_key")
    private val aiModelIdKey = stringPreferencesKey("ai_model_id")
    private val aiBaseUrlKey = stringPreferencesKey("ai_custom_base_url")
    private val aiAutoApplyTagsKey = booleanPreferencesKey("ai_auto_apply_tags")
    private val aiAutoOrganizeCollectionsKey = booleanPreferencesKey("ai_auto_organize_collections")
    private val aiIncludeOcrContextKey = booleanPreferencesKey("ai_include_ocr_context")
    private val aiCustomPromptKey = stringPreferencesKey("ai_custom_prompt_instructions")
    private val aiBatchDelayMsKey = longPreferencesKey("ai_batch_delay_ms")

    val aiConfig: Flow<AiConfig> = context.galleryDataStore.data.map { prefs ->
        AiConfig(
            enabled = prefs[aiEnabledKey] ?: true,
            baseUrl = prefs[aiBaseUrlKey]?.trim()?.trimEnd('/') ?: "",
            apiKey = prefs[aiApiKeyKey]?.trim() ?: "",
            modelId = prefs[aiModelIdKey]?.trim() ?: "",
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

    suspend fun setBaseUrl(baseUrl: String) {
        context.galleryDataStore.edit { it[aiBaseUrlKey] = baseUrl.trim().trimEnd('/') }
    }

    suspend fun setApiKey(apiKey: String) {
        context.galleryDataStore.edit { it[aiApiKeyKey] = apiKey.trim() }
    }

    suspend fun setModelId(modelId: String) {
        context.galleryDataStore.edit { it[aiModelIdKey] = modelId.trim() }
    }

    suspend fun saveEndpointConfig(baseUrl: String, apiKey: String, modelId: String) {
        context.galleryDataStore.edit { prefs ->
            prefs[aiBaseUrlKey] = baseUrl.trim().trimEnd('/')
            prefs[aiApiKeyKey] = apiKey.trim()
            prefs[aiModelIdKey] = modelId.trim()
        }
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
