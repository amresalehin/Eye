package com.amresalehin.emregallery.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import android.app.Activity
import android.os.Build
import com.amresalehin.emregallery.data.AdBlockEngine
import com.amresalehin.emregallery.data.BotDetectionMitigator
import com.amresalehin.emregallery.data.BrowserHistoryManager
import com.amresalehin.emregallery.data.RamGovernor
import com.amresalehin.emregallery.data.BrowserSession
import com.amresalehin.emregallery.data.BrowserShortcut
import com.amresalehin.emregallery.data.BrowserTabItem
import com.amresalehin.emregallery.data.DownloadQueueManager
import com.amresalehin.emregallery.data.DownloaderSession
import com.amresalehin.emregallery.data.ExtractionSource
import com.amresalehin.emregallery.data.MediaCaptureEngine
import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.MediaProvenance
import com.amresalehin.emregallery.data.PocketDeepScanEngine
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.data.RedirectProtectionEngine
import com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager
import com.amresalehin.emregallery.data.ScrapedMediaItem
import android.webkit.URLUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URL

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DirectBrowserView(
    initialUrl: String = "",
    onClose: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
    onUrlChange: ((String) -> Unit)? = null,
    onPageTitleChanged: ((String) -> Unit)? = null,
    onMediaExtracted: (ScrapedMediaItem) -> Unit,
    onBatchExtracted: (List<ScrapedMediaItem>, pageTitle: String?, domain: String?) -> Unit,
    onSwitchToMediaTab: () -> Unit = {},
    onOpenDownloads: (() -> Unit)? = null,
    onOpenDiscoveredMedia: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val historyManager = remember { BrowserHistoryManager(context) }
    val tabSession = remember { BrowserSession.getTabSession("tab_main") }
    val tabDetectedCount by tabSession.detectedCount.collectAsState()
    val sessionDiscoveredItems by DownloaderSession.discoveredItems.collectAsState()

    val prefsRepo = remember { PreferencesRepository(context) }
    val downloaderPrefs = remember { DownloaderPreferencesManager(context) }
    val adBlockEnabled by downloaderPrefs.adBlockEnabled.collectAsState(initial = true)
    val redirectProtectionEnabled by downloaderPrefs.redirectProtectionEnabled.collectAsState(initial = true)
    val redirectTapsToOpen by downloaderPrefs.redirectTapsToOpen.collectAsState(initial = 2)
    val hideBlockedElements by downloaderPrefs.hideBlockedRedirectElements.collectAsState(initial = true)
    val speedBoostEnabled by downloaderPrefs.speedBoostEnabled.collectAsState(initial = true)
    val speedBoostConnections by downloaderPrefs.speedBoostConnections.collectAsState(initial = 4)
    val videoQuality by downloaderPrefs.videoQualityCap.collectAsState(initial = com.amresalehin.emregallery.data.preferences.DownloaderVideoQuality.BEST)
    val formatMode by downloaderPrefs.formatMode.collectAsState(initial = com.amresalehin.emregallery.data.preferences.DownloaderFormatMode.VIDEO_AUDIO)
    val downloadPlaylist by downloaderPrefs.downloadPlaylist.collectAsState(initial = false)
    val downloadSubtitles by downloaderPrefs.downloadSubtitles.collectAsState(initial = false)
    val includeAutoSubtitles by downloaderPrefs.includeAutoSubtitles.collectAsState(initial = true)
    val subtitleLanguages by downloaderPrefs.subtitleLanguages.collectAsState(initial = setOf("en"))
    val proxyEnabled by downloaderPrefs.proxyEnabled.collectAsState(initial = false)
    val proxyUrl by downloaderPrefs.proxyUrl.collectAsState(initial = "")
    val recursiveCrawlEnabled by downloaderPrefs.recursiveCrawlEnabled.collectAsState(initial = false)
    val recursiveCrawlMaxDepth by downloaderPrefs.recursiveCrawlMaxDepth.collectAsState(initial = 3)
    val recursiveCrawlMaxPages by downloaderPrefs.recursiveCrawlMaxPages.collectAsState(initial = 20)
    val recursiveCrawlSameDomainOnly by downloaderPrefs.recursiveCrawlSameDomainOnly.collectAsState(initial = true)
    val saveCrawlSitemap by downloaderPrefs.saveCrawlSitemap.collectAsState(initial = true)
    var showNetworkStatusDialog by remember { mutableStateOf(false) }
    val themePref by prefsRepo.theme.collectAsState(initial = "system")
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (themePref) {
        "dark" -> true
        "light" -> false
        else -> systemInDark
    }

    val shortcutsList by historyManager.shortcuts.collectAsState(initial = historyManager.defaultShortcuts)
    val allHistorySites by historyManager.allSites.collectAsState(initial = historyManager.defaultRecentSites)
    val tabsList by historyManager.openTabs.collectAsState(initial = historyManager.defaultTabs)

    val startUrl = remember(initialUrl) {
        val trimmed = initialUrl.trim()
        val savedSite = BrowserSession.savedBrowserSite.trim()
        when {
            trimmed.isNotBlank() -> {
                BrowserSession.savedBrowserSite = ""
                BrowserSession.pendingScrollRestore = false
                MediaClassifier.formatQueryOrUrl(trimmed)
            }
            BrowserSession.pendingScrollRestore && savedSite.isNotBlank() -> savedSite
            else -> {
                BrowserSession.savedBrowserSite = ""
                "" // Clean monochrome home page by default
            }
        }
    }

    var hasVisitedBrowserHome by remember(initialUrl) {
        mutableStateOf(startUrl.isBlank())
    }

    // Discovery & Deep Scan states
    var isDeepScanning by remember { mutableStateOf(false) }
    var hasInitiatedScanInStage by remember { mutableStateOf(false) }
    var deepScanJob by remember { mutableStateOf<Job?>(null) }
    var deepScanToken by remember { mutableIntStateOf(0) }

    var currentUrl by remember(initialUrl) { mutableStateOf(startUrl) }
    val totalDetectedCount = remember(currentUrl, tabDetectedCount, sessionDiscoveredItems.size) {
        if (currentUrl.isBlank() || currentUrl == "about:blank") 0
        else maxOf(tabDetectedCount, sessionDiscoveredItems.size)
    }
    var addressInput by remember(initialUrl) { mutableStateOf(startUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingProgress by remember { mutableFloatStateOf(0f) }
    var isDesktopMode by remember { mutableStateOf(false) }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var webViewRecreateEpoch by remember { mutableIntStateOf(0) }
    var lastRendererCrashMs by remember { mutableLongStateOf(0L) }

    // Dialog & Sheet States
    var showAddShortcutDialog by remember { mutableStateOf(false) }
    var selectedShortcutForEdit by remember { mutableStateOf<BrowserShortcut?>(null) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showTabsSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showDiscoveredMediaOverlay by remember { mutableStateOf(false) }
    var activeTabId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tabsList) {
        if (tabsList.isEmpty()) {
            val newId = "tab_${System.currentTimeMillis()}"
            val initialTab = BrowserTabItem(
                id = newId,
                title = if (pageTitle.isNotBlank()) pageTitle else if (currentUrl.isNotBlank()) MediaClassifier.cleanDomainFromUrl(currentUrl) else "Home",
                url = currentUrl
            )
            activeTabId = newId
            historyManager.saveTabs(listOf(initialTab))
        } else if (activeTabId == null || tabsList.none { it.id == activeTabId }) {
            val matching = tabsList.firstOrNull { it.url.isNotBlank() && it.url.equals(currentUrl, ignoreCase = true) }
                ?: tabsList.first()
            activeTabId = matching.id
        }
    }

    fun syncCurrentTabWithPage(targetUrl: String, targetTitle: String) {
        val tabId = activeTabId ?: return
        val currentTabs = tabsList
        val existing = currentTabs.firstOrNull { it.id == tabId }
        val displayTitle = if (targetTitle.isNotBlank() && targetTitle != "about:blank") {
            targetTitle
        } else if (targetUrl.isNotBlank() && targetUrl != "about:blank") {
            MediaClassifier.cleanDomainFromUrl(targetUrl)
        } else {
            "Home"
        }
        val cleanUrl = if (targetUrl == "about:blank") "" else targetUrl
        if (existing == null || existing.url != cleanUrl || existing.title != displayTitle) {
            val updatedTabs = if (existing == null) {
                currentTabs + BrowserTabItem(id = tabId, title = displayTitle, url = cleanUrl)
            } else {
                currentTabs.map {
                    if (it.id == tabId) it.copy(title = displayTitle, url = cleanUrl, timestamp = System.currentTimeMillis())
                    else it
                }
            }
            coroutineScope.launch {
                historyManager.saveTabs(updatedTabs)
            }
        }
    }

    val currentView = LocalView.current
    DisposableEffect(isDark) {
        val window = (currentView.context as? Activity)?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, currentView)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
        onDispose { }
    }

    val scrollHandler = remember { BrowserToolbarScrollHandler() }

    LaunchedEffect(currentUrl) {
        if (currentUrl.isBlank()) {
            scrollHandler.show()
        }
    }

    LaunchedEffect(Unit) {
        // Ensure no background deep-scan WebView is contending for the Chromium renderer process
        PocketDeepScanEngine.stopScan()
        if (startUrl.isBlank()) {
            tabSession.clearMedia()
            tabSession.updateNavigation("")
            DownloaderSession.clear()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            BrowserSession.savedBrowserSite = ""
            BrowserSession.pendingScrollRestore = false
            webViewInstance?.let { wv ->
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.onPause()
                wv.webViewClient = WebViewClient()
                wv.webChromeClient = WebChromeClient()
                wv.post {
                    try { wv.destroy() } catch (_: Throwable) {}
                }
                webViewInstance = null
            }
        }
    }

    fun returnToBrowserHome() {
        hasVisitedBrowserHome = true
        BrowserSession.savedBrowserSite = ""
        BrowserSession.pendingScrollRestore = false
        currentUrl = ""
        addressInput = ""
        pageTitle = ""
        canGoBack = false
        canGoForward = false
        onUrlChange?.invoke("")
        tabSession.clearMedia()
        tabSession.updateNavigation("")
        DownloaderSession.clear()
        scrollHandler.show()
        webViewInstance?.clearHistory()
        webViewInstance?.loadUrl("about:blank")
    }

    // Synchronized back navigation for DirectBrowserView (shared by native BackHandler and search bar back button)
    fun handleBrowserBackNavigation() {
        when {
            showDiscoveredMediaOverlay -> {
                showDiscoveredMediaOverlay = false
                deepScanJob?.cancel()
                deepScanJob = null
                isDeepScanning = false
                hasInitiatedScanInStage = false
            }
            showHistorySheet -> showHistorySheet = false
            showTabsSheet -> showTabsSheet = false
            showSettingsSheet -> showSettingsSheet = false
            showAddShortcutDialog -> showAddShortcutDialog = false
            selectedShortcutForEdit != null -> selectedShortcutForEdit = null
            currentUrl.isNotBlank() -> {
                if (canGoBack && webViewInstance?.canGoBack() == true) {
                    webViewInstance?.goBack()
                } else if (!hasVisitedBrowserHome && onClose != null) {
                    BrowserSession.savedBrowserSite = ""
                    BrowserSession.pendingScrollRestore = false
                    onClose()
                } else {
                    returnToBrowserHome()
                }
            }
            onClose != null -> {
                BrowserSession.savedBrowserSite = ""
                BrowserSession.pendingScrollRestore = false
                onClose()
            }
        }
    }

    // Intercept back button when browser can navigate back, return to home, dismiss a sheet, or exit the browser
    BackHandler(
        enabled = showDiscoveredMediaOverlay || showHistorySheet || showTabsSheet || showSettingsSheet ||
                showAddShortcutDialog || selectedShortcutForEdit != null ||
                currentUrl.isNotBlank() || onClose != null
    ) {
        handleBrowserBackNavigation()
    }

    fun scanCurrentPageDOM(wv: WebView?, onCompleted: (() -> Unit)? = null) {
        if (wv == null) {
            onCompleted?.invoke()
            return
        }
        val current = wv.url ?: run {
            onCompleted?.invoke()
            return
        }
        if (current.isBlank() || current == "about:blank" || currentUrl.isBlank()) {
            onCompleted?.invoke()
            return
        }

        wv.evaluateJavascript(MediaCaptureEngine.getJsDomScanScript()) { jsonRaw ->
            val discovered = MediaCaptureEngine.parseJsDomScanResult(jsonRaw, current)
            if (discovered.isNotEmpty()) {
                val domain = runCatching { URL(current).host }.getOrDefault("")
                tabSession.registerMediaBatch(discovered, pageTitle)
                onBatchExtracted(discovered, pageTitle, domain)
                DownloaderSession.addDiscoveredItems(discovered, pageTitle)
            }
            onCompleted?.invoke()
        }
    }

    fun stopBrowserDeepScan() {
        deepScanToken++
        deepScanJob?.cancel()
        deepScanJob = null
        PocketDeepScanEngine.stopScan()
        isDeepScanning = false
    }

    fun startBrowserDeepScan() {
        val current = currentUrl.ifBlank { webViewInstance?.url.orEmpty() }
        if (current.isBlank() || current == "about:blank") return

        stopBrowserDeepScan()
        val myScanToken = ++deepScanToken
        isDeepScanning = true
        hasInitiatedScanInStage = true

        // 1. Immediately scan active DOM on the visible WebView WITHOUT any visual scrolling
        // Promote lazy attributes in-place without moving the visible viewport
        webViewInstance?.let { wv ->
            wv.evaluateJavascript(
                """
                (function() {
                    try {
                        var lazyImgs = document.querySelectorAll('img[data-src], img[data-original], img[data-lazy], img[data-url], img[data-highres]');
                        for (var i = 0; i < lazyImgs.length; i++) {
                            var img = lazyImgs[i];
                            var realSrc = img.getAttribute('data-src') || img.getAttribute('data-original') || img.getAttribute('data-lazy') || img.getAttribute('data-url') || img.getAttribute('data-highres');
                            if (realSrc && !img.src) img.src = realSrc;
                        }
                        var lazyVideos = document.querySelectorAll('video[data-src], source[data-src], video[data-url], source[data-url]');
                        for (var j = 0; j < lazyVideos.length; j++) {
                            var v = lazyVideos[j];
                            var vSrc = v.getAttribute('data-src') || v.getAttribute('data-url');
                            if (vSrc && !v.src) v.src = vSrc;
                        }
                        window.dispatchEvent(new Event('scroll'));
                        window.dispatchEvent(new Event('resize'));
                    } catch(e) {}
                })();
                """.trimIndent(),
                null
            )
            scanCurrentPageDOM(wv)
        }

        // 2. Run multi-pass DOM & lazy-media extraction directly on the active WebView
        // Avoids spawning a second concurrent WebView in the single Chromium renderer process
        deepScanJob = coroutineScope.launch {
            try {
                repeat(6) {
                    if (!isActive || !isDeepScanning) return@launch
                    val wv = webViewInstance ?: return@launch
                    RamGovernor.enforceLimit(context, wv)
                    wv.evaluateJavascript(RamGovernor.MEDIA_BUFFER_PRUNING_JS, null)
                    wv.evaluateJavascript(
                        """
                        (function() {
                            try {
                                var lazyEls = document.querySelectorAll('[data-src], [data-original], [data-lazy], [data-lazy-src], [data-url], [data-highres]');
                                for (var i = 0; i < lazyEls.length; i++) {
                                    var el = lazyEls[i];
                                    var candidate = el.getAttribute('data-src') || el.getAttribute('data-original') || el.getAttribute('data-lazy') || el.getAttribute('data-lazy-src') || el.getAttribute('data-url') || el.getAttribute('data-highres');
                                    if (candidate && !el.getAttribute('src')) {
                                        el.setAttribute('src', candidate);
                                    }
                                }
                                window.dispatchEvent(new Event('scroll'));
                            } catch(e) {}
                        })();
                        """.trimIndent(),
                        null
                    )
                    scanCurrentPageDOM(wv)
                    delay(1_200L)
                }
            } finally {
                if (deepScanToken == myScanToken) {
                    isDeepScanning = false
                }
            }
        }
    }

    fun handleDiscoveredMediaClick() {
        if (!hasInitiatedScanInStage) {
            // First tap: Do NOT open the discovery page immediately.
            // On tapping update media counts via explicitly requested 30s deep scan.
            startBrowserDeepScan()
        } else {
            // Tapping discover button again in this stage opens the discovery page.
            stopBrowserDeepScan()

            val wv = webViewInstance
            val site = currentUrl.ifBlank { wv?.url.orEmpty() }

            // Save last scroll position and site before opening discovery page
            val scrollX = wv?.scrollX ?: 0
            val scrollY = wv?.scrollY ?: 0

            BrowserSession.savedBrowserSite = site
            BrowserSession.savedScrollX = scrollX
            BrowserSession.savedScrollY = scrollY
            BrowserSession.pendingScrollRestore = true

            // Also probe JavaScript for high-precision scroll offsets
            wv?.evaluateJavascript(
                "(function() { return JSON.stringify({ x: window.pageXOffset || 0, y: window.pageYOffset || 0 }); })();"
            ) { rawJson ->
                try {
                    if (!rawJson.isNullOrBlank() && rawJson != "null") {
                        val clean = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                            org.json.JSONObject(org.json.JSONTokener(rawJson).nextValue().toString())
                        } else {
                            org.json.JSONObject(rawJson)
                        }
                        val jsX = clean.optInt("x", scrollX)
                        val jsY = clean.optInt("y", scrollY)
                        if (jsY > 0) BrowserSession.savedScrollY = jsY
                        if (jsX > 0) BrowserSession.savedScrollX = jsX
                    }
                } catch (_: Throwable) {}
            }

            // Open discovered media page without killing webpage for super fast instant return
            showDiscoveredMediaOverlay = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopBrowserDeepScan()
        }
    }

    // Reset to idle state and restore scroll position when returning from discovery page
    LaunchedEffect(Unit) {
        if (BrowserSession.pendingScrollRestore && BrowserSession.savedBrowserSite.isNotBlank()) {
            isDeepScanning = false
            hasInitiatedScanInStage = false
            deepScanJob?.cancel()
            deepScanJob = null

            val targetX = BrowserSession.savedScrollX
            val targetY = BrowserSession.savedScrollY

            webViewInstance?.let { wv ->
                wv.postDelayed({
                    wv.scrollTo(targetX, targetY)
                    wv.evaluateJavascript("window.scrollTo($targetX, $targetY);", null)
                }, 100)
                wv.postDelayed({
                    wv.scrollTo(targetX, targetY)
                    wv.evaluateJavascript("window.scrollTo($targetX, $targetY);", null)
                }, 350)
                wv.postDelayed({
                    wv.scrollTo(targetX, targetY)
                    wv.evaluateJavascript("window.scrollTo($targetX, $targetY);", null)
                }, 750)
            }
            BrowserSession.pendingScrollRestore = false
            BrowserSession.savedBrowserSite = ""
        }
    }

    fun navigateToUrl(target: String) {
        val trimmed = target.trim()
        if (trimmed.isBlank()) return
        stopBrowserDeepScan()
        hasInitiatedScanInStage = false
        BrowserSession.pendingScrollRestore = false
        BrowserSession.savedBrowserSite = ""
        scrollHandler.show()
        val formatted = MediaClassifier.formatQueryOrUrl(trimmed)
        tabSession.clearMedia()
        DownloaderSession.clear()
        addressInput = formatted
        currentUrl = formatted
        onUrlChange?.invoke(formatted)
        tabSession.updateNavigation(formatted, pageTitle)
        DownloaderSession.updateBrowserPage(formatted)
        syncCurrentTabWithPage(formatted, pageTitle)
        coroutineScope.launch {
            historyManager.recordVisit(formatted)
        }
        webViewInstance?.loadUrl(formatted)
        focusManager.clearFocus()
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("direct_browser_view"),
        color = BrowserThemeColors.background(isDark)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // The toolbar is a fixed layout section; webpage content begins below it.
            Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(1f)
                    .background(BrowserThemeColors.background(isDark))
                    .testTag("browser_pinned_top_controls")
            ) {
            // 1. Minimalist Monochrome Top Bar (always visible)
            BrowserTopBar(
                addressInput = addressInput,
                onAddressChange = { addressInput = it },
                onNavigate = { navigateToUrl(it) },
                onHamburgerClick = {
                    if (onMenuClick != null) {
                        onMenuClick.invoke()
                    } else {
                        showTabsSheet = true
                    }
                },
                onDiscoveredMediaClick = {
                    handleDiscoveredMediaClick()
                },
                discoveredCount = totalDetectedCount,
                isDeepScanning = isDeepScanning,
                onNewTabClick = {
                    val newId = "tab_${System.currentTimeMillis()}"
                    val newTab = BrowserTabItem(id = newId, title = "New Tab", url = "")
                    hasVisitedBrowserHome = true
                    activeTabId = newId
                    currentUrl = ""
                    addressInput = ""
                    pageTitle = ""
                    onUrlChange?.invoke("")
                    tabSession.clearMedia()
                    tabSession.updateNavigation("")
                    DownloaderSession.clear()
                    scrollHandler.show()
                    webViewInstance?.loadUrl("about:blank")
                    coroutineScope.launch {
                        historyManager.saveTabs(tabsList + newTab)
                    }
                },
                onReloadClick = {
                    if (isLoading) webViewInstance?.stopLoading() else webViewInstance?.reload()
                },
                onPinCurrentSite = {
                    if (currentUrl.isNotBlank()) {
                        coroutineScope.launch {
                            historyManager.addShortcut(pageTitle.ifBlank { currentUrl }, currentUrl)
                        }
                    }
                },
                onSaveToPocket = {
                    if (currentUrl.isNotBlank()) {
                        val pocketRepo = com.amresalehin.emregallery.data.PocketRepository.getInstance(context)
                        val effectiveTitle = pageTitle.ifBlank { webViewInstance?.title ?: currentUrl }
                        
                        // Collect all already fetched / discovered media from the active browser session
                        val currentDetected = tabSession.getDetectedMedia()
                        val currentDiscovered = DownloaderSession.discoveredItems.value
                        val allScraped = (currentDetected + currentDiscovered).distinctBy { it.url }
                        val alreadyFetchedMediaItems = allScraped.map { scraped ->
                            com.amresalehin.emregallery.data.MediaInboxItem(
                                id = scraped.id.ifBlank { "media_${scraped.url.hashCode().toUInt().toString(16)}" },
                                title = scraped.title.ifBlank { effectiveTitle },
                                localUri = "",
                                sourceUrl = scraped.url,
                                isVideo = scraped.isVideo,
                                isAudio = scraped.isAudio,
                                isStream = scraped.isStream,
                                format = scraped.format,
                                sizeBytes = scraped.sizeBytes ?: 0L,
                                dateAddedMillis = scraped.detectedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                                width = scraped.width ?: 0,
                                height = scraped.height ?: 0
                            )
                        }

                        // Save bookmark to Pocket directly without preview saving
                        val bookmark = pocketRepo.addBookmark(
                            title = effectiveTitle,
                            url = currentUrl,
                            description = "",
                            previewImageUrl = "",
                            autoFetchMetadata = true,
                            initialItems = alreadyFetchedMediaItems
                        )

                        // Avoid spawning a contending background WebView while the interactive browser WebView is active;
                        // only trigger background deep scan if no media was captured yet from the live page.
                        if (alreadyFetchedMediaItems.isEmpty()) {
                            scanCurrentPageDOM(webViewInstance)
                        }

                        val initialCount = alreadyFetchedMediaItems.size
                        val message = if (initialCount > 0) {
                            "Saved to Pocket ($initialCount media) • Capturing all media..."
                        } else {
                            "Saved to Pocket • Capturing all media in background..."
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                },
                onShareClick = {
                    if (currentUrl.isNotBlank()) {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, currentUrl)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share link"))
                    }
                },
                onDesktopToggle = { desktop ->
                    isDesktopMode = desktop
                    webViewInstance?.settings?.apply {
                        userAgentString = if (desktop) {
                            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        } else {
                            BotDetectionMitigator.STEALTH_USER_AGENT
                        }
                        useWideViewPort = desktop
                        loadWithOverviewMode = desktop
                    }
                    webViewInstance?.reload()
                },
                isDesktopMode = isDesktopMode,
                onClearHistoryClick = {
                    coroutineScope.launch {
                        historyManager.clearHistory()
                    }
                },
                onSettingsClick = {
                    showSettingsSheet = true
                },
                isWebPage = currentUrl.isNotBlank(),
                onHomeClick = {
                    handleBrowserBackNavigation()
                },
                onCloseBrowser = onClose,
                tabCount = tabsList.size,
                onOpenDownloads = {
                    onOpenDownloads?.invoke() ?: onSwitchToMediaTab()
                },
                onOpenHistory = {
                    showHistorySheet = true
                },
                onOpenTabs = {
                    showTabsSheet = true
                },
                isDark = isDark,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // Loading Progress Bar
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { loadingProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp),
                    color = BrowserThemeColors.textPrimary(isDark),
                    trackColor = BrowserThemeColors.divider(isDark)
                )
            }

            }

            val isChallengeActive = remember(pageTitle, currentUrl) {
                BotDetectionMitigator.isBotChallenge(pageTitle, currentUrl)
            }
            AnimatedVisibility(
                visible = isChallengeActive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)
            ) {
                Surface(
                    color = Color(0xFFFEF3C7),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("browser_bot_challenge_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = Color(0xFF92400E),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Security Verification: Tap captcha checkbox if prompted to continue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF92400E),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }


            // The browser viewport uses the remaining height beneath the toolbar.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Interactive WebView (always instantiated for high-speed responsiveness)
                key(webViewRecreateEpoch) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewInstance = this
                                BrowserSession.activeWebView = this
                            // Software layer avoids missing GPU rendernode crashes in cloud emulators
                            setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
                            }
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                                scrollHandler.onScroll(scrollY, oldScrollY)
                            }

                            setBackgroundColor(if (isDark) android.graphics.Color.parseColor("#111111") else android.graphics.Color.WHITE)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                settings.forceDark = if (isDark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
                            }
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                javaScriptCanOpenWindowsAutomatically = true
                                setSupportMultipleWindows(false)
                                cacheMode = WebSettings.LOAD_DEFAULT
                                builtInZoomControls = true
                                displayZoomControls = false
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                mediaPlaybackRequiresUserGesture = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                userAgentString = BotDetectionMitigator.STEALTH_USER_AGENT
                            }

                            setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                                if (!url.isNullOrBlank()) {
                                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                                    val isVideo = mimetype?.startsWith("video/", ignoreCase = true) == true ||
                                        fileName.endsWith(".mp4", ignoreCase = true) || fileName.endsWith(".webm", ignoreCase = true) ||
                                        fileName.endsWith(".mkv", ignoreCase = true) || fileName.endsWith(".mov", ignoreCase = true)
                                    val isAudio = mimetype?.startsWith("audio/", ignoreCase = true) == true
                                    val item = ScrapedMediaItem(
                                        id = "direct_dl_${System.currentTimeMillis()}_${url.hashCode()}",
                                        url = url,
                                        title = fileName,
                                        isVideo = isVideo,
                                        isAudio = isAudio,
                                        category = if (isVideo) MediaCategory.VIDEOS else if (isAudio) MediaCategory.AUDIO else MediaCategory.IMAGES,
                                        format = mimetype ?: (if (isVideo) "VIDEO" else "MEDIA"),
                                        sizeBytes = if (contentLength > 0L) contentLength else null,
                                        provenance = MediaProvenance(
                                            extractorEngine = "InteractiveBrowser",
                                            pageUrl = currentUrl
                                        ),
                                        downloadHeaders = mapOf(
                                            "User-Agent" to (userAgent ?: BotDetectionMitigator.STEALTH_USER_AGENT),
                                            "Referer" to currentUrl
                                        )
                                    )
                                    DownloadQueueManager.enqueueDownload(
                                        context = context,
                                        item = item,
                                        referer = currentUrl,
                                        sourceUrl = currentUrl
                                    )
                                    Toast.makeText(context, "Downloading $fileName", Toast.LENGTH_SHORT).show()
                                }
                            }

                            addJavascriptInterface(
                                BrowserSession.EmreMediaBridge { rawUrl ->
                                    val item = MediaCaptureEngine.buildMediaItem(
                                        rawUrl = rawUrl,
                                        source = ExtractionSource.DOM_SCRAPE,
                                        baseUrl = currentUrl
                                    )
                                    if (item != null) {
                                        tabSession.registerMedia(item)
                                    }
                                },
                                "EmreBridge"
                            )

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadingProgress = newProgress / 100f
                                    isLoading = newProgress < 100
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    pageTitle = title.orEmpty()
                                    onPageTitleChanged?.invoke(pageTitle)
                                    tabSession.updateNavigation(currentUrl, pageTitle)
                                    if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                                        syncCurrentTabWithPage(currentUrl, pageTitle)
                                    }
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                private fun handleExternalNavigation(view: WebView?, urlString: String): Boolean {
                                    val lower = urlString.trim().lowercase()
                                    if (lower.startsWith("http://") || lower.startsWith("https://") ||
                                        lower.startsWith("about:") || lower.startsWith("data:") ||
                                        lower.startsWith("javascript:") || lower.startsWith("blob:") ||
                                        lower.startsWith("file:///android_asset/")
                                    ) {
                                        return false
                                    }

                                    val context = view?.context ?: return false

                                    if (lower.startsWith("intent://") || lower.startsWith("intent:")) {
                                        try {
                                            val intent = Intent.parseUri(urlString, Intent.URI_INTENT_SCHEME)
                                            if (intent != null) {
                                                // Prevent untrusted pages from launching internal app components or selectors
                                                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                                                intent.component = null
                                                intent.selector = null
                                                if (intent.`package` == context.packageName) {
                                                    return true
                                                }

                                                val pm = context.packageManager
                                                val resolveComponent = intent.resolveActivity(pm)
                                                if (resolveComponent != null &&
                                                    resolveComponent.packageName != context.packageName
                                                ) {
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    context.startActivity(intent)
                                                    return true
                                                }

                                                val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                                if (!fallbackUrl.isNullOrBlank() &&
                                                    (fallbackUrl.startsWith("http://", ignoreCase = true) || fallbackUrl.startsWith("https://", ignoreCase = true))
                                                ) {
                                                    view?.loadUrl(fallbackUrl)
                                                    return true
                                                }

                                                val packageName = intent.`package`
                                                if (!packageName.isNullOrBlank()) {
                                                    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    val marketComponent = marketIntent.resolveActivity(pm)
                                                    if (marketComponent != null &&
                                                        marketComponent.packageName != context.packageName
                                                    ) {
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
                                        return true
                                    }

                                    // Block unsafe internal URI schemes from untrusted web pages
                                    if (lower.startsWith("file:") || lower.startsWith("content:") || lower.startsWith("android-app:")) {
                                        return true
                                    }

                                    try {
                                        val uri = Uri.parse(urlString)
                                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                            addCategory(Intent.CATEGORY_BROWSABLE)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        val pm = context.packageManager
                                        val resolveComponent = intent.resolveActivity(pm)
                                        if (resolveComponent != null &&
                                            resolveComponent.packageName != context.packageName
                                        ) {
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

                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val reqUrl = request?.url?.toString() ?: return false
                                    if (handleExternalNavigation(view, reqUrl)) return true
                                    val decision = RedirectProtectionEngine.evaluateNavigation(
                                        currentPageUrl = currentUrl,
                                        targetUrl = reqUrl,
                                        isServerRedirect = request.isRedirect,
                                        hasUserGesture = request.hasGesture(),
                                        enabled = redirectProtectionEnabled,
                                        tapsToOpen = redirectTapsToOpen,
                                        hideBlockedElements = hideBlockedElements
                                    )
                                    if (!decision.allowed) {
                                        if (decision.shouldHideClickedElement) {
                                            view?.evaluateJavascript(RedirectProtectionEngine.getHideLastClickedElementScript(), null)
                                        }
                                        decision.reason?.let { msg ->
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                        return true
                                    }
                                    return false
                                }

                                @Suppress("DEPRECATION")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    if (url.isNullOrBlank()) return false
                                    if (handleExternalNavigation(view, url)) return true
                                    val decision = RedirectProtectionEngine.evaluateNavigation(
                                        currentPageUrl = currentUrl,
                                        targetUrl = url,
                                        enabled = redirectProtectionEnabled,
                                        tapsToOpen = redirectTapsToOpen,
                                        hideBlockedElements = hideBlockedElements
                                    )
                                    if (!decision.allowed) {
                                        if (decision.shouldHideClickedElement) {
                                            view?.evaluateJavascript(RedirectProtectionEngine.getHideLastClickedElementScript(), null)
                                        }
                                        decision.reason?.let { msg ->
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    scrollHandler.show()
                                    if (url.isNullOrBlank() || url == "about:blank") {
                                        tabSession.clearMedia()
                                        tabSession.updateNavigation("")
                                        DownloaderSession.clear()
                                        isLoading = false
                                        canGoBack = false
                                        canGoForward = false
                                        syncCurrentTabWithPage("", "Home")
                                        return
                                    }
                                    tabSession.clearMedia()
                                    DownloaderSession.clear()
                                    isLoading = true
                                    BotDetectionMitigator.injectStealth(view)
                                    currentUrl = url
                                    addressInput = url
                                    onUrlChange?.invoke(url)
                                    tabSession.updateNavigation(url, pageTitle)
                                    DownloaderSession.updateBrowserPage(url)
                                    syncCurrentTabWithPage(url, pageTitle)
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    if (url.isNullOrBlank() || url == "about:blank") {
                                        tabSession.updateNavigation("")
                                        canGoBack = false
                                        canGoForward = false
                                        syncCurrentTabWithPage("", "Home")
                                        return
                                    }
                                    BotDetectionMitigator.injectStealth(view)
                                    BotDetectionMitigator.syncCookiesFromWebView(url)
                                    view?.evaluateJavascript(RamGovernor.MEDIA_BUFFER_PRUNING_JS, null)
                                    RamGovernor.enforceLimit(context, view)
                                    if (redirectProtectionEnabled && hideBlockedElements) {
                                        view?.evaluateJavascript(RedirectProtectionEngine.getTrackClickTargetScript(), null)
                                    }
                                    currentUrl = url
                                    addressInput = url
                                    onUrlChange?.invoke(url)
                                    val finalTitle = view?.title.orEmpty().ifBlank { pageTitle }
                                    pageTitle = finalTitle
                                    tabSession.updateNavigation(url, finalTitle)
                                    syncCurrentTabWithPage(url, finalTitle)
                                    coroutineScope.launch {
                                        historyManager.recordVisit(url, view?.title)
                                    }
                                    if (BrowserSession.pendingScrollRestore && BrowserSession.savedBrowserSite.isNotBlank() &&
                                        url?.equals(BrowserSession.savedBrowserSite, ignoreCase = true) == true) {
                                        val targetX = BrowserSession.savedScrollX
                                        val targetY = BrowserSession.savedScrollY
                                        view?.postDelayed({
                                            view.scrollTo(targetX, targetY)
                                            view.evaluateJavascript("window.scrollTo($targetX, $targetY);", null)
                                        }, 150)
                                        view?.postDelayed({
                                            view.scrollTo(targetX, targetY)
                                            view.evaluateJavascript("window.scrollTo($targetX, $targetY);", null)
                                        }, 500)
                                        BrowserSession.pendingScrollRestore = false
                                        BrowserSession.savedBrowserSite = ""
                                        isDeepScanning = false
                                        hasInitiatedScanInStage = false
                                    }
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val reqUrl = request?.url?.toString() ?: return null
                                    if (reqUrl.startsWith("about:")) return null

                                    // Ad & tracker blocking when enabled
                                    if (adBlockEnabled && AdBlockEngine.isAdOrTracker(reqUrl)) {
                                        return AdBlockEngine.createEmptyResponse()
                                    }

                                    // Live background network media interception (Videos, Audio, Streams, Images, Subtitles, Files)
                                    val activePage = currentUrl
                                    if (activePage.isNotBlank() && activePage != "about:blank") {
                                        return MediaCaptureEngine.shouldInterceptRequest(
                                            request = request,
                                            baseUrl = activePage,
                                            onMediaDiscovered = { item ->
                                                tabSession.registerMedia(item)
                                                DownloaderSession.addDiscoveredItem(item)
                                                onMediaExtracted(item)
                                            }
                                        )
                                    }

                                    return null
                                }

                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?
                                ): Boolean {
                                    val didCrash = detail?.didCrash() ?: false
                                    val now = System.currentTimeMillis()
                                    val isRapidCrashLoop = (now - lastRendererCrashMs) < 8_000L
                                    lastRendererCrashMs = now
                                    Log.w("DirectBrowserView", "Chromium renderer process gone (didCrash=$didCrash, rapidLoop=$isRapidCrashLoop). Cleanly recovering...")
                                    stopBrowserDeepScan()
                                    try {
                                        view?.let { wv ->
                                            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                                            wv.destroy()
                                        }
                                    } catch (_: Throwable) {}
                                    if (webViewInstance === view) {
                                        webViewInstance = null
                                    }
                                    if (BrowserSession.activeWebView === view) {
                                        BrowserSession.activeWebView = null
                                    }
                                    if (isRapidCrashLoop) {
                                        currentUrl = ""
                                        addressInput = ""
                                    }
                                    RamGovernor.enforceLimit(context, null)
                                    webViewRecreateEpoch++
                                    return true
                                }
                            }

                            val initialToLoad = currentUrl.ifBlank { startUrl }
                            if (initialToLoad.isNotBlank() && initialToLoad != "about:blank") {
                                loadUrl(initialToLoad)
                            }
                        }
                    },
                    update = { wv ->
                        webViewInstance = wv
                        BrowserSession.activeWebView = wv
                        if (wv.layerType != android.view.View.LAYER_TYPE_SOFTWARE) {
                            wv.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                        }
                        wv.setBackgroundColor(if (isDark) android.graphics.Color.parseColor("#111111") else android.graphics.Color.WHITE)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            wv.settings.forceDark = if (isDark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
                        }
                    },
                    onRelease = { wv ->
                        wv.stopLoading()
                        wv.loadUrl("about:blank")
                        wv.onPause()
                        wv.webViewClient = WebViewClient()
                        wv.webChromeClient = WebChromeClient()
                        wv.post {
                            try { wv.destroy() } catch (_: Throwable) {}
                        }
                        if (webViewInstance === wv) {
                            webViewInstance = null
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("browser_webview_canvas")
                )
            }

                // 2. Minimalist Monochrome Mobile Browser Home Layout (blank when empty)
                if (currentUrl.isBlank()) {
                    BrowserHomeContent(
                        shortcuts = shortcutsList,
                        recentSites = allHistorySites,
                        onNavigate = { navigateToUrl(it) },
                        onAddShortcutClick = { showAddShortcutDialog = true },
                        onEditShortcutClick = { selectedShortcutForEdit = it },
                        onSeeAllHistoryClick = { showHistorySheet = true },
                        isDark = isDark,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("browser_empty_home_page")
                    )
                }
            }
        }

        // Fullscreen Discovered Media Overlay (keeps underlying WebView alive for instant return & deep scanning)
        if (showDiscoveredMediaOverlay) {
            DiscoveredMediaPage(
                onBack = {
                    showDiscoveredMediaOverlay = false
                    stopBrowserDeepScan()
                    hasInitiatedScanInStage = false
                },
                onDeepScanClick = {
                    if (isDeepScanning) {
                        stopBrowserDeepScan()
                    } else {
                        startBrowserDeepScan()
                    }
                },
                isDeepScanning = isDeepScanning,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(100f)
            )
        }
    }
}

    // Modal Dialogs & Sheets
    if (showAddShortcutDialog) {
        AddShortcutDialog(
            onDismiss = { showAddShortcutDialog = false },
            onAdd = { title, url ->
                coroutineScope.launch {
                    historyManager.addShortcut(title, url)
                }
            },
            isDark = isDark
        )
    }

    selectedShortcutForEdit?.let { sc ->
        EditShortcutDialog(
            shortcut = sc,
            onDismiss = { selectedShortcutForEdit = null },
            onUpdate = { title, url ->
                coroutineScope.launch {
                    historyManager.updateShortcut(sc.id, title, url)
                }
            },
            onDelete = {
                coroutineScope.launch {
                    historyManager.removeShortcut(sc.id)
                }
            },
            isDark = isDark
        )
    }

    if (showHistorySheet) {
        BrowserHistoryBottomSheet(
            history = allHistorySites,
            onNavigate = { navigateToUrl(it) },
            onDeleteSite = { url ->
                coroutineScope.launch {
                    historyManager.deleteSite(url)
                }
            },
            onClearAll = {
                coroutineScope.launch {
                    historyManager.clearHistory()
                }
            },
            onDismiss = { showHistorySheet = false },
            isDark = isDark
        )
    }

    if (showTabsSheet) {
        BrowserTabsBottomSheet(
            tabs = tabsList,
            currentUrl = currentUrl,
            activeTabId = activeTabId,
            onSelectTab = { tab ->
                activeTabId = tab.id
                currentUrl = tab.url
                addressInput = tab.url
                pageTitle = tab.title
                onUrlChange?.invoke(tab.url)
                tabSession.clearMedia()
                tabSession.updateNavigation(tab.url, tab.title)
                DownloaderSession.clear()
                scrollHandler.show()
                if (tab.url.isNotBlank()) {
                    webViewInstance?.loadUrl(tab.url)
                } else {
                    hasVisitedBrowserHome = true
                    webViewInstance?.loadUrl("about:blank")
                }
            },
            onCloseTab = { tabId ->
                val remaining = tabsList.filter { it.id != tabId }
                if (remaining.isEmpty()) {
                    val freshId = "tab_${System.currentTimeMillis()}"
                    val freshTab = BrowserTabItem(id = freshId, title = "New Tab", url = "")
                    hasVisitedBrowserHome = true
                    activeTabId = freshId
                    currentUrl = ""
                    addressInput = ""
                    pageTitle = ""
                    onUrlChange?.invoke("")
                    tabSession.clearMedia()
                    tabSession.updateNavigation("")
                    DownloaderSession.clear()
                    scrollHandler.show()
                    webViewInstance?.loadUrl("about:blank")
                    coroutineScope.launch {
                        historyManager.saveTabs(listOf(freshTab))
                    }
                } else {
                    if (activeTabId == tabId) {
                        val nextTab = remaining.last()
                        activeTabId = nextTab.id
                        currentUrl = nextTab.url
                        addressInput = nextTab.url
                        pageTitle = nextTab.title
                        onUrlChange?.invoke(nextTab.url)
                        tabSession.clearMedia()
                        tabSession.updateNavigation(nextTab.url, nextTab.title)
                        DownloaderSession.clear()
                        scrollHandler.show()
                        if (nextTab.url.isNotBlank()) {
                            webViewInstance?.loadUrl(nextTab.url)
                        } else {
                            hasVisitedBrowserHome = true
                            webViewInstance?.loadUrl("about:blank")
                        }
                    }
                    coroutineScope.launch {
                        historyManager.saveTabs(remaining)
                    }
                }
            },
            onNewTab = {
                val newId = "tab_${System.currentTimeMillis()}"
                val newTab = BrowserTabItem(id = newId, title = "New Tab", url = "")
                hasVisitedBrowserHome = true
                activeTabId = newId
                currentUrl = ""
                addressInput = ""
                pageTitle = ""
                onUrlChange?.invoke("")
                tabSession.clearMedia()
                tabSession.updateNavigation("")
                DownloaderSession.clear()
                scrollHandler.show()
                webViewInstance?.loadUrl("about:blank")
                coroutineScope.launch {
                    historyManager.saveTabs(tabsList + newTab)
                }
            },
            onDismiss = { showTabsSheet = false },
            isDark = isDark
        )
    }

    if (showSettingsSheet) {
        BrowserSettingsBottomSheet(
            isDesktopMode = isDesktopMode,
            onDesktopModeChange = { desktop ->
                isDesktopMode = desktop
                webViewInstance?.settings?.apply {
                    userAgentString = if (desktop) {
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    } else {
                        BotDetectionMitigator.STEALTH_USER_AGENT
                    }
                    useWideViewPort = desktop
                    loadWithOverviewMode = desktop
                }
                webViewInstance?.reload()
            },
            onClearCacheClick = {
                webViewInstance?.clearCache(true)
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                RedirectProtectionEngine.clearHistory()
            },
            onDismiss = { showSettingsSheet = false },
            isDark = isDark,
            adBlockEnabled = adBlockEnabled,
            onAdBlockChange = { enabled ->
                coroutineScope.launch { downloaderPrefs.setAdBlockEnabled(enabled) }
            },
            redirectProtectionEnabled = redirectProtectionEnabled,
            onRedirectProtectionChange = { enabled ->
                coroutineScope.launch { downloaderPrefs.setRedirectProtectionEnabled(enabled) }
            },
            redirectTapsToOpen = redirectTapsToOpen,
            onRedirectTapsChange = { taps ->
                coroutineScope.launch { downloaderPrefs.setRedirectTapsToOpen(taps) }
            },
            hideBlockedElements = hideBlockedElements,
            onHideBlockedElementsChange = { hide ->
                coroutineScope.launch { downloaderPrefs.setHideBlockedRedirectElements(hide) }
            },
            speedBoostEnabled = speedBoostEnabled,
            onSpeedBoostChange = { enabled ->
                coroutineScope.launch { downloaderPrefs.setSpeedBoostEnabled(enabled) }
            },
            speedBoostConnections = speedBoostConnections,
            onSpeedBoostConnectionsChange = { conns ->
                coroutineScope.launch { downloaderPrefs.setSpeedBoostConnections(conns) }
            },
            videoQuality = videoQuality,
            onVideoQualityChange = { q ->
                coroutineScope.launch { downloaderPrefs.setVideoQualityCap(q) }
            },
            formatMode = formatMode,
            onFormatModeChange = { mode ->
                coroutineScope.launch { downloaderPrefs.setFormatMode(mode) }
            },
            downloadPlaylist = downloadPlaylist,
            onDownloadPlaylistChange = { enabled ->
                coroutineScope.launch { downloaderPrefs.setDownloadPlaylist(enabled) }
            },
            downloadSubtitles = downloadSubtitles,
            onDownloadSubtitlesChange = { enabled ->
                coroutineScope.launch { downloaderPrefs.setDownloadSubtitles(enabled) }
            },
            includeAutoSubtitles = includeAutoSubtitles,
            onIncludeAutoSubtitlesChange = { enabled ->
                coroutineScope.launch { downloaderPrefs.setIncludeAutoSubtitles(enabled) }
            },
            subtitleLanguages = subtitleLanguages,
            onSubtitleLanguagesChange = { langs ->
                coroutineScope.launch { downloaderPrefs.setSubtitleLanguages(langs) }
            },
            proxyEnabled = proxyEnabled,
            proxyUrl = proxyUrl,
            onProxyConfigChange = { enabled, url ->
                coroutineScope.launch { downloaderPrefs.setProxyConfig(enabled, url) }
            },
            onOpenNetworkStatus = {
                showSettingsSheet = false
                showNetworkStatusDialog = true
            },
            recursiveCrawlEnabled = recursiveCrawlEnabled,
            onRecursiveCrawlEnabledChange = { enabled ->
                coroutineScope.launch { downloaderPrefs.setRecursiveCrawlEnabled(enabled) }
            },
            recursiveCrawlMaxDepth = recursiveCrawlMaxDepth,
            onRecursiveCrawlMaxDepthChange = { depth ->
                coroutineScope.launch { downloaderPrefs.setRecursiveCrawlMaxDepth(depth) }
            },
            recursiveCrawlMaxPages = recursiveCrawlMaxPages,
            onRecursiveCrawlMaxPagesChange = { pages ->
                coroutineScope.launch { downloaderPrefs.setRecursiveCrawlMaxPages(pages) }
            },
            recursiveCrawlSameDomainOnly = recursiveCrawlSameDomainOnly,
            onRecursiveCrawlSameDomainOnlyChange = { sameDomain ->
                coroutineScope.launch { downloaderPrefs.setRecursiveCrawlSameDomainOnly(sameDomain) }
            },
            saveCrawlSitemap = saveCrawlSitemap,
            onSaveCrawlSitemapChange = { save ->
                coroutineScope.launch { downloaderPrefs.setSaveCrawlSitemap(save) }
            }
        )
    }

    if (showNetworkStatusDialog) {
        NetworkStatusDialog(onDismiss = { showNetworkStatusDialog = false })
    }
}
