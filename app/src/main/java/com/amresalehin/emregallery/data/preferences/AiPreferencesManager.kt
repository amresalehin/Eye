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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class AiProviderProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = ""
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank()
}

data class AiConfig(
    val enabled: Boolean = true,
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = "",
    val autoApplyTags: Boolean = true,
    val autoOrganizeCollections: Boolean = true,
    val includeOcrContext: Boolean = true,
    val customPromptInstructions: String = "",
    val batchDelayMs: Long = 500L,
    val providerId: String = "legacy",
    val providerName: String = "Default provider"
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
 * Persists named custom API provider profiles and shared AI behavior preferences.
 * Existing single-provider settings are retained as a legacy profile on migration.
 */
class AiPreferencesManager(private val context: Context) {

    private val aiEnabledKey = booleanPreferencesKey("ai_enabled")
    private val aiApiKeyKey = stringPreferencesKey("ai_api_key")
    private val aiModelIdKey = stringPreferencesKey("ai_model_id")
    private val aiBaseUrlKey = stringPreferencesKey("ai_custom_base_url")
    private val aiProfilesKey = stringPreferencesKey("ai_provider_profiles_json")
    private val aiActiveProfileIdKey = stringPreferencesKey("ai_active_provider_profile_id")
    private val aiAutoApplyTagsKey = booleanPreferencesKey("ai_auto_apply_tags")
    private val aiAutoOrganizeCollectionsKey = booleanPreferencesKey("ai_auto_organize_collections")
    private val aiIncludeOcrContextKey = booleanPreferencesKey("ai_include_ocr_context")
    private val aiCustomPromptKey = stringPreferencesKey("ai_custom_prompt_instructions")
    private val aiBatchDelayMsKey = longPreferencesKey("ai_batch_delay_ms")

    private fun decodeProfiles(raw: String?): List<AiProviderProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
                    val name = item.optString("name").ifBlank { "Provider ${index + 1}" }
                    add(
                        AiProviderProfile(
                            id = id,
                            name = name,
                            baseUrl = item.optString("baseUrl"),
                            apiKey = item.optString("apiKey"),
                            modelId = item.optString("modelId")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeProfiles(profiles: List<AiProviderProfile>): String =
        JSONArray().apply {
            profiles.forEach { profile ->
                put(
                    JSONObject().apply {
                        put("id", profile.id)
                        put("name", profile.name)
                        put("baseUrl", profile.baseUrl)
                        put("apiKey", profile.apiKey)
                        put("modelId", profile.modelId)
                    }
                )
            }
        }.toString()

    private fun legacyProfile(prefs: androidx.datastore.preferences.core.Preferences): AiProviderProfile? {
        val baseUrl = prefs[aiBaseUrlKey].orEmpty()
        val apiKey = prefs[aiApiKeyKey].orEmpty()
        val modelId = prefs[aiModelIdKey].orEmpty()
        if (baseUrl.isBlank() && apiKey.isBlank() && modelId.isBlank()) return null
        return AiProviderProfile(
            id = "legacy",
            name = "Default provider",
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelId = modelId
        )
    }

    val providers: Flow<List<AiProviderProfile>> = context.galleryDataStore.data.map { prefs ->
        decodeProfiles(prefs[aiProfilesKey]).ifEmpty {
            listOfNotNull(legacyProfile(prefs))
        }
    }.distinctUntilChanged()

    val activeProviderId: Flow<String> = context.galleryDataStore.data.map { prefs ->
        val profiles = decodeProfiles(prefs[aiProfilesKey]).ifEmpty { listOfNotNull(legacyProfile(prefs)) }
        prefs[aiActiveProfileIdKey]?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id.orEmpty()
    }.distinctUntilChanged()

    val aiConfig: Flow<AiConfig> = context.galleryDataStore.data.map { prefs ->
        val profiles = decodeProfiles(prefs[aiProfilesKey]).ifEmpty {
            listOfNotNull(legacyProfile(prefs))
        }
        val selectedId = prefs[aiActiveProfileIdKey]
        val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
        AiConfig(
            enabled = prefs[aiEnabledKey] ?: true,
            baseUrl = selected?.baseUrl?.trim()?.trimEnd('/') ?: "",
            apiKey = selected?.apiKey?.trim() ?: "",
            modelId = selected?.modelId?.trim() ?: "",
            autoApplyTags = prefs[aiAutoApplyTagsKey] ?: true,
            autoOrganizeCollections = prefs[aiAutoOrganizeCollectionsKey] ?: true,
            includeOcrContext = prefs[aiIncludeOcrContextKey] ?: true,
            customPromptInstructions = prefs[aiCustomPromptKey] ?: "",
            batchDelayMs = (prefs[aiBatchDelayMsKey] ?: 500L).coerceIn(0L, 5000L),
            providerId = selected?.id ?: "legacy",
            providerName = selected?.name ?: "No provider selected"
        )
    }.distinctUntilChanged()

    suspend fun currentConfig(): AiConfig = aiConfig.first()

    suspend fun addProvider(name: String): String {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Enter a provider name." }
        val id = UUID.randomUUID().toString()
        context.galleryDataStore.edit { prefs ->
            val profiles = decodeProfiles(prefs[aiProfilesKey]).ifEmpty {
                listOfNotNull(legacyProfile(prefs))
            }.toMutableList()
            profiles.add(AiProviderProfile(id = id, name = cleanName))
            prefs[aiProfilesKey] = encodeProfiles(profiles)
            prefs[aiActiveProfileIdKey] = id
        }
        return id
    }

    suspend fun selectProvider(id: String) {
        context.galleryDataStore.edit { prefs ->
            val profiles = decodeProfiles(prefs[aiProfilesKey]).ifEmpty {
                listOfNotNull(legacyProfile(prefs))
            }
            require(profiles.any { it.id == id }) { "Provider not found." }
            prefs[aiActiveProfileIdKey] = id
        }
    }

    suspend fun renameProvider(id: String, name: String) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Enter a provider name." }
        context.galleryDataStore.edit { prefs ->
            val profiles = decodeProfiles(prefs[aiProfilesKey]).ifEmpty {
                listOfNotNull(legacyProfile(prefs))
            }
            val index = profiles.indexOfFirst { it.id == id }
            require(index >= 0) { "Provider not found." }
            prefs[aiProfilesKey] = encodeProfiles(profiles.toMutableList().also {
                it[index] = it[index].copy(name = cleanName)
            })
        }
    }

    suspend fun deleteProvider(id: String) {
        context.galleryDataStore.edit { prefs ->
            val profiles = decodeProfiles(prefs[aiProfilesKey]).ifEmpty {
                listOfNotNull(legacyProfile(prefs))
            }.filterNot { it.id == id }
            prefs[aiProfilesKey] = encodeProfiles(profiles)
            if (prefs[aiActiveProfileIdKey] == id) {
                prefs[aiActiveProfileIdKey] = profiles.firstOrNull()?.id.orEmpty()
            }
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.galleryDataStore.edit { it[aiEnabledKey] = enabled }
    }

    suspend fun setBaseUrl(baseUrl: String) {
        val config = currentConfig()
        saveEndpointConfig(baseUrl, config.apiKey, config.modelId)
    }

    suspend fun setApiKey(apiKey: String) {
        val config = currentConfig()
        saveEndpointConfig(config.baseUrl, apiKey, config.modelId)
    }

    suspend fun setModelId(modelId: String) {
        val config = currentConfig()
        saveEndpointConfig(config.baseUrl, config.apiKey, modelId)
    }

    suspend fun saveProviderConfig(
        providerId: String,
        name: String,
        baseUrl: String,
        apiKey: String,
        modelId: String
    ): String {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Enter a provider name." }
        require(baseUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank()) {
            "Complete the endpoint, API key, and model before saving."
        }
        var savedId = providerId
        context.galleryDataStore.edit { prefs ->
            val profiles = decodeProfiles(prefs[aiProfilesKey]).ifEmpty {
                listOfNotNull(legacyProfile(prefs))
            }.toMutableList()
            var index = profiles.indexOfFirst { it.id == providerId }
            if (index < 0) {
                savedId = UUID.randomUUID().toString()
                index = -1
            }
            val updated = AiProviderProfile(
                id = savedId,
                name = cleanName,
                baseUrl = baseUrl.trim().trimEnd('/'),
                apiKey = apiKey.trim(),
                modelId = modelId.trim()
            )
            if (index >= 0) profiles[index] = updated else profiles.add(updated)
            prefs[aiProfilesKey] = encodeProfiles(profiles)
            prefs[aiActiveProfileIdKey] = savedId
            // Keep legacy values synchronized for downgrade compatibility.
            prefs[aiBaseUrlKey] = updated.baseUrl
            prefs[aiApiKeyKey] = updated.apiKey
            prefs[aiModelIdKey] = updated.modelId
        }
        return savedId
    }

    suspend fun saveEndpointConfig(baseUrl: String, apiKey: String, modelId: String) {
        val current = currentConfig()
        saveProviderConfig(current.providerId, current.providerName, baseUrl, apiKey, modelId)
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
