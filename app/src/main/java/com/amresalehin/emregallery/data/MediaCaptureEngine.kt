package com.amresalehin.emregallery.data

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * Unified Media Capture Engine.
 * Single source of truth for:
 * 1. Network request interception & stream/media classification.
 * 2. Deep JavaScript DOM, srcset, CSS background, and JSON-LD schema inspection.
 * 3. MutationObserver injection for dynamic DOM change detection.
 * 4. Adaptive scrolling logic.
 * 5. Media normalization, deduplication, and ScrapedMediaItem construction with headers.
 */
object MediaCaptureEngine {

    /**
     * Shared JavaScript DOM scan script that inspects:
     * - <img>, <picture source>, data-src, data-original, srcset, data-lazy-src
     * - <video>, <video source>, poster
     * - <audio>, <audio source>
     * - Schema.org JSON-LD (ImageObject, VideoObject, contentUrl)
     * - Content container CSS background-image
     * - IFrames pointing directly to media
     */
    private val IMAGE_FORMAT_QUERY_REGEX = Regex("""[?&](?:format|fmt|ext)=(?:jpg|jpeg|png|webp|avif)""")

    fun getJsDomScanScript(): String {
        return """
            (function() {
                var list = [];
                var seen = Object.create(null);
                var baseHref = document.baseURI || window.location.href;
                function resolveAbs(u) {
                    if (!u || u.indexOf('data:') === 0 || u.indexOf('blob:') === 0) return null;
                    try {
                        return new URL(u, baseHref).href;
                    } catch(e) {
                        if (u.indexOf('//') === 0) return 'https:' + u;
                        return u;
                    }
                }
                function pushCandidate(entry) {
                    if (!entry || !entry.u) return;
                    var key = entry.u + '|' + (entry.s || '');
                    if (seen[key]) return;
                    seen[key] = true;
                    list.push(entry);
                }

                function isChromeElement(el) {
                    if (!el) return false;
                    var chromeParent = el.closest('header, nav, footer, aside, menu, form, [role="banner"], [role="navigation"], [role="contentinfo"], .header, .site-header, .nav, .navbar, .site-nav, .menu, #header, #nav, #navbar, #footer, #mw-navigation, #p-logo, .footer, .site-footer, .breadcrumb, .breadcrumbs, .pagination, .toolbar, .ad, .ads, .advertisement, .cookie, .cookie-banner, .consent');
                    if (chromeParent) {
                        var contentParent = el.closest('article, main, [role="main"], #content, #main-content, .mw-parser-output, figure, .gallery, .shots-grid, .shots, .dribbble-shot, .entry-content, .post-content');
                        if (!contentParent || chromeParent.contains(contentParent) === false) {
                            return true;
                        }
                    }
                    var idAndClass = ((el.id || '') + ' ' + (el.className || '') + ' ' + (el.getAttribute('alt') || '')).toLowerCase();
                    if (/logo|favicon|avatar_default|badge|donate|donation|fundraising|paypal|magnifying-glass|nav-icon|menu-icon|close-icon|arrow-|chevron|bullet|spinner|1x1/.test(idAndClass)) {
                        return true;
                    }
                    return false;
                }

                // 1. Deep Image Inspection (img, picture, data attributes, srcset)
                var images = document.querySelectorAll('img, picture source');
                for (var i = 0; i < images.length; i++) {
                    var img = images[i];
                    if (isChromeElement(img)) continue;

                    // Extract srcset highest resolution candidates
                    var srcset = img.getAttribute('srcset') || img.getAttribute('data-srcset');
                    if (srcset) {
                        var parts = srcset.split(',');
                        for (var p = 0; p < parts.length; p++) {
                            var trimmed = parts[p].trim();
                            if (trimmed) {
                                var splitBits = trimmed.split(/\s+/);
                                var u = splitBits[0];
                                var descriptor = splitBits[1] || '';
                                var absSrcset = resolveAbs(u);
                                if (absSrcset) {
                                    pushCandidate({u: absSrcset, s: 'SRCSET', r: descriptor});
                                }
                            }
                        }
                    }

                    // Direct src & data-lazy attributes
                    var dataSrc = img.getAttribute('data-src') || img.getAttribute('data-original') ||
                                  img.getAttribute('data-lazy-src') || img.getAttribute('data-large-file') ||
                                  img.getAttribute('data-hi-res-src') || img.getAttribute('data-zoom-image');
                    if (dataSrc) {
                        var absData = resolveAbs(dataSrc);
                        if (absData) pushCandidate({u: absData, s: 'DATA_SRC'});
                    }
                    var rawSrc = img.src || img.getAttribute('src');
                    if (rawSrc) {
                        var absRaw = resolveAbs(rawSrc);
                        if (absRaw) pushCandidate({u: absRaw, s: 'DOM'});
                    }
                }

                // 2. Video Elements & Sources
                var videos = document.querySelectorAll('video');
                for (var v = 0; v < videos.length; v++) {
                    var vid = videos[v];
                    var poster = resolveAbs(vid.getAttribute('poster') || vid.poster);
                    var vDur = (typeof vid.duration === 'number' && isFinite(vid.duration) && vid.duration > 0)
                        ? vid.duration
                        : (vid.getAttribute('duration') || vid.getAttribute('data-duration') || null);
                    var chosenVideo = resolveAbs(vid.src);
                    if (!chosenVideo) {
                        var vsources = vid.querySelectorAll('source');
                        for (var j = 0; j < vsources.length; j++) {
                            chosenVideo = resolveAbs(vsources[j].src);
                            if (chosenVideo) break;
                        }
                    }
                    if (chosenVideo) {
                        pushCandidate({u: chosenVideo, s: 'DOM', p: poster, r: 'video', d: vDur});
                    } else if (poster) {
                        pushCandidate({u: poster, s: 'POSTER', r: 'poster'});
                    }
                }

                // 3. Audio Elements
                var audios = document.querySelectorAll('audio');
                for (var a = 0; a < audios.length; a++) {
                    var au = audios[a];
                    var aDur = (typeof au.duration === 'number' && isFinite(au.duration) && au.duration > 0)
                        ? au.duration
                        : (au.getAttribute('duration') || au.getAttribute('data-duration') || null);
                    var chosenAudio = resolveAbs(au.src);
                    if (!chosenAudio) {
                        var asources = au.querySelectorAll('source');
                        for (var k = 0; k < asources.length; k++) {
                            chosenAudio = resolveAbs(asources[k].src);
                            if (chosenAudio) break;
                        }
                    }
                    if (chosenAudio) {
                        pushCandidate({u: chosenAudio, s: 'DOM', r: 'audio', d: aDur});
                    }
                }

                // 4. Schema.org JSON-LD (ImageObject, VideoObject)
                var jsonLdScripts = document.querySelectorAll('script[type="application/ld+json"]');
                for (var jl = 0; jl < jsonLdScripts.length; jl++) {
                    try {
                        var parsed = JSON.parse(jsonLdScripts[jl].innerText);
                        var arr = Array.isArray(parsed) ? parsed : [parsed];
                        for (var idx = 0; idx < arr.length; idx++) {
                            var item = arr[idx];
                            if (item.contentUrl) {
                                var cu = resolveAbs(item.contentUrl);
                                if (cu) pushCandidate({u: cu, s: 'DOM', r: 'json-ld', d: item.duration || null});
                            }
                            if (item.thumbnailUrl) {
                                var tu = resolveAbs(item.thumbnailUrl);
                                if (tu) pushCandidate({u: tu, s: 'DOM', r: 'json-ld-thumb'});
                            }
                            if (item.image && typeof item.image === 'string') {
                                var iu = resolveAbs(item.image);
                                if (iu) pushCandidate({u: iu, s: 'DOM', r: 'json-ld-image'});
                            }
                        }
                    } catch(e) {}
                }

                // 5. CSS background-image in content containers (check inline style before forced getComputedStyle)
                var bgElements = document.querySelectorAll('main, article, figure, [role="main"], #content, .gallery, .shots-grid, .shots, .photo, .hero');
                var count = Math.min(bgElements.length, 24);
                for (var b = 0; b < count; b++) {
                    var bEl = bgElements[b];
                    if (!isChromeElement(bEl)) {
                        var inlineBg = bEl.style ? bEl.style.backgroundImage : '';
                        var bg = (inlineBg && inlineBg.indexOf('url(') !== -1)
                            ? inlineBg
                            : ((bEl.clientWidth >= 120 && bEl.clientHeight >= 120) ? window.getComputedStyle(bEl).backgroundImage : '');
                        if (bg && bg.indexOf('url(') !== -1) {
                            var match = bg.match(/url\(['"]?(.*?)['"]?\)/);
                            if (match && match[1]) {
                                var bgu = resolveAbs(match[1]);
                                if (bgu) pushCandidate({u: bgu, s: 'CSS', r: 'background'});
                            }
                        }
                    }
                }

                // 6. Direct media IFrames
                var iframes = document.querySelectorAll('iframe');
                for (var f = 0; f < iframes.length; f++) {
                    var ifr = resolveAbs(iframes[f].src);
                    if (ifr && /\.(?:mp4|webm|mkv|mov|mp3|m4a|wav|jpe?g|png|webp|gif|avif)(?:[?#]|$)/i.test(ifr)) {
                        pushCandidate({u: ifr, s: 'IFRAME', r: 'iframe'});
                    }
                }

                return JSON.stringify(list);
            })();
        """.trimIndent()
    }

