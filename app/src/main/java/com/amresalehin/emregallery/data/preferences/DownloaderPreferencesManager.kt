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

    // Persistent full-browser preferences. Defaults preserve the current browser behavior.
    val browserJavaScriptEnabled: Flow<Boolean> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_BROWSER_JAVASCRIPT] ?: syncPrefs.getBoolean(PREF_BROWSER_JAVASCRIPT, true)
    }.distinctUntilChanged()

    val browserAdBlockingEnabled: Flow<Boolean> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_BROWSER_AD_BLOCKING] ?: syncPrefs.getBoolean(PREF_BROWSER_AD_BLOCKING, true)
    }.distinctUntilChanged()

    val browserLoadImages: Flow<Boolean> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_BROWSER_LOAD_IMAGES] ?: syncPrefs.getBoolean(PREF_BROWSER_LOAD_IMAGES, true)
    }.distinctUntilChanged()

    val browserThirdPartyCookies: Flow<Boolean> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_BROWSER_THIRD_PARTY_COOKIES] ?: syncPrefs.getBoolean(PREF_BROWSER_THIRD_PARTY_COOKIES, false)
    }.distinctUntilChanged()

    val browserSaveHistory: Flow<Boolean> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_BROWSER_SAVE_HISTORY] ?: syncPrefs.getBoolean(PREF_BROWSER_SAVE_HISTORY, true)
    }.distinctUntilChanged()

    val browserDesktopSiteByDefault: Flow<Boolean> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_BROWSER_DESKTOP_BY_DEFAULT] ?: syncPrefs.getBoolean(PREF_BROWSER_DESKTOP_BY_DEFAULT, false)
    }.distinctUntilChanged()

    val browserTextZoom: Flow<Int> = appContext.galleryDataStore.data.map { prefs ->
        (prefs[KEY_BROWSER_TEXT_ZOOM] ?: syncPrefs.getInt(PREF_BROWSER_TEXT_ZOOM, 100)).coerceIn(50, 200)
    }.distinctUntilChanged()

    val browserSafeBrowsingEnabled: Flow<Boolean> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_BROWSER_SAFE_BROWSING] ?: syncPrefs.getBoolean(PREF_BROWSER_SAFE_BROWSING, true)
    }.distinctUntilChanged()

    val browserBlockPopups: Flow<Boolean> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_BROWSER_BLOCK_POPUPS] ?: syncPrefs.getBoolean(PREF_BROWSER_BLOCK_POPUPS, true)
    }.distinctUntilChanged()

    val browserBlockMixedContent: Flow<Boolean> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_BROWSER_BLOCK_MIXED_CONTENT] ?: syncPrefs.getBoolean(PREF_BROWSER_BLOCK_MIXED_CONTENT, true)
    }.distinctUntilChanged()

    val browserAllowAutoplay: Flow<Boolean> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_BROWSER_ALLOW_AUTOPLAY] ?: syncPrefs.getBoolean(PREF_BROWSER_ALLOW_AUTOPLAY, true)
    }.distinctUntilChanged()

    val browserPinchZoomEnabled: Flow<Boolean> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_BROWSER_PINCH_ZOOM] ?: syncPrefs.getBoolean(PREF_BROWSER_PINCH_ZOOM, true)
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
        val clean = format.trim().uppercase(Locale.ROOT).takeIf { it in setOf("M4A", "MP3", "OPUS") } ?: "M4A"
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
        appContext.galleryDataStore.edit { it[KEY_ENABLED_PROVIDERS] = safe }
    }

    suspend fun setSearchEngine(engine: BrowserSearchEngine) {
        syncPrefs.edit().putString(PREF_SEARCH_ENGINE, engine.name).apply()
        appContext.galleryDataStore.edit { it[KEY_SEARCH_ENGINE] = engine.name }
    }

    suspend fun setBrowserJavaScriptEnabled(enabled: Boolean) {
        syncPrefs.edit().putBoolean(PREF_BROWSER_JAVASCRIPT, enabled).apply()
        appContext.galleryDataStore.edit { it[KEY_BROWSER_JAVASCRIPT] = enabled }
    }

    suspend fun setBrowserAdBlockingEnabled(enabled: Boolean) {
        syncPrefs.edit().putBoolean(PREF_BROWSER_AD_BLOCKING, enabled).apply()
        appContext.galleryDataStore.edit { it[KEY_BROWSER_AD_BLOCKING] = enabled }
    }

    suspend fun setBrowserLoadImages(enabled: Boolean) {
        syncPrefs.edit().putBoolean(PREF_BROWSER_LOAD_IMAGES, enabled).apply()
        appContext.galleryDataStore.edit { it[KEY_BROWSER_LOAD_IMAGES] = enabled }
    }

    suspend fun setBrowserThirdPartyCookies(enabled: Boolean) {
        syncPrefs.edit().putBoolean(PREF_BROWSER_THIRD_PARTY_COOKIES, enabled).apply()
        appContext.galleryDataStore.edit { it[KEY_BROWSER_THIRD_PARTY_COOKIES] = enabled }
    }

    suspend fun setBrowserSaveHistory(enabled: Boolean) {
        syncPrefs.edit().putBoolean(PREF_BROWSER_SAVE_HISTORY, enabled).apply()
        appContext.galleryDataStore.edit { it[KEY_BROWSER_SAVE_HISTORY] = enabled }
    }

    suspend fun setBrowserDesktopSiteByDefault(enabled: Boolean) {
        syncPrefs.edit().putBoolean(PREF_BROWSER_DESKTOP_BY_DEFAULT, enabled).apply()
        appContext.galleryDataStore.edit { it[KEY_BROWSER_DESKTOP_BY_DEFAULT] = enabled }
    }

    suspend fun setBrowserTextZoom(zoom: Int) {
        val clamped = zoom.coerceIn(50, 200)
        syncPrefs.edit().putInt(PREF_BROWSER_TEXT_ZOOM, clamped).apply()
        appContext.galleryDataStore.edit { it[KEY_BROWSER_TEXT_ZOOM] = clamped }
    }

    suspend fun setBrowserSafeBrowsingEnabled(enabled: Boolean) {
        syncPrefs.edit().putBoolean(PREF_BROWSER_SAFE_BROWSING, enabled).apply()
        appContext.galleryDataStore.edit { it[KEY_BROWSER_SAFE_BROWSING] = enabled }
    }

    suspend fun setBrowserBlockPopups(enabled: Boolean) {
        syncPrefs.edit().putBoolean(PREF_BROWSER_BLOCK_POPUPS, enabled).apply()
        appContext.galleryDataStore.edit { it[KEY_BROWSER_BLOCK_POPUPS] = enabled }
    }

    suspend fun setBrowserBlockMixedContent(enabled: Boolean) {
        syncPrefs.edit().putBoolean(PREF_BROWSER_BLOCK_MIXED_CONTENT, enabled).apply()
        appContext.galleryDataStore.edit { it[KEY_BROWSER_BLOCK_MIXED_CONTENT] = enabled }
    }

    suspend fun setBrowserAllowAutoplay(enabled: Boolean) {
        syncPrefs.edit().putBoolean(PREF_BROWSER_ALLOW_AUTOPLAY, enabled).apply()
        appContext.galleryDataStore.edit { it[KEY_BROWSER_ALLOW_AUTOPLAY] = enabled }
    }

    suspend fun setBrowserPinchZoomEnabled(enabled: Boolean) {
        syncPrefs.edit().putBoolean(PREF_BROWSER_PINCH_ZOOM, enabled).apply()
        appContext.galleryDataStore.edit { it[KEY_BROWSER_PINCH_ZOOM] = enabled }
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
        private const val PREF_BROWSER_JAVASCRIPT = "browser_javascript_enabled"
        private const val PREF_BROWSER_AD_BLOCKING = "browser_ad_blocking_enabled"
        private const val PREF_BROWSER_LOAD_IMAGES = "browser_load_images"
        private const val PREF_BROWSER_THIRD_PARTY_COOKIES = "browser_third_party_cookies"
        private const val PREF_BROWSER_SAVE_HISTORY = "browser_save_history"
        private const val PREF_BROWSER_DESKTOP_BY_DEFAULT = "browser_desktop_site_by_default"
        private const val PREF_BROWSER_TEXT_ZOOM = "browser_text_zoom"
        private const val PREF_BROWSER_SAFE_BROWSING = "browser_safe_browsing"
        private const val PREF_BROWSER_BLOCK_POPUPS = "browser_block_popups"
        private const val PREF_BROWSER_BLOCK_MIXED_CONTENT = "browser_block_mixed_content"
        private const val PREF_BROWSER_ALLOW_AUTOPLAY = "browser_allow_autoplay"
        private const val PREF_BROWSER_PINCH_ZOOM = "browser_pinch_zoom"

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
        private val KEY_BROWSER_JAVASCRIPT = booleanPreferencesKey(PREF_BROWSER_JAVASCRIPT)
        private val KEY_BROWSER_AD_BLOCKING = booleanPreferencesKey(PREF_BROWSER_AD_BLOCKING)
        private val KEY_BROWSER_LOAD_IMAGES = booleanPreferencesKey(PREF_BROWSER_LOAD_IMAGES)
        private val KEY_BROWSER_THIRD_PARTY_COOKIES = booleanPreferencesKey(PREF_BROWSER_THIRD_PARTY_COOKIES)
        private val KEY_BROWSER_SAVE_HISTORY = booleanPreferencesKey(PREF_BROWSER_SAVE_HISTORY)
        private val KEY_BROWSER_DESKTOP_BY_DEFAULT = booleanPreferencesKey(PREF_BROWSER_DESKTOP_BY_DEFAULT)
        private val KEY_BROWSER_TEXT_ZOOM = intPreferencesKey(PREF_BROWSER_TEXT_ZOOM)
        private val KEY_BROWSER_SAFE_BROWSING = booleanPreferencesKey(PREF_BROWSER_SAFE_BROWSING)
        private val KEY_BROWSER_BLOCK_POPUPS = booleanPreferencesKey(PREF_BROWSER_BLOCK_POPUPS)
        private val KEY_BROWSER_BLOCK_MIXED_CONTENT = booleanPreferencesKey(PREF_BROWSER_BLOCK_MIXED_CONTENT)
        private val KEY_BROWSER_ALLOW_AUTOPLAY = booleanPreferencesKey(PREF_BROWSER_ALLOW_AUTOPLAY)
        private val KEY_BROWSER_PINCH_ZOOM = booleanPreferencesKey(PREF_BROWSER_PINCH_ZOOM)

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

        fun getSubfolderModeSync(context: Context): DownloaderSubfolderMode {
            val raw = context.applicationContext
                .getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_SUBFOLDER_MODE, "FLAT")
            return DownloaderSubfolderMode.fromId(raw)
        }

        fun getSearchEngineSync(context: Context): BrowserSearchEngine {
            val raw = context.applicationContext
                .getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_SEARCH_ENGINE, BrowserSearchEngine.DUCKDUCKGO.name)
            return BrowserSearchEngine.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: BrowserSearchEngine.DUCKDUCKGO
        }

        fun getBrowserDesktopSiteByDefaultSync(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_BROWSER_DESKTOP_BY_DEFAULT, false)

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
