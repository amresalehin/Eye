package com.amresalehin.emregallery.data

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Pure import/export engine implementing denho/faved's migration capabilities:
 * - Browser Bookmarks (Netscape HTML from Chrome, Safari, Firefox, Edge) preserving
 *   original folder hierarchy as nested tags.
 * - Raindrop.io CSV export (preserving nested folder paths, tags, notes, excerpts, timestamps).
 * - Pocket CSV and HTML exports (preserving tags and timestamps).
 * - Full-fidelity JSON backup/restore (bookmarks + nested tags + colors + pins + descriptions).
 * - Export back to Netscape Bookmarks HTML, CSV, and JSON.
 */
object BookmarkImportExportEngine {

    data class ParsedBookmarkEntry(
        val url: String,
        val title: String,
        val description: String = "",
        val notes: String = "",
        val previewImageUrl: String = "",
        val timestampMillis: Long = System.currentTimeMillis(),
        val tagPaths: List<List<String>> = emptyList()
    )

    data class ParsedImportBatch(
        val formatName: String,
        val entries: List<ParsedBookmarkEntry>,
        val importedTags: List<FavedTag> = emptyList()
    )

    /**
     * Auto-detects the bookmark file format and parses all entries and tag hierarchies.
     */
    fun parseAuto(content: String, fileName: String = ""): ParsedImportBatch {
        val trimmed = content.trim()
        if (trimmed.isBlank()) {
            return ParsedImportBatch("Empty", emptyList())
        }

        val lowerName = fileName.lowercase(Locale.ROOT)
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || lowerName.endsWith(".json")) {
            return parseJsonBackup(trimmed)
        }

        if (trimmed.contains("<!DOCTYPE NETSCAPE-Bookmark-file-1", ignoreCase = true) ||
            trimmed.contains("<DL>", ignoreCase = true) ||
            trimmed.contains("<A HREF=", ignoreCase = true) ||
            lowerName.endsWith(".html") || lowerName.endsWith(".htm")
        ) {
            return parseNetscapeHtml(trimmed)
        }