    /**
     * Shared JavaScript MutationObserver injection for dynamic DOM additions.
     */
    fun getMutationObserverScript(): String {
        return """
            (function() {
                if (window.__emreObserverInstalled) return;
                window.__emreObserverInstalled = true;
                var seenUrls = new Set();
                var pendingNodes = new Set();
                var flushTimer = null;

                function report(u) {
                    if (!u || u.indexOf('data:') === 0 || u.indexOf('blob:') === 0) return;
                    if (seenUrls.has(u)) return;
                    try {
                        var abs = new URL(u, document.baseURI || window.location.href).href;
                        if (seenUrls.has(abs)) return;
                        if (seenUrls.size > 2500) seenUrls.clear();
                        seenUrls.add(u);
                        seenUrls.add(abs);
                        if (window.EmreBridge && window.EmreBridge.reportMedia) {
                            window.EmreBridge.reportMedia(abs);
                        }
                    } catch(e) {}
                }

                function inspectSingleElement(node) {
                    if (!node || node.nodeType !== 1) return false;
                    var tag = node.tagName ? node.tagName.toUpperCase() : '';
                    if (tag === 'IMG') {
                        report(node.src);
                        report(node.getAttribute('data-src'));
                        report(node.getAttribute('data-original'));
                        report(node.getAttribute('data-lazy-src'));
                        report(node.getAttribute('data-large-file'));
                        report(node.getAttribute('data-hi-res-src'));
                        var srcset = node.getAttribute('srcset') || node.getAttribute('data-srcset');
                        if (srcset) {
                            var parts = srcset.split(',');
                            for (var i = 0; i < parts.length; i++) {
                                var bit = parts[i].trim().split(/\s+/)[0];
                                if (bit) report(bit);
                            }
                        }
                        return true;
                    } else if (tag === 'VIDEO') {
                        if (node.src && node.src.indexOf('blob:') !== 0 && node.src.indexOf('data:') !== 0) {
                            report(node.src);
                        } else if (node.querySelectorAll) {
                            var vSources = node.querySelectorAll('source');
                            for (var vs = 0; vs < vSources.length; vs++) {
                                var vsSrc = vSources[vs].src;
                                if (vsSrc && vsSrc.indexOf('blob:') !== 0 && vsSrc.indexOf('data:') !== 0) {
                                    report(vsSrc);
                                    break;
                                }
                            }
                        }
                        report(node.getAttribute('poster'));
                        return true;
                    } else if (tag === 'AUDIO') {
                        if (node.src && node.src.indexOf('blob:') !== 0 && node.src.indexOf('data:') !== 0) {
                            report(node.src);
                        } else if (node.querySelectorAll) {
                            var aSources = node.querySelectorAll('source');
                            for (var as = 0; as < aSources.length; as++) {
                                var asSrc = aSources[as].src;
                                if (asSrc && asSrc.indexOf('blob:') !== 0 && asSrc.indexOf('data:') !== 0) {
                                    report(asSrc);
                                    break;
                                }
                            }
                        }
                        return true;
                    } else if (tag === 'SOURCE') {
                        var parentTag = node.parentNode ? (node.parentNode.tagName || '').toUpperCase() : '';
                        if (parentTag !== 'VIDEO' && parentTag !== 'AUDIO') {
                            report(node.src);
                            var sSrcset = node.getAttribute('srcset');
                            if (sSrcset) {
                                var sParts = sSrcset.split(',');
                                for (var si = 0; si < sParts.length; si++) {
                                    var sBit = sParts[si].trim().split(/\s+/)[0];
                                    if (sBit) report(sBit);
                                }
                            }
                        }
                        return true;
                    }
                    return false;
                }

                function flushPending() {
                    flushTimer = null;
                    if (pendingNodes.size === 0) return;
                    var batch = Array.from(pendingNodes);
                    pendingNodes.clear();
                    for (var idx = 0; idx < batch.length; idx++) {
                        var node = batch[idx];
                        if (!node || !node.isConnected) continue;
                        var isLeaf = inspectSingleElement(node);
                        if (!isLeaf && node.querySelectorAll) {
                            var children = node.querySelectorAll('img, video, audio, source');
                            for (var c = 0; c < children.length; c++) {
                                inspectSingleElement(children[c]);
                            }
                        }
                    }
                }

                var observer = new MutationObserver(function(mutations) {
                    for (var m = 0; m < mutations.length; m++) {
                        var mut = mutations[m];
                        if (mut.type === 'childList') {
                            for (var i = 0; i < mut.addedNodes.length; i++) {
                                var added = mut.addedNodes[i];
                                if (added && added.nodeType === 1) pendingNodes.add(added);
                            }
                        } else if (mut.type === 'attributes' && mut.target && mut.target.nodeType === 1) {
                            pendingNodes.add(mut.target);
                        }
                    }
                    if (pendingNodes.size > 0 && !flushTimer) {
                        flushTimer = setTimeout(flushPending, 250);
                    }
                });

                observer.observe(document.body || document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['src', 'data-src', 'data-original', 'data-lazy-src', 'srcset', 'data-srcset', 'poster']
                });
            })();
        """.trimIndent()
    }

