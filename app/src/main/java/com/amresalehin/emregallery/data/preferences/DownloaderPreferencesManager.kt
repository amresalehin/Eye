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
    Q360P("360P", "360p Data Saver", 360),
    Q240P("240P", "240p Low", 240),
    Q144P("144P", "144p Minimal", 144);



    suspend fun setRedditConfig(config: RedditDlConfig) {
        val encSecret = if (config.clientSecret.isNotBlank()) com.amresalehin.emregallery.data.VaultCrypto.encryptStringToBase64(config.clientSecret) else ""
        val encPass = if (config.password.isNotBlank()) com.amresalehin.emregallery.data.VaultCrypto.encryptStringToBase64(config.password) else ""
        val encToken = if (config.refreshToken.isNotBlank()) com.amresalehin.emregallery.data.VaultCrypto.encryptStringToBase64(config.refreshToken) else ""
        syncPrefs.edit()
            .putString(PREF_REDDIT_CLIENT_ID, config.clientId)
            .putString(PREF_REDDIT_CLIENT_SECRET, encSecret)
            .putString(PREF_REDDIT_USERNAME, config.username)
            .putString(PREF_REDDIT_PASSWORD, encPass)
            .putString(PREF_REDDIT_REFRESH_TOKEN, encToken)
            .putInt(PREF_REDDIT_LIMIT, config.limit.coerceIn(1, 500))
            .putString(PREF_REDDIT_AFTER_DATE, config.afterDate)
            .putString(PREF_REDDIT_BEFORE_DATE, config.beforeDate)
            .putBoolean(PREF_REDDIT_INCLUDE_COMMENTS, config.includeComments)
            .putBoolean(PREF_REDDIT_INCLUDE_AUTHORS, config.includePostAuthors)
            .apply()
    }

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

enum class DownloaderFormatMode(val id: String, val label: String) {
    VIDEO_AUDIO("video_audio", "Video + Audio"),
    VIDEO_ONLY("video_only", "Video Only"),
    AUDIO_ONLY("audio_only", "Audio Only");

    companion object {
        fun fromId(id: String?): DownloaderFormatMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: VIDEO_AUDIO
    }
}

data class RedditDlConfig(
    val clientId: String = "",
    val clientSecret: String = "",
    val username: String = "",
    val password: String = "",
    val refreshToken: String = "",
    val limit: Int = 50,
    val afterDate: String = "",
    val beforeDate: String = "",
    val includeComments: Boolean = false,
    val includePostAuthors: Boolean = false
)

class DownloaderPreferencesManager(private val context: Context) {

