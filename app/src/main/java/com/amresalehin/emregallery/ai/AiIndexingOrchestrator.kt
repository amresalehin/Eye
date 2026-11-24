package com.amresalehin.emregallery.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.ocr.TesseractOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

/**
 * Single orchestrator for both background batch indexing (`IndexingForegroundService`)
 * and on-demand single-photo analysis (`ViewerDetailsDrawer`).
 *
 * Hybrid two-stage pipeline:
 * 1. Local on-device stage (always runs, offline):
 *    - Dominant color palette + human color names (`ColorPaletteExtractor`)
 *    - On-device CLIP ViT-B/32 ONNX embedding + zero-shot tags (if CLIP model installed)
 *      or compact 64-dim visual color-spatial fingerprint fallback
 * 2. Optional remote Vision API stage (Ollama / OpenAI-compatible / Gemini):
 *    - Generates Karakeep/mymind-style title, searchable description, and rich tags
 *    - Enriched with cached OCR text when enabled
 *    - Respects Locked Vault privacy exclusion (`excludeLockedVaultFromCloud`)
 */
object AiIndexingOrchestrator {

    suspend fun analyzePhoto(
        context: Context,
        item: MediaItem,
        allowCloudApi: Boolean = true,
        isLockedVaultItem: Boolean = false
    ): Result<AiMediaRecord> = withContext(Dispatchers.IO) {
        runCatching {
            require(!item.isVideo) { "Video AI indexing is not enabled yet; select a photo." }

            val appContext = context.applicationContext
            val store = AiIndexStore.getInstance(appContext)
            val existing = store.getRecord(item.uri)
            val config = AiSettings.load(appContext)
            val mediaUri = Uri.parse(item.uri)

            val bitmap = decodeScaledBitmap(appContext, mediaUri, maxSide = 1024)
                ?: error("Could not decode image for AI analysis.")

            try {
                val cachedOcr = runCatching {
                    TesseractOcr.readCachedResult(appContext, mediaUri)
                }.getOrNull()

                val isScreenshotHint = item.relativePath.contains("screenshot", ignoreCase = true) ||
                    item.name.contains("screenshot", ignoreCase = true)

                // Stage 1: On-device colors & visual embedding
                val localFeatures = ColorPaletteExtractor.extract(
                    bitmap = bitmap,
                    hasOcrText = !cachedOcr.isNullOrBlank(),
                    isScreenshotHint = isScreenshotHint
                )

                val clipEmbedding = ClipOnnxEngine.encodeImage(appContext, bitmap)
                val embedding: FloatArray
                val embeddingModel: String
                val zeroShotTags: List<String>

                if (clipEmbedding != null && clipEmbedding.isNotEmpty()) {
                    embedding = clipEmbedding
                    embeddingModel = ClipOnnxModelManager.MODEL_ID
                    zeroShotTags = ClipOnnxEngine.predictZeroShotTags(appContext, clipEmbedding)
                } else {
                    embedding = localFeatures.fallbackEmbedding
                    embeddingModel = ColorPaletteExtractor.FALLBACK_EMBEDDING_MODEL
                    zeroShotTags = emptyList()
                }

                val localTags = (zeroShotTags + localFeatures.heuristicTags)
                    .map { it.trim().lowercase(Locale.ROOT) }
                    .filter { it.isNotBlank() }
                    .distinct()

                var finalTitle = existing?.title.orEmpty()
                var finalCaption = existing?.caption.orEmpty()
                var finalTags = if (existing != null && existing.hasRichCaption) {
                    (existing.tags + localTags).distinct()
                } else {
                    localTags
                }
                var captionProvider = existing?.captionProvider.orEmpty()
                var captionModel = existing?.captionModel.orEmpty()
                var status = if (existing?.hasRichCaption == true) {
                    AiMediaRecord.STATUS_FULL
                } else {
                    AiMediaRecord.STATUS_LOCAL
                }
                var warningError: String? = null

                // Stage 2: Optional remote Vision API (Ollama / Gemini / OpenAI-compatible)
                val canCallRemote = allowCloudApi &&
                    config.isRemoteConfigured &&
                    !(isLockedVaultItem && config.excludeLockedVaultFromCloud)

                if (canCallRemote) {
                    val remoteResult = VisionApiClient.analyzeImage(
                        bitmap = bitmap,
                        config = config,
                        ocrText = cachedOcr
                    )
                    remoteResult.onSuccess { res ->
                        if (res.title.isNotBlank()) finalTitle = res.title
                        if (res.caption.isNotBlank()) finalCaption = res.caption
                        finalTags = (res.tags + zeroShotTags + localFeatures.colors.take(2).map { it.name })
                            .map { it.trim().lowercase(Locale.ROOT) }
                            .filter { it.isNotBlank() }
                            .distinct()
                        captionProvider = res.provider
                        captionModel = res.model
                        status = AiMediaRecord.STATUS_FULL
                    }.onFailure { err ->
                        // Keep local colors/embeddings even if remote API fails transiently
                        warningError = err.localizedMessage ?: "Vision API call failed"
                    }
                }

                val record = AiMediaRecord(
                    mediaUri = item.uri,
                    title = finalTitle,
                    caption = finalCaption,
                    tags = finalTags,
                    colors = localFeatures.colors,
                    embedding = embedding,
                    embeddingDim = embedding.size,
                    embeddingModel = embeddingModel,
                    captionProvider = captionProvider,
                    captionModel = captionModel,
                    status = status,
                    errorMessage = warningError,
                    updatedAt = System.currentTimeMillis()
                )
                store.upsertRecord(record)
                record
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun decodeScaledBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        }
        val origW = boundsOpts.outWidth
        val origH = boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) return null

        var sampleSize = 1
        while (max(origW / sampleSize, origH / sampleSize) > maxSide * 2) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        }
    }
}
