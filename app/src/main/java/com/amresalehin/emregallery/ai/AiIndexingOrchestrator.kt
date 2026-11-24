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
 * Dual-system AI orchestrator cleanly separating two independent AI subsystems:
 *
 * 1. **System 1 — Semantic & Visual Indexing (`indexSemanticOnly`)**:
 *    - Fast, continuous background CLIP ViT-B/32 ONNX embedding (512-dim) for natural-language
 *      photo search & visually similar image lookup, plus dominant color palette extraction.
 *    - Strictly does NOT fabricate descriptions or pretend CLIP describes photographs.
 *    - Preserves any existing VLM title/caption/tags on the record.
 *
 * 2. **System 2 — Real VLM Understanding (`analyzeWithVlm` & `askAboutPhoto`)**:
 *    - Runs an on-device Vision-Language Model ([MediaPipeVlmEngine] / pluggable [LocalVlmEngine])
 *      or optional self-hosted/cloud Vision API ([VisionApiClient]) with cached OCR context.
 *    - Generates real descriptions, titles, intelligent tags, and answers contextual questions.
 *    - Respects Locked Vault privacy (`excludeLockedVaultFromCloud` keeps vault photos strictly on-device).
 */
object AiIndexingOrchestrator {

    /**
     * System 1: Fast background semantic embedding + dominant color indexing (CLIP + Palette).
     */
    suspend fun indexSemanticOnly(
        context: Context,
        item: MediaItem
    ): Result<AiMediaRecord> = withContext(Dispatchers.IO) {
        runCatching {
            require(!item.isVideo) { "Semantic indexing currently supports photos." }

            val appContext = context.applicationContext
            val store = AiIndexStore.getInstance(appContext)
            val existing = store.getRecord(item.uri)
            val mediaUri = Uri.parse(item.uri)

            val bitmap = decodeScaledBitmap(appContext, mediaUri, maxSide = 512)
                ?: error("Could not decode image for semantic indexing.")

            try {
                val localFeatures = ColorPaletteExtractor.extract(bitmap = bitmap)
                val clipEmbedding = ClipOnnxEngine.encodeImage(appContext, bitmap)

                val embedding: FloatArray
                val embeddingModel: String
                if (clipEmbedding != null && clipEmbedding.isNotEmpty()) {
                    embedding = clipEmbedding
                    embeddingModel = ClipOnnxModelManager.MODEL_ID
                } else {
                    embedding = localFeatures.fallbackEmbedding
                    embeddingModel = ColorPaletteExtractor.FALLBACK_EMBEDDING_MODEL
                }

                // Preserve existing VLM title, caption, tags, and status if already analyzed by VLM
                val record = AiMediaRecord(
                    mediaUri = item.uri,
                    title = existing?.title.orEmpty(),
                    caption = existing?.caption.orEmpty(),
                    tags = existing?.tags.orEmpty(),
                    colors = localFeatures.colors,
                    embedding = embedding,
                    embeddingDim = embedding.size,
                    embeddingModel = embeddingModel,
                    captionProvider = existing?.captionProvider.orEmpty(),
                    captionModel = existing?.captionModel.orEmpty(),
                    status = if (existing?.hasRichCaption == true) AiMediaRecord.STATUS_FULL else AiMediaRecord.STATUS_LOCAL,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
                store.upsertRecord(record)
                record
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    /**
     * System 2: Real VLM analysis for detailed descriptions, intelligent tagging, and OCR-aware
     * contextual image understanding (also ensures semantic embedding & colors exist).
     */
    suspend fun analyzeWithVlm(
        context: Context,
        item: MediaItem,
        isLockedVaultItem: Boolean = false
    ): Result<AiMediaRecord> = withContext(Dispatchers.IO) {
        runCatching {
            require(!item.isVideo) { "VLM analysis currently supports photos." }

            val appContext = context.applicationContext
            val store = AiIndexStore.getInstance(appContext)
            val existing = store.getRecord(item.uri)
            val config = AiSettings.load(appContext)
            val mediaUri = Uri.parse(item.uri)

            val bitmap = decodeScaledBitmap(appContext, mediaUri, maxSide = 1024)
                ?: error("Could not decode image for VLM analysis.")

            try {
                val cachedOcr = runCatching {
                    TesseractOcr.readCachedResult(appContext, mediaUri)
                }.getOrNull()

                // Ensure color palette & semantic embedding are populated alongside VLM output
                val localFeatures = ColorPaletteExtractor.extract(bitmap = bitmap)
                val embedding: FloatArray
                val embeddingModel: String
                if (existing != null && existing.embedding.isNotEmpty() && existing.embeddingModel == ClipOnnxModelManager.MODEL_ID) {
                    embedding = existing.embedding
                    embeddingModel = existing.embeddingModel
                } else {
                    val clipEmbedding = ClipOnnxEngine.encodeImage(appContext, bitmap)
                    if (clipEmbedding != null && clipEmbedding.isNotEmpty()) {
                        embedding = clipEmbedding
                        embeddingModel = ClipOnnxModelManager.MODEL_ID
                    } else {
                        embedding = localFeatures.fallbackEmbedding
                        embeddingModel = ColorPaletteExtractor.FALLBACK_EMBEDDING_MODEL
                    }
                }

                // Route to On-Device VLM or Remote Vision API (enforcing Locked Vault privacy)
                val useLocalVlm = config.provider == AiProviderType.ON_DEVICE_VLM ||
                    (isLockedVaultItem && config.excludeLockedVaultFromCloud)

                val vlmResult: Result<VlmAnalysisOutput> = if (useLocalVlm) {
                    val engine = LocalVlmRegistry.get(config.localVlmEngineId)
                    engine.analyzeImage(
                        context = appContext,
                        bitmap = bitmap,
                        ocrText = cachedOcr,
                        includeOcrContext = config.includeOcrContext
                    )
                } else {
                    VisionApiClient.analyzeImage(
                        bitmap = bitmap,
                        config = config,
                        ocrText = cachedOcr
                    ).map { res ->
                        VlmAnalysisOutput(
                            title = res.title,
                            caption = res.caption,
                            tags = res.tags,
                            rawResponse = res.caption,
                            engineId = res.provider,
                            modelName = res.model
                        )
                    }
                }

                val output = vlmResult.getOrThrow()
                val normalizedTags = output.tags
                    .map { it.trim().lowercase(Locale.ROOT).removePrefix("#") }
                    .filter { it.isNotBlank() }
                    .distinct()

                val record = AiMediaRecord(
                    mediaUri = item.uri,
                    title = output.title,
                    caption = output.caption,
                    tags = normalizedTags,
                    colors = localFeatures.colors,
                    embedding = embedding,
                    embeddingDim = embedding.size,
                    embeddingModel = embeddingModel,
                    captionProvider = output.engineId,
                    captionModel = output.modelName,
                    status = AiMediaRecord.STATUS_FULL,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
                store.upsertRecord(record)
                record
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    /**
     * System 2 (Interactive): Ask a contextual question about a photo inside the Viewer
     * using the active On-Device VLM (MediaPipe/LiteRT-LM) or configured Vision API.
     */
    suspend fun askAboutPhoto(
        context: Context,
        item: MediaItem,
        question: String,
        isLockedVaultItem: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(!item.isVideo) { "Visual Q&A currently supports photos." }
            val appContext = context.applicationContext
            val config = AiSettings.load(appContext)
            val mediaUri = Uri.parse(item.uri)
            val bitmap = decodeScaledBitmap(appContext, mediaUri, maxSide = 1024)
                ?: error("Could not decode image.")

            try {
                val cachedOcr = runCatching {
                    TesseractOcr.readCachedResult(appContext, mediaUri)
                }.getOrNull()

                val useLocalVlm = config.provider == AiProviderType.ON_DEVICE_VLM ||
                    (isLockedVaultItem && config.excludeLockedVaultFromCloud)

                if (useLocalVlm) {
                    LocalVlmRegistry.get(config.localVlmEngineId)
                        .askQuestion(appContext, bitmap, question, cachedOcr)
                        .getOrThrow()
                } else {
                    VisionApiClient.askQuestion(bitmap, config, question, cachedOcr).getOrThrow()
                }
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    /**
     * Backward-compatible entry point delegating to [indexSemanticOnly] or [analyzeWithVlm].
     */
    suspend fun analyzePhoto(
        context: Context,
        item: MediaItem,
        allowCloudApi: Boolean = true,
        isLockedVaultItem: Boolean = false
    ): Result<AiMediaRecord> {
        return if (allowCloudApi) {
            analyzeWithVlm(context, item, isLockedVaultItem)
        } else {
            indexSemanticOnly(context, item)
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