    val speedBoostEnabled: Flow<Boolean> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_SPEED_BOOST] ?: syncPrefs.getBoolean(PREF_SPEED_BOOST, true)
    }.distinctUntilChanged()

    val recursiveCrawlEnabled: Flow<Boolean> = appContext.galleryDataStore.data.map { prefs ->
        prefs[KEY_RECURSIVE_CRAWL] ?: syncPrefs.getBoolean(PREF_RECURSIVE_CRAWL, false)
    }.distinctUntilChanged()

    val formatMode: Flow<DownloaderFormatMode> = appContext.galleryDataStore.data.map { prefs ->
        DownloaderFormatMode.fromId(prefs[KEY_FORMAT_MODE] ?: syncPrefs.getString(PREF_FORMAT_MODE, DownloaderFormatMode.VIDEO_AUDIO.id))
    }.distinctUntilChanged()

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

    suspend fun setSpeedBoostEnabled(enabled: Boolean) {
        syncPrefs.edit().putBoolean(PREF_SPEED_BOOST, enabled).apply()
        appContext.galleryDataStore.edit { it[KEY_SPEED_BOOST] = enabled }
    }

    suspend fun setRecursiveCrawlEnabled(enabled: Boolean) {
        syncPrefs.edit().putBoolean(PREF_RECURSIVE_CRAWL, enabled).apply()
        appContext.galleryDataStore.edit { it[KEY_RECURSIVE_CRAWL] = enabled }
    }

    suspend fun setFormatMode(mode: DownloaderFormatMode) {
        syncPrefs.edit().putString(PREF_FORMAT_MODE, mode.id).apply()
        appContext.galleryDataStore.edit { it[KEY_FORMAT_MODE] = mode.id }
    }

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
        private const val PREF_SPEED_BOOST = "downloader_speed_boost_enabled"
        private const val PREF_RECURSIVE_CRAWL = "downloader_recursive_crawl_enabled"
        private const val PREF_FORMAT_MODE = "downloader_format_mode"

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

        private val KEY_SPEED_BOOST = booleanPreferencesKey("downloader_speed_boost_enabled")
        private val KEY_RECURSIVE_CRAWL = booleanPreferencesKey("downloader_recursive_crawl_enabled")
        private val KEY_FORMAT_MODE = stringPreferencesKey("downloader_format_mode")

        private const val PREF_SPEED_BOOST = "speed_boost_enabled"
        private const val PREF_RECURSIVE_CRAWL = "recursive_crawl_enabled"
        private const val PREF_FORMAT_MODE = "format_mode"
        private const val PREF_PROXY_ENABLED = "proxy_enabled"
        private const val PREF_PROXY_URL = "proxy_url"
        private const val PREF_NETWORK_TIMEOUT = "network_timeout_seconds"
        private const val PREF_RETRY_COUNT = "retry_count"
        private const val PREF_DOWNLOAD_PLAYLIST = "download_playlist"
        private const val PREF_DOWNLOAD_SUBTITLES = "download_subtitles"
        private const val PREF_INCLUDE_AUTO_SUBTITLES = "include_auto_subtitles"
        private const val PREF_SUBTITLE_LANGS = "subtitle_languages"
        private const val PREF_REDDIT_CLIENT_ID = "reddit_dl_client_id"
        private const val PREF_REDDIT_CLIENT_SECRET = "reddit_dl_client_secret"
        private const val PREF_REDDIT_USERNAME = "reddit_dl_username"
        private const val PREF_REDDIT_PASSWORD = "reddit_dl_password"
        private const val PREF_REDDIT_REFRESH_TOKEN = "reddit_dl_refresh_token"
        private const val PREF_REDDIT_LIMIT = "reddit_dl_limit"
        private const val PREF_REDDIT_AFTER_DATE = "reddit_dl_after_date"
        private const val PREF_REDDIT_BEFORE_DATE = "reddit_dl_before_date"
        private const val PREF_REDDIT_INCLUDE_COMMENTS = "reddit_dl_include_comments"
        private const val PREF_REDDIT_INCLUDE_AUTHORS = "reddit_dl_include_authors"

        fun getActiveProxyUrlSync(context: Context): String? {
            val sp = context.applicationContext.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            if (!sp.getBoolean(PREF_PROXY_ENABLED, false)) return null
            return sp.getString(PREF_PROXY_URL, null)?.trim()?.takeIf { it.isNotBlank() }
        }

        fun getFormatModeSync(context: Context): DownloaderFormatMode {
            val sp = context.applicationContext.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            return DownloaderFormatMode.fromId(sp.getString(PREF_FORMAT_MODE, DownloaderFormatMode.VIDEO_AUDIO.id))
        }

        fun getNetworkTimeoutSync(context: Context): Int {
            val sp = context.applicationContext.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            return sp.getInt(PREF_NETWORK_TIMEOUT, 30).coerceIn(10, 180)
        }

        fun getRetryCountSync(context: Context): Int {
            val sp = context.applicationContext.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            return sp.getInt(PREF_RETRY_COUNT, 3).coerceIn(0, 10)
        }

        fun isDownloadPlaylistSync(context: Context): Boolean {
            val sp = context.applicationContext.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            return sp.getBoolean(PREF_DOWNLOAD_PLAYLIST, false)
        }

        fun isDownloadSubtitlesSync(context: Context): Boolean {
            val sp = context.applicationContext.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            return sp.getBoolean(PREF_DOWNLOAD_SUBTITLES, false)
        }

        fun isIncludeAutoSubtitlesSync(context: Context): Boolean {
            val sp = context.applicationContext.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            return sp.getBoolean(PREF_INCLUDE_AUTO_SUBTITLES, false)
        }

        fun getSubtitleLanguagesSync(context: Context): List<String> {
            val sp = context.applicationContext.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            val raw = sp.getString(PREF_SUBTITLE_LANGS, "en") ?: "en"
            return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf("en") }
        }

        fun isRecursiveCrawlEnabledSync(context: Context): Boolean {
            val sp = context.applicationContext.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            return sp.getBoolean(PREF_RECURSIVE_CRAWL, false)
        }

        fun getRecursiveCrawlConfigSync(context: Context): com.amresalehin.emregallery.data.RecursiveCrawlConfig {
            val sp = context.applicationContext.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            return com.amresalehin.emregallery.data.RecursiveCrawlConfig(
                maxDepth = sp.getInt("recursive_crawl_max_depth", 2).coerceIn(1, 5),
                maxPages = sp.getInt("recursive_crawl_max_pages", 15).coerceIn(1, 100),
                sameDomainOnly = sp.getBoolean("recursive_crawl_same_domain", true),
                delayMs = sp.getLong("recursive_crawl_delay_ms", 400L).coerceIn(100L, 5000L)
            )
        }

        fun getRedditConfigSync(context: Context): RedditDlConfig {
            val sp = context.applicationContext.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            fun dec(key: String): String {
                val raw = sp.getString(key, "") ?: ""
                if (raw.isBlank()) return ""
                return runCatching { com.amresalehin.emregallery.data.VaultCrypto.decryptStringFromBase64(raw) }.getOrDefault(raw)
            }
            return RedditDlConfig(
                clientId = sp.getString(PREF_REDDIT_CLIENT_ID, "") ?: "",
                clientSecret = dec(PREF_REDDIT_CLIENT_SECRET),
                username = sp.getString(PREF_REDDIT_USERNAME, "") ?: "",
                password = dec(PREF_REDDIT_PASSWORD),
                refreshToken = dec(PREF_REDDIT_REFRESH_TOKEN),
                limit = sp.getInt(PREF_REDDIT_LIMIT, 50),
                afterDate = sp.getString(PREF_REDDIT_AFTER_DATE, "") ?: "",
                beforeDate = sp.getString(PREF_REDDIT_BEFORE_DATE, "") ?: "",
                includeComments = sp.getBoolean(PREF_REDDIT_INCLUDE_COMMENTS, false),
                includePostAuthors = sp.getBoolean(PREF_REDDIT_INCLUDE_AUTHORS, false)
            )
        }
    }
}
