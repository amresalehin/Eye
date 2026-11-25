package com.amresalehin.emregallery.data

import java.util.Locale

/**
 * Canonical formatting for user-visible tags and AI-generated collection names.
 * Tags and collection names use Title Case with standard acronyms retained.
 */
object MetadataNameNormalizer {
    private val whitespace = Regex("\\s+")
    private val words = Regex("[\\p{L}\\p{N}]+")
    private val minorWords = setOf(
        "a", "an", "and", "as", "at", "but", "by", "for", "from",
        "in", "into", "nor", "of", "on", "onto", "or", "over",
        "per", "the", "to", "via", "with"
    )
    private val conventionalCasing = mapOf(
        "ai" to "AI", "api" to "API", "ar" to "AR", "aws" to "AWS",
        "dslr" to "DSLR", "eu" to "EU", "gps" to "GPS", "hdr" to "HDR",
        "html" to "HTML", "http" to "HTTP", "https" to "HTTPS", "id" to "ID",
        "ios" to "iOS", "ip" to "IP", "macos" to "macOS", "nasa" to "NASA",
        "nfc" to "NFC", "nlp" to "NLP", "nyc" to "NYC", "pdf" to "PDF",
        "qr" to "QR", "sql" to "SQL", "ui" to "UI", "url" to "URL",
        "usb" to "USB", "usa" to "USA", "uk" to "UK", "3d" to "3D",
        "4k" to "4K", "8k" to "8K", "chatgpt" to "ChatGPT", "ebay" to "eBay",
        "github" to "GitHub", "gitlab" to "GitLab", "iphone" to "iPhone",
        "ipad" to "iPad", "ipod" to "iPod", "javascript" to "JavaScript",
        "linkedin" to "LinkedIn", "mongodb" to "MongoDB", "openai" to "OpenAI",
        "paypal" to "PayPal", "postgresql" to "PostgreSQL", "sqlite" to "SQLite",
        "typescript" to "TypeScript", "whatsapp" to "WhatsApp", "youtube" to "YouTube"
    )

    fun normalizeTag(value: String): String =
        normalizeCollectionName(value.trim().trimStart('#'))

    fun normalizeTags(values: Collection<String>): List<String> {
        val unique = LinkedHashSet<String>()
        values.map(::normalizeTag).filter(String::isNotBlank).forEach(unique::add)
        return unique.toList()
    }

    fun normalizeCollectionName(value: String): String {
        val cleaned = value.trim().replace(whitespace, " ")
        if (cleaned.isBlank()) return ""
        var wordIndex = 0
        return words.replace(cleaned) { match ->
            val lower = match.value.lowercase(Locale.ROOT)
            val formatted = conventionalCasing[lower] ?: when {
                wordIndex > 0 && lower.length > 1 && lower in minorWords -> lower
                else -> lower.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
            wordIndex++
            formatted
        }
    }

    fun normalizeCollectionNames(values: Collection<String>): List<String> {
        val unique = LinkedHashMap<String, String>()
        values.map(::normalizeCollectionName).filter(String::isNotBlank).forEach { name ->
            val key = name.lowercase(Locale.ROOT)
            if (!unique.containsKey(key)) unique[key] = name
        }
        return unique.values.toList()
    }
}
