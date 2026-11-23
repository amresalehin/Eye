package com.amresalehin.emregallery

import android.app.Application
import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.amresalehin.emregallery.data.MediaStoreThumbnailFetcher
import com.amresalehin.emregallery.data.OkHttpDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class GalleryApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val isLowRam = activityManager?.isLowRamDevice ?: false

        // Unconstrained memory caching: Allocate up to 45% (30% on low-RAM) of JVM heap to Coil.
        // With largeHeap enabled, this unlocks hundreds of megabytes for raw pre-decoded bitmaps,
        // avoiding recurring decompression bottlenecks during 120Hz continuous scrolling.
        val cachePercent = if (isLowRam) 0.15 else 0.25

        // Bounded concurrency prevents fast flings from saturating CPU cores and causing GC/thermal spikes.
        val imageIoDispatcher = Dispatchers.IO.limitedParallelism(6)
        val imageComputeDispatcher = Dispatchers.Default.limitedParallelism(4)

        val imageLoader = ImageLoader.Builder(this)
            .okHttpClient { OkHttpDownloader.client }
            .fetcherDispatcher(imageIoDispatcher)
            .decoderDispatcher(imageComputeDispatcher)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(cachePercent)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(1073741824L) // 1GB disk cache
                    .build()
            }
            .components {
                add(MediaStoreThumbnailFetcher.Factory(this@GalleryApplication))
                add(VideoFrameDecoder.Factory())
                add(SvgDecoder.Factory())
            }
            .bitmapConfig(if (isLowRam) android.graphics.Bitmap.Config.RGB_565 else android.graphics.Bitmap.Config.ARGB_8888)
            .allowRgb565(true)
            .allowHardware(true)
            .crossfade(150)
            .respectCacheHeaders(false)
            .build()
        Coil.setImageLoader(imageLoader)

        // Deterministic registration of both face recognition engines
        com.amresalehin.emregallery.face.FaceEngineRegistry.register(com.amresalehin.emregallery.face.BuffaloLFaceEngineFactory)
        com.amresalehin.emregallery.face.FaceEngineRegistry.register(com.amresalehin.emregallery.face.EnteFaceEngineFactory)
        // Restore face coordinator state and download history off the startup main thread.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            com.amresalehin.emregallery.face.FaceProcessingCoordinator.init(this@GalleryApplication)
            com.amresalehin.emregallery.data.DownloadActivityManager.initialize(this@GalleryApplication)
        }

    }


    /**
     * Emre never limits itself when Android is not limiting it.
     * When Android signals genuine memory constraints via onTrimMemory,
     * memory caches are trimmed or cleared according to severity.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching {
            val coilCache = Coil.imageLoader(this).memoryCache
            when {
                level >= TRIM_MEMORY_MODERATE || level == TRIM_MEMORY_RUNNING_CRITICAL -> {
                    coilCache?.clear()
                    com.amresalehin.emregallery.face.FaceThumbnailHelper.clearCache()
                }
                level >= TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_RUNNING_LOW -> {
                    coilCache?.trimMemory(level)
                    com.amresalehin.emregallery.face.FaceThumbnailHelper.trimMemory(level)
                }
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching {
            Coil.imageLoader(this).memoryCache?.clear()
            com.amresalehin.emregallery.face.FaceThumbnailHelper.clearCache()
        }
    }
}

