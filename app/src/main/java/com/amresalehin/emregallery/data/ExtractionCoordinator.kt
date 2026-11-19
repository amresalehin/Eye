package com.amresalehin.emregallery.data

import android.content.Context
import android.util.Log
import com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * TargetMediaExpectation represents the expected media type based on URL classification.
 * Used to govern routing decisions and determine whether an extraction result is actually
 * sufficient or merely a partial preview (e.g. static OpenGraph poster).
 */
enum class TargetMediaExpectation {
    VIDEO_OR_STREAM,
    GALLERY_OR_ALBUM,
    GENERIC_ANY
}

/**
 * Extraction Coordinator - Goal-aware, routing-based media extraction engine.
 *
 * Implements a dependable "is this the actual downloadable media?" decision model:
 * 1. Categorizes user expectation (VIDEO_OR_STREAM, GALLERY_OR_ALBUM, GENERIC_ANY).
 * 2. Checks explicit engine availability (isYtDlpAvailable, isGalleryDlAvailable) before invoking Python adapters.
 * 3. Preserves extractor diagnostic error messages (e.g. bot challenge, login required, throttling) without swallowing them.
 * 4. Ensures static thumbnails (og:image) never prematurely terminate video or album extraction pipelines.
 * 5. Falls back to dynamic Chromium browser when specialized or static extractors do not satisfy the expectation.
 * 6. Modularized coroutine steps so each suspend state machine stays well below Android ART's 16,384-instruction JIT limit.
 */
typealias ExtractionState = DualMediaExtractor.ExtractionState
typealias ExtractionPhase = DualMediaExtractor.ExtractionPhase

class ExtractionCoordinator(private val context: Context) {

    private var extractionJob: Job? = null

    private val _state = MutableStateFlow(ExtractionState())
    val state: StateFlow<ExtractionState> = _state.asStateFlow()

    private class ExtractionSessionContext(
        val requestContext: MediaRequestContext,
        val expectation: TargetMediaExpectation,
        val isRedditTarget: Boolean,
        val isInstagramTarget: Boolean,
        val isPinterestTarget: Boolean,
        val isYouTubeTarget: Boolean,
        val itemsMap: LinkedHashMap<String, ScrapedMediaItem> = LinkedHashMap(),
        val itemsMutex: Mutex = Mutex()
    ) {
        @Volatile
        var lastDiagnosticError: String? = null

        @Volatile
        var captchaChallengeUrl: String? = null
    }

    fun extract(targetUrl: String, scope: CoroutineScope, directOnly: Boolean = false) {
        cancel()

        val trimmed = targetUrl.trim()
        val normalizedRedditTarget = MediaClassifier.normalizeRedditShorthand(trimmed)
        val rawEffectiveUrl = normalizedRedditTarget ?: trimmed
        val effectiveInputUrl = if (GalleryDlEngine.isYouTubeUrl(rawEffectiveUrl)) {
            GalleryDlEngine.normalizeYouTubeUrl(rawEffectiveUrl)
        } else {
            rawEffectiveUrl
        }
        if (!MediaClassifier.isValidWebUrl(effectiveInputUrl)) {
            _state.value = ExtractionState(
                errorMessage = "Please enter a valid URL or Reddit target (e.g. https://example.com, r/funny, u/username)",
                isRunning = false,
                items = emptyList()
            )
            return
        }

        val requestContext = MediaRequestContext.create(effectiveInputUrl)
        val domain = requestContext.domain
        val expectation = determineExpectation(requestContext.url)
        val lowerUrl = requestContext.url.lowercase(java.util.Locale.ROOT)
        val session = ExtractionSessionContext(
            requestContext = requestContext,
            expectation = expectation,
            isRedditTarget = normalizedRedditTarget != null ||
                lowerUrl.contains("reddit.com") ||
                lowerUrl.contains("redd.it") ||
                lowerUrl.contains("redgifs.com"),
            isInstagramTarget = lowerUrl.contains("instagram.com") || lowerUrl.contains("instagr.am"),
            isPinterestTarget = lowerUrl.contains("pinterest.") || lowerUrl.contains("pin.it") || lowerUrl.contains("pinimg.com"),
            isYouTubeTarget = GalleryDlEngine.isYouTubeUrl(requestContext.url)
        )

        _state.value = ExtractionState(
            isRunning = true,
            statusMessage = "Analyzing URL route...",
            phase = ExtractionPhase.DIRECT_MEDIA,
            domain = domain,
            items = emptyList()
        )

        extractionJob = scope.launch {
            executeExtractionPipeline(session, directOnly)
        }
    }

