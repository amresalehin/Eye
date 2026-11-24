package com.amresalehin.emregallery.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiResponseParserTest {

    @Test
    fun parsesDescriptionAndEverySupportedTagCategory() {
        val parsed = AiResponseParser.parse(
            """
            {
              "description": "A person cycling beside a river.",
              "tags": {
                "people": ["person", "cyclist"],
                "objects": ["bicycle", "river"],
                "activities": ["cycling"],
                "locations": ["riverside"],
                "events": ["outdoor recreation"],
                "scenes": ["outdoor", "waterfront"],
                "visual_attributes": ["green", "daylight"]
              }
            }
            """.trimIndent()
        )

        assertEquals("A person cycling beside a river.", parsed.description)
        assertEquals(
            setOf("people", "objects", "activities", "locations", "events", "scenes", "visual_attributes"),
            parsed.tags.keys
        )
        assertEquals(listOf("bicycle", "river"), parsed.tags["objects"])
    }

    @Test
    fun removesDuplicateAndEmptyTagsCaseInsensitively() {
        val parsed = AiResponseParser.parse(
            """
            {
              "description": "A red car on a road.",
              "tags": {
                "objects": ["Car", " car ", "", "road", "road."]
              }
            }
            """.trimIndent()
        )

        assertEquals(listOf("Car", "road"), parsed.tags["objects"])
    }

    @Test
    fun acceptsFencedJsonAndFlatTagObjects() {
        val json = """
            {
              "description": "A dog running on grass.",
              "tag_list": [
                {"category": "activities", "name": "running"},
                {"category": "visual attributes", "name": "green grass"},
                {"category": "unknown", "name": "ignored"}
              ]
            }
        """.trimIndent()
        val fence = 96.toChar().toString().repeat(3)
        val parsed = AiResponseParser.parse(fence + "json\n" + json + "\n" + fence)

        assertEquals("A dog running on grass.", parsed.description)
        assertEquals(listOf("running"), parsed.tags["activities"])
        assertEquals(listOf("green grass"), parsed.tags["visual_attributes"])
        assertTrue("Unknown categories must be ignored.", parsed.tags.keys.none { it == "unknown" })
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsResponsesWithoutDescription() {
        AiResponseParser.parse("""{"tags":{"objects":["camera"]}}""")
    }
}
