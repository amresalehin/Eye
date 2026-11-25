package com.amresalehin.emregallery.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.amresalehin.emregallery.data.preferences.AiConfig
import com.amresalehin.emregallery.data.preferences.AiProviderProfile
import com.amresalehin.emregallery.data.preferences.AiPreferencesManager
import com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager
import com.amresalehin.emregallery.data.preferences.DownloaderSubfolderMode
import com.amresalehin.emregallery.data.preferences.DownloaderVideoQuality
import com.amresalehin.emregallery.data.preferences.MediaCollectionsPreferencesManager
import com.amresalehin.emregallery.data.preferences.OcrPreferencesManager
import com.amresalehin.emregallery.data.preferences.UiPreferencesManager
import com.amresalehin.emregallery.data.preferences.VaultPreferencesManager
import kotlinx.coroutines.flow.Flow

val Context.galleryDataStore: DataStore<Preferences> by preferencesDataStore(name = "emre_gallery")

/**
 * Unified facade providing access to domain-specific preference managers:
 * - [uiPrefs]: UI, theme, sorting, grouping, and onboarding preferences
 * - [vaultPrefs]: Security, locked folder PIN hash, and biometrics
 * - [ocrPrefs]: OCR models, languages, and engines
 * - [mediaPrefs]: Albums, tags, people, favorites, trash bin, archive, and search history
 * - [aiPrefs]: Custom API key AI configuration & Shots Studio-style smart organization
 */
class PreferencesRepository(context: Context) {

    private val uiPrefs = UiPreferencesManager(context)
    private val vaultPrefs = VaultPreferencesManager(context)
    private val ocrPrefs = OcrPreferencesManager(context)
    private val mediaPrefs = MediaCollectionsPreferencesManager(context)
    val downloaderPrefs = DownloaderPreferencesManager(context)
    val aiPrefs = AiPreferencesManager(context)

    // --- Onboarding & UI Navigation ---
    fun isWelcomeCompletedSync(): Boolean = uiPrefs.isWelcomeCompletedSync()
    val hasCompletedWelcome: Flow<Boolean> get() = uiPrefs.hasCompletedWelcome
    suspend fun setCompletedWelcome(completed: Boolean) = uiPrefs.setCompletedWelcome(completed)

    val homeScrollPosition: Flow<Pair<Int, Int>> get() = uiPrefs.homeScrollPosition
    suspend fun saveHomeScrollPosition(index: Int, offset: Int) = uiPrefs.saveHomeScrollPosition(index, offset)
    suspend fun clearHomeScrollPosition() = uiPrefs.clearHomeScrollPosition()

    val theme: Flow<String> get() = uiPrefs.theme
    suspend fun setTheme(value: String) = uiPrefs.setTheme(value)

    val sortOrder: Flow<String> get() = uiPrefs.sortOrder
    suspend fun setSortOrder(value: String) = uiPrefs.setSortOrder(value)

    val groupBy: Flow<String> get() = uiPrefs.groupBy
    suspend fun setGroupBy(value: String) = uiPrefs.setGroupBy(value)

    val gridColumns: Flow<Int> get() = uiPrefs.gridColumns
    suspend fun setGridColumns(value: Int) = uiPrefs.setGridColumns(value)

    val gridStyle: Flow<String> get() = uiPrefs.gridStyle
    suspend fun setGridStyle(value: String) = uiPrefs.setGridStyle(value)

    // --- Vault & Security ---
    val lockedFolderPinHash: Flow<String?> get() = vaultPrefs.lockedFolderPinHash
    val lockedFolderBiometricEnabled: Flow<Boolean> get() = vaultPrefs.lockedFolderBiometricEnabled
    suspend fun setLockedFolderPin(pin: String) = vaultPrefs.setLockedFolderPin(pin)
    suspend fun verifyLockedFolderPin(pin: String): Boolean = vaultPrefs.verifyLockedFolderPin(pin)
    suspend fun clearLockedFolderPin() = vaultPrefs.clearLockedFolderPin()
    suspend fun setLockedFolderBiometricEnabled(enabled: Boolean) = vaultPrefs.setLockedFolderBiometricEnabled(enabled)

