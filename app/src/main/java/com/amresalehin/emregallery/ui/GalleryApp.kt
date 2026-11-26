package com.amresalehin.emregallery.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amresalehin.emregallery.BrowserTabOpenRequest
import com.amresalehin.emregallery.data.CustomAlbum
import com.amresalehin.emregallery.data.LockedVaultManager
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.data.MediaLoadEvent
import com.amresalehin.emregallery.data.MediaLoadResult
import com.amresalehin.emregallery.data.MediaRepository
import com.amresalehin.emregallery.data.MediaSnapshotCache
import com.amresalehin.emregallery.data.ThumbnailPrewarmManager
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.data.SearchFilterState
import com.amresalehin.emregallery.data.SearchSuggestion
import com.amresalehin.emregallery.data.SuggestionType
import com.amresalehin.emregallery.ocr.TesseractOcr
import com.amresalehin.emregallery.face.BuffaloLFaceEngine
import com.amresalehin.emregallery.face.FaceClusterStore
import com.amresalehin.emregallery.face.FaceEngineRegistry
import com.amresalehin.emregallery.face.FaceIndexingRepository
import com.amresalehin.emregallery.face.FaceRecognitionSettings
import com.amresalehin.emregallery.data.StoryGroup

data class NavBackStackEntry(
    val destination: Destination,
    val selectedMedia: MediaItem? = null,
    val viewerMediaList: List<MediaItem> = emptyList(),
    val viewerStoryTitle: String? = null,
    val viewerStories: List<StoryGroup> = emptyList(),
    val currentStoryIndex: Int = 0,
    val mapFocusItem: MediaItem? = null,
    val albumFilter: String? = null,
    val albumFilterReturnDestination: Destination = Destination.ALBUMS,
    val inboxInitialViewMode: DownloaderViewMode = DownloaderViewMode.SEARCH_PAGE,
    val inboxInitialStack: com.amresalehin.emregallery.data.MediaInboxStack? = null
)

