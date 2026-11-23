package com.amresalehin.emregallery.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.amresalehin.emregallery.data.galleryDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.net.URI

/**
 * Search history entered on the Media Inbox downloader home page.
 *
 * This is intentionally independent of BrowserHistoryManager: browsing a page must
 * never populate the Inbox's recent-search list. Entries are kept newest-first,
 * without an arbitrary history limit.
 */
class MediaInboxSearchHistory(private val context: Context) {

    private val searchesKey = stringPreferencesKey("media_inbox_recent_searches_v1")

    val recentSearches: Flow<List<String>> = context.galleryDataStore.data
        .map { preferences ->
            decode(preferences[searchesKey])
        }
        .distinctUntilChanged()

    suspend fun recordSearch(rawQuery: String) {
        val query = normalizeMediaInboxSearchEntry(rawQuery)
        if (query.isBlank()) return

        context.galleryDataStore.edit { preferences ->
            val updated = normalizeAndDeduplicateMediaInboxSearches(
                listOf(query) + decode(preferences[searchesKey])
            )
            preferences[searchesKey] = JSONArray(updated).toString()
        }
    }

    suspend fun removeSearch(rawQuery: String) {
        val query = normalizeMediaInboxSearchEntry(rawQuery)
        if (query.isBlank()) return

        context.galleryDataStore.edit { preferences ->
            val updated = decode(preferences[searchesKey]).filterNot { it == query }
            if (updated.isEmpty()) {
                preferences.remove(searchesKey)
            } else {
                preferences[searchesKey] = JSONArray(updated).toString()
            }
        }
    }

    suspend fun clear() {
        context.galleryDataStore.edit { preferences ->
            preferences.remove(searchesKey)
        }
    }

    private fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val values = JSONArray(raw)
            val decoded = mutableListOf<String>()
            for (index in 0 until values.length()) {
                if (values.isNull(index)) continue
                val value = values.optString(index).trim()
                if (value.isNotBlank()) decoded.add(value)
            }
            normalizeAndDeduplicateMediaInboxSearches(decoded)
        }.getOrDefault(emptyList())
    }
}

/**
 * Canonicalizes a downloader URL for history storage and matching.
 * Scheme/host casing, default ports, dot segments and URL fragments do not
 * create separate history entries. Path and query casing are preserved.
 */
internal fun normalizeMediaInboxSearchEntry(rawQuery: String): String {
    val trimmed = rawQuery.trim()
    if (trimmed.isBlank()) return ""

    val candidate = if (
        trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        trimmed
    } else {
        "https://$trimmed"
    }

    return runCatching {
        val uri = URI(candidate.replace(" ", "%20")).normalize()
        val scheme = uri.scheme?.lowercase() ?: return@runCatching candidate
        val host = uri.host?.lowercase() ?: return@runCatching candidate
        val renderedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val userInfo = uri.rawUserInfo?.let { "$it@" }.orEmpty()
        val port = when {
            uri.port < 0 -> ""
            scheme == "https" && uri.port == 443 -> ""
            scheme == "http" && uri.port == 80 -> ""
            else -> ":${uri.port}"
        }
        val path = uri.rawPath.orEmpty().ifBlank { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        "$scheme://$userInfo$renderedHost$port$path$query"
    }.getOrDefault(candidate)
}

/**
 * Deduplicates normalized entries while preserving their newest-first order.
 * The history remains uncapped; callers can display only a preview subset.
 */
internal fun normalizeAndDeduplicateMediaInboxSearches(searches: List<String>): List<String> {
    val seen = mutableSetOf<String>()
    val result = mutableListOf<String>()
    searches.forEach { raw ->
        val normalized = normalizeMediaInboxSearchEntry(raw)
        if (normalized.isNotBlank() && seen.add(normalized)) {
            result.add(normalized)
        }
    }
    return result
}
