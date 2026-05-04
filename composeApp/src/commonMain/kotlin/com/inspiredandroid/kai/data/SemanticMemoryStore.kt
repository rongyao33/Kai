package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import com.inspiredandroid.kai.util.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Immutable
@Serializable
data class SemanticMemoryEntry(
    val id: String,
    val content: String,
    val embedding: List<Float>,
    val metadata: SemanticMetadata,
    val createdAt: Long,
    val updatedAt: Long,
)

@Immutable
@Serializable
data class SemanticMetadata(
    val category: MemoryCategory = MemoryCategory.GENERAL,
    val keywords: List<String> = emptyList(),
    val memoryKey: String? = null,
    val sourceConversationId: String? = null,
    val confidence: Float = 1.0f,
)

@OptIn(ExperimentalTime::class)
class SemanticMemoryStore(private val appSettings: AppSettings) {

    private val json = SharedJson
    private val mutex = Mutex()

    private var cachedEntries: MutableList<SemanticMemoryEntry>? = null
    private var cacheDirty = true

    companion object {
        const val EMBEDDING_DIM = 128
        const val DEFAULT_TOP_K = 5
        const val SIMILARITY_THRESHOLD = 0.7f
        const val MAX_ENTRIES = 500
    }

    private fun loadEntries(): MutableList<SemanticMemoryEntry> {
        val cached = cachedEntries
        if (cached != null && !cacheDirty) return cached

        val raw = appSettings.getSemanticMemoryJson()
        val entries = if (raw.isBlank()) {
            mutableListOf()
        } else {
            try {
                json.decodeFromString<List<SemanticMemoryEntry>>(raw).toMutableList()
            } catch (e: Exception) {
                Logger.e("SemanticMemoryStore", "failed to load entries: ${e.message}")
                mutableListOf()
            }
        }
        cachedEntries = entries
        cacheDirty = false
        return entries
    }

    private fun saveEntries(entries: List<SemanticMemoryEntry>) {
        val trimmed = trimOldEntries(entries)
        appSettings.setSemanticMemoryJson(json.encodeToString(trimmed))
        cachedEntries = trimmed.toMutableList()
        cacheDirty = false
    }

    private fun trimOldEntries(entries: List<SemanticMemoryEntry>): List<SemanticMemoryEntry> {
        if (entries.size <= MAX_ENTRIES) return entries
        return entries.sortedByDescending { it.updatedAt }.take(MAX_ENTRIES)
    }

    fun invalidateCache() {
        cacheDirty = true
    }