    private suspend fun executeExtractionPipeline(
        session: ExtractionSessionContext,
        directOnly: Boolean
    ) {
        try {
            val activeProxy = DownloaderPreferencesManager.getActiveProxyUrlSync(context)
            val networkTimeout = DownloaderPreferencesManager.getNetworkTimeoutSync(context)
            OkHttpDownloader.configureProxy(
                enabled = activeProxy != null,
                proxyUrl = activeProxy.orEmpty(),
                timeoutSeconds = networkTimeout
            )

            // Step 1: OkHttp Direct-Media Fast Path Probe (skip for YouTube watch pages)
            if (!session.isYouTubeTarget && probeDirectMediaFastPath(session)) {
                finishSuccess(session.itemsMap.size)
                return
            }

            if (directOnly) {
                finalizeEmptyOrSuccess(session)
                return
            }

            // Step 2: Concurrent Multi-Engine Discovery
            updateStatus(
                session,
                if (session.isYouTubeTarget) {
                    "Extracting YouTube streams via yt-dlp InnerTube engine..."
                } else {
                    "Initiating concurrent discovery (yt-dlp, gallery-dl, Static Scraper, Browser)..."
                },
                ExtractionPhase.YT_DLP
            )

            val supportInfo = withContext(Dispatchers.IO) {
                GalleryDlEngine.init(context)
                GalleryDlEngine.checkSupport(session.requestContext.url)
            }

            val rawCookies = OkHttpDownloader.sharedCookieJar
                .getCookieString(session.requestContext.url)
                .takeIf { it.isNotBlank() }
            val cookies = GalleryDlEngine.sanitizeCookiesForUrl(session.requestContext.url, rawCookies)
            val options = buildExtractorOptions(cookies, activeProxy, networkTimeout)

            val satisfiedFast = runConcurrentDiscoveryChannels(session, options)
            if (satisfiedFast) {
                finishSuccess(session.itemsMap.size)
                return
            }

            // Step 3: Bot Challenge / Cookie Refresh Evaluation
            if (handleBotAndCookieRetryIfNeeded(session, supportInfo, options, cookies)) {
                return
            }

            finalizeEmptyOrSuccess(session)
        } catch (_: CancellationException) {
            _state.value = _state.value.copy(
                isRunning = false,
                phase = ExtractionPhase.IDLE,
                statusMessage = "Extraction stopped"
            )
        } catch (e: Exception) {
            Log.e("ExtractionCoordinator", "Routing error", e)
            val err = e.localizedMessage ?: "Extraction failed"
            _state.value = _state.value.copy(
                isRunning = false,
                phase = ExtractionPhase.ERROR,
                statusMessage = "Extraction failed: $err",
                errorMessage = err
            )
        }
    }

    private suspend fun probeDirectMediaFastPath(session: ExtractionSessionContext): Boolean {
        updateStatus(session, "Probing URL headers with OkHttp...", ExtractionPhase.DIRECT_MEDIA)
        val directProbe = OkHttpDownloader.probeDirectMedia(session.requestContext.url)
        if (!directProbe.isConfirmedDirectMedia) return false

        val finalUrl = directProbe.finalUrl
        val ct = directProbe.contentType
        val cd = directProbe.contentDisposition
        val cl = directProbe.contentLength

        val classification = MediaClassifier.classify(finalUrl, mimeHint = ct)
        val derivedTitle = if (!cd.isNullOrBlank() && cd.contains("filename=", ignoreCase = true)) {
            cd.substringAfter("filename=").replace("\"", "").trim()
        } else {
            MediaClassifier.deriveFileName(finalUrl, classification.format, 0)
        }

        val directItem = ScrapedMediaItem(
            id = "direct_${System.currentTimeMillis()}_${(finalUrl.hashCode() and 0xFFFF)}",
            url = finalUrl,
            title = derivedTitle,
            isVideo = classification.isVideo,
            isAudio = classification.isAudio,
            isStream = classification.isStream,
            category = classification.category,
            format = classification.format,
            mimeType = classification.mimeType,
            sizeBytes = cl,
            source = "Direct HTTP",
            provenance = MediaProvenance(extractorEngine = "OkHttp Direct", pageUrl = session.requestContext.url)
        )

        updateItems(session, listOf(directItem), title = derivedTitle, phase = ExtractionPhase.COMPLETED)
        return true
    }

