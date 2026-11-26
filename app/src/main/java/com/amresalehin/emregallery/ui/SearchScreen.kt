package com.amresalehin.emregallery.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amresalehin.emregallery.ai.AiMetadataStore
import com.amresalehin.emregallery.ai.AiStoreEvents
import com.amresalehin.emregallery.ai.CustomAiClient
import com.amresalehin.emregallery.data.preferences.AiConfig
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amresalehin.emregallery.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    query: String,
    stage: SearchStage,
    allMedia: List<MediaItem>,
    recentSearches: List<String>,
    customAlbums: List<CustomAlbum>,
    customTagsMap: Map<String, Set<String>>,
    customPeopleMap: Map<String, Set<String>>,
    favourites: Set<String>,
    themePref: String = "system",
    onQueryChange: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onClearRecent: () -> Unit,
    onBack: () -> Unit,
    onOpenMedia: (MediaItem, List<MediaItem>) -> Unit,
    initialFilter: SearchFilterState? = null,
    onConsumeInitialFilter: () -> Unit = {},
    onOpenAiSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    val isDark = when (themePref) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    val searchEngine = remember { SearchEngine(context) }
    val prefsRepo = remember(context) { PreferencesRepository(context.applicationContext) }
    val aiStore = remember(context) { AiMetadataStore(context.applicationContext) }
    val aiClient = remember { CustomAiClient() }
    val aiConfig by prefsRepo.aiConfig.collectAsStateWithLifecycle(initialValue = AiConfig())

    // Search & Filter State
    var filterState by remember { mutableStateOf(initialFilter ?: SearchFilterState()) }
    var sortMode by remember { mutableStateOf(SearchSortMode.RELEVANCE) }
    var viewMode by remember { mutableStateOf(SearchViewMode.MASONRY) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showDatePickerDialog by remember { mutableStateOf(false) }

    // Semantic AI Search State
    var isAiSearching by remember { mutableStateOf(false) }
    var aiMatchedUris by remember { mutableStateOf<List<String>?>(null) }
    var aiSearchSummary by remember { mutableStateOf<String?>(null) }
    var aiSearchError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialFilter) {
        if (initialFilter != null) {
            filterState = initialFilter
            onConsumeInitialFilter()
        }
    }

    LaunchedEffect(query) {
        aiMatchedUris = null
        aiSearchSummary = null
        aiSearchError = null
    }

    // Collapsed Date Groups State for State C
    val collapsedGroups = remember { mutableStateMapOf<Long, Boolean>() }

    // Discovery Data for State A
    var discoveryData by remember { mutableStateOf<DiscoveryData?>(null) }

    // Live Suggestions for State B
    var liveSuggestions by remember { mutableStateOf<List<SearchSuggestion>>(emptyList()) }
    var suggestionsJob by remember { mutableStateOf<Job?>(null) }

    // Search Results for State C
    var searchResultsData by remember {
        mutableStateOf(SearchResultData(query = "", items = emptyList(), dynamicChips = emptyList(), totalPhotosCount = 0, totalVideosCount = 0))
    }

    // Load Discovery Data when entering Search / changing media / updating AI metadata
    var aiUpdateTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        AiStoreEvents.updates.collect { aiUpdateTick++ }
    }
    LaunchedEffect(allMedia, customAlbums, customTagsMap, customPeopleMap, favourites, aiUpdateTick) {
        discoveryData = searchEngine.getDiscoveryContent(
            media = allMedia,
            customAlbums = customAlbums,
            customTagsMap = customTagsMap,
            customPeopleMap = customPeopleMap,
            favourites = favourites
        )
    }

    // Auto-focus input when in Discovery or Typing mode
    LaunchedEffect(stage) {
        if (stage == SearchStage.DISCOVERY || stage == SearchStage.TYPING) {
            runCatching {
                focusRequester.requestFocus()
                keyboardController?.show()
            }
        } else {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    // Debounced Live Suggestions computation for State B
    LaunchedEffect(query, stage, allMedia, customAlbums, customTagsMap, customPeopleMap, recentSearches) {
        if (stage == SearchStage.TYPING && query.isNotBlank()) {
            suggestionsJob?.cancel()
            suggestionsJob = scope.launch {
                delay(80) // Debounce typing
                val suggestions = searchEngine.getLiveSuggestions(
                    query = query,
                    media = allMedia,
                    recentSearches = recentSearches,
                    customAlbums = customAlbums,
                    customTagsMap = customTagsMap,
                    customPeopleMap = customPeopleMap
                )
                liveSuggestions = suggestions
            }
        } else {
            liveSuggestions = emptyList()
        }
    }

    // Compute Search Results for State C or whenever query/filters/sort change
    LaunchedEffect(query, stage, filterState, sortMode, allMedia, customAlbums, customTagsMap, customPeopleMap, favourites, aiUpdateTick) {
        if (stage == SearchStage.RESULTS || !filterState.isDefault) {
            val results = searchEngine.searchMedia(
                query = query,
                media = allMedia,
                filter = filterState,
                sortMode = sortMode,
                customAlbums = customAlbums,
                customTagsMap = customTagsMap,
                customPeopleMap = customPeopleMap,
                favourites = favourites
            )
            searchResultsData = results
        }
    }

    fun triggerSemanticAiSearch(searchText: String) {
        val trimmed = searchText.trim()
        if (trimmed.isEmpty() || isAiSearching) return
        if (!aiConfig.isConfigured) {
            onOpenAiSettings?.invoke()
            return
        }
        focusManager.clearFocus()
        keyboardController?.hide()
        onQueryChange(trimmed)
        onSubmitSearch(trimmed)
        isAiSearching = true
        aiSearchError = null
        scope.launch {
            val analyses = withContext(Dispatchers.IO) {
                aiStore.getAllCompleted().values.toList()
            }
            val result = aiClient.semanticSearch(trimmed, analyses, aiConfig)
            result.onSuccess { match ->
                aiMatchedUris = match.matchingUris
                aiSearchSummary = match.summary
            }.onFailure { err ->
                aiSearchError = err.message ?: "AI semantic search failed"
            }
            isAiSearching = false
        }
    }

    // Handle Back Press navigation cleanly
    BackHandler {
        when {
            !filterState.isDefault -> {
                filterState = SearchFilterState()
            }
            stage == SearchStage.RESULTS -> {
                // If in results, go back to typing/discovery
                if (query.isNotBlank()) {
                    onQueryChange(query)
                } else {
                    onBack()
                }
            }
            stage == SearchStage.TYPING -> {
                if (query.isNotEmpty()) {
                    onQueryChange("")
                } else {
                    onBack()
                }
            }
            stage == SearchStage.DISCOVERY -> {
                onBack()
            }
        }
    }

    val bgColor = searchBgColor(isDark)
    val textColor = searchPrimaryTextColor(isDark)
    val secondaryText = searchSecondaryTextColor(isDark)
    val cardSurface = searchCardSurfaceColor(isDark)
    val dividerColor = searchDividerColor(isDark)
    val selectedSurface = searchSelectedSurfaceColor(isDark)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bgColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(10.dp))

            // TOP SEARCH BAR CAPSULE (Present in All 3 States)
            SearchBarCapsule(
                query = query,
                onQueryChange = { newQuery ->
                    onQueryChange(newQuery)
                },
                onSearchSubmit = { submittedQuery ->
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    onSubmitSearch(submittedQuery)
                },
                onClearQuery = {
                    onQueryChange("")
                    focusRequester.requestFocus()
                    keyboardController?.show()
                },
                onBack = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    if (!filterState.isDefault) {
                        filterState = SearchFilterState()
                    } else if (stage == SearchStage.RESULTS) {
                        onQueryChange("")
                    } else {
                        onBack()
                    }
                },
                onOpenFilterSheet = { showFilterSheet = true },
                hasActiveFilters = !filterState.isDefault,
                focusRequester = focusRequester,
                isDark = isDark
            )

            // SINGLE SCROLLABLE ROW OF FILTER PILLS (State A, State B, and State C)
            // Hides as keyboard hides and appears as keyboard appears
            AnimatedVisibility(
                visible = WindowInsets.isImeVisible,
                enter = expandVertically(tween(200)) + fadeIn(tween(150)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    SearchFilterChipsRow(
                        filterState = filterState,
                        onFilterChange = { newFilter -> filterState = newFilter },
                        dynamicChips = if (stage == SearchStage.RESULTS) searchResultsData.dynamicChips else emptyList(),
                        onToggleDynamicChip = { chip ->
                            // Toggle active dynamic entity chip
                            filterState = when (chip.kind) {
                                ChipKind.ALBUM -> {
                                    if (filterState.selectedAlbumId == chip.value) {
                                        filterState.copy(selectedAlbumId = null)
                                    } else {
                                        filterState.copy(selectedAlbumId = chip.value)
                                    }
                                }
                                ChipKind.PERSON -> {
                                    if (filterState.selectedPersonId == chip.value) {
                                        filterState.copy(selectedPersonId = null)
                                    } else {
                                        filterState.copy(selectedPersonId = chip.value)
                                    }
                                }
                                ChipKind.PLACE -> {
                                    if (filterState.selectedPlace == chip.value) {
                                        filterState.copy(selectedPlace = null)
                                    } else {
                                        filterState.copy(selectedPlace = chip.value)
                                    }
                                }
                                ChipKind.TAG -> {
                                    if (filterState.selectedTag == chip.value) {
                                        filterState.copy(selectedTag = null)
                                    } else {
                                        filterState.copy(selectedTag = chip.value)
                                    }
                                }
                            }
                        },
                        onRequestCustomDatePicker = { showDatePickerDialog = true },
                        isDark = isDark
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // SCREEN BODY ACCORDING TO CURRENT STAGE
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                when {
                    stage == SearchStage.RESULTS || (!filterState.isDefault && stage == SearchStage.DISCOVERY) -> {
                        val effectiveResults = remember(searchResultsData, aiMatchedUris, allMedia) {
                            val semanticUris = aiMatchedUris
                            if (semanticUris == null) {
                                searchResultsData
                            } else {
                                val mediaMap = allMedia.filterNot { it.isTrashed }.associateBy { it.uri }
                                val prioritized = semanticUris.mapNotNull { mediaMap[it] }
                                val prioritizedSet = prioritized.map { it.uri }.toSet()
                                val combined = prioritized + searchResultsData.items.filterNot { it.uri in prioritizedSet }
                                searchResultsData.copy(
                                    items = combined,
                                    totalPhotosCount = combined.count { !it.isVideo },
                                    totalVideosCount = combined.count { it.isVideo }
                                )
                            }
                        }

                        SubmittedResultsContent(
                            query = query,
                            resultsData = effectiveResults,
                            sortMode = sortMode,
                            onSortChange = { sortMode = it },
                            viewMode = viewMode,
                            onViewModeChange = { viewMode = it },
                            hasActiveFilters = !filterState.isDefault,
                            onResetFilters = { filterState = SearchFilterState() },
                            collapsedGroups = collapsedGroups,
                            onToggleGroupCollapse = { key ->
                                collapsedGroups[key] = !(collapsedGroups[key] ?: false)
                            },
                            onOpenMedia = { item ->
                                onOpenMedia(item, effectiveResults.items)
                            },
                            hasAiKey = aiConfig.isConfigured,
                            isAiSearching = isAiSearching,
                            aiSearchSummary = aiSearchSummary,
                            aiSearchError = aiSearchError,
                            onRunAiSearch = { triggerSemanticAiSearch(query) },
                            onClearAiSearch = {
                                aiMatchedUris = null
                                aiSearchSummary = null
                                aiSearchError = null
                            },
                            onOpenAiSettings = onOpenAiSettings,
                            isDark = isDark
                        )
                    }

                    stage == SearchStage.TYPING -> {
                        // STATE B: Typing / live suggestions
                        TypingLiveSuggestionsContent(
                            query = query,
                            suggestions = liveSuggestions,
                            onSelectSuggestion = { suggestion ->
                                if (suggestion.targetFilter != null) {
                                    filterState = suggestion.targetFilter
                                }
                                val q = suggestion.targetQuery ?: suggestion.title
                                onQueryChange(q)
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                onSubmitSearch(q)
                            },
                            onFillSuggestion = { suggestion ->
                                val q = suggestion.targetQuery ?: suggestion.title
                                onQueryChange(q)
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            },
                            onSubmitQuery = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                onSubmitSearch(query.trim())
                            },
                            hasAiKey = aiConfig.isConfigured,
                            onAskAiQuery = { triggerSemanticAiSearch(query) },
                            isDark = isDark
                        )
                    }

                    else -> {
                        // STATE A: Discovery content (Search opened, no query entered, no filter pills active)
                        DiscoveryContent(
                            discoveryData = discoveryData,
                            recentSearches = recentSearches,
                            onSelectRecent = { selectedQuery ->
                                onQueryChange(selectedQuery)
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                onSubmitSearch(selectedQuery)
                            },
                            onClearRecent = onClearRecent,
                            onSelectCategory = { category ->
                                if (category.filterAction != null) {
                                    filterState = category.filterAction
                                }
                                val q = category.queryAction ?: category.title
                                onQueryChange(q)
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                onSubmitSearch(q)
                            },
                            onSelectEntity = { entity ->
                                if (entity.filterAction != null) {
                                    filterState = entity.filterAction
                                }
                                val q = entity.queryAction ?: entity.title
                                onQueryChange(q)
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                onSubmitSearch(q)
                            },
                            isDark = isDark
                        )
                    }
                }
            }
        }
    }

    // Modal Advanced Filter Sheet
    if (showFilterSheet) {
        SearchAdvancedFilterSheet(
            filterState = filterState,
            onApplyFilter = { newFilter -> filterState = newFilter },
            onDismiss = { showFilterSheet = false },
            onRequestCustomDatePicker = {
                showFilterSheet = false
                showDatePickerDialog = true
            },
            isDark = isDark
        )
    }

    // Custom Date Range Dialog
    if (showDatePickerDialog) {
        CustomDateRangeDialog(
            onDismiss = { showDatePickerDialog = false },
            onDateRangeSelected = { startMillis, endMillis ->
                filterState = filterState.copy(
                    timeRange = TimeRangeFilter.Custom(startMillis, endMillis)
                )
                showDatePickerDialog = false
            },
            isDark = isDark
        )
    }
}

