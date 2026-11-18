package com.amresalehin.emregallery.data

import java.util.Locale
import java.util.UUID

/**
 * Customizable nested tag model modeled after denho/faved.
 * Supports arbitrary parent-child nesting, custom colors, descriptions, and pinning.
 */
data class FavedTag(
    val id: String = "tag_${UUID.randomUUID()}",
    val title: String,
    val parentId: String? = null,
    val colorHex: String = FavedTagColorPalette.DEFAULT_HEX,
    val description: String = "",
    val isPinned: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis()
)

/**
 * Bookmark presentation layouts matching denho/faved's customizable views.
 */
enum class BookmarkLayoutMode(val label: String) {
    CARD("Card"),
    LIST("List"),
    TABLE("Table");

    companion object {
        fun fromName(name: String?): BookmarkLayoutMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: CARD
    }
}

/**
 * Customizable visible fields for bookmark items across layouts.
 */
data class BookmarkDisplayFields(
    val showPreview: Boolean = true,
    val showDescription: Boolean = true,
    val showTags: Boolean = true,
    val showDomain: Boolean = true,
    val showDate: Boolean = true
)

/**
 * Summary returned after importing bookmarks from browser HTML, Raindrop.io, Pocket, or JSON.
 */
data class BookmarkImportResult(
    val importedCount: Int = 0,
    val skippedDuplicates: Int = 0,
    val tagsCreated: Int = 0,
    val detectedFormat: String = "Unknown",
    val errorMessage: String? = null
)

/**
 * Flattened tree node for rendering hierarchical nested tags with indentation and counts.
 */
data class FavedTagTreeNode(
    val tag: FavedTag,
    val depth: Int,
    val fullPath: String,
    val hasChildren: Boolean,
    val directBookmarkCount: Int,
    val rolledUpBookmarkCount: Int
)

/**
 * Curated tag color palette inspired by denho/faved.
 */
object FavedTagColorPalette {
    const val DEFAULT_HEX = "#64748B"

    data class TagColorOption(val name: String, val hex: String)

    val options: List<TagColorOption> = listOf(
        TagColorOption("Slate", "#64748B"),
        TagColorOption("Red", "#EF4444"),
        TagColorOption("Orange", "#F97316"),
        TagColorOption("Amber", "#F59E0B"),
        TagColorOption("Emerald", "#10B981"),
        TagColorOption("Teal", "#14B8A6"),
        TagColorOption("Sky", "#0EA5E9"),
        TagColorOption("Blue", "#3B82F6"),
        TagColorOption("Indigo", "#6366F1"),
        TagColorOption("Violet", "#8B5CF6"),
        TagColorOption("Pink", "#EC4899"),
        TagColorOption("Rose", "#F43F5E")
    )

    fun parseColorLong(hex: String): Long {
        val cleaned = hex.trim().removePrefix("#")
        return when (cleaned.length) {
            6 -> ("FF$cleaned").toLongOrNull(16) ?: 0xFF64748BL
            8 -> cleaned.toLongOrNull(16) ?: 0xFF64748BL
            else -> 0xFF64748BL
        }
    }
}

/**
 * Pure hierarchical tag tree utilities:
 * - Ancestor/descendant traversal for tag rollup
 * - Cycle detection for safe reparenting
 * - Full breadcrumb path generation (e.g. "Programming Languages → Backend → Python")
 * - Local keyword-based tag suggestion for smart bookmark management
 */
object FavedTagTreeHelper {