    /**
     * Shared JavaScript adaptive scrolling script using `behavior: 'instant'` to avoid
     * 500ms smooth-scroll animation overhead during automated background scraping.
     */
    fun getAdaptiveScrollScript(): String {
        return """
            (function() {
                var doc = document.documentElement || document.body;
                var scrollY = Math.round(window.pageYOffset || doc.scrollTop || 0);
                var innerHeight = window.innerHeight || 0;
                var scrollHeight = doc.scrollHeight || 0;
                var remaining = scrollHeight - (scrollY + innerHeight);

                var scrollDelta = Math.max(innerHeight * 0.85, 400);
                window.scrollBy({ top: scrollDelta, behavior: 'instant' });
                window.dispatchEvent(new Event('scroll'));

                return JSON.stringify({
                    scrollY: scrollY,
                    scrollHeight: scrollHeight,
                    remaining: remaining
                });
            })();
        """.trimIndent()
    }

    /**
     * Parses duration values expressed as numeric seconds, "MM:SS"/"HH:MM:SS", or ISO-8601 ("PT1M24S")
     * into positive milliseconds, or null if invalid/non-positive.
     */
    fun parseDurationToMillis(raw: Any?): Long? {
        if (raw == null || raw == JSONObject.NULL || raw is Boolean) return null
        if (raw is Number) {
            val sec = raw.toDouble()
            return if (sec.isFinite() && sec > 0.0) (sec * 1000.0).toLong().takeIf { it > 0L } else null
        }
        val text = raw.toString().trim()
        if (text.isBlank() || text.equals("null", ignoreCase = true) || text.equals("NaN", ignoreCase = true)) {
            return null
        }

        // Plain numeric seconds string (e.g., "84" or "12.5")
        text.toDoubleOrNull()?.let { sec ->
            return if (sec.isFinite() && sec > 0.0) (sec * 1000.0).toLong().takeIf { it > 0L } else null
        }

        // Colon-separated MM:SS or HH:MM:SS
        if (text.contains(':')) {
            val parts = text.split(':')
            if (parts.size in 2..3) {
                val nums = parts.mapNotNull { it.trim().toDoubleOrNull() }
                if (nums.size == parts.size) {
                    val totalSec = if (nums.size == 3) {
                        nums[0] * 3600.0 + nums[1] * 60.0 + nums[2]
                    } else {
                        nums[0] * 60.0 + nums[1]
                    }
                    return if (totalSec.isFinite() && totalSec > 0.0) (totalSec * 1000.0).toLong().takeIf { it > 0L } else null
                }
            }
        }

        // ISO-8601 duration (e.g., "PT1M24S", "PT1H2M3.5S", "P0DT0H1M5S")
        val isoRegex = Regex("""^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$""", RegexOption.IGNORE_CASE)
        val match = isoRegex.matchEntire(text)
        if (match != null && match.groupValues.drop(1).any { it.isNotEmpty() }) {
            val days = match.groupValues[1].toDoubleOrNull() ?: 0.0
            val hours = match.groupValues[2].toDoubleOrNull() ?: 0.0
            val mins = match.groupValues[3].toDoubleOrNull() ?: 0.0
            val secs = match.groupValues[4].toDoubleOrNull() ?: 0.0
            val totalSec = days * 86400.0 + hours * 3600.0 + mins * 60.0 + secs
            return if (totalSec.isFinite() && totalSec > 0.0) (totalSec * 1000.0).toLong().takeIf { it > 0L } else null
        }

        return null
    }