    private fun buildExtractorOptions(
        cookies: String?,
        activeProxy: String?,
        networkTimeout: Int
    ): GalleryDlOptions {
        val redditPrefs = DownloaderPreferencesManager.getRedditConfigSync(context)
        return GalleryDlOptions(
            cookies = cookies,
            timeoutSeconds = networkTimeout,
            limit = redditPrefs.limit,
            afterDate = redditPrefs.afterDate.takeIf { it.isNotBlank() },
            beforeDate = redditPrefs.beforeDate.takeIf { it.isNotBlank() },
            includeComments = redditPrefs.includeComments,
            includePostAuthors = redditPrefs.includePostAuthors,
            redditClientId = redditPrefs.clientId.takeIf { it.isNotBlank() },
            redditClientSecret = redditPrefs.clientSecret.takeIf { it.isNotBlank() },
            redditUsername = redditPrefs.username.takeIf { it.isNotBlank() },
            redditPassword = redditPrefs.password.takeIf { it.isNotBlank() },
            redditRefreshToken = redditPrefs.refreshToken.takeIf { it.isNotBlank() },
            proxy = activeProxy,
            maxHeight = DownloaderPreferencesManager.getVideoQualityCapSync(context).maxHeight,
            formatMode = DownloaderPreferencesManager.getFormatModeSync(context).id,
            downloadPlaylist = DownloaderPreferencesManager.isDownloadPlaylistSync(context),
            downloadSubtitles = DownloaderPreferencesManager.isDownloadSubtitlesSync(context),
            includeAutoSubtitles = DownloaderPreferencesManager.isIncludeAutoSubtitlesSync(context),
            subtitleLanguages = DownloaderPreferencesManager.getSubtitleLanguagesSync(context),
            retries = DownloaderPreferencesManager.getRetryCountSync(context)
        )
    }

