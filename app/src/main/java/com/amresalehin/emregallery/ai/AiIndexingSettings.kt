package com.amresalehin.emregallery.ai

import android.content.Context

/**
 * Persists a folder scope for AI Vision indexing independently from Face Recognition.
 */
object AiIndexingSettings {
    private const val PREFS_NAME = "ai_indexing_settings"
    private const val KEY_INDEX_ALL_FOLDERS = "index_all_folders"
    private const val KEY_SELECTED_FOLDERS = "selected_folders"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isIndexAllFolders(context: Context): Boolean =
        preferences(context).getBoolean(KEY_INDEX_ALL_FOLDERS, true)

    fun setIndexAllFolders(context: Context, all: Boolean) {
        preferences(context).edit().putBoolean(KEY_INDEX_ALL_FOLDERS, all).apply()
    }

    fun getSelectedFolders(context: Context): Set<String> {
        val selected = preferences(context).getStringSet(KEY_SELECTED_FOLDERS, null)
        return selected?.let(::HashSet) ?: emptySet()
    }

    fun setSelectedFolders(context: Context, folders: Set<String>) {
        preferences(context).edit().putStringSet(KEY_SELECTED_FOLDERS, HashSet(folders)).apply()
    }

    fun normalizeFolderPath(relativePath: String): String =
        relativePath.trim().trim('/').ifBlank { "Internal storage" }

    fun <T> filterMediaForAiIndexing(
        context: Context,
        media: List<T>,
        pathSelector: (T) -> String
    ): List<T> {
        return filterMediaByFolders(
            media = media,
            allFolders = isIndexAllFolders(context),
            selectedFolders = getSelectedFolders(context),
            pathSelector = pathSelector
        )
    }

    internal fun <T> filterMediaByFolders(
        media: List<T>,
        allFolders: Boolean,
        selectedFolders: Set<String>,
        pathSelector: (T) -> String
    ): List<T> {
        if (allFolders || selectedFolders.isEmpty()) return media
        return media.filter { item ->
            normalizeFolderPath(pathSelector(item)) in selectedFolders
        }
    }
}