    /**
     * Builds and validates a ScrapedMediaItem with provenance, clean titles,
     * download headers (User-Agent, Referer, Cookie), and thumbnail classification.
     */
    fun buildMediaItem(
        rawUrl: String,
        source: ExtractionSource,
        baseUrl: String,
        poster: String? = null,
        resolutionHint: String? = null,
        mimeHint: String? = null,
        indexHint: Int = 0,
        durationMillis: Long? = null,
        precomputedCookieHeader: String? = null
    ): ScrapedMediaItem? {
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("blob:", ignoreCase = true)) return null
        if (trimmed.startsWith("data:", ignoreCase = true) && !MediaClassifier.isSubstantialDataUri(trimmed)) return null
        if (MediaClassifier.isMediaFragment(trimmed)) return null
        if (AdBlockEngine.isAdOrTracker(trimmed)) return null

        val resolved = MediaClassifier.resolveUrl(trimmed, baseUrl) ?: return null
        if (!MediaClassifier.isValidMedia(resolved)) return null
        if (MediaClassifier.isMediaFragment(resolved)) return null

        val resolvedInfo = MediaClassifier.resolveOriginalMedia(resolved)
        val cleanedUrl = MediaClassifier.cleanMediaUrl(resolvedInfo.mediaUrl)
        if (MediaClassifier.isMediaFragment(cleanedUrl)) return null

