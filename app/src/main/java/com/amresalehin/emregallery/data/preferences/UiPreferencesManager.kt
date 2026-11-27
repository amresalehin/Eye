package com.amresalehin.emregallery.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.*
import com.amresalehin.emregallery.data.galleryDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Manages UI layout, theming, scrolling, and onboarding preferences.
 */
class UiPreferencesManager(private val context: Context) {

    private val themeKey = stringPreferencesKey("theme")
    private val sortOrderKey = stringPreferencesKey("sort_order")
    private val groupByKey = stringPreferencesKey("group_by")
    private val gridColumnsKey = stringPreferencesKey("grid_columns")
    private val gridStyleKey = stringPreferencesKey("grid_style")
    private val searchSortOrderKey = stringPreferencesKey("search_sort_order")
    private val searchGroupByKey = stringPreferencesKey("search_group_by")
    private val searchGridColumnsKey = intPreferencesKey("search_grid_columns")
    private val searchGridStyleKey = stringPreferencesKey("search_grid_style")
    private val searchListViewKey = booleanPreferencesKey("search_list_view")
    private val albumContentSortOrderKey = stringPreferencesKey("album_content_sort_order")
    private val albumContentGroupByKey = stringPreferencesKey("album_content_group_by")
    private val albumContentGridColumnsKey = intPreferencesKey("album_content_grid_columns")
    private val albumContentGridStyleKey = stringPreferencesKey("album_content_grid_style")
    private val albumContentListViewKey = booleanPreferencesKey("album_content_list_view")
    private val folderContentSortOrderKey = stringPreferencesKey("folder_content_sort_order")
    private val folderContentGroupByKey = stringPreferencesKey("folder_content_group_by")
    private val folderContentGridColumnsKey = intPreferencesKey("folder_content_grid_columns")
    private val folderContentGridStyleKey = stringPreferencesKey("folder_content_grid_style")
    private val folderContentListViewKey = booleanPreferencesKey("folder_content_list_view")
    private val homeScrollIndexKey = intPreferencesKey("home_scroll_index")
    private val homeScrollOffsetKey = intPreferencesKey("home_scroll_offset")
    private val welcomeCompletedKey = booleanPreferencesKey("welcome_completed")
    private val sharedPrefs = context.getSharedPreferences("emre_gallery_prefs", Context.MODE_PRIVATE)

    fun isWelcomeCompletedSync(): Boolean =
        sharedPrefs.getBoolean("welcome_completed", false)

    val hasCompletedWelcome: Flow<Boolean> = context.galleryDataStore.data.map {
        it[welcomeCompletedKey] ?: isWelcomeCompletedSync()
    }.distinctUntilChanged()

    suspend fun setCompletedWelcome(completed: Boolean) {
        sharedPrefs.edit().putBoolean("welcome_completed", completed).apply()
        context.galleryDataStore.edit { prefs ->
            prefs[welcomeCompletedKey] = completed
        }
    }

    val homeScrollPosition: Flow<Pair<Int, Int>> = context.galleryDataStore.data.map { prefs ->
        val index = prefs[homeScrollIndexKey] ?: 0
        val offset = prefs[homeScrollOffsetKey] ?: 0
        Pair(index, offset)
    }.distinctUntilChanged()

    suspend fun saveHomeScrollPosition(index: Int, offset: Int) {
        context.galleryDataStore.edit { prefs ->
            prefs[homeScrollIndexKey] = index
            prefs[homeScrollOffsetKey] = offset
        }
    }

    suspend fun clearHomeScrollPosition() {
        context.galleryDataStore.edit { prefs ->
            prefs.remove(homeScrollIndexKey)
            prefs.remove(homeScrollOffsetKey)
        }
    }

    val theme: Flow<String> = context.galleryDataStore.data.map {
        it[themeKey] ?: "system"
    }.distinctUntilChanged()

    suspend fun setTheme(value: String) {
        context.galleryDataStore.edit { it[themeKey] = value }
    }

    val sortOrder: Flow<String> = context.galleryDataStore.data.map {
        it[sortOrderKey] ?: "date_desc"
    }.distinctUntilChanged()

    suspend fun setSortOrder(value: String) {
        context.galleryDataStore.edit { it[sortOrderKey] = value }
    }

    val groupBy: Flow<String> = context.galleryDataStore.data.map {
        it[groupByKey] ?: "date"
    }.distinctUntilChanged()

    suspend fun setGroupBy(value: String) {
        context.galleryDataStore.edit { it[groupByKey] = value }
    }

    val gridColumns: Flow<Int> = context.galleryDataStore.data.map {
        it[gridColumnsKey]?.toIntOrNull() ?: 3
    }.distinctUntilChanged()

    suspend fun setGridColumns(value: Int) {
        context.galleryDataStore.edit { it[gridColumnsKey] = value.toString() }
    }

    val gridStyle: Flow<String> = context.galleryDataStore.data.map {
        it[gridStyleKey] ?: "square"
    }.distinctUntilChanged()

    suspend fun setGridStyle(value: String) {
        context.galleryDataStore.edit { it[gridStyleKey] = value }
    }