    private suspend fun runConcurrentDiscoveryChannels(
        session: ExtractionSessionContext,
        options: GalleryDlOptions
    ): Boolean = coroutineScope {
        val url = session.requestContext.url
        val lowerUrl = url.lowercase(java.util.Locale.ROOT)
        val detectedPlatform = SupportedMediaPlatform.detect(url)
        val isSpotifyTarget = detectedPlatform == SupportedMediaPlatform.SPOTIFY
        val isRadioJavanTarget = detectedPlatform == SupportedMediaPlatform.RADIOJAVAN
        val isXSpaceTarget = SupportedMediaPlatform.isXSpace(url)
        val isTumblrTarget = lowerUrl.contains("tumblr.com")
        val isPixivTarget = lowerUrl.contains("pixiv.net")
        val isFacebookTarget = lowerUrl.contains("facebook.com") || lowerUrl.contains("fb.watch")
        val isTwitterStatusTarget = (lowerUrl.contains("twitter.com/") || lowerUrl.contains("x.com/")) && lowerUrl.contains("/status/")
        val recursiveCrawlEnabled = DownloaderPreferencesManager.isRecursiveCrawlEnabledSync(context)

        val fastJobs = mutableListOf<Job>()

        // Skip static HTML scraper, gallery-dl, and headless WebView for YouTube watch/shorts URLs
        // because YouTube uses MSE blob streams and offscreen WebViews trigger guest-cookie bot checks.
        if (!session.isYouTubeTarget) {
            fastJobs += launch(Dispatchers.IO) { runStaticScraperChannel(session) }
        }
        if (session.isRedditTarget) {
            fastJobs += launch(Dispatchers.IO) {
                runPythonChannel(session, "reddit-dl", GalleryDlEngine.isRedditDlAvailable()) {
                    GalleryDlEngine.extractReddit(url, options)
                }
            }
        }
        if (session.isInstagramTarget) {
            fastJobs += launch(Dispatchers.IO) {
                runPythonChannel(session, "Instaloader", GalleryDlEngine.isInstaloaderAvailable()) {
                    GalleryDlEngine.extractInstagram(url, options)
                }
            }
        }
        if (session.isPinterestTarget) {
            fastJobs += launch(Dispatchers.IO) {
                runPythonChannel(session, "Pinterest-DL", GalleryDlEngine.isPinterestDlAvailable()) {
                    GalleryDlEngine.extractPinterest(url, options)
                }
            }
        }
        if (isSpotifyTarget) {
            fastJobs += launch(Dispatchers.IO) {
                runPythonChannel(session, "Spotify", GalleryDlEngine.isMediaDownloaderAvailable()) {
                    GalleryDlEngine.extractSpotify(url, options)
                }
            }
        }
        if (isRadioJavanTarget) {
            fastJobs += launch(Dispatchers.IO) {
                runPythonChannel(session, "RadioJavan", GalleryDlEngine.isMediaDownloaderAvailable()) {
                    GalleryDlEngine.extractRadioJavan(url, options)
                }
            }
        }
        if (isXSpaceTarget) {
            fastJobs += launch(Dispatchers.IO) {
                runPythonChannel(session, "X Spaces", GalleryDlEngine.isMediaDownloaderAvailable()) {
                    GalleryDlEngine.extractXSpace(url, options)
                }
            }
        }
        if (isTumblrTarget) {
            fastJobs += launch(Dispatchers.IO) {
                runPythonChannel(session, "Tumblr media-scraper", GalleryDlEngine.isMediaScraperAvailable(), recordError = false) {
                    GalleryDlEngine.extractTumblr(url, options)
                }
            }
        }
        if (isPixivTarget) {
            fastJobs += launch(Dispatchers.IO) {
                runPythonChannel(session, "Pixiv media-scraper", GalleryDlEngine.isMediaScraperAvailable(), recordError = false) {
                    GalleryDlEngine.extractPixiv(url, options)
                }
            }
        }
        if (isTwitterStatusTarget) {
            fastJobs += launch(Dispatchers.IO) {
                runPythonChannel(session, "Twitter media-scraper", GalleryDlEngine.isMediaScraperAvailable(), recordError = false) {
                    GalleryDlEngine.extractTwitterMediaScraper(url, options)
                }
            }
        }
        if (isFacebookTarget) {
            fastJobs += launch(Dispatchers.IO) {
                runPythonChannel(session, "Facebook media-scraper", GalleryDlEngine.isMediaScraperAvailable(), recordError = false) {
                    GalleryDlEngine.extractFacebookMediaScraper(url, options)
                }
            }
        }
        if (recursiveCrawlEnabled && session.expectation != TargetMediaExpectation.VIDEO_OR_STREAM && !session.isYouTubeTarget) {
            fastJobs += launch(Dispatchers.IO) { runRecursiveCrawlerChannel(session) }
        }

        // Primary yt-dlp channel
        fastJobs += launch(Dispatchers.IO) {
            runPythonChannel(session, "yt-dlp", GalleryDlEngine.isYtDlpAvailable(), overwriteError = true) {
                GalleryDlEngine.extractYtDlp(url, options)
            }
        }

        // Secondary gallery-dl channel (skip on YouTube video links)
        if (!session.isYouTubeTarget) {
            fastJobs += launch(Dispatchers.IO) {
                runPythonChannel(session, "gallery-dl", GalleryDlEngine.isGalleryDlAvailable()) {
                    GalleryDlEngine.extractGalleryDl(url, options)
                }
            }
        }

        // Complementary headless Chromium browser channel (never spawn on YouTube URLs)
        val browserJob = if (!session.isYouTubeTarget) {
            launch(Dispatchers.IO) { runHeadlessBrowserChannel(session) }
        } else null

        for (job in fastJobs) {
            job.join()
        }

        val fastExtractorsSufficient = session.itemsMutex.withLock {
            isExtractionSufficient(session.expectation, session.itemsMap.values)
        }
        if (fastExtractorsSufficient) {
            browserJob?.cancel()
            return@coroutineScope true
        }

        browserJob?.join()
        return@coroutineScope false
    }

