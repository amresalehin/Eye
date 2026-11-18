package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.*
import org.junit.Assert.*
import org.junit.Test

class FavedFeaturesUnitTest {

    @Test
    fun testNestedTagHierarchyAndRollup() {
        val prog = FavedTag(id = "t_prog", title = "Programming Languages", colorHex = "#3B82F6", isPinned = true)
        val backend = FavedTag(id = "t_back", title = "Backend", parentId = "t_prog", colorHex = "#10B981")
        val kotlin = FavedTag(id = "t_kt", title = "Kotlin", parentId = "t_back", colorHex = "#8B5CF6")
        val go = FavedTag(id = "t_go", title = "Go", parentId = "t_back", colorHex = "#0EA5E9")
        val design = FavedTag(id = "t_des", title = "Design", colorHex = "#EC4899")
        val allTags = listOf(prog, backend, kotlin, go, design)

        assertEquals("Programming Languages → Backend → Kotlin", FavedTagTreeHelper.buildHierarchyPath("t_kt", allTags))
        assertEquals(setOf("t_prog", "t_back", "t_kt", "t_go"), FavedTagTreeHelper.getDescendantIds("t_prog", allTags))

        // Cycle prevention
        assertTrue(FavedTagTreeHelper.wouldCreateCycle("t_prog", "t_kt", allTags))
        assertTrue(FavedTagTreeHelper.wouldCreateCycle("t_prog", "t_prog", allTags))
        assertFalse(FavedTagTreeHelper.wouldCreateCycle("t_kt", "t_des", allTags))

        val bmKotlin = MediaInboxStack(
            id = "bm_1",
            title = "Kotlin Coroutines",
            sourceDomain = "kotlinlang.org",
            sourceUrl = "https://kotlinlang.org/docs/coroutines-overview.html",
            items = emptyList(),
            tagIds = listOf("t_kt")
        )
        val bmUntagged = MediaInboxStack(
            id = "bm_2",
            title = "Untagged Link",
            sourceDomain = "example.com",
            sourceUrl = "https://example.com",
            items = emptyList()
        )

        // With rollup enabled, filtering by parent tag matches child & grandchild bookmarks
        assertTrue(FavedTagTreeHelper.bookmarkMatchesTagFilter(bmKotlin, "t_prog", false, true, allTags))
        assertTrue(FavedTagTreeHelper.bookmarkMatchesTagFilter(bmKotlin, "t_back", false, true, allTags))
        // With rollup disabled, parent tag does not match grandchild bookmark
        assertFalse(FavedTagTreeHelper.bookmarkMatchesTagFilter(bmKotlin, "t_prog", false, false, allTags))
        assertTrue(FavedTagTreeHelper.bookmarkMatchesTagFilter(bmKotlin, "t_kt", false, false, allTags))
        // Untagged filter
        assertTrue(FavedTagTreeHelper.bookmarkMatchesTagFilter(bmUntagged, null, true, true, allTags))
        assertFalse(FavedTagTreeHelper.bookmarkMatchesTagFilter(bmKotlin, null, true, true, allTags))

        // Tree nodes preserve counts and ancestor visibility when searching
        val searchNodes = FavedTagTreeHelper.buildOrderedTreeNodes(allTags, listOf(bmKotlin, bmUntagged), searchQuery = "kotlin")
        assertEquals(listOf("t_prog", "t_back", "t_kt"), searchNodes.map { it.tag.id })
        assertEquals(1, searchNodes.first().rolledUpBookmarkCount)
        assertEquals(0, searchNodes.first().directBookmarkCount)
    }

    @Test
    fun testSmartLocalTagSuggestions() {
        val tags = listOf(
            FavedTag(id = "t1", title = "Kotlin", description = "Android JVM programming"),
            FavedTag(id = "t2", title = "Cooking", description = "Recipes and kitchen"),
            FavedTag(id = "t3", title = "Android", description = "Mobile app development")
        )
        val suggestions = FavedTagTreeHelper.suggestTagIdsForBookmark(
            url = "https://developer.android.com/kotlin/compose",
            title = "Jetpack Compose for Android",
            description = "Modern toolkit for Kotlin UI",
            allTags = tags
        )
        assertTrue(suggestions.any { it.id == "t1" })
        assertTrue(suggestions.any { it.id == "t3" })
        assertFalse(suggestions.any { it.id == "t2" })
    }

