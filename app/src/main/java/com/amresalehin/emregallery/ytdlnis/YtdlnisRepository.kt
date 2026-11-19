package com.amresalehin.emregallery.ytdlnis

import android.content.Context
import android.webkit.CookieManager
import com.amresalehin.emregallery.data.VaultCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * Persistent state repository for YTDLnis inside Emre Gallery.
 * Manages download queue/history, duplicate detection, command templates,
 * observe sources, cookies, PO token, incognito mode, search history,
 * full JSON backup/restore, and cache/leftover cleanup.
 */
class YtdlnisRepository private constructor(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _downloads = MutableStateFlow<List<YtdlnisDownloadItem>>(emptyList())
    val downloads: StateFlow<List<YtdlnisDownloadItem>> = _downloads.asStateFlow()

    private val _commandTemplates = MutableStateFlow<List<YtdlnisCommandTemplate>>(emptyList())
    val commandTemplates: StateFlow<List<YtdlnisCommandTemplate>> = _commandTemplates.asStateFlow()

    private val _observeSources = MutableStateFlow<List<YtdlnisObserveSource>>(emptyList())
    val observeSources: StateFlow<List<YtdlnisObserveSource>> = _observeSources.asStateFlow()

    private val _cookies = MutableStateFlow<List<YtdlnisCookieEntry>>(emptyList())
    val cookies: StateFlow<List<YtdlnisCookieEntry>> = _cookies.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _incognitoMode = MutableStateFlow(false)
    val incognitoMode: StateFlow<Boolean> = _incognitoMode.asStateFlow()

    private val _quickDownloadMode = MutableStateFlow(false)
    val quickDownloadMode: StateFlow<Boolean> = _quickDownloadMode.asStateFlow()

    private val _youtubePoToken = MutableStateFlow("")
    val youtubePoToken: StateFlow<String> = _youtubePoToken.asStateFlow()

    private val _defaultFilenameTemplate = MutableStateFlow("%(title)s.%(ext)s")
    val defaultFilenameTemplate: StateFlow<String> = _defaultFilenameTemplate.asStateFlow()

    private val _defaultSubfolderMode = MutableStateFlow(YtdlnisSubfolderMode.NONE)
    val defaultSubfolderMode: StateFlow<YtdlnisSubfolderMode> = _defaultSubfolderMode.asStateFlow()

    init {
        loadAll()
    }

    private fun loadAll() {
        _incognitoMode.value = prefs.getBoolean(KEY_INCOGNITO, false)
        _quickDownloadMode.value = prefs.getBoolean(KEY_QUICK_DOWNLOAD, false)
        _youtubePoToken.value = VaultCrypto.decryptStringFromBase64(prefs.getString(KEY_PO_TOKEN, "") ?: "")
        _defaultFilenameTemplate.value = prefs.getString(KEY_DEFAULT_TEMPLATE, "%(title)s.%(ext)s") ?: "%(title)s.%(ext)s"
        _defaultSubfolderMode.value = YtdlnisSubfolderMode.fromId(prefs.getString(KEY_DEFAULT_SUBFOLDER, "none"))

        _downloads.value = parseJsonList(prefs.getString(KEY_DOWNLOADS, null)) { YtdlnisDownloadItem.fromJson(it) }
        val loadedTemplates = parseJsonList(prefs.getString(KEY_TEMPLATES, null)) { YtdlnisCommandTemplate.fromJson(it) }
        _commandTemplates.value = loadedTemplates.ifEmpty { YtdlnisCommandTemplate.defaultTemplates() }
        _observeSources.value = parseJsonList(prefs.getString(KEY_OBSERVE_SOURCES, null)) { YtdlnisObserveSource.fromJson(it) }

        val rawCookies = VaultCrypto.decryptStringFromBase64(prefs.getString(KEY_COOKIES, "") ?: "")
        _cookies.value = parseJsonList(rawCookies.ifBlank { null }) { YtdlnisCookieEntry.fromJson(it) }

        val histRaw = prefs.getString(KEY_SEARCH_HISTORY, null)
        val hist = mutableListOf<String>()
        if (!histRaw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(histRaw)
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i)
                    if (v.isNotBlank()) hist.add(v)
                }
            }
        }
        _searchHistory.value = hist
    }

    private inline fun <T> parseJsonList(raw: String?, parser: (JSONObject) -> T): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { add(parser(it)) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistDownloads() {
        // Incognito items are never written to persistent storage
        val persistable = _downloads.value.filterNot { it.incognito }
        val arr = JSONArray().apply { persistable.forEach { put(it.toJson()) } }
        prefs.edit().putString(KEY_DOWNLOADS, arr.toString()).apply()
    }

    private fun persistTemplates() {
        val arr = JSONArray().apply { _commandTemplates.value.forEach { put(it.toJson()) } }
        prefs.edit().putString(KEY_TEMPLATES, arr.toString()).apply()
    }

    private fun persistObserveSources() {
        val arr = JSONArray().apply { _observeSources.value.forEach { put(it.toJson()) } }
        prefs.edit().putString(KEY_OBSERVE_SOURCES, arr.toString()).apply()
    }

    private fun persistCookies() {
        val arr = JSONArray().apply { _cookies.value.forEach { put(it.toJson()) } }
        val encrypted = VaultCrypto.encryptStringToBase64(arr.toString())
        prefs.edit().putString(KEY_COOKIES, encrypted).apply()
    }

    private fun persistSearchHistory() {
        val arr = JSONArray(_searchHistory.value.take(30))
        prefs.edit().putString(KEY_SEARCH_HISTORY, arr.toString()).apply()
    }

    fun setIncognitoMode(enabled: Boolean) {
        _incognitoMode.value = enabled
        prefs.edit().putBoolean(KEY_INCOGNITO, enabled).apply()
    }

    fun setQuickDownloadMode(enabled: Boolean) {
        _quickDownloadMode.value = enabled
        prefs.edit().putBoolean(KEY_QUICK_DOWNLOAD, enabled).apply()
    }

    fun setYoutubePoToken(token: String) {
        val clean = token.trim()
        _youtubePoToken.value = clean
        prefs.edit().putString(KEY_PO_TOKEN, VaultCrypto.encryptStringToBase64(clean)).apply()
    }

    fun setDefaultFilenameTemplate(template: String) {
        val clean = template.trim().ifBlank { "%(title)s.%(ext)s" }
        _defaultFilenameTemplate.value = clean
        prefs.edit().putString(KEY_DEFAULT_TEMPLATE, clean).apply()
    }

    fun setDefaultSubfolderMode(mode: YtdlnisSubfolderMode) {
        _defaultSubfolderMode.value = mode
        prefs.edit().putString(KEY_DEFAULT_SUBFOLDER, mode.id).apply()
    }

    fun addSearchHistory(query: String) {
        if (_incognitoMode.value) return
        val clean = query.trim()
        if (clean.isBlank()) return
        val updated = listOf(clean) + _searchHistory.value.filterNot { it.equals(clean, ignoreCase = true) }
        _searchHistory.value = updated.take(30)
        persistSearchHistory()
    }

    fun removeSearchHistoryItem(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        _searchHistory.value = _searchHistory.value.filterNot { it.equals(clean, ignoreCase = true) }
        persistSearchHistory()
    }

    fun clearSearchHistory() {
        _searchHistory.value = emptyList()
        prefs.edit().remove(KEY_SEARCH_HISTORY).apply()
    }

    fun findDuplicate(url: String, type: YtdlnisDownloadType): YtdlnisDownloadItem? {
        val normalized = normalizeUrlKey(url)
        return _downloads.value.firstOrNull {
            normalizeUrlKey(it.url) == normalized &&
                it.type == type &&
                (it.status == YtdlnisDownloadStatus.COMPLETED ||
                    it.status == YtdlnisDownloadStatus.ACTIVE ||
                    it.status == YtdlnisDownloadStatus.QUEUED)
        }
    }

    fun upsertDownload(item: YtdlnisDownloadItem) {
        val effectiveItem = if (_incognitoMode.value) item.copy(incognito = true) else item
        val current = _downloads.value.toMutableList()
        val idx = current.indexOfFirst { it.id == effectiveItem.id }
        if (idx >= 0) {
            current[idx] = effectiveItem
        } else {
            current.add(0, effectiveItem)
        }
        _downloads.value = current
        persistDownloads()
    }

    fun updateDownload(id: String, transform: (YtdlnisDownloadItem) -> YtdlnisDownloadItem) {
        val current = _downloads.value.toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) {
            current[idx] = transform(current[idx])
            _downloads.value = current
            persistDownloads()
        }
    }

    fun getDownloadById(id: String): YtdlnisDownloadItem? = _downloads.value.firstOrNull { it.id == id }

    fun removeDownload(id: String) {
        _downloads.value = _downloads.value.filterNot { it.id == id }
        persistDownloads()
    }

    fun clearCompletedAndCancelled() {
        _downloads.value = _downloads.value.filterNot {
            it.status == YtdlnisDownloadStatus.COMPLETED || it.status == YtdlnisDownloadStatus.CANCELLED
        }
        persistDownloads()
    }

    // Command Templates CRUD
    fun upsertTemplate(template: YtdlnisCommandTemplate) {
        val current = _commandTemplates.value.toMutableList()
        val idx = current.indexOfFirst { it.id == template.id }
        if (idx >= 0) current[idx] = template else current.add(0, template)
        _commandTemplates.value = current
        persistTemplates()
    }

    fun deleteTemplate(id: String) {
        _commandTemplates.value = _commandTemplates.value.filterNot { it.id == id }
        persistTemplates()
    }

    // Observe Sources CRUD
    fun upsertObserveSource(source: YtdlnisObserveSource) {
        val current = _observeSources.value.toMutableList()
        val idx = current.indexOfFirst { it.id == source.id }
        if (idx >= 0) current[idx] = source else current.add(0, source)
        _observeSources.value = current
        persistObserveSources()
    }

    fun deleteObserveSource(id: String) {
        _observeSources.value = _observeSources.value.filterNot { it.id == id }
        persistObserveSources()
    }

    // Cookies management (manual Netscape format + import from in-app browser WebView)
    fun upsertCookie(domain: String, cookiesNetscape: String) {
        val cleanDomain = com.amresalehin.emregallery.data.HostRateLimiter.canonicalHost(domain)
        if (cleanDomain.isBlank() || cookiesNetscape.isBlank()) return
        val current = _cookies.value.toMutableList()
        val idx = current.indexOfFirst { it.domain.equals(cleanDomain, ignoreCase = true) }
        val entry = YtdlnisCookieEntry(
            id = if (idx >= 0) current[idx].id else java.util.UUID.randomUUID().toString(),
            domain = cleanDomain,
            cookiesNetscape = cookiesNetscape.trim(),
            updatedAtMillis = System.currentTimeMillis()
        )
        if (idx >= 0) current[idx] = entry else current.add(0, entry)
        _cookies.value = current
        persistCookies()
    }

    fun deleteCookie(id: String) {
        _cookies.value = _cookies.value.filterNot { it.id == id }
        persistCookies()
    }

    /**
     * Imports active session cookies for a URL from Android's WebView CookieManager
     * (populated when logging in through Emre Gallery's built-in browser) and converts
     * them into Netscape cookies.txt format for yt-dlp.
     */
    fun importCookiesFromWebView(url: String): Boolean {
        val rawHost = extractHost(url) ?: return false
        val canonical = com.amresalehin.emregallery.data.HostRateLimiter.canonicalHost(rawHost)
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return false

        val domainUrls = if (canonical == "youtube.com") {
            listOf(
                url to ".youtube.com",
                "https://www.youtube.com" to ".youtube.com",
                "https://youtube.com" to ".youtube.com",
                "https://accounts.google.com" to ".google.com",
                "https://google.com" to ".google.com"
            )
        } else {
            listOf(url to ".$canonical", "https://$canonical" to ".$canonical")
        }

        val seenKeys = LinkedHashSet<String>()
        val expiry = (System.currentTimeMillis() / 1000L) + 86400L * 30L
        val lines = mutableListOf<String>()
        for ((targetUrl, netscapeDomain) in domainUrls) {
            val header = runCatching { cm.getCookie(targetUrl) }.getOrNull().orEmpty()
            if (header.isBlank()) continue
            header.split(";").forEach { pair ->
                val parts = pair.trim().split("=", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    val name = parts[0].trim()
                    val value = parts[1].trim()
                    if (seenKeys.add("$netscapeDomain|$name")) {
                        lines.add("$netscapeDomain\tTRUE\t/\tTRUE\t$expiry\t$name\t$value")
                    }
                }
            }
        }
        if (lines.isEmpty()) return false

        val netscape = buildString {
            appendLine("# Netscape HTTP Cookie File")
            lines.forEach { appendLine(it) }
        }
        upsertCookie(canonical, netscape)
        return true
    }

    fun findCookiesForUrl(url: String): String? {
        val host = extractHost(url) ?: return null
        val canonical = com.amresalehin.emregallery.data.HostRateLimiter.canonicalHost(host)
        val stored = _cookies.value.firstOrNull { entry ->
            val entryCanonical = com.amresalehin.emregallery.data.HostRateLimiter.canonicalHost(entry.domain)
            canonical == entryCanonical || host == entry.domain || host.endsWith(".${entry.domain}")
        }?.cookiesNetscape
        if (!stored.isNullOrBlank()) return stored

        // Cross-stack fallback: reuse cookies captured in the general downloader's shared OkHttp cookie jar
        val jarHeader = com.amresalehin.emregallery.data.OkHttpDownloader.sharedCookieJar.getCookieString(url)
        if (jarHeader.isBlank()) return null
        val expiry = (System.currentTimeMillis() / 1000L) + 86400L * 7L
        return buildString {
            appendLine("# Netscape HTTP Cookie File")
            jarHeader.split(";").forEach { pair ->
                val parts = pair.trim().split("=", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    appendLine(".$canonical\tTRUE\t/\tTRUE\t$expiry\t${parts[0].trim()}\t${parts[1].trim()}")
                }
            }
        }
    }

    // Full Backup & Restore (YTDLnis feature)
    fun exportBackupJson(): String {
        val root = JSONObject().apply {
            put("app", "EmreGallery-YTDLnis")
            put("schema_version", 1)
            put("exported_at_millis", System.currentTimeMillis())
            put("incognito_mode", _incognitoMode.value)
            put("quick_download_mode", _quickDownloadMode.value)
            put("default_filename_template", _defaultFilenameTemplate.value)
            put("default_subfolder_mode", _defaultSubfolderMode.value.id)
            put("templates", JSONArray().apply { _commandTemplates.value.forEach { put(it.toJson()) } })
            put("observe_sources", JSONArray().apply { _observeSources.value.forEach { put(it.toJson()) } })
            put("downloads", JSONArray().apply {
                _downloads.value.filterNot { it.incognito }.forEach { put(it.toJson()) }
            })
            put("search_history", JSONArray(_searchHistory.value))
        }
        return root.toString(2)
    }

    fun importBackupJson(jsonText: String): Result<Int> = runCatching {
        val root = JSONObject(jsonText)
        var importedCount = 0
        if (root.has("incognito_mode")) setIncognitoMode(root.optBoolean("incognito_mode", false))
        if (root.has("quick_download_mode")) setQuickDownloadMode(root.optBoolean("quick_download_mode", false))
        if (root.has("default_filename_template")) setDefaultFilenameTemplate(root.optString("default_filename_template"))
        if (root.has("default_subfolder_mode")) {
            setDefaultSubfolderMode(YtdlnisSubfolderMode.fromId(root.optString("default_subfolder_mode")))
        }
        root.optJSONArray("templates")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let {
                    upsertTemplate(YtdlnisCommandTemplate.fromJson(it))
                    importedCount++
                }
            }
        }
        root.optJSONArray("observe_sources")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let {
                    upsertObserveSource(YtdlnisObserveSource.fromJson(it))
                    importedCount++
                }
            }
        }
        root.optJSONArray("downloads")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let {
                    upsertDownload(YtdlnisDownloadItem.fromJson(it))
                    importedCount++
                }
            }
        }
        importedCount
    }

    // Cache & leftover files manager
    fun getTempWorkDir(): File {
        val dir = File(appContext.cacheDir, "ytdlnis_work")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun calculateCacheSizeBytes(): Long {
        val dir = getTempWorkDir()
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun clearCacheAndLeftovers(): Long {
        val dir = getTempWorkDir()
        var freed = 0L
        dir.walkBottomUp().forEach { f ->
            if (f != dir) {
                if (f.isFile) freed += f.length()
                f.delete()
            }
        }
        return freed
    }

    companion object {
        private const val PREFS_NAME = "emre_ytdlnis_prefs"
        private const val KEY_DOWNLOADS = "downloads_json"
        private const val KEY_TEMPLATES = "templates_json"
        private const val KEY_OBSERVE_SOURCES = "observe_sources_json"
        private const val KEY_COOKIES = "cookies_enc_json"
        private const val KEY_SEARCH_HISTORY = "search_history_json"
        private const val KEY_INCOGNITO = "incognito_mode"
        private const val KEY_QUICK_DOWNLOAD = "quick_download_mode"
        private const val KEY_PO_TOKEN = "youtube_po_token_enc"
        private const val KEY_DEFAULT_TEMPLATE = "default_filename_template"
        private const val KEY_DEFAULT_SUBFOLDER = "default_subfolder_mode"

        @Volatile
        private var instance: YtdlnisRepository? = null

        fun getInstance(context: Context): YtdlnisRepository {
            return instance ?: synchronized(this) {
                instance ?: YtdlnisRepository(context.applicationContext).also { instance = it }
            }
        }

        fun normalizeUrlKey(url: String): String {
            return url.trim()
                .lowercase()
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
                .trimEnd('/')
        }

        fun extractHost(url: String): String? {
            return runCatching {
                URI(url.trim()).host?.lowercase()?.removePrefix("www.")
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
    }
}