    private suspend fun runStaticScraperChannel(session: ExtractionSessionContext) {
        try {
            val staticRes = StaticMediaScraper().scrape(session.requestContext.url)
            if (staticRes.items.isNotEmpty()) {
                val filteredStatic = if (session.expectation == TargetMediaExpectation.VIDEO_OR_STREAM) {
                    staticRes.items.filter { it.isVideo || it.isAudio || it.isStream }
                } else {
                    staticRes.items
                }
                if (filteredStatic.isNotEmpty()) {
                    updateItems(
                        session,
                        filteredStatic,
                        title = staticRes.pageTitle,
                        msg = "Discovered ${filteredStatic.size} item(s) via fast HTML scraper"
                    )
                } else if (staticRes.pageTitle.isNotBlank()) {
                    updateItems(session, emptyList(), title = staticRes.pageTitle)
                }
            } else if (!staticRes.errorMessage.isNullOrBlank() && session.lastDiagnosticError == null) {
                session.lastDiagnosticError = staticRes.errorMessage
            }
        } catch (e: Exception) {
            Log.w("ExtractionCoordinator", "Concurrent StaticMediaScraper error", e)
        }
    }

    private suspend fun runPythonChannel(
        session: ExtractionSessionContext,
        engineLabel: String,
        isAvailable: Boolean,
        recordError: Boolean = true,
        overwriteError: Boolean = false,
        block: suspend () -> GalleryDlExtractResult
    ) {
        if (!isAvailable) return
        try {
            val res = block()
            if (res.success && res.items.isNotEmpty()) {
                updateItems(
                    session,
                    res.items,
                    title = res.pageTitle,
                    msg = "Discovered ${res.items.size} items via $engineLabel"
                )
            } else if (recordError && !res.errorMessage.isNullOrBlank()) {
                if (overwriteError || session.lastDiagnosticError == null) {
                    session.lastDiagnosticError = res.errorMessage
                }
            }
        } catch (e: Exception) {
            Log.w("ExtractionCoordinator", "Concurrent $engineLabel error", e)
        }
    }

    private suspend fun runRecursiveCrawlerChannel(session: ExtractionSessionContext) = coroutineScope {
        try {
            val recursiveCrawlConfig = DownloaderPreferencesManager.getRecursiveCrawlConfigSync(context)
            val crawler = RecursiveSiteCrawler()
            val crawlRes = crawler.crawl(
                startUrl = session.requestContext.url,
                config = recursiveCrawlConfig,
                context = context,
                onProgress = { p ->
                    launch {
                        updateStatus(
                            session,
                            "Recursive crawl (${p.pagesCrawled}/${p.maxPages} pages, depth ${p.currentDepth}): ${p.mediaDiscovered} media",
                            ExtractionPhase.STATIC_SCRAPING
                        )
                    }
                },
                onMediaDiscovered = { discovered ->
                    launch { updateItems(session, discovered) }
                }
            )
            if (crawlRes.items.isNotEmpty()) {
                updateItems(
                    session,
                    crawlRes.items,
                    title = crawlRes.pageTitle,
                    msg = "Recursive crawl discovered ${crawlRes.items.size} media across ${crawlRes.pagesCrawled} page(s)"
                )
            }
            if (crawlRes.captchaDetected) {
                session.captchaChallengeUrl = crawlRes.captchaUrl ?: session.requestContext.url
                if (session.lastDiagnosticError == null) {
                    session.lastDiagnosticError = crawlRes.errorMessage
                }
            }
        } catch (e: Exception) {
            Log.w("ExtractionCoordinator", "Concurrent RecursiveSiteCrawler error", e)
        }
    }