    /**
     * Returns all descendant tag IDs of [rootTagId], optionally including [rootTagId] itself.
     */
    fun getDescendantIds(
        rootTagId: String,
        allTags: List<FavedTag>,
        includeSelf: Boolean = true
    ): Set<String> {
        val childrenByParent = allTags.groupBy { it.parentId }
        val result = LinkedHashSet<String>()
        if (includeSelf) result.add(rootTagId)

        val queue = ArrayDeque<String>()
        queue.add(rootTagId)
        val visited = HashSet<String>().apply { add(rootTagId) }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = childrenByParent[current].orEmpty()
            for (child in children) {
                if (visited.add(child.id)) {
                    result.add(child.id)
                    queue.add(child.id)
                }
            }
        }
        return result
    }

    /**
     * Returns true if setting [tagId]'s parent to [proposedParentId] would introduce a cycle.
     */
    fun wouldCreateCycle(
        tagId: String,
        proposedParentId: String?,
        allTags: List<FavedTag>
    ): Boolean {
        if (proposedParentId == null) return false
        if (tagId == proposedParentId) return true
        val descendants = getDescendantIds(tagId, allTags, includeSelf = true)
        return proposedParentId in descendants
    }

    /**
     * Builds the full hierarchical path for [tagId] from root to leaf, e.g. "Dev → Backend → Kotlin".
     */
    fun buildHierarchyPath(
        tagId: String,
        allTags: List<FavedTag>,
        separator: String = " → "
    ): String {
        val byId = allTags.associateBy { it.id }
        val segments = mutableListOf<String>()
        val visited = HashSet<String>()
        var current: FavedTag? = byId[tagId]
        while (current != null && visited.add(current.id)) {
            segments.add(current.title)
            current = current.parentId?.let { byId[it] }
        }
        return segments.asReversed().joinToString(separator)
    }

    /**
     * Returns the list of ancestor segments (root to leaf) for [tagId].
     */
    fun getPathSegments(tagId: String, allTags: List<FavedTag>): List<String> {
        val byId = allTags.associateBy { it.id }
        val segments = mutableListOf<String>()
        val visited = HashSet<String>()
        var current: FavedTag? = byId[tagId]
        while (current != null && visited.add(current.id)) {
            segments.add(current.title)
            current = current.parentId?.let { byId[it] }
        }
        return segments.asReversed()
    }

    /**
     * Builds a depth-first ordered list of [FavedTagTreeNode]s with direct and rolled-up bookmark counts.
     * Supports optional [searchQuery] filtering (keeps matching tags and their ancestors so tree context stays clear).
     */
    fun buildOrderedTreeNodes(
        allTags: List<FavedTag>,
        bookmarks: List<MediaInboxStack>,
        searchQuery: String = "",
        collapsedTagIds: Set<String> = emptySet()
    ): List<FavedTagTreeNode> {
        if (allTags.isEmpty()) return emptyList()
        val byId = allTags.associateBy { it.id }
        val childrenByParent = allTags
            .groupBy { tag ->
                // Treat dangling parent references as top-level roots
                if (tag.parentId != null && byId.containsKey(tag.parentId) && tag.parentId != tag.id) {
                    tag.parentId
                } else {
                    null
                }
            }
            .mapValues { (_, list) ->
                list.sortedWith(
                    compareByDescending<FavedTag> { it.isPinned }
                        .thenBy { it.title.lowercase(Locale.ROOT) }
                )
            }

        // Precompute direct and rolled-up counts
        val directCounts = HashMap<String, Int>()
        bookmarks.forEach { bm ->
            bm.tagIds.distinct().forEach { tid ->
                directCounts[tid] = (directCounts[tid] ?: 0) + 1
            }
        }

        val rolledUpCounts = HashMap<String, Int>()
        for (tag in allTags) {
            val descendantIds = getDescendantIds(tag.id, allTags, includeSelf = true)
            rolledUpCounts[tag.id] = bookmarks.count { bm -> bm.tagIds.any { it in descendantIds } }
        }

        val query = searchQuery.trim().lowercase(Locale.ROOT)
        val allowedIds: Set<String>? = if (query.isBlank()) {
            null
        } else {
            val matching = HashSet<String>()
            for (tag in allTags) {
                val matches = tag.title.lowercase(Locale.ROOT).contains(query) ||
                    tag.description.lowercase(Locale.ROOT).contains(query)
                if (matches) {
                    matching.add(tag.id)
                    // Include ancestors so hierarchy stays readable
                    var parentId = tag.parentId
                    val guard = HashSet<String>()
                    while (parentId != null && guard.add(parentId)) {
                        matching.add(parentId)
                        parentId = byId[parentId]?.parentId
                    }
                }
            }
            matching
        }

        val result = mutableListOf<FavedTagTreeNode>()
        val visited = HashSet<String>()

        fun visit(parentId: String?, depth: Int) {
            val children = childrenByParent[parentId].orEmpty()
            for (tag in children) {
                if (!visited.add(tag.id)) continue
                if (allowedIds != null && tag.id !in allowedIds) continue
                val subChildren = childrenByParent[tag.id].orEmpty()
                val hasVisibleChildren = if (allowedIds == null) {
                    subChildren.isNotEmpty()
                } else {
                    subChildren.any { it.id in allowedIds }
                }
                result.add(
                    FavedTagTreeNode(
                        tag = tag,
                        depth = depth,
                        fullPath = buildHierarchyPath(tag.id, allTags),
                        hasChildren = hasVisibleChildren,
                        directBookmarkCount = directCounts[tag.id] ?: 0,
                        rolledUpBookmarkCount = rolledUpCounts[tag.id] ?: 0
                    )
                )
                if (hasVisibleChildren && (query.isNotBlank() || tag.id !in collapsedTagIds)) {
                    visit(tag.id, depth + 1)
                }
            }
        }

        visit(null, 0)
        return result
    }

    /**
     * Checks whether [bookmark] matches the active tag filter (supports untagged-only and optional child tag rollup).
     */
    fun bookmarkMatchesTagFilter(
        bookmark: MediaInboxStack,
        selectedTagId: String?,
        untaggedOnly: Boolean,
        rollupEnabled: Boolean,
        allTags: List<FavedTag>
    ): Boolean {
        if (untaggedOnly) {
            return bookmark.tagIds.isEmpty()
        }
        if (selectedTagId.isNullOrBlank()) {
            return true
        }
        if (bookmark.tagIds.isEmpty()) return false
        return if (rollupEnabled) {
            val allowed = getDescendantIds(selectedTagId, allTags, includeSelf = true)
            bookmark.tagIds.any { it in allowed }
        } else {
            selectedTagId in bookmark.tagIds
        }
    }

    /**
     * Suggests up to [maxSuggestions] existing tags that match keywords in the bookmark's URL,
     * domain, title, or description. Pure local heuristic (zero network calls).
     */
    fun suggestTagIdsForBookmark(
        url: String,
        title: String,
        description: String,
        allTags: List<FavedTag>,
        alreadyAssigned: Set<String> = emptySet(),
        maxSuggestions: Int = 5
    ): List<FavedTag> {
        if (allTags.isEmpty()) return emptyList()
        val haystack = "$url $title $description".lowercase(Locale.ROOT)
        if (haystack.isBlank()) return emptyList()

        val tokens = haystack
            .split(Regex("""[^a-z0-9]+"""))
            .filter { it.length >= 2 }
            .toSet()

        return allTags
            .asSequence()
            .filter { it.id !in alreadyAssigned }
            .mapNotNull { tag ->
                val tagLower = tag.title.trim().lowercase(Locale.ROOT)
                if (tagLower.length < 2) return@mapNotNull null
                var score = 0
                if (tagLower in tokens) score += 4
                else if (haystack.contains(tagLower)) score += 2
                val descTokens = tag.description.lowercase(Locale.ROOT)
                    .split(Regex("""[^a-z0-9]+"""))
                    .filter { it.length >= 4 }
                score += descTokens.count { it in tokens }
                if (score > 0) tag to score else null
            }
            .sortedByDescending { it.second }
            .take(maxSuggestions)
            .map { it.first }
            .toList()
    }
}
