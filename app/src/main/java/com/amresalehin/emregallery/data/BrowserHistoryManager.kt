package com.amresalehin.emregallery.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

data class BrowserSite(
    val url: String,
    val title: String,
    val domain: String,
    val visitCount: Int = 1,
    val lastVisitedMillis: Long = System.currentTimeMillis()
)

/**
 * Persists browser history and user-saved home shortcuts.
 */
class BrowserHistoryManager(private val context: Context) {

    private val historyKey = stringPreferencesKey("browser_sites_history_v1")
    private val savedSitesKey = stringPreferencesKey("browser_saved_sites_v1")

    // No hardcoded sites; shortcuts and recent sites are based on the user's activity.
    val defaultTopSites: List<BrowserSite> = emptyList()

    val allSites: Flow<List<BrowserSite>> = context.galleryDataStore.data.map { prefs ->
        decodeSites(prefs[historyKey])
    }.distinctUntilChanged()

    val savedSites: Flow<List<BrowserSite>> = context.galleryDataStore.data.map { prefs ->
        decodeSites(prefs[savedSitesKey]).sortedByDescending { it.lastVisitedMillis }
    }.distinctUntilChanged()

    suspend fun recordVisit(rawUrl: String, title: String? = null) {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank() || trimmed == "about:blank" || trimmed.startsWith("javascript:")) return

        val domain = runCatching {
            URL(trimmed).host.removePrefix("www.")
        }.getOrDefault("").ifBlank {
            trimmed.substringAfter("://").substringBefore("/").removePrefix("www.")
        }
        if (domain.isBlank()) return

        val displayTitle = title?.takeIf {
            it.isNotBlank() && it != "about:blank" && !it.startsWith("http")
        } ?: domain.substringBefore('.').replaceFirstChar { it.uppercase() }

        context.galleryDataStore.edit { prefs ->
            val list = decodeSites(prefs[historyKey]).toMutableList()
            val existingIndex = list.indexOfFirst {
                it.domain.equals(domain, ignoreCase = true) ||
                    it.url.equals(trimmed, ignoreCase = true)
            }

            if (existingIndex >= 0) {
                val existing = list[existingIndex]
                list[existingIndex] = existing.copy(
                    url = trimmed,
                    title = if (displayTitle.isNotBlank() && displayTitle != existing.domain) {
                        displayTitle
                    } else {
                        existing.title
                    },
                    visitCount = existing.visitCount + 1,
                    lastVisitedMillis = System.currentTimeMillis()
                )
            } else {
                list.add(
                    BrowserSite(
                        url = trimmed,
                        title = displayTitle,
                        domain = domain,
                        visitCount = 1,
                        lastVisitedMillis = System.currentTimeMillis()
                    )
                )
            }

            prefs[historyKey] = encodeSites(list.sortedByDescending { it.lastVisitedMillis }.take(30))
        }
    }

    /**
     * Saves one site as a persistent shortcut on the browser home page.
     * Saved shortcuts are stored separately so clearing browsing history does not remove them.
     */
    suspend fun saveSite(rawUrl: String, title: String? = null) {
        val site = createSavableSite(rawUrl, title) ?: return

        context.galleryDataStore.edit { prefs ->
            val list = decodeSites(prefs[savedSitesKey]).toMutableList()
            val existingIndex = list.indexOfFirst {
                it.domain.equals(site.domain, ignoreCase = true) ||
                    it.url.equals(site.url, ignoreCase = true)
            }

            if (existingIndex >= 0) {
                val existing = list[existingIndex]
                list[existingIndex] = existing.copy(
                    url = site.url,
                    title = site.title.ifBlank { existing.title },
                    lastVisitedMillis = System.currentTimeMillis()
                )
            } else {
                list.add(0, site)
            }

            prefs[savedSitesKey] = encodeSites(
                list.sortedByDescending { it.lastVisitedMillis }.take(30)
            )
        }
    }

    /**
     * Removes the saved shortcut for the supplied site/domain only.
     * Browsing history remains untouched.
     */
    suspend fun removeSavedSite(rawUrl: String) {
        val site = createSavableSite(rawUrl, null) ?: return
        context.galleryDataStore.edit { prefs ->
            val remaining = decodeSites(prefs[savedSitesKey]).filterNot {
                it.domain.equals(site.domain, ignoreCase = true) ||
                    it.url.equals(site.url, ignoreCase = true)
            }
            if (remaining.isEmpty()) {
                prefs.remove(savedSitesKey)
            } else {
                prefs[savedSitesKey] = encodeSites(remaining)
            }
        }
    }

    suspend fun clearHistory() {
        context.galleryDataStore.edit { prefs ->
            prefs.remove(historyKey)
        }
    }

    private fun createSavableSite(rawUrl: String, title: String?): BrowserSite? {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) return null

        val domain = runCatching { URL(trimmed).host.removePrefix("www.").lowercase() }
            .getOrDefault("")
        if (domain.isBlank()) return null

        val displayTitle = title?.takeIf {
            it.isNotBlank() && it != "about:blank" && !it.startsWith("http")
        } ?: domain.substringBefore('.').replaceFirstChar { it.uppercase() }

        return BrowserSite(
            url = trimmed,
            title = displayTitle,
            domain = domain,
            visitCount = 0,
            lastVisitedMillis = System.currentTimeMillis()
        )
    }

    private fun decodeSites(raw: String?): List<BrowserSite> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        BrowserSite(
                            url = obj.getString("url"),
                            title = obj.optString("title", ""),
                            domain = obj.optString("domain", ""),
                            visitCount = obj.optInt("count", 1),
                            lastVisitedMillis = obj.optLong("time", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeSites(sites: List<BrowserSite>): String {
        val array = JSONArray()
        sites.forEach { site ->
            array.put(
                JSONObject().apply {
                    put("url", site.url)
                    put("title", site.title)
                    put("domain", site.domain)
                    put("count", site.visitCount)
                    put("time", site.lastVisitedMillis)
                }
            )
        }
        return array.toString()
    }
}