    private suspend fun runHeadlessBrowserChannel(session: ExtractionSessionContext) = coroutineScope {
        try {
            kotlinx.coroutines.delay(350L)
            val alreadySufficient = session.itemsMutex.withLock {
                isExtractionSufficient(session.expectation, session.itemsMap.values)
            }
            if (alreadySufficient) return@coroutineScope

            val browserRes = withContext(Dispatchers.Main) {
                BrowserSession.extractHeadless(
                    context = context,
                    targetUrl = session.requestContext.url,
                    onStatusUpdate = { s -> launch { updateStatus(session, s, ExtractionPhase.BROWSER_INTERCEPTING) } },
                    onMediaDiscovered = { item -> launch { updateItems(session, listOf(item)) } }
                )
            }
            if (browserRes.items.isNotEmpty()) {
                updateItems(session, browserRes.items, title = browserRes.pageTitle)
            } else if (session.lastDiagnosticError == null) {
                session.lastDiagnosticError = browserRes.errorMessage
            }
        } catch (_: CancellationException) {
            // Fast extractors succeeded and cancelled headless browser early
        } catch (e: Exception) {
            Log.w("ExtractionCoordinator", "Concurrent BrowserSession error", e)
        }
    }

    private suspend fun handleBotAndCookieRetryIfNeeded(
        session: ExtractionSessionContext,
        supportInfo: GalleryDlSupportInfo,
        options: GalleryDlOptions,
        initialCookies: String?
    ): Boolean = coroutineScope {
        if (session.itemsMap.isNotEmpty()) return@coroutineScope false
        // Do not run anonymous WebView cookie scraping for YouTube (it only produces unauthenticated VISITOR_INFO1_LIVE cookies)
        if (session.isYouTubeTarget) return@coroutineScope false

        val domain = session.requestContext.domain
        val url = session.requestContext.url
        val needsSignInDirect = BotDetectionMitigator.indicatesSignInRequired(session.lastDiagnosticError)
        val isSupportedByEngines = supportInfo.isSupported
        val needsCookiesOrBot = BotDetectionMitigator.indicatesCookiesOrBotVerificationNeeded(session.lastDiagnosticError) ||
            (isSupportedByEngines && session.itemsMap.isEmpty() && initialCookies.isNullOrBlank())

        if (session.captchaChallengeUrl != null) {
            _state.value = _state.value.copy(
                isRunning = false,
                requiresSignIn = true,
                signInUrl = session.captchaChallengeUrl,
                statusMessage = "CAPTCHA verification required for $domain",
                errorMessage = "CAPTCHA detected on $domain. Complete verification in the browser to continue."
            )
            return@coroutineScope true
        }

        if (needsSignInDirect) {
            _state.value = _state.value.copy(
                isRunning = false,
                requiresSignIn = true,
                signInUrl = url,
                statusMessage = "Sign-in required for $domain",
                errorMessage = "Sign in to $domain to access and download this media."
            )
            return@coroutineScope true
        }

        if (!needsCookiesOrBot) return@coroutineScope false

        updateStatus(session, "Resolving session cookies in background for $domain...", ExtractionPhase.YT_DLP)
        _state.value = _state.value.copy(isObtainingCookiesInBackground = true)

        val cookieRes = withContext(Dispatchers.Main) {
            BrowserSession.fetchCookiesInBackground(
                context = context,
                targetUrl = url,
                onStatusUpdate = { s -> launch { updateStatus(session, s) } }
            )
        }
        _state.value = _state.value.copy(isObtainingCookiesInBackground = false)

        if (cookieRes.requiresSignIn) {
            _state.value = _state.value.copy(
                isRunning = false,
                requiresSignIn = true,
                signInUrl = cookieRes.signInUrl ?: url,
                statusMessage = "Sign-in required for $domain",
                errorMessage = "Sign in to $domain to access and download this media."
            )
            return@coroutineScope true
        }

        val sanitizedRetryCookies = GalleryDlEngine.sanitizeCookiesForUrl(url, cookieRes.cookies)
        if (!sanitizedRetryCookies.isNullOrBlank()) {
            val updatedOptions = options.copy(cookies = sanitizedRetryCookies)
            updateStatus(session, "Cookies refreshed. Re-probing with yt-dlp & gallery-dl...", ExtractionPhase.YT_DLP)
            retryPythonExtractorsWithCookies(session, updatedOptions)
        }
        return@coroutineScope false
    }

