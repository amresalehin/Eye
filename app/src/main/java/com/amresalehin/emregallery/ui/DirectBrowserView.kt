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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.amresalehin.emregallery.data.AdBlockEngine
import com.amresalehin.emregallery.data.BrowserHistoryManager
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

    val startUrl = remember {
        val trimmed = initialUrl.trim()
        if (trimmed.isNotBlank()) {
            MediaClassifier.formatQueryOrUrl(trimmed)
        } else {
            "" // Empty home page by default
        }
    }

    var currentUrl by remember { mutableStateOf(startUrl) }
    var addressInput by remember { mutableStateOf(startUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val loadingProgressState = remember { mutableFloatStateOf(0f) }
    var browserMenuExpanded by remember { mutableStateOf(false) }
    var isDesktopMode by remember { mutableStateOf(false) }
    var isAddressBarFocused by remember { mutableStateOf(false) }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
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

            val builtItems = withContext(Dispatchers.Default) {
                val cookieCache = HashMap<String, String>(4)
                val built = ArrayList<ScrapedMediaItem>(batch.size)
                for ((idx, cand) in batch.withIndex()) {
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

            if (builtItems.isNotEmpty()) {
                val title = pageTitleRef.get().ifBlank { null }
                val domain = runCatching { URL(activeBaseUrlRef.get()).host }.getOrDefault("")
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
                wv.stopLoading()
                wv.webViewClient = WebViewClient()
                wv.webChromeClient = WebChromeClient()
                wv.destroy()
                webViewInstance = null
            }
        }
    }

    // Intercept back button when browser can navigate back
    BackHandler(enabled = isActive && canGoBack && !isAddressBarFocused) {
        webViewInstance?.goBack()
    }

    fun notifyUrlIfChanged(newUrl: String) {
        activeBaseUrlRef.set(newUrl)
        if (newUrl != lastNotifiedUrl) {
            lastNotifiedUrl = newUrl
            onUrlChange?.invoke(newUrl)
        }
    }

    fun navigateToUrl(target: String) {
        val trimmed = target.trim()
        if (trimmed.isBlank()) return
        val formatted = MediaClassifier.formatQueryOrUrl(trimmed)
        if (formatted != currentUrl) {
            seenRawUrls.clear()
            lastScannedDomUrl = ""
        }
        addressInput = formatted
        currentUrl = formatted
        notifyUrlIfChanged(formatted)
        if (formatted != lastRecordedHistoryUrl) {
            lastRecordedHistoryUrl = formatted
            coroutineScope.launch {
                historyManager.recordVisit(formatted)
            }
        }
        webViewInstance?.loadUrl(formatted)
        focusManager.clearFocus()
    }

    LaunchedEffect(initialUrl, navigationRequestId) {
        val requested = initialUrl.trim()
        if (requested.isNotBlank() &&
            MediaClassifier.formatQueryOrUrl(requested) != currentUrl
        ) {
            navigateToUrl(requested)
        }
    }

    fun scanCurrentPageDOM(wv: WebView?) {
        if (wv == null) return
        val current = wv.url ?: return
        if (current.isBlank() || current == "about:blank" || currentUrl.isBlank()) return

        wv.evaluateJavascript(MediaCaptureEngine.getJsDomScanScript()) { jsonRaw ->
            if (jsonRaw.isNullOrBlank() || jsonRaw == "null") return@evaluateJavascript
            coroutineScope.launch {
                val discovered = withContext(Dispatchers.Default) {
                    MediaCaptureEngine.parseJsDomScanResult(jsonRaw, current)
                }
                if (discovered.isNotEmpty()) {
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
            .testTag("direct_browser_view")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Full-width browser search pill, matching the app's standard top-bar treatment.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
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
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to app",
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

                        // URL and search input are integrated directly into the same pill.
                        val isSecure = currentUrl.startsWith("https://", ignoreCase = true)
                        Icon(
                            imageVector = if (isSecure) Icons.Default.Lock
                            else if (currentUrl.isNotBlank()) Icons.Default.Language
                            else Icons.Default.Search,
                            contentDescription = null,
                            tint = if (isSecure) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(7.dp))

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

                        if (addressInput.isNotEmpty()) {
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

                        IconButton(
                            onClick = {
                                if (isLoading) webViewInstance?.stopLoading()
                                else webViewInstance?.reload()
                            },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("browser_refresh_btn")
                        ) {
                            Icon(
                                imageVector = if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = if (isLoading) "Stop" else "Reload",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        if (discoveredCount > 0) {
                            Surface(
                                onClick = onSwitchToMediaTab,
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier
                                    .height(32.dp)
                                    .testTag("browser_media_badge_top")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PermMedia,
                                        contentDescription = "Discovered media",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "$discoveredCount",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
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
                                    val nextDesktop = !isDesktopMode
                                    isDesktopMode = nextDesktop
                                    webViewInstance?.let { wv ->
                                        wv.settings.userAgentString = if (nextDesktop) {
                                            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                                        } else {
                                            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                                        }
                                        if (currentUrl.isNotBlank()) {
                                            wv.reload()
                                        }
                                    }
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
                        }
                    }
                    }
                }
            }

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
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewInstance = this
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                cacheMode = WebSettings.LOAD_DEFAULT
                                builtInZoomControls = true
                                displayZoomControls = false
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                mediaPlaybackRequiresUserGesture = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                userAgentString = OkHttpDownloader.DEFAULT_USER_AGENT
                            }

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
                                        onPageTitleChanged?.invoke(resolvedTitle)
                                    }
                                }
                            }

                            webViewClient = object : WebViewClient() {
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
                                                if (seenRawUrls.size > 1500) {
                                                    seenRawUrls.clear()
                                                }
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

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
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
                                            if (it != lastRecordedHistoryUrl) {
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

                                    if (AdBlockEngine.isAdOrTracker(reqUrl, uri.host)) {
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

                            if (startUrl.isNotBlank()) {
                                loadUrl(startUrl)
                            } else {
                                loadUrl("about:blank")
                            }
                        }
                    },
                    update = { wv ->
                        if (webViewInstance !== wv) {
                            webViewInstance = wv
                        }
                    },
                    onRelease = { wv ->
                        wv.stopLoading()
                        wv.webViewClient = WebViewClient()
                        wv.webChromeClient = WebChromeClient()
                        wv.destroy()
                        if (webViewInstance === wv) {
                            webViewInstance = null
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("browser_webview_canvas")
                )

                if (currentUrl.isBlank()) {
                    BrowserEmptyHomePage(
                        onNavigate = { target ->
                            navigateToUrl(target)
                        },
                        onRemoveSavedSite = { site ->
                            coroutineScope.launch { historyManager.removeSavedSite(site.url) }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .testTag("browser_empty_home_page")
                    )
                }
            }
        }
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val historyManager = remember { BrowserHistoryManager(context) }
    val allHistorySites by historyManager.allSites.collectAsState(initial = emptyList())
    val savedSites by historyManager.savedSites.collectAsState(initial = emptyList())

    val shortcutSites = remember(allHistorySites) {
        allHistorySites.sortedWith(
            compareByDescending<BrowserSite> { it.visitCount }
                .thenByDescending { it.lastVisitedMillis }
        ).take(5)
    }
    val recentSites = remember(allHistorySites) {
        allHistorySites.sortedByDescending { it.lastVisitedMillis }.take(8)
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

        Spacer(Modifier.height(12.dp))
    }
}