        val classification = MediaClassifier.classify(cleanedUrl, mimeHint)
        val isVidAudStream = classification.isVideo || classification.isAudio || classification.isStream
        val title = resolvedInfo.cleanTitle ?: MediaClassifier.deriveFileName(cleanedUrl, classification.format, indexHint)

        if (ContentAwareMediaFilter.isUiChromeUrl(
                url = cleanedUrl,
                title = title,
                isVideoOrAudioOrStream = isVidAudStream
            )
        ) {
            return null
        }

        val thumb = resolvedInfo.thumbnailUrl ?: poster ?: if (classification.category == MediaCategory.IMAGES) resolved else poster

        val headers = mutableMapOf<String, String>()
        headers["Referer"] = baseUrl
        headers["User-Agent"] = OkHttpDownloader.DEFAULT_USER_AGENT
        val cookieStr = precomputedCookieHeader ?: OkHttpDownloader.sharedCookieJar.getCookieString(baseUrl)
        if (cookieStr.isNotBlank()) {
            headers["Cookie"] = cookieStr
        }

        val metadataMap = mutableMapOf<String, String>()
        resolutionHint?.let { metadataMap["resolution"] = it }
        mimeHint?.let { metadataMap["mime_hint"] = it }
        durationMillis?.takeIf { it > 0L }?.let { metadataMap["duration_ms"] = it.toString() }
        metadataMap["source_url"] = baseUrl

