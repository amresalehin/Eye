package com.amresalehin.emregallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.amresalehin.emregallery.ai.AiMediaAnalysis
import com.amresalehin.emregallery.ai.AiMetadataStore
import com.amresalehin.emregallery.ai.CustomAiClient
import com.amresalehin.emregallery.data.AiFilter
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.data.galleryDataStore
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.data.SearchEngine
import com.amresalehin.emregallery.data.SearchFilterState
import com.amresalehin.emregallery.data.SearchSortMode
import com.amresalehin.emregallery.data.SuggestionType
import com.amresalehin.emregallery.data.preferences.AiConfig
import com.amresalehin.emregallery.data.preferences.AiPreferencesManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomAiFeatureTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var client: CustomAiClient
    private lateinit var aiStore: AiMetadataStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        runBlocking { context.galleryDataStore.edit { it.clear() } }
        server = MockWebServer()
        server.start()
        client = CustomAiClient(OkHttpClient())
        aiStore = AiMetadataStore(context)
        aiStore.clearAll()
    }

    @After
    fun tearDown() {
        aiStore.clearAll()
        aiStore.close()
        runBlocking { context.galleryDataStore.edit { it.clear() } }
        server.shutdown()
    }

    @Test
    fun geminiAndOtherProvidersUseTheSameChatCompletionsProtocol() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"OK"}}]}""")
        )
        val geminiConfig = AiConfig(
            enabled = true,
            apiKey = "test-gemini-key",
            modelId = "gemini-2.5-flash",
            customBaseUrl = server.url("/v1beta/openai").toString().trimEnd('/')
        )
        val geminiResult = client.testConnection(geminiConfig)
        assertTrue("Gemini OpenAI-compatible endpoint should succeed", geminiResult.isSuccess)
        val geminiReq = server.takeRequest()
        assertEquals("/v1beta/openai/chat/completions", geminiReq.path)
        assertEquals("Bearer test-gemini-key", geminiReq.getHeader("Authorization"))
        val geminiPayload = JSONObject(geminiReq.body.readUtf8())
        assertEquals("gemini-2.5-flash", geminiPayload.getString("model"))

        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"OK"}}]}""")
        )
        val openAiConfig = AiConfig(
            enabled = true,
            apiKey = "sk-test-openai",
            modelId = "gpt-4o-mini",
            customBaseUrl = server.url("/v1").toString().trimEnd('/')
        )
        val openAiResult = client.testConnection(openAiConfig)
        assertTrue("OpenAI-compatible endpoint should succeed", openAiResult.isSuccess)
        val openAiReq = server.takeRequest()
        assertEquals("/v1/chat/completions", openAiReq.path)
        assertEquals("Bearer sk-test-openai", openAiReq.getHeader("Authorization"))
    }

    @Test
    fun legacyGeminiSettingsMigrateToGoogleOpenAiCompatibleEndpoint() = runBlocking {
        context.galleryDataStore.edit { prefs ->
            prefs[stringPreferencesKey("ai_provider")] = "gemini"
            prefs[stringPreferencesKey("ai_api_key")] = "legacy-gemini-key"
            prefs[stringPreferencesKey("ai_model_id")] = "gemini-2.5-flash"
        }
        val config = AiPreferencesManager(context).currentConfig()
        assertEquals("legacy-gemini-key", config.apiKey)
        assertEquals("gemini-2.5-flash", config.modelId)
        assertEquals(AiConfig.GEMINI_OPENAI_BASE_URL, config.customBaseUrl)
        assertTrue(config.isConfigured)
    }

    @Test
    fun newUnifiedConfigurationUsesOneEndpointKeyAndModel() = runBlocking {
        val repository = PreferencesRepository(context)
        repository.setAiConfiguration("custom-provider-key", "provider/vision-model", "https://api.example.com/v1")
        val config = repository.aiConfig.first()
        assertEquals("custom-provider-key", config.apiKey)
        assertEquals("provider/vision-model", config.modelId)
        assertEquals("https://api.example.com/v1", config.customBaseUrl)
        assertTrue(config.isConfigured)
    }

    @Test
    fun freshInstallUsesUnifiedOpenAiCompatibleDefaults() = runBlocking {
        val config = AiPreferencesManager(context).currentConfig()
        assertEquals(AiConfig.DEFAULT_MODEL_ID, config.modelId)
        assertEquals(AiConfig.DEFAULT_API_BASE_URL, config.customBaseUrl)
        assertFalse(config.hasApiKey)
    }

    @Test
    fun openAiCompatibleVisionTestSendsImageAndDisablesStreaming() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"blue"}}]}""")
        )
        val config = AiConfig(
            enabled = true,
            apiKey = "nvidia-test-key",
            modelId = "vendor/example-vision-model",
            customBaseUrl = server.url("/v1").toString().trimEnd('/')
        )

        val result = client.testVisionConnection(config)

        assertTrue("Vision test should succeed for a vision-capable endpoint", result.isSuccess)
        assertTrue(result.getOrThrow().contains("Vision API verified"))

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer nvidia-test-key", request.getHeader("Authorization"))
        val payload = JSONObject(request.body.readUtf8())
        assertFalse("The response parser expects a single JSON response", payload.optBoolean("stream", true))
        val content = payload.getJSONArray("messages")
            .getJSONObject(0)
            .getJSONArray("content")
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertTrue(
            content.getJSONObject(1).getJSONObject("image_url")
                .getString("url").startsWith("data:image/jpeg;base64,")
        )
    }

    @Test
    fun visionCapabilityIsDeterminedByActualProviderResponseNotModelName() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"This model only supports text input","code":"unsupported_modality"}}""")
        )
        val config = AiConfig(
            enabled = true,
            apiKey = "provider-test-key",
            modelId = "provider/custom-model-id",
            customBaseUrl = server.url("/v1").toString().trimEnd('/')
        )

        val result = client.testVisionConnection(config)

        assertFalse(result.isSuccess)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("HTTP 400"))
        assertTrue(message.contains("only supports text input"))
        assertEquals("The endpoint must be probed instead of guessing from its model ID", 1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        val payload = JSONObject(request.body.readUtf8())
        assertFalse(payload.optBoolean("stream", true))
        assertEquals("provider/custom-model-id", payload.getString("model"))
    }

    @Test
    fun openAiCompatibleVisionRetriesWithoutUnsupportedJsonMode() = runBlocking {
        val tempImage = createTempJpegFile("openai_compatible_vision.jpg")
        try {
            server.enqueue(
                MockResponse().setResponseCode(400)
                    .setBody("""{"error":{"message":"response_format is not supported by this model","code":"unsupported_parameter"}}""")
            )
            val analysisJson = JSONObject().apply {
                put("title", "Blue Mug")
                put("description", "A blue mug on a table.")
                put("tags", org.json.JSONArray(listOf("mug", "blue", "table")))
                put("collection", "Objects")
                put("key_details", "")
            }.toString()
            val successBody = JSONObject().put(
                "choices",
                org.json.JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("content", analysisJson)
                    )
                )
            ).toString()
            server.enqueue(MockResponse().setResponseCode(200).setBody(successBody))
            val config = AiConfig(
                enabled = true,
                apiKey = "provider-test-key",
                modelId = "provider/vision-model",
                customBaseUrl = server.url("/v1").toString().trimEnd('/')
            )

            val result = client.analyzeImage(
                context = context,
                mediaUri = Uri.fromFile(tempImage).toString(),
                config = config
            )

            assertTrue("Image analysis should retry without the optional JSON-mode field", result.isSuccess)
            assertEquals("Blue Mug", result.getOrThrow().title)
            val firstPayload = JSONObject(server.takeRequest().body.readUtf8())
            val retryPayload = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(firstPayload.has("response_format"))
            assertFalse(retryPayload.has("response_format"))
            assertFalse(retryPayload.optBoolean("stream", true))
            val retryContent = retryPayload.getJSONArray("messages")
                .getJSONObject(0).getJSONArray("content")
            assertEquals("image_url", retryContent.getJSONObject(1).getString("type"))
        } finally {
            tempImage.delete()
        }
    }

    @Test
    fun openAiCompatibleErrorsIncludeHttpStatusAndProviderMessage() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"This model does not accept image input","code":"unsupported_modality"}}""")
        )
        val config = AiConfig(
            enabled = true,
            apiKey = "test-key",
            modelId = "vendor/custom-model",
            customBaseUrl = server.url("/v1").toString().trimEnd('/')
        )

        val result = client.testConnection(config)

        assertFalse(result.isSuccess)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("HTTP 400"))
        assertTrue(message.contains("This model does not accept image input"))
        assertTrue(message.contains("unsupported_modality"))
    }

    @Test
    fun analyzeImage_and_askAboutImage_parseStructuredJsonAndText() = runBlocking {
        val tempImage = createTempJpegFile("receipt_sample.jpg")
        try {
            val imageUri = Uri.fromFile(tempImage).toString()
            val fencedJson = """
                ```json
                {
                  "title": "Coffee Shop Receipt",
                  "description": "Paper receipt showing two espresso drinks and total amount.",
                  "tags": ["#Receipt", "Coffee", "Espresso", "receipt"],
                  "collection": "Receipts",
                  "key_details": "Total $9.50"
                }
                ```
            """.trimIndent()
            val compatibleResponse = JSONObject().put(
                "choices",
                org.json.JSONArray().put(
                    JSONObject().put("message", JSONObject().put("content", fencedJson))
                )
            ).toString()

            server.enqueue(MockResponse().setResponseCode(200).setBody(compatibleResponse))

            val config = AiConfig(
                enabled = true,
                apiKey = "key-123",
                modelId = "gemini-2.5-flash",
                customBaseUrl = server.url("/v1beta/openai").toString().trimEnd('/'),
                customPromptInstructions = "Highlight totals on receipts"
            )

            val analysisResult = client.analyzeImage(
                context = context,
                mediaUri = imageUri,
                config = config,
                existingCollections = listOf("Receipts", "Travel"),
                ocrContext = "TOTAL $9.50"
            )

            assertTrue("analyzeImage should succeed", analysisResult.isSuccess)
            val analysis = analysisResult.getOrThrow()
            assertEquals("Coffee Shop Receipt", analysis.title)
            assertEquals("Receipts", analysis.suggestedCollection)
            assertEquals("Total $9.50", analysis.keyDetails)
            assertEquals(listOf("receipt", "coffee", "espresso"), analysis.tags)

            val analyzeReqBody = server.takeRequest().body.readUtf8()
            assertTrue(analyzeReqBody.contains("image_url"))
            assertTrue(analyzeReqBody.contains("TOTAL $9.50"))
            assertTrue(analyzeReqBody.contains("Highlight totals on receipts"))

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"choices":[{"message":{"content":"The total on the receipt is $9.50."}}]}""")
            )
            val qaResult = client.askAboutImage(
                context = context,
                mediaUri = imageUri,
                question = "How much was the total?",
                config = config,
                ocrContext = "TOTAL $9.50"
            )
            assertTrue(qaResult.isSuccess)
            assertEquals("The total on the receipt is $9.50.", qaResult.getOrThrow())
        } finally {
            tempImage.delete()
        }
    }

    @Test
    fun semanticSearch_returnsMatchedUrisAndSummary() = runBlocking {
        val searchPayload = """
            {
              "matched_indices": [1, 99, 0],
              "summary": "Found 2 travel and receipt screenshots."
            }
        """.trimIndent()
        val openAiResp = org.json.JSONObject().put(
            "choices",
            org.json.JSONArray().put(
                org.json.JSONObject().put(
                    "message",
                    org.json.JSONObject().put("content", searchPayload)
                )
            )
        ).toString()
        server.enqueue(MockResponse().setResponseCode(200).setBody(openAiResp))

        val analyses = listOf(
            AiMediaAnalysis(
                mediaUri = "content://media/external/images/media/10",
                title = "Train Ticket",
                description = "QR boarding pass for Eurostar",
                tags = listOf("ticket", "travel"),
                suggestedCollection = "Travel"
            ),
            AiMediaAnalysis(
                mediaUri = "content://media/external/images/media/20",
                title = "Hotel Invoice",
                description = "Receipt for two nights stay",
                tags = listOf("invoice", "receipt"),
                suggestedCollection = "Receipts"
            )
        )

        val config = AiConfig(
            enabled = true,
            apiKey = "sk-search",
            modelId = "gpt-4o-mini",
            customBaseUrl = server.url("/v1").toString().trimEnd('/')
        )

        val res = client.semanticSearch("Find my hotel invoice or train pass", analyses, config)
        assertTrue(res.isSuccess)
        val match = res.getOrThrow()
        assertEquals(
            listOf(
                "content://media/external/images/media/20",
                "content://media/external/images/media/10"
            ),
            match.matchingUris
        )
        assertEquals("Found 2 travel and receipt screenshots.", match.summary)
    }

    @Test
    fun aiMetadataStore_persistsAnalysesFailuresAndCollections() {
        val uri1 = "content://media/external/images/media/101"
        val uri2 = "content://media/external/images/media/102"
        val uri3 = "content://media/external/images/media/103"

        aiStore.saveAnalysis(
            AiMediaAnalysis(
                mediaUri = uri1,
                title = "Boarding Pass",
                description = "Airline QR pass",
                tags = listOf("travel", "flight"),
                suggestedCollection = "Travel",
                keyDetails = "Gate B12",
                modelUsed = "gemini-2.5-flash"
            )
        )
        aiStore.saveAnalysis(
            AiMediaAnalysis(
                mediaUri = uri2,
                title = "Grocery Receipt",
                description = "Supermarket checkout receipt",
                tags = listOf("receipt", "groceries"),
                suggestedCollection = "Receipts",
                modelUsed = "gemini-2.5-flash"
            )
        )
        // Completed record must not be overwritten by markFailed
        aiStore.markFailed(uri1, "gemini-2.5-flash", "Transient error")
        aiStore.markFailed(uri3, "gemini-2.5-flash", "429 Rate limit")

        assertEquals(2, aiStore.getTotalCompletedCount())
        assertEquals(2, aiStore.getCompletedCount(listOf(uri1, uri2, uri3)))
        assertEquals("Boarding Pass", aiStore.getAnalysis(uri1)?.title)

        val failed = aiStore.getFailedItems()
        assertEquals(1, failed.size)
        assertEquals(uri3, failed.first().first)
        assertEquals("429 Rate limit", failed.first().second)

        val collections = aiStore.getCollectionSummaries(setOf(uri1, uri2))
        assertEquals(2, collections.size)

        aiStore.clearFailed()
        assertTrue(aiStore.getFailedItems().isEmpty())
        assertNull(aiStore.getAnalysis(uri3))
        assertNotNull(aiStore.getAnalysis(uri1))

        aiStore.clearAll()
        assertEquals(0, aiStore.getTotalCompletedCount())
    }

    @Test
    fun searchEngine_matchesAiMetadataFiltersAndCollections() = runBlocking {
        val sampleMedia = listOf(
            MediaItem(
                id = 1L,
                uri = "content://media/external/images/media/201",
                name = "IMG_0001.jpg",
                mimeType = "image/jpeg",
                dateAddedSeconds = 1710000000L,
                dateTakenMillis = 1710000000000L,
                sizeBytes = 1024L,
                width = 1080,
                height = 1920,
                relativePath = "Pictures/Screenshots",
                isVideo = false
            ),
            MediaItem(
                id = 2L,
                uri = "content://media/external/images/media/202",
                name = "IMG_0002.jpg",
                mimeType = "image/jpeg",
                dateAddedSeconds = 1710000500L,
                dateTakenMillis = 1710000500000L,
                sizeBytes = 2048L,
                width = 1080,
                height = 1920,
                relativePath = "DCIM/Camera",
                isVideo = false
            )
        )

        aiStore.saveAnalysis(
            AiMediaAnalysis(
                mediaUri = "content://media/external/images/media/201",
                title = "Kotlin Coroutine Snippet",
                description = "IDE screenshot showing Flow collector code",
                tags = listOf("kotlin", "coroutines", "snippet"),
                suggestedCollection = "Code",
                keyDetails = "Dispatchers.IO",
                modelUsed = "gemini-2.5-flash"
            )
        )

        val engine = SearchEngine(context)

        val queryMatch = engine.searchMedia(
            query = "coroutine",
            media = sampleMedia,
            filter = SearchFilterState(),
            sortMode = SearchSortMode.RELEVANCE,
            customAlbums = emptyList(),
            customTagsMap = emptyMap(),
            customPeopleMap = emptyMap(),
            favourites = emptySet()
        )
        assertEquals(listOf("content://media/external/images/media/201"), queryMatch.items.map { it.uri })

        val analyzedOnly = engine.searchMedia(
            query = "",
            media = sampleMedia,
            filter = SearchFilterState(aiFilter = AiFilter.AI_ANALYZED),
            sortMode = SearchSortMode.RELEVANCE,
            customAlbums = emptyList(),
            customTagsMap = emptyMap(),
            customPeopleMap = emptyMap(),
            favourites = emptySet()
        )
        assertEquals(listOf("content://media/external/images/media/201"), analyzedOnly.items.map { it.uri })

        val notAnalyzedOnly = engine.searchMedia(
            query = "",
            media = sampleMedia,
            filter = SearchFilterState(aiFilter = AiFilter.NOT_ANALYZED),
            sortMode = SearchSortMode.RELEVANCE,
            customAlbums = emptyList(),
            customTagsMap = emptyMap(),
            customPeopleMap = emptyMap(),
            favourites = emptySet()
        )
        assertEquals(listOf("content://media/external/images/media/202"), notAnalyzedOnly.items.map { it.uri })

        val collectionFiltered = engine.searchMedia(
            query = "",
            media = sampleMedia,
            filter = SearchFilterState(selectedAiCollection = "Code"),
            sortMode = SearchSortMode.RELEVANCE,
            customAlbums = emptyList(),
            customTagsMap = emptyMap(),
            customPeopleMap = emptyMap(),
            favourites = emptySet()
        )
        assertEquals(listOf("content://media/external/images/media/201"), collectionFiltered.items.map { it.uri })

        val discovery = engine.getDiscoveryContent(
            media = sampleMedia,
            customAlbums = emptyList(),
            customTagsMap = emptyMap(),
            customPeopleMap = emptyMap(),
            favourites = emptySet()
        )
        assertTrue(discovery.aiCollections.any { it.title == "Code" })

        val suggestions = engine.getLiveSuggestions(
            query = "Code",
            media = sampleMedia,
            recentSearches = emptyList(),
            customAlbums = emptyList(),
            customTagsMap = emptyMap(),
            customPeopleMap = emptyMap()
        )
        assertTrue(suggestions.any { it.type == SuggestionType.AI_COLLECTION && it.title == "Code" })
        assertFalse(suggestions.any { it.type == SuggestionType.AI_COLLECTION && it.title == "Unknown" })
    }

    private fun createTempJpegFile(fileName: String): File {
        val file = File(context.cacheDir, fileName)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.DKGRAY)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()
        return file
    }
}