        // CSV detection: inspect header line
        val headerLine = trimmed.lineSequence().firstOrNull { it.isNotBlank() }?.lowercase(Locale.ROOT).orEmpty()
        return when {
            headerLine.contains("folder") && headerLine.contains("url") -> parseRaindropCsv(trimmed)
            headerLine.contains("time_added") || (headerLine.contains("url") && headerLine.contains("tags")) -> parsePocketCsv(trimmed)
            else -> parseGenericCsv(trimmed)
        }
    }

    /**
     * Parses Netscape Bookmark HTML (Chrome, Safari, Firefox, Edge, Pocket HTML).
     * Preserves nested `<H3>` folder hierarchy as nested tag paths (e.g. ["Bookmarks Bar", "Dev", "Kotlin"])
     * and also reads Pocket/Raindrop `TAGS="a,b"` attributes on `<A>` elements.
     */
    fun parseNetscapeHtml(html: String): ParsedImportBatch {
        val doc = Jsoup.parse(html)
        val entries = mutableListOf<ParsedBookmarkEntry>()

        // Walk the DOM recursively tracking active folder path per <DL>
        fun walkDl(dl: Element, currentPath: List<String>) {
            val children = dl.children()
            var pendingFolderName: String? = null
            for (child in children) {
                when (child.tagName().lowercase(Locale.ROOT)) {
                    "dt" -> {
                        val h3 = child.selectFirst("> h3")
                        val anchor = child.selectFirst("> a[href]")
                        val nestedDl = child.selectFirst("> dl")

                        if (h3 != null) {
                            val folderName = h3.text().trim()
                            val nextPath = if (folderName.isNotBlank() && !isBoilerplateRootFolder(folderName)) {
                                currentPath + folderName
                            } else {
                                currentPath
                            }
                            if (nestedDl != null) {
                                walkDl(nestedDl, nextPath)
                                pendingFolderName = null
                            } else {
                                pendingFolderName = if (folderName.isNotBlank() && !isBoilerplateRootFolder(folderName)) {
                                    folderName
                                } else {
                                    null
                                }
                            }
                        } else if (anchor != null) {
                            pendingFolderName = null
                            val href = anchor.attr("href").trim()
                            if (isImportableUrl(href)) {
                                val title = anchor.text().trim().ifBlank { MediaClassifier.cleanDomainFromUrl(href) }
                                val addDateSec = anchor.attr("add_date").trim().toLongOrNull()
                                val timestampMs = normalizeEpochMillis(addDateSec)
                                val iconAttr = anchor.attr("icon_uri").trim()
                                val attrTags = anchor.attr("tags").trim()
                                    .split(",")
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }

                                // Check sibling <DD> description if present
                                val nextSibling = child.nextElementSibling()
                                val ddDesc = if (nextSibling?.tagName()?.equals("dd", ignoreCase = true) == true) {
                                    nextSibling.text().trim()
                                } else ""

                                val tagPaths = mutableListOf<List<String>>()
                                if (currentPath.isNotEmpty()) {
                                    tagPaths.add(currentPath)
                                }
                                attrTags.forEach { t ->
                                    val splitPath = t.split("/", "→", ">").map { it.trim() }.filter { it.isNotBlank() }
                                    if (splitPath.isNotEmpty()) tagPaths.add(splitPath)
                                }

                                entries.add(
                                    ParsedBookmarkEntry(
                                        url = href,
                                        title = title,
                                        description = ddDesc,
                                        previewImageUrl = if (iconAttr.startsWith("http", ignoreCase = true)) iconAttr else "",
                                        timestampMillis = timestampMs,
                                        tagPaths = tagPaths.distinct()
                                    )
                                )
                            }
                        } else if (nestedDl != null) {
                            val nextPath = if (pendingFolderName != null) currentPath + pendingFolderName else currentPath
                            pendingFolderName = null
                            walkDl(nestedDl, nextPath)
                        }
                    }
                    "dl" -> {
                        val nextPath = if (pendingFolderName != null) currentPath + pendingFolderName else currentPath
                        pendingFolderName = null
                        walkDl(child, nextPath)
                    }
                    "h3" -> {
                        val folderName = child.text().trim()
                        pendingFolderName = if (folderName.isNotBlank() && !isBoilerplateRootFolder(folderName)) {
                            folderName
                        } else {
                            null
                        }
                    }
                }
            }
        }

        val rootDls = doc.select("body > dl")
        if (rootDls.isNotEmpty()) {
            rootDls.forEach { walkDl(it, emptyList()) }
        } else {
            val anyDl = doc.selectFirst("dl")
            if (anyDl != null) {
                walkDl(anyDl, emptyList())
            } else {
                // Fallback: flat <a href> links
                doc.select("a[href]").forEach { a ->
                    val href = a.attr("href").trim()
                    if (isImportableUrl(href)) {
                        val attrTags = a.attr("tags").split(",").map { it.trim() }.filter { it.isNotBlank() }
                        entries.add(
                            ParsedBookmarkEntry(
                                url = href,
                                title = a.text().trim().ifBlank { MediaClassifier.cleanDomainFromUrl(href) },
                                timestampMillis = normalizeEpochMillis(a.attr("add_date").trim().toLongOrNull()),
                                tagPaths = attrTags.map { listOf(it) }
                            )
                        )
                    }
                }
            }
        }

        return ParsedImportBatch(
            formatName = "Browser Bookmarks HTML",
            entries = entries
        )
    }

    /**
     * Parses Raindrop.io CSV export:
     * Columns: id,title,note,excerpt,url,folder,tags,created,cover,highlights,favorite
     */
    fun parseRaindropCsv(csv: String): ParsedImportBatch {
        val rows = parseCsvRows(csv)
        if (rows.size < 2) return ParsedImportBatch("Raindrop.io CSV", emptyList())

        val headers = rows.first().map { it.trim().lowercase(Locale.ROOT) }
        val urlIdx = headers.indexOf("url")
        if (urlIdx < 0) return ParsedImportBatch("Raindrop.io CSV", emptyList())

        val titleIdx = headers.indexOf("title")
        val noteIdx = headers.indexOf("note")
        val excerptIdx = headers.indexOf("excerpt")
        val folderIdx = headers.indexOf("folder")
        val tagsIdx = headers.indexOf("tags")
        val createdIdx = headers.indexOf("created")
        val coverIdx = headers.indexOf("cover")

        val entries = mutableListOf<ParsedBookmarkEntry>()
        for (row in rows.drop(1)) {
            val url = row.getOrNull(urlIdx)?.trim().orEmpty()
            if (!isImportableUrl(url)) continue

            val title = row.getOrNull(titleIdx)?.trim().orEmpty()
                .ifBlank { MediaClassifier.cleanDomainFromUrl(url) }
            val note = row.getOrNull(noteIdx)?.trim().orEmpty()
            val excerpt = row.getOrNull(excerptIdx)?.trim().orEmpty()
            val folder = row.getOrNull(folderIdx)?.trim().orEmpty()
            val tagsRaw = row.getOrNull(tagsIdx)?.trim().orEmpty()
            val createdRaw = row.getOrNull(createdIdx)?.trim().orEmpty()
            val cover = row.getOrNull(coverIdx)?.trim().orEmpty()

            val tagPaths = mutableListOf<List<String>>()
            if (folder.isNotBlank() && !folder.equals("Unsorted", ignoreCase = true)) {
                val folderPath = folder.split("/").map { it.trim() }.filter { it.isNotBlank() }
                if (folderPath.isNotEmpty()) tagPaths.add(folderPath)
            }
            if (tagsRaw.isNotBlank()) {
                tagsRaw.split(",", "|").map { it.trim() }.filter { it.isNotBlank() }.forEach { tag ->
                    val segs = tag.split("/").map { it.trim() }.filter { it.isNotBlank() }
                    if (segs.isNotEmpty()) tagPaths.add(segs)
                }
            }

            entries.add(
                ParsedBookmarkEntry(
                    url = url,
                    title = title,
                    description = excerpt,
                    notes = note,
                    previewImageUrl = if (cover.startsWith("http", ignoreCase = true)) cover else "",
                    timestampMillis = parseIsoOrEpochMillis(createdRaw),
                    tagPaths = tagPaths.distinct()
                )
            )
        }

        return ParsedImportBatch("Raindrop.io CSV", entries)
    }

    /**
     * Parses Pocket CSV export:
     * Columns: title,url,time_added,tags,status
     */
    fun parsePocketCsv(csv: String): ParsedImportBatch {
        val rows = parseCsvRows(csv)
        if (rows.size < 2) return ParsedImportBatch("Pocket CSV", emptyList())

        val headers = rows.first().map { it.trim().lowercase(Locale.ROOT) }
        val urlIdx = headers.indexOf("url")
        if (urlIdx < 0) return ParsedImportBatch("Pocket CSV", emptyList())

        val titleIdx = headers.indexOf("title")
        val timeIdx = headers.indexOf("time_added")
        val tagsIdx = headers.indexOf("tags")
        val statusIdx = headers.indexOf("status")

        val entries = mutableListOf<ParsedBookmarkEntry>()
        for (row in rows.drop(1)) {
            val url = row.getOrNull(urlIdx)?.trim().orEmpty()
            if (!isImportableUrl(url)) continue

            val title = row.getOrNull(titleIdx)?.trim().orEmpty()
                .ifBlank { MediaClassifier.cleanDomainFromUrl(url) }
            val timeRaw = row.getOrNull(timeIdx)?.trim().orEmpty()
            val tagsRaw = row.getOrNull(tagsIdx)?.trim().orEmpty()
            val status = row.getOrNull(statusIdx)?.trim().orEmpty()

            val tagPaths = mutableListOf<List<String>>()
            if (tagsRaw.isNotBlank()) {
                tagsRaw.split("|", ",").map { it.trim() }.filter { it.isNotBlank() }.forEach { t ->
                    tagPaths.add(listOf(t))
                }
            }
            if (status.equals("archive", ignoreCase = true)) {
                tagPaths.add(listOf("Pocket Archive"))
            }

            entries.add(
                ParsedBookmarkEntry(
                    url = url,
                    title = title,
                    timestampMillis = parseIsoOrEpochMillis(timeRaw),
                    tagPaths = tagPaths.distinct()
                )
            )
        }

        return ParsedImportBatch("Pocket CSV", entries)
    }

    private fun parseGenericCsv(csv: String): ParsedImportBatch {
        val rows = parseCsvRows(csv)
        if (rows.isEmpty()) return ParsedImportBatch("CSV", emptyList())

        val first = rows.first().map { it.trim().lowercase(Locale.ROOT) }
        val hasHeader = first.any { it in listOf("url", "link", "href", "title") }
        val urlIdx = if (hasHeader) {
            first.indexOfFirst { it in listOf("url", "link", "href") }.takeIf { it >= 0 } ?: 0
        } else 0
        val titleIdx = if (hasHeader) first.indexOfFirst { it in listOf("title", "name") } else -1
        val descIdx = if (hasHeader) first.indexOfFirst { it in listOf("description", "note", "notes", "excerpt") } else -1
        val tagsIdx = if (hasHeader) first.indexOfFirst { it in listOf("tags", "tag", "folder", "category") } else -1

        val dataRows = if (hasHeader) rows.drop(1) else rows
        val entries = dataRows.mapNotNull { row ->
            val url = row.getOrNull(urlIdx)?.trim().orEmpty()
            if (!isImportableUrl(url)) return@mapNotNull null
            val title = row.getOrNull(titleIdx)?.trim().orEmpty().ifBlank { MediaClassifier.cleanDomainFromUrl(url) }
            val desc = row.getOrNull(descIdx)?.trim().orEmpty()
            val tagsRaw = row.getOrNull(tagsIdx)?.trim().orEmpty()
            val tagPaths = tagsRaw.split(",", "|", ";")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { seg -> seg.split("/", "→").map { it.trim() }.filter { it.isNotBlank() } }
                .filter { it.isNotEmpty() }

            ParsedBookmarkEntry(
                url = url,
                title = title,
                description = desc,
                tagPaths = tagPaths
            )
        }
        return ParsedImportBatch("CSV", entries)
    }

    /**
     * Parses Faved / Emre Gallery JSON backup format.
     */
    fun parseJsonBackup(rawJson: String): ParsedImportBatch {
        val entries = mutableListOf<ParsedBookmarkEntry>()
        val importedTags = mutableListOf<FavedTag>()
        try {
            if (rawJson.startsWith("{")) {
                val root = JSONObject(rawJson)
                val tagsArr = root.optJSONArray("tags")
                val tagMap = mutableMapOf<String, FavedTag>()
                if (tagsArr != null) {
                    for (i in 0 until tagsArr.length()) {
                        val tObj = tagsArr.optJSONObject(i) ?: continue
                        val id = tObj.optString("id").ifBlank { "tag_${UUID.randomUUID()}" }
                        val title = tObj.optString("title").trim()
                        if (title.isBlank()) continue
                        val tag = FavedTag(
                            id = id,
                            title = title,
                            parentId = tObj.optString("parentId").takeIf { it.isNotBlank() && it != "null" },
                            colorHex = tObj.optString("colorHex", FavedTagColorPalette.DEFAULT_HEX),
                            description = tObj.optString("description", ""),
                            isPinned = tObj.optBoolean("isPinned", false),
                            createdAtMillis = tObj.optLong("createdAtMillis", System.currentTimeMillis())
                        )
                        importedTags.add(tag)
                        tagMap[id] = tag
                    }
                }

                val bmArr = root.optJSONArray("bookmarks") ?: root.optJSONArray("items") ?: JSONArray()
                for (i in 0 until bmArr.length()) {
                    val obj = bmArr.optJSONObject(i) ?: continue
                    val url = obj.optString("sourceUrl").ifBlank { obj.optString("url") }.trim()
                    if (!isImportableUrl(url)) continue

                    val tagPaths = mutableListOf<List<String>>()
                    val tagIdsArr = obj.optJSONArray("tagIds")
                    if (tagIdsArr != null) {
                        for (j in 0 until tagIdsArr.length()) {
                            val tid = tagIdsArr.optString(j)
                            if (tid.isNotBlank() && tagMap.containsKey(tid)) {
                                val segs = FavedTagTreeHelper.getPathSegments(tid, importedTags)
                                if (segs.isNotEmpty()) tagPaths.add(segs)
                            }
                        }
                    }
                    val tagNamesArr = obj.optJSONArray("tags")
                    if (tagNamesArr != null) {
                        for (j in 0 until tagNamesArr.length()) {
                            val tName = tagNamesArr.optString(j).trim()
                            if (tName.isNotBlank()) {
                                tagPaths.add(tName.split("/", "→").map { it.trim() }.filter { it.isNotBlank() })
                            }
                        }
                    }

                    entries.add(
                        ParsedBookmarkEntry(
                            url = url,
                            title = obj.optString("title").ifBlank { MediaClassifier.cleanDomainFromUrl(url) },
                            description = obj.optString("description", ""),
                            notes = obj.optString("notes", ""),
                            previewImageUrl = obj.optString("previewImageUrl", ""),
                            timestampMillis = obj.optLong("timestampMillis", System.currentTimeMillis()),
                            tagPaths = tagPaths.distinct()
                        )
                    )
                }
            } else if (rawJson.startsWith("[")) {
                val arr = JSONArray(rawJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val url = obj.optString("sourceUrl").ifBlank { obj.optString("url") }.trim()
                    if (!isImportableUrl(url)) continue
                    entries.add(
                        ParsedBookmarkEntry(
                            url = url,
                            title = obj.optString("title").ifBlank { MediaClassifier.cleanDomainFromUrl(url) },
                            description = obj.optString("description", ""),
                            notes = obj.optString("notes", ""),
                            previewImageUrl = obj.optString("previewImageUrl", ""),
                            timestampMillis = obj.optLong("timestampMillis", System.currentTimeMillis())
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Return whatever was parsed cleanly
        }
        return ParsedImportBatch("JSON Backup", entries, importedTags)
    }

    /**
     * Exports bookmarks and nested tag hierarchy to Netscape Bookmarks HTML
     * (compatible with Chrome, Firefox, Safari, Edge, Raindrop.io, and denho/faved).
     */
    fun exportToNetscapeHtml(
        bookmarks: List<MediaInboxStack>,
        tags: List<FavedTag>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("<!DOCTYPE NETSCAPE-Bookmark-file-1>")
        sb.appendLine("<!-- Exported by Emre Gallery (Faved-compatible) -->")
        sb.appendLine("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
        sb.appendLine("<TITLE>Bookmarks</TITLE>")
        sb.appendLine("<H1>Bookmarks</H1>")
        sb.appendLine("<DL><p>")

        val tagsById = tags.associateBy { it.id }
        val childrenByParent = tags.groupBy { tag ->
            if (tag.parentId != null && tagsById.containsKey(tag.parentId) && tag.parentId != tag.id) tag.parentId else null
        }

        fun formatAnchor(bm: MediaInboxStack, indent: String): String {
            val addDate = (bm.timestampMillis / 1000L).coerceAtLeast(0L)
            val tagNames = bm.tagIds.mapNotNull { tagsById[it]?.title }.joinToString(",")
            val tagsAttr = if (tagNames.isNotBlank()) " TAGS=\"${escapeHtml(tagNames)}\"" else ""
            val line = "$indent<DT><A HREF=\"${escapeHtml(bm.sourceUrl)}\" ADD_DATE=\"$addDate\"$tagsAttr>${escapeHtml(bm.title)}</A>"
            val desc = bm.notes.ifBlank { bm.description }
            return if (desc.isNotBlank()) "$line\n$indent<DD>${escapeHtml(desc)}" else line
        }

        val exportedIds = HashSet<String>()
        val visitedTags = HashSet<String>()

        fun writeTagFolder(tag: FavedTag, indent: String) {
            if (!visitedTags.add(tag.id)) return
            val addDate = (tag.createdAtMillis / 1000L).coerceAtLeast(0L)
            sb.appendLine("$indent<DT><H3 ADD_DATE=\"$addDate\">${escapeHtml(tag.title)}</H3>")
            sb.appendLine("$indent<DL><p>")

            // Bookmarks directly assigned to this tag
            bookmarks.filter { tag.id in it.tagIds }.forEach { bm ->
                sb.appendLine(formatAnchor(bm, "$indent    "))
                exportedIds.add(bm.id)
            }

            // Recurse into nested child tags
            childrenByParent[tag.id].orEmpty()
                .sortedBy { it.title.lowercase(Locale.ROOT) }
                .forEach { child -> writeTagFolder(child, "$indent    ") }

            sb.appendLine("$indent</DL><p>")
        }

        childrenByParent[null].orEmpty()
            .sortedBy { it.title.lowercase(Locale.ROOT) }
            .forEach { rootTag -> writeTagFolder(rootTag, "    ") }

        // Untagged bookmarks at root level
        bookmarks.filter { it.id !in exportedIds }.forEach { bm ->
            sb.appendLine(formatAnchor(bm, "    "))
        }

        sb.appendLine("</DL><p>")
        return sb.toString()
    }

    /**
     * Exports bookmarks to Raindrop.io / spreadsheet-compatible CSV.
     */
    fun exportToCsv(
        bookmarks: List<MediaInboxStack>,
        tags: List<FavedTag>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("title,url,description,notes,folder,tags,created")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        bookmarks.forEach { bm ->
            val primaryPath = bm.tagIds.firstOrNull()?.let { tid ->
                FavedTagTreeHelper.getPathSegments(tid, tags).joinToString("/")
            }.orEmpty()
            val allTagNames = bm.tagIds.mapNotNull { tid ->
                tags.firstOrNull { it.id == tid }?.title
            }.joinToString(", ")
            val created = iso.format(Date(bm.timestampMillis))
            sb.appendLine(
                listOf(
                    csvEscape(bm.title),
                    csvEscape(bm.sourceUrl),
                    csvEscape(bm.description),
                    csvEscape(bm.notes),
                    csvEscape(primaryPath),
                    csvEscape(allTagNames),
                    csvEscape(created)
                ).joinToString(",")
            )
        }
        return sb.toString()
    }

    /**
     * Exports full-fidelity JSON backup containing both nested tags and bookmarks.
     */
    fun exportToJson(
        bookmarks: List<MediaInboxStack>,
        tags: List<FavedTag>
    ): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAtMillis", System.currentTimeMillis())

        val tagsArr = JSONArray()
        tags.forEach { tag ->
            tagsArr.put(
                JSONObject().apply {
                    put("id", tag.id)
                    put("title", tag.title)
                    put("parentId", tag.parentId ?: JSONObject.NULL)
                    put("colorHex", tag.colorHex)
                    put("description", tag.description)
                    put("isPinned", tag.isPinned)
                    put("createdAtMillis", tag.createdAtMillis)
                }
            )
        }
        root.put("tags", tagsArr)

        val bmArr = JSONArray()
        bookmarks.forEach { bm ->
            bmArr.put(
                JSONObject().apply {
                    put("id", bm.id)
                    put("title", bm.title)
                    put("sourceDomain", bm.sourceDomain)
                    put("sourceUrl", bm.sourceUrl)
                    put("timestampMillis", bm.timestampMillis)
                    put("description", bm.description)
                    put("notes", bm.notes)
                    put("previewImageUrl", bm.previewImageUrl)
                    put("tagIds", JSONArray(bm.tagIds))
                }
            )
        }
        root.put("bookmarks", bmArr)
        return root.toString(2)
    }

    /**
     * RFC 4180 compliant CSV parser handling quoted fields, embedded commas, and escaped quotes.
     */
    internal fun parseCsvRows(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val len = csv.length

        while (i < len) {
            val c = csv[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && csv[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        currentRow.add(field.toString())
                        field.setLength(0)
                    }
                    '\r' -> {
                        // ignore CR; LF will finish the row
                    }
                    '\n' -> {
                        currentRow.add(field.toString())
                        field.setLength(0)
                        if (currentRow.any { it.isNotBlank() }) {
                            rows.add(currentRow.toList())
                        }
                        currentRow.clear()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(field.toString())
            if (currentRow.any { it.isNotBlank() }) {
                rows.add(currentRow.toList())
            }
        }
        return rows
    }

    private fun isImportableUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
    }

    private fun isBoilerplateRootFolder(name: String): Boolean {
        val lower = name.trim().lowercase(Locale.ROOT)
        return lower in setOf(
            "bookmarks",
            "bookmarks bar",
            "bookmarks toolbar",
            "bookmarks menu",
            "other bookmarks",
            "mobile bookmarks",
            "favorites bar",
            "favorites"
        )
    }

    private fun normalizeEpochMillis(raw: Long?): Long {
        if (raw == null || raw <= 0L) return System.currentTimeMillis()
        return when {
            raw > 1_000_000_000_000_000L -> raw / 1000L // microseconds
            raw > 100_000_000_000L -> raw // milliseconds
            else -> raw * 1000L // seconds
        }
    }

    private fun parseIsoOrEpochMillis(raw: String): Long {
        if (raw.isBlank()) return System.currentTimeMillis()
        raw.toLongOrNull()?.let { return normalizeEpochMillis(it) }
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (pattern in formats) {
            runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                sdf.parse(raw)?.time
            }.getOrNull()?.let { return it }
        }
        return System.currentTimeMillis()
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun csvEscape(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        return if (needsQuotes) "\"${value.replace("\"", "\"\"")}\"" else value
    }
}
