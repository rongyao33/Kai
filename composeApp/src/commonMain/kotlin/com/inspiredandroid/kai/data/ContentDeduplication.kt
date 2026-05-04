package com.inspiredandroid.kai.data

object ContentDeduplication {

    private const val SIMILARITY_THRESHOLD = 0.8
    private const val MIN_CONTENT_LENGTH = 10

    data class DeduplicationResult(
        val isDuplicate: Boolean,
        val duplicateSource: String? = null,
        val similarity: Double = 0.0,
        val suggestion: String? = null,
    )

    fun checkForDuplicate(
        newContent: String,
        soulText: String,
        customSections: Map<String, EditableSection>,
        memoryContents: List<String> = emptyList(),
    ): DeduplicationResult {
        if (newContent.length < MIN_CONTENT_LENGTH) {
            return DeduplicationResult(isDuplicate = false)
        }

        val normalized = normalize(newContent)

        // Check Soul
        if (soulText.isNotBlank()) {
            val soulSimilarity = similarity(normalized, normalize(soulText))
            if (soulSimilarity >= SIMILARITY_THRESHOLD) {
                return DeduplicationResult(
                    isDuplicate = true,
                    duplicateSource = "Soul",
                    similarity = soulSimilarity,
                    suggestion = "Content already exists in Soul. Consider using memory_reinforce instead, or promote_learning is not needed."
                )
            }
            if (soulText.contains(newContent, ignoreCase = true)) {
                return DeduplicationResult(
                    isDuplicate = true,
                    duplicateSource = "Soul",
                    similarity = 1.0,
                    suggestion = "Exact content already exists in Soul."
                )
            }
        }

        // Check Custom Sections
        for ((id, section) in customSections) {
            val sectionSimilarity = similarity(normalized, normalize(section.content))
            if (sectionSimilarity >= SIMILARITY_THRESHOLD) {
                return DeduplicationResult(
                    isDuplicate = true,
                    duplicateSource = "section:$id (${section.name})",
                    similarity = sectionSimilarity,
                    suggestion = "Similar content exists in section '${section.name}'. Update the existing section or use a different approach."
                )
            }
            if (section.content.contains(newContent, ignoreCase = true)) {
                return DeduplicationResult(
                    isDuplicate = true,
                    duplicateSource = "section:$id (${section.name})",
                    similarity = 1.0,
                    suggestion = "Exact content already exists in section '${section.name}'."
                )
            }
        }

        // Check Memory contents
        for (memory in memoryContents) {
            val memorySimilarity = similarity(normalized, normalize(memory))
            if (memorySimilarity >= SIMILARITY_THRESHOLD) {
                return DeduplicationResult(
                    isDuplicate = true,
                    duplicateSource = "Memory",
                    similarity = memorySimilarity,
                    suggestion = "Similar content exists in memories. Use memory_reinforce to strengthen the existing memory."
                )
            }
        }

        return DeduplicationResult(isDuplicate = false)
    }

    fun normalize(text: String): String {
        return text
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\n\\r\\t]+"), " ")
            .trim()
    }

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0

        val longer = if (a.length > b.length) a else b
        val shorter = if (a.length > b.length) b else a

        val editDistance = levenshteinDistance(longer, shorter)
        return (longer.length - editDistance) / longer.length.toDouble()
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val matrix = Array(a.length + 1) { IntArray(b.length + 1) }

        for (i in 0..a.length) {
            matrix[i][0] = i
        }
        for (j in 0..b.length) {
            matrix[0][j] = j
        }

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                matrix[i][j] = minOf(
                    matrix[i - 1][j] + 1,
                    matrix[i][j - 1] + 1,
                    matrix[i - 1][j - 1] + cost
                )
            }
        }

        return matrix[a.length][b.length]
    }

    fun extractKeyPhrases(text: String, maxPhrases: Int = 5): List<String> {
        val cleaned = text
            .replace(Regex("[^a-zA-Z0-9\\s]"), "")
            .lowercase()

        val words = cleaned.split(Regex("\\s+"))
            .filter { it.length > 3 }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(maxPhrases)
            .map { it.key }

        return words
    }

    fun hasKeywordOverlap(a: String, b: String, minOverlap: Int = 2): Boolean {
        val phrasesA = extractKeyPhrases(a).toSet()
        val phrasesB = extractKeyPhrases(b).toSet()
        return (phrasesA intersect phrasesB).size >= minOverlap
    }
}
