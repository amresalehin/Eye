package com.amresalehin.emregallery.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URL

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
 */
typealias ExtractionState = DualMediaExtractor.ExtractionState
typealias ExtractionPhase = DualMediaExtractor.ExtractionPhase

class ExtractionCoordinator(private val context: Context) {

    private var extractionJob: Job? = null

    private val _state = MutableStateFlow(ExtractionState())
    val state: StateFlow<ExtractionState> = _state.asStateFlow()

    fun extract(targetUrl: String, scope: CoroutineScope, directOnly: Boolean = false) {
        cancel()

        val trimmed = targetUrl.trim()
        val normalizedRedditTarget = MediaClassifier.normalizeRedditShorthand(trimmed)
        val effectiveInputUrl = normalizedRedditTarget ?: trimmed
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

        _state.value = ExtractionState(
            isRunning = true,
            statusMessage = "Analyzing URL route...",
            phase = ExtractionPhase.DIRECT_MEDIA,
            domain = domain,
            items = emptyList()
        )

        extractionJob = scope.launch {
            val itemsMap = LinkedHashMap<String, ScrapedMediaItem>()
            val itemsMutex = Mutex()
            val lastDiagnosticError = java.util.concurrent.atomic.AtomicReference<String?>(null)

            suspend fun updateItems(
                newList: List<ScrapedMediaItem>,
                title: String? = null,
                msg: String? = null,
                phase: ExtractionPhase? = null
            ) = itemsMutex.withLock {
                val modified = MediaNormalizer.mergeCandidates(itemsMap, newList)
                if (modified || msg != null || phase != null || title != null) {
                    val currentItems = itemsMap.values.toList()
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

            suspend fun updateStatus(status: String, phase: ExtractionPhase? = null) = itemsMutex.withLock {
                _state.value = _state.value.copy(
                    statusMessage = status,
                    phase = phase ?: _state.value.phase
                )
            }

            try {
                val activeProxy = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.getActiveProxyUrlSync(context)
                val networkTimeout = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.getNetworkTimeoutSync(context)
                OkHttpDownloader.configureProxy(
                    enabled = activeProxy != null,
                    proxyUrl = activeProxy.orEmpty(),
                    timeoutSeconds = networkTimeout
                )

                // Step 1: OkHttp Direct-Media Fast Path Probe
                updateStatus("Probing URL headers with OkHttp...", ExtractionPhase.DIRECT_MEDIA)
                val directProbe = OkHttpDownloader.probeDirectMedia(requestContext.url)

                if (directProbe.isConfirmedDirectMedia) {
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
                        provenance = MediaProvenance(extractorEngine = "OkHttp Direct", pageUrl = requestContext.url)
                    )

                    updateItems(listOf(directItem), title = derivedTitle, phase = ExtractionPhase.COMPLETED)
                    finishSuccess(itemsMap.size)
                    return@launch
                }

                // Step 2: Inconclusive / Not direct media -> Concurrent Multi-Engine Discovery
                updateStatus("Initiating concurrent discovery (yt-dlp, gallery-dl, Browser Session)...", ExtractionPhase.YT_DLP)

                val supportInfo = withContext(Dispatchers.IO) {
                    GalleryDlEngine.checkSupport(requestContext.url)
                }

                val cookies = OkHttpDownloader.sharedCookieJar.getCookieString(requestContext.url).takeIf { it.isNotBlank() }
                val redditPrefs = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.getRedditConfigSync(context)
                val options = GalleryDlOptions(
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
                    maxHeight = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.getVideoQualityCapSync(context).maxHeight,
                    formatMode = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.getFormatModeSync(context).id,
                    downloadPlaylist = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.isDownloadPlaylistSync(context),
                    downloadSubtitles = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.isDownloadSubtitlesSync(context),
                    includeAutoSubtitles = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.isIncludeAutoSubtitlesSync(context),
                    subtitleLanguages = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.getSubtitleLanguagesSync(context),
                    retries = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.getRetryCountSync(context)
                )

                // Launch concurrent discovery jobs:
                // Rule 1: No exclusive extractor selection. Both yt-dlp and gallery-dl run concurrently.
                // Rule 2: Browser discovery is complementary. Runs to discover dynamic network requests and DOM media.
                // Rule 5: One unified candidate manager.
                val lowerRequestUrl = requestContext.url.lowercase(java.util.Locale.ROOT)
                val isInstagramTarget = lowerRequestUrl.contains("instagram.com") || lowerRequestUrl.contains("instagr.am")
                val isRedditTarget = normalizedRedditTarget != null ||
                    lowerRequestUrl.contains("reddit.com") ||
                    lowerRequestUrl.contains("redd.it") ||
                    lowerRequestUrl.contains("redgifs.com")
                val detectedPlatform = SupportedMediaPlatform.detect(requestContext.url)
                val isSpotifyTarget = detectedPlatform == SupportedMediaPlatform.SPOTIFY
                val isRadioJavanTarget = detectedPlatform == SupportedMediaPlatform.RADIOJAVAN
                val isXSpaceTarget = SupportedMediaPlatform.isXSpace(requestContext.url)
                val isTumblrTarget = lowerRequestUrl.contains("tumblr.com")
                val isPixivTarget = lowerRequestUrl.contains("pixiv.net")
                val isFacebookTarget = lowerRequestUrl.contains("facebook.com") || lowerRequestUrl.contains("fb.watch")
                val isTwitterStatusTarget = (lowerRequestUrl.contains("twitter.com/") || lowerRequestUrl.contains("x.com/")) && lowerRequestUrl.contains("/status/")
                val recursiveCrawlEnabled = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.isRecursiveCrawlEnabledSync(context)
                val recursiveCrawlConfig = com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager.getRecursiveCrawlConfigSync(context)
                val captchaChallengeUrl = java.util.concurrent.atomic.AtomicReference<String?>(null)

                val redditDlJob = if (isRedditTarget) {
                    launch(Dispatchers.IO) {
                        if (GalleryDlEngine.isRedditDlAvailable()) {
                            try {
                                val res = GalleryDlEngine.extractReddit(requestContext.url, options)
                                if (res.success && res.items.isNotEmpty()) {
                                    updateItems(res.items, title = res.pageTitle, msg = "Discovered ${res.items.size} items via reddit-dl")
                                } else if (!res.errorMessage.isNullOrBlank()) {
                                    lastDiagnosticError.compareAndSet(null, res.errorMessage)
                                }
                            } catch (e: Exception) {
                                Log.w("ExtractionCoordinator", "Concurrent reddit-dl error", e)
                            }
                        }
                    }
                } else null

                val instaloaderJob = if (isInstagramTarget) {
                    launch(Dispatchers.IO) {
                        if (GalleryDlEngine.isInstaloaderAvailable()) {
                            try {
                                val res = GalleryDlEngine.extractInstagram(requestContext.url, options)
                                if (res.success && res.items.isNotEmpty()) {
                                    updateItems(res.items, title = res.pageTitle, msg = "Discovered ${res.items.size} items via Instaloader")
                                } else if (!res.errorMessage.isNullOrBlank()) {
                                    lastDiagnosticError.compareAndSet(null, res.errorMessage)
                                }
                            } catch (e: Exception) {
                                Log.w("ExtractionCoordinator", "Concurrent Instaloader error", e)
                            }
                        }
                    }
                } else null

                val spotifyJob = if (isSpotifyTarget) {
                    launch(Dispatchers.IO) {
                        if (GalleryDlEngine.isMediaDownloaderAvailable()) {
                            try {
                                val res = GalleryDlEngine.extractSpotify(requestContext.url, options)
                                if (res.success && res.items.isNotEmpty()) {
                                    updateItems(res.items, title = res.pageTitle, msg = "Matched ${res.items.size} Spotify audio track(s) via YouTube")
                                } else if (!res.errorMessage.isNullOrBlank()) {
                                    lastDiagnosticError.compareAndSet(null, res.errorMessage)
                                }
                            } catch (e: Exception) {
                                Log.w("ExtractionCoordinator", "Concurrent Spotify error", e)
                            }
                        }
                    }
                } else null

                val radioJavanJob = if (isRadioJavanTarget) {
                    launch(Dispatchers.IO) {
                        if (GalleryDlEngine.isMediaDownloaderAvailable()) {
                            try {
                                val res = GalleryDlEngine.extractRadioJavan(requestContext.url, options)
                                if (res.success && res.items.isNotEmpty()) {
                                    updateItems(res.items, title = res.pageTitle, msg = "Discovered ${res.items.size} RadioJavan item(s)")
                                } else if (!res.errorMessage.isNullOrBlank()) {
                                    lastDiagnosticError.compareAndSet(null, res.errorMessage)
                                }
                            } catch (e: Exception) {
                                Log.w("ExtractionCoordinator", "Concurrent RadioJavan error", e)
                            }
                        }
                    }
                } else null

                val xSpaceJob = if (isXSpaceTarget) {
                    launch(Dispatchers.IO) {
                        if (GalleryDlEngine.isMediaDownloaderAvailable()) {
                            try {
                                val res = GalleryDlEngine.extractXSpace(requestContext.url, options)
                                if (res.success && res.items.isNotEmpty()) {
                                    updateItems(res.items, title = res.pageTitle, msg = "Resolved Twitter/X Space HLS audio stream")
                                } else if (!res.errorMessage.isNullOrBlank()) {
                                    lastDiagnosticError.compareAndSet(null, res.errorMessage)
                                }
                            } catch (e: Exception) {
                                Log.w("ExtractionCoordinator", "Concurrent X Space error", e)
                            }
                        }
                    }
                } else null

                val tumblrJob = if (isTumblrTarget) {
                    launch(Dispatchers.IO) {
                        if (GalleryDlEngine.isMediaScraperAvailable()) {
                            try {
                                val res = GalleryDlEngine.extractTumblr(requestContext.url, options)
                                if (res.success && res.items.isNotEmpty()) {
                                    updateItems(res.items, title = res.pageTitle, msg = "Discovered ${res.items.size} Tumblr item(s) via media-scraper")
                                }
                            } catch (e: Exception) {
                                Log.w("ExtractionCoordinator", "Concurrent Tumblr media-scraper error", e)
                            }
                        }
                    }
                } else null

                val pixivJob = if (isPixivTarget) {
                    launch(Dispatchers.IO) {
                        if (GalleryDlEngine.isMediaScraperAvailable()) {
                            try {
                                val res = GalleryDlEngine.extractPixiv(requestContext.url, options)
                                if (res.success && res.items.isNotEmpty()) {
                                    updateItems(res.items, title = res.pageTitle, msg = "Discovered ${res.items.size} Pixiv artwork page(s) via media-scraper")
                                }
                            } catch (e: Exception) {
                                Log.w("ExtractionCoordinator", "Concurrent Pixiv media-scraper error", e)
                            }
                        }
                    }
                } else null

                val twitterScraperJob = if (isTwitterStatusTarget) {
                    launch(Dispatchers.IO) {
                        if (GalleryDlEngine.isMediaScraperAvailable()) {
                            try {
                                val res = GalleryDlEngine.extractTwitterMediaScraper(requestContext.url, options)
                                if (res.success && res.items.isNotEmpty()) {
                                    updateItems(res.items, title = res.pageTitle, msg = "Discovered ${res.items.size} Twitter/X media item(s) via media-scraper")
                                }
                            } catch (e: Exception) {
                                Log.w("ExtractionCoordinator", "Concurrent Twitter media-scraper error", e)
                            }
                        }
                    }
                } else null

                val facebookScraperJob = if (isFacebookTarget) {
                    launch(Dispatchers.IO) {
                        if (GalleryDlEngine.isMediaScraperAvailable()) {
                            try {
                                val res = GalleryDlEngine.extractFacebookMediaScraper(requestContext.url, options)
                                if (res.success && res.items.isNotEmpty()) {
                                    updateItems(res.items, title = res.pageTitle, msg = "Discovered ${res.items.size} Facebook item(s) via media-scraper")
                                }
                            } catch (e: Exception) {
                                Log.w("ExtractionCoordinator", "Concurrent Facebook media-scraper error", e)
                            }
                        }
                    }
                } else null

                val recursiveCrawlJob = if (recursiveCrawlEnabled && expectation != TargetMediaExpectation.VIDEO_OR_STREAM) {
                    launch(Dispatchers.IO) {
                        try {
                            val crawler = RecursiveSiteCrawler()
                            val crawlRes = crawler.crawl(
                                startUrl = requestContext.url,
                                config = recursiveCrawlConfig,
                                context = context,
                                onProgress = { p ->
                                    launch {
                                        updateStatus(
                                            "Recursive crawl (${p.pagesCrawled}/${p.maxPages} pages, depth ${p.currentDepth}): ${p.mediaDiscovered} media",
                                            ExtractionPhase.STATIC_SCRAPING
                                        )
                                    }
                                },
                                onMediaDiscovered = { discovered ->
                                    launch { updateItems(discovered) }
                                }
                            )
                            if (crawlRes.items.isNotEmpty()) {
                                updateItems(
                                    crawlRes.items,
                                    title = crawlRes.pageTitle,
                                    msg = "Recursive crawl discovered ${crawlRes.items.size} media across ${crawlRes.pagesCrawled} page(s)"
                                )
                            }
                            if (crawlRes.captchaDetected) {
                                captchaChallengeUrl.compareAndSet(null, crawlRes.captchaUrl ?: requestContext.url)
                                if (!crawlRes.errorMessage.isNullOrBlank()) {
                                    lastDiagnosticError.compareAndSet(null, crawlRes.errorMessage)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("ExtractionCoordinator", "Concurrent RecursiveSiteCrawler error", e)
                        }
                    }
                } else null

                val ytdlpJob = launch(Dispatchers.IO) {
                    if (GalleryDlEngine.isYtDlpAvailable()) {
                        try {
                            val res = GalleryDlEngine.extractYtDlp(requestContext.url, options)
                            if (res.success && res.items.isNotEmpty()) {
                                updateItems(res.items, title = res.pageTitle, msg = "Discovered ${res.items.size} items via yt-dlp")
                            } else if (!res.errorMessage.isNullOrBlank()) {
                                lastDiagnosticError.set(res.errorMessage)
                            }
                        } catch (e: Exception) {
                            Log.w("ExtractionCoordinator", "Concurrent yt-dlp error", e)
                        }
                    }
                }

                val galleryDlJob = launch(Dispatchers.IO) {
                    if (GalleryDlEngine.isGalleryDlAvailable()) {
                        try {
                            val res = GalleryDlEngine.extractGalleryDl(requestContext.url, options)
                            if (res.success && res.items.isNotEmpty()) {
                                updateItems(res.items, title = res.pageTitle, msg = "Discovered ${res.items.size} items via gallery-dl")
                            } else if (!res.errorMessage.isNullOrBlank()) {
                                lastDiagnosticError.compareAndSet(null, res.errorMessage)
                            }
                        } catch (e: Exception) {
                            Log.w("ExtractionCoordinator", "Concurrent gallery-dl error", e)
                        }
                    }
                }

                val browserJob = launch(Dispatchers.Main) {
                    try {
                        val browserRes = BrowserSession.extractHeadless(
                            context = context,
                            targetUrl = requestContext.url,
                            onStatusUpdate = { s -> launch { updateStatus(s, ExtractionPhase.BROWSER_INTERCEPTING) } },
                            onMediaDiscovered = { item -> launch { updateItems(listOf(item)) } }
                        )
                        if (browserRes.items.isNotEmpty()) {
                            updateItems(browserRes.items, title = browserRes.pageTitle)
                        } else if (!browserRes.errorMessage.isNullOrBlank()) {
                            lastDiagnosticError.compareAndSet(null, browserRes.errorMessage)
                        }
                    } catch (e: Exception) {
                        Log.w("ExtractionCoordinator", "Concurrent BrowserSession error", e)
                    }
                }

                // Await all concurrent discovery channels
                redditDlJob?.join()
                instaloaderJob?.join()
                spotifyJob?.join()
                radioJavanJob?.join()
                xSpaceJob?.join()
                tumblrJob?.join()
                pixivJob?.join()
                twitterScraperJob?.join()
                facebookScraperJob?.join()
                recursiveCrawlJob?.join()
                ytdlpJob.join()
                galleryDlJob.join()
                browserJob.join()

                // Check bot challenges or login requirement if candidates are still empty
                val diagnosticErrorSnapshot = lastDiagnosticError.get()
                val captchaUrlSnapshot = captchaChallengeUrl.get()
                val needsSignInDirect = BotDetectionMitigator.indicatesSignInRequired(diagnosticErrorSnapshot)
                val isSupportedByEngines = supportInfo.isSupported
                val needsCookiesOrBot = BotDetectionMitigator.indicatesCookiesOrBotVerificationNeeded(diagnosticErrorSnapshot) ||
                        (isSupportedByEngines && itemsMap.isEmpty() && cookies.isNullOrBlank())

                if (captchaUrlSnapshot != null && itemsMap.isEmpty()) {
                    _state.value = _state.value.copy(
                        isRunning = false,
                        requiresSignIn = true,
                        signInUrl = captchaUrlSnapshot,
                        statusMessage = "CAPTCHA verification required for $domain",
                        errorMessage = "CAPTCHA detected on $domain. Complete verification in the browser to continue."
                    )
                    return@launch
                }

                if (needsSignInDirect && itemsMap.isEmpty()) {
                    _state.value = _state.value.copy(
                        isRunning = false,
                        requiresSignIn = true,
                        signInUrl = requestContext.url,
                        statusMessage = "Sign-in required for $domain",
                        errorMessage = "Sign in to $domain to access and download this media."
                    )
                    return@launch
                } else if (needsCookiesOrBot && itemsMap.isEmpty()) {
                    updateStatus("Resolving session cookies in background for $domain...", ExtractionPhase.YT_DLP)
                    _state.value = _state.value.copy(isObtainingCookiesInBackground = true)

                    val cookieRes = withContext(Dispatchers.Main) {
                        BrowserSession.fetchCookiesInBackground(
                            context = context,
                            targetUrl = requestContext.url,
                            onStatusUpdate = { s -> launch { updateStatus(s) } }
                        )
                    }
                    _state.value = _state.value.copy(isObtainingCookiesInBackground = false)

                    if (cookieRes.requiresSignIn) {
                        _state.value = _state.value.copy(
                            isRunning = false,
                            requiresSignIn = true,
                            signInUrl = cookieRes.signInUrl ?: requestContext.url,
                            statusMessage = "Sign-in required for $domain",
                            errorMessage = "Sign in to $domain to access and download this media."
                        )
                        return@launch
                    } else if (!cookieRes.cookies.isNullOrBlank()) {
                        val updatedOptions = options.copy(cookies = cookieRes.cookies)
                        updateStatus("Cookies refreshed. Re-probing with yt-dlp & gallery-dl...", ExtractionPhase.YT_DLP)

                        if (isRedditTarget && GalleryDlEngine.isRedditDlAvailable()) {
                            val retryReddit = withContext(Dispatchers.IO) {
                                try {
                                    GalleryDlEngine.extractReddit(requestContext.url, updatedOptions)
                                } catch (e: Exception) {
                                    GalleryDlExtractResult(success = false, errorMessage = e.localizedMessage)
                                }
                            }
                            if (retryReddit.success && retryReddit.items.isNotEmpty()) {
                                updateItems(retryReddit.items, title = retryReddit.pageTitle)
                            }
                        }

                        if (isInstagramTarget && GalleryDlEngine.isInstaloaderAvailable()) {
                            val retryIg = withContext(Dispatchers.IO) {
                                try {
                                    GalleryDlEngine.extractInstagram(requestContext.url, updatedOptions)
                                } catch (e: Exception) {
                                    GalleryDlExtractResult(success = false, errorMessage = e.localizedMessage)
                                }
                            }
                            if (retryIg.success && retryIg.items.isNotEmpty()) {
                                updateItems(retryIg.items, title = retryIg.pageTitle)
                            }
                        }

                        val retryYt = withContext(Dispatchers.IO) {
                            try {
                                GalleryDlEngine.extractYtDlp(requestContext.url, updatedOptions)
                            } catch (e: Exception) {
                                GalleryDlExtractResult(success = false, errorMessage = e.localizedMessage)
                            }
                        }
                        if (retryYt.success && retryYt.items.isNotEmpty()) {
                            updateItems(retryYt.items, title = retryYt.pageTitle)
                        }

                        val retryGdl = withContext(Dispatchers.IO) {
                            try {
                                GalleryDlEngine.extractGalleryDl(requestContext.url, updatedOptions)
                            } catch (e: Exception) {
                                GalleryDlExtractResult(success = false, errorMessage = e.localizedMessage)
                            }
                        }
                        if (retryGdl.success && retryGdl.items.isNotEmpty()) {
                            updateItems(retryGdl.items, title = retryGdl.pageTitle)
                        }
                    }
                }

                // Evaluate merged discovery results
                if (itemsMap.isNotEmpty()) {
                    finishSuccess(itemsMap.size)
                    return@launch
                }

                // Final Evaluation if no items discovered
                val finalReason: String = diagnosticErrorSnapshot?.takeIf { it.isNotBlank() }
                    ?: "No media items discovered by any discovery engine."

                _state.value = _state.value.copy(
                    isRunning = false,
                    phase = ExtractionPhase.ERROR,
                    statusMessage = finalReason,
                    errorMessage = finalReason
                )
            } catch (ce: CancellationException) {
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