    private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator == 0f) 0f else dotProduct / denominator
    }

    private fun simpleEmbedding(text: String): List<Float> {
        val words = text.lowercase().split(Regex("[\\s,.!?;:]+")).filter { it.isNotBlank() }
        val embedding = MutableList(EMBEDDING_DIM) { 0f }
        for ((index, word) in words.distinct().take(EMBEDDING_DIM).withIndex()) {
            embedding[index] = word.hashCode().toFloat() / Int.MAX_VALUE * 2 - 1
        }
        val magnitude = sqrt(embedding.sumOf { it.toDouble() * it.toDouble() }.toFloat())
        return if (magnitude > 0) embedding.map { it / magnitude } else embedding
    }

    suspend fun add(
        content: String,
        metadata: SemanticMetadata = SemanticMetadata(),
    ): SemanticMemoryEntry = mutex.withLock {
        val entries = loadEntries()
        val now = Clock.System.now().toEpochMilliseconds()
        val embedding = simpleEmbedding(content)
        val entry = SemanticMemoryEntry(
            id = "sem-${now}-${entries.size}",
            content = content,
            embedding = embedding,
            metadata = metadata,
            createdAt = now,
            updatedAt = now,
        )
        entries.add(entry)
        saveEntries(entries)
        entry
    }

    suspend fun addWithEmbedding(
        content: String,
        embedding: List<Float>,
        metadata: SemanticMetadata = SemanticMetadata(),
    ): SemanticMemoryEntry = mutex.withLock {
        val entries = loadEntries()
        val now = Clock.System.now().toEpochMilliseconds()
        val entry = SemanticMemoryEntry(
            id = "sem-${now}-${entries.size}",
            content = content,
            embedding = embedding,
            metadata = metadata,
            createdAt = now,
            updatedAt = now,
        )
        entries.add(entry)
        saveEntries(entries)
        entry
    }

    fun searchByContent(
        query: String,
        topK: Int = DEFAULT_TOP_K,
    ): List<SemanticSearchResult> {
        val queryEmbedding = simpleEmbedding(query)
        return searchByEmbedding(queryEmbedding, topK)
    }

    fun searchByEmbedding(
        queryEmbedding: List<Float>,
        topK: Int = DEFAULT_TOP_K,
    ): List<SemanticSearchResult> {
        val entries = loadEntries()
        return entries.map { entry ->
            val similarity = cosineSimilarity(queryEmbedding, entry.embedding)
            SemanticSearchResult(
                entry = entry,
                similarity = similarity,
            )
        }
            .filter { it.similarity >= SIMILARITY_THRESHOLD }
            .sortedByDescending { it.similarity }
            .take(topK)
    }

    fun searchHybrid(
        query: String,
        keywords: List<String>,
        topK: Int = DEFAULT_TOP_K,
    ): List<SemanticSearchResult> {
        val semanticResults = searchByContent(query, topK * 2)
        val keywordSet = keywords.map { it.lowercase() }.toSet()

        return semanticResults.map { result ->
            val keywordMatches = result.entry.content.lowercase().let { content ->
                keywordSet.count { keyword -> content.contains(keyword) }
            }
            val boost = if (keywordMatches > 0) 1 + (keywordMatches * 0.1f) else 1f
            result.copy(
                similarity = result.similarity * boost,
                keywordMatches = keywordMatches,
            )
        }
            .sortedByDescending { it.similarity }
            .take(topK)
    }

    suspend fun update(
        id: String,
        content: String? = null,
        metadata: SemanticMetadata? = null,
    ): SemanticMemoryEntry? = mutex.withLock {
        val entries = loadEntries()
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return@withLock null

        val now = Clock.System.now().toEpochMilliseconds()
        val existing = entries[index]
        val newContent = content ?: existing.content
        val newMetadata = metadata ?: existing.metadata

        val updated = existing.copy(
            content = newContent,
            embedding = if (content != null) simpleEmbedding(newContent) else existing.embedding,
            metadata = newMetadata,
            updatedAt = now,
        )
        entries[index] = updated
        saveEntries(entries)
        updated
    }

    suspend fun delete(id: String): Boolean = mutex.withLock {
        val entries = loadEntries()
        val removed = entries.removeAll { it.id == id }
        if (removed) saveEntries(entries)
        removed
    }

    suspend fun deleteByMemoryKey(memoryKey: String): Int = mutex.withLock {
        val entries = loadEntries()
        val before = entries.size
        val remaining = entries.filter { it.metadata.memoryKey != memoryKey }
        saveEntries(remaining)
        before - remaining.size
    }

    fun getByCategory(category: MemoryCategory): List<SemanticMemoryEntry> =
        loadEntries().filter { it.metadata.category == category }

    fun getByConversation(conversationId: String): List<SemanticMemoryEntry> =
        loadEntries().filter { it.metadata.sourceConversationId == conversationId }

    fun getStats(): SemanticMemoryStats {
        val entries = loadEntries()
        return SemanticMemoryStats(
            totalEntries = entries.size,
            byCategory = entries.groupBy { it.metadata.category }.mapValues { it.value.size },
            avgSimilarity = if (entries.isNotEmpty()) {
                entries.map { it.embedding.sum() / it.embedding.size }.average().toFloat()
            } else 0f,
        )
    }

    suspend fun cleanup(keepRecent: Int = 100): Int = mutex.withLock {
        val entries = loadEntries()
        val before = entries.size
        val recentIds = entries.sortedByDescending { it.updatedAt }
            .take(keepRecent)
            .map { it.id }
            .toSet()
        val preserved = entries.filter {
            it.metadata.memoryKey != null || it.id in recentIds
        }
        saveEntries(preserved)
        before - preserved.size
    }

    suspend fun clearAll(): Int = mutex.withLock {
        val entries = loadEntries()
        val count = entries.size
        saveEntries(emptyList())
        count
    }
}

data class SemanticSearchResult(
    val entry: SemanticMemoryEntry,
    val similarity: Float,
    val keywordMatches: Int = 0,
)

data class SemanticMemoryStats(
    val totalEntries: Int,
    val byCategory: Map<MemoryCategory, Int>,
    val avgSimilarity: Float,
)