val LocalViewerCoordinatedProgress = compositionLocalOf<() -> Float> { { 0f } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryApp(
    externalBrowserOpenRequest: BrowserTabOpenRequest? = null,
    onExternalBrowserOpenRequestConsumed: ((Long) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesRepository(context.applicationContext) }
    val mediaRepository = remember { MediaRepository(context.contentResolver) }
    val mediaSnapshotContext = remember(context) { context.applicationContext }
    val lockedVaultManager = remember { LockedVaultManager(context.applicationContext) }
    var destination by remember { mutableStateOf(Destination.HOME) }
    var previousDestination by remember { mutableStateOf(Destination.HOME) }
    var albumFilterReturnDestination by remember { mutableStateOf(Destination.ALBUMS) }
    var media by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var lockedVaultItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(hasMediaPermission(context)) }
    var selectedMedia by remember { mutableStateOf<MediaItem?>(null) }
    var viewerMediaList by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var viewerStoryTitle by remember { mutableStateOf<String?>(null) }
    var viewerStories by remember { mutableStateOf<List<StoryGroup>>(emptyList()) }
    var currentStoryIndex by remember { mutableIntStateOf(0) }
    var mapFocusItem by remember { mutableStateOf<MediaItem?>(null) }
    val backStack = remember { mutableStateListOf<NavBackStackEntry>() }
    val viewerTransitionProgress = remember { Animatable(0f) }
    var showViewerControls by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var searchStage by remember { mutableStateOf(SearchStage.DISCOVERY) }
    var searchQuery by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf("") }
    var searchVideosOnly by remember { mutableStateOf(false) }
    var pendingSearchFilter by remember { mutableStateOf<SearchFilterState?>(null) }
    var albumFilter by remember { mutableStateOf<String?>(null) }
    val favourites by prefs.favourites.collectAsStateWithLifecycle(initialValue = emptySet())
    val hidden by prefs.hidden.collectAsStateWithLifecycle(initialValue = emptySet())
    val inBin by prefs.bin.collectAsStateWithLifecycle(initialValue = emptySet())
    val archive by prefs.archive.collectAsStateWithLifecycle(initialValue = emptySet())
    val recentSearches by prefs.recentSearches.collectAsStateWithLifecycle(initialValue = emptyList())
    val themePref by prefs.theme.collectAsStateWithLifecycle(initialValue = "system")
    val customAlbums by prefs.customAlbums.collectAsStateWithLifecycle(initialValue = emptyList())
    val sortOrderPref by prefs.sortOrder.collectAsStateWithLifecycle(initialValue = "date_desc")
    val groupByPref by prefs.groupBy.collectAsStateWithLifecycle(initialValue = "day")
    val gridColumnsPref by prefs.gridColumns.collectAsStateWithLifecycle(initialValue = 3)
    val gridStylePref by prefs.gridStyle.collectAsStateWithLifecycle(initialValue = "masonry")
    val ocrLanguagesPref by prefs.ocrLanguages.collectAsStateWithLifecycle(initialValue = setOf("eng"))
    val mlKitLanguagesPref by prefs.mlKitLanguages.collectAsStateWithLifecycle(initialValue = setOf("latin"))
    val ocrModelPref by prefs.ocrModel.collectAsStateWithLifecycle(initialValue = "fast")
    val ocrEnginePref by prefs.ocrEngine.collectAsStateWithLifecycle(initialValue = "tesseract")
    val customTagsMap by prefs.customTagsMap.collectAsStateWithLifecycle(initialValue = emptyMap())
    val customPeopleMap by prefs.customPeopleMap.collectAsStateWithLifecycle(initialValue = emptyMap())
    val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()
    var lastHomeButtonTapAt by remember { mutableLongStateOf(0L) }
    var showHomeViewOptionsSheet by remember { mutableStateOf(false) }
    var showSidePanel by remember { mutableStateOf(false) }
    val lockedFolderPinHash by prefs.lockedFolderPinHash.collectAsStateWithLifecycle(initialValue = null)
    var isLockedFolderUnlocked by remember { mutableStateOf(false) }
    var pendingPermanentDeleteUris by remember { mutableStateOf<List<String>>(emptyList()) }
    // SharedPreferences may still be loading its XML file on a cold process start.
    // Never perform this synchronous read during composition on the UI thread.
    val isWelcomeCompletedInit by produceState<Boolean?>(initialValue = null, prefs) {
        value = withContext(Dispatchers.IO) {
            prefs.isWelcomeCompletedSync()
        }
    }
    val hasCompletedWelcomeFlow by prefs.hasCompletedWelcome.collectAsStateWithLifecycle(initialValue = false)
    val hasCompletedWelcome = hasCompletedWelcomeFlow || isWelcomeCompletedInit == true
    var homeSelectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    val snackbarHostState = remember { SnackbarHostState() }
    var showLockedFolderSetupPrompt by remember { mutableStateOf(false) }
    var activePersonId by remember { mutableStateOf<String?>(null) }
    var hasOpenedPeopleScreen by remember { mutableStateOf(false) }
    var hasOpenedAlbumsScreen by remember { mutableStateOf(false) }
    var inboxInitialViewMode by remember { mutableStateOf(DownloaderViewMode.SEARCH_PAGE) }
    var inboxInitialStack by remember { mutableStateOf<com.amresalehin.emregallery.data.MediaInboxStack?>(null) }
    var showInboxGlobalNavigation by remember { mutableStateOf(true) }
    var hasOpenedAlbumBrowser by remember { mutableStateOf(false) }
    var isAlbumBrowserVisible by remember { mutableStateOf(false) }
    var albumBrowserInitialUrl by remember { mutableStateOf("") }
    var albumBrowserNavigationRequestId by remember { mutableStateOf(0) }

    LaunchedEffect(externalBrowserOpenRequest?.requestId) {
        val request = externalBrowserOpenRequest ?: return@LaunchedEffect
        albumBrowserInitialUrl = request.url
        albumBrowserNavigationRequestId += 1
        hasOpenedAlbumBrowser = true
        isAlbumBrowserVisible = true
        onExternalBrowserOpenRequestConsumed?.invoke(request.requestId)
    }

    var isRefreshing by remember { mutableStateOf(false) }
    var mediaLoadError by remember { mutableStateOf(false) }

    fun refreshMedia(userInitiated: Boolean = false) {
        scope.launch {
            if (userInitiated) {
                isRefreshing = true
                mediaLoadError = false
            } else {
                // Reconcile cached media with MediaStore on every cold launch. Keep existing
                // rows on screen; this flag only holds the empty state while an empty/stale
                // snapshot is being checked against the authoritative MediaStore result.
                loading = true
                mediaLoadError = false
            }
            val startTime = System.currentTimeMillis()
            // Refresh locked vault items concurrently so disk scanning never delays the home grid.
            scope.launch {
                lockedVaultItems = withContext(Dispatchers.IO) {
                    runCatching { lockedVaultManager.getVaultItems() }.getOrDefault(emptyList())
                }
            }
            if (hasMediaPermission(context)) {
                // Publish real MediaStore rows progressively. The first small batch is
                // rendered immediately; later bounded batches keep the UI responsive while
                // the remaining cursor rows are still being read.
                val progressiveItems = LinkedHashMap<String, MediaItem>(media.size + 128)
                media.forEach { progressiveItems[it.uri] = it }
                var firstPageSnapshotWritten = false
                var loadedResult: MediaLoadResult? = null

                try {
                    mediaRepository.loadMediaBatches().collect { event ->
                        when (event) {
                            is MediaLoadEvent.Batch -> {
                                event.items.forEach { progressiveItems[it.uri] = it }
                                media = progressiveItems.values.toList()
                                loading = false
                                mediaLoadError = false

                                // Save the first real page as soon as it arrives. Even if the
                                // app is killed before the full catalogue scan completes, the
                                // next cold launch has a usable snapshot to render immediately.
                                if (!firstPageSnapshotWritten) {
                                    firstPageSnapshotWritten = true
                                    val firstPageSnapshot = withContext(Dispatchers.Default) {
                                        progressiveItems.values
                                            .sortedWith(
                                                compareByDescending<MediaItem> {
                                                    if (it.dateTakenMillis > 0L) it.dateTakenMillis
                                                    else it.dateAddedSeconds * 1000L
                                                }.thenByDescending { it.id }
                                            )
                                            .take(180)
                                    }
                                    withContext(Dispatchers.IO) {
                                        MediaSnapshotCache.write(mediaSnapshotContext, firstPageSnapshot)
                                    }
                                }
                            }
                            is MediaLoadEvent.Completed -> loadedResult = event.result
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    loadedResult = null
                }

                if (loadedResult != null && loadedResult!!.successfulCollections > 0) {
                    val loadedMedia = loadedResult!!.items
                    media = loadedMedia
                    loading = false
                    mediaLoadError = false

                    // Persist the most recent metadata page from the authoritative final result.
                    val recentSnapshot = withContext(Dispatchers.Default) {
                        loadedMedia.sortedWith(
                            compareByDescending<MediaItem> {
                                if (it.dateTakenMillis > 0L) it.dateTakenMillis
                                else it.dateAddedSeconds * 1000L
                            }.thenByDescending { it.id }
                        ).take(180)
                    }
                    withContext(Dispatchers.IO) {
                        MediaSnapshotCache.write(mediaSnapshotContext, recentSnapshot)
                    }

                    // Continue background preparation after the initial visible batches were shown.
                    scope.launch(Dispatchers.Default) {
                        ThumbnailPrewarmManager.start(context.applicationContext, loadedMedia)
                    }
                } else {
                    // A failed query is not proof that the library is empty. Keep the last
                    // valid cached/progressively received rows and expose retry only when none exist.
                    mediaLoadError = media.isEmpty()
                    if (media.isNotEmpty()) loading = false
                }
            }
            if (userInitiated) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < 400L) {
                    delay(400L - elapsed)
                }
                isRefreshing = false
            } else {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            lockedVaultItems = runCatching { lockedVaultManager.getVaultItems() }.getOrDefault(emptyList())
        }
        if (hasMediaPermission(context)) {
            // Restore recent metadata immediately, then reconcile against the live MediaStore.
            // Thumbnail bytes continue to come from the existing Coil/prepared disk caches.
            val cachedMedia = withContext(Dispatchers.IO) {
                MediaSnapshotCache.read(mediaSnapshotContext)
            }
            if (!cachedMedia.isNullOrEmpty()) {
                media = cachedMedia
                // Keep the reconciliation flag active until MediaStore confirms this snapshot.
                // Non-empty cached rows still render immediately; only an empty filtered result
                // remains on the skeleton instead of flashing the empty-library message.
                // Warm the first visible rows from the persistent snapshot before the live
                // MediaStore query finishes. This prepares the shared disk thumbnail files;
                // Coil then serves its visible AsyncImage requests without bulk RAM preloading.
                ThumbnailPrewarmManager.start(
                    context.applicationContext,
                    cachedMedia.take(36)
                )
            }
            refreshMedia()
        }
    }
    LaunchedEffect(media, hidden, inBin, destination) {
        // Location metadata indexing is unrelated to Home thumbnail presentation. Start it
        // only after leaving the initial Home interaction window and never while Home is active.
        if (media.isNotEmpty() && destination != Destination.HOME) {
            delay(3000)
            withContext(Dispatchers.IO) {
                runCatching {
                    val locationCache = com.amresalehin.emregallery.data.PhotoMapLocationCache(context.applicationContext)
                    val safeMedia = media.filter { it.uri !in hidden && !it.isTrashed && it.uri !in inBin }
                    locationCache.scanMediaBatch(context, safeMedia)
                }
            }
        }
    }

    val permanentDeleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val deletedUris = pendingPermanentDeleteUris
        pendingPermanentDeleteUris = emptyList()
        if (result.resultCode == android.app.Activity.RESULT_OK && deletedUris.isNotEmpty()) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    FaceClusterStore(context).deleteMediaCompletely(deletedUris)
                }
                prefs.removePermanently(deletedUris)
                refreshMedia()
                snackbarHostState.showSnackbar(
                    message = "${deletedUris.size} ${if (deletedUris.size == 1) "item" else "items"} permanently deleted",
                    duration = SnackbarDuration.Short
                )
            }
        } else if (result.resultCode != android.app.Activity.RESULT_OK) {
            scope.launch {
                snackbarHostState.showSnackbar("Deletion cancelled")
            }
        }
    }

    fun permanentlyDelete(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val itemUris = items.map { it.uri }.distinct()
        if (hasAllFilesAccess()) {
            scope.launch {
                val deleted = withContext(Dispatchers.IO) {
                    itemUris.filter { uri ->
                        runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) > 0 }.getOrDefault(false)
                    }
                }
                if (deleted.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        FaceClusterStore(context).deleteMediaCompletely(deleted)
                    }
                    prefs.removePermanently(deleted)
                    refreshMedia()
                    snackbarHostState.showSnackbar(
                        message = "${deleted.size} ${if (deleted.size == 1) "item" else "items"} permanently deleted",
                        duration = SnackbarDuration.Short
                    )
                } else {
                    snackbarHostState.showSnackbar("Permission denied: could not delete files")
                }
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val requestLaunched = runCatching {
                val request = android.provider.MediaStore.createDeleteRequest(
                    context.contentResolver,
                    itemUris.map { Uri.parse(it) }
                )
                pendingPermanentDeleteUris = itemUris
                permanentDeleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                true
            }.getOrDefault(false)

            if (!requestLaunched) {
                scope.launch {
                    snackbarHostState.showSnackbar("Permission denied: could not request deletion")
                }
            }
        } else {
            scope.launch {
                val deleted = withContext(Dispatchers.IO) {
                    itemUris.filter { uri ->
                        runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) > 0 }.getOrDefault(false)
                    }
                }
                if (deleted.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        FaceClusterStore(context).deleteMediaCompletely(deleted)
                    }
                    prefs.removePermanently(deleted)
                    refreshMedia()
                    snackbarHostState.showSnackbar(
                        message = "${deleted.size} ${if (deleted.size == 1) "item" else "items"} deleted",
                        duration = SnackbarDuration.Short
                    )
                } else {
                    snackbarHostState.showSnackbar("Permission denied: could not delete file")
                }
            }
        }
    }

    var pendingRestoreUris by remember { mutableStateOf<List<String>>(emptyList()) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uris = pendingRestoreUris
        pendingRestoreUris = emptyList()
        if (result.resultCode == android.app.Activity.RESULT_OK && uris.isNotEmpty()) {
            scope.launch {
                prefs.setInBinBatch(uris.toSet(), false)
                refreshMedia()
                snackbarHostState.showSnackbar("Restored to gallery", duration = SnackbarDuration.Short)
            }
        }
    }

    fun restoreFromDeviceTrash(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val uris = items.map { it.uri }.distinct()
        if (hasAllFilesAccess()) {
            scope.launch {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val cv = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.IS_TRASHED, 0)
                    }
                    withContext(Dispatchers.IO) {
                        uris.forEach { u ->
                            runCatching { context.contentResolver.update(Uri.parse(u), cv, null, null) }
                        }
                    }
                }
                prefs.setInBinBatch(uris.toSet(), false)
                refreshMedia()
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val requestLaunched = runCatching {
                val request = android.provider.MediaStore.createTrashRequest(
                    context.contentResolver,
                    uris.map { Uri.parse(it) },
                    false
                )
                pendingRestoreUris = uris
                restoreLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                true
            }.getOrDefault(false)

            if (!requestLaunched) {
                scope.launch {
                    prefs.setInBinBatch(uris.toSet(), false)
                    refreshMedia()
                }
            }
        } else {
            scope.launch {
                prefs.setInBinBatch(uris.toSet(), false)
                refreshMedia()
            }
        }
    }

    var pendingTrashUris by remember { mutableStateOf<List<String>>(emptyList()) }
    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uris = pendingTrashUris
        pendingTrashUris = emptyList()
        if (result.resultCode == android.app.Activity.RESULT_OK && uris.isNotEmpty()) {
            scope.launch {
                prefs.setInBinBatch(uris.toSet(), true)
                prefs.setHiddenBatch(uris.toSet(), false)
                refreshMedia()
                val res = snackbarHostState.showSnackbar(
                    message = if (uris.size == 1) "Moved to Bin" else "${uris.size} items moved to Bin",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short
                )
                if (res == SnackbarResult.ActionPerformed) {
                    restoreFromDeviceTrash(media.filter { it.uri in uris })
                }
            }
        }
    }

    fun moveToDeviceTrash(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val uris = items.map { it.uri }.distinct()
        if (hasAllFilesAccess()) {
            scope.launch {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val cv = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.IS_TRASHED, 1)
                    }
                    withContext(Dispatchers.IO) {
                        uris.forEach { u ->
                            runCatching { context.contentResolver.update(Uri.parse(u), cv, null, null) }
                        }
                    }
                }
                prefs.setInBinBatch(uris.toSet(), true)
                prefs.setHiddenBatch(uris.toSet(), false)
                refreshMedia()
                val res = snackbarHostState.showSnackbar(
                    message = if (uris.size == 1) "Moved to Bin" else "${uris.size} items moved to Bin",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short
                )
                if (res == SnackbarResult.ActionPerformed) {
                    restoreFromDeviceTrash(items)
                }
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val requestLaunched = runCatching {
                val request = android.provider.MediaStore.createTrashRequest(
                    context.contentResolver,
                    uris.map { Uri.parse(it) },
                    true
                )
                pendingTrashUris = uris
                trashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                true
            }.getOrDefault(false)

            if (!requestLaunched) {
                scope.launch {
                    snackbarHostState.showSnackbar("Cannot move to Bin: permission required")
                }
            }
        } else {
            scope.launch {
                prefs.setInBinBatch(uris.toSet(), true)
                prefs.setHiddenBatch(uris.toSet(), false)
                refreshMedia()
                val res = snackbarHostState.showSnackbar(
                    message = if (uris.size == 1) "Moved to Bin" else "${uris.size} items moved to Bin",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short
                )
                if (res == SnackbarResult.ActionPerformed) {
                    restoreFromDeviceTrash(items)
                }
            }
        }
    }

    fun moveItemsToLocked(uris: Set<String>) {
        if (uris.isEmpty()) return
        if (lockedFolderPinHash.isNullOrBlank()) {
            showLockedFolderSetupPrompt = true
        } else {
            scope.launch {
                val itemsToLock = media.filter { it.uri in uris }
                if (itemsToLock.isNotEmpty()) {
                    val lockedList = withContext(Dispatchers.IO) { lockedVaultManager.lockItems(itemsToLock) }
                    lockedVaultItems = withContext(Dispatchers.IO) { lockedVaultManager.getVaultItems() }
                    prefs.setHiddenBatch(uris, true)
                    refreshMedia()
                    val res = snackbarHostState.showSnackbar(
                        message = if (uris.size == 1) "Moved to Locked folder" else "${uris.size} items moved to Locked folder",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short
                    )
                    if (res == SnackbarResult.ActionPerformed) {
                        withContext(Dispatchers.IO) { lockedVaultManager.unlockItems(lockedList) }
                        lockedVaultItems = withContext(Dispatchers.IO) { lockedVaultManager.getVaultItems() }
                        prefs.setHiddenBatch(uris, false)
                        refreshMedia()
                    }
                }
            }
        }
    }

    var showCreateAlbumDialog by remember { mutableStateOf(false) }
    var createAlbumPresetParent by remember { mutableStateOf("") }
    var showAddToAlbumSheet by remember { mutableStateOf(false) }
    var mediaForAddToAlbum by remember { mutableStateOf<MediaItem?>(null) }
    var showAddPhotosPickerForAlbum by remember { mutableStateOf<CustomAlbum?>(null) }

    var isNavigatingBack by remember { mutableStateOf(false) }
    var isPredictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var predictiveBackEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }

    fun navigateTo(
        dest: Destination,
        rememberPrevious: Boolean = true,
        newSelectedMedia: MediaItem? = selectedMedia,
        newMapFocusItem: MediaItem? = mapFocusItem,
        newViewerMediaList: List<MediaItem> = viewerMediaList,
        newViewerStoryTitle: String? = if (dest == Destination.VIEWER) viewerStoryTitle else null,
        newViewerStories: List<StoryGroup> = if (dest == Destination.VIEWER) viewerStories else emptyList(),
        newCurrentStoryIndex: Int = if (dest == Destination.VIEWER) currentStoryIndex else 0,
        newInboxInitialViewMode: DownloaderViewMode = DownloaderViewMode.SEARCH_PAGE,
        newInboxInitialStack: com.amresalehin.emregallery.data.MediaInboxStack? = null
    ) {
        if (dest == Destination.PEOPLE) {
            hasOpenedPeopleScreen = true
        }
        if (dest == Destination.ALBUMS) {
            hasOpenedAlbumsScreen = true
        }
        if (dest == Destination.MEDIA_INBOX) {
            showInboxGlobalNavigation = true
        }
        if (dest != Destination.LOCKED_FOLDER && dest != Destination.VIEWER) {
            isLockedFolderUnlocked = false
        }
        homeSelectedUris = emptySet()
        isNavigatingBack = false
        if (rememberPrevious && destination != dest) {
            backStack.add(
                NavBackStackEntry(
                    destination = destination,
                    selectedMedia = selectedMedia,
                    viewerMediaList = viewerMediaList,
                    viewerStoryTitle = viewerStoryTitle,
                    viewerStories = viewerStories,
                    currentStoryIndex = currentStoryIndex,
                    mapFocusItem = mapFocusItem,
                    albumFilter = albumFilter,
                    albumFilterReturnDestination = albumFilterReturnDestination,
                    inboxInitialViewMode = inboxInitialViewMode,
                    inboxInitialStack = inboxInitialStack
                )
            )
            previousDestination = destination
        }
        destination = dest
        selectedMedia = newSelectedMedia
        mapFocusItem = newMapFocusItem
        viewerMediaList = newViewerMediaList
        viewerStoryTitle = newViewerStoryTitle
        viewerStories = newViewerStories
        currentStoryIndex = newCurrentStoryIndex
        inboxInitialViewMode = newInboxInitialViewMode
        inboxInitialStack = newInboxInitialStack
    }

    fun openMediaInViewer(item: MediaItem, list: List<MediaItem>) {
        val safeList = if (list.any { it.uri == item.uri }) list else listOf(item)
        selectedMedia = item
        viewerMediaList = safeList
        viewerStoryTitle = null
        viewerStories = emptyList()
        currentStoryIndex = 0
        showViewerControls = false
        showDetails = false
        scope.launch { viewerTransitionProgress.snapTo(0f) }
        navigateTo(
            dest = Destination.VIEWER,
            rememberPrevious = true,
            newSelectedMedia = item,
            newViewerMediaList = safeList,
            newViewerStoryTitle = null,
            newViewerStories = emptyList(),
            newCurrentStoryIndex = 0
        )
    }

    fun openMemoriesInViewer(stories: List<StoryGroup>, startIndex: Int) {
        if (stories.isEmpty()) return
        val initialStory = stories.getOrNull(startIndex) ?: stories.first()
        if (initialStory.items.isEmpty()) return
        val cover = initialStory.items.first()
        selectedMedia = cover
        viewerMediaList = initialStory.items
        viewerStoryTitle = initialStory.title
        viewerStories = stories
        currentStoryIndex = startIndex
        showViewerControls = true
        showDetails = false
        scope.launch { viewerTransitionProgress.snapTo(0f) }
        navigateTo(
            dest = Destination.VIEWER,
            rememberPrevious = true,
            newSelectedMedia = cover,
            newViewerMediaList = initialStory.items,
            newViewerStoryTitle = initialStory.title,
            newViewerStories = stories,
            newCurrentStoryIndex = startIndex
        )
    }


    fun performBackNavigation() {
        if (homeSelectedUris.isNotEmpty()) {
            homeSelectedUris = emptySet()
            return
        }
        isNavigatingBack = true
        scope.launch { viewerTransitionProgress.snapTo(0f) }
        if (destination == Destination.LOCKED_FOLDER) {
            isLockedFolderUnlocked = false
        }
        when {
            showSidePanel -> {
                showSidePanel = false
            }
            destination == Destination.VIEWER && showDetails -> {
                showDetails = false
            }
            backStack.isNotEmpty() -> {
                val entry = backStack.removeAt(backStack.size - 1)
                destination = entry.destination
                selectedMedia = entry.selectedMedia
                viewerMediaList = entry.viewerMediaList
                viewerStoryTitle = entry.viewerStoryTitle
                viewerStories = entry.viewerStories
                currentStoryIndex = entry.currentStoryIndex
                mapFocusItem = entry.mapFocusItem
                albumFilter = entry.albumFilter
                albumFilterReturnDestination = entry.albumFilterReturnDestination
                inboxInitialViewMode = entry.inboxInitialViewMode
                inboxInitialStack = entry.inboxInitialStack
                previousDestination = backStack.lastOrNull()?.destination ?: Destination.HOME
            }
            albumFilter != null -> {
                albumFilter = null
                destination = albumFilterReturnDestination
                viewerStoryTitle = null
            }
            destination in listOf(
                Destination.DEVICE_FOLDERS,
                Destination.ALBUM_CATALOG,
                Destination.FAVOURITES,
                Destination.BIN,
                Destination.VIDEOS,
                Destination.RECENTLY_ADDED,
                Destination.LOCKED_FOLDER,
                Destination.ARCHIVE,
                Destination.PEOPLE,
                Destination.MAP
            ) -> {
                destination = Destination.ALBUMS
                mapFocusItem = null
                viewerStoryTitle = null
            }
            destination in listOf(
                Destination.LIBRARY_OVERVIEW,
                Destination.INDEXING_STATUS,
                Destination.OCR_SETTINGS,
                Destination.AI_SETTINGS,
                Destination.ABOUT
            ) -> {
                destination = Destination.SETTINGS
                viewerStoryTitle = null
            }
            destination == Destination.MEDIA_INBOX -> {
                destination = Destination.HOME
                mapFocusItem = null
                selectedMedia = null
                viewerStoryTitle = null
            }
            destination == Destination.VIEWER -> {
                val targetDest = previousDestination.takeIf { it != Destination.VIEWER } ?: Destination.HOME
                destination = targetDest
                selectedMedia = null
                viewerMediaList = emptyList()
                viewerStoryTitle = null
            }
            else -> {
                destination = Destination.HOME
                mapFocusItem = null
                selectedMedia = null
                viewerStoryTitle = null
            }
        }
    }

    val canGoBack = homeSelectedUris.isNotEmpty() || backStack.isNotEmpty() || destination != Destination.HOME || albumFilter != null || showSidePanel || (destination == Destination.VIEWER && showDetails)

    PredictiveBackHandler(enabled = canGoBack && !isAlbumBrowserVisible) { progress: Flow<BackEventCompat> ->
        try {
            isPredictiveBackActive = true
            progress.collect { backEvent ->
                predictiveBackProgress = backEvent.progress
                predictiveBackEdge = backEvent.swipeEdge
            }
            performBackNavigation()
        } catch (e: CancellationException) {
            predictiveBackProgress = 0f
            throw e
        } finally {
            isPredictiveBackActive = false
            predictiveBackProgress = 0f
        }
    }

    val activeCustomAlbum = remember(albumFilter, customAlbums) {
        customAlbums.firstOrNull { it.id == albumFilter || it.fullPath == albumFilter }
    }
    val activeAlbumTitle = remember(albumFilter, activeCustomAlbum) {
        activeCustomAlbum?.fullPath ?: when (albumFilter) {
            "Hidden" -> "Locked folder"
            "Bin" -> "Bin"
            "Archive" -> "Archive"
            "Favourites" -> "Favourites"
            "Videos" -> "Videos"
            "Recently added" -> "Recently added"
            "People" -> "People & pets"
            "Places", "Map" -> "Map & Places"
            "Albums" -> "Albums"
            "Folders" -> "Device folders"
            else -> albumFilter
        }
    }

    fun filterVisibleMedia(): List<MediaItem> {
        val nowCutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        return media.filter { item ->
            val isItemBinned = item.isTrashed || item.uri in inBin
            val inScope = when (albumFilter) {
                "Hidden" -> item.uri in hidden && !isItemBinned
                "Bin" -> isItemBinned
                "Archive" -> item.uri in archive && !isItemBinned
                else -> item.uri !in hidden && !isItemBinned && item.uri !in archive
            }
            val matchesCategory = when (albumFilter) {
                null, "Hidden", "Bin", "Archive", "People", "Places", "Map", "Albums", "Folders" -> true
                "Favourites" -> item.uri in favourites
                "Videos" -> item.isVideo
                "Recently added" -> item.dateAddedSeconds * 1000L > nowCutoff
                else -> {
                    val albumMatch = customAlbums.firstOrNull { it.id == albumFilter || it.fullPath == albumFilter || it.name == albumFilter }
                    if (albumMatch != null) {
                        item.uri in albumMatch.mediaUris
                    } else {
                        item.relativePath.contains(albumFilter.orEmpty(), true)
                    }
                }
            }
            inScope && matchesCategory
        }
    }

    val visibleMediaFilterKey = remember(media, hidden, inBin, archive, favourites, albumFilter, customAlbums) {
        Any()
    }
    var visibleMedia by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var completedVisibleMediaFilterKey by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(visibleMediaFilterKey) {
        val filtered = if (media.size <= 150) {
            filterVisibleMedia()
        } else {
            withContext(Dispatchers.Default) { filterVisibleMedia() }
        }
        visibleMedia = filtered
        completedVisibleMediaFilterKey = visibleMediaFilterKey
    }
    // Keep the previous real media rows visible while the next batch/filter is prepared.
    val visibleMediaLoading = completedVisibleMediaFilterKey !== visibleMediaFilterKey

    var peopleMedia by remember {
        mutableStateOf(
            if (media.size <= 150) {
                media.filter { it.uri !in hidden && !it.isTrashed && it.uri !in inBin && it.uri !in archive }
            } else emptyList()
        )
    }
    LaunchedEffect(hasOpenedPeopleScreen, media, hidden, inBin, archive) {
        if (!hasOpenedPeopleScreen) {
            peopleMedia = emptyList()
            return@LaunchedEffect
        }
        peopleMedia = if (media.size <= 150) {
            media.filter { it.uri !in hidden && !it.isTrashed && it.uri !in inBin && it.uri !in archive }
        } else {
            withContext(Dispatchers.Default) {
                media.filter { it.uri !in hidden && !it.isTrashed && it.uri !in inBin && it.uri !in archive }
            }
        }
    }
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topContentPadding = statusBarTop + 68.dp
    val bottomContentPadding = navBarBottom + 88.dp

    LaunchedEffect(Unit) {
        prefs.clearHomeScrollPosition()
    }

    // Avoid briefly showing the welcome flow for returning users while the legacy
    // onboarding preference is loaded on IO. The activity remains responsive meanwhile.
    if (isWelcomeCompletedInit == null && !hasCompletedWelcomeFlow) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val hasMediaPerm = hasMediaPermission(context)
    val hasNotifPerm = hasNotificationPermission(context)
    val hasAllFilesPerm = hasAllFilesAccess()
    val allRequiredPermissions = hasMediaPerm && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasNotifPerm) && hasAllFilesPerm
    if (!hasCompletedWelcome || !allRequiredPermissions) {
        WelcomeScreen(
            initialStep = if (hasCompletedWelcome) 2 else 0,
            onComplete = {
                scope.launch {
                    prefs.setCompletedWelcome(true)
                    refreshMedia()
                }
            }
        )
        return
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (isPredictiveBackActive) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                )
            }

            val baseDestination = if (destination == Destination.VIEWER) previousDestination else destination
            val returnDestination = if (destination == Destination.VIEWER) previousDestination else destination

            CompositionLocalProvider(LocalViewerCoordinatedProgress provides { if (destination == Destination.VIEWER) viewerTransitionProgress.value else 0f }) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (isPredictiveBackActive && predictiveBackProgress > 0f) {
                                val p = predictiveBackProgress
                                val scale = 1f - (p * 0.12f)
                                scaleX = scale
                                scaleY = scale
                                val maxShift = 48.dp.toPx()
                                translationX = if (predictiveBackEdge == BackEventCompat.EDGE_LEFT) {
                                    p * maxShift
                                } else {
                                    -p * maxShift
                                }
                                shape = RoundedCornerShape((p * 28).dp)
                                clip = true
                                shadowElevation = (p * 20).dp.toPx()
                            }
                        }
                ) {
                    // Keep the album screen mounted while other destinations are active.
                    // Moving the inactive host off-screen preserves its scroll position and
                    // remembered UI state instead of recreating the screen on every visit.
                    if (hasOpenedAlbumsScreen || baseDestination == Destination.ALBUMS) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val albumsVisible = baseDestination == Destination.ALBUMS
                                alpha = if (albumsVisible) 1f else 0f
                                translationX = if (albumsVisible) 0f else size.width * 2f
                            }
                            .zIndex(if (baseDestination == Destination.ALBUMS) 1f else -1f)
                    ) {
                        AlbumsScreen(
                        media = media,
                        favourites = favourites,
                        hidden = hidden,
                        inBin = remember(media, inBin) { (inBin + media.filter { it.isTrashed }.map { it.uri }).toSet() },
                        archive = archive,
                        customAlbums = customAlbums,
                        topContentPadding = topContentPadding,
                        bottomContentPadding = bottomContentPadding,
                        isRefreshing = isRefreshing,
                        onRefresh = { refreshMedia(userInitiated = true) },
                        onFilter = { when (it) { "Map" -> {
                            navigateTo(Destination.MAP, rememberPrevious = true, newMapFocusItem = null)
                        }; "Folders" -> navigateTo(Destination.DEVICE_FOLDERS); "Albums" -> navigateTo(Destination.ALBUM_CATALOG); "Favourites" -> { albumFilter = null; navigateTo(Destination.FAVOURITES) }; "Bin" -> { albumFilter = null; navigateTo(Destination.BIN) }; "Videos" -> { albumFilter = null; navigateTo(Destination.VIDEOS) }; "Recently added" -> { albumFilter = null; navigateTo(Destination.RECENTLY_ADDED) }; "Hidden", "Locked folder" -> { albumFilter = null; navigateTo(Destination.LOCKED_FOLDER) }; "Archive" -> { albumFilter = null; navigateTo(Destination.ARCHIVE) }; "People" -> { albumFilter = null; activePersonId = null; navigateTo(Destination.PEOPLE) }; "Saved", "Saved Media Library", "Saved Library" -> { albumFilter = null; navigateTo(dest = Destination.MEDIA_INBOX, rememberPrevious = true, newInboxInitialViewMode = DownloaderViewMode.SAVED_LIBRARY) }; "Media Downloader", "Media Inbox", "Inbox" -> { albumFilter = null; navigateTo(dest = Destination.MEDIA_INBOX, rememberPrevious = true, newInboxInitialViewMode = DownloaderViewMode.SEARCH_PAGE) }; else -> { albumFilterReturnDestination = Destination.ALBUMS; albumFilter = it; navigateTo(Destination.HOME) } } },
                        onOpen = { openMediaInViewer(it, visibleMedia) },
                        onOpenPerson = { personId ->
                            activePersonId = personId
                            navigateTo(Destination.PEOPLE)
                        },
                        onOpenBrowser = {
                            hasOpenedAlbumBrowser = true
                            isAlbumBrowserVisible = true
                        },
                        onCreateAlbumClick = { parentPreset ->
                            createAlbumPresetParent = parentPreset
                            showCreateAlbumDialog = true
                        },
                        onDeleteAlbum = { id -> scope.launch { prefs.deleteCustomAlbum(id) } },
                        onRenameAlbum = { id, newName -> scope.launch { prefs.renameCustomAlbum(id, newName) } }
                    )
                    }
                    }

                    // Retain face-index and People UI state after the first visit.
                    // The BackHandler inside PeopleScreen is gated by isVisible below.
                    if (hasOpenedPeopleScreen) {
                        val peopleVisible = baseDestination == Destination.PEOPLE
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = if (peopleVisible) 1f else 0f
                                    translationX = if (peopleVisible) 0f else size.width * 2f
                                }
                                .zIndex(if (peopleVisible) 1f else -1f)
                        ) {
                            PeopleScreen(
                                media = peopleMedia,
                                initialPersonId = activePersonId,
                                isVisible = peopleVisible,
                                onBack = {
                                    activePersonId = null
                                    performBackNavigation()
                                },
                                onOpenMedia = { item -> openMediaInViewer(item, peopleMedia) },
                                onOpenIndexStatus = { navigateTo(Destination.INDEXING_STATUS) }
                            )
                        }
                    }

                    // Keep the main gallery mounted so navigation away and back does not
                    // recreate its grid, scroll effects, or remembered UI state.
                    val homeVisible = baseDestination == Destination.HOME
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = if (homeVisible) 1f else 0f
                                translationX = if (homeVisible) 0f else size.width * 2f
                            }
                            .zIndex(if (homeVisible) 1f else -1f)
                    ) {
                        HomeScreen(
                            isVisible = homeVisible,
                        listState = homeListState,
                        media = visibleMedia,
                        loading = loading || visibleMediaLoading,
                        mediaLoadError = mediaLoadError,
                        isRefreshing = isRefreshing,
                        onRefresh = { refreshMedia(userInitiated = true) },
                        onPermissionGranted = { refreshMedia() },
                        albumTitle = activeAlbumTitle,
                        sortOrder = sortOrderPref,
                        groupBy = groupByPref,
                        gridColumns = gridColumnsPref,
                        gridStyle = gridStylePref,
                        topContentPadding = topContentPadding,
                        bottomContentPadding = bottomContentPadding,
                        onClearAlbumFilter = { performBackNavigation() },
                        onAddPhotosToAlbum = if (activeCustomAlbum != null) { { showAddPhotosPickerForAlbum = activeCustomAlbum } } else null,
                        onMoveToBin = { uris ->
                            val itemsToBin = media.filter { it.uri in uris }
                            moveToDeviceTrash(itemsToBin)
                        },
                        onMoveToLocked = { uris -> moveItemsToLocked(uris) },
                        onShareItems = { uris ->
                            val itemsToShare = media.filter { it.uri in uris }
                            shareMultipleMediaItems(context, itemsToShare)
                        },
                        onAddToAlbum = { uris ->
                            val first = media.firstOrNull { it.uri in uris }
                            if (first != null) {
                                mediaForAddToAlbum = first
                                showAddToAlbumSheet = true
                            }
                        },
                        selectedUris = homeSelectedUris,
                        onSelectionChange = { homeSelectedUris = it },
                        onOpen = { item, orderedItems -> openMediaInViewer(item, orderedItems) }
                    )
                    }

                    when (baseDestination) {
                    Destination.HOME -> Unit
                    Destination.ALBUMS -> Unit
                    Destination.FAVOURITES -> {
                        val favouriteItems = remember(media, favourites, hidden, inBin, archive) {
                            media.filter { it.uri in favourites && it.uri !in hidden && !it.isTrashed && it.uri !in inBin && it.uri !in archive }
                        }
                        FavouritesScreen(
                            items = favouriteItems,
                            isRefreshing = isRefreshing,
                            onRefresh = { refreshMedia(userInitiated = true) },
                            onBack = { performBackNavigation() },
                            onOpen = { openMediaInViewer(it, favouriteItems) },
                            onRemoveFavourite = { item -> scope.launch { prefs.toggleFavourite(item.uri) } }
                        )
                    }
                    Destination.BIN -> {
                        val binItems = remember(media, inBin) {
                            media.filter { it.isTrashed || it.uri in inBin }
                        }
                        BinScreen(
                            items = binItems,
                            isRefreshing = isRefreshing,
                            onRefresh = { refreshMedia(userInitiated = true) },
                            onBack = { performBackNavigation() },
                            onOpen = { openMediaInViewer(it, binItems) },
                            onRestore = { item -> restoreFromDeviceTrash(listOf(item)) },
                            onRestoreBatch = { uris -> restoreFromDeviceTrash(media.filter { it.uri in uris }) },
                            onDeletePermanently = { item -> permanentlyDelete(listOf(item)) },
                            onDeletePermanentlyBatch = { items -> permanentlyDelete(items) },
                            onEmptyBin = { items -> permanentlyDelete(items) }
                        )
                    }
                    Destination.VIDEOS -> {
                        val videoItems = remember(media, hidden, inBin, archive) {
                            media.filter { it.isVideo && it.uri !in hidden && !it.isTrashed && it.uri !in inBin && it.uri !in archive }
                        }
                        HomeScreen(
                            media = videoItems,
                            loading = loading,
                            mediaLoadError = mediaLoadError,
                            isRefreshing = isRefreshing,
                            onRefresh = { refreshMedia(userInitiated = true) },
                            onPermissionGranted = { refreshMedia() },
                            albumTitle = "Videos",
                            sortOrder = sortOrderPref,
                            groupBy = groupByPref,
                            gridColumns = gridColumnsPref,
                            gridStyle = gridStylePref,
                            topContentPadding = topContentPadding,
                            bottomContentPadding = bottomContentPadding,
                            onClearAlbumFilter = { performBackNavigation() },
                            onAddPhotosToAlbum = null,
                            onMoveToBin = { uris -> moveToDeviceTrash(media.filter { it.uri in uris }) },
                            onMoveToLocked = { uris -> moveItemsToLocked(uris) },
                            onShareItems = { uris -> shareMultipleMediaItems(context, media.filter { it.uri in uris }) },
                            onAddToAlbum = null,
                            onOpen = { item, orderedItems -> openMediaInViewer(item, orderedItems) }
                        )
                    }
                    Destination.RECENTLY_ADDED -> {
                        val recentItems = remember(media, hidden, inBin, archive) {
                            media.filter { item ->
                                item.uri !in hidden && !item.isTrashed && item.uri !in inBin && item.uri !in archive &&
                                    item.dateAddedSeconds * 1000L > System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                            }
                        }
                        HomeScreen(
                            media = recentItems,
                            loading = loading,
                            mediaLoadError = mediaLoadError,
                            isRefreshing = isRefreshing,
                            onRefresh = { refreshMedia(userInitiated = true) },
                            onPermissionGranted = { refreshMedia() },
                            albumTitle = "Recently added",
                            sortOrder = sortOrderPref,
                            groupBy = groupByPref,
                            gridColumns = gridColumnsPref,
                            gridStyle = gridStylePref,
                            topContentPadding = topContentPadding,
                            bottomContentPadding = bottomContentPadding,
                            onClearAlbumFilter = { performBackNavigation() },
                            onAddPhotosToAlbum = null,
                            onMoveToBin = { uris -> moveToDeviceTrash(media.filter { it.uri in uris }) },
                            onMoveToLocked = { uris -> moveItemsToLocked(uris) },
                            onShareItems = { uris -> shareMultipleMediaItems(context, media.filter { it.uri in uris }) },
                            onAddToAlbum = null,
                            onOpen = { item, orderedItems -> openMediaInViewer(item, orderedItems) }
                        )
                    }
                    Destination.LOCKED_FOLDER -> {
                        val legacyLocked = remember(media, hidden, inBin) {
                            media.filter { it.uri in hidden && !it.isTrashed && it.uri !in inBin }
                        }
                        val allLockedItems = remember(lockedVaultItems, legacyLocked) {
                            (lockedVaultItems + legacyLocked).distinctBy { it.id }
                        }
                        LockedFolderScreen(
                            items = allLockedItems,
                            availableGalleryMedia = media.filter { it.uri !in hidden && !it.isTrashed && it.uri !in inBin && it.uri !in archive },
                            isUnlocked = isLockedFolderUnlocked,
                            hasPinSet = !lockedFolderPinHash.isNullOrBlank(),
                            onBack = {
                                isLockedFolderUnlocked = false
                                performBackNavigation()
                            },
                            onOpen = { openMediaInViewer(it, allLockedItems) },
                            onUnlockItem = { item ->
                                scope.launch {
                                    withContext(Dispatchers.IO) { lockedVaultManager.unlockItems(listOf(item)) }
                                    prefs.setHidden(item.uri, false)
                                    lockedVaultItems = withContext(Dispatchers.IO) { lockedVaultManager.getVaultItems() }
                                    refreshMedia()
                                    snackbarHostState.showSnackbar("Restored to gallery", duration = SnackbarDuration.Short)
                                }
                            },
                            onUnlockBatch = { uris ->
                                scope.launch {
                                    val targets = allLockedItems.filter { it.uri in uris }
                                    withContext(Dispatchers.IO) { lockedVaultManager.unlockItems(targets) }
                                    prefs.setHiddenBatch(uris, false)
                                    lockedVaultItems = withContext(Dispatchers.IO) { lockedVaultManager.getVaultItems() }
                                    refreshMedia()
                                    snackbarHostState.showSnackbar("${uris.size} items restored to gallery", duration = SnackbarDuration.Short)
                                }
                            },
                            onDeleteBatchToBin = { uris ->
                                scope.launch {
                                    val targets = allLockedItems.filter { it.uri in uris }
                                    withContext(Dispatchers.IO) { lockedVaultManager.deletePermanently(targets) }
                                    prefs.removePermanently(uris)
                                    lockedVaultItems = withContext(Dispatchers.IO) { lockedVaultManager.getVaultItems() }
                                    refreshMedia()
                                    snackbarHostState.showSnackbar("${uris.size} items deleted permanently", duration = SnackbarDuration.Short)
                                }
                            },
                            onAddItemsToLocked = { uris ->
                                moveItemsToLocked(uris)
                            },
                            onSetPin = { newPin -> scope.launch { prefs.setLockedFolderPin(newPin) } },
                            onVerifyPin = { pin -> prefs.verifyLockedFolderPin(pin) },
                            onUnlockSuccess = { isLockedFolderUnlocked = true },
                            onLockAgain = { isLockedFolderUnlocked = false }
                        )
                    }
                    Destination.ARCHIVE -> {
                        val archiveItems = remember(media, archive, inBin, hidden) {
                            media.filter { it.uri in archive && !it.isTrashed && it.uri !in inBin && it.uri !in hidden }
                        }
                        ArchiveScreen(
                            items = archiveItems,
                            isRefreshing = isRefreshing,
                            onRefresh = { refreshMedia(userInitiated = true) },
                            onBack = { performBackNavigation() },
                            onOpen = { openMediaInViewer(it, archiveItems) },
                            onUnarchive = { item -> scope.launch { prefs.setArchived(item.uri, false) } }
                        )
                    }
                    Destination.PEOPLE -> Unit
                    Destination.MAP -> {
                        val mapMedia = remember(media, hidden, inBin, archive) {
                            media.filter { it.uri !in hidden && !it.isTrashed && it.uri !in inBin && it.uri !in archive }
                        }
                        PhotoMapScreen(
                            media = mapMedia,
                            focusItem = mapFocusItem,
                            onBack = { performBackNavigation() },
                            onOpen = { photo ->
                                openMediaInViewer(photo, mapMedia)
                            }
                        )
                    }
                    Destination.DEVICE_FOLDERS -> DeviceFoldersScreen(
                        media = media.filter { it.uri !in hidden && !it.isTrashed && it.uri !in inBin && it.uri !in archive },
                        isRefreshing = isRefreshing,
                        onRefresh = { refreshMedia(userInitiated = true) },
                        onBack = { performBackNavigation() },
                        onOpenFolder = { folderPath -> albumFilterReturnDestination = Destination.DEVICE_FOLDERS; albumFilter = folderPath; navigateTo(Destination.HOME) }
                    )
                    Destination.ALBUM_CATALOG -> AlbumCatalogScreen(
                        albums = customAlbums,
                        media = media.filter { it.uri !in hidden && !it.isTrashed && it.uri !in inBin && it.uri !in archive },
                        isRefreshing = isRefreshing,
                        onRefresh = { refreshMedia(userInitiated = true) },
                        onBack = { performBackNavigation() },
                        onCreateAlbum = { parentPath ->
                            createAlbumPresetParent = parentPath
                            showCreateAlbumDialog = true
                        },
                        onOpenAlbum = { album ->
                            albumFilterReturnDestination = Destination.ALBUM_CATALOG
                            albumFilter = album.id
                            navigateTo(Destination.HOME)
                        }
                    )
                    Destination.SETTINGS -> SettingsScreen(
                        total = media.size,
                        onMenuClick = { showSidePanel = true },
                        theme = themePref,
                        topContentPadding = statusBarTop + 14.dp,
                        bottomContentPadding = bottomContentPadding,
                        onTheme = { scope.launch { prefs.setTheme(it) } },
                        onRefresh = { refreshMedia() },
                        onSettings = { label ->
                            when (label) {
                                "AI & API Key" -> navigateTo(Destination.AI_SETTINGS)
                                "Indexing Status" -> navigateTo(Destination.INDEXING_STATUS)
                                "About" -> navigateTo(Destination.ABOUT)
                                "Inbox" -> navigateTo(Destination.MEDIA_INBOX)
                            }
                        },
                        onPermissionSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                    )
                    Destination.LIBRARY_OVERVIEW -> LibraryOverviewScreen(
                        media = media.filter { it.uri !in hidden && !it.isTrashed && it.uri !in inBin && it.uri !in archive },
                        loading = loading,
                        isRefreshing = isRefreshing,
                        onBack = { performBackNavigation() },
                        onRefresh = { refreshMedia(userInitiated = true) }
                    )
                    Destination.INDEXING_STATUS -> IndexingStatusScreen(
                        media = media.filter { it.uri !in hidden && !it.isTrashed && it.uri !in inBin && it.uri !in archive },
                        ocrLanguages = ocrLanguagesPref,
                        ocrModel = ocrModelPref,
                        ocrEngine = ocrEnginePref,
                        mlKitLanguages = mlKitLanguagesPref,
                        onModelChange = { newModel -> scope.launch { prefs.setOcrModel(newModel) } },
                        onEngineChange = { newEngine -> scope.launch { prefs.setOcrEngine(newEngine) } },
                        onToggleLanguage = { code ->
                            val updated = ocrLanguagesPref.toMutableSet()
                            if (code in updated) {
                                if (updated.size > 1) updated.remove(code)
                            } else {
                                updated.add(code)
                            }
                            scope.launch { prefs.setOcrLanguages(updated) }
                        },
                        onToggleMlKitLanguage = { code ->
                            val updated = mlKitLanguagesPref.toMutableSet()
                            when (code) {
                                "all" -> updated.addAll(com.amresalehin.emregallery.ocr.MlKitLanguage.ALL_CODES)
                                "latin_only" -> {
                                    updated.clear()
                                    updated.add("latin")
                                }
                                else -> {
                                    if (code in updated) {
                                        if (updated.size > 1) updated.remove(code)
                                    } else {
                                        updated.add(code)
                                    }
                                }
                            }
                            scope.launch { prefs.setMlKitLanguages(updated) }
                        },
                        onOpenMap = {
                            navigateTo(Destination.MAP)
                        },
                        onOpenAiSettings = {
                            navigateTo(Destination.AI_SETTINGS)
                        },
                        onBack = { performBackNavigation() }
                    )
                    Destination.OCR_SETTINGS -> OcrSettingsScreen(
                        currentEngine = ocrEnginePref,
                        currentModel = ocrModelPref,
                        ocrLanguages = ocrLanguagesPref,
                        mlKitLanguages = mlKitLanguagesPref,
                        onSelectEngine = { newEngine -> scope.launch { prefs.setOcrEngine(newEngine) } },
                        onSelectModel = { newModel -> scope.launch { prefs.setOcrModel(newModel) } },
                        onToggleLanguage = { code ->
                            val updated = ocrLanguagesPref.toMutableSet()
                            if (code in updated) {
                                if (updated.size > 1) updated.remove(code)
                            } else {
                                updated.add(code)
                            }
                            scope.launch { prefs.setOcrLanguages(updated) }
                        },
                        onToggleMlKitLanguage = { code ->
                            val updated = mlKitLanguagesPref.toMutableSet()
                            when (code) {
                                "all" -> updated.addAll(com.amresalehin.emregallery.ocr.MlKitLanguage.ALL_CODES)
                                "latin_only" -> {
                                    updated.clear()
                                    updated.add("latin")
                                }
                                else -> {
                                    if (code in updated) {
                                        if (updated.size > 1) updated.remove(code)
                                    } else {
                                        updated.add(code)
                                    }
                                }
                            }
                            scope.launch { prefs.setMlKitLanguages(updated) }
                        },
                        onBack = { performBackNavigation() }
                    )
                    Destination.AI_SETTINGS -> AiSettingsScreen(
                        onBack = { performBackNavigation() }
                    )
                    Destination.ABOUT -> AboutScreen(
                        onBack = { performBackNavigation() }
                    )
                    Destination.MEDIA_INBOX -> MediaInboxScreen(
                        initialViewMode = inboxInitialViewMode,
                        initialStackForDetail = inboxInitialStack,
                        onOpenMenu = { showSidePanel = true },
                        onGlobalNavigationVisibilityChanged = { visible ->
                            showInboxGlobalNavigation = visible
                        },
                        onOpenBrowser = { url ->
                            albumBrowserInitialUrl = url
                            albumBrowserNavigationRequestId += 1
                            hasOpenedAlbumBrowser = true
                            isAlbumBrowserVisible = true
                        },
                        onBack = { performBackNavigation() },
                        onOpenMediaItem = { uri ->
                            val file = java.io.File(uri.removePrefix("file://"))
                            val fallbackName = if (file.exists()) file.name else uri.substringAfterLast('/').ifBlank { "media_item" }
                            val classified = com.amresalehin.emregallery.data.MediaClassifier.resolveLocalMediaMimeType(fallbackName.ifBlank { uri })
                            val match = media.firstOrNull { it.uri == uri || (it.name.isNotBlank() && uri.contains(it.name)) }
                            val targetItem = if (match != null) {
                                if (classified.isAudio && (match.mimeType.isNullOrBlank() || match.mimeType.startsWith("image/"))) {
                                    match.copy(mimeType = classified.mimeType)
                                } else {
                                    match
                                }
                            } else {
                                MediaItem(
                                    id = uri.hashCode().toLong(),
                                    uri = uri,
                                    name = fallbackName,
                                    mimeType = classified.mimeType,
                                    dateAddedSeconds = System.currentTimeMillis() / 1000,
                                    dateTakenMillis = System.currentTimeMillis(),
                                    sizeBytes = if (file.exists()) file.length() else 0L,
                                    width = 0,
                                    height = 0,
                                    relativePath = "",
                                    isVideo = classified.isVideo
                                )
                            }
                            openMediaInViewer(targetItem, listOf(targetItem))
                        },
                        onOpenMediaWithList = { uri, allUris ->
                            val safeUris = if (allUris.contains(uri)) allUris else (listOf(uri) + allUris)
                            val viewerItems = safeUris.map { u ->
                                val file = java.io.File(u.removePrefix("file://"))
                                val fallbackName = if (file.exists()) file.name else u.substringAfterLast('/').ifBlank { "media_item" }
                                val classified = com.amresalehin.emregallery.data.MediaClassifier.resolveLocalMediaMimeType(fallbackName.ifBlank { u })
                                val match = media.firstOrNull { it.uri == u || (it.name.isNotBlank() && u.contains(it.name)) }
                                if (match != null) {
                                    if (classified.isAudio && (match.mimeType.isNullOrBlank() || match.mimeType.startsWith("image/"))) {
                                        match.copy(mimeType = classified.mimeType)
                                    } else {
                                        match
                                    }
                                } else {
                                    MediaItem(
                                        id = u.hashCode().toLong(),
                                        uri = u,
                                        name = fallbackName,
                                        mimeType = classified.mimeType,
                                        dateAddedSeconds = System.currentTimeMillis() / 1000,
                                        dateTakenMillis = System.currentTimeMillis(),
                                        sizeBytes = if (file.exists()) file.length() else 0L,
                                        width = 0,
                                        height = 0,
                                        relativePath = "",
                                        isVideo = classified.isVideo
                                    )
                                }
                            }
                            val target = viewerItems.firstOrNull { it.uri == uri } ?: viewerItems.first()
                            openMediaInViewer(target, viewerItems)
                        }
                    )
                    Destination.SEARCH -> {
                        val searchScopeMedia = remember(media, hidden, inBin, archive) {
                            media.filter {
                                it.uri !in hidden && !it.isTrashed && it.uri !in inBin && it.uri !in archive
                            }
                        }
                        SearchScreen(
                            query = searchQuery,
                            stage = searchStage,
                            allMedia = searchScopeMedia,
                            recentSearches = recentSearches,
                            customAlbums = customAlbums,
                            customTagsMap = customTagsMap,
                            customPeopleMap = customPeopleMap,
                            favourites = favourites,
                            themePref = themePref,
                            onQueryChange = { newQuery ->
                                searchQuery = newQuery
                                searchStage = if (newQuery.isBlank()) SearchStage.DISCOVERY else SearchStage.TYPING
                            },
                            onSubmitSearch = { submitted ->
                                val clean = submitted.trim()
                                searchQuery = clean
                                submittedQuery = clean
                                searchStage = SearchStage.RESULTS
                                if (clean.isNotBlank()) {
                                    scope.launch { prefs.addRecentSearch(clean) }
                                }
                            },
                            onClearRecent = { scope.launch { prefs.clearRecentSearches() } },
                            onBack = {
                                searchQuery = ""
                                searchStage = SearchStage.DISCOVERY
                                performBackNavigation()
                            },
                            onOpenMedia = { item, currentList ->
                                openMediaInViewer(item, currentList)
                            },
                            onOpenSuggestion = { suggestion ->
                                when (suggestion.type) {
                                    SuggestionType.PERSON -> {
                                        val personId = suggestion.targetFilter?.selectedPersonId
                                        if (!personId.isNullOrBlank() && !personId.startsWith("name:")) {
                                            activePersonId = personId
                                            searchQuery = ""
                                            searchStage = SearchStage.DISCOVERY
                                            navigateTo(Destination.PEOPLE)
                                        }
                                    }
                                    SuggestionType.ALBUM -> {
                                        val albumId = suggestion.targetFilter?.selectedAlbumId
                                        if (!albumId.isNullOrBlank()) {
                                            albumFilterReturnDestination = Destination.SEARCH
                                            albumFilter = albumId
                                            searchQuery = ""
                                            searchStage = SearchStage.DISCOVERY
                                            navigateTo(Destination.HOME)
                                        }
                                    }
                                    else -> Unit
                                }
                            },
                            initialFilter = pendingSearchFilter,
                            onConsumeInitialFilter = { pendingSearchFilter = null }
                        )
                    }
                    Destination.VIEWER -> {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }

        // Top-layer Media Viewer with continuous thumbnail-to-fullscreen & return shared animation
        val activeViewerMedia = selectedMedia
        if (destination == Destination.VIEWER && activeViewerMedia != null) {
            val currentItem = activeViewerMedia
            val isItemBinned = currentItem.isTrashed || currentItem.uri in inBin
            val itemsForViewer = remember(viewerMediaList, currentItem) {
                if (viewerMediaList.any { it.uri == currentItem.uri }) viewerMediaList else listOf(currentItem)
            }
            ViewerScreen(
                item = currentItem,
                items = itemsForViewer,
                storyTitle = viewerStoryTitle,
                storyGroups = viewerStories,
                initialStoryIndex = currentStoryIndex,
                defaultOcrLanguages = ocrLanguagesPref,
                defaultOcrModel = ocrModelPref,
                defaultMlKitLanguages = mlKitLanguagesPref,
                favourite = currentItem.uri in favourites,
                isHidden = currentItem.uri in hidden,
                isBin = isItemBinned,
                isArchived = currentItem.uri in archive,
                showControls = showViewerControls,
                showDetails = showDetails,
                customAlbums = customAlbums,
                customTags = customTagsMap[currentItem.uri] ?: emptySet(),
                customPeople = customPeopleMap[currentItem.uri] ?: emptySet(),
                onAddTag = { tag -> scope.launch { prefs.addTagForMedia(currentItem.uri, tag) } },
                onAddTags = { tags -> scope.launch { prefs.addTagsForMedia(currentItem.uri, tags) } },
                onAddToNamedAlbum = { albumNames ->
                    scope.launch {
                        val cleanNames = albumNames.map { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinctBy { it.lowercase(java.util.Locale.ROOT) }
                        prefs.ensureAlbumsAndAddMedia(cleanNames, currentItem.uri)
                        if (cleanNames.isNotEmpty()) {
                            val message = if (cleanNames.size == 1) {
                                "Added to ${cleanNames.first()}"
                            } else {
                                "Added to ${cleanNames.size} collections"
                            }
                            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
                        }
                    }
                },
                onOpenAiSettings = {
                    showDetails = false
                    navigateTo(Destination.AI_SETTINGS, rememberPrevious = true, newSelectedMedia = null)
                },
                onAddPerson = { person -> scope.launch { prefs.addPersonForMedia(currentItem.uri, person) } },
                onToggleControls = { showViewerControls = !showViewerControls },
                onToggleDetails = { showDetails = !showDetails; showViewerControls = true },
                onBack = { performBackNavigation() },
                onFavourite = { scope.launch { prefs.toggleFavourite(currentItem.uri) } },
                onToggleHide = {
                    val isAlreadyLocked = currentItem.relativePath == "Locked" || currentItem.uri in lockedVaultItems.map { it.uri }
                    if (!isAlreadyLocked && lockedFolderPinHash.isNullOrBlank()) {
                        showLockedFolderSetupPrompt = true
                    } else {
                        scope.launch {
                            if (!isAlreadyLocked) {
                                val lockedList = withContext(Dispatchers.IO) { lockedVaultManager.lockItems(listOf(currentItem)) }
                                lockedVaultItems = withContext(Dispatchers.IO) { lockedVaultManager.getVaultItems() }
                                prefs.setHidden(currentItem.uri, true)
                                refreshMedia()
                                val res = snackbarHostState.showSnackbar(
                                    message = "Moved to Locked folder",
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Short
                                )
                                if (res == SnackbarResult.ActionPerformed) {
                                    withContext(Dispatchers.IO) { lockedVaultManager.unlockItems(lockedList) }
                                    lockedVaultItems = withContext(Dispatchers.IO) { lockedVaultManager.getVaultItems() }
                                    prefs.setHidden(currentItem.uri, false)
                                    refreshMedia()
                                }
                            } else {
                                withContext(Dispatchers.IO) { lockedVaultManager.unlockItems(listOf(currentItem)) }
                                lockedVaultItems = withContext(Dispatchers.IO) { lockedVaultManager.getVaultItems() }
                                prefs.setHidden(currentItem.uri, false)
                                refreshMedia()
                                snackbarHostState.showSnackbar("Restored to gallery", duration = SnackbarDuration.Short)
                            }
                        }
                        performBackNavigation()
                    }
                },
                onToggleBin = {
                    if (isItemBinned) {
                        restoreFromDeviceTrash(listOf(currentItem))
                    } else {
                        moveToDeviceTrash(listOf(currentItem))
                    }
                    performBackNavigation()
                },
                onDeletePermanently = if (isItemBinned) {
                    {
                        permanentlyDelete(listOf(currentItem))
                        performBackNavigation()
                    }
                } else null,
                onToggleArchive = {
                    val willArchive = currentItem.uri !in archive
                    scope.launch {
                        prefs.setArchived(currentItem.uri, willArchive)
                        val msg = if (willArchive) "Archived" else "Unarchived"
                        val res = snackbarHostState.showSnackbar(
                            message = msg,
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Short
                        )
                        if (res == SnackbarResult.ActionPerformed) {
                            prefs.setArchived(currentItem.uri, !willArchive)
                        }
                    }
                    performBackNavigation()
                },
                onAddTo = { mediaForAddToAlbum = currentItem; showAddToAlbumSheet = true },
                onOpenNeighbor = { selectedMedia = it; showDetails = false },
                onOpenMap = {
                    showDetails = false
                    navigateTo(Destination.MAP, rememberPrevious = true, newMapFocusItem = currentItem)
                },
                onOpenPerson = { personId, engineId ->
                    showDetails = false
                    if (engineId.isNotBlank()) {
                        FaceRecognitionSettings.setActiveEngine(context, engineId)
                        FaceEngineRegistry.setActiveEngine(engineId)
                    }
                    activePersonId = personId
                    navigateTo(Destination.PEOPLE)
                },
                transitionProgress = viewerTransitionProgress
            )
        }

        // Wider top and bottom edge blur across app screens, smoothly fading with return progress
        val showEdgeBlurs by remember(destination) {
            derivedStateOf { (destination != Destination.VIEWER) || viewerTransitionProgress.value < 0.99f }
        }
        if (showEdgeBlurs) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.045f)
                    .graphicsLayer {
                        val cp = if (destination == Destination.VIEWER) viewerTransitionProgress.value else 0f
                        alpha = (1f - cp).coerceIn(0f, 1f)
                    }
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.045f)
                    .graphicsLayer {
                        val cp = if (destination == Destination.VIEWER) viewerTransitionProgress.value else 0f
                        alpha = (1f - cp).coerceIn(0f, 1f)
                    }
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
                            )
                        )
                    )
            )
        }

        // Floating search bar across Home and Albums - coordinated entrance downward from above screen edge
        val showSearchBar = returnDestination in listOf(Destination.HOME, Destination.ALBUMS) && destination != Destination.VIEWER
        if (showSearchBar) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .clipToBounds()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .graphicsLayer {
                            val cp = if (destination == Destination.VIEWER) viewerTransitionProgress.value else 0f
                            translationY = -(size.height + 60.dp.toPx()) * cp
                            alpha = (1f - cp * 1.15f).coerceIn(0f, 1f)
                        }
                ) {
                    FloatingSearchPill(
                        label = when (returnDestination) {
                            Destination.ALBUMS -> "Search folders, albums…"
                            else -> "Search photos, people, places…"
                        },
                        showMoreOptions = returnDestination == Destination.HOME,
                        onMenuClick = { showSidePanel = true },
                        onOptionsClick = { showHomeViewOptionsSheet = true },
                        onClick = {
                            navigateTo(Destination.SEARCH)
                            searchStage = SearchStage.DISCOVERY
                            searchQuery = ""
                        }
                    )
                }
            }
        }

        // Floating Bottom Navigation Bar across Home, Albums, Settings - coordinated entrance upward from below screen edge
        val supportsMainNavigation = returnDestination in listOf(
            Destination.HOME, Destination.ALBUMS, Destination.SETTINGS, Destination.MEDIA_INBOX
        )
        val shouldShowMainNavigation = supportsMainNavigation &&
            (returnDestination != Destination.MEDIA_INBOX || showInboxGlobalNavigation)
        val showBottomBarContainer = ((shouldShowMainNavigation && albumFilter == null) || homeSelectedUris.isNotEmpty()) && destination != Destination.VIEWER
        if (showBottomBarContainer) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clipToBounds()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                        .graphicsLayer {
                            val cp = if (destination == Destination.VIEWER) viewerTransitionProgress.value else 0f
                            translationY = (size.height + 60.dp.toPx()) * cp
                            alpha = (1f - cp * 1.15f).coerceIn(0f, 1f)
                        }
                ) {
                    if (albumFilter == null && shouldShowMainNavigation) {
                        FloatingNavigationBar(
                            current = returnDestination,
                            onSelect = { dest ->
                                if (dest == destination) {
                                    if (dest == Destination.HOME) {
                                        scope.launch {
                                            homeListState.scrollToItem(0)
                                        }
                                    }
                                } else {
                                    backStack.clear()
                                    albumFilter = null
                                    homeSelectedUris = emptySet()
                                    if (dest == Destination.MEDIA_INBOX) {
                                        inboxInitialViewMode = DownloaderViewMode.SEARCH_PAGE
                                        inboxInitialStack = null
                                    }
                                    navigateTo(dest, rememberPrevious = false, newSelectedMedia = null, newMapFocusItem = null)
                                }
                            }
                        )
                    }

                    // Floating Selection Toolbar overlying the bottom navigation bar:
                    AnimatedVisibility(
                        visible = homeSelectedUris.isNotEmpty(),
                        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
                        exit = fadeOut(tween(160)) + slideOutVertically(tween(180)) { it / 2 }
                    ) {
                        HomeSelectionToolbar(
                            selectedUris = homeSelectedUris,
                            totalCount = visibleMedia.size,
                            onClose = { homeSelectedUris = emptySet() },
                            onSelectAllToggle = {
                                homeSelectedUris = if (homeSelectedUris.size == visibleMedia.size) {
                                    emptySet()
                                } else {
                                    visibleMedia.map { it.uri }.toSet()
                                }
                            },
                            onShare = {
                                shareMultipleMediaItems(context, visibleMedia.filter { it.uri in homeSelectedUris })
                            },
                            onMoveToLocked = {
                                val targets = homeSelectedUris
                                homeSelectedUris = emptySet()
                                moveItemsToLocked(targets)
                            },
                            onMoveToBin = {
                                val targets = homeSelectedUris
                                homeSelectedUris = emptySet()
                                moveToDeviceTrash(visibleMedia.filter { it.uri in targets })
                            },
                            onAddToAlbum = {
                                val targets = homeSelectedUris
                                homeSelectedUris = emptySet()
                                val first = visibleMedia.firstOrNull { it.uri in targets }
                                if (first != null) {
                                    mediaForAddToAlbum = first
                                    showAddToAlbumSheet = true
                                }
                            }
                        )
                    }
                }
            }
        }

        SideNavigationDrawer(
            visible = showSidePanel,
            destination = destination,
            albumFilter = albumFilter,
            onNavigate = { dest ->
                if (dest in listOf(Destination.HOME, Destination.ALBUMS, Destination.SETTINGS)) {
                    backStack.clear()
                    albumFilter = null
                    navigateTo(dest, rememberPrevious = false, newSelectedMedia = null, newMapFocusItem = null)
                } else {
                    albumFilter = null
                    navigateTo(dest, rememberPrevious = true, newMapFocusItem = if (dest == Destination.MAP) null else mapFocusItem)
                }
                showSidePanel = false
            },
            onOpenBrowser = {
                hasOpenedAlbumBrowser = true
                isAlbumBrowserVisible = true
                showSidePanel = false
            },
            onClose = { showSidePanel = false }
        )

        if (hasOpenedAlbumBrowser) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (isAlbumBrowserVisible) 100f else -1f)
                    .graphicsLayer {
                        alpha = if (isAlbumBrowserVisible) 1f else 0f
                        translationX = if (isAlbumBrowserVisible) 0f else size.width * 2f
                    }
            ) {
                FullScreenBrowserView(
                    initialUrl = albumBrowserInitialUrl,
                    navigationRequestId = albumBrowserNavigationRequestId,
                    isActive = isAlbumBrowserVisible,
                    onClose = { isAlbumBrowserVisible = false },
                    onDownloadComplete = { count ->
                        scope.launch {
                            snackbarHostState.showSnackbar("Downloaded $count media items to Inbox")
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomContentPadding)
        )
    }
}

    if (showLockedFolderSetupPrompt) {
        AlertDialog(
            onDismissRequest = { showLockedFolderSetupPrompt = false },
            icon = { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = {
                Text(
                    "Locked folder not configured",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Create a 4-digit PIN first so you can safely access and protect your locked photos.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLockedFolderSetupPrompt = false
                        navigateTo(Destination.LOCKED_FOLDER)
                    }
                ) {
                    Text("Set up PIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLockedFolderSetupPrompt = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCreateAlbumDialog) {
        CreateAlbumDialog(
            presetParent = createAlbumPresetParent,
            existingAlbums = customAlbums,
            onDismiss = { showCreateAlbumDialog = false },
            onConfirm = { name, parentPath ->
                scope.launch {
                    val newAlbum = prefs.createCustomAlbum(name, parentPath)
                    mediaForAddToAlbum?.let { addedItem ->
                        prefs.addMediaToCustomAlbum(newAlbum.id, addedItem.uri)
                    }
                }
                showCreateAlbumDialog = false
            }
        )
    }

    val activeMediaForAlbum = mediaForAddToAlbum
    if (showAddToAlbumSheet && activeMediaForAlbum != null) {
        AddToAlbumBottomSheet(
            item = activeMediaForAlbum,
            customAlbums = customAlbums,
            onDismiss = { showAddToAlbumSheet = false },
            onToggleAlbum = { album ->
                scope.launch {
                    val mediaItem = mediaForAddToAlbum ?: activeMediaForAlbum
                    if (mediaItem.uri in album.mediaUris) {
                        prefs.removeMediaFromCustomAlbum(album.id, mediaItem.uri)
                        snackbarHostState.showSnackbar("Removed from ${album.name}", duration = SnackbarDuration.Short)
                    } else {
                        prefs.addMediaToCustomAlbum(album.id, mediaItem.uri)
                        snackbarHostState.showSnackbar("Moved to ${album.name}", duration = SnackbarDuration.Short)
                    }
                }
            },
            onCreateNewAlbum = {
                showAddToAlbumSheet = false
                createAlbumPresetParent = ""
                showCreateAlbumDialog = true
            }
        )
    }

    val activeAlbumForPicker = showAddPhotosPickerForAlbum
    if (activeAlbumForPicker != null) {
        AddPhotosToAlbumDialog(
            album = activeAlbumForPicker,
            allMedia = media.filter { it.uri !in hidden && it.uri !in inBin },
            onDismiss = { showAddPhotosPickerForAlbum = null },
            onAddSelected = { uris ->
                val target = showAddPhotosPickerForAlbum ?: activeAlbumForPicker
                scope.launch {
                    uris.forEach { uri -> prefs.addMediaToCustomAlbum(target.id, uri) }
                }
                showAddPhotosPickerForAlbum = null
            }
        )
    }

    if (showHomeViewOptionsSheet) {
        HomeViewOptionsBottomSheet(
            sortOrder = sortOrderPref,
            groupBy = groupByPref,
            gridColumns = gridColumnsPref,
            gridStyle = gridStylePref,
            onSortOrderChange = { scope.launch { prefs.setSortOrder(it) } },
            onGroupByChange = { scope.launch { prefs.setGroupBy(it) } },
            onGridColumnsChange = { scope.launch { prefs.setGridColumns(it) } },
            onGridStyleChange = { scope.launch { prefs.setGridStyle(it) } },
            onDismiss = { showHomeViewOptionsSheet = false }
        )
    }
}

private fun shareMultipleMediaItems(context: android.content.Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    runCatching {
        if (items.size == 1) {
            val item = items.first()
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = item.mimeType ?: com.amresalehin.emregallery.data.MediaClassifier.resolveLocalMediaMimeType(item.name.ifBlank { item.uri }).mimeType
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Share media"
                )
            )
        } else {
            val uris = ArrayList(items.map { Uri.parse(it.uri) })
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Share media"
                )
            )
        }
    }
}
