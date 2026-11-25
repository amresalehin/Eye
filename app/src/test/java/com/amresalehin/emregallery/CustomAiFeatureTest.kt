package com.amresalehin.emregallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.ai.AiMediaAnalysis
import com.amresalehin.emregallery.ai.AiMetadataStore
import com.amresalehin.emregallery.ai.CustomAiClient
import com.amresalehin.emregallery.data.AiFilter
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.data.SearchEngine
import com.amresalehin.emregallery.data.SearchFilterState
import com.amresalehin.emregallery.data.SearchSortMode
import com.amresalehin.emregallery.data.SuggestionType
import com.amresalehin.emregallery.data.preferences.AiConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
        server.shutdown()
    }

    @Test
    fun testEndpointCapabilities_openaiCompatible_andGeminiCompat_testBothTextAndVision() = runBlocking {
        // 1. Gemini OpenAI-compatibility endpoint must route through the common OpenAI-compatible client
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"OK"}}]}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"Blue"}}]}""")
        )

        val geminiCompatConfig = AiConfig(
            enabled = true,
            baseUrl = server.url("/v1beta/openai").toString().trimEnd('/'),
            apiKey = "AIza-compat-key",
            modelId = "custom-model-alpha" // no model-name assumptions
        )
        val compatResult = client.testEndpointCapabilities(geminiCompatConfig)
        assertTrue("Capability test should succeed", compatResult.isSuccess)
        val report = compatResult.getOrThrow()
        assertEquals("OpenAI-compatible", report.protocolLabel)
        assertTrue("Text capability should pass", report.text.supported)
        assertTrue("Vision capability should pass", report.vision.supported)
        assertTrue(report.fullyReady)

        val textReq = server.takeRequest()
        assertEquals("/v1beta/openai/chat/completions", textReq.path)
        assertEquals("Bearer AIza-compat-key", textReq.getHeader("Authorization"))
        val textJson = JSONObject(textReq.body.readUtf8())
        assertEquals("custom-model-alpha", textJson.getString("model"))

        val visionReq = server.takeRequest()
        assertEquals("/v1beta/openai/chat/completions", visionReq.path)
        val visionJson = JSONObject(visionReq.body.readUtf8())
        val contentArr = visionJson.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        val imageUrl = contentArr.getJSONObject(1).getJSONObject("image_url").getString("url")
        assertTrue("Vision probe must send data:image/jpeg;base64 payload", imageUrl.startsWith("data:image/jpeg;base64,"))
        assertTrue(imageUrl.removePrefix("data:image/jpeg;base64,").isNotBlank())
    }

    @Test
    fun testEndpointCapabilities_reportsPartialFailure_whenTextPassesAndVisionUnsupported() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"OK"}}]}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"Image input is not supported for this model"}}""")
        )

        val textOnlyConfig = AiConfig(
            enabled = true,
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "sk-local-test",
            modelId = "llama-text-only-8b"
        )
        val result = client.testEndpointCapabilities(textOnlyConfig)
        assertTrue(result.isSuccess)
        val report = result.getOrThrow()
        assertTrue("Text should pass", report.text.supported)
        assertFalse("Vision should fail", report.vision.supported)
        assertFalse(report.fullyReady)
        assertTrue(report.vision.detail.contains("not supported"))
    }

    @Test
    fun internalNativeAdapters_dispatchByUrlProtocol_withoutModelNameAssumptions() = runBlocking {
        // Gemini native URL with a completely non-Gemini-named model ID
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"candidates":[{"content":{"parts":[{"text":"OK"}]}}]}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"candidates":[{"content":{"parts":[{"text":"Blue square"}]}}]}""")
        )

        val nativeGeminiUrlConfig = AiConfig(
            enabled = true,
            baseUrl = server.url("/v1beta/models/arbitrary-vision-model:generateContent").toString(),
            apiKey = "native-key",
            modelId = "arbitrary-vision-model"
        )
        val geminiNativeReport = client.testEndpointCapabilities(nativeGeminiUrlConfig).getOrThrow()
        assertEquals("Gemini native", geminiNativeReport.protocolLabel)
        assertTrue(geminiNativeReport.fullyReady)

        val gTextReq = server.takeRequest()
        assertEquals("/v1beta/models/arbitrary-vision-model:generateContent", gTextReq.path)
        assertEquals("native-key", gTextReq.getHeader("x-goog-api-key"))
        val gVisionReq = server.takeRequest()
        assertTrue(gVisionReq.body.readUtf8().contains("inline_data"))

        // Anthropic native /v1/messages endpoint with arbitrary model ID
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"content":[{"type":"text","text":"OK"}]}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"content":[{"type":"text","text":"Blue"}]}""")
        )

        val anthropicConfig = AiConfig(
            enabled = true,
            baseUrl = server.url("/v1/messages").toString(),
            apiKey = "ant-key",
            modelId = "custom-proxy-model"
        )
        val antReport = client.testEndpointCapabilities(anthropicConfig).getOrThrow()
        assertEquals("Anthropic native", antReport.protocolLabel)
        assertTrue(antReport.fullyReady)

        val aTextReq = server.takeRequest()
        assertEquals("/v1/messages", aTextReq.path)
        assertEquals("ant-key", aTextReq.getHeader("x-api-key"))
        val aVisionReq = server.takeRequest()
        assertTrue(aVisionReq.body.readUtf8().contains(""""type":"image""""))
    }

    @Test
    fun analyzeImage_and_askAboutImage_workThroughCommonOpenAiClient_withJsonRetryFallback() = runBlocking {
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
            val openAiJsonBody = JSONObject().put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("content", fencedJson)
                    )
                )
            ).toString()

            // First attempt with response_format gets 400; client should automatically retry without response_format
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error":{"message":"response_format is unsupported"}}""")
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(openAiJsonBody))

            val config = AiConfig(
                enabled = true,
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                apiKey = "key-123",
                modelId = "any-vision-model",
                analysisPromptTemplate = AiConfig.DEFAULT_ANALYSIS_PROMPT_TEMPLATE + "\n\nHighlight totals on receipts"
            )

            val analysisResult = client.analyzeImage(
                context = context,
                mediaUri = imageUri,
                config = config,
                existingCollections = listOf("Receipts", "Travel"),
                ocrContext = "TOTAL $9.50"
            )

            assertTrue("analyzeImage should succeed after fallback retry", analysisResult.isSuccess)
            val analysis = analysisResult.getOrThrow()
            assertEquals("Coffee Shop Receipt", analysis.title)
            assertEquals("Receipts", analysis.suggestedCollection)
            assertEquals("Total $9.50", analysis.keyDetails)
            assertEquals(listOf("receipt", "coffee", "espresso"), analysis.tags)

            val firstReqBody = server.takeRequest().body.readUtf8()
            assertTrue(firstReqBody.contains("response_format"))
            val retryReqBody = server.takeRequest().body.readUtf8()
            assertFalse(retryReqBody.contains("response_format"))
            assertTrue(retryReqBody.contains("image_url"))
            assertTrue(retryReqBody.contains("TOTAL $9.50"))
            assertTrue(retryReqBody.contains("Receipts, Travel"))
            assertFalse(retryReqBody.contains("{{existing_collections}}"))
            assertFalse(retryReqBody.contains("{{ocr_context}}"))
            assertTrue(retryReqBody.contains("Highlight totals on receipts"))

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
        val openAiResp = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject().put("content", searchPayload)
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
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "sk-search",
            modelId = "custom-search-model"
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
                modelUsed = "custom-model"
            )
        )
        aiStore.saveAnalysis(
            AiMediaAnalysis(
                mediaUri = uri2,
                title = "Grocery Receipt",
                description = "Supermarket checkout receipt",
                tags = listOf("receipt", "groceries"),
                suggestedCollection = "Receipts",
                modelUsed = "custom-model"
            )
        )
        aiStore.markFailed(uri1, "custom-model", "Transient error")
        aiStore.markFailed(uri3, "custom-model", "429 Rate limit")

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
                modelUsed = "custom-model"
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
