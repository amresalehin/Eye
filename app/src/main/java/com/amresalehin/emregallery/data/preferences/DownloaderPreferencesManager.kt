package com.amresalehin.emregallery.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.amresalehin.emregallery.data.BrowserSearchEngine
import com.amresalehin.emregallery.data.galleryDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale

enum class DownloaderVideoQuality(val id: String, val label: String, val maxHeight: Int) {
    BEST("BEST", "Best Available (Up to 8K)", 0),
    Q8K("8K", "8K Ultra HD (4320p)", 4320),
    Q4K("4K", "4K UHD (2160p)", 2160),
    Q2K("2K", "2K QHD (1440p)", 1440),
    Q1080P("1080P", "1080p Full HD", 1080),
    Q720P("720P", "720p HD", 720),
    Q480P("480P", "480p SD", 480),
    Q360P("360P", "360p Data Saver", 360);

    companion object {
        fun fromId(id: String?): DownloaderVideoQuality =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: BEST
    }
}

enum class DownloaderSubfolderMode(val id: String, val label: String, val description: String) {
    FLAT("FLAT", "Single Folder", "Save all files directly in Downloads/Emre Gallery"),
    BY_DOMAIN("BY_DOMAIN", "By Website Domain", "Group files into subfolders like reddit.com, youtube.com"),
    BY_STACK("BY_STACK", "By Page / Post Title", "Group files into subfolders named after each collection");

    companion object {
        fun fromId(id: String?): DownloaderSubfolderMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: FLAT
    }
}

/**
 * Manages persistent customization settings for the Media Downloader subsystem.
 * Synchronizes values to both Jetpack DataStore (for reactive Compose UI Flows)
 * and SharedPreferences (for non-blocking reads in WorkManager and downloader engines).
 */
class DownloaderPreferencesManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val syncPrefs = appContext.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)

    val videoQualityCap: Flow<DownloaderVideoQuality> = appContext.galleryDataStore.data.map { prefs ->
        DownloaderVideoQuality.fromId(prefs[KEY_VIDEO_QUALITY] ?: syncPrefs.getString(PREF_VIDEO_QUALITY, "BEST"))
    }.distinctUntilChanged()

    val audioOnlyMode: Flow<Boolean> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_AUDIO_ONLY] ?: syncPrefs.getBoolean(PREF_AUDIO_ONLY, false)
    }.distinctUntilChanged()

    val preferredAudioFormat: Flow<String> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_AUDIO_FORMAT] ?: syncPrefs.getString(PREF_AUDIO_FORMAT, "M4A") ?: "M4A"
    }.distinctUntilChanged()

    val minKbFilter: Flow<Int> = appContext.galleryDataStore.data.map { prefs ->
        (prefs[KEY_MIN_KB] ?: syncPrefs.getInt(PREF_MIN_KB, 1)).coerceAtLeast(0)
    }.distinctUntilChanged()

    val minWidthFilter: Flow<Int> = appContext.galleryDataStore.data.map { prefs ->
        (prefs[KEY_MIN_WIDTH] ?: syncPrefs.getInt(PREF_MIN_WIDTH, 0)).coerceAtLeast(0)
    }.distinctUntilChanged()

    val minHeightFilter: Flow<Int> = appContext.galleryDataStore.data.map { prefs ->
        (prefs[KEY_MIN_HEIGHT] ?: syncPrefs.getInt(PREF_MIN_HEIGHT, 0)).coerceAtLeast(0)
    }.distinctUntilChanged()

    val showSvg: Flow<Boolean> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_SHOW_SVG] ?: syncPrefs.getBoolean(PREF_SHOW_SVG, false)
    }.distinctUntilChanged()

    val subfolderMode: Flow<DownloaderSubfolderMode> = appContext.galleryDataStore.data.map { prefs ->
        DownloaderSubfolderMode.fromId(prefs[KEY_SUBFOLDER_MODE] ?: syncPrefs.getString(PREF_SUBFOLDER_MODE, "FLAT"))
    }.distinctUntilChanged()

    val filenameTemplate: Flow<String> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_FILENAME_TEMPLATE] ?: syncPrefs.getString(PREF_FILENAME_TEMPLATE, DEFAULT_FILENAME_TEMPLATE) ?: DEFAULT_FILENAME_TEMPLATE
    }.distinctUntilChanged()

    val wifiOnlyDownloads: Flow<Boolean> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_WIFI_ONLY] ?: syncPrefs.getBoolean(PREF_WIFI_ONLY, false)
    }.distinctUntilChanged()

    val maxConcurrentDownloads: Flow<Int> = appContext.galleryDataStore.data.map { prefs ->
        (prefs[KEY_MAX_CONCURRENT] ?: syncPrefs.getInt(PREF_MAX_CONCURRENT, 3)).coerceIn(1, 6)
    }.distinctUntilChanged()

    val enabledProviders: Flow<Set<String>> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_ENABLED_PROVIDERS] ?: DEFAULT_PROVIDERS
    }.distinctUntilChanged()

    val searchEngine: Flow<BrowserSearchEngine> = appContext.galleryDataStore.data.map { prefs ->
        val raw = prefs[KEY_SEARCH_ENGINE] ?: syncPrefs.getString(PREF_SEARCH_ENGINE, BrowserSearchEngine.DUCKDUCKGO.name)
        BrowserSearchEngine.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: BrowserSearchEngine.DUCKDUCKGO
    }.distinctUntilChanged()

    suspend fun setVideoQualityCap(quality: DownloaderVideoQuality) {
        syncPrefs.edit().putString(PREF_VIDEO_QUALITY, quality.id).apply()
        appContext.galleryDataStore.edit { it[KEY_VIDEO_QUALITY] = quality.id }
    }

    suspend fun setAudioOnlyMode(enabled: Boolean) {
        syncPrefs.edit().putBoolean(PREF_AUDIO_ONLY, enabled).apply()
        appContext.galleryDataStore.edit { it[KEY_AUDIO_ONLY] = enabled }
    }

    suspend fun setPreferredAudioFormat(format: String) {
        val clean = format.trim().uppercase(Locale.ROOT).takeIf { it in setOf("M4A", "OPUS") } ?: "M4A"
        syncPrefs.edit().putString(PREF_AUDIO_FORMAT, clean).apply()
        appContext.galleryDataStore.edit { it[KEY_AUDIO_FORMAT] = clean }
    }

    suspend fun setMinKbFilter(minKb: Int) {
        val clamped = minKb.coerceIn(0, 50_000)
        syncPrefs.edit().putInt(PREF_MIN_KB, clamped).apply()
        appContext.galleryDataStore.edit { it[KEY_MIN_KB] = clamped }
    }

    suspend fun setMinWidthFilter(minWidth: Int) {
        val clamped = minWidth.coerceIn(0, 3840)
        syncPrefs.edit().putInt(PREF_MIN_WIDTH, clamped).apply()
        appContext.galleryDataStore.edit { it[KEY_MIN_WIDTH] = clamped }
    }

    suspend fun setMinHeightFilter(minHeight: Int) {
        val clamped = minHeight.coerceIn(0, 2160)
        syncPrefs.edit().putInt(PREF_MIN_HEIGHT, clamped).apply()
        appContext.galleryDataStore.edit { it[KEY_MIN_HEIGHT] = clamped }
    }

    suspend fun setShowSvg(show: Boolean) {
        syncPrefs.edit().putBoolean(PREF_SHOW_SVG, show).apply()
        appContext.galleryDataStore.edit { it[KEY_SHOW_SVG] = show }
    }

    suspend fun setSubfolderMode(mode: DownloaderSubfolderMode) {
        syncPrefs.edit().putString(PREF_SUBFOLDER_MODE, mode.id).apply()
        appContext.galleryDataStore.edit { it[KEY_SUBFOLDER_MODE] = mode.id }
    }

    suspend fun setFilenameTemplate(template: String) {
        val clean = template.trim().ifBlank { DEFAULT_FILENAME_TEMPLATE }
        syncPrefs.edit().putString(PREF_FILENAME_TEMPLATE, clean).apply()
        appContext.galleryDataStore.edit { it[KEY_FILENAME_TEMPLATE] = clean }
    }

    suspend fun setWifiOnlyDownloads(wifiOnly: Boolean) {
        syncPrefs.edit().putBoolean(PREF_WIFI_ONLY, wifiOnly).apply()
        appContext.galleryDataStore.edit { it[KEY_WIFI_ONLY] = wifiOnly }
    }

    suspend fun setMaxConcurrentDownloads(maxConcurrent: Int) {
        val clamped = maxConcurrent.coerceIn(1, 6)
        syncPrefs.edit().putInt(PREF_MAX_CONCURRENT, clamped).apply()
        appContext.galleryDataStore.edit { it[KEY_MAX_CONCURRENT] = clamped }
    }

    suspend fun setEnabledProviders(providers: Set<String>) {
        val safe = providers.ifEmpty { setOf("static") }
        syncPrefs.edit().putStringSet(PREF_ENABLED_PROVIDERS, safe).apply()
        appContext.galleryDataStore.edit { it[KEY_ENABLED_PROVIDERS] = safe }
    }

    suspend fun setSearchEngine(engine: BrowserSearchEngine) {
        syncPrefs.edit().putString(PREF_SEARCH_ENGINE, engine.name).apply()
        appContext.galleryDataStore.edit { it[KEY_SEARCH_ENGINE] = engine.name }
    }

    companion object {
        const val SYNC_PREFS_NAME = "emre_downloader_prefs"
        const val DEFAULT_FILENAME_TEMPLATE = "{title}_{hash}"
        val DEFAULT_PROVIDERS = setOf("gallery-dl", "yt-dlp", "static", "browser")

        private const val PREF_VIDEO_QUALITY = "video_quality_cap"
        private const val PREF_AUDIO_ONLY = "audio_only_mode"
        private const val PREF_AUDIO_FORMAT = "preferred_audio_format"
        private const val PREF_MIN_KB = "min_kb_filter"
        private const val PREF_MIN_WIDTH = "min_width_filter"
        private const val PREF_MIN_HEIGHT = "min_height_filter"
        private const val PREF_SHOW_SVG = "show_svg"
        private const val PREF_SUBFOLDER_MODE = "subfolder_mode"
        private const val PREF_FILENAME_TEMPLATE = "filename_template"
        private const val PREF_WIFI_ONLY = "wifi_only_downloads"
        private const val PREF_MAX_CONCURRENT = "max_concurrent_downloads"
        private const val PREF_SEARCH_ENGINE = "browser_search_engine"
        private const val PREF_ENABLED_PROVIDERS = "enabled_providers"

        private val KEY_VIDEO_QUALITY = stringPreferencesKey(PREF_VIDEO_QUALITY)
        private val KEY_AUDIO_ONLY = booleanPreferencesKey(PREF_AUDIO_ONLY)
        private val KEY_AUDIO_FORMAT = stringPreferencesKey(PREF_AUDIO_FORMAT)
        private val KEY_MIN_KB = intPreferencesKey(PREF_MIN_KB)
        private val KEY_MIN_WIDTH = intPreferencesKey(PREF_MIN_WIDTH)
        private val KEY_MIN_HEIGHT = intPreferencesKey(PREF_MIN_HEIGHT)
        private val KEY_SHOW_SVG = booleanPreferencesKey(PREF_SHOW_SVG)
        private val KEY_SUBFOLDER_MODE = stringPreferencesKey(PREF_SUBFOLDER_MODE)
        private val KEY_FILENAME_TEMPLATE = stringPreferencesKey(PREF_FILENAME_TEMPLATE)
        private val KEY_WIFI_ONLY = booleanPreferencesKey(PREF_WIFI_ONLY)
        private val KEY_MAX_CONCURRENT = intPreferencesKey(PREF_MAX_CONCURRENT)
        private val KEY_ENABLED_PROVIDERS = stringSetPreferencesKey("enabled_providers")
        private val KEY_SEARCH_ENGINE = stringPreferencesKey(PREF_SEARCH_ENGINE)

        fun isWifiOnlySync(context: Context): Boolean {
            return context.applicationContext
                .getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_WIFI_ONLY, false)
        }

        fun getVideoQualityCapSync(context: Context): DownloaderVideoQuality {
            val raw = context.applicationContext
                .getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_VIDEO_QUALITY, "BEST")
            return DownloaderVideoQuality.fromId(raw)
        }

        fun isAudioOnlySync(context: Context): Boolean {
            return context.applicationContext
                .getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_AUDIO_ONLY, false)
        }

        fun getPreferredAudioFormatSync(context: Context): String {
            val raw = context.applicationContext
                .getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_AUDIO_FORMAT, "M4A")
            return raw?.trim()?.uppercase(Locale.ROOT)?.takeIf { it in setOf("M4A", "OPUS") } ?: "M4A"
        }

        fun getSubfolderModeSync(context: Context): DownloaderSubfolderMode {
            val raw = context.applicationContext
                .getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_SUBFOLDER_MODE, "FLAT")
            return DownloaderSubfolderMode.fromId(raw)
        }

        fun getEnabledProvidersSync(context: Context): Set<String> {
            return context.applicationContext
                .getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(PREF_ENABLED_PROVIDERS, DEFAULT_PROVIDERS)
                ?.ifEmpty { setOf("static") }
                ?: DEFAULT_PROVIDERS
        }

        fun getMaxConcurrentDownloadsSync(context: Context): Int {
            return context.applicationContext
                .getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_MAX_CONCURRENT, 3)
                .coerceIn(1, 6)
        }

        fun getFilenameTemplateSync(context: Context): String {
            return context.applicationContext
                .getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_FILENAME_TEMPLATE, DEFAULT_FILENAME_TEMPLATE)
                ?.ifBlank { DEFAULT_FILENAME_TEMPLATE }
                ?: DEFAULT_FILENAME_TEMPLATE
        }

        fun getSearchEngineSync(context: Context): BrowserSearchEngine {
            val raw = context.applicationContext
                .getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_SEARCH_ENGINE, BrowserSearchEngine.DUCKDUCKGO.name)
            return BrowserSearchEngine.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: BrowserSearchEngine.DUCKDUCKGO
        }

        fun resolveSubfolderName(
            context: Context,
            sourceDomain: String? = null,
            stackTitle: String? = null,
            explicitMode: DownloaderSubfolderMode? = null
        ): String? {
            return when (explicitMode ?: getSubfolderModeSync(context)) {
                DownloaderSubfolderMode.FLAT -> null
                DownloaderSubfolderMode.BY_DOMAIN -> {
                    val clean = (sourceDomain ?: "")
                        .trim()
                        .removePrefix("www.")
                        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        .trim('_')
                        .take(48)
                    clean.ifBlank { null }
                }
                DownloaderSubfolderMode.BY_STACK -> {
                    val clean = (stackTitle ?: sourceDomain ?: "")
                        .trim()
                        .replace(Regex("[^a-zA-Z0-9 _-]"), "_")
                        .replace(Regex("\\s+"), " ")
                        .trim('_', ' ')
                        .take(48)
                    clean.ifBlank { null }
                }
            }
        }
    }
}
