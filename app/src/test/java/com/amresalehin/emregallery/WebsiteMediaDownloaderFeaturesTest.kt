package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.DiscoveryFilterManager
import com.amresalehin.emregallery.data.DiscoveryFilterState
import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.RedirectProtectionEngine
import com.amresalehin.emregallery.data.ScrapedMediaItem
import com.amresalehin.emregallery.data.SpeedBoostDownloader
import com.amresalehin.emregallery.data.StreamReconstructionEngine
import com.amresalehin.emregallery.ui.QrCodeEncoder
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class WebsiteMediaDownloaderFeaturesTest {

    @Before
    fun setUp() {
        RedirectProtectionEngine.clearHistory()
    }

    @Test
    fun speedBoostChunkRangesCoverEntireFileWithoutGaps() {
        val total = 10_000_003L
        val ranges = SpeedBoostDownloader.computeChunkRanges(total, 8)
        assertEquals(8, ranges.size)
        assertEquals(0L, ranges.first().start)
        assertEquals(total - 1L, ranges.last().end)
        for (i in 1 until ranges.size) {
            assertEquals(ranges[i - 1].end + 1L, ranges[i].start)
        }
        assertEquals(total, ranges.sumOf { it.length })
    }

    @Test
    fun speedBoostClampsConnectionsToMax16() {
        val ranges = SpeedBoostDownloader.computeChunkRanges(20_000_000L, 64)
        assertEquals(16, ranges.size)
    }

    @Test
    fun hlsMasterPlaylistParsesVariantsAndRespectsHeightCap() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,CODECS="avc1.4d401f"
            mid/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5500000,RESOLUTION=1920x1080,CODECS="avc1.640028"
            high/index.m3u8
        """.trimIndent()

        assertTrue(StreamReconstructionEngine.isHlsMasterPlaylist(master))
        val variants = StreamReconstructionEngine.parseHlsMasterPlaylist(master, "https://cdn.example.com/hls/master.m3u8")
        assertEquals(3, variants.size)
        assertEquals(1080, variants.first().height)
        assertEquals("https://cdn.example.com/hls/high/index.m3u8", variants.first().url)

        val capped = StreamReconstructionEngine.selectBestVariant(variants, maxHeight = 720)
        assertNotNull(capped)
        assertEquals(720, capped?.height)
    }

    @Test
    fun hlsDetectsSeparateAudioUnsupportedEncryptionAndLivePlaylists() {
        val alternateAudioMaster = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE="AUDIO",GROUP-ID="audio",NAME="English",URI="audio/eng.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,AUDIO="audio"
            video/1080p.m3u8
        """.trimIndent()
        assertTrue(StreamReconstructionEngine.hasExternalHlsAudioRendition(alternateAudioMaster))

        val sampleAes = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://content-key"
            #EXTINF:4.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertTrue(StreamReconstructionEngine.hasUnsupportedHlsEncryption(sampleAes))
        val sessionKey = """
            #EXTM3U
            #EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI="skd://content-key"
            #EXT-X-STREAM-INF:BANDWIDTH=3000000
            video/1080p.m3u8
        """.trimIndent()
        assertTrue(StreamReconstructionEngine.hasUnsupportedHlsEncryption(sessionKey))

        val aes128 = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:4.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertFalse(StreamReconstructionEngine.hasUnsupportedHlsEncryption(aes128))
        assertTrue(StreamReconstructionEngine.isFinalizedHlsMediaPlaylist(aes128))
        assertFalse(StreamReconstructionEngine.isFinalizedHlsMediaPlaylist(aes128.replace("#EXT-X-ENDLIST", "")))
    }

    @Test
    fun hlsMediaPlaylistParsesSegmentsAndDecryptsAes128() {
        val media = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:10
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x0000000000000000000000000000000A
            #EXTINF:4.0,
            seg0.ts
            #EXTINF:4.0,
            seg1.ts
        """.trimIndent()

        val segs = StreamReconstructionEngine.parseHlsMediaPlaylist(media, "https://cdn.example.com/hls/index.m3u8")
        assertEquals(2, segs.size)
        assertEquals(10L, segs[0].mediaSequence)
        assertEquals("AES-128", segs[0].keyMethod)
        assertEquals("https://cdn.example.com/hls/key.bin", segs[0].keyUri)

        // Encrypt sample payload and verify decryptAes128Segment round-trip
        val key = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(16).apply { this[15] = 0x0A }
        val plain = "Hello HLS AES-128 Stream Segment!".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plain)

        val decrypted = StreamReconstructionEngine.decryptAes128Segment(
            encryptedBytes = encrypted,
            keyBytes = key,
            ivHex = segs[0].keyIvHex,
            mediaSequence = segs[0].mediaSequence
        )
        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun dashManifestParsesVideoAndAudioSegmentTimeline() {
        val mpd = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD>
              <Period>
                <AdaptationSet mimeType="video/mp4">
                  <Representation id="v1080" width="1920" height="1080" bandwidth="4000000">
                    <SegmentTemplate initialization="init_${'$'}RepresentationID${'$'}.mp4" media="chunk_${'$'}RepresentationID${'$'}_${'$'}Number${'$'}.m4s" startNumber="1">
                      <SegmentTimeline>
                        <S t="0" d="2000" r="2"/>
                      </SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet mimeType="audio/mp4">
                  <Representation id="a128" bandwidth="128000">
                    <BaseURL>audio_full.m4a</BaseURL>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val parsed = StreamReconstructionEngine.parseDashManifest(mpd, "https://stream.example.com/dash/manifest.mpd")
        assertEquals(1, parsed.videoRepresentations.size)
        assertEquals(1, parsed.audioRepresentations.size)
        val vid = parsed.videoRepresentations.first()
        assertEquals("https://stream.example.com/dash/init_v1080.mp4", vid.initUrl)
        assertEquals(3, vid.segmentUrls.size)
        assertEquals("https://stream.example.com/dash/chunk_v1080_3.m4s", vid.segmentUrls.last())
        assertEquals("https://stream.example.com/dash/audio_full.m4a", parsed.audioRepresentations.first().segmentUrls.first())
    }

    @Test
    fun subtitleAndFileCategoriesClassifiedCorrectly() {
        val vtt = MediaClassifier.classify("https://cdn.example.com/subs/english.vtt")
        assertEquals(MediaCategory.SUBTITLES, vtt.category)
        assertEquals("VTT", vtt.format)

        val pdf = MediaClassifier.classify("https://example.com/docs/manual.pdf")
        assertEquals(MediaCategory.FILES, pdf.category)
        assertEquals("PDF", pdf.format)

        val zip = MediaClassifier.classify("https://example.com/archive/bundle.zip")
        assertEquals(MediaCategory.FILES, zip.category)
        assertEquals("ZIP", zip.format)
    }

    @Test
    fun hideUnknownSizeImagesAndGlobalSearchWorkAsExpected() {
        val filter = DiscoveryFilterState(
            minWidth = 0,
            minHeight = 0,
            minSizeBytes = 0L,
            hideUnknownSizeImages = true
        )
        val unknownSizeImg = ScrapedMediaItem(
            id = "u1",
            url = "https://cdn.example.com/photo_alpha.jpg",
            title = "Alpha Photo",
            format = "JPG",
            sizeBytes = null
        )
        val knownSizeImg = ScrapedMediaItem(
            id = "k1",
            url = "https://cdn.example.com/photo_beta.jpg",
            title = "Beta Photo",
            format = "JPG",
            sizeBytes = 64_000L
        )

        assertFalse(DiscoveryFilterManager.matchesFilters(unknownSizeImg, filter))
        assertTrue(DiscoveryFilterManager.matchesFilters(knownSizeImg, filter))
        assertTrue(DiscoveryFilterManager.matchesSearchQuery(knownSizeImg, "beta jpg"))
        assertFalse(DiscoveryFilterManager.matchesSearchQuery(knownSizeImg, "gamma"))
    }

    @Test
    fun intelligentDeduplicationCollapsesRedundantEntries() {
        val item1 = ScrapedMediaItem(
            id = "1",
            url = "https://cdn1.example.com/a/concert_clip.mp4",
            title = "concert_clip.mp4",
            isVideo = true,
            category = MediaCategory.VIDEOS,
            sizeBytes = 5_000_000L,
            width = 1280,
            height = 720
        )
        val item2 = ScrapedMediaItem(
            id = "2",
            url = "https://cdn2.example.com/b/concert-clip.mp4",
            title = "concert-clip.mp4",
            isVideo = true,
            category = MediaCategory.VIDEOS,
            sizeBytes = 5_000_000L,
            width = 1920,
            height = 1080
        )
        val deduped = MediaClassifier.intelligentDeduplicate(listOf(item1, item2))
        assertEquals(1, deduped.size)
        assertEquals("2", deduped.first().id)
    }

    @Test
    fun redirectProtectionRequiresConfiguredTapsForCrossOriginLinks() {
        val d1 = RedirectProtectionEngine.evaluateNavigation(
            currentPageUrl = "https://news.example.com/article",
            targetUrl = "https://external-site.org/offer",
            enabled = true,
            tapsToOpen = 2,
            nowMs = 1000L
        )
        assertFalse(d1.allowed)
        assertEquals(1, d1.remainingTaps)

        val d2 = RedirectProtectionEngine.evaluateNavigation(
            currentPageUrl = "https://news.example.com/article",
            targetUrl = "https://external-site.org/offer",
            enabled = true,
            tapsToOpen = 2,
            nowMs = 2000L
        )
        assertTrue(d2.allowed)
    }

    @Test
    fun qrCodeEncoderGeneratesValidSquareMatrix() {
        val matrix = QrCodeEncoder.encode("https://cdn.example.com/videos/sample_1080p.mp4")
        assertNotNull(matrix)
        val size = matrix!!.size
        assertTrue("QR size should be >= 21", size >= 21)
        assertEquals(size, matrix[0].size)
        // Top-left finder pattern corner must be dark
        assertTrue(matrix[0][0])
        assertTrue(matrix[6][6])
    }
}
