package com.amresalehin.emregallery.data

import android.util.Log
import kotlinx.coroutines.delay
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Shared per-host rate limiter, cooldown circuit breaker, and error classifier
 * used across both the general downloader (ExtractionCoordinator, MediaDownloadWorker,
 * RecursiveSiteCrawler).
 *
 * Prevents cascading 429 / anti-bot storms without restricting extractor capabilities.
 */
object HostRateLimiter {
    private const val TAG = "HostRateLimiter"

    enum class ErrorCategory {
        PERMANENT,
        RATE_LIMITED,
        TRANSIENT
    }

    private data class HostState(
        @Volatile var lastRequestAtMs: Long = 0L,
        @Volatile var cooldownUntilMs: Long = 0L,
        @Volatile var consecutiveThrottles: Int = 0
    )

    private val states = ConcurrentHashMap<String, HostState>()

    /**
     * Normalizes sibling domains so YouTube/X/Reddit share one coherent rate-limit & cookie bucket.
     */
    fun canonicalHost(urlOrHost: String?): String {
        if (urlOrHost.isNullOrBlank()) return "unknown"
        val raw = urlOrHost.trim()
        val host = runCatching {
            if (raw.contains("://")) URI(raw).host else URI("https://$raw").host
        }.getOrNull()?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: raw.lowercase(Locale.ROOT)

        return when {
            host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") -> "youtube.com"
            host == "twitter.com" || host.endsWith(".twitter.com") || host == "x.com" || host.endsWith(".x.com") -> "x.com"
            host == "redd.it" || host == "reddit.com" || host.endsWith(".reddit.com") -> "reddit.com"
            host == "pin.it" || host == "pinterest.com" || host.endsWith(".pinterest.com") -> "pinterest.com"
            host == "instagr.am" || host == "instagram.com" || host.endsWith(".instagram.com") -> "instagram.com"
            else -> host.ifBlank { "unknown" }
        }
    }

    private fun minSpacingMs(canonicalHost: String): Long = when (canonicalHost) {
        "youtube.com" -> 600L
        "instagram.com", "pinterest.com", "reddit.com", "x.com" -> 400L
        else -> 150L
    }

    /**
     * Returns remaining cooldown milliseconds for [urlOrHost], or 0 if clear.
     */
    fun remainingCooldownMs(urlOrHost: String?): Long {
        val host = canonicalHost(urlOrHost)
        val state = states[host] ?: return 0L
        return (state.cooldownUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /**
     * Enforces minimum per-host spacing and honors active cooldown windows (bounded wait).
     * Returns true if cleared to proceed, or false if the host is in an extended cooldown (> 30 s).
     */
    suspend fun acquire(urlOrHost: String?, maxWaitMs: Long = 30_000L): Boolean {
        val host = canonicalHost(urlOrHost)
        val state = states.computeIfAbsent(host) { HostState() }
        val now = System.currentTimeMillis()
        val cooldownWait = (state.cooldownUntilMs - now).coerceAtLeast(0L)
        if (cooldownWait > maxWaitMs) {
            Log.w(TAG, "Host $host in extended cooldown (${cooldownWait / 1000}s remaining)")
            return false
        }
        val spacingWait = (state.lastRequestAtMs + minSpacingMs(host) - now).coerceAtLeast(0L)
        val waitMs = maxOf(cooldownWait, spacingWait)
        if (waitMs > 0L) {
            delay(waitMs)
        }
        state.lastRequestAtMs = System.currentTimeMillis()
        return true
    }

    /**
     * Records an HTTP 429 or anti-bot challenge and applies exponential cooldown (15s .. 300s).
     */
    fun recordRateLimitOrBotChallenge(urlOrHost: String?, retryAfterSeconds: Long? = null): Long {
        val host = canonicalHost(urlOrHost)
        val state = states.computeIfAbsent(host) { HostState() }
        val strikes = (state.consecutiveThrottles + 1).coerceAtMost(6)
        state.consecutiveThrottles = strikes
        val delaySec = if (retryAfterSeconds != null && retryAfterSeconds > 0L) {
            retryAfterSeconds.coerceIn(5L, 300L)
        } else {
            min(300L, 15L * (1L shl (strikes - 1)))
        }
        state.cooldownUntilMs = System.currentTimeMillis() + (delaySec * 1000L)
        Log.w(TAG, "Applied ${delaySec}s cooldown for $host (strike #$strikes)")
        return delaySec
    }

    /**
     * Clears throttle strikes on a clean response from [urlOrHost].
     */
    fun recordSuccess(urlOrHost: String?) {
        val host = canonicalHost(urlOrHost)
        states[host]?.let {
            it.consecutiveThrottles = 0
            it.cooldownUntilMs = 0L
        }
    }

    /**
     * Unified error classifier shared across downloaders to avoid retrying permanent errors.
     */
    fun classifyError(httpStatus: Int = -1, errorMessage: String?): ErrorCategory {
        val msg = errorMessage.orEmpty().lowercase(Locale.ROOT)
        if (httpStatus == 429 ||
            msg.contains("429") ||
            msg.contains("too many requests") ||
            msg.contains("rate limit") ||
            msg.contains("sign in to confirm you're not a bot") ||
            msg.contains("not a bot") ||
            msg.contains("captcha")
        ) {
            return ErrorCategory.RATE_LIMITED
        }
        if (httpStatus in setOf(400, 401, 403, 404, 410, 451) ||
            msg.contains("video unavailable") ||
            msg.contains("private video") ||
            msg.contains("copyright") ||
            msg.contains("account has been terminated") ||
            msg.contains("unsupported url") ||
            msg.contains("does not exist") ||
            msg.contains("untrusted ssl") ||
            msg.contains("certificate")
        ) {
            return ErrorCategory.PERMANENT
        }
        return ErrorCategory.TRANSIENT
    }

    /**
     * Resets all in-memory host buckets (used in unit tests).
     */
    internal fun resetForTesting() {
        states.clear()
    }
}
