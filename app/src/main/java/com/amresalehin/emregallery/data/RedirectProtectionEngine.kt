package com.amresalehin.emregallery.data

import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Redirect & Unwanted Ad Protection Engine inspired by Website Media Downloader.
 *
 * Features:
 * - Blocks unexpected cross-origin redirects and ad/popunder navigation attempts.
 * - Configurable "Number of Taps to Open" (1..5): requires repeated intentional user taps
 *   to navigate to an external cross-origin domain before permitting it.
 * - Optional "Hide Blocked Content" JS snippet to hide the clicked element/overlay after
 *   it triggers a blocked redirect.
 */
object RedirectProtectionEngine {

    private const val CONFIRMATION_WINDOW_MS = 15_000L

    data class RedirectDecision(
        val allowed: Boolean,
        val remainingTaps: Int = 0,
        val reason: String? = null,
        val shouldHideClickedElement: Boolean = false
    )

    private data class TapRecord(
        val count: Int,
        val lastTimestampMs: Long
    )

    private val tapCounters = ConcurrentHashMap<String, TapRecord>()

    fun clearHistory() {
        tapCounters.clear()
    }

    /**
     * Evaluates whether a WebView navigation from [currentPageUrl] to [targetUrl] should be
     * permitted or blocked under Redirect Protection rules.
     */
    fun evaluateNavigation(
        currentPageUrl: String,
        targetUrl: String,
        isServerRedirect: Boolean = false,
        hasUserGesture: Boolean = true,
        enabled: Boolean = true,
        tapsToOpen: Int = 2,
        hideBlockedElements: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): RedirectDecision {
        if (!enabled) return RedirectDecision(allowed = true)

        val trimmedTarget = targetUrl.trim()
        if (trimmedTarget.isBlank() || trimmedTarget.startsWith("about:") || trimmedTarget.startsWith("javascript:")) {
            return RedirectDecision(allowed = true)
        }

        // 1. Always block known ad / popunder / tracker domains
        if (AdBlockEngine.isAdOrTracker(trimmedTarget)) {
            return RedirectDecision(
                allowed = false,
                remainingTaps = 0,
                reason = "Blocked ad or tracker redirect",
                shouldHideClickedElement = hideBlockedElements
            )
        }

        val currentRoot = extractRootDomain(currentPageUrl)
        val targetRoot = extractRootDomain(trimmedTarget)

        // 2. Allow initial navigation (empty current page) or same-site navigation
        if (currentRoot.isBlank() || targetRoot.isBlank() || currentRoot == targetRoot) {
            return RedirectDecision(allowed = true)
        }

        // 3. Block automatic cross-origin redirects without user gesture
        if (isServerRedirect && !hasUserGesture) {
            return RedirectDecision(
                allowed = false,
                remainingTaps = tapsToOpen.coerceIn(1, 5),
                reason = "Blocked automatic redirect to $targetRoot",
                shouldHideClickedElement = hideBlockedElements
            )
        }

        val requiredTaps = tapsToOpen.coerceIn(1, 5)
        if (requiredTaps <= 1) {
            return RedirectDecision(allowed = true)
        }

        val key = "$currentRoot->$targetRoot"
        val prev = tapCounters[key]
        val currentCount = if (prev != null && (nowMs - prev.lastTimestampMs) <= CONFIRMATION_WINDOW_MS) {
            prev.count + 1
        } else {
            1
        }

        return if (currentCount >= requiredTaps) {
            tapCounters.remove(key)
            RedirectDecision(allowed = true)
        } else {
            tapCounters[key] = TapRecord(count = currentCount, lastTimestampMs = nowMs)
            val remaining = requiredTaps - currentCount
            RedirectDecision(
                allowed = false,
                remainingTaps = remaining,
                reason = "Blocked redirect to $targetRoot — tap $remaining more time${if (remaining > 1) "s" else ""} to open",
                shouldHideClickedElement = hideBlockedElements
            )
        }
    }

    /**
     * JavaScript snippet injected on page load to track the most recently clicked element
     * so it can be hidden if it triggers a blocked redirect.
     */
    fun getTrackClickTargetScript(): String {
        return """
            (function() {
                if (window.__emreRedirectTrackerInstalled) return;
                window.__emreRedirectTrackerInstalled = true;
                document.addEventListener('click', function(e) {
                    window.__emreLastClickedEl = e.target;
                }, true);
                document.addEventListener('touchstart', function(e) {
                    if (e.target) window.__emreLastClickedEl = e.target;
                }, true);
            })();
        """.trimIndent()
    }

    /**
     * JavaScript snippet to hide the clickable element/ad overlay that triggered a blocked redirect.
     */
    fun getHideLastClickedElementScript(): String {
        return """
            (function() {
                try {
                    var el = window.__emreLastClickedEl;
                    if (!el || el === document.body || el === document.documentElement) return false;
                    var target = el.closest('a, [onclick], iframe, .ad, .ads, [class*="banner"], [class*="popup"], [class*="overlay"]') || el;
                    if (target && target !== document.body && target !== document.documentElement) {
                        target.style.setProperty('display', 'none', 'important');
                        target.style.setProperty('visibility', 'hidden', 'important');
                        target.style.setProperty('pointer-events', 'none', 'important');
                        window.__emreLastClickedEl = null;
                        return true;
                    }
                } catch(e) {}
                return false;
            })();
        """.trimIndent()
    }

    internal fun extractRootDomain(url: String): String {
        if (url.isBlank()) return ""
        val host = runCatching {
            val normalized = if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) "https://$url" else url
            URI(normalized).host?.lowercase(Locale.ROOT) ?: ""
        }.getOrDefault("")
        if (host.isBlank()) return ""
        val clean = host.removePrefix("www.")
        val parts = clean.split(".")
        return if (parts.size >= 2) {
            "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
        } else {
            clean
        }
    }
}
