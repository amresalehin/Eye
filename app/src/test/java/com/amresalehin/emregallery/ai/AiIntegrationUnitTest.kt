package com.amresalehin.emregallery.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiIntegrationUnitTest {

    @Test
    fun vectorMath_roundTripAndCosineSimilarity() {
        val v1 = AiVectorMath.l2Normalize(floatArrayOf(3f, 4f, 0f))
        assertEquals(0.6f, v1[0], 1e-4f)
        assertEquals(0.8f, v1[1], 1e-4f)

        val bytes = AiVectorMath.toBytes(v1)
        val restored = AiVectorMath.fromBytes(bytes)
        assertEquals(v1.size, restored.size)
        assertEquals(1.0f, AiVectorMath.cosineSimilarity(v1, restored), 1e-4f)

        val orthogonal = AiVectorMath.l2Normalize(floatArrayOf(0f, 0f, 1f))
        assertEquals(0.0f, AiVectorMath.cosineSimilarity(v1, orthogonal), 1e-4f)
    }

    @Test
    fun visionApiClient_parsesFencedJsonResponse() {
        val raw = """
            ```json
            {
              "title": "Coffee Shop Receipt",
              "caption": "A printed receipt from a local cafe showing an espresso and croissant.",
              "tags": ["#Receipt", "Coffee", "Cafe", "Document", "receipt"]
            }
            ```
        """.trimIndent()
        val config = AiConfig(
            provider = AiProviderType.OLLAMA,
            baseUrl = "http://192.168.1.10:11434/v1",
            modelName = "qwen2.5vl:3b"
        )
        val parsed = VisionApiClient.parseStructuredResponse(raw, config)
        assertEquals("Coffee Shop Receipt", parsed.title)
        assertTrue(parsed.caption.contains("espresso"))
        assertEquals(listOf("receipt", "coffee", "cafe", "document"), parsed.tags)
        assertEquals("ollama", parsed.provider)
    }

    @Test
    fun aiConfig_remoteConfigurationRules() {
        assertFalse(AiConfig(provider = AiProviderType.ON_DEVICE_VLM).isRemoteConfigured)
        assertEquals(LocalVlmRegistry.ENGINE_MEDIAPIPE, LocalVlmRegistry.get("mediapipe").engineId)
        assertEquals(LocalVlmRegistry.ENGINE_LLAMACPP, LocalVlmRegistry.get("llamacpp").engineId)
        assertTrue(
            AiConfig(
                provider = AiProviderType.OLLAMA,
                baseUrl = "http://192.168.1.10:11434/v1",
                modelName = "llava",
                apiKey = ""
            ).isRemoteConfigured
        )
        assertFalse(
            AiConfig(
                provider = AiProviderType.GEMINI,
                baseUrl = AiProviderType.GEMINI.defaultBaseUrl,
                modelName = AiProviderType.GEMINI.defaultModel,
                apiKey = ""
            ).isRemoteConfigured
        )
    }
}