    @Test
    fun testNetscapeBrowserHtmlImportPreservesNestedFolders() {
        val html = """
            <!DOCTYPE NETSCAPE-Bookmark-file-1>
            <TITLE>Bookmarks</TITLE>
            <H1>Bookmarks</H1>
            <DL><p>
                <DT><H3>Bookmarks Bar</H3>
                <DL><p>
                    <DT><H3>Programming Languages</H3>
                    <DL><p>
                        <DT><H3>Backend</H3>
                        <DL><p>
                            <DT><A HREF="https://go.dev" ADD_DATE="1700000000" TAGS="fast,compiled">Go Language</A>
                            <DD>Official Go site
                        </DL><p>
                    </DL><p>
                </DL><p>
            </DL><p>
        """.trimIndent()

        val batch = BookmarkImportExportEngine.parseAuto(html, "chrome_bookmarks.html")
        assertEquals("Browser Bookmarks HTML", batch.formatName)
        assertEquals(1, batch.entries.size)
        val entry = batch.entries.first()
        assertEquals("https://go.dev", entry.url)
        assertEquals("Go Language", entry.title)
        assertEquals("Official Go site", entry.description)
        assertEquals(1700000000_000L, entry.timestampMillis)
        assertTrue(entry.tagPaths.contains(listOf("Programming Languages", "Backend")))
        assertTrue(entry.tagPaths.contains(listOf("fast")))
        assertTrue(entry.tagPaths.contains(listOf("compiled")))
    }

    @Test
    fun testRaindropAndPocketCsvImport() {
        val raindropCsv = """
            id,title,note,excerpt,url,folder,tags,created,cover,highlights,favorite
            1,"Compose Docs","Great reference","Official docs","https://developer.android.com/compose","Dev/Android","kotlin, ui",2026-01-15T10:00:00Z,"https://example.com/cover.png","",true
        """.trimIndent()

        val rdBatch = BookmarkImportExportEngine.parseAuto(raindropCsv, "raindrop.csv")
        assertEquals("Raindrop.io CSV", rdBatch.formatName)
        assertEquals(1, rdBatch.entries.size)
        val rd = rdBatch.entries.first()
        assertEquals("Compose Docs", rd.title)
        assertEquals("Great reference", rd.notes)
        assertEquals("Official docs", rd.description)
        assertTrue(rd.tagPaths.contains(listOf("Dev", "Android")))
        assertTrue(rd.tagPaths.contains(listOf("kotlin")))
        assertTrue(rd.tagPaths.contains(listOf("ui")))

        val pocketCsv = """
            title,url,time_added,tags,status
            "Rust Book","https://doc.rust-lang.org/book/",1700000100,"rust|systems",archive
        """.trimIndent()

        val pkBatch = BookmarkImportExportEngine.parseAuto(pocketCsv, "pocket_export.csv")
        assertEquals("Pocket CSV", pkBatch.formatName)
        assertEquals(1, pkBatch.entries.size)
        val pk = pkBatch.entries.first()
        assertTrue(pk.tagPaths.contains(listOf("rust")))
        assertTrue(pk.tagPaths.contains(listOf("systems")))
        assertTrue(pk.tagPaths.contains(listOf("Pocket Archive")))
    }

    @Test
    fun testExportHtmlCsvAndJsonRoundTrip() {
        val parent = FavedTag(id = "t_root", title = "Research", colorHex = "#3B82F6", isPinned = true)
        val child = FavedTag(id = "t_child", title = "Medicine", parentId = "t_root", colorHex = "#10B981")
        val tags = listOf(parent, child)
        val bm = MediaInboxStack(
            id = "bm_1",
            title = "PubMed Central",
            sourceDomain = "pmc.ncbi.nlm.nih.gov",
            sourceUrl = "https://pmc.ncbi.nlm.nih.gov/",
            items = emptyList(),
            description = "Biomedical literature",
            notes = "Key journals",
            tagIds = listOf("t_child")
        )

        val htmlOut = BookmarkImportExportEngine.exportToNetscapeHtml(listOf(bm), tags)
        val reparsedHtml = BookmarkImportExportEngine.parseNetscapeHtml(htmlOut)
        assertEquals(1, reparsedHtml.entries.size)
        assertTrue(reparsedHtml.entries.first().tagPaths.contains(listOf("Research", "Medicine")))

        val csvOut = BookmarkImportExportEngine.exportToCsv(listOf(bm), tags)
        assertTrue(csvOut.contains("Research/Medicine"))
        assertTrue(csvOut.contains("PubMed Central"))

        val jsonOut = BookmarkImportExportEngine.exportToJson(listOf(bm), tags)
        val reparsedJson = BookmarkImportExportEngine.parseJsonBackup(jsonOut)
        assertEquals(1, reparsedJson.entries.size)
        assertEquals(2, reparsedJson.importedTags.size)
        assertTrue(reparsedJson.entries.first().tagPaths.contains(listOf("Research", "Medicine")))
    }
}
