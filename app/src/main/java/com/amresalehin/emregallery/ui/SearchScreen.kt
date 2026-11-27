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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import com.amresalehin.emregallery.ai.AiStoreEvents
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    gridColumns: Int = 3,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onClearRecent: () -> Unit,
    onBack: () -> Unit,
    onOpenMedia: (MediaItem, List<MediaItem>) -> Unit,
    onOpenSuggestion: (SearchSuggestion) -> Unit,
    initialFilter: SearchFilterState? = null,
    onConsumeInitialFilter: () -> Unit = {}
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

    // Search & Filter State
    var filterState by remember { mutableStateOf(initialFilter ?: SearchFilterState()) }
    var sortMode by remember { mutableStateOf(SearchSortMode.RELEVANCE) }
    var viewMode by remember { mutableStateOf(SearchViewMode.MASONRY) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showDatePickerDialog by remember { mutableStateOf(false) }

    // Treat filtered Discovery destinations as result screens as well.
    val isResultsScreen = stage == SearchStage.RESULTS ||
        (!filterState.isDefault && stage == SearchStage.DISCOVERY)

    LaunchedEffect(initialFilter) {
        if (initialFilter != null) {
            filterState = initialFilter
            onConsumeInitialFilter()
        }
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

    // A request key changes immediately when any search input changes, so stale empty
    // results are never rendered during the frame before the new search coroutine starts.
    val shouldComputeSearchResults = stage == SearchStage.RESULTS || !filterState.isDefault
    val searchRequestKey = remember(
        query,
        filterState,
        sortMode,
        allMedia,
        customAlbums,
        customTagsMap,
        customPeopleMap,
        favourites,
        aiUpdateTick
    ) { Any() }
    var completedSearchRequestKey by remember { mutableStateOf<Any?>(null) }
    val isSearchRunning = shouldComputeSearchResults &&
        completedSearchRequestKey !== searchRequestKey
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

    // Compute Search Results for State C or whenever query/filters/sort change.
    // The request key drives the UI loading state synchronously, before this effect runs.
    LaunchedEffect(searchRequestKey, stage) {
        if (shouldComputeSearchResults) {
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
            // Do not publish results from a request which was cancelled by newer input.
            currentCoroutineContext().ensureActive()
            searchResultsData = results
            completedSearchRequestKey = searchRequestKey
        }
    }

    // Backing out of a filter-only result set should return to Discovery.
    // Clearing the filter while staying in RESULTS would immediately run an empty
    // query against the unfiltered library, making the entire media collection appear.
    fun clearFilterAndReturnToDiscoveryIfNeeded() {
        filterState = SearchFilterState()
        if (query.isBlank()) {
            onQueryChange("")
        }
    }

    // Handle Back Press navigation cleanly
    BackHandler {
        when {
            !filterState.isDefault -> {
                clearFilterAndReturnToDiscoveryIfNeeded()
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
                        clearFilterAndReturnToDiscoveryIfNeeded()
                    } else if (stage == SearchStage.RESULTS) {
                        onQueryChange("")
                    } else {
                        onBack()
                    }
                },
                onOpenFilterSheet = { showFilterSheet = true },
                hasActiveFilters = !filterState.isDefault,
                showResultActions = isResultsScreen,
                sortMode = sortMode,
                onSortChange = { sortMode = it },
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
                focusRequester = focusRequester,
                isDark = isDark
            )

            // Keep quick filters visible while browsing submitted results, even with the keyboard dismissed.
            AnimatedVisibility(
                visible = WindowInsets.isImeVisible || isResultsScreen,
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
                        SubmittedResultsContent(
                            query = query,
                            resultsData = searchResultsData,
                            isSearching = isSearchRunning,
                            gridColumns = gridColumns,
                            viewMode = viewMode,
                            onViewModeChange = { viewMode = it },
                            hasActiveFilters = !filterState.isDefault,
                            onResetFilters = { filterState = SearchFilterState() },
                            collapsedGroups = collapsedGroups,
                            onToggleGroupCollapse = { key ->
                                collapsedGroups[key] = !(collapsedGroups[key] ?: false)
                            },
                            onOpenMedia = { item ->
                                onOpenMedia(item, searchResultsData.items)
                            },
                            isDark = isDark
                        )
                    }

                    stage == SearchStage.TYPING -> {
                        // STATE B: Typing / live suggestions
                        TypingLiveSuggestionsContent(
                            query = query,
                            suggestions = liveSuggestions,
                            onSelectSuggestion = { suggestion ->
                                focusManager.clearFocus()
                                keyboardController?.hide()

                                // Media suggestions should open the matched asset itself.
                                // AI-enriched title matches represent individual media rows and
                                // open directly in the viewer.
                                val targetMediaUri = when (suggestion.type) {
                                    SuggestionType.FILE, SuggestionType.DOCUMENT -> suggestion.thumbnailUri
                                    SuggestionType.AI_MEDIA -> suggestion.thumbnailUri
                                        .takeIf { suggestion.id.startsWith("ai_item_") }
                                    else -> null
                                }
                                val targetMedia = targetMediaUri?.let { uri ->
                                    allMedia.firstOrNull { it.uri == uri }
                                }

                                when {
                                    targetMedia != null -> {
                                        onOpenMedia(targetMedia, allMedia)
                                    }
                                    suggestion.type == SuggestionType.ALBUM -> {
                                        onOpenSuggestion(suggestion)
                                    }
                                    suggestion.type == SuggestionType.PERSON &&
                                        suggestion.targetFilter?.selectedPersonId?.startsWith("name:") != true -> {
                                        // Face-cluster people have a dedicated People detail view.
                                        onOpenSuggestion(suggestion)
                                    }
                                    suggestion.targetFilter != null -> {
                                        // Places, tags, and manually named people open directly
                                        // into their filtered media results.
                                        filterState = suggestion.targetFilter
                                        onSubmitSearch("")
                                    }
                                    else -> {
                                        // Non-entity suggestions (such as recent queries) remain
                                        // searchable rather than being mistaken for destinations.
                                        val q = suggestion.targetQuery ?: suggestion.title
                                        onQueryChange(q)
                                        onSubmitSearch(q)
                                    }
                                }
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
                                // A discovery filter already identifies the intended result set.
                                // Do not also search for the card/entity label: that would
                                // intersect the filter with an unrelated text query (e.g. "Photos").
                                val q = if (category.filterAction != null) {
                                    ""
                                } else {
                                    category.queryAction ?: category.title
                                }
                                onQueryChange(q)
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                onSubmitSearch(q)
                            },
                            onSelectEntity = { entity ->
                                if (entity.filterAction != null) {
                                    filterState = entity.filterAction
                                }
                                val q = if (entity.filterAction != null) {
                                    ""
                                } else {
                                    entity.queryAction ?: entity.title
                                }
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
    isDark: Boolean = false
) {
    val textColor = searchPrimaryTextColor(isDark)
    val secondaryText = searchSecondaryTextColor(isDark)

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

private data class PreparedSearchGridData(
    val sourceItems: List<MediaItem>,
    val columnCount: Int,
    val groupedRows: List<Pair<DayGroup, List<List<MediaItem>>>>
)

/**
 * State C: Submitted Search Results.
 * Date-grouped masonry photo grid, Sort/View controls beside results header.
 */
@Composable
private fun SubmittedResultsContent(
    query: String,
    resultsData: SearchResultData,
    isSearching: Boolean,
    gridColumns: Int,
    viewMode: SearchViewMode,
    onViewModeChange: (SearchViewMode) -> Unit,
    hasActiveFilters: Boolean,
    onResetFilters: () -> Unit,
    collapsedGroups: Map<Long, Boolean>,
    onToggleGroupCollapse: (Long) -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    isDark: Boolean = false
) {
    // Keep the results surface completely blank until the current request completes.
    if (isSearching) return

    val results = resultsData.items
    val totalCount = results.size

    if (totalCount == 0) {
        SearchEmptyState(
            query = query,
            hasActiveFilters = hasActiveFilters,
            onResetFilters = onResetFilters,
            isDark = isDark
        )
        return
    }

    // Share the same lazy-list state and fast scrollbar behaviour as Home.
    val resultsListState = rememberLazyListState()
    val homeGridColumns = gridColumns.coerceIn(1, 6)

    // Search used to regroup and chunk its results synchronously during composition.
    // Prepare the exact same grouped-row structure as Home off the UI thread instead.
    var preparedGrid by remember { mutableStateOf<PreparedSearchGridData?>(null) }
    LaunchedEffect(results, homeGridColumns) {
        val nextGrid = withContext(Dispatchers.Default) {
            val groups = groupMediaByDay(results)
            PreparedSearchGridData(
                sourceItems = results,
                columnCount = homeGridColumns,
                groupedRows = buildGroupedRows(groups, homeGridColumns)
            )
        }
        preparedGrid = nextGrid
    }

    val currentGrid = preparedGrid
    if (currentGrid == null ||
        currentGrid.sourceItems != results ||
        currentGrid.columnCount != homeGridColumns
    ) return
    val groupedRows = currentGrid.groupedRows

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerWidth = maxWidth

        LazyColumn(
            state = resultsListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item(key = "search_results_header", contentType = "header") {
                SearchResultsHeader(
                    queryTitle = query.ifBlank { "All results" },
                    totalCount = totalCount,
                    photosCount = resultsData.totalPhotosCount,
                    videosCount = resultsData.totalVideosCount,
                    isDark = isDark
                )
            }

            groupedRows.forEach { (group, rows) ->
                val isCollapsed = collapsedGroups[group.dayKey] == true

                item(
                    key = "search_date_group_${group.dayKey}",
                    contentType = "date_header"
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SearchDateGroupHeader(
                            label = group.dayLabel,
                            count = group.items.size,
                            isExpanded = !isCollapsed,
                            onToggleExpand = { onToggleGroupCollapse(group.dayKey) },
                            isDark = isDark
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }

                when (viewMode) {
                    SearchViewMode.MASONRY -> {
                        items(
                            count = rows.size,
                            key = { rowIndex ->
                                "search_masonry_${group.dayKey}_${rows[rowIndex].first().id}"
                            },
                            contentType = { "search_masonry_row" }
                        ) { rowIndex ->
                            AnimatedVisibility(
                                visible = !isCollapsed,
                                enter = expandVertically(tween(200)) + fadeIn(tween(150)),
                                exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (rowIndex == rows.lastIndex) {
                                                Modifier.padding(bottom = 12.dp)
                                            } else {
                                                Modifier
                                            }
                                        )
                                ) {
                                    JustifiedMosaicRow(
                                        items = rows[rowIndex],
                                        containerWidth = containerWidth,
                                        rowIndex = rowIndex,
                                        isSquare = false,
                                        onOpen = onOpenMedia
                                    )
                                }
                            }
                        }
                    }

                    SearchViewMode.GRID -> {
                        items(
                            count = rows.size,
                            key = { rowIndex ->
                                "search_square_${group.dayKey}_${rows[rowIndex].first().id}"
                            },
                            contentType = { "search_square_row" }
                        ) { rowIndex ->
                            AnimatedVisibility(
                                visible = !isCollapsed,
                                enter = expandVertically(tween(200)) + fadeIn(tween(150)),
                                exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
                            ) {
                                JustifiedMosaicRow(
                                    items = rows[rowIndex],
                                    containerWidth = containerWidth,
                                    rowIndex = rowIndex,
                                    isSquare = true,
                                    onOpen = onOpenMedia
                                )
                            }
                        }
                    }

                    SearchViewMode.LIST -> {
                        items(
                            count = group.items.size,
                            key = { itemIndex ->
                                "search_list_${group.dayKey}_${group.items[itemIndex].id}"
                            },
                            contentType = { "search_list_row" }
                        ) { itemIndex ->
                            AnimatedVisibility(
                                visible = !isCollapsed,
                                enter = expandVertically(tween(200)) + fadeIn(tween(150)),
                                exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
                            ) {
                                CompactMediaListRow(
                                    item = group.items[itemIndex],
                                    onOpen = { onOpenMedia(group.items[itemIndex]) },
                                    isDark = isDark
                                )
                            }
                        }
                    }
                }
            }
        }

        HomeFastScrollbar(
            listState = resultsListState,
            topContentPadding = 4.dp,
            bottomContentPadding = 32.dp,
            thumbColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

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
