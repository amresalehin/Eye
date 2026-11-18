package com.amresalehin.emregallery.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import androidx.annotation.Keep
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/**
 * High-resolution (up to 4K/8K) on-device container muxer powered by Android's native [MediaExtractor] and [MediaMuxer].
 * Supports both:
 * - [MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4] for H.264 (`avc1`), HEVC (`hev1`/`hvc1`), and AV1 (`av01`) video + AAC (`mp4a`) audio.
 * - [MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM] for 2K/4K/8K VP9 (`vp09`/`vp9`) and VP8 video + Opus/Vorbis audio.
 * Uses an 8 MB sample buffer and timestamp-interleaved sample writing so 4K 60fps and 8K IDR keyframes never truncate.
 */
@Keep
object AndroidMediaMuxer {
    private const val TAG = "AndroidMediaMuxer"
    const val DEFAULT_BUFFER_SIZE = 8 * 1024 * 1024 // 8 MB buffer for 4K/8K IDR keyframes

    /**
     * Determines the appropriate [MediaMuxer.OutputFormat] based on the video and audio MIME types.
     * Strictly checks video codec compatibility first because MUXER_OUTPUT_WEBM only accepts VP8/VP9 video.
     */
    @JvmStatic
    fun resolveMuxerOutputFormat(videoMime: String?, audioMime: String?): Int {
        val v = videoMime?.lowercase(Locale.ROOT).orEmpty()
        val a = audioMime?.lowercase(Locale.ROOT).orEmpty()
        val isWebmVideo = v.contains("vp9") || v.contains("vp8") || v.contains("x-vnd.on2")
        val isMp4OnlyVideo = v.contains("avc") || v.contains("hevc") || v.contains("dolby-vision") || v.contains("mp4v") || v.contains("av01")
        val isWebmAudio = a.contains("opus") || a.contains("vorbis")
        return when {
            isWebmVideo -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            isMp4OnlyVideo -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            isWebmAudio -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }
    }

