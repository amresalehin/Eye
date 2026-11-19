package com.amresalehin.emregallery.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.URI
import java.net.URL
import java.util.Locale

/**
 * Configuration for recursive BFS site crawling (ported from mitchlinDEV/media-scraper).
 */
data class RecursiveCrawlConfig(
    val maxDepth: Int = 3,
    val maxPages: Int = 20,
    val sameDomainOnly: Boolean = true,
    val delayMs: Long = 500L,
    val maxRetries: Int = 3,
    val saveSitemap: Boolean = true,
    val verbosityMode: String = "normal"
)

/**
 * Live progress event emitted during a recursive BFS crawl.
 */
data class RecursiveCrawlProgress(
    val pagesCrawled: Int,
    val maxPages: Int,
    val queueSize: Int,
    val mediaDiscovered: Int,
    val currentUrl: String,
    val currentDepth: Int
)

/**
 * Final outcome of a recursive BFS crawl, including the visited sitemap and CAPTCHA signal.
 */
data class RecursiveCrawlResult(
    val startUrl: String,
    val pageTitle: String,
    val domain: String,
    val items: List<ScrapedMediaItem>,
    val visitedUrls: List<String>,
    val sitemapFilePath: String? = null,
    val captchaDetected: Boolean = false,
    val captchaUrl: String? = null,
    val pagesCrawled: Int = visitedUrls.size,
    val errorMessage: String? = null
)

/**
 * Native Kotlin Breadth-First Search (BFS) recursive web media crawler implementing
 * mitchlinDEV/media-scraper's `scrape_recursive()` and `scrape_recursive_full_site()`:
 * - Same-domain BFS link traversal up to configurable depth (default 3) and page cap
 * - Automatic HTTPS URL normalization and fragment stripping
 * - Per-page retry with backoff (default 3 attempts)
 * - CAPTCHA / bot-challenge detection to pause and prompt user browser verification
 * - Sitemap logging (`sitemap.txt`) of all visited URLs
 * - Windows & Android-safe filename sanitization (`sanitizeFilename`)
 */