    // --- OCR & Machine Learning ---
    val ocrLanguages: Flow<Set<String>> get() = ocrPrefs.ocrLanguages
    val mlKitLanguages: Flow<Set<String>> get() = ocrPrefs.mlKitLanguages
    val ocrModel: Flow<String> get() = ocrPrefs.ocrModel
    val ocrEngine: Flow<String> get() = ocrPrefs.ocrEngine
    suspend fun setOcrLanguages(languages: Set<String>) = ocrPrefs.setOcrLanguages(languages)
    suspend fun setMlKitLanguages(languages: Set<String>) = ocrPrefs.setMlKitLanguages(languages)
    suspend fun setOcrModel(modelId: String) = ocrPrefs.setOcrModel(modelId)
    suspend fun setOcrEngine(engine: String) = ocrPrefs.setOcrEngine(engine)

    // --- Media Collections & Metadata ---
    val favourites: Flow<Set<String>> get() = mediaPrefs.favourites
    val hidden: Flow<Set<String>> get() = mediaPrefs.hidden
    val bin: Flow<Set<String>> get() = mediaPrefs.bin
    val archive: Flow<Set<String>> get() = mediaPrefs.archive
    val recentSearches: Flow<List<String>> get() = mediaPrefs.recentSearches
    val customAlbums: Flow<List<CustomAlbum>> get() = mediaPrefs.customAlbums
    val customTagsMap: Flow<Map<String, Set<String>>> get() = mediaPrefs.customTagsMap
    val customPeopleMap: Flow<Map<String, Set<String>>> get() = mediaPrefs.customPeopleMap

    suspend fun saveCustomAlbums(albums: List<CustomAlbum>) = mediaPrefs.saveCustomAlbums(albums)
    suspend fun createCustomAlbum(name: String, parentPath: String = ""): CustomAlbum = mediaPrefs.createCustomAlbum(name, parentPath)
    suspend fun deleteCustomAlbum(albumId: String) = mediaPrefs.deleteCustomAlbum(albumId)
    suspend fun renameCustomAlbum(albumId: String, newName: String) = mediaPrefs.renameCustomAlbum(albumId, newName)
    suspend fun addMediaToCustomAlbum(albumId: String, uri: String) = mediaPrefs.addMediaToCustomAlbum(albumId, uri)
    suspend fun removeMediaFromCustomAlbum(albumId: String, uri: String) = mediaPrefs.removeMediaFromCustomAlbum(albumId, uri)

    suspend fun toggleFavourite(uri: String) = mediaPrefs.toggleFavourite(uri)
    suspend fun setHidden(uri: String, hiddenVal: Boolean) = mediaPrefs.setHidden(uri, hiddenVal)
    suspend fun setHiddenBatch(uris: Collection<String>, hiddenVal: Boolean) = mediaPrefs.setHiddenBatch(uris, hiddenVal)
    suspend fun setInBin(uri: String, inBin: Boolean) = mediaPrefs.setInBin(uri, inBin)
    suspend fun setInBinBatch(uris: Collection<String>, inBin: Boolean) = mediaPrefs.setInBinBatch(uris, inBin)
    suspend fun removePermanently(uris: Collection<String>) = mediaPrefs.removePermanently(uris)
    suspend fun setArchived(uri: String, archivedVal: Boolean) = mediaPrefs.setArchived(uri, archivedVal)
    suspend fun setArchivedBatch(uris: Collection<String>, archivedVal: Boolean) = mediaPrefs.setArchivedBatch(uris, archivedVal)

    suspend fun addRecentSearch(query: String) = mediaPrefs.addRecentSearch(query)
    suspend fun clearRecentSearches() = mediaPrefs.clearRecentSearches()
    suspend fun addTagForMedia(uri: String, tag: String) = mediaPrefs.addTagForMedia(uri, tag)
    suspend fun addTagsForMedia(uri: String, tags: Collection<String>) = mediaPrefs.addTagsForMedia(uri, tags)
    suspend fun ensureAlbumAndAddMedia(albumName: String, uri: String) = mediaPrefs.ensureAlbumAndAddMedia(albumName, uri)
    suspend fun addPersonForMedia(uri: String, person: String) = mediaPrefs.addPersonForMedia(uri, person)