    private suspend fun retryPythonExtractorsWithCookies(
        session: ExtractionSessionContext,
        updatedOptions: GalleryDlOptions
    ) {
        val url = session.requestContext.url
        if (session.isRedditTarget && GalleryDlEngine.isRedditDlAvailable()) {
            val retryReddit = withContext(Dispatchers.IO) {
                runCatching { GalleryDlEngine.extractReddit(url, updatedOptions) }
                    .getOrElse { GalleryDlExtractResult(success = false, errorMessage = it.localizedMessage) }
            }
            if (retryReddit.success && retryReddit.items.isNotEmpty()) {
                updateItems(session, retryReddit.items, title = retryReddit.pageTitle)
            }
        }

        if (session.isInstagramTarget && GalleryDlEngine.isInstaloaderAvailable()) {
            val retryIg = withContext(Dispatchers.IO) {
                runCatching { GalleryDlEngine.extractInstagram(url, updatedOptions) }
                    .getOrElse { GalleryDlExtractResult(success = false, errorMessage = it.localizedMessage) }
            }
            if (retryIg.success && retryIg.items.isNotEmpty()) {
                updateItems(session, retryIg.items, title = retryIg.pageTitle)
            }
        }

        if (session.isPinterestTarget && GalleryDlEngine.isPinterestDlAvailable()) {
            val retryPinterest = withContext(Dispatchers.IO) {
                runCatching { GalleryDlEngine.extractPinterest(url, updatedOptions) }
                    .getOrElse { GalleryDlExtractResult(success = false, errorMessage = it.localizedMessage) }
            }
            if (retryPinterest.success && retryPinterest.items.isNotEmpty()) {
                updateItems(session, retryPinterest.items, title = retryPinterest.pageTitle)
            }
        }

        val retryYt = withContext(Dispatchers.IO) {
            runCatching { GalleryDlEngine.extractYtDlp(url, updatedOptions) }
                .getOrElse { GalleryDlExtractResult(success = false, errorMessage = it.localizedMessage) }
        }
        if (retryYt.success && retryYt.items.isNotEmpty()) {
            updateItems(session, retryYt.items, title = retryYt.pageTitle)
        }

        if (!session.isYouTubeTarget) {
            val retryGdl = withContext(Dispatchers.IO) {
                runCatching { GalleryDlEngine.extractGalleryDl(url, updatedOptions) }
                    .getOrElse { GalleryDlExtractResult(success = false, errorMessage = it.localizedMessage) }
            }
            if (retryGdl.success && retryGdl.items.isNotEmpty()) {
                updateItems(session, retryGdl.items, title = retryGdl.pageTitle)
            }
        }
    }

    private fun finalizeEmptyOrSuccess(session: ExtractionSessionContext) {
        if (session.itemsMap.isNotEmpty()) {
            finishSuccess(session.itemsMap.size)
            return
        }
        val finalReason: String = session.lastDiagnosticError?.takeIf { it.isNotBlank() }
            ?: "No media items discovered by any discovery engine."

        _state.value = _state.value.copy(
            isRunning = false,
            phase = ExtractionPhase.ERROR,
            statusMessage = finalReason,
            errorMessage = finalReason
        )
    }

    private suspend fun updateItems(
        session: ExtractionSessionContext,
        newList: List<ScrapedMediaItem>,
        title: String? = null,
        msg: String? = null,
        phase: ExtractionPhase? = null
    ) = session.itemsMutex.withLock {
        val modified = MediaNormalizer.mergeCandidates(session.itemsMap, newList)
        if (modified || msg != null || phase != null || title != null) {
            val currentItems = session.itemsMap.values.toList()
            _state.value = _state.value.copy(
                items = currentItems,
                pageTitle = if (_state.value.pageTitle.isBlank() && !title.isNullOrBlank()) title else _state.value.pageTitle,
                statusMessage = msg ?: _state.value.statusMessage,
                phase = phase ?: _state.value.phase
            )
            DownloaderSession.addDiscoveredItems(currentItems)
            if (!title.isNullOrBlank()) DownloaderSession.setPageTitle(title)
            if (msg != null) DownloaderSession.setStatusMessage(msg)
        }
    }

    private suspend fun updateStatus(
        session: ExtractionSessionContext,
        status: String,
        phase: ExtractionPhase? = null
    ) = session.itemsMutex.withLock {
        _state.value = _state.value.copy(
            statusMessage = status,
            phase = phase ?: _state.value.phase
        )
    }

    private fun finishSuccess(count: Int) {
        _state.value = _state.value.copy(
            isRunning = false,
            phase = ExtractionPhase.COMPLETED,
            statusMessage = "Extraction completed. Discovered $count media items."
        )
    }