/**
 * Discovery Content for State A.
 * Strictly uses real app data. Omits empty sections!
 */
@Composable
private fun DiscoveryContent(
    discoveryData: DiscoveryData?,
    recentSearches: List<String>,
    onSelectRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onSelectCategory: (DiscoveryCategory) -> Unit,
    onSelectEntity: (DiscoveryEntityItem) -> Unit,
    isDark: Boolean = false
) {
    val textColor = searchPrimaryTextColor(isDark)
    val secondaryText = searchSecondaryTextColor(isDark)
    val cardSurface = searchCardSurfaceColor(isDark)
    val dividerCol = searchDividerColor(isDark)
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp)
    ) {
        // 1. Recent Searches (Only if non-empty!)
        if (recentSearches.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Recent searches",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            ),
                            color = textColor
                        )
                        Text(
                            text = "Clear all",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                            color = secondaryText,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(onClick = onClearRecent)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .testTag("recent_search_clear_all")
                        )
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(recentSearches.take(8)) { recentQuery ->
                            Surface(
                                modifier = Modifier
                                    .height(36.dp)
                                    .clip(CircleShape)
                                    .clickable { onSelectRecent(recentQuery) }
                                    .testTag("recent_chip_$recentQuery"),
                                shape = CircleShape,
                                color = cardSurface,
                                border = BorderStroke(1.dp, dividerCol)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = secondaryText
                                    )
                                    Text(
                                        text = recentQuery,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                        color = textColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Search by Category (Photos, Videos, Documents, Favorites - only if non-empty)
        if (discoveryData != null && discoveryData.categories.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Search by category",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        ),
                        color = textColor
                    )

                    // 2-column grid of compact category cards with mini-thumbnails
                    val chunkedCats = discoveryData.categories.chunked(2)
                    chunkedCats.forEach { rowCategories ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowCategories.forEach { category ->
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(108.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { onSelectCategory(category) }
                                        .testTag("category_card_${category.id}"),
                                    shape = RoundedCornerShape(14.dp),
                                    color = cardSurface,
                                    border = BorderStroke(1.dp, dividerCol.copy(alpha = 0.8f))
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                val catIcon = when (category.id) {
                                                    "cat_photos" -> Icons.Default.Image
                                                    "cat_videos" -> Icons.Default.Videocam
                                                    "cat_docs" -> Icons.Default.Description
                                                    "cat_ai" -> Icons.Outlined.AutoAwesome
                                                    "cat_favs" -> Icons.Default.Favorite
                                                    else -> Icons.Default.Folder
                                                }
                                                Icon(catIcon, null, modifier = Modifier.size(16.dp), tint = textColor)
                                                Text(
                                                    text = category.title,
                                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                                    color = textColor
                                                )
                                            }
                                            Icon(
                                                Icons.Default.ChevronRight,
                                                null,
                                                modifier = Modifier.size(16.dp),
                                                tint = secondaryText
                                            )
                                        }

                                        // Mini-thumbnails row
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            for (uri in category.sampleUris.take(3)) {
                                                AsyncImage(
                                                    model = remember(uri, context) {
                                                        ThumbnailUtils.buildThumbnailRequest(context, uri)
                                                    },
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(52.dp)
                                                        .clip(RoundedCornerShape(6.dp)),
                                                    contentScale = ContentScale.Crop
                                                )
                                            }
                                            // Fill empty slots if fewer than 3
                                            for (i in 0 until (3 - category.sampleUris.size)) {
                                                Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                            if (rowCategories.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // 3. Real People (Horizontal strip of recognized person clusters)
        if (discoveryData != null && discoveryData.people.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "People",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        ),
                        color = textColor
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(discoveryData.people) { person ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(72.dp)
                                    .clickable { onSelectEntity(person) }
                                    .testTag("discovery_person_${person.id}")
                            ) {
                                if (person.thumbnailUri != null) {
                                    AsyncImage(
                                        model = remember(person.thumbnailUri, context) {
                                            ThumbnailUtils.buildThumbnailRequest(context, person.thumbnailUri)
                                        },
                                        contentDescription = person.title,
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(CircleShape)
                                            .border(1.dp, dividerCol, CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Surface(
                                        modifier = Modifier.size(64.dp),
                                        shape = CircleShape,
                                        color = cardSurface,
                                        border = BorderStroke(1.dp, dividerCol)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Person, null, tint = secondaryText, modifier = Modifier.size(28.dp))
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = person.title,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                    color = textColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!person.subtitle.isNullOrBlank()) {
                                    Text(
                                        text = person.subtitle,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = secondaryText,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Real Places (Horizontal strip of indexed locations)
        if (discoveryData != null && discoveryData.places.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Places",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        ),
                        color = textColor
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(discoveryData.places) { place ->
                            Surface(
                                modifier = Modifier
                                    .width(118.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectEntity(place) }
                                    .testTag("discovery_place_${place.id}"),
                                shape = RoundedCornerShape(12.dp),
                                color = cardSurface,
                                border = BorderStroke(1.dp, dividerCol)
                            ) {
                                Column {
                                    if (place.thumbnailUri != null) {
                                        AsyncImage(
                                            model = remember(place.thumbnailUri, context) {
                                                ThumbnailUtils.buildThumbnailRequest(context, place.thumbnailUri)
                                            },
                                            contentDescription = place.title,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(76.dp),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(76.dp)
                                                .background(dividerCol.copy(alpha = 0.5f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Place, null, tint = secondaryText)
                                        }
                                    }
                                    Column(Modifier.padding(8.dp)) {
                                        Text(
                                            text = place.title,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = textColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (!place.subtitle.isNullOrBlank()) {
                                            Text(
                                                text = place.subtitle,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                color = secondaryText,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Real Albums (Horizontal strip of existing albums)
        if (discoveryData != null && discoveryData.albums.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Albums",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        ),
                        color = textColor
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(discoveryData.albums) { album ->
                            Surface(
                                modifier = Modifier
                                    .width(118.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectEntity(album) }
                                    .testTag("discovery_album_${album.id}"),
                                shape = RoundedCornerShape(12.dp),
                                color = cardSurface,
                                border = BorderStroke(1.dp, dividerCol)
                            ) {
                                Column {
                                    if (album.thumbnailUri != null) {
                                        AsyncImage(
                                            model = remember(album.thumbnailUri, context) {
                                                ThumbnailUtils.buildThumbnailRequest(context, album.thumbnailUri)
                                            },
                                            contentDescription = album.title,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(76.dp),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(76.dp)
                                                .background(dividerCol.copy(alpha = 0.5f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.PhotoAlbum, null, tint = secondaryText)
                                        }
                                    }
                                    Column(Modifier.padding(8.dp)) {
                                        Text(
                                            text = album.title,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = textColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (!album.subtitle.isNullOrBlank()) {
                                            Text(
                                                text = album.subtitle,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                color = secondaryText,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. Real Tags (If user has created tags)
        if (discoveryData != null && discoveryData.tags.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Tags",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        ),
                        color = textColor
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(discoveryData.tags) { tag ->
                            Surface(
                                modifier = Modifier
                                    .height(34.dp)
                                    .clip(CircleShape)
                                    .clickable { onSelectEntity(tag) }
                                    .testTag("discovery_tag_${tag.id}"),
                                shape = CircleShape,
                                color = cardSurface,
                                border = BorderStroke(1.dp, dividerCol)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.Tag, null, modifier = Modifier.size(15.dp), tint = secondaryText)
                                    Text(text = tag.title, style = MaterialTheme.typography.labelMedium, color = textColor)
                                    if (!tag.subtitle.isNullOrBlank()) {
                                        Text(text = tag.subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = secondaryText)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 7. AI Smart Collections (Generated by Custom API Key AI)
        if (discoveryData != null && discoveryData.aiCollections.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = textColor
                        )
                        Text(
                            text = "AI Smart Collections",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            ),
                            color = textColor
                        )
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(discoveryData.aiCollections) { aiCol ->
                            Surface(
                                modifier = Modifier
                                    .width(126.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectEntity(aiCol) }
                                    .testTag("discovery_${aiCol.id}"),
                                shape = RoundedCornerShape(12.dp),
                                color = cardSurface,
                                border = BorderStroke(1.dp, dividerCol)
                            ) {
                                Column {
                                    if (aiCol.thumbnailUri != null) {
                                        AsyncImage(
                                            model = remember(aiCol.thumbnailUri, context) {
                                                ThumbnailUtils.buildThumbnailRequest(context, aiCol.thumbnailUri)
                                            },
                                            contentDescription = aiCol.title,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(76.dp),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(76.dp)
                                                .background(dividerCol.copy(alpha = 0.5f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Outlined.AutoAwesome, null, tint = secondaryText)
                                        }
                                    }
                                    Column(Modifier.padding(8.dp)) {
                                        Text(
                                            text = aiCol.title,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = textColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (!aiCol.subtitle.isNullOrBlank()) {
                                            Text(
                                                text = aiCol.subtitle,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                color = secondaryText,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * State B: Live Suggestions Content while typing.
 * Maximum 1-2 per category, compact rows, northwest arrow, submit button.
 */
@Composable
private fun TypingLiveSuggestionsContent(
    query: String,
    suggestions: List<SearchSuggestion>,
    onSelectSuggestion: (SearchSuggestion) -> Unit,
    onFillSuggestion: (SearchSuggestion) -> Unit,
    onSubmitQuery: () -> Unit,
    hasAiKey: Boolean = false,
    onAskAiQuery: () -> Unit = {},
    isDark: Boolean = false
) {
    val textColor = searchPrimaryTextColor(isDark)
    val secondaryText = searchSecondaryTextColor(isDark)
    val cardSurface = searchCardSurfaceColor(isDark)
    val dividerCol = searchDividerColor(isDark)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Direct Query Action Row
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSubmitQuery)
                    .testTag("submit_query_row"),
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = textColor
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = query,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            ),
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Search photos, locations, and text",
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryText
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.NorthWest,
                        contentDescription = "Submit search",
                        tint = secondaryText.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Semantic AI Search Row
        if (query.isNotBlank()) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onAskAiQuery)
                        .testTag("search_ask_ai_typing_row"),
                    shape = RoundedCornerShape(14.dp),
                    color = cardSurface,
                    border = BorderStroke(1.dp, dividerCol)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = "Ask AI",
                            tint = textColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ask AI: “$query”",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (hasAiKey) "Ask AI" else "Set API key",
                                style = MaterialTheme.typography.bodySmall,
                                color = secondaryText
                            )
                        }
                    }
                }
            }
        }

        // Live Suggestion Rows (Grouped or ordered cleanly, max 1-2 per category)
        items(suggestions, key = { it.id }) { suggestion ->
            SearchSuggestionRow(
                suggestion = suggestion,
                onClick = { onSelectSuggestion(suggestion) },
                onFillQuery = { onFillSuggestion(suggestion) },
                isDark = isDark
            )
        }
    }
}

/**
 * State C: Submitted Search Results.
 * Date-grouped masonry photo grid, Sort/View controls beside results header.
 */
@Composable
private fun SubmittedResultsContent(
    query: String,
    resultsData: SearchResultData,
    sortMode: SearchSortMode,
    onSortChange: (SearchSortMode) -> Unit,
    viewMode: SearchViewMode,
    onViewModeChange: (SearchViewMode) -> Unit,
    hasActiveFilters: Boolean,
    onResetFilters: () -> Unit,
    collapsedGroups: Map<Long, Boolean>,
    onToggleGroupCollapse: (Long) -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    hasAiKey: Boolean = false,
    isAiSearching: Boolean = false,
    aiSearchSummary: String? = null,
    aiSearchError: String? = null,
    onRunAiSearch: () -> Unit = {},
    onClearAiSearch: () -> Unit = {},
    onOpenAiSettings: (() -> Unit)? = null,
    isDark: Boolean = false
) {
    val textColor = searchPrimaryTextColor(isDark)
    val secondaryText = searchSecondaryTextColor(isDark)
    val cardSurface = searchCardSurfaceColor(isDark)
    val dividerCol = searchDividerColor(isDark)
    val results = resultsData.items
    val totalCount = results.size

    if (totalCount == 0) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (query.isNotBlank()) {
                AiSemanticSearchBanner(
                    query = query,
                    hasAiKey = hasAiKey,
                    isAiSearching = isAiSearching,
                    aiSearchSummary = aiSearchSummary,
                    aiSearchError = aiSearchError,
                    onRunAiSearch = onRunAiSearch,
                    onClearAiSearch = onClearAiSearch,
                    onOpenAiSettings = onOpenAiSettings,
                    textColor = textColor,
                    secondaryText = secondaryText,
                    cardSurface = cardSurface,
                    dividerCol = dividerCol
                )
            }
            SearchEmptyState(
                query = query,
                hasActiveFilters = hasActiveFilters,
                onResetFilters = onResetFilters,
                isDark = isDark
            )
        }
        return
    }

    // Date grouping for results
    val dayGroups = remember(results) { groupMediaByDay(results) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Results Header: Query title, count, content summary, Sort & View controls
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchResultsHeader(
                    queryTitle = query.ifBlank { "All results" },
                    totalCount = totalCount,
                    photosCount = resultsData.totalPhotosCount,
                    videosCount = resultsData.totalVideosCount,
                    sortMode = sortMode,
                    onSortChange = onSortChange,
                    viewMode = viewMode,
                    onViewModeChange = onViewModeChange,
                    isDark = isDark
                )

                if (query.isNotBlank()) {
                    AiSemanticSearchBanner(
                        query = query,
                        hasAiKey = hasAiKey,
                        isAiSearching = isAiSearching,
                        aiSearchSummary = aiSearchSummary,
                        aiSearchError = aiSearchError,
                        onRunAiSearch = onRunAiSearch,
                        onClearAiSearch = onClearAiSearch,
                        onOpenAiSettings = onOpenAiSettings,
                        textColor = textColor,
                        secondaryText = secondaryText,
                        cardSurface = cardSurface,
                        dividerCol = dividerCol
                    )
                }
            }
        }

        // Date Groups
        items(dayGroups, key = { it.dayKey }) { group ->
            val isCollapsed = collapsedGroups[group.dayKey] == true

            Column(modifier = Modifier.fillMaxWidth()) {
                SearchDateGroupHeader(
                    label = group.dayLabel,
                    count = group.items.size,
                    isExpanded = !isCollapsed,
                    onToggleExpand = { onToggleGroupCollapse(group.dayKey) },
                    isDark = isDark
                )

                AnimatedVisibility(
                    visible = !isCollapsed,
                    enter = expandVertically(tween(200)) + fadeIn(tween(150)),
                    exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
                ) {
                    when (viewMode) {
                        SearchViewMode.MASONRY -> {
                            // Natural varied image heights, narrow 4dp gutters, rounded corners
                            DynamicMasonryGrid(
                                media = group.items,
                                columnsCount = 3,
                                onOpen = onOpenMedia
                            )
                        }
                        SearchViewMode.GRID -> {
                            SquareGrid(
                                media = group.items,
                                columnsCount = 3,
                                onOpen = onOpenMedia
                            )
                        }
                        SearchViewMode.LARGE_GRID -> {
                            SquareGrid(
                                media = group.items,
                                columnsCount = 2,
                                onOpen = onOpenMedia
                            )
                        }
                        SearchViewMode.LIST -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                group.items.forEach { item ->
                                    CompactMediaListRow(
                                        item = item,
                                        onOpen = { onOpenMedia(item) },
                                        isDark = isDark
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiSemanticSearchBanner(
    query: String,
    hasAiKey: Boolean,
    isAiSearching: Boolean,
    aiSearchSummary: String?,
    aiSearchError: String?,
    onRunAiSearch: () -> Unit,
    onClearAiSearch: () -> Unit,
    onOpenAiSettings: (() -> Unit)?,
    textColor: Color,
    secondaryText: Color,
    cardSurface: Color,
    dividerCol: Color
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardSurface,
        border = BorderStroke(1.dp, dividerCol),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search_ai_banner")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = when {
                            isAiSearching -> "Searching…"
                            !aiSearchSummary.isNullOrBlank() -> aiSearchSummary
                            hasAiKey -> "AI Search"
                            else -> "API key not set"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (aiSearchSummary != null) textColor else secondaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isAiSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (aiSearchSummary != null) {
                    TextButton(
                        onClick = onClearAiSearch,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 40.dp)
                            .testTag("search_clear_ai_button")
                    ) {
                        Text("Reset")
                    }
                } else {
                    TextButton(
                        onClick = {
                            if (hasAiKey) onRunAiSearch() else onOpenAiSettings?.invoke()
                        },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 40.dp)
                            .testTag("search_ask_ai_button")
                    ) {
                        Text(if (hasAiKey) "Ask AI" else "Set Key")
                    }
                }
            }

            aiSearchError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SquareGrid(
    media: List<MediaItem>,
    columnsCount: Int,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cols = columnsCount.coerceAtLeast(1)
    val rows = remember(media, cols) {
        media.chunked(cols)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                rowItems.forEach { item ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpen(item) }
                            .testTag("media_item_${item.id}")
                    ) {
                        AsyncImage(
                            model = remember(item.uri, context) {
                                ThumbnailUtils.buildThumbnailRequest(context, item.uri)
                            },
                            contentDescription = item.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        if (item.isVideo) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    .padding(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Video",
                                    modifier = Modifier.size(14.dp),
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
                val remainder = cols - rowItems.size
                repeat(remainder) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DynamicMasonryGrid(
    media: List<MediaItem>,
    columnsCount: Int,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cols = columnsCount.coerceAtLeast(1)
    val columns = remember(media, cols) {
        List(cols) { colIndex ->
            media.filterIndexed { index, _ -> index % cols == colIndex }
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        columns.forEach { colItems ->
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                colItems.forEach { item ->
                    val aspect = when (Math.abs(item.id.hashCode()) % 3) {
                        0 -> 0.85f
                        1 -> 1.0f
                        else -> 1.25f
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpen(item) }
                            .testTag("media_item_${item.id}")
                    ) {
                        AsyncImage(
                            model = remember(item.uri, context) {
                                ThumbnailUtils.buildThumbnailRequest(context, item.uri)
                            },
                            contentDescription = item.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        if (item.isVideo) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    .padding(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Video",
                                    modifier = Modifier.size(14.dp),
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact list row for list view mode in search results.
 */
@Composable
private fun CompactMediaListRow(
    item: MediaItem,
    onOpen: () -> Unit,
    isDark: Boolean = false
) {
    val textColor = searchPrimaryTextColor(isDark)
    val secondaryText = searchSecondaryTextColor(isDark)
    val cardSurface = searchCardSurfaceColor(isDark)
    val context = LocalContext.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen)
            .testTag("media_row_${item.id}"),
        shape = RoundedCornerShape(10.dp),
        color = cardSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = remember(item.uri, context) {
                    ThumbnailUtils.buildThumbnailRequest(context, item.uri)
                },
                contentDescription = item.name,
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.relativePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (item.isVideo) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Video",
                    modifier = Modifier.size(20.dp),
                    tint = secondaryText
                )
            }
        }
    }
}

/**
 * Custom Date Range Picker Dialog.
 */
@Composable
private fun CustomDateRangeDialog(
    onDismiss: () -> Unit,
    onDateRangeSelected: (startMillis: Long, endMillis: Long) -> Unit,
    isDark: Boolean = false
) {
    val textColor = searchPrimaryTextColor(isDark)
    val secondaryText = searchSecondaryTextColor(isDark)
    val cardSurface = searchCardSurfaceColor(isDark)

    var startDaysAgo by remember { mutableIntStateOf(30) }
    var endDaysAgo by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cardSurface,
        title = {
            Text(
                text = "Select Date Range",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = textColor
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Filter items taken between:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondaryText
                )

                listOf(
                    Pair("Past week (7 days)", 7 to 0),
                    Pair("Past month (30 days)", 30 to 0),
                    Pair("Past 6 months (180 days)", 180 to 0),
                    Pair("Past year (365 days)", 365 to 0),
                    Pair("Older than 1 year", 3650 to 365)
                ).forEach { (label, range) ->
                    val isSelected = startDaysAgo == range.first && endDaysAgo == range.second
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                startDaysAgo = range.first
                                endDaysAgo = range.second
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                startDaysAgo = range.first
                                endDaysAgo = range.second
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = label, color = textColor, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    val startMillis = now - (startDaysAgo.toLong() * 24 * 60 * 60 * 1000L)
                    val endMillis = now - (endDaysAgo.toLong() * 24 * 60 * 60 * 1000L)
                    onDateRangeSelected(startMillis, endMillis)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = textColor,
                    contentColor = if (isDark) SearchColors.DarkBg else Color.White
                )
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = secondaryText)
            }
        }
    )
}
