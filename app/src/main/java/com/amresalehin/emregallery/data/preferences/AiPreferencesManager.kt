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

internal fun isOllamaEndpoint(rawBaseUrl: String): Boolean =
    runCatching {
        val uri = URI(rawBaseUrl.trim())
        val host = uri.host.orEmpty().lowercase()
        uri.port == 11434 || host == "ollama" || host.endsWith(".ollama")
    }.getOrDefault(false)

data class AiProviderProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = ""
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() &&
            (apiKey.isNotBlank() || isOllamaEndpoint(baseUrl)) &&
            modelId.isNotBlank()
}

data class AiConfig(
    val enabled: Boolean = true,
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = "",
    val autoApplyTags: Boolean = true,
    val autoOrganizeCollections: Boolean = true,
    val customPromptInstructions: String = "",
    val analysisPromptTemplate: String = DEFAULT_ANALYSIS_PROMPT_TEMPLATE,
    val batchDelayMs: Long = 500L,
    val providerId: String = "legacy",
    val providerName: String = "Default provider"
) {
    companion object {
        const val DEFAULT_ANALYSIS_PROMPT_TEMPLATE = """Analyze this image or screenshot for a smart photo and screenshot organizer.
Return ONLY a valid JSON object with this exact schema:
{
  "title": "",
  "description": "",
  "tags": [],
  "collections": []
}
Rules:
- Generate 3 to 7 concise, searchable tags in Title Case that describe concrete subjects, topics, entities, actions, and details visible in this specific image. Avoid vague filler tags.
- Write a concise 1–2 sentence description that summarizes the image and naturally includes important legible facts when useful.
- Format every tag in Title Case, preserving standard acronyms and established brand casing.
- Format every collection name in Title Case; keep short connecting words lowercase and preserve standard acronyms and established brand casing.
- Generate every distinct collection that genuinely and meaningfully applies to this image. There is no fixed maximum number of collections; do not stop after one or a small preset number.
- Make each collection granular and specific to the actual subject matter and context. Prefer precise multi-word collection names over broad, generic, catch-all categories.
- Collections are semantic groupings, not a short list of broad media types. Derive them from the image and OCR content rather than from a preset taxonomy.
- Do not choose from, imitate, or expect a supplied list of existing collection names. Generate the most accurate names independently for this image.
- Do not create redundant variations of the same collection, and do not infer details that are not supported by the image.
- Return an empty collections array only when no meaningful collection can be identified.
""";

        const val LEGACY_SINGLE_COLLECTION_ANALYSIS_PROMPT_TEMPLATE = """Analyze this image or screenshot for a smart photo & screenshot organizer.
Return ONLY a valid JSON object with this exact schema:
{
  "title": "Short 2-5 word descriptive title",
  "description": "Clear 1-2 sentence summary of the visual content, scene, app/UI, or subject.",
  "tags": ["tag1", "tag2", "tag3"],
  "collection": "Single concise collection name"
}
Rules:
- Provide 3 to 7 concise, lowercase, searchable tags (1-2 words each, no # prefix).
- Include important legible facts naturally in the description instead of returning a separate details field.
- Choose a clean collection name such as Receipts, Documents, Code, Chats, Notes, Travel, Food, Shopping, Design, Memes, Nature, People, or Devices.
- Prefer reusing one of the existing collections below when it fits well.

Existing collections:
{{existing_collections}}
"""
    }

    val normalizedBaseUrl: String
        get() {
            val base = baseUrl.trim().trimEnd('/')
            val uri = runCatching { URI(base) }.getOrNull()
            return if (base.isNotBlank() && isOllamaEndpoint(base) && uri?.path.isNullOrBlank()) {
                "$base/v1"
            } else {
                base
            }
        }

    val hasCredentials: Boolean
        get() = normalizedBaseUrl.isNotBlank() &&
            (apiKey.isNotBlank() || isOllamaEndpoint(baseUrl)) &&
            modelId.isNotBlank()

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
    private val aiCustomPromptKey = stringPreferencesKey("ai_custom_prompt_instructions")
    private val aiAnalysisPromptTemplateKey = stringPreferencesKey("ai_analysis_prompt_template")
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

    private fun resolveAnalysisPromptTemplate(
        storedTemplate: String?,
        legacyInstructions: String?
    ): String {
        if (storedTemplate.isNullOrBlank()) {
            return buildString {
                append(AiConfig.DEFAULT_ANALYSIS_PROMPT_TEMPLATE)
                val extra = legacyInstructions.orEmpty().trim()
                if (extra.isNotBlank()) {
                    append("\n\nAdditional user instructions:\n")
                    append(extra.take(500))
                }
            }
        }

        val legacyDefault = AiConfig.LEGACY_SINGLE_COLLECTION_ANALYSIS_PROMPT_TEMPLATE
        return if (storedTemplate.startsWith(legacyDefault)) {
            AiConfig.DEFAULT_ANALYSIS_PROMPT_TEMPLATE + storedTemplate.removePrefix(legacyDefault)
        } else {
            storedTemplate
        }
    }

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
            customPromptInstructions = prefs[aiCustomPromptKey] ?: "",
            analysisPromptTemplate = resolveAnalysisPromptTemplate(
                storedTemplate = prefs[aiAnalysisPromptTemplateKey],
                legacyInstructions = prefs[aiCustomPromptKey]
            ),
            batchDelayMs = (prefs[aiBatchDelayMsKey] ?: 500L).coerceAtLeast(0L),
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
        require(baseUrl.isNotBlank() && (apiKey.isNotBlank() || isOllamaEndpoint(baseUrl)) && modelId.isNotBlank()) {
            "Complete the endpoint and model; enter an API key unless this is an Ollama endpoint."
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

    suspend fun setCustomPromptInstructions(instructions: String) {
        context.galleryDataStore.edit { it[aiCustomPromptKey] = instructions.trim() }
    }

    suspend fun setAnalysisPromptTemplate(template: String) {
        context.galleryDataStore.edit { it[aiAnalysisPromptTemplateKey] = template }
    }

    suspend fun setBatchDelayMs(delayMs: Long) {
        context.galleryDataStore.edit { it[aiBatchDelayMsKey] = delayMs.coerceAtLeast(0L) }
    }
}
