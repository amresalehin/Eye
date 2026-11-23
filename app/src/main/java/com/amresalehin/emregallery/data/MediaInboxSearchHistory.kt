package com.amresalehin.emregallery.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.amresalehin.emregallery.data.galleryDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray

/**
 * Search history entered on the Media Inbox downloader home page.
 *
 * This is intentionally independent of BrowserHistoryManager: browsing a page must
 * never populate the Inbox's recent-search list.
 */
class MediaInboxSearchHistory(private val context: Context) {

    private val searchesKey = stringPreferencesKey("media_inbox_recent_searches_v1")

    val recentSearches: Flow<List<String>> = context.galleryDataStore.data
        .map { preferences ->
            decode(preferences[searchesKey])
        }
        .distinctUntilChanged()

    suspend fun recordSearch(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isBlank()) return

        context.galleryDataStore.edit { preferences ->
            val updated = decode(preferences[searchesKey])
                .filterNot { it.equals(query, ignoreCase = true) }
                .toMutableList()
                .apply { add(0, query) }
                .take(MAX_ENTRIES)
            preferences[searchesKey] = JSONArray(updated).toString()
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
            buildList {
                for (index in 0 until values.length()) {
                    val value = values.optString(index).trim()
                    if (value.isNotBlank() && none { it.equals(value, ignoreCase = true) }) {
                        add(value)
                    }
                }
            }.take(MAX_ENTRIES)
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val MAX_ENTRIES = 20
    }
}