    /**
     * Checks whether the given video and audio MIME types can be muxed together by Android's native [MediaMuxer].
     */
    @JvmStatic
    fun areTracksMuxable(videoMime: String?, audioMime: String?): Boolean {
        val v = videoMime?.lowercase(Locale.ROOT).orEmpty()
        val a = audioMime?.lowercase(Locale.ROOT).orEmpty()
        val isWebmVideo = v.contains("vp9") || v.contains("vp8") || v.contains("x-vnd.on2")
        val isWebmAudio = a.contains("opus") || a.contains("vorbis")
        val isMp4Audio = a.contains("mp4a") || a.contains("aac") || (a.contains("opus") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        return if (isWebmVideo) {
            isWebmAudio
        } else {
            isMp4Audio
        }
    }

    /**
     * Checks whether the given media file contains at least one audio track.
     */
    @JvmStatic
    fun hasAudioTrack(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase(Locale.ROOT) ?: continue
                if (mime.startsWith("audio/")) {
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Checks whether the given media file contains at least one video track.
     */
    @JvmStatic
    fun hasVideoTrack(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase(Locale.ROOT) ?: continue
                if (mime.startsWith("video/")) {
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * String-path convenience bridge callable directly from Chaquopy Python or Kotlin.
     */
    @JvmStatic
    fun muxVideoAndAudioPaths(videoPath: String, audioPath: String, outputPath: String): Boolean {
        return muxVideoAndAudio(File(videoPath), File(audioPath), File(outputPath))
    }

    /**
     * Losslessly muxes the primary video track from [videoFile] and the primary audio track from [audioFile]
     * into [outputFile] using either [MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4] or [MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM].
     * Interleaves samples by presentation timestamp (`presentationTimeUs`) for WebM cluster compliance and MP4 fast-start.
     */
    @JvmStatic
    fun muxVideoAndAudio(videoFile: File, audioFile: File, outputFile: File): Boolean {
        if (!videoFile.exists() || videoFile.length() <= 0L) {
            Log.w(TAG, "muxVideoAndAudio aborted: videoFile missing or empty (${videoFile.absolutePath})")
            return false
        }
        if (!audioFile.exists() || audioFile.length() <= 0L) {
            Log.w(TAG, "muxVideoAndAudio aborted: audioFile missing or empty (${audioFile.absolutePath})")
            return false
        }

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var tempMuxFile: File? = null

        return try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var videoMime = ""
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase(Locale.ROOT) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    videoMime = mime
                    break
                }
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            var audioMime = ""
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase(Locale.ROOT) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    audioMime = mime
                    break
                }
            }

            if (videoTrackIndex < 0 || videoFormat == null || audioTrackIndex < 0 || audioFormat == null) {
                Log.w(TAG, "Could not find valid video ($videoTrackIndex) or audio ($audioTrackIndex) tracks for muxing")
                return false
            }

            if (!areTracksMuxable(videoMime, audioMime)) {
                Log.w(TAG, "Incompatible video ($videoMime) and audio ($audioMime) tracks for Android MediaMuxer")
                return false
            }

            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)

            val outputFormat = resolveMuxerOutputFormat(videoMime, audioMime)
            val extSuffix = if (outputFormat == MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM) "webm" else "mp4"
            val stagingFile = File(
                outputFile.parentFile ?: videoFile.parentFile,
                "${outputFile.name}.muxing_${System.currentTimeMillis()}.$extSuffix"
            )
            tempMuxFile = stagingFile

            muxer = MediaMuxer(stagingFile.absolutePath, outputFormat)

            if (outputFormat == MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 && videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                runCatching {
                    val rotation = videoFormat.getInteger(MediaFormat.KEY_ROTATION)
                    muxer.setOrientationHint(rotation)
                }
            }

            val outVideoTrack = muxer.addTrack(videoFormat)
            val outAudioTrack = muxer.addTrack(audioFormat)

            muxer.start()
            muxerStarted = true

            val maxVideoInput = runCatching {
                if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else DEFAULT_BUFFER_SIZE
            }.getOrDefault(DEFAULT_BUFFER_SIZE)
            val maxAudioInput = runCatching {
                if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else DEFAULT_BUFFER_SIZE
            }.getOrDefault(DEFAULT_BUFFER_SIZE)
            val bufferCapacity = maxOf(DEFAULT_BUFFER_SIZE, maxVideoInput, maxAudioInput)

            val buffer = ByteBuffer.allocateDirect(bufferCapacity)
            val bufferInfo = MediaCodec.BufferInfo()

            var videoEof = false
            var audioEof = false
            var lastVideoPts = 0L
            var lastAudioPts = 0L

            // Interleave video and audio samples by presentation timestamp (required for WebM, optimal for MP4)
            while (!videoEof || !audioEof) {
                val nextVideoPts = if (!videoEof) videoExtractor.sampleTime else -1L
                val nextAudioPts = if (!audioEof) audioExtractor.sampleTime else -1L

                if (!videoEof && nextVideoPts < 0L) {
                    videoEof = true
                    continue
                }
                if (!audioEof && nextAudioPts < 0L) {
                    audioEof = true
                    continue
                }
                if (videoEof && audioEof) break

                val writeVideoNext = when {
                    audioEof -> true
                    videoEof -> false
                    else -> nextVideoPts <= nextAudioPts
                }

                buffer.clear()
                if (writeVideoNext) {
                    val sampleSize = videoExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        videoEof = true
                    } else {
                        val pts = maxOf(lastVideoPts, nextVideoPts.coerceAtLeast(0L))
                        lastVideoPts = pts
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = pts
                        bufferInfo.flags = mapExtractorFlags(videoExtractor.sampleFlags)
                        muxer.writeSampleData(outVideoTrack, buffer, bufferInfo)
                        videoExtractor.advance()
                    }
                } else {
                    val sampleSize = audioExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        audioEof = true
                    } else {
                        val pts = maxOf(lastAudioPts, nextAudioPts.coerceAtLeast(0L))
                        lastAudioPts = pts
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = pts
                        bufferInfo.flags = mapExtractorFlags(audioExtractor.sampleFlags)
                        muxer.writeSampleData(outAudioTrack, buffer, bufferInfo)
                        audioExtractor.advance()
                    }
                }
            }

            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null

            if (stagingFile.exists() && stagingFile.length() > 0L) {
                if (outputFile.exists() && outputFile.absolutePath != stagingFile.absolutePath) {
                    outputFile.delete()
                }
                val moved = if (stagingFile.renameTo(outputFile)) {
                    true
                } else {
                    stagingFile.copyTo(outputFile, overwrite = true)
                    stagingFile.delete()
                    true
                }
                if (moved && outputFile.exists() && outputFile.length() > 0L) {
                    if (videoFile.absolutePath != outputFile.absolutePath) videoFile.delete()
                    if (audioFile.absolutePath != outputFile.absolutePath) audioFile.delete()
                    return true
                }
            }
            false
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to mux video and audio tracks", e)
            runCatching { tempMuxFile?.let { if (it.exists()) it.delete() } }
            false
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            runCatching {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            }
        }
    }

    private fun mapExtractorFlags(sampleFlags: Int): Int {
        var codecFlags = 0
        if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return codecFlags
    }

    /**
     * Derives candidate companion audio stream URLs for a Reddit `v.redd.it` DASH video URL.
     */
    @JvmStatic
    fun tryResolveRedditAudioUrls(videoUrl: String): List<String> {
        val clean = videoUrl.substringBefore("?").substringBefore("#")
        if (!clean.contains("v.redd.it/", ignoreCase = true)) return emptyList()
        val baseDir = clean.substringBeforeLast("/", "")
        if (baseDir.isBlank()) return emptyList()
        return listOf(
            "$baseDir/DASH_AUDIO_128.mp4",
            "$baseDir/DASH_AUDIO_64.mp4",
            "$baseDir/DASH_audio.mp4",
            "$baseDir/CMAF_AUDIO_128.mp4",
            "$baseDir/CMAF_AUDIO_64.mp4",
            "$baseDir/CMAF_audio.mp4",
            "$baseDir/audio.mp4",
            "$baseDir/audio"
        )
    }
}