    // Search presentation settings are independent from Home presentation settings.
    val searchSortOrder: Flow<String> = context.galleryDataStore.data.map {
        it[searchSortOrderKey] ?: "date_desc"
    }.distinctUntilChanged()

    suspend fun setSearchSortOrder(value: String) {
        context.galleryDataStore.edit { it[searchSortOrderKey] = value }
    }

    val searchGroupBy: Flow<String> = context.galleryDataStore.data.map {
        it[searchGroupByKey] ?: "day"
    }.distinctUntilChanged()

    suspend fun setSearchGroupBy(value: String) {
        context.galleryDataStore.edit { it[searchGroupByKey] = value }
    }

    val searchGridColumns: Flow<Int> = context.galleryDataStore.data.map {
        it[searchGridColumnsKey] ?: 3
    }.distinctUntilChanged()

    suspend fun setSearchGridColumns(value: Int) {
        context.galleryDataStore.edit { it[searchGridColumnsKey] = value.coerceIn(1, 6) }
    }

    val searchGridStyle: Flow<String> = context.galleryDataStore.data.map {
        it[searchGridStyleKey] ?: "masonry"
    }.distinctUntilChanged()

    suspend fun setSearchGridStyle(value: String) {
        context.galleryDataStore.edit { it[searchGridStyleKey] = value }
    }

    val searchListView: Flow<Boolean> = context.galleryDataStore.data.map {
        it[searchListViewKey] ?: false
    }.distinctUntilChanged()

    suspend fun setSearchListView(value: Boolean) {
        context.galleryDataStore.edit { it[searchListViewKey] = value }
    }

    // Presentation preferences for media shown inside an opened custom album.
    val albumContentSortOrder: Flow<String> = context.galleryDataStore.data.map {
        it[albumContentSortOrderKey] ?: "date_desc"
    }.distinctUntilChanged()
    suspend fun setAlbumContentSortOrder(value: String) {
        context.galleryDataStore.edit { it[albumContentSortOrderKey] = value }
    }

    val albumContentGroupBy: Flow<String> = context.galleryDataStore.data.map {
        it[albumContentGroupByKey] ?: "day"
    }.distinctUntilChanged()
    suspend fun setAlbumContentGroupBy(value: String) {
        context.galleryDataStore.edit { it[albumContentGroupByKey] = value }
    }

    val albumContentGridColumns: Flow<Int> = context.galleryDataStore.data.map {
        it[albumContentGridColumnsKey] ?: 3
    }.distinctUntilChanged()
    suspend fun setAlbumContentGridColumns(value: Int) {
        context.galleryDataStore.edit { it[albumContentGridColumnsKey] = value.coerceIn(1, 6) }
    }

    val albumContentGridStyle: Flow<String> = context.galleryDataStore.data.map {
        it[albumContentGridStyleKey] ?: "masonry"
    }.distinctUntilChanged()
    suspend fun setAlbumContentGridStyle(value: String) {
        context.galleryDataStore.edit { it[albumContentGridStyleKey] = value }
    }

    val albumContentListView: Flow<Boolean> = context.galleryDataStore.data.map {
        it[albumContentListViewKey] ?: false
    }.distinctUntilChanged()
    suspend fun setAlbumContentListView(value: Boolean) {
        context.galleryDataStore.edit { it[albumContentListViewKey] = value }
    }

    // Presentation preferences for media shown inside an opened device folder.
    val folderContentSortOrder: Flow<String> = context.galleryDataStore.data.map {
        it[folderContentSortOrderKey] ?: "date_desc"
    }.distinctUntilChanged()
    suspend fun setFolderContentSortOrder(value: String) {
        context.galleryDataStore.edit { it[folderContentSortOrderKey] = value }
    }

    val folderContentGroupBy: Flow<String> = context.galleryDataStore.data.map {
        it[folderContentGroupByKey] ?: "day"
    }.distinctUntilChanged()
    suspend fun setFolderContentGroupBy(value: String) {
        context.galleryDataStore.edit { it[folderContentGroupByKey] = value }
    }

    val folderContentGridColumns: Flow<Int> = context.galleryDataStore.data.map {
        it[folderContentGridColumnsKey] ?: 3
    }.distinctUntilChanged()
    suspend fun setFolderContentGridColumns(value: Int) {
        context.galleryDataStore.edit { it[folderContentGridColumnsKey] = value.coerceIn(1, 6) }
    }

    val folderContentGridStyle: Flow<String> = context.galleryDataStore.data.map {
        it[folderContentGridStyleKey] ?: "masonry"
    }.distinctUntilChanged()
    suspend fun setFolderContentGridStyle(value: String) {
        context.galleryDataStore.edit { it[folderContentGridStyleKey] = value }
    }

    val folderContentListView: Flow<Boolean> = context.galleryDataStore.data.map {
        it[folderContentListViewKey] ?: false
    }.distinctUntilChanged()
    suspend fun setFolderContentListView(value: Boolean) {
        context.galleryDataStore.edit { it[folderContentListViewKey] = value }
    }
}
