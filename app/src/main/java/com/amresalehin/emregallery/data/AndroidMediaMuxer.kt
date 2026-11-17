package com.amresalehin.emregallery.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Lightweight on-device MP4 container muxer powered by Android's native [MediaExtractor] and [MediaMuxer].
 * Losslessly merges separate DASH video-only (.mp4) and audio-only (.m4a / .mp4 / .aac) streams
 * without requiring an external FFmpeg binary.
 */
object AndroidMediaMuxer {
    private const val TAG = "AndroidMediaMuxer"
    private const val DEFAULT_BUFFER_SIZE = 1024 * 1024 // 1 MB sample buffer

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
                val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase() ?: continue
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
                val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase() ?: continue
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
     * into [outputFile] using [MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4].
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

        val tempMuxFile = File(
            outputFile.parentFile ?: videoFile.parentFile,
            "${outputFile.name}.muxing_${System.currentTimeMillis()}.mp4"
        )

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        return try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase() ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase() ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (videoTrackIndex < 0 || videoFormat == null || audioTrackIndex < 0 || audioFormat == null) {
                Log.w(TAG, "Could not find valid video ($videoTrackIndex) or audio ($audioTrackIndex) tracks for muxing")
                return false
            }

            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)

            muxer = MediaMuxer(tempMuxFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
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

            // 1. Copy all video samples
            var lastVideoPts = 0L
            while (true) {
                buffer.clear()
                val sampleSize = videoExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val rawPts = videoExtractor.sampleTime
                val pts = if (rawPts < 0L) lastVideoPts else rawPts
                lastVideoPts = maxOf(lastVideoPts, pts)

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = pts
                bufferInfo.flags = mapExtractorFlags(videoExtractor.sampleFlags)

                muxer.writeSampleData(outVideoTrack, buffer, bufferInfo)
                videoExtractor.advance()
            }

            // 2. Copy all audio samples
            var lastAudioPts = 0L
            while (true) {
                buffer.clear()
                val sampleSize = audioExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val rawPts = audioExtractor.sampleTime
                val pts = if (rawPts < 0L) lastAudioPts else rawPts
                lastAudioPts = maxOf(lastAudioPts, pts)

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = pts
                bufferInfo.flags = mapExtractorFlags(audioExtractor.sampleFlags)

                muxer.writeSampleData(outAudioTrack, buffer, bufferInfo)
                audioExtractor.advance()
            }

            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null

            if (tempMuxFile.exists() && tempMuxFile.length() > 0L) {
                if (outputFile.exists() && outputFile.absolutePath != tempMuxFile.absolutePath) {
                    outputFile.delete()
                }
                val moved = if (tempMuxFile.renameTo(outputFile)) {
                    true
                } else {
                    tempMuxFile.copyTo(outputFile, overwrite = true)
                    tempMuxFile.delete()
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
            runCatching { if (tempMuxFile.exists()) tempMuxFile.delete() }
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