class RecursiveSiteCrawler(
    private val staticScraper: StaticMediaScraper = StaticMediaScraper()
) {

    suspend fun crawl(
        startUrl: String,
        config: RecursiveCrawlConfig = RecursiveCrawlConfig(),
        context: Context? = null,
        onProgress: ((RecursiveCrawlProgress) -> Unit)? = null,
        onMediaDiscovered: ((List<ScrapedMediaItem>) -> Unit)? = null
    ): RecursiveCrawlResult = withContext(Dispatchers.IO) {
        val normalizedStart = normalizeHttpsUrl(startUrl, null)
            ?: return@withContext RecursiveCrawlResult(
                startUrl = startUrl,
                pageTitle = "",
                domain = "",
                items = emptyList(),
                visitedUrls = emptyList(),
                errorMessage = "Invalid URL for recursive crawl"
            )

        val baseHost = extractHost(normalizedStart)
        val clampedDepth = config.maxDepth.coerceIn(0, 5)
        val clampedPages = config.maxPages.coerceIn(1, 100)
        val clampedRetries = config.maxRetries.coerceIn(1, 5)
        val clampedDelay = config.delayMs.coerceIn(0L, 5_000L)

        val queue = ArrayDeque<Pair<String, Int>>()
        queue.addLast(normalizedStart to 0)

        val visited = LinkedHashSet<String>()
        val discoveredItems = LinkedHashMap<String, ScrapedMediaItem>()
        var rootTitle = ""
        var captchaDetected = false
        var captchaUrl: String? = null
        var lastError: String? = null

        while (queue.isNotEmpty() && visited.size < clampedPages) {
            currentCoroutineContext().ensureActive()
            val (currentUrl, depth) = queue.removeFirst()
            if (!visited.add(currentUrl)) continue

            onProgress?.invoke(
                RecursiveCrawlProgress(
                    pagesCrawled = visited.size,
                    maxPages = clampedPages,
                    queueSize = queue.size,
                    mediaDiscovered = discoveredItems.size,
                    currentUrl = currentUrl,
                    currentDepth = depth
                )
            )

            val response = fetchWithRetries(currentUrl, clampedRetries, clampedDelay)
            if (response == null || !response.isSuccessful) {
                lastError = response?.errorMessage ?: "Failed to fetch $currentUrl"
                continue
            }

            val finalUrl = response.finalUrl.ifBlank { currentUrl }
            val contentType = response.contentType.lowercase(Locale.ROOT)
            if (contentType.startsWith("image/") || contentType.startsWith("video/") ||
                contentType.startsWith("audio/") || contentType.contains("mpegurl") || contentType.contains("dash")
            ) {
                val directClass = MediaClassifier.classify(finalUrl, contentType)
                val fileName = sanitizeFilename(
                    MediaClassifier.deriveFileName(finalUrl, directClass.format, discoveredItems.size)
                )
                val item = ScrapedMediaItem(
                    id = "crawl_direct_${System.currentTimeMillis()}_${discoveredItems.size}",
                    url = finalUrl,
                    title = fileName,
                    isVideo = directClass.isVideo,
                    isAudio = directClass.isAudio,
                    isStream = directClass.isStream,
                    category = directClass.category,
                    format = directClass.format,
                    thumbnailUrl = if (directClass.category == MediaCategory.IMAGES) finalUrl else null,
                    mimeType = directClass.mimeType,
                    source = "media-scraper (Recursive)",
                    provenance = MediaProvenance(
                        extractorEngine = "Recursive Site Crawler",
                        pageUrl = currentUrl
                    ),
                    downloadHeaders = mapOf(
                        "User-Agent" to OkHttpDownloader.DEFAULT_USER_AGENT,
                        "Referer" to currentUrl
                    )
                )
                val key = MediaClassifier.deduplicationKey(item.url)
                if (!discoveredItems.containsKey(key)) {
                    discoveredItems[key] = item
                    onMediaDiscovered?.invoke(listOf(item))
                }
                continue
            }

            val htmlBody = response.bodyString.orEmpty()
            if (detectCaptchaChallenge(htmlBody)) {
                captchaDetected = true
                captchaUrl = finalUrl
                continue
            }

            val doc: Document = Jsoup.parse(htmlBody, finalUrl)
            if (rootTitle.isBlank()) {
                rootTitle = sanitizeFilename(doc.title().trim(), fallback = baseHost)
            }

            val pageItems = staticScraper.parseDocumentMedia(doc, finalUrl).map { item ->
                item.copy(
                    title = sanitizeFilename(item.title, fallback = "media"),
                    source = if (depth == 0) item.source else "media-scraper (Depth $depth)"
                )
            }

            val newlyAdded = mutableListOf<ScrapedMediaItem>()
            for (item in pageItems) {
                val key = MediaClassifier.deduplicationKey(item.url)
                val existing = discoveredItems[key]
                if (existing == null) {
                    discoveredItems[key] = item
                    newlyAdded.add(item)
                } else if (MediaClassifier.compareMediaQuality(item, existing) > 0) {
                    discoveredItems[key] = item.copy(
                        id = existing.id,
                        thumbnailUrl = item.thumbnailUrl ?: existing.thumbnailUrl
                    )
                }
            }
            if (newlyAdded.isNotEmpty()) {
                onMediaDiscovered?.invoke(newlyAdded)
            }

            if (depth < clampedDepth) {
                val nextLinks = extractCrawlableLinks(doc, finalUrl, baseHost, config.sameDomainOnly)
                for (nextLink in nextLinks) {
                    if (!visited.contains(nextLink)) {
                        queue.addLast(nextLink to (depth + 1))
                    }
                }
            }

            if (clampedDelay > 0L && queue.isNotEmpty() && visited.size < clampedPages) {
                delay(clampedDelay)
            }
        }

        val visitedList = visited.toList()
        val sitemapPath = if (config.saveSitemap && context != null && visitedList.isNotEmpty()) {
            writeSitemapFile(context, baseHost, visitedList)
        } else null

        RecursiveCrawlResult(
            startUrl = normalizedStart,
            pageTitle = rootTitle.ifBlank { baseHost },
            domain = baseHost,
            items = discoveredItems.values.toList(),
            visitedUrls = visitedList,
            sitemapFilePath = sitemapPath,
            captchaDetected = captchaDetected,
            captchaUrl = captchaUrl,
            pagesCrawled = visitedList.size,
            errorMessage = when {
                discoveredItems.isNotEmpty() -> null
                captchaDetected -> "CAPTCHA verification detected at ${captchaUrl ?: normalizedStart}"
                else -> lastError ?: "No media discovered across ${visitedList.size} crawled page(s)"
            }
        )
    }

    private suspend fun fetchWithRetries(
        url: String,
        maxRetries: Int,
        retryDelayMs: Long
    ): OkHttpDownloader.HttpResponse? {
        var lastResult: OkHttpDownloader.HttpResponse? = null
        for (attempt in 1..maxRetries) {
            currentCoroutineContext().ensureActive()
            if (!HostRateLimiter.acquire(url)) return lastResult
            val res = runCatching { OkHttpDownloader.getHtml(url) }.getOrNull()
            if (res != null && res.isSuccessful) {
                if (detectCaptchaChallenge(res.bodyString)) {
                    HostRateLimiter.recordRateLimitOrBotChallenge(url)
                    return res
                }
                HostRateLimiter.recordSuccess(url)
                return res
            }
            lastResult = res
            if (res != null && res.statusCode == 429) {
                HostRateLimiter.recordRateLimitOrBotChallenge(url)
                return res
            }
            if (res != null && HostRateLimiter.classifyError(res.statusCode, res.errorMessage) == HostRateLimiter.ErrorCategory.PERMANENT) {
                return res
            }
            if (attempt < maxRetries && retryDelayMs > 0L) {
                delay((retryDelayMs * attempt).coerceAtMost(5_000L))
            }
        }
        return lastResult
    }

    private fun writeSitemapFile(context: Context, domain: String, visitedUrls: List<String>): String? {
        return runCatching {
            val safeDomain = sanitizeFilename(domain, fallback = "site")
            val dir = File(context.cacheDir, "media_scraper_sitemaps")
            if (!dir.exists()) dir.mkdirs()
            val sitemapFile = File(dir, "${safeDomain}_sitemap.txt")
            sitemapFile.writeText(visitedUrls.joinToString(separator = "\n") + "\n")
            sitemapFile.absolutePath
        }.getOrNull()
    }

    companion object {
        private val INVALID_FILENAME_REGEX = Regex("""[<>:"/\\|?*\n\r\t]+""")
        private val MULTI_SPACE_REGEX = Regex("""\s+""")

        private val CAPTCHA_MARKERS = listOf(
            "captcha",
            "g-recaptcha",
            "h-captcha",
            "cf-challenge",
            "cf-turnstile",
            "verify you are human",
            "are you a robot",
            "bot detection"
        )

        private val NON_PAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "tif", "tiff", "avif", "heic",
            "mp4", "m4v", "webm", "mkv", "mov", "avi", "wmv", "flv", "ogv", "3gp", "ts", "m3u8", "mpd",
            "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "wma",
            "pdf", "zip", "rar", "7z", "tar", "gz", "css", "js", "mjs", "map",
            "woff", "woff2", "ttf", "otf", "eot", "xml", "rss", "atom", "json", "apk", "exe", "dmg"
        )

        /**
         * Windows & Android-safe filename sanitizer (mitchlinDEV/media-scraper 2025 update).
         * Strips `[<>:"/\|?*\n\r\t]`, collapses whitespace, and trims trailing spaces/dots.
         */
        fun sanitizeFilename(raw: String?, fallback: String = "media"): String {
            if (raw.isNullOrBlank()) return fallback
            val stripped = INVALID_FILENAME_REGEX.replace(raw, "_")
            val collapsed = MULTI_SPACE_REGEX.replace(stripped, " ").trim(' ', '.')
            if (collapsed.isBlank()) return fallback
            return collapsed.take(180)
        }

        /**
         * Detects CAPTCHA or human-verification challenges in page HTML/text.
         */
        fun detectCaptchaChallenge(html: String?): Boolean {
            if (html.isNullOrBlank()) return false
            val lower = html.lowercase(Locale.ROOT)
            if (CAPTCHA_MARKERS.any { it != "captcha" && lower.contains(it) }) return true
            val visibleText = lower.replace(Regex("<[^>]+>"), " ")
            return visibleText.contains("captcha")
        }

        /**
         * Resolves relative links against baseUrl, strips fragments, and normalizes http:// to https://.
         */
        fun normalizeHttpsUrl(rawUrl: String?, baseUrl: String?, forceHttps: Boolean = true): String? {
            if (rawUrl.isNullOrBlank()) return null
            val trimmed = rawUrl.trim()
            if (trimmed.startsWith("#") ||
                trimmed.startsWith("javascript:", ignoreCase = true) ||
                trimmed.startsWith("mailto:", ignoreCase = true) ||
                trimmed.startsWith("tel:", ignoreCase = true) ||
                trimmed.startsWith("data:", ignoreCase = true) ||
                trimmed.startsWith("blob:", ignoreCase = true)
            ) {
                return null
            }

            val withScheme = if (baseUrl == null && !trimmed.contains("://")) {
                "https://$trimmed"
            } else {
                trimmed
            }

            return runCatching {
                val resolved = if (baseUrl != null) URL(URL(baseUrl), withScheme) else URL(withScheme)
                val proto = resolved.protocol.lowercase(Locale.ROOT)
                if (proto != "http" && proto != "https") return null
                val host = resolved.host.lowercase(Locale.ROOT)
                if (host.isBlank()) return null
                val finalProto = if (forceHttps && proto == "http") "https" else proto
                val port = if (resolved.port == -1 ||
                    (finalProto == "https" && resolved.port == 443) ||
                    (finalProto == "http" && resolved.port == 80)
                ) -1 else resolved.port
                val path = resolved.path.ifBlank { "/" }
                URI(finalProto, null, host, port, path, resolved.query, null).toASCIIString()
            }.getOrNull()
        }

        fun extractHost(url: String): String {
            return runCatching { URL(url).host.lowercase(Locale.ROOT) }.getOrDefault("")
        }

        fun isCrawlablePageUrl(url: String): Boolean {
            val cleanPath = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
            val lastSegment = cleanPath.substringAfterLast('/', "")
            val ext = lastSegment.substringAfterLast('.', "")
            if (ext.isNotBlank() && ext != lastSegment && ext in NON_PAGE_EXTENSIONS) {
                return false
            }
            return true
        }

        fun extractCrawlableLinks(
            doc: Document,
            pageUrl: String,
            baseHost: String,
            sameDomainOnly: Boolean
        ): List<String> {
            val links = LinkedHashSet<String>()
            for (anchor in doc.select("a[href]")) {
                val href = anchor.attr("href").trim()
                if (href.isBlank()) continue
                val normalized = normalizeHttpsUrl(href, pageUrl, forceHttps = true) ?: continue
                if (!isCrawlablePageUrl(normalized)) continue
                if (sameDomainOnly && extractHost(normalized) != baseHost) continue
                links.add(normalized)
            }
            return links.toList()
        }
    }
}