    companion object {
        fun isDirectMediaUrl(url: String): Boolean {
            if (!MediaClassifier.isValidMedia(url)) return false
            val classification = MediaClassifier.classify(url)
            return classification.isVideo || classification.isAudio || classification.isStream ||
                    url.endsWith(".jpg", true) || url.endsWith(".jpeg", true) ||
                    url.endsWith(".png", true) || url.endsWith(".webp", true) ||
                    url.endsWith(".gif", true) || url.endsWith(".svg", true)
        }

        fun determineExpectation(url: String): TargetMediaExpectation {
            val lower = url.lowercase(java.util.Locale.ROOT)
            if (isVideoPlatformUrl(lower) || lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains("/videoplayback")) {
                return TargetMediaExpectation.VIDEO_OR_STREAM
            }
            if (isGalleryPlatformUrl(lower)) {
                return TargetMediaExpectation.GALLERY_OR_ALBUM
            }
            return TargetMediaExpectation.GENERIC_ANY
        }

        /**
         * Determines whether the discovered media items satisfy the extraction goal.
         * Prevents partial thumbnail/poster previews from being declared as success
         * for video streams or album platforms.
         */
        fun isExtractionSufficient(
            expectation: TargetMediaExpectation,
            items: Collection<ScrapedMediaItem>
        ): Boolean {
            if (items.isEmpty()) return false
            return when (expectation) {
                TargetMediaExpectation.VIDEO_OR_STREAM -> {
                    // Must have at least one playable video, stream, or audio item
                    items.any { it.isVideo || it.isStream || it.isAudio }
                }
                TargetMediaExpectation.GALLERY_OR_ALBUM -> {
                    items.isNotEmpty()
                }
                TargetMediaExpectation.GENERIC_ANY -> {
                    items.isNotEmpty()
                }
            }
        }

        fun isVideoPlatformUrl(lower: String): Boolean {
            val videoHosts = listOf(
                "youtube.com", "youtu.be", "vimeo.com", "tiktok.com", "dailymotion.com",
                "twitch.tv", "soundcloud.com", "bandcamp.com", "facebook.com", "instagram.com/reel", "instagram.com/reels/",
                "open.spotify.com", "spotify.link", "spoti.fi", "radiojavan.com", "rj.app", "x.com/i/spaces/", "twitter.com/i/spaces/"
            )
            return videoHosts.any { lower.contains(it) } || lower.contains(".m3u8") || lower.contains(".mpd")
        }

        fun isGalleryPlatformUrl(lower: String): Boolean {
            val galleryHosts = listOf(
                "reddit.com", "redd.it", "imgur.com", "pinterest.com", "pin.it",
                "artstation.com", "deviantart.com", "flickr.com", "instagram.com/p/",
                "instagram.com/tv/", "tumblr.com", "pixiv.net"
            )
            return galleryHosts.any { lower.contains(it) }
        }
    }

    @Synchronized
    fun addDiscoveredItems(newList: List<ScrapedMediaItem>, title: String? = null, domain: String? = null) {
        val existingMap = LinkedHashMap<String, ScrapedMediaItem>()
        for (item in _state.value.items) {
            existingMap[MediaClassifier.deduplicationKey(item.url)] = item
        }
        val modified = MediaNormalizer.mergeCandidates(existingMap, newList)
        if (modified || title != null || domain != null) {
            _state.value = _state.value.copy(
                items = existingMap.values.toList(),
                pageTitle = title ?: _state.value.pageTitle,
                domain = domain ?: _state.value.domain,
                statusMessage = "Discovered ${existingMap.size} media items"
            )
        }
    }

    fun addDiscoveredItem(item: ScrapedMediaItem) {
        addDiscoveredItems(listOf(item))
    }

    fun cancel() {
        extractionJob?.cancel()
        extractionJob = null
        if (_state.value.isRunning) {
            _state.value = _state.value.copy(
                isRunning = false,
                phase = ExtractionPhase.IDLE,
                statusMessage = "Extraction stopped"
            )
        }
    }

    fun reset() {
        cancel()
        _state.value = ExtractionState()
    }

    fun cleanup() {
        reset()
    }
}