        return ScrapedMediaItem(
            id = "captured_${indexHint}_${System.currentTimeMillis()}_${cleanedUrl.hashCode()}",
            url = cleanedUrl,
            title = title,
            isVideo = classification.isVideo,
            isAudio = classification.isAudio,
            isStream = classification.isStream,
            category = classification.category,
            format = classification.format,
            thumbnailUrl = thumb,
            source = source.label,
            mimeType = classification.mimeType,
            resolutionHint = resolutionHint,
            durationMillis = durationMillis?.takeIf { it > 0L },
            provenance = MediaProvenance(
                extractorEngine = "MediaCaptureEngine (${source.label})",
                pageUrl = baseUrl,
                domSelector = if (source == ExtractionSource.NETWORK_INTERCEPT) "network_request" else null
            ),
            metadata = metadataMap,
            downloadHeaders = headers
        )
    }

    /**
     * Fast-path network request inspection suitable for Chromium's IO thread.
     * Performs zero CookieManager IPC, zero Regex compilation, and zero URI allocations.
     */
    fun matchCandidateMediaUrl(request: WebResourceRequest): String? {
        val reqUrl = request.url.toString()
        if (MediaClassifier.isMediaFragment(reqUrl)) return null

        val reqLower = reqUrl.lowercase()
        val cleanReqPath = reqLower.substringBefore("?").substringBefore("#")

        // Skip common non-media web documents, scripts, stylesheets, and telemetry before touching headers
        if (cleanReqPath.endsWith(".html") || cleanReqPath.endsWith(".htm") ||
            cleanReqPath.endsWith(".php") || cleanReqPath.endsWith(".js") || cleanReqPath.endsWith(".css") ||
            cleanReqPath.endsWith(".json") || cleanReqPath.endsWith(".xml") || cleanReqPath.endsWith(".txt") ||
            cleanReqPath.endsWith(".do") || cleanReqPath.endsWith(".asp") || cleanReqPath.endsWith(".aspx") ||
            cleanReqPath.endsWith(".woff2") || cleanReqPath.endsWith(".woff") || cleanReqPath.endsWith(".ttf")
        ) {
            return null
        }

        if (cleanReqPath.contains("/api/") || cleanReqPath.contains("/apis/") ||
            cleanReqPath.contains("/youtubei/") || cleanReqPath.contains("/stats/") ||
            cleanReqPath.contains("/log") || cleanReqPath.contains("/logging") ||
            cleanReqPath.contains("/ptracking") || cleanReqPath.contains("/beacon") ||
            cleanReqPath.contains("/telemetry") || cleanReqPath.contains("/metrics") ||
            cleanReqPath.contains("generate_204") || cleanReqPath.contains("/recaptcha") ||
            cleanReqPath.contains("/embed/") || cleanReqPath.contains("play.google.com/log") ||
            cleanReqPath.contains("youtube.com/api/") || cleanReqPath.contains("/wiki/") ||
            cleanReqPath.contains("/geohack") || cleanReqPath.contains("centralautologin")
        ) {
            return null
        }

        val headers = request.requestHeaders ?: emptyMap()
        val acceptHeader = headers["Accept"]?.lowercase() ?: ""

        val isStream = cleanReqPath.endsWith(".m3u8") || cleanReqPath.endsWith(".mpd") ||
                cleanReqPath.contains("chunklist") || acceptHeader.contains("mpegurl") ||
                acceptHeader.contains("dash+xml")

        val isVideo = isStream || cleanReqPath.endsWith(".mp4") || cleanReqPath.endsWith(".webm") ||
                cleanReqPath.endsWith(".mov") || cleanReqPath.endsWith(".mkv") ||
                acceptHeader.contains("video/")

        val isAudio = cleanReqPath.endsWith(".mp3") || cleanReqPath.endsWith(".m4a") ||
                cleanReqPath.endsWith(".aac") || cleanReqPath.endsWith(".wav") ||
                cleanReqPath.endsWith(".ogg") || cleanReqPath.endsWith(".flac") ||
                cleanReqPath.endsWith(".opus") || acceptHeader.contains("audio/")

        val hasImageExt = cleanReqPath.endsWith(".jpg") || cleanReqPath.endsWith(".jpeg") ||
                cleanReqPath.endsWith(".png") || cleanReqPath.endsWith(".webp") ||
                cleanReqPath.endsWith(".gif") || cleanReqPath.endsWith(".avif") ||
                cleanReqPath.endsWith(".svg") || cleanReqPath.endsWith(".bmp") ||
                cleanReqPath.contains("/vi/") || cleanReqPath.contains("/vi_webp/") ||
                IMAGE_FORMAT_QUERY_REGEX.containsMatchIn(reqLower)

        val isImage = hasImageExt && !acceptHeader.contains("text/html")

        return when {
            isVideo || isStream -> MediaClassifier.cleanMediaUrl(reqUrl)
            isAudio || isImage -> reqUrl
            else -> null
        }
    }

    /**
     * Unified network request interception for both BrowserMediaExtractor and DirectBrowserView.
     */
    fun shouldInterceptRequest(
        request: WebResourceRequest?,
        baseUrl: String,
        onMediaDiscovered: (ScrapedMediaItem) -> Unit
    ): WebResourceResponse? {
        if (request == null) return null
        val reqUrl = request.url.toString()

        // 1. Instant zero-payload response for ad/tracker networks
        if (AdBlockEngine.isAdOrTracker(reqUrl, request.url.host)) {
            return AdBlockEngine.createEmptyResponse()
        }

        // 2. Candidate media matching
        val candidateUrl = matchCandidateMediaUrl(request) ?: return null
        val item = buildMediaItem(
            rawUrl = candidateUrl,
            source = ExtractionSource.NETWORK_INTERCEPT,
            baseUrl = baseUrl,
            mimeHint = null
        )
        if (item != null) {
            onMediaDiscovered(item)
        }

        return null
    }

    /**
     * Parses the JSON array returned by getJsDomScanScript().
     */
    fun parseJsDomScanResult(
        resultJson: String?,
        baseUrl: String
    ): List<ScrapedMediaItem> {
        if (resultJson.isNullOrBlank() || resultJson == "null") return emptyList()

        return try {
            val unescaped = if (resultJson.startsWith("\"") && resultJson.endsWith("\"")) {
                org.json.JSONTokener(resultJson).nextValue().toString()
            } else {
                resultJson
            }

            val jsonArray = JSONArray(unescaped)
            if (jsonArray.length() == 0) return emptyList()
            val batchCookieHeader = OkHttpDownloader.sharedCookieJar.getCookieString(baseUrl)
            val results = ArrayList<ScrapedMediaItem>(jsonArray.length())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val rawUrl = obj.optString("u", "")
                if (rawUrl.isNotBlank()) {
                    val sTag = obj.optString("s", "DOM")
                    val source = when (sTag) {
                        "CSS" -> ExtractionSource.CSS_BACKGROUND
                        "SRCSET" -> ExtractionSource.SRCSET
                        "IFRAME" -> ExtractionSource.IFRAME
                        else -> ExtractionSource.DOM_SCRAPE
                    }
                    val poster = obj.optString("p", "").ifBlank { null }
                    val res = obj.optString("r", "").ifBlank { null }
                    val durationMs = parseDurationToMillis(obj.opt("d"))
                    val item = buildMediaItem(
                        rawUrl = rawUrl,
                        source = source,
                        baseUrl = baseUrl,
                        poster = poster,
                        resolutionHint = res,
                        indexHint = i,
                        durationMillis = durationMs,
                        precomputedCookieHeader = batchCookieHeader
                    )
                    if (item != null) {
                        results.add(item)
                    }
                }
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }
}
