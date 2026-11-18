package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.DownloadActivityItem
import com.amresalehin.emregallery.data.DownloadActivityStatus
import com.amresalehin.emregallery.data.OkHttpDownloader
import com.amresalehin.emregallery.data.SupportedMediaPlatform
import com.amresalehin.emregallery.data.preferences.DownloaderFormatMode
import com.amresalehin.emregallery.data.preferences.DownloaderVideoQuality
import com.amresalehin.emregallery.ui.theme.AppColorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

class MediaDownloaderMsc72mFeaturesTest {

    @Test
    fun `smart platform detector recognizes all 8 MSC72m platforms and X Spaces`() {
        assertEquals(SupportedMediaPlatform.YOUTUBE, SupportedMediaPlatform.detect("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(SupportedMediaPlatform.SPOTIFY, SupportedMediaPlatform.detect("https://open.spotify.com/track/123"))
        assertEquals(SupportedMediaPlatform.SPOTIFY, SupportedMediaPlatform.detect("https://spotify.link/abc"))
        assertEquals(SupportedMediaPlatform.TIKTOK, SupportedMediaPlatform.detect("https://vm.tiktok.com/ZM8xyz/"))
        assertEquals(SupportedMediaPlatform.INSTAGRAM, SupportedMediaPlatform.detect("https://www.instagram.com/reel/abc/"))
        assertEquals(SupportedMediaPlatform.TWITTER_X, SupportedMediaPlatform.detect("https://x.com/i/spaces/1vAxRvQAbCdKl"))
        assertTrue(SupportedMediaPlatform.isXSpace("https://x.com/i/spaces/1vAxRvQAbCdKl"))
        assertFalse(SupportedMediaPlatform.isXSpace("https://x.com/user/status/123"))
        assertEquals(SupportedMediaPlatform.PINTEREST, SupportedMediaPlatform.detect("https://pin.it/abc123"))
        assertEquals(SupportedMediaPlatform.SOUNDCLOUD, SupportedMediaPlatform.detect("https://soundcloud.com/artist/song"))
        assertEquals(SupportedMediaPlatform.RADIOJAVAN, SupportedMediaPlatform.detect("https://rj.app/m/xyz"))
        assertEquals(8, SupportedMediaPlatform.entries.size)
    }

    @Test
    fun `video quality range spans 144p to 8K and format modes include video-only`() {
        assertEquals(144, DownloaderVideoQuality.fromId("144P").maxHeight)
        assertEquals(240, DownloaderVideoQuality.fromId("240P").maxHeight)
        assertEquals(4320, DownloaderVideoQuality.fromId("8K").maxHeight)
        assertEquals(DownloaderFormatMode.VIDEO_ONLY, DownloaderFormatMode.fromId("video_only"))
        assertEquals(DownloaderFormatMode.AUDIO_ONLY, DownloaderFormatMode.fromId("audio_only"))
        assertEquals(DownloaderFormatMode.VIDEO_AUDIO, DownloaderFormatMode.fromId("unknown"))
    }

    @Test
    fun `proxy url parser supports HTTP and SOCKS5 with optional auth`() {
        val socks = OkHttpDownloader.parseProxyUrl("socks5://user:pass@127.0.0.1:1080")
        assertNotNull(socks)
        assertEquals(Proxy.Type.SOCKS, socks!!.type)
        assertEquals("127.0.0.1", socks.host)
        assertEquals(1080, socks.port)
        assertEquals("user", socks.username)
        assertEquals("pass", socks.password)

        val http = OkHttpDownloader.parseProxyUrl("http://proxy.local:8080")
        assertNotNull(http)
        assertEquals(Proxy.Type.HTTP, http!!.type)
        assertEquals(8080, http.port)

        assertNull(OkHttpDownloader.parseProxyUrl(""))
        assertNull(OkHttpDownloader.parseProxyUrl("ftp://invalid:21"))
    }

    @Test
    fun `theme palette provides Monochrome default plus all 22 MSC72m color themes`() {
        assertEquals(23, AppColorTheme.entries.size)
        assertEquals(AppColorTheme.MONOCHROME, AppColorTheme.fromId(null))
        assertEquals(AppColorTheme.CYBERPUNK, AppColorTheme.fromId("cyberpunk"))
        assertEquals(AppColorTheme.ESPRESSO, AppColorTheme.fromId("espresso"))
        assertEquals(AppColorTheme.GLACIER, AppColorTheme.fromId("glacier"))
        assertEquals(AppColorTheme.SUNSET, AppColorTheme.fromId("sunset"))
    }

    @Test
    fun `download activity item computes live ETA`() {
        val item = DownloadActivityItem(
            id = "dl_1",
            title = "Video.mp4",
            mediaUrl = "https://example.com/v.mp4",
            status = DownloadActivityStatus.DOWNLOADING,
            bytesRead = 2_000_000L,
            totalBytes = 10_000_000L,
            speedBytesPerSec = 1_000_000L
        )
        assertEquals(8L, item.etaSeconds)
        assertEquals("8s left", item.formattedEta)
    }
}
