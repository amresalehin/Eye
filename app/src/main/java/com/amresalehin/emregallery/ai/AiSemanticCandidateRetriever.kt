package com.amresalehin.emregallery.ai

import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

/**
 * Local first-stage retrieval for semantic gallery search.
 *
 * Every completed AI analysis is considered. A weighted BM25-style score ranks records
 * across title, tags, collection, key details, and description before a bounded shortlist
 * is sent to the remote/local LLM for semantic reranking.
 */
internal object AiSemanticCandidateRetriever {
    const val DEFAULT_CANDIDATE_LIMIT = 100

    private const val K1 = 1.2
    private const val B = 0.75

    private val tokenRegex = Regex("[\\p{L}\\p{N}]+")
    private val stopWords = setOf(
        "a", "about", "all", "an", "and", "any", "are", "as", "at", "be", "by",
        "can", "could", "did", "do", "does", "find", "for", "from", "get", "give",
        "have", "how", "i", "in", "is", "it", "me", "my", "of", "on", "or", "please",
        "show", "some", "that", "the", "their", "them", "there", "this", "to", "was",
        "were", "what", "when", "where", "which", "who", "with", "would", "you",
        "photo", "photos", "picture", "pictures", "image", "images", "item", "items"
    )

    private data class FieldVector(
        val frequencies: Map<String, Int>,
        val length: Int,
        val weight: Double
    )

    private data class SearchDocument(
        val analysis: AiMediaAnalysis,
        val fields: List<FieldVector>,
        val terms: Set<String>,
        val normalizedFields: List<String>
    )

    /**
     * Searches the full supplied collection, then returns at most [limit] relevant records.
     * No score means no candidate; unrelated recent records are deliberately not sent to the LLM.
     */
    fun retrieve(
        query: String,
        analyses: List<AiMediaAnalysis>,
        limit: Int = DEFAULT_CANDIDATE_LIMIT
    ): List<AiMediaAnalysis> {
        if (analyses.isEmpty() || limit <= 0) return emptyList()

        val queryTokens = tokenize(query).filterNot { it in stopWords }.distinct()
        if (queryTokens.isEmpty()) return emptyList()

        val documents = analyses.map { analysis ->
            val fields = listOf(
                analysis.title to 3.0,
                analysis.tags.joinToString(" ") to 2.6,
                analysis.suggestedCollection to 1.8,
                analysis.keyDetails to 1.5,
                analysis.description to 1.0
            ).map { (text, weight) ->
                val tokens = tokenize(text)
                FieldVector(
                    frequencies = tokens.groupingBy { it }.eachCount(),
                    length = tokens.size,
                    weight = weight
                )
            }
            SearchDocument(
                analysis = analysis,
                fields = fields,
                terms = fields.flatMapTo(HashSet()) { it.frequencies.keys },
                normalizedFields = listOf(
                    normalizePhrase(analysis.title),
                    normalizePhrase(analysis.tags.joinToString(" ")),
                    normalizePhrase(analysis.suggestedCollection),
                    normalizePhrase(analysis.keyDetails),
                    normalizePhrase(analysis.description)
                )
            )
        }

        val documentCount = documents.size.toDouble()
        val documentFrequency = HashMap<String, Int>()
        val averageFieldLengths = DoubleArray(5)

        documents.forEach { document ->
            document.terms.forEach { term ->
                documentFrequency[term] = (documentFrequency[term] ?: 0) + 1
            }
            document.fields.forEachIndexed { index, field ->
                averageFieldLengths[index] += field.length
            }
        }
        for (index in averageFieldLengths.indices) {
            averageFieldLengths[index] =
                (averageFieldLengths[index] / documentCount).coerceAtLeast(1.0)
        }

        val normalizedQuery = normalizePhrase(query)
        val scored = documents.mapNotNull { document ->
            var score = 0.0

            queryTokens.forEach { term ->
                val df = documentFrequency[term] ?: 0
                if (df == 0) return@forEach
                val inverseDocumentFrequency =
                    ln(1.0 + (documentCount - df + 0.5) / (df + 0.5))

                document.fields.forEachIndexed { fieldIndex, field ->
                    val exactTf = field.frequencies[term] ?: 0
                    val prefixTf = if (exactTf == 0 && term.length >= 5) {
                        field.frequencies.entries
                            .filter { (candidate, _) ->
                                candidate.startsWith(term) || term.startsWith(candidate)
                            }
                            .sumOf { it.value }
                            .coerceAtMost(2)
                    } else {
                        0
                    }
                    val tf = exactTf + prefixTf * 0.35
                    if (tf <= 0.0) return@forEachIndexed

                    val lengthNorm = 1.0 - B +
                        B * field.length / averageFieldLengths[fieldIndex]
                    val bm25Tf = (tf * (K1 + 1.0)) / (tf + K1 * lengthNorm)
                    score += inverseDocumentFrequency * bm25Tf * field.weight
                }
            }

            if (normalizedQuery.length >= 4) {
                val phraseBoost = when {
                    document.normalizedFields[0].contains(normalizedQuery) -> 5.0
                    document.normalizedFields[1].contains(normalizedQuery) -> 4.0
                    document.normalizedFields[2].contains(normalizedQuery) -> 3.0
                    document.normalizedFields[3].contains(normalizedQuery) -> 2.5
                    document.normalizedFields[4].contains(normalizedQuery) -> 2.0
                    else -> 0.0
                }
                score += phraseBoost
            }

            if (score > 0.0) document to score else null
        }

        return scored
            .sortedWith(
                compareByDescending<Pair<SearchDocument, Double>> { it.second }
                    .thenByDescending { it.first.analysis.analyzedAtMillis }
                    .thenBy { it.first.analysis.mediaUri }
            )
            .take(limit)
            .map { it.first.analysis }
    }

    private fun tokenize(value: String): List<String> {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        return tokenRegex.findAll(normalized)
            .map { normalizeToken(it.value) }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun normalizeToken(raw: String): String {
        val token = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return when {
            token.length > 5 && token.endsWith("ies") -> token.dropLast(3) + "y"
            token.length > 5 && listOf("ches", "shes", "xes", "zes", "sses").any(token::endsWith) ->
                token.dropLast(2)
            token.length > 5 && token.endsWith("ing") -> collapseDoubleEnding(token.dropLast(3))
            token.length > 4 && token.endsWith("ed") -> collapseDoubleEnding(token.dropLast(2))
            token.length > 4 && token.endsWith("s") && !token.endsWith("ss") -> token.dropLast(1)
            else -> token
        }
    }

    private fun collapseDoubleEnding(value: String): String =
        if (value.length >= 2 && value.last() == value[value.lastIndex - 1]) {
            value.dropLast(1)
        } else {
            value
        }

    private fun normalizePhrase(value: String): String =
        tokenize(value).joinToString(" ")
}
