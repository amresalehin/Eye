package com.amresalehin.emregallery.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.amresalehin.emregallery.data.AdBlockEngine
import com.amresalehin.emregallery.data.BrowserHistoryManager
import com.amresalehin.emregallery.data.BrowserSearchEngine
import com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager
import com.amresalehin.emregallery.data.BrowserMediaExtractor
import com.amresalehin.emregallery.data.BrowserSite
import com.amresalehin.emregallery.data.DownloaderSession
import com.amresalehin.emregallery.data.ExtractionSource
import com.amresalehin.emregallery.data.MediaCaptureEngine
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.OkHttpDownloader
import com.amresalehin.emregallery.data.ScrapedMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private data class PendingBrowserMediaCandidate(
    val rawUrl: String,
    val baseUrl: String,
    val source: ExtractionSource
)

private data class BrowserRendererRecoverySnapshot(
    val url: String,
    val webViewState: Bundle?,
    val scrollX: Int,
    val scrollY: Int
)

private data class BrowserTabSession(
    val id: Int,
    val url: String,
    val title: String,
    val savedWebViewState: Bundle? = null,
    val scrollX: Int = 0,
    val scrollY: Int = 0
)

@Composable
private fun BrowserLoadingProgressBar(
    isLoading: Boolean,
    progressState: FloatState
) {
    if (isLoading) {
        LinearProgressIndicator(
            progress = { progressState.floatValue },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DirectBrowserView(
    initialUrl: String = "",
    navigationRequestId: Int = 0,
    onClose: (() -> Unit)? = null,
    onUrlChange: ((String) -> Unit)? = null,
    onPageTitleChanged: ((String) -> Unit)? = null,
    discoveredCount: Int = 0,
    onMediaExtracted: (ScrapedMediaItem) -> Unit,
    onBatchExtracted: (List<ScrapedMediaItem>, pageTitle: String?, domain: String?) -> Unit,
    onSwitchToMediaTab: () -> Unit,
    isActive: Boolean = true,
    onAddressBarFocusChanged: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    // Give WebView's initial about:blank frame the active theme color before first draw.
    val browserSurfaceColor = MaterialTheme.colorScheme.surface.toArgb()
    val downloaderPreferences = remember(context) { DownloaderPreferencesManager(context) }
    val defaultSearchEngine by downloaderPreferences.searchEngine.collectAsState(
        initial = DownloaderPreferencesManager.getSearchEngineSync(context)
    )
    val browserJavaScriptEnabled by downloaderPreferences.browserJavaScriptEnabled.collectAsState(initial = true)
    val browserAdBlockingEnabled by downloaderPreferences.browserAdBlockingEnabled.collectAsState(initial = true)
    val browserLoadImages by downloaderPreferences.browserLoadImages.collectAsState(initial = true)
    val browserThirdPartyCookies by downloaderPreferences.browserThirdPartyCookies.collectAsState(initial = false)
    val browserSaveHistory by downloaderPreferences.browserSaveHistory.collectAsState(initial = true)
    val browserDesktopSiteByDefault by downloaderPreferences.browserDesktopSiteByDefault.collectAsState(
        initial = DownloaderPreferencesManager.getBrowserDesktopSiteByDefaultSync(context)
    )
    val browserTextZoom by downloaderPreferences.browserTextZoom.collectAsState(initial = 100)
    val browserSafeBrowsingEnabled by downloaderPreferences.browserSafeBrowsingEnabled.collectAsState(initial = true)
    val browserBlockPopups by downloaderPreferences.browserBlockPopups.collectAsState(initial = true)
    val browserBlockMixedContent by downloaderPreferences.browserBlockMixedContent.collectAsState(initial = true)
    val browserAllowAutoplay by downloaderPreferences.browserAllowAutoplay.collectAsState(initial = true)
    val browserPinchZoomEnabled by downloaderPreferences.browserPinchZoomEnabled.collectAsState(initial = true)
    val browserHomepageUrl by downloaderPreferences.browserHomepageUrl.collectAsState(
        initial = DownloaderPreferencesManager.getBrowserHomepageUrlSync(context)
    )
    val browserShowShortcuts by downloaderPreferences.browserShowShortcuts.collectAsState(initial = true)
    val browserShowRecentlyVisited by downloaderPreferences.browserShowRecentlyVisited.collectAsState(initial = true)
    val browserDarkenWebsites by downloaderPreferences.browserDarkenWebsites.collectAsState(initial = false)
    val latestAdBlockingEnabled by rememberUpdatedState(browserAdBlockingEnabled)
    val latestSaveHistoryEnabled by rememberUpdatedState(browserSaveHistory)
    val coroutineScope = rememberCoroutineScope()

    val startUrl = remember(initialUrl, defaultSearchEngine, browserHomepageUrl) {
        val trimmed = initialUrl.trim()
        when {
            trimmed.isNotBlank() -> MediaClassifier.formatQueryOrUrl(trimmed, defaultSearchEngine)
            browserHomepageUrl.isNotBlank() -> browserHomepageUrl
            else -> "" // Internal browser home page
        }
    }

    var currentUrl by remember { mutableStateOf(startUrl) }
    var addressInput by remember { mutableStateOf(startUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var browserTabs by remember {
        mutableStateOf(listOf(BrowserTabSession(id = 1, url = startUrl, title = "")))
    }
    var activeBrowserTabId by remember { mutableIntStateOf(1) }
    var nextBrowserTabId by remember { mutableIntStateOf(2) }
    var showTabSwitcher by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val loadingProgressState = remember { mutableFloatStateOf(0f) }
    var browserMenuExpanded by remember { mutableStateOf(false) }
    var showBrowserSettings by remember { mutableStateOf(false) }
    var pageZoomPercent by remember { mutableIntStateOf(100) }
    var isDesktopMode by remember(browserDesktopSiteByDefault) {
        mutableStateOf(browserDesktopSiteByDefault)
    }
    var isAddressBarFocused by remember { mutableStateOf(false) }
    var browserToolbarFirstFrameReady by remember { mutableStateOf(false) }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var webViewGeneration by remember { mutableIntStateOf(0) }
    var rendererRecoveryMessage by remember { mutableStateOf<String?>(null) }
    var rendererCrashUrl by remember { mutableStateOf("") }
    var rendererCrashAttempts by remember { mutableIntStateOf(0) }
    val browserRecoverySnapshotRef = remember { AtomicReference<BrowserRendererRecoverySnapshot?>(null) }
    val destroyedWebViews = remember {
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<WebView, Boolean>())
    }

    fun releaseBrowserWebView(view: WebView) {
        if (!destroyedWebViews.add(view)) return
        runCatching { (view.parent as? android.view.ViewGroup)?.removeView(view) }
        runCatching { view.stopLoading() }
        runCatching { view.removeJavascriptInterface("EmreBridge") }
        runCatching { view.webViewClient = WebViewClient() }
        runCatching { view.webChromeClient = WebChromeClient() }
        runCatching { view.destroy() }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        browserToolbarFirstFrameReady = true
    }

    // Keep the retained browser's page state, but pause its own processing while it is hidden.
    // Do not use pauseTimers(): that API affects every WebView in the process, including extractors.
    LaunchedEffect(webViewInstance, isActive) {
        val activeView = webViewInstance ?: return@LaunchedEffect
        if (isActive) activeView.onResume() else activeView.onPause()
    }

    val clipboardManager = LocalClipboardManager.current
    val historyManager = remember { BrowserHistoryManager(context) }
    val savedSites by historyManager.savedSites.collectAsState(initial = emptyList())
    val currentSiteDomain = remember(currentUrl) {
        runCatching { URL(currentUrl).host.removePrefix("www.").lowercase() }.getOrDefault("")
    }
    val currentSiteIsSaved = savedSites.any { it.domain.equals(currentSiteDomain, ignoreCase = true) }

    val seenRawUrls = remember { ConcurrentHashMap.newKeySet<String>() }
    val pendingCandidateChannel = remember { Channel<PendingBrowserMediaCandidate>(capacity = 512) }
    val activeBaseUrlRef = remember { AtomicReference(startUrl) }
    val pageTitleRef = remember { AtomicReference("") }
    var lastNotifiedUrl by remember { mutableStateOf(startUrl) }
    var lastRecordedHistoryUrl by remember { mutableStateOf("") }
    var lastScannedDomUrl by remember { mutableStateOf("") }
    var pendingDomScanJob by remember { mutableStateOf<Job?>(null) }

    val latestOnBatchExtracted by rememberUpdatedState(onBatchExtracted)

    // Batch-process intercepted network & MutationObserver media off Chromium's IO/JS threads
    LaunchedEffect(Unit) {
        val batch = ArrayList<PendingBrowserMediaCandidate>(24)
        while (true) {
            val first = pendingCandidateChannel.receive()
            batch.clear()
            batch.add(first)

            val deadlineMs = System.currentTimeMillis() + 180L
            while (batch.size < 24) {
                val next = pendingCandidateChannel.tryReceive().getOrNull()
                if (next != null) {
                    batch.add(next)
                    continue
                }
                val remainingMs = deadlineMs - System.currentTimeMillis()
                if (remainingMs <= 0L) break
                val awaited = withTimeoutOrNull(remainingMs) {
                    pendingCandidateChannel.receive()
                } ?: break
                batch.add(awaited)
            }

            // Do not let delayed network callbacks from the previous page pollute the new page.
            val activePageUrl = activeBaseUrlRef.get()
            val activeBatch = batch.filter { it.baseUrl == activePageUrl }
            if (activeBatch.isEmpty()) continue

            val builtItems = withContext(Dispatchers.Default) {
                val cookieCache = HashMap<String, String>(4)
                val built = ArrayList<ScrapedMediaItem>(activeBatch.size)
                for ((idx, cand) in activeBatch.withIndex()) {
                    val cookieHeader = cookieCache.getOrPut(cand.baseUrl) {
                        OkHttpDownloader.sharedCookieJar.getCookieString(cand.baseUrl)
                    }
                    val item = MediaCaptureEngine.buildMediaItem(
                        rawUrl = cand.rawUrl,
                        source = cand.source,
                        baseUrl = cand.baseUrl,
                        indexHint = idx,
                        precomputedCookieHeader = cookieHeader
                    )
                    if (item != null) {
                        built.add(item)
                    }
                }
                built
            }

            if (builtItems.isNotEmpty() && activeBaseUrlRef.get() == activePageUrl) {
                val title = pageTitleRef.get().ifBlank { null }
                val domain = runCatching { URL(activePageUrl).host }.getOrDefault("")
                DownloaderSession.addDiscoveredItems(builtItems, title)
                latestOnBatchExtracted(builtItems, title, domain)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingDomScanJob?.cancel()
            pendingCandidateChannel.close()
            webViewInstance?.let { wv ->
                releaseBrowserWebView(wv)
                webViewInstance = null
            }
        }
    }

    // Intercept back button when browser can navigate back
    BackHandler(enabled = isActive && (showBrowserSettings || (canGoBack && !isAddressBarFocused))) {
        if (showBrowserSettings) {
            showBrowserSettings = false
        } else {
            webViewInstance?.goBack()
        }
    }

    fun updateActiveBrowserTab(
        url: String = currentUrl,
        title: String = pageTitle,
        clearSavedState: Boolean = false
    ) {
        browserTabs = browserTabs.map { tab ->
            if (tab.id == activeBrowserTabId) {
                tab.copy(
                    url = url,
                    title = title,
                    savedWebViewState = if (clearSavedState) null else tab.savedWebViewState,
                    scrollX = if (clearSavedState) 0 else tab.scrollX,
                    scrollY = if (clearSavedState) 0 else tab.scrollY
                )
            } else tab
        }
    }

    fun saveActiveBrowserTabState() {
        val activeTab = browserTabs.firstOrNull { it.id == activeBrowserTabId } ?: return
        val activeView = webViewInstance
        val state = Bundle()
        val stateSaved = activeView != null && runCatching {
            activeView.saveState(state)
            !state.isEmpty
        }.getOrDefault(false)
        val savedUrl = activeView?.url?.takeIf { it.isNotBlank() && it != "about:blank" } ?: currentUrl
        val savedTitle = activeView?.title?.takeIf { it.isNotBlank() } ?: pageTitle
        browserTabs = browserTabs.map { tab ->
            if (tab.id == activeBrowserTabId) {
                tab.copy(
                    url = savedUrl,
                    title = savedTitle,
                    savedWebViewState = if (stateSaved) Bundle(state) else activeTab.savedWebViewState,
                    scrollX = activeView?.scrollX ?: tab.scrollX,
                    scrollY = activeView?.scrollY ?: tab.scrollY
                )
            } else tab
        }
    }

    fun activateBrowserTab(tabId: Int) {
        if (tabId == activeBrowserTabId) {
            showTabSwitcher = false
            return
        }
        val requestedTab = browserTabs.firstOrNull { it.id == tabId } ?: return
        saveActiveBrowserTabState()
        val targetTab = browserTabs.firstOrNull { it.id == tabId } ?: requestedTab

        pendingDomScanJob?.cancel()
        webViewInstance?.let { oldView ->
            runCatching { oldView.onPause() }
            releaseBrowserWebView(oldView)
        }
        webViewInstance = null

        activeBrowserTabId = targetTab.id
        currentUrl = targetTab.url
        addressInput = targetTab.url
        pageTitle = targetTab.title
        pageTitleRef.set(targetTab.title)
        activeBaseUrlRef.set(targetTab.url)
        lastNotifiedUrl = targetTab.url
        lastRecordedHistoryUrl = targetTab.url
        lastScannedDomUrl = ""
        seenRawUrls.clear()
        canGoBack = false
        canGoForward = false
        isLoading = targetTab.url.isNotBlank()
        loadingProgressState.floatValue = 0f
        rendererRecoveryMessage = null
        rendererCrashUrl = ""
        rendererCrashAttempts = 0
        browserRecoverySnapshotRef.set(
            if (targetTab.url.isNotBlank()) {
                BrowserRendererRecoverySnapshot(
                    url = targetTab.url,
                    webViewState = targetTab.savedWebViewState?.let { Bundle(it) },
                    scrollX = targetTab.scrollX,
                    scrollY = targetTab.scrollY
                )
            } else null
        )
        showTabSwitcher = false
        webViewGeneration += 1
        onUrlChange?.invoke(targetTab.url)
        onPageTitleChanged?.invoke(targetTab.title)
        focusManager.clearFocus()
    }

    fun createBrowserTab() {
        saveActiveBrowserTabState()
        val newTabId = nextBrowserTabId++
        val homeUrl = browserHomepageUrl.trim().takeIf { it.isNotBlank() }?.let {
            MediaClassifier.formatQueryOrUrl(it, defaultSearchEngine)
        }.orEmpty()
        browserTabs = browserTabs + BrowserTabSession(id = newTabId, url = homeUrl, title = "")
        activateBrowserTab(newTabId)
    }

    fun closeBrowserTab(tabId: Int) {
        val closingIndex = browserTabs.indexOfFirst { it.id == tabId }
        if (closingIndex < 0) return

        if (browserTabs.size == 1) {
            val homeUrl = browserHomepageUrl.trim().takeIf { it.isNotBlank() }?.let {
                MediaClassifier.formatQueryOrUrl(it, defaultSearchEngine)
            }.orEmpty()
            webViewInstance?.let { oldView ->
                runCatching { oldView.onPause() }
                releaseBrowserWebView(oldView)
            }
            webViewInstance = null
            pendingDomScanJob?.cancel()
            browserTabs = listOf(BrowserTabSession(id = tabId, url = homeUrl, title = ""))
            activeBrowserTabId = tabId
            currentUrl = homeUrl
            addressInput = homeUrl
            pageTitle = ""
            pageTitleRef.set("")
            activeBaseUrlRef.set(homeUrl)
            lastNotifiedUrl = homeUrl
            lastRecordedHistoryUrl = homeUrl
            lastScannedDomUrl = ""
            seenRawUrls.clear()
            canGoBack = false
            canGoForward = false
            isLoading = homeUrl.isNotBlank()
            loadingProgressState.floatValue = 0f
            browserRecoverySnapshotRef.set(null)
            rendererRecoveryMessage = null
            rendererCrashUrl = ""
            rendererCrashAttempts = 0
            webViewGeneration += 1
            showTabSwitcher = false
            onUrlChange?.invoke(homeUrl)
            onPageTitleChanged?.invoke("")
            return
        }

        val wasActive = tabId == activeBrowserTabId
        val nextTab = if (wasActive) {
            browserTabs.getOrNull(closingIndex + 1) ?: browserTabs.getOrNull(closingIndex - 1)
        } else null
        browserTabs = browserTabs.filterNot { it.id == tabId }
        if (wasActive && nextTab != null) {
            activateBrowserTab(nextTab.id)
        }
    }

    fun notifyUrlIfChanged(newUrl: String) {
        activeBaseUrlRef.set(newUrl)
        if (newUrl != rendererCrashUrl) {
            rendererCrashUrl = ""
            rendererCrashAttempts = 0
            rendererRecoveryMessage = null
        }
        if (newUrl != lastNotifiedUrl) {
            lastNotifiedUrl = newUrl
            pageTitle = ""
            pageTitleRef.set("")
            updateActiveBrowserTab(url = newUrl, title = "", clearSavedState = true)
            onPageTitleChanged?.invoke("")
            onUrlChange?.invoke(newUrl)
        }
    }

    fun navigateToUrl(target: String) {
        val trimmed = target.trim()
        if (trimmed.isBlank()) return
        val formatted = MediaClassifier.formatQueryOrUrl(trimmed, defaultSearchEngine)
        if (formatted != currentUrl) {
            seenRawUrls.clear()
            lastScannedDomUrl = ""
        }
        addressInput = formatted
        currentUrl = formatted
        notifyUrlIfChanged(formatted)
        if (browserSaveHistory && formatted != lastRecordedHistoryUrl) {
            lastRecordedHistoryUrl = formatted
            coroutineScope.launch {
                historyManager.recordVisit(formatted)
            }
        }
        val activeWebView = webViewInstance
        if (activeWebView != null) {
            activeWebView.loadUrl(formatted)
        } else {
            // A renderer can die while the browser surface is still open. Recreate
            // the WebView when the user navigates instead of leaving the address bar inert.
            rendererCrashAttempts = 0
            rendererCrashUrl = ""
            rendererRecoveryMessage = "Opening page…"
            isLoading = true
            webViewGeneration += 1
        }
        focusManager.clearFocus()
    }

    LaunchedEffect(initialUrl, navigationRequestId, defaultSearchEngine) {
        val requested = initialUrl.trim()
        if (requested.isNotBlank() &&
            MediaClassifier.formatQueryOrUrl(requested, defaultSearchEngine) != currentUrl
        ) {
            navigateToUrl(requested)
        }
    }

    var lastAppliedBrowserSettings by remember { mutableStateOf<String?>(null) }
    val browserRuntimeSettingsKey = listOf(
        browserJavaScriptEnabled,
        browserAdBlockingEnabled,
        browserLoadImages,
        browserThirdPartyCookies,
        browserSafeBrowsingEnabled,
        browserBlockPopups,
        browserBlockMixedContent,
        browserAllowAutoplay,
        browserPinchZoomEnabled,
        browserDarkenWebsites,
        isDesktopMode
    ).joinToString("|")

    LaunchedEffect(webViewInstance, browserRuntimeSettingsKey) {
        val activeWebView = webViewInstance ?: return@LaunchedEffect
        val previousSettings = lastAppliedBrowserSettings
        lastAppliedBrowserSettings = browserRuntimeSettingsKey
        if (previousSettings != null &&
            previousSettings != browserRuntimeSettingsKey &&
            currentUrl.isNotBlank()
        ) {
            activeWebView.reload()
        }
    }

    fun captureBrowserRecoverySnapshot(view: WebView?, saveNavigationHistory: Boolean = true) {
        val activeView = view ?: return
        if (destroyedWebViews.contains(activeView)) return
        val url = activeView.url?.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        } ?: activeBaseUrlRef.get().takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        } ?: return
        val previousSnapshot = browserRecoverySnapshotRef.get()?.takeIf { it.url == url }
        val state = Bundle()
        val stateSaved = saveNavigationHistory && runCatching {
            activeView.saveState(state)
            !state.isEmpty
        }.getOrDefault(false)
        browserRecoverySnapshotRef.set(
            BrowserRendererRecoverySnapshot(
                url = url,
                webViewState = if (stateSaved) Bundle(state) else previousSnapshot?.webViewState,
                scrollX = activeView.scrollX,
                scrollY = activeView.scrollY
            )
        )
    }

    fun scanCurrentPageDOM(wv: WebView?) {
        if (wv == null) return
        val current = wv.url ?: return
        if (current.isBlank() || current == "about:blank" || currentUrl.isBlank()) return

        wv.evaluateJavascript(MediaCaptureEngine.getJsDomScanScript()) { jsonRaw ->
            if (jsonRaw.isNullOrBlank() || jsonRaw == "null") return@evaluateJavascript
            // A navigation may finish while JavaScript is still serializing the previous page.
            if (wv.url != current || activeBaseUrlRef.get() != current) return@evaluateJavascript
            coroutineScope.launch {
                if (wv.url != current || activeBaseUrlRef.get() != current) return@launch
                val discovered = withContext(Dispatchers.Default) {
                    MediaCaptureEngine.parseJsDomScanResult(jsonRaw, current)
                }
                if (discovered.isNotEmpty() && wv.url == current && activeBaseUrlRef.get() == current) {
                    val domain = runCatching { URL(current).host }.getOrDefault("")
                    val title = pageTitleRef.get()
                    DownloaderSession.addDiscoveredItems(discovered, title)
                    latestOnBatchExtracted(discovered, title, domain)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag("direct_browser_view")
    ) {
            // Full-width browser search pill, matching the app's standard top-bar treatment.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .zIndex(1f)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("browser_search_bar")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onClose != null) {
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("browser_close_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = "Close browser",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { webViewInstance?.goBack() },
                                enabled = canGoBack,
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("browser_back_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = if (canGoBack) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                )
                            }
                        }

                        // Show the site/security indicator only after navigating; keep the home field clean.
                        if (currentUrl.isNotBlank()) {
                            val isSecure = currentUrl.startsWith("https://", ignoreCase = true)
                            Icon(
                                imageVector = if (isSecure) Icons.Default.Lock else Icons.Default.Language,
                                contentDescription = null,
                                tint = if (isSecure) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(7.dp))
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (addressInput.isEmpty()) {
                                Text(
                                    text = "Search or type URL",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = addressInput,
                                onValueChange = { addressInput = it },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Go
                                ),
                                keyboardActions = KeyboardActions(
                                    onGo = { navigateToUrl(addressInput) }
                                ),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { focusState ->
                                        isAddressBarFocused = focusState.isFocused
                                        onAddressBarFocusChanged?.invoke(focusState.isFocused)
                                    }
                                    .testTag("browser_address_input")
                            )
                        }

                        if (isAddressBarFocused && addressInput.isNotEmpty()) {
                            IconButton(
                                onClick = { addressInput = "" },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("browser_address_clear_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear address",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }

                        if (isAddressBarFocused && addressInput.isNotBlank()) {
                            IconButton(
                                onClick = { navigateToUrl(addressInput) },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("browser_go_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Go",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                    // Browser tabs: open the tab overview and show the number of open pages.
                    BadgedBox(
                        badge = {
                            Badge { Text(browserTabs.size.toString()) }
                        }
                    ) {
                        IconButton(
                            onClick = { showTabSwitcher = true },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("browser_tabs_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tab,
                                contentDescription = "Open tabs",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Three-dot Overflow Menu in Browser Top Bar Right Corner
                    Box {
                        IconButton(
                            onClick = { browserMenuExpanded = true },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("browser_more_menu_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More browser options",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = browserMenuExpanded,
                            onDismissRequest = { browserMenuExpanded = false },
                            modifier = Modifier.testTag("browser_dropdown_menu")
                        ) {
                            DropdownMenuItem(
                                text = { Text("Back") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                enabled = canGoBack,
                                onClick = {
                                    browserMenuExpanded = false
                                    webViewInstance?.goBack()
                                },
                                modifier = Modifier.testTag("browser_menu_back")
                            )

                            DropdownMenuItem(
                                text = { Text("Forward") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                enabled = canGoForward,
                                onClick = {
                                    browserMenuExpanded = false
                                    webViewInstance?.goForward()
                                },
                                modifier = Modifier.testTag("browser_menu_forward")
                            )

                            DropdownMenuItem(
                                text = { Text(if (isLoading) "Stop loading" else "Reload") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                enabled = currentUrl.isNotBlank() || isLoading,
                                onClick = {
                                    browserMenuExpanded = false
                                    if (isLoading) webViewInstance?.stopLoading()
                                    else webViewInstance?.reload()
                                },
                                modifier = Modifier.testTag("browser_menu_reload")
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .testTag("browser_menu_zoom_controls"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Zoom",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                IconButton(
                                    onClick = {
                                        val view = webViewInstance ?: return@IconButton
                                        val currentScale = view.scale.takeIf { it > 0f } ?: 1f
                                        val targetScale = (currentScale * 0.9f).coerceAtLeast(0.5f)
                                        if (targetScale < currentScale) {
                                            view.zoomBy(targetScale / currentScale)
                                            pageZoomPercent = Math.round(targetScale * 100f)
                                        }
                                    },
                                    enabled = currentUrl.isNotBlank() && webViewInstance != null && pageZoomPercent > 50,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("browser_menu_zoom_out")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Remove,
                                        contentDescription = "Zoom out",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Text(
                                    text = pageZoomPercent.toString() + "%",
                                    modifier = Modifier
                                        .widthIn(min = 48.dp)
                                        .clickable(enabled = currentUrl.isNotBlank() && pageZoomPercent != 100) {
                                            val view = webViewInstance
                                            if (view != null) {
                                                val currentScale = view.scale.takeIf { it > 0f } ?: 1f
                                                view.zoomBy(1f / currentScale)
                                                pageZoomPercent = 100
                                            }
                                        }
                                        .testTag("browser_menu_zoom_reset")
                                        .padding(horizontal = 4.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                                IconButton(
                                    onClick = {
                                        val view = webViewInstance ?: return@IconButton
                                        val currentScale = view.scale.takeIf { it > 0f } ?: 1f
                                        val targetScale = (currentScale * 1.1f).coerceAtMost(3f)
                                        if (targetScale > currentScale) {
                                            view.zoomBy(targetScale / currentScale)
                                            pageZoomPercent = Math.round(targetScale * 100f)
                                        }
                                    },
                                    enabled = currentUrl.isNotBlank() && webViewInstance != null && pageZoomPercent < 300,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("browser_menu_zoom_in")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Zoom in",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            HorizontalDivider()

                            DropdownMenuItem(
                                text = { Text("Scan page for media") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.TravelExplore,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                enabled = currentUrl.isNotBlank(),
                                onClick = {
                                    browserMenuExpanded = false
                                    scanCurrentPageDOM(webViewInstance)
                                },
                                modifier = Modifier.testTag("browser_menu_scan_page")
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (discoveredCount > 0) "Discovered media ($discoveredCount)" else "Discovered media"
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.PermMedia,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    browserMenuExpanded = false
                                    onSwitchToMediaTab()
                                },
                                modifier = Modifier.testTag("browser_menu_discovered_media")
                            )

                            DropdownMenuItem(
                                text = { Text(if (currentSiteIsSaved) "Remove saved shortcut" else "Save to shortcuts") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (currentSiteIsSaved) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                enabled = currentUrl.isNotBlank(),
                                onClick = {
                                    browserMenuExpanded = false
                                    val targetUrl = currentUrl
                                    val targetTitle = pageTitle
                                    coroutineScope.launch {
                                        if (currentSiteIsSaved) {
                                            historyManager.removeSavedSite(targetUrl)
                                        } else {
                                            historyManager.saveSite(targetUrl, targetTitle)
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("browser_menu_save_shortcut")
                            )

                            DropdownMenuItem(
                                text = { Text("Home") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Home,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    browserMenuExpanded = false
                                    if (browserHomepageUrl.isNotBlank()) {
                                        navigateToUrl(browserHomepageUrl)
                                    } else {
                                        currentUrl = ""
                                        addressInput = ""
                                        pageTitle = ""
                                        pageTitleRef.set("")
                                        lastRecordedHistoryUrl = ""
                                        pendingDomScanJob?.cancel()
                                        lastScannedDomUrl = ""
                                        seenRawUrls.clear()
                                        notifyUrlIfChanged("")
                                        webViewInstance?.loadUrl("about:blank")
                                    }
                                },
                                modifier = Modifier.testTag("browser_menu_home")
                            )

                            DropdownMenuItem(
                                text = { Text("Desktop site") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.DesktopWindows,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        imageVector = if (isDesktopMode) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    browserMenuExpanded = false
                                    isDesktopMode = !isDesktopMode
                                },
                                modifier = Modifier.testTag("browser_menu_desktop_site")
                            )

                            HorizontalDivider()

                            DropdownMenuItem(
                                text = { Text("Copy URL") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                enabled = currentUrl.isNotBlank(),
                                onClick = {
                                    browserMenuExpanded = false
                                    if (currentUrl.isNotBlank()) {
                                        clipboardManager.setText(AnnotatedString(currentUrl))
                                        Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.testTag("browser_menu_copy_url")
                            )

                            DropdownMenuItem(
                                text = { Text("Share URL") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                enabled = currentUrl.isNotBlank(),
                                onClick = {
                                    browserMenuExpanded = false
                                    if (currentUrl.isNotBlank()) {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, currentUrl)
                                        }
                                        runCatching {
                                            context.startActivity(
                                                Intent.createChooser(shareIntent, "Share URL").apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("browser_menu_share_url")
                            )

                            DropdownMenuItem(
                                text = { Text("Open in external browser") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                enabled = currentUrl.isNotBlank(),
                                onClick = {
                                    browserMenuExpanded = false
                                    if (currentUrl.isNotBlank()) {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        runCatching { context.startActivity(intent) }
                                    }
                                },
                                modifier = Modifier.testTag("browser_menu_open_external")
                            )

                            HorizontalDivider()

                            DropdownMenuItem(
                                text = { Text("Browser settings") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    browserMenuExpanded = false
                                    showBrowserSettings = true
                                },
                                modifier = Modifier.testTag("browser_menu_settings")
                            )
                        }
                    }
                    }
                }
            }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 68.dp
                )
        ) {
            if (browserToolbarFirstFrameReady) {
                // Loading Progress Bar (isolated so progress updates do not recompose the toolbar or WebView)
                BrowserLoadingProgressBar(
                    isLoading = isLoading,
                    progressState = loadingProgressState
                )

                // 2. Interactive WebView and Home Page Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                key(webViewGeneration) {
                AndroidView(
                    factory = { ctx ->
                        val recoveryUrl = activeBaseUrlRef.get().ifBlank { currentUrl }
                        val recoverySnapshot = browserRecoverySnapshotRef.get()?.takeIf { it.url == recoveryUrl }
                        WebView(ctx).apply {
                            setBackgroundColor(browserSurfaceColor)
                            webViewInstance = this
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            settings.apply {
                                javaScriptEnabled = browserJavaScriptEnabled
                                blockNetworkImage = !browserLoadImages
                                textZoom = browserTextZoom.coerceIn(50, 200)
                                domStorageEnabled = true
                                databaseEnabled = true
                                cacheMode = WebSettings.LOAD_DEFAULT
                                builtInZoomControls = browserPinchZoomEnabled
                                displayZoomControls = false
                                setSupportZoom(browserPinchZoomEnabled)
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                mediaPlaybackRequiresUserGesture = !browserAllowAutoplay
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    setAlgorithmicDarkeningAllowed(browserDarkenWebsites)
                                }
                                safeBrowsingEnabled = browserSafeBrowsingEnabled
                                javaScriptCanOpenWindowsAutomatically = !browserBlockPopups
                                mixedContentMode = if (browserBlockMixedContent) {
                                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                } else {
                                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                }
                                userAgentString = if (isDesktopMode) {
                                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                                } else {
                                    OkHttpDownloader.DEFAULT_USER_AGENT
                                }
                            }
                            setOnScrollChangeListener { _, scrollX, scrollY, _, _ ->
                                val pageUrl = url?.takeIf {
                                    it.startsWith("http://", ignoreCase = true) ||
                                        it.startsWith("https://", ignoreCase = true)
                                } ?: activeBaseUrlRef.get().takeIf {
                                    it.startsWith("http://", ignoreCase = true) ||
                                        it.startsWith("https://", ignoreCase = true)
                                }
                                if (pageUrl != null) {
                                    val previousSnapshot = browserRecoverySnapshotRef.get()?.takeIf {
                                        it.url == pageUrl
                                    }
                                    browserRecoverySnapshotRef.set(
                                        BrowserRendererRecoverySnapshot(
                                            url = pageUrl,
                                            webViewState = previousSnapshot?.webViewState,
                                            scrollX = scrollX,
                                            scrollY = scrollY
                                        )
                                    )
                                }
                            }
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, browserThirdPartyCookies)

                            addJavascriptInterface(
                                BrowserMediaExtractor.EmreMediaBridge { rawUrl ->
                                    if (seenRawUrls.size < 2500 && seenRawUrls.add(rawUrl)) {
                                        val base = activeBaseUrlRef.get()
                                        if (base.isNotBlank()) {
                                            pendingCandidateChannel.trySend(
                                                PendingBrowserMediaCandidate(
                                                    rawUrl = rawUrl,
                                                    baseUrl = base,
                                                    source = ExtractionSource.DOM_SCRAPE
                                                )
                                            )
                                        }
                                    }
                                },
                                "EmreBridge"
                            )

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadingProgressState.floatValue = newProgress / 100f
                                    val stillLoading = newProgress < 100
                                    if (isLoading != stillLoading) {
                                        isLoading = stillLoading
                                    }
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    val resolvedTitle = title.orEmpty()
                                    if (resolvedTitle != pageTitle) {
                                        pageTitle = resolvedTitle
                                        pageTitleRef.set(resolvedTitle)
                                        updateActiveBrowserTab(title = resolvedTitle)
                                        onPageTitleChanged?.invoke(resolvedTitle)
                                    }
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                                    super.onScaleChanged(view, oldScale, newScale)
                                    pageZoomPercent = Math.round(newScale * 100f).coerceIn(1, 500)
                                }

                                private fun handleExternalNavigation(view: WebView?, urlString: String): Boolean {
                                    if (urlString.isBlank()) return false
                                    val lower = urlString.trim().lowercase()

                                    // Internal browser schemes should load within Chromium WebView
                                    if (lower.startsWith("http://") || lower.startsWith("https://") ||
                                        lower.startsWith("about:") || lower.startsWith("data:") ||
                                        lower.startsWith("javascript:") || lower.startsWith("blob:") ||
                                        lower.startsWith("file:///android_asset/")
                                    ) {
                                        return false
                                    }

                                    val context = view?.context ?: return false

                                    // 1. Handle intent:// URI scheme (e.g. intent://...#Intent;scheme=...;package=...;end)
                                    if (lower.startsWith("intent://") || lower.startsWith("intent:")) {
                                        try {
                                            val intent = Intent.parseUri(urlString, Intent.URI_INTENT_SCHEME)
                                            if (intent != null) {
                                                val pm = context.packageManager
                                                val resolveInfo = intent.resolveActivity(pm)
                                                if (resolveInfo != null) {
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    context.startActivity(intent)
                                                    return true
                                                }

                                                // If target app is not installed, check for browser_fallback_url
                                                val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                                if (!fallbackUrl.isNullOrBlank() &&
                                                    (fallbackUrl.startsWith("http://", ignoreCase = true) || fallbackUrl.startsWith("https://", ignoreCase = true))
                                                ) {
                                                    view?.loadUrl(fallbackUrl)
                                                    return true
                                                }

                                                // If intent specifies target package, route to Google Play Store
                                                val packageName = intent.`package`
                                                if (!packageName.isNullOrBlank()) {
                                                    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    if (marketIntent.resolveActivity(pm) != null) {
                                                        context.startActivity(marketIntent)
                                                        return true
                                                    } else {
                                                        view?.loadUrl("https://play.google.com/store/apps/details?id=$packageName")
                                                        return true
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.w("DirectBrowserView", "Failed to resolve intent scheme: $urlString", e)
                                        }
                                        return true // Prevent Chromium from displaying net::ERR_UNKNOWN_URL_SCHEME
                                    }

                                    // 2. Handle specific app/system schemes: market://, tel:, mailto:, sms:, geo:, whatsapp:, tg:, etc.
                                    try {
                                        val uri = Uri.parse(urlString)
                                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        val pm = context.packageManager
                                        if (intent.resolveActivity(pm) != null) {
                                            context.startActivity(intent)
                                            return true
                                        } else if (lower.startsWith("market://details?id=")) {
                                            val pkg = uri.getQueryParameter("id")
                                            if (!pkg.isNullOrBlank()) {
                                                view?.loadUrl("https://play.google.com/store/apps/details?id=$pkg")
                                                return true
                                            }
                                        }
                                    } catch (e: ActivityNotFoundException) {
                                        Log.w("DirectBrowserView", "No app available to handle scheme: $urlString", e)
                                        return true
                                    } catch (e: Exception) {
                                        Log.w("DirectBrowserView", "Failed to handle external URI: $urlString", e)
                                        return true
                                    }

                                    return true
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val reqUrl = request?.url?.toString() ?: return false
                                    return handleExternalNavigation(view, reqUrl)
                                }

                                @Suppress("DEPRECATION")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    if (url.isNullOrBlank()) return false
                                    return handleExternalNavigation(view, url)
                                }
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    if (!isLoading) isLoading = true
                                    url?.let {
                                        if (it == "about:blank" || it.isBlank()) {
                                            pendingDomScanJob?.cancel()
                                            seenRawUrls.clear()
                                            lastScannedDomUrl = ""
                                            currentUrl = ""
                                            addressInput = ""
                                            pageTitle = ""
                                            pageTitleRef.set("")
                                            notifyUrlIfChanged("")
                                        } else {
                                            if (it != lastNotifiedUrl) {
                                                pendingDomScanJob?.cancel()
                                                seenRawUrls.clear()
                                                lastScannedDomUrl = ""
                                            }
                                            currentUrl = it
                                            addressInput = it
                                            notifyUrlIfChanged(it)
                                        }
                                    }
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                }

                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    val navigatedUrl = url?.takeIf {
                                        it.isNotBlank() && it != "about:blank"
                                    } ?: return
                                    if (navigatedUrl == lastNotifiedUrl) return

                                    pendingDomScanJob?.cancel()
                                    seenRawUrls.clear()
                                    lastScannedDomUrl = ""
                                    currentUrl = navigatedUrl
                                    addressInput = navigatedUrl
                                    notifyUrlIfChanged(navigatedUrl)

                                    val title = view?.title.orEmpty()
                                    pageTitle = title
                                    pageTitleRef.set(title)
                                    updateActiveBrowserTab(title = title)
                                    onPageTitleChanged?.invoke(title)
                                    pendingDomScanJob = coroutineScope.launch {
                                        delay(320L)
                                        if (view?.url == navigatedUrl &&
                                            activeBaseUrlRef.get() == navigatedUrl &&
                                            lastScannedDomUrl != navigatedUrl
                                        ) {
                                            lastScannedDomUrl = navigatedUrl
                                            scanCurrentPageDOM(view)
                                        }
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    rendererRecoveryMessage = null
                                    url?.let {
                                        if (it == "about:blank" || it.isBlank()) {
                                            currentUrl = ""
                                            addressInput = ""
                                            pageTitle = ""
                                            pageTitleRef.set("")
                                            notifyUrlIfChanged("")
                                        } else {
                                            currentUrl = it
                                            addressInput = it
                                            notifyUrlIfChanged(it)
                                            if (latestSaveHistoryEnabled && it != lastRecordedHistoryUrl) {
                                                lastRecordedHistoryUrl = it
                                                val recordedTitle = view?.title
                                                coroutineScope.launch {
                                                    historyManager.recordVisit(it, recordedTitle)
                                                }
                                            }
                                            // Setup live MutationObserver and defer DOM scan slightly so first paint stays snappy
                                            view?.evaluateJavascript(MediaCaptureEngine.getMutationObserverScript(), null)
                                            if (it != lastScannedDomUrl) {
                                                lastScannedDomUrl = it
                                                pendingDomScanJob?.cancel()
                                                pendingDomScanJob = coroutineScope.launch {
                                                    delay(220L)
                                                    scanCurrentPageDOM(view)
                                                }
                                            }
                                        }
                                    }
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                    view?.let { finishedView ->
                                        finishedView.post {
                                            if (webViewInstance === finishedView && finishedView.url == url) {
                                                captureBrowserRecoverySnapshot(finishedView)
                                            }
                                        }
                                    }
                                }

                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?
                                ): Boolean {
                                    val crashedView = view ?: return true
                                    if (webViewInstance !== crashedView) {
                                        releaseBrowserWebView(crashedView)
                                        return true
                                    }
                                    val crashedUrl = crashedView.url?.takeIf {
                                        it.startsWith("http://", ignoreCase = true) ||
                                            it.startsWith("https://", ignoreCase = true)
                                    } ?: activeBaseUrlRef.get().takeIf {
                                        it.startsWith("http://", ignoreCase = true) ||
                                            it.startsWith("https://", ignoreCase = true)
                                    }.orEmpty()

                                    Log.e(
                                        "DirectBrowserView",
                                        "WebView renderer exited (didCrash=${detail?.didCrash()}, url=$crashedUrl)"
                                    )
                                    // Preserve the most recent scroll position even when the
                                    // renderer dies between successful page-finish snapshots.
                                    captureBrowserRecoverySnapshot(crashedView, saveNavigationHistory = false)

                                    if (webViewInstance === crashedView) {
                                        webViewInstance = null
                                    }
                                    pendingDomScanJob?.cancel()
                                    lastScannedDomUrl = ""
                                    seenRawUrls.clear()
                                    if (crashedUrl.isNotBlank()) {
                                        currentUrl = crashedUrl
                                        addressInput = crashedUrl
                                        activeBaseUrlRef.set(crashedUrl)
                                    }
                                    canGoBack = false
                                    canGoForward = false
                                    isLoading = false
                                    loadingProgressState.floatValue = 0f
                                    releaseBrowserWebView(crashedView)

                                    val attempts = if (rendererCrashUrl == crashedUrl) {
                                        rendererCrashAttempts + 1
                                    } else {
                                        1
                                    }
                                    rendererCrashUrl = crashedUrl
                                    rendererCrashAttempts = attempts

                                    if (attempts <= 1) {
                                        rendererRecoveryMessage = "The page renderer stopped. Restoring this page…"
                                        isLoading = true
                                        coroutineScope.launch {
                                            delay(450L)
                                            if (
                                                webViewInstance == null &&
                                                rendererCrashUrl == crashedUrl &&
                                                currentUrl == crashedUrl
                                            ) {
                                                webViewGeneration += 1
                                            }
                                        }
                                    } else {
                                        rendererRecoveryMessage = "The page renderer stopped repeatedly. Retry this page to continue."
                                    }
                                    return true
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    if (request == null) return null
                                    val uri = request.url ?: return null
                                    val scheme = uri.scheme
                                    if (scheme != "http" && scheme != "https") return null
                                    val reqUrl = uri.toString()

                                    if (latestAdBlockingEnabled && AdBlockEngine.isAdOrTracker(reqUrl, uri.host)) {
                                        return AdBlockEngine.createEmptyResponse()
                                    }

                                    val candidateUrl = MediaCaptureEngine.matchCandidateMediaUrl(request)
                                    if (candidateUrl != null && seenRawUrls.size < 2500 && seenRawUrls.add(candidateUrl)) {
                                        val base = activeBaseUrlRef.get().ifBlank { reqUrl }
                                        pendingCandidateChannel.trySend(
                                            PendingBrowserMediaCandidate(
                                                rawUrl = candidateUrl,
                                                baseUrl = base,
                                                source = ExtractionSource.NETWORK_INTERCEPT
                                            )
                                        )
                                    }
                                    return null
                                }
                            }

                            val restoredHistory = if (webViewGeneration > 0) {
                                recoverySnapshot?.webViewState?.let { savedState ->
                                    runCatching {
                                        restoreState(Bundle(savedState))
                                    }.onFailure {
                                        Log.w("DirectBrowserView", "Failed to restore WebView navigation state", it)
                                    }.getOrNull()
                                }
                            } else null

                            if (restoredHistory != null && recoverySnapshot != null) {
                                val restoredUrl = url?.takeIf {
                                    it.startsWith("http://", ignoreCase = true) ||
                                        it.startsWith("https://", ignoreCase = true)
                                } ?: recoveryUrl
                                if (restoredUrl.isNotBlank()) {
                                    currentUrl = restoredUrl
                                    addressInput = restoredUrl
                                    activeBaseUrlRef.set(restoredUrl)
                                }
                                postDelayed({
                                    if (webViewInstance === this) {
                                        scrollTo(recoverySnapshot.scrollX, recoverySnapshot.scrollY)
                                    }
                                }, 350L)
                            } else if (recoveryUrl.isNotBlank()) {
                                loadUrl(recoveryUrl)
                            } else {
                                loadUrl("about:blank")
                            }
                        }
                    },
                    update = { wv ->
                        // AndroidView can briefly issue an update for its previous child after a
                        // renderer crash. Never re-register a destroyed WebView as active.
                        if (!destroyedWebViews.contains(wv)) {
                            if (webViewInstance !== wv) {
                                webViewInstance = wv
                            }
                            wv.setBackgroundColor(browserSurfaceColor)
                            wv.settings.javaScriptEnabled = browserJavaScriptEnabled
                            wv.settings.blockNetworkImage = !browserLoadImages
                            wv.settings.textZoom = browserTextZoom.coerceIn(50, 200)
                            wv.settings.safeBrowsingEnabled = browserSafeBrowsingEnabled
                            wv.settings.javaScriptCanOpenWindowsAutomatically = !browserBlockPopups
                            wv.settings.mixedContentMode = if (browserBlockMixedContent) {
                                WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            } else {
                                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            }
                            wv.settings.mediaPlaybackRequiresUserGesture = !browserAllowAutoplay
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                wv.settings.setAlgorithmicDarkeningAllowed(browserDarkenWebsites)
                            }
                            wv.settings.setSupportZoom(browserPinchZoomEnabled)
                            wv.settings.builtInZoomControls = browserPinchZoomEnabled
                            wv.settings.userAgentString = if (isDesktopMode) {
                                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                            } else {
                                OkHttpDownloader.DEFAULT_USER_AGENT
                            }
                            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, browserThirdPartyCookies)
                        }
                    },
                    onRelease = { wv ->
                        releaseBrowserWebView(wv)
                        if (webViewInstance === wv) {
                            webViewInstance = null
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("browser_webview_canvas")
                )
                }

                if (rendererCrashAttempts > 1 && rendererRecoveryMessage != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                            .zIndex(4f),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 8.dp,
                        shadowElevation = 12.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                rendererRecoveryMessage.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "Your browser settings and discovered media are preserved.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = {
                                    rendererCrashAttempts = 0
                                    rendererCrashUrl = currentUrl
                                    rendererRecoveryMessage = "Restoring this page…"
                                    isLoading = true
                                    webViewGeneration += 1
                                },
                                modifier = Modifier.testTag("browser_renderer_retry")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Retry page")
                            }
                        }
                    }
                }

                if (currentUrl.isBlank()) {
                    BrowserEmptyHomePage(
                        onNavigate = { target ->
                            navigateToUrl(target)
                        },
                        onRemoveSavedSite = { site ->
                            coroutineScope.launch { historyManager.removeSavedSite(site.url) }
                        },
                        showShortcuts = browserShowShortcuts,
                        showRecentlyVisited = browserShowRecentlyVisited,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .testTag("browser_empty_home_page")
                    )
                }
            }
            }
        }

        if (showTabSwitcher) {
            ModalBottomSheet(
                onDismissRequest = { showTabSwitcher = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 18.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Tabs",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${browserTabs.size} open",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showTabSwitcher = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close tabs overview")
                        }
                    }

                    FilledTonalButton(
                        onClick = { createBrowserTab() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .testTag("browser_new_tab_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("New tab")
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(browserTabs, key = { it.id }) { tab ->
                            val displayTitle = tab.title.ifBlank {
                                if (tab.url.isBlank()) "New tab"
                                else runCatching { URL(tab.url).host }.getOrDefault(tab.url)
                            }
                            val displayAddress = tab.url.ifBlank { "Start page" }
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { activateBrowserTab(tab.id) }
                                    .testTag("browser_tab_${tab.id}"),
                                shape = RoundedCornerShape(16.dp),
                                color = if (tab.id == activeBrowserTabId) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                tonalElevation = if (tab.id == activeBrowserTabId) 2.dp else 0.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Language,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            displayTitle,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (tab.id == activeBrowserTabId) FontWeight.SemiBold else FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            displayAddress,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { closeBrowserTab(tab.id) },
                                        modifier = Modifier
                                            .size(38.dp)
                                            .testTag("browser_close_tab_${tab.id}")
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Close ${displayTitle} tab",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showBrowserSettings) {
            BrowserSettingsScreen(
                searchEngine = defaultSearchEngine,
                javaScriptEnabled = browserJavaScriptEnabled,
                adBlockingEnabled = browserAdBlockingEnabled,
                loadImages = browserLoadImages,
                thirdPartyCookies = browserThirdPartyCookies,
                saveHistory = browserSaveHistory,
                desktopSiteByDefault = browserDesktopSiteByDefault,
                textZoom = browserTextZoom,
                safeBrowsingEnabled = browserSafeBrowsingEnabled,
                blockPopups = browserBlockPopups,
                blockMixedContent = browserBlockMixedContent,
                allowAutoplay = browserAllowAutoplay,
                pinchZoomEnabled = browserPinchZoomEnabled,
                homepageUrl = browserHomepageUrl,
                showShortcuts = browserShowShortcuts,
                showRecentlyVisited = browserShowRecentlyVisited,
                darkenWebsites = browserDarkenWebsites,
                onHomepageSave = { url ->
                    coroutineScope.launch { downloaderPreferences.setBrowserHomepageUrl(url) }
                },
                onShowShortcutsChange = { enabled ->
                    coroutineScope.launch { downloaderPreferences.setBrowserShowShortcuts(enabled) }
                },
                onShowRecentlyVisitedChange = { enabled ->
                    coroutineScope.launch { downloaderPreferences.setBrowserShowRecentlyVisited(enabled) }
                },
                onDarkenWebsitesChange = { enabled ->
                    coroutineScope.launch { downloaderPreferences.setBrowserDarkenWebsites(enabled) }
                },
                onSearchEngineChange = { engine ->
                    coroutineScope.launch { downloaderPreferences.setSearchEngine(engine) }
                },
                onJavaScriptChange = { enabled ->
                    coroutineScope.launch { downloaderPreferences.setBrowserJavaScriptEnabled(enabled) }
                },
                onAdBlockingChange = { enabled ->
                    coroutineScope.launch { downloaderPreferences.setBrowserAdBlockingEnabled(enabled) }
                },
                onLoadImagesChange = { enabled ->
                    coroutineScope.launch { downloaderPreferences.setBrowserLoadImages(enabled) }
                },
                onThirdPartyCookiesChange = { enabled ->
                    coroutineScope.launch { downloaderPreferences.setBrowserThirdPartyCookies(enabled) }
                },
                onSaveHistoryChange = { enabled ->
                    coroutineScope.launch { downloaderPreferences.setBrowserSaveHistory(enabled) }
                },
                onDesktopSiteByDefaultChange = { enabled ->
                    isDesktopMode = enabled
                    coroutineScope.launch { downloaderPreferences.setBrowserDesktopSiteByDefault(enabled) }
                },
                onTextZoomChange = { zoom ->
                    coroutineScope.launch { downloaderPreferences.setBrowserTextZoom(zoom) }
                },
                onSafeBrowsingChange = { enabled ->
                    coroutineScope.launch { downloaderPreferences.setBrowserSafeBrowsingEnabled(enabled) }
                },
                onBlockPopupsChange = { enabled ->
                    coroutineScope.launch { downloaderPreferences.setBrowserBlockPopups(enabled) }
                },
                onBlockMixedContentChange = { enabled ->
                    coroutineScope.launch { downloaderPreferences.setBrowserBlockMixedContent(enabled) }
                },
                onAllowAutoplayChange = { enabled ->
                    coroutineScope.launch { downloaderPreferences.setBrowserAllowAutoplay(enabled) }
                },
                onPinchZoomChange = { enabled ->
                    coroutineScope.launch { downloaderPreferences.setBrowserPinchZoomEnabled(enabled) }
                },
                onClearBrowsingData = {
                    coroutineScope.launch { historyManager.clearHistory() }
                    webViewInstance?.apply {
                        clearHistory()
                        clearCache(true)
                        clearFormData()
                    }
                    CookieManager.getInstance().removeAllCookies {
                        CookieManager.getInstance().flush()
                    }
                    WebStorage.getInstance().deleteAllData()
                    lastRecordedHistoryUrl = ""
                    Toast.makeText(context, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                },
                onBack = { showBrowserSettings = false },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Browser-level preferences opened from the browser overflow menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserSettingsScreen(
    searchEngine: BrowserSearchEngine,
    javaScriptEnabled: Boolean,
    adBlockingEnabled: Boolean,
    loadImages: Boolean,
    thirdPartyCookies: Boolean,
    saveHistory: Boolean,
    desktopSiteByDefault: Boolean,
    textZoom: Int,
    safeBrowsingEnabled: Boolean,
    blockPopups: Boolean,
    blockMixedContent: Boolean,
    allowAutoplay: Boolean,
    pinchZoomEnabled: Boolean,
    homepageUrl: String,
    showShortcuts: Boolean,
    showRecentlyVisited: Boolean,
    darkenWebsites: Boolean,
    onHomepageSave: (String) -> Unit,
    onShowShortcutsChange: (Boolean) -> Unit,
    onShowRecentlyVisitedChange: (Boolean) -> Unit,
    onDarkenWebsitesChange: (Boolean) -> Unit,
    onSearchEngineChange: (BrowserSearchEngine) -> Unit,
    onJavaScriptChange: (Boolean) -> Unit,
    onAdBlockingChange: (Boolean) -> Unit,
    onLoadImagesChange: (Boolean) -> Unit,
    onThirdPartyCookiesChange: (Boolean) -> Unit,
    onSaveHistoryChange: (Boolean) -> Unit,
    onDesktopSiteByDefaultChange: (Boolean) -> Unit,
    onTextZoomChange: (Int) -> Unit,
    onSafeBrowsingChange: (Boolean) -> Unit,
    onBlockPopupsChange: (Boolean) -> Unit,
    onBlockMixedContentChange: (Boolean) -> Unit,
    onAllowAutoplayChange: (Boolean) -> Unit,
    onPinchZoomChange: (Boolean) -> Unit,
    onClearBrowsingData: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showClearDataConfirmation by remember { mutableStateOf(false) }
    var homepageDraft by remember(homepageUrl) { mutableStateOf(homepageUrl) }
    var homepageError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("browser_settings_screen"),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Browser settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BrowserSettingsSectionTitle("General")
            Text(
                text = "Default search engine",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            BrowserSearchEngine.entries.forEach { engine ->
                Surface(
                    onClick = { onSearchEngineChange(engine) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (searchEngine == engine) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = searchEngine == engine,
                            onClick = { onSearchEngineChange(engine) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = engine.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (searchEngine == engine) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            Text(
                "Homepage",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Used when the browser starts without an incoming link and when you choose Home.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = homepageDraft,
                onValueChange = {
                    homepageDraft = it
                    homepageError = null
                },
                modifier = Modifier.fillMaxWidth().testTag("browser_homepage_field"),
                singleLine = true,
                label = { Text("Website address") },
                placeholder = { Text("Leave empty for the browser home page") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                isError = homepageError != null,
                supportingText = { homepageError?.let { Text(it) } }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        val entered = homepageDraft.trim()
                        val candidate = when {
                            entered.isBlank() -> ""
                            entered.startsWith("https://", ignoreCase = true) ||
                                entered.startsWith("http://", ignoreCase = true) -> entered
                            else -> "https://$entered"
                        }
                        val parsed = Uri.parse(candidate)
                        if (candidate.isNotBlank() &&
                            (parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrBlank())
                        ) {
                            homepageError = "Enter a valid website address."
                        } else {
                            homepageError = null
                            homepageDraft = candidate
                            onHomepageSave(candidate)
                        }
                    },
                    modifier = Modifier.testTag("browser_save_homepage")
                ) {
                    Text("Save homepage")
                }
                TextButton(
                    onClick = {
                        homepageDraft = ""
                        homepageError = null
                        onHomepageSave("")
                    },
                    modifier = Modifier.testTag("browser_reset_homepage")
                ) {
                    Text("Reset")
                }
            }

            BrowserSettingsSwitchRow(
                title = "Show shortcuts",
                description = "Show frequently visited sites on the browser home page",
                checked = showShortcuts,
                onCheckedChange = onShowShortcutsChange
            )
            BrowserSettingsSwitchRow(
                title = "Show recently visited",
                description = "Show recent browsing entries on the browser home page",
                checked = showRecentlyVisited,
                onCheckedChange = onShowRecentlyVisitedChange
            )

            HorizontalDivider()
            BrowserSettingsSectionTitle("Privacy")
            BrowserSettingsSwitchRow(
                title = "Block ads and trackers",
                description = "Block known advertising and tracking requests",
                checked = adBlockingEnabled,
                onCheckedChange = onAdBlockingChange
            )
            BrowserSettingsSwitchRow(
                title = "Save browsing history",
                description = "Show recently visited sites on the browser home page",
                checked = saveHistory,
                onCheckedChange = onSaveHistoryChange
            )
            BrowserSettingsSwitchRow(
                title = "Third-party cookies",
                description = "Allow cookies from sites other than the one you visit",
                checked = thirdPartyCookies,
                onCheckedChange = onThirdPartyCookiesChange
            )
            BrowserSettingsSwitchRow(
                title = "Safe Browsing",
                description = "Warn before loading websites flagged for malware or phishing",
                checked = safeBrowsingEnabled,
                onCheckedChange = onSafeBrowsingChange
            )
            BrowserSettingsSwitchRow(
                title = "Block pop-ups",
                description = "Prevent websites from opening unsolicited windows",
                checked = blockPopups,
                onCheckedChange = onBlockPopupsChange
            )
            OutlinedButton(
                onClick = { showClearDataConfirmation = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Clear browsing data")
            }

            HorizontalDivider()
            BrowserSettingsSectionTitle("Site settings")
            BrowserSettingsSwitchRow(
                title = "JavaScript",
                description = "Allow websites to run JavaScript",
                checked = javaScriptEnabled,
                onCheckedChange = onJavaScriptChange
            )
            BrowserSettingsSwitchRow(
                title = "Load images",
                description = "Show images embedded in web pages",
                checked = loadImages,
                onCheckedChange = onLoadImagesChange
            )
            BrowserSettingsSwitchRow(
                title = "Block insecure content",
                description = "Prevent HTTP resources from loading inside HTTPS pages",
                checked = blockMixedContent,
                onCheckedChange = onBlockMixedContentChange
            )
            BrowserSettingsSwitchRow(
                title = "Allow media autoplay",
                description = "Let websites play audio and video without a tap",
                checked = allowAutoplay,
                onCheckedChange = onAllowAutoplayChange
            )

            HorizontalDivider()
            BrowserSettingsSectionTitle("Appearance")
            BrowserSettingsSwitchRow(
                title = "Desktop site by default",
                description = "Request desktop versions of websites when possible",
                checked = desktopSiteByDefault,
                onCheckedChange = onDesktopSiteByDefaultChange
            )
            BrowserSettingsSwitchRow(
                title = "Pinch to zoom",
                description = "Allow two-finger zoom gestures and browser zoom controls",
                checked = pinchZoomEnabled,
                onCheckedChange = onPinchZoomChange
            )
            BrowserSettingsSwitchRow(
                title = "Allow website darkening",
                description = "Let WebView darken pages without a dark theme when the app is in dark mode (Android 13+)",
                checked = darkenWebsites,
                onCheckedChange = onDarkenWebsitesChange,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Page text size",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "$textZoom%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = textZoom.toFloat().coerceIn(50f, 200f),
                    onValueChange = { onTextZoomChange(it.toInt().coerceIn(50, 200)) },
                    valueRange = 50f..200f,
                    steps = 29,
                    modifier = Modifier.testTag("browser_text_zoom_slider")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("50%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("200%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showClearDataConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearDataConfirmation = false },
            title = { Text("Clear browsing data?") },
            text = {
                Text("This clears browsing history, cached pages, cookies and website storage. Saved shortcuts and downloaded media will remain.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDataConfirmation = false
                        onClearBrowsingData()
                    },
                    modifier = Modifier.testTag("browser_confirm_clear_data")
                ) {
                    Text("Clear data", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun BrowserSettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun BrowserSettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

/**
 * Builds the same host-based favicon URL used by the downloader's recent-site list.
 * Invalid or empty hosts return an empty URL so the UI can render its text fallback.
 */
fun browserFaviconUrl(domain: String): String {
    val host = domain.trim().lowercase().removePrefix("www.")
    if (host.isBlank() || host.any { it.isWhitespace() || it in "/?#@" }) return ""
    return "https://www.google.com/s2/favicons?domain=$host&sz=128"
}

/**
 * Shared browser-home favicon renderer. The site's initial stays visible while the
 * remote icon loads and remains as a fallback if the favicon request fails.
 */
@Composable
private fun BrowserHomeFavicon(
    site: BrowserSite,
    modifier: Modifier = Modifier,
    iconPadding: Dp = 6.dp
) {
    val context = LocalContext.current
    val faviconUrl = remember(site.domain) { browserFaviconUrl(site.domain) }
    var faviconFailed by remember(faviconUrl) {
        mutableStateOf(faviconUrl.isBlank())
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = site.title.ifBlank { site.domain }
                .firstOrNull()?.uppercaseChar()?.toString() ?: "W",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )

        if (!faviconFailed) {
            AsyncImage(
                model = remember(faviconUrl, context) {
                    ImageRequest.Builder(context)
                        .data(faviconUrl)
                        .crossfade(true)
                        .listener(onError = { _, _ -> faviconFailed = true })
                        .build()
                },
                contentDescription = "${site.title.ifBlank { site.domain }} favicon",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(iconPadding),
                contentScale = ContentScale.Fit
            )
        }
    }
}

/**
 * Browser home content. The fixed toolbar above this page is the single URL/search field.
 */
@Composable
fun BrowserEmptyHomePage(
    onNavigate: (String) -> Unit,
    onRemoveSavedSite: (BrowserSite) -> Unit = {},
    showShortcuts: Boolean = true,
    showRecentlyVisited: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val historyManager = remember { BrowserHistoryManager(context) }
    val allHistorySites by historyManager.allSites.collectAsState(initial = emptyList())
    val savedSites by historyManager.savedSites.collectAsState(initial = emptyList())

    val shortcutSites = remember(allHistorySites, showShortcuts) {
        if (showShortcuts) {
            allHistorySites.sortedWith(
                compareByDescending<BrowserSite> { it.visitCount }
                    .thenByDescending { it.lastVisitedMillis }
            ).take(5)
        } else {
            emptyList()
        }
    }
    val recentSites = remember(allHistorySites, showRecentlyVisited) {
        if (showRecentlyVisited) allHistorySites.sortedByDescending { it.lastVisitedMillis }.take(8) else emptyList()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        if (savedSites.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Saved",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${savedSites.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(savedSites, key = { it.domain }) { site ->
                        Surface(
                            onClick = { onNavigate(site.url) },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier
                                .widthIn(min = 148.dp, max = 196.dp)
                                .testTag("browser_saved_site_${site.domain}")
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    BrowserHomeFavicon(
                                        site = site,
                                        modifier = Modifier.fillMaxSize(),
                                        iconPadding = 5.dp
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        site.title.ifBlank { site.domain },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        site.domain,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveSavedSite(site) },
                                    modifier = Modifier.size(28.dp).testTag("browser_remove_saved_${site.domain}")
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove ${site.domain} from saved shortcuts",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showShortcuts) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Shortcuts",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (shortcutSites.isEmpty()) {
                Text(
                    "Frequently visited sites will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(shortcutSites, key = { it.domain }) { site ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(68.dp)
                                .clickable { onNavigate(site.url) }
                                .testTag("browser_shortcut_${site.domain}")
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                ),
                                shadowElevation = 1.dp,
                                modifier = Modifier.size(48.dp)
                            ) {
                                BrowserHomeFavicon(
                                    site = site,
                                    modifier = Modifier.fillMaxSize(),
                                    iconPadding = 8.dp
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                site.title.ifBlank { site.domain },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        }
        if (showRecentlyVisited) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Recently visited",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (recentSites.isNotEmpty()) {
                    Text(
                        "${recentSites.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (recentSites.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                "No browsing history yet",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Use the address bar above to visit your first site.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                recentSites.forEachIndexed { index, site ->
                    Surface(
                        onClick = { onNavigate(site.url) },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 58.dp)
                            .testTag("browser_recent_site_$index")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.size(38.dp)
                            ) {
                                BrowserHomeFavicon(
                                    site = site,
                                    modifier = Modifier.fillMaxSize(),
                                    iconPadding = 8.dp
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    site.title.ifBlank { site.domain },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    site.domain,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                Icons.Default.ArrowForward,
                                contentDescription = "Open site",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
        }

        Spacer(Modifier.height(12.dp))
    }
}
