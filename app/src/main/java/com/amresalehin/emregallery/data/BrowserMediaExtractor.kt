package com.amresalehin.emregallery.data

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import androidx.annotation.Keep
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URL
import java.util.Locale

/**
 * Chromium WebView-assisted media extraction engine.
 * Powered by unified MediaCaptureEngine:
 * 1. Live network request interception.
 * 2. Deep JavaScript DOM, computed CSS, srcset, schema.org JSON-LD.
 * 3. MutationObserver dynamic DOM change detection.
 * 4. Adaptive viewport scrolling with dynamic content expansion support.
 * 5. Layout with real viewport dimensions, error resilience, anti-bot protection awareness, and non-premature settling watchdog.
 */
class BrowserMediaExtractor(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val seenCandidateUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val blobCaptureStore = BrowserBlobCaptureStore(context)
    private var webView: WebView? = null
    @Volatile
    private var isCancelled = false

    companion object {
        private val mediaProcessingExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "BrowserMediaExtractor-Worker").apply { isDaemon = true }
        }
    }

    private fun findActivity(ctx: Context): Activity? {
        var current: Context? = ctx
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun startLiveExtraction(
        targetUrl: String,
        onStatusUpdate: (String) -> Unit,
        onMediaDiscovered: (ScrapedMediaItem) -> Unit,
        onFinished: (failureReason: String?) -> Unit
    ) {
        mainHandler.post {
            cleanup()
            isCancelled = false

            val normalizedUrl = if (!targetUrl.startsWith("http://", ignoreCase = true) && !targetUrl.startsWith("https://", ignoreCase = true)) {
                "https://$targetUrl"
            } else {
                targetUrl
            }

            val domain = runCatching { URL(normalizedUrl).host }.getOrDefault("")
            onStatusUpdate("Initializing Chromium browser engine...")

            try {
                val act = findActivity(context)
                val wv = if (act != null) WebView(act) else WebView(context)
                webView = wv

                // Sync cookies bidirectionally with CookieManager before navigation
                try {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(wv, true)
                    val existingCookieStr = OkHttpDownloader.sharedCookieJar.getCookieString(normalizedUrl)
                    if (existingCookieStr.isNotBlank()) {
                        for (c in existingCookieStr.split(";")) {
                            val trimmed = c.trim()
                            if (trimmed.isNotEmpty()) {
                                cookieManager.setCookie(normalizedUrl, trimmed)
                            }
                        }
                        cookieManager.flush()
                    }
                } catch (_: Throwable) {}

                // Measure and layout so modern DOM scripts have real viewport dimensions
                val displayMetrics = context.resources.displayMetrics
                val viewWidth = if (displayMetrics.widthPixels > 0) displayMetrics.widthPixels else 1080
                val viewHeight = if (displayMetrics.heightPixels > 0) displayMetrics.heightPixels else 1920

                if (act != null && !act.isFinishing && !act.isDestroyed) {
                    val root = act.findViewById<ViewGroup>(android.R.id.content)
                    if (root != null) {
                        wv.alpha = 0.001f
                        wv.translationX = 0f
                        wv.translationY = 0f
                        wv.isClickable = false
                        wv.isFocusable = false
                        val lp = ViewGroup.LayoutParams(viewWidth, viewHeight)
                        try {
                            root.addView(wv, 0, lp)
                        } catch (_: Throwable) {
                            root.addView(wv, lp)
                        }
                    }
                }

                wv.measure(
                    View.MeasureSpec.makeMeasureSpec(viewWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(viewHeight, View.MeasureSpec.EXACTLY)
                )
                wv.layout(0, 0, viewWidth, viewHeight)

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mediaPlaybackRequiresUserGesture = false
                    loadsImagesAutomatically = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = OkHttpDownloader.DEFAULT_USER_AGENT
                    cacheMode = WebSettings.LOAD_DEFAULT
                    javaScriptCanOpenWindowsAutomatically = true
                }

                // A newly-created extraction WebView is not globally paused. Avoid resumeTimers(),
                // which changes timer state for every WebView in the process.
                wv.onResume()

                val seenMap = mutableMapOf<String, ScrapedMediaItem>()
                var isExtractionCompleted = false
                var pageFinishedCount = 0
                var detectedProtectionReason: String? = null
                var pendingGracePeriodRunnable: Runnable? = null
                var blobWaitFallbackRunnable: Runnable? = null
                var isWaitingForBlobCaptures = false

                fun syncCookies() {
                    try {
                        val cm = CookieManager.getInstance()
                        cm.flush()
                        val latestCookieStr = cm.getCookie(normalizedUrl)
                        if (!latestCookieStr.isNullOrBlank()) {
                            val httpUrl = normalizedUrl.toHttpUrlOrNull()
                            if (httpUrl != null) {
                                val parsed = latestCookieStr.split(";").mapNotNull { part ->
                                    val trimmed = part.trim()
                                    if (trimmed.isNotEmpty()) Cookie.parse(httpUrl, trimmed) else null
                                }
                                if (parsed.isNotEmpty()) {
                                    OkHttpDownloader.sharedCookieJar.saveFromResponse(httpUrl, parsed)
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }

                fun deliverCompletion(reason: String?) {
                    if (isCancelled || isExtractionCompleted) return
                    isWaitingForBlobCaptures = false
                    blobWaitFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
                    blobWaitFallbackRunnable = null
                    isExtractionCompleted = true
                    pendingGracePeriodRunnable?.let { mainHandler.removeCallbacks(it) }
                    syncCookies()
                    onFinished(reason)
                }

                fun safeComplete(reason: String? = null) {
                    if (isExtractionCompleted || isCancelled || isWaitingForBlobCaptures) return
                    isWaitingForBlobCaptures = true

                    // Blob data is transferred asynchronously in bounded chunks. Give active
                    // captures time to finish before destroying the renderer and its object URLs.
                    val waitForBlobsScript = """
                        (async function() {
                            try {
                                if (window.__emreWaitForBlobCaptures) {
                                    await window.__emreWaitForBlobCaptures(30000);
                                }
                            } catch(e) {}
                            return true;
                        })();
                    """.trimIndent()

                    val fallback = Runnable { deliverCompletion(reason) }
                    blobWaitFallbackRunnable = fallback
                    mainHandler.postDelayed(fallback, 35000L)

                    wv.evaluateJavascript(waitForBlobsScript) {
                        deliverCompletion(reason)
                    }
                }

                fun dispatchMediaItem(
                    rawUrl: String,
                    source: ExtractionSource,
                    poster: String? = null,
                    resolution: String? = null,
                    mimeHint: String? = null
                ) {
                    if (isCancelled) return
                    val currentCount = synchronized(seenMap) { seenMap.size }
                    val candidateItem = MediaCaptureEngine.buildMediaItem(
                        rawUrl = rawUrl,
                        source = source,
                        baseUrl = normalizedUrl,
                        poster = poster,
                        resolutionHint = resolution,
                        mimeHint = mimeHint,
                        indexHint = currentCount
                    ) ?: return

                    val key = MediaClassifier.canonicalKey(candidateItem.url)
                    synchronized(seenMap) {
                        val existing = seenMap[key]
                        if (existing != null && MediaClassifier.compareMediaQuality(candidateItem, existing) <= 0) {
                            return
                        }
                        seenMap[key] = if (existing != null) {
                            candidateItem.copy(
                                thumbnailUrl = candidateItem.thumbnailUrl ?: existing.thumbnailUrl,
                                durationMillis = candidateItem.durationMillis ?: existing.durationMillis
                            )
                        } else candidateItem
                    }

                    DownloaderSession.addDiscoveredItem(candidateItem)
                    mainHandler.post {
                        if (!isCancelled) {
                            onMediaDiscovered(candidateItem)
                        }
                    }
                }

                fun dispatchCapturedBlobItem(item: ScrapedMediaItem) {
                    if (isCancelled || !seenCandidateUrls.add(item.url)) return
                    val key = MediaClassifier.canonicalKey(item.url)
                    val accepted = synchronized(seenMap) {
                        val existing = seenMap[key]
                        if (existing != null && MediaClassifier.compareMediaQuality(item, existing) <= 0) false
                        else {
                            seenMap[key] = item
                            true
                        }
                    }
                    if (!accepted) return
                    DownloaderSession.addDiscoveredItem(item)
                    mainHandler.post {
                        if (!isCancelled) onMediaDiscovered(item)
                    }
                }

                wv.addJavascriptInterface(
                    EmreMediaBridge(
                        onMediaFound = { rawUrl, mimeHint ->
                            if (!isCancelled && seenCandidateUrls.size < 2500 && seenCandidateUrls.add(rawUrl)) {
                                mediaProcessingExecutor.execute {
                                    if (!isCancelled) {
                                        dispatchMediaItem(rawUrl, ExtractionSource.DOM_SCRAPE, mimeHint = mimeHint)
                                    }
                                }
                            }
                        },
                        blobCaptureStore = blobCaptureStore,
                        onBlobCaptured = { item ->
                            // finishBlobCapture is invoked only after every byte is written;
                            // merge immediately so provider cleanup cannot race a queued callback.
                            if (!isCancelled) dispatchCapturedBlobItem(item)
                        }
                    ),
                    "EmreBridge"
                )

                wv.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        if (isCancelled) return
                        if (newProgress < 100) {
                            onStatusUpdate("Loading page: $newProgress% (monitoring network requests)...")
                        }
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        if (isCancelled) return
                        val t = (title ?: "").lowercase(Locale.ROOT)
                        if (t.contains("just a moment") || t.contains("challenge") ||
                            t.contains("security check") || t.contains("turnstile") ||
                            t.contains("are you human") || t.contains("bot verification") ||
                            t.contains("aws waf") || t.contains("cloudflare") || t.contains("ddos-guard")
                        ) {
                            detectedProtectionReason = "Verification challenge detected ($title)"
                            onStatusUpdate(detectedProtectionReason!!)
                        }
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        if (isCancelled || request == null) return null
                        val uri = request.url ?: return null
                        val reqUrl = uri.toString()
                        if (AdBlockEngine.isAdOrTracker(reqUrl, uri.host)) {
                            return AdBlockEngine.createEmptyResponse()
                        }
                        val candidateUrl = MediaCaptureEngine.matchCandidateMediaUrl(request)
                        if (candidateUrl != null && seenCandidateUrls.size < 2500 && seenCandidateUrls.add(candidateUrl)) {
                            mediaProcessingExecutor.execute {
                                if (!isCancelled) {
                                    dispatchMediaItem(candidateUrl, ExtractionSource.NETWORK_INTERCEPT)
                                }
                            }
                        }
                        return null
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val reqUrl = request?.url?.toString() ?: return false
                        val lower = reqUrl.lowercase(Locale.ROOT)
                        if (!lower.startsWith("http://") && !lower.startsWith("https://") &&
                            !lower.startsWith("about:") && !lower.startsWith("data:")
                        ) {
                            return true
                        }
                        return false
                    }

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        val reqUrl = url ?: return false
                        val lower = reqUrl.lowercase(Locale.ROOT)
                        if (!lower.startsWith("http://") && !lower.startsWith("https://") &&
                            !lower.startsWith("about:") && !lower.startsWith("data:")
                        ) {
                            return true
                        }
                        return false
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        Log.w("BrowserMediaExtractor", "Page error: $errorCode $description on $failingUrl")
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            Log.w("BrowserMediaExtractor", "Main frame error: ${error?.errorCode} ${error?.description}")
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        if (request?.isForMainFrame == true) {
                            val code = errorResponse?.statusCode ?: 0
                            Log.w("BrowserMediaExtractor", "HTTP error on main frame: $code")
                            if (code == 202) {
                                detectedProtectionReason = "Webpage returned security challenge response (HTTP 202 / AWS WAF)"
                                onStatusUpdate("Dynamic protection challenge encountered (HTTP 202)...")
                            } else if (code == 403) {
                                detectedProtectionReason = "Access restricted (HTTP 403 Forbidden / Anti-Bot Shield)"
                                onStatusUpdate("Access restricted by website anti-bot protection...")
                            }
                        }
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?
                    ) {
                        handler?.proceed()
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        Log.e(
                            "BrowserMediaExtractor",
                            "Renderer process exited (didCrash=${detail?.didCrash()}); ending this attempt so the provider can retry"
                        )
                        mainHandler.post {
                            if (isCancelled || isExtractionCompleted) return@post
                            val hasPartialResults = synchronized(seenMap) { seenMap.isNotEmpty() }
                            onStatusUpdate(
                                if (hasPartialResults) {
                                    "Browser renderer stopped. Preserving discovered media before retrying…"
                                } else {
                                    "Browser renderer stopped. Retrying live extraction…"
                                }
                            )
                            safeComplete("Browser renderer process crashed")
                        }
                        return true
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        if (isCancelled) return
                        view?.evaluateJavascript(MediaCaptureEngine.getDynamicMediaCaptureScript(), null)
                        pendingGracePeriodRunnable?.let { mainHandler.removeCallbacks(it) }
                        if (pageFinishedCount > 0) {
                            onStatusUpdate("Verification solved / page navigating to dynamic content...")
                        } else {
                            onStatusUpdate("Browser loaded page. Active network interception listening...")
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (isCancelled || isExtractionCompleted) return
                        pageFinishedCount++

                        syncCookies()
                        onStatusUpdate("Scanning DOM, API responses, Blob URLs, and dynamic media tags...")

                        // Install page-context API/Blob hooks before DOM and mutation scans.
                        wv.evaluateJavascript(MediaCaptureEngine.getDynamicMediaCaptureScript(), null)
                        wv.evaluateJavascript(MediaCaptureEngine.getMutationObserverScript(), null)

                        fun performScanAndScroll(onComplete: () -> Unit, forceScroll: Boolean = false) {
                            wv.evaluateJavascript(MediaCaptureEngine.getJsDomScanScript()) { scanJson ->
                                if (isCancelled || isExtractionCompleted) return@evaluateJavascript
                                val entries = MediaCaptureEngine.parseJsDomScanResult(scanJson, normalizedUrl)
                                for (item in entries) {
                                    val key = MediaClassifier.canonicalKey(item.url)
                                    synchronized(seenMap) {
                                        val existing = seenMap[key]
                                        if (existing != null && MediaClassifier.compareMediaQuality(item, existing) <= 0) {
                                            continue
                                        }
                                        seenMap[key] = if (existing != null) {
                                            item.copy(
                                                thumbnailUrl = item.thumbnailUrl ?: existing.thumbnailUrl,
                                                durationMillis = item.durationMillis ?: existing.durationMillis
                                            )
                                        } else item
                                    }
                                    onMediaDiscovered(item)
                                    DownloaderSession.addDiscoveredItem(item)
                                }

                                val currentCountAfterInitial = synchronized(seenMap) { seenMap.size }
                                if (currentCountAfterInitial > 0 || forceScroll) {
                                    onStatusUpdate(
                                        if (currentCountAfterInitial > 0) {
                                            "Discovered media ($currentCountAfterInitial). Auto-scrolling for more lazy-loaded media..."
                                        } else {
                                            "No media in the initial viewport. Auto-scrolling to trigger lazy loading..."
                                        }
                                    )
                                    executeAdaptiveScrollSequence(wv) {
                                        if (isCancelled || isExtractionCompleted) return@executeAdaptiveScrollSequence
                                        wv.evaluateJavascript(MediaCaptureEngine.getJsDomScanScript()) { postScrollJson ->
                                            if (isCancelled || isExtractionCompleted) return@evaluateJavascript
                                            val postEntries = MediaCaptureEngine.parseJsDomScanResult(postScrollJson, normalizedUrl)
                                            for (item in postEntries) {
                                                val key = MediaClassifier.canonicalKey(item.url)
                                                synchronized(seenMap) {
                                                    val existing = seenMap[key]
                                                    if (existing != null && MediaClassifier.compareMediaQuality(item, existing) <= 0) {
                                                        continue
                                                    }
                                                    seenMap[key] = if (existing != null) {
                                                        item.copy(
                                                            thumbnailUrl = item.thumbnailUrl ?: existing.thumbnailUrl,
                                                            durationMillis = item.durationMillis ?: existing.durationMillis
                                                        )
                                                    } else item
                                                }
                                                onMediaDiscovered(item)
                                                DownloaderSession.addDiscoveredItem(item)
                                            }
                                            val totalCount = synchronized(seenMap) { seenMap.size }
                                            onStatusUpdate("Live browser extraction completed ($totalCount items).")
                                            if (totalCount == 0) onComplete() else safeComplete(null)
                                        }
                                    }
                                } else {
                                    onComplete()
                                }
                            }
                        }

                        // First attempt
                        performScanAndScroll(onComplete = {
                            // If 0 items were discovered, probe page DOM for WAF challenge or client-side SPA hydration
                            wv.evaluateJavascript("""
                                (function() {
                                    var title = document.title || '';
                                    var text = (document.body ? document.body.innerText.substring(0, 1000) : '');
                                    var html = (document.body ? document.body.innerHTML.substring(0, 1000) : '').toLowerCase();
                                    var isWaf = html.indexOf('waf') !== -1 || html.indexOf('challenge') !== -1 ||
                                                 html.indexOf('turnstile') !== -1 || html.indexOf('captcha') !== -1 ||
                                                 title.toLowerCase().indexOf('moment') !== -1;
                                    var isSkeleton = document.querySelectorAll('img, video, picture').length === 0;
                                    return JSON.stringify({ isWaf: isWaf, isSkeleton: isSkeleton, title: title });
                                })();
                            """.trimIndent()) { probeJson ->
                                if (isCancelled || isExtractionCompleted) return@evaluateJavascript
                                val isWaf = runCatching { org.json.JSONObject(probeJson).optBoolean("isWaf", false) }.getOrDefault(false)
                                val probeTitle = runCatching { org.json.JSONObject(probeJson).optString("title", "") }.getOrDefault("")

                                if (isWaf || probeTitle.contains("moment", true) || probeTitle.contains("challenge", true)) {
                                    if (detectedProtectionReason == null) {
                                        detectedProtectionReason = "Verification challenge detected (${probeTitle.ifBlank { "AWS WAF / Cloudflare" }})"
                                    }
                                    onStatusUpdate("Solving dynamic protection / anti-bot verification...")
                                } else {
                                    onStatusUpdate("Waiting for dynamic content to hydrate...")
                                }

                                // Grace period: Give challenge / client-side JS 2800ms to complete and populate DOM
                                val graceRunnable = Runnable {
                                    if (isCancelled || isExtractionCompleted) return@Runnable
                                    performScanAndScroll({
                                        // Still 0 items after hydration grace and auto-scrolling.
                                        val finalReason = detectedProtectionReason
                                            ?: "No media discovered after dynamic page loading and auto-scrolling."
                                        onStatusUpdate(finalReason)
                                        safeComplete(finalReason)
                                    }, forceScroll = true)
                                }
                                pendingGracePeriodRunnable = graceRunnable
                                mainHandler.postDelayed(graceRunnable, if (isWaf) 3200L else 2400L)
                            }
                        })
                    }
                }

                // Watchdog timer (16s max): guarantees extraction never hangs if page keeps loading or streams
                val watchdogRunnable = Runnable {
                    if (!isCancelled && !isExtractionCompleted) {
                        Log.i("BrowserMediaExtractor", "Extraction watchdog timeout reached, scanning current page DOM...")
                        onStatusUpdate("Completing page scan...")
                        wv.evaluateJavascript(MediaCaptureEngine.getJsDomScanScript()) { fallbackJson ->
                            val fallbackEntries = MediaCaptureEngine.parseJsDomScanResult(fallbackJson, normalizedUrl)
                            for (item in fallbackEntries) {
                                val key = MediaClassifier.canonicalKey(item.url)
                                synchronized(seenMap) {
                                    val existing = seenMap[key]
                                    if (existing != null && MediaClassifier.compareMediaQuality(item, existing) <= 0) {
                                        continue
                                    }
                                    seenMap[key] = item
                                }
                                onMediaDiscovered(item)
                                DownloaderSession.addDiscoveredItem(item)
                            }
                            val isSeenEmpty = synchronized(seenMap) { seenMap.isEmpty() }
                            val finalReason = if (isSeenEmpty) {
                                detectedProtectionReason ?: "Page scan timed out (anti-bot protection or dynamic content failed to render)"
                            } else null
                            safeComplete(finalReason)
                        }
                    }
                }
                mainHandler.postDelayed(watchdogRunnable, 16000L)

                wv.loadUrl(normalizedUrl)
            } catch (e: Exception) {
                Log.e("BrowserMediaExtractor", "Browser initialization failed", e)
                onStatusUpdate("Browser initialization failed: ${e.localizedMessage}")
                onFinished(e.localizedMessage)
            }
        }
    }

    @Keep
    class EmreMediaBridge(
        private val onMediaFound: (String, String?) -> Unit,
        private val blobCaptureStore: BrowserBlobCaptureStore? = null,
        private val onBlobCaptured: ((ScrapedMediaItem) -> Unit)? = null
    ) {
        @Keep
        @android.webkit.JavascriptInterface
        fun reportMedia(url: String?) {
            if (!url.isNullOrBlank()) onMediaFound(url, null)
        }

        @Keep
        @android.webkit.JavascriptInterface
        fun reportMediaWithMime(url: String?, mimeHint: String?) {
            if (!url.isNullOrBlank()) onMediaFound(url, mimeHint)
        }

        @Keep
        @android.webkit.JavascriptInterface
        fun beginBlobCapture(captureId: String?, mimeType: String?, fileName: String?, expectedBytes: Long): Boolean {
            return blobCaptureStore?.beginCapture(captureId, mimeType, fileName, expectedBytes) == true
        }

        @Keep
        @android.webkit.JavascriptInterface
        fun appendBlobChunk(captureId: String?, base64Chunk: String?): Boolean {
            return blobCaptureStore?.appendChunk(captureId, base64Chunk) == true
        }

        @Keep
        @android.webkit.JavascriptInterface
        fun finishBlobCapture(captureId: String?, pageUrl: String?): Boolean {
            val item = blobCaptureStore?.finishCapture(captureId, pageUrl) ?: return false
            onBlobCaptured?.invoke(item)
            return true
        }

        @Keep
        @android.webkit.JavascriptInterface
        fun abortBlobCapture(captureId: String?) {
            blobCaptureStore?.abortCapture(captureId)
        }
    }

    private fun executeAdaptiveScrollSequence(
        wv: WebView,
        step: Int = 1,
        maxSteps: Int = 12,
        stagnantCount: Int = 0,
        lastScrollY: Int = -1,
        onDone: () -> Unit
    ) {
        if (isCancelled || step > maxSteps || stagnantCount >= 2) {
            onDone()
            return
        }

        wv.evaluateJavascript(MediaCaptureEngine.getAdaptiveScrollScript()) { resultJson ->
            if (isCancelled) {
                onDone()
                return@evaluateJavascript
            }

            var currentScrollY = lastScrollY
            var remaining = 1000
            runCatching {
                val unescaped = if (resultJson != null && resultJson.startsWith("\"") && resultJson.endsWith("\"")) {
                    org.json.JSONTokener(resultJson).nextValue().toString()
                } else resultJson ?: "{}"
                val obj = org.json.JSONObject(unescaped)
                currentScrollY = obj.optInt("scrollY", lastScrollY)
                remaining = obj.optInt("remaining", 1000)
            }

            val isStagnant = lastScrollY != -1 && Math.abs(currentScrollY - lastScrollY) < 10
            val newStagnantCount = if (isStagnant) stagnantCount + 1 else 0

            if (remaining <= 5 || newStagnantCount >= 2) {
                onDone()
            } else {
                mainHandler.postDelayed({
                    if (!isCancelled) {
                        executeAdaptiveScrollSequence(
                            wv = wv,
                            step = step + 1,
                            maxSteps = maxSteps,
                            stagnantCount = newStagnantCount,
                            lastScrollY = currentScrollY,
                            onDone = onDone
                        )
                    } else {
                        onDone()
                    }
                }, 500)
            }
        }
    }

    fun cleanup() {
        isCancelled = true
        blobCaptureStore.abortAll()
        mainHandler.removeCallbacksAndMessages(null)
        seenCandidateUrls.clear()
        try {
            (webView?.parent as? ViewGroup)?.removeView(webView)
            webView?.stopLoading()
            webView?.removeJavascriptInterface("EmreBridge")
            webView?.webViewClient = WebViewClient()
            webView?.webChromeClient = WebChromeClient()
            webView?.destroy()
        } catch (_: Exception) {
        } finally {
            webView = null
        }
    }
}

