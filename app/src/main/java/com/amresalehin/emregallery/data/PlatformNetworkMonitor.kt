package com.amresalehin.emregallery.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.Locale

/**
 * The 8 primary platforms supported by MSC72m/media_downloader,
 * plus Reddit and Generic web support already present in Emre Gallery.
 */
enum class SupportedMediaPlatform(
    val displayName: String,
    val probeUrl: String,
    val hostPatterns: List<String>,
    val contentSummary: String
) {
    YOUTUBE(
        displayName = "YouTube",
        probeUrl = "https://www.youtube.com/generate_204",
        hostPatterns = listOf("youtube.com", "youtu.be", "music.youtube.com"),
        contentSummary = "Videos, Shorts, Playlists, Subtitles (144p–8K)"
    ),
    SPOTIFY(
        displayName = "Spotify",
        probeUrl = "https://open.spotify.com/",
        hostPatterns = listOf("open.spotify.com", "play.spotify.com", "spotify.link", "spoti.fi"),
        contentSummary = "Tracks, Albums, Playlists, Artists (YouTube Audio Match)"
    ),
    TIKTOK(
        displayName = "TikTok",
        probeUrl = "https://www.tiktok.com/",
        hostPatterns = listOf("tiktok.com", "vm.tiktok.com", "vt.tiktok.com"),
        contentSummary = "Best Quality Video & Metadata"
    ),
    INSTAGRAM(
        displayName = "Instagram",
        probeUrl = "https://www.instagram.com/",
        hostPatterns = listOf("instagram.com", "instagr.am"),
        contentSummary = "Posts, Reels, Carousels, Captions"
    ),
    TWITTER_X(
        displayName = "Twitter / X",
        probeUrl = "https://x.com/",
        hostPatterns = listOf("x.com", "twitter.com", "t.co"),
        contentSummary = "Tweets, Images, Videos & Live/Replay Spaces"
    ),
    PINTEREST(
        displayName = "Pinterest",
        probeUrl = "https://www.pinterest.com/",
        hostPatterns = listOf("pinterest.com", "pin.it", "pinimg.com"),
        contentSummary = "High-Quality Pins & Boards"
    ),
    SOUNDCLOUD(
        displayName = "SoundCloud",
        probeUrl = "https://soundcloud.com/",
        hostPatterns = listOf("soundcloud.com", "on.soundcloud.com"),
        contentSummary = "Free Tracks, Sets & Artwork"
    ),
    RADIOJAVAN(
        displayName = "RadioJavan",
        probeUrl = "https://play.radiojavan.com/",
        hostPatterns = listOf("radiojavan.com", "play.radiojavan.com", "rj.app"),
        contentSummary = "Songs, Videos, Podcasts, Playlists (5-Host CDN)"
    );

    companion object {
        fun detect(url: String?): SupportedMediaPlatform? {
            val clean = url?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (clean.isBlank()) return null
            return entries.firstOrNull { platform ->
                platform.hostPatterns.any { host -> clean.contains(host) }
            }
        }

        fun isXSpace(url: String?): Boolean {
            val clean = url?.trim()?.lowercase(Locale.ROOT) ?: return false
            return (clean.contains("x.com/i/spaces/") || clean.contains("twitter.com/i/spaces/"))
        }
    }
}

enum class PlatformReachability {
    ONLINE,
    DEGRADED,
    UNREACHABLE
}

data class PlatformHealthResult(
    val platform: SupportedMediaPlatform,
    val status: PlatformReachability,
    val statusCode: Int,
    val latencyMs: Long,
    val detail: String
)

/**
 * Concurrent connectivity and latency monitor for all 8 supported platforms
 * (ported from MSC72m/media_downloader Tools -> Network Status).
 * Automatically routes probes through the configured SOCKS5/HTTP proxy when enabled.
 */
object PlatformNetworkMonitor {

    suspend fun checkAllPlatforms(): List<PlatformHealthResult> = coroutineScope {
        SupportedMediaPlatform.entries.map { platform ->
            async(Dispatchers.IO) { checkPlatform(platform) }
        }.awaitAll()
    }

    suspend fun checkPlatform(platform: SupportedMediaPlatform): PlatformHealthResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url(platform.probeUrl)
                .head()
                .build()
            val client = OkHttpDownloader.client
            client.newCall(req).execute().use { resp ->
                val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                val code = resp.code
                val status = when {
                    code in 200..399 -> PlatformReachability.ONLINE
                    code in 400..499 -> PlatformReachability.DEGRADED // Reachable, but anti-bot/auth header required
                    else -> PlatformReachability.UNREACHABLE
                }
                val detail = when (status) {
                    PlatformReachability.ONLINE -> "HTTP $code (${elapsed} ms)"
                    PlatformReachability.DEGRADED -> "Reachable (HTTP $code, ${elapsed} ms)"
                    PlatformReachability.UNREACHABLE -> "Server error HTTP $code"
                }
                PlatformHealthResult(
                    platform = platform,
                    status = status,
                    statusCode = code,
                    latencyMs = elapsed,
                    detail = detail
                )
            }
        } catch (e: Exception) {
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            PlatformHealthResult(
                platform = platform,
                status = PlatformReachability.UNREACHABLE,
                statusCode = -1,
                latencyMs = elapsed,
                detail = e.localizedMessage?.take(60) ?: "Connection failed"
            )
        }
    }
}