    // --- Unified Custom API AI ---
    val aiConfig: Flow<AiConfig> get() = aiPrefs.aiConfig
    val aiProviders: Flow<List<AiProviderProfile>> get() = aiPrefs.providers
    val activeAiProviderId: Flow<String> get() = aiPrefs.activeProviderId
    suspend fun addAiProvider(name: String): String = aiPrefs.addProvider(name)
    suspend fun selectAiProvider(id: String) = aiPrefs.selectProvider(id)
    suspend fun renameAiProvider(id: String, name: String) = aiPrefs.renameProvider(id, name)
    suspend fun deleteAiProvider(id: String) = aiPrefs.deleteProvider(id)
    suspend fun setAiEnabled(enabled: Boolean) = aiPrefs.setEnabled(enabled)
    suspend fun setAiBaseUrl(baseUrl: String) = aiPrefs.setBaseUrl(baseUrl)
    suspend fun setAiApiKey(apiKey: String) = aiPrefs.setApiKey(apiKey)
    suspend fun setAiModelId(modelId: String) = aiPrefs.setModelId(modelId)
    suspend fun saveAiEndpointConfig(baseUrl: String, apiKey: String, modelId: String) =
        aiPrefs.saveEndpointConfig(baseUrl, apiKey, modelId)
    suspend fun setAiAutoApplyTags(enabled: Boolean) = aiPrefs.setAutoApplyTags(enabled)
    suspend fun setAiAutoOrganizeCollections(enabled: Boolean) = aiPrefs.setAutoOrganizeCollections(enabled)
    suspend fun setAiIncludeOcrContext(enabled: Boolean) = aiPrefs.setIncludeOcrContext(enabled)
    suspend fun setAiCustomPromptInstructions(instructions: String) = aiPrefs.setCustomPromptInstructions(instructions)
    suspend fun setAiBatchDelayMs(delayMs: Long) = aiPrefs.setBatchDelayMs(delayMs)

    // --- Media Downloader Customizability ---
    val videoQualityCap: Flow<DownloaderVideoQuality> get() = downloaderPrefs.videoQualityCap
    val audioOnlyMode: Flow<Boolean> get() = downloaderPrefs.audioOnlyMode
    val preferredAudioFormat: Flow<String> get() = downloaderPrefs.preferredAudioFormat
    val minKbFilter: Flow<Int> get() = downloaderPrefs.minKbFilter
    val minWidthFilter: Flow<Int> get() = downloaderPrefs.minWidthFilter
    val minHeightFilter: Flow<Int> get() = downloaderPrefs.minHeightFilter
    val showSvg: Flow<Boolean> get() = downloaderPrefs.showSvg
    val subfolderMode: Flow<DownloaderSubfolderMode> get() = downloaderPrefs.subfolderMode
    val filenameTemplate: Flow<String> get() = downloaderPrefs.filenameTemplate
    val wifiOnlyDownloads: Flow<Boolean> get() = downloaderPrefs.wifiOnlyDownloads
    val maxConcurrentDownloads: Flow<Int> get() = downloaderPrefs.maxConcurrentDownloads
    val enabledProviders: Flow<Set<String>> get() = downloaderPrefs.enabledProviders
    val searchEngine: Flow<BrowserSearchEngine> get() = downloaderPrefs.searchEngine

    suspend fun setVideoQualityCap(quality: DownloaderVideoQuality) = downloaderPrefs.setVideoQualityCap(quality)
    suspend fun setAudioOnlyMode(enabled: Boolean) = downloaderPrefs.setAudioOnlyMode(enabled)
    suspend fun setPreferredAudioFormat(format: String) = downloaderPrefs.setPreferredAudioFormat(format)
    suspend fun setMinKbFilter(minKb: Int) = downloaderPrefs.setMinKbFilter(minKb)
    suspend fun setMinWidthFilter(minWidth: Int) = downloaderPrefs.setMinWidthFilter(minWidth)
    suspend fun setMinHeightFilter(minHeight: Int) = downloaderPrefs.setMinHeightFilter(minHeight)
    suspend fun setShowSvg(show: Boolean) = downloaderPrefs.setShowSvg(show)
    suspend fun setSubfolderMode(mode: DownloaderSubfolderMode) = downloaderPrefs.setSubfolderMode(mode)
    suspend fun setFilenameTemplate(template: String) = downloaderPrefs.setFilenameTemplate(template)
    suspend fun setWifiOnlyDownloads(wifiOnly: Boolean) = downloaderPrefs.setWifiOnlyDownloads(wifiOnly)
    suspend fun setMaxConcurrentDownloads(maxConcurrent: Int) = downloaderPrefs.setMaxConcurrentDownloads(maxConcurrent)
    suspend fun setEnabledProviders(providers: Set<String>) = downloaderPrefs.setEnabledProviders(providers)
    suspend fun setSearchEngine(engine: BrowserSearchEngine) = downloaderPrefs.setSearchEngine(engine)
}
