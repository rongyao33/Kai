package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import com.inspiredandroid.kai.util.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Serializable
enum class MemoryCategory {
    GENERAL,
    LEARNING,
    ERROR,
    PREFERENCE,
}

@Immutable
@Serializable
data class MemoryEntry(
    val key: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val category: MemoryCategory = MemoryCategory.GENERAL,
    val hitCount: Int = 1,
    val source: String? = null,
    val subject: String? = null,
    val predicate: String? = null,
    val object_: String? = null,
    val confidence: Float = 1.0f,
    val lastValidated: Long? = null,
    val validationCount: Int = 0,
    val accessCount: Int = 0,
    val lastAccessed: Long? = null,
    val decayFactor: Float = 1.0f,
    val tags: List<String> = emptyList(),
    val relatedKeys: List<String> = emptyList(),
)

enum class MemoryDecayStrategy {
    EXPONENTIAL,
    LINEAR,
    PIECEWISE,
    NEURO_SCIENCE,
}

@OptIn(ExperimentalTime::class)
class MemoryStore(private val appSettings: AppSettings) {

    companion object {
        const val DEFAULT_DECAY_RATE = 0.95f
        const val MIN_CONFIDENCE_THRESHOLD = 0.3f
        const val CONSOLIDATION_CROSSOVER_MS = 3L * 24 * 60 * 60 * 1000
        const val REINFORCEMENT_BOOST = 0.1f
        const val VALIDATION_BOOST = 0.05f
    }

    private val json = SharedJson
    private val mutex = Mutex()

    private var cachedMemories: MutableList<MemoryEntry>? = null
    private var cacheDirty = true

    private fun loadMemories(): MutableList<MemoryEntry> {
        val cached = cachedMemories
        if (cached != null && !cacheDirty) return cached

        val raw = appSettings.getMemoriesJson()
        val memories = if (raw.isBlank()) {
            mutableListOf()
        } else {
            try {
                json.decodeFromString<List<MemoryEntry>>(raw).toMutableList()
            } catch (e: Exception) {
                Logger.e("MemoryStore", "failed to load memories: ${e.message}")
                mutableListOf()
            }
        }
        cachedMemories = memories
        cacheDirty = false
        return memories
    }

    private fun saveMemories(memories: List<MemoryEntry>) {
        appSettings.setMemoriesJson(json.encodeToString(memories))
        cachedMemories = memories.toMutableList()
        cacheDirty = false
    }

    fun invalidateCache() {
        cacheDirty = true
    }

    fun calculateDecayedConfidence(
        entry: MemoryEntry,
        now: Long,
        strategy: MemoryDecayStrategy = MemoryDecayStrategy.NEURO_SCIENCE,
    ): Float {
        val age = now - entry.lastValidated.let { it ?: entry.updatedAt }
        val ageDays = age.toFloat() / (24 * 60 * 60 * 1000)
        val baseConfidence = entry.confidence

        val decayed = when (strategy) {
            MemoryDecayStrategy.EXPONENTIAL -> {
                baseConfidence * Math.pow(DEFAULT_DECAY_RATE.toDouble(), ageDays.toDouble()).toFloat()
            }

            MemoryDecayStrategy.LINEAR -> {
                (baseConfidence - (ageDays * 0.01f)).coerceAtLeast(MIN_CONFIDENCE_THRESHOLD)
            }

            MemoryDecayStrategy.PIECEWISE -> {
                if (ageDays < 3) {
                    baseConfidence * (1 - ageDays * 0.05f)
                } else {
                    baseConfidence * Math.pow(DEFAULT_DECAY_RATE.toDouble(), (ageDays - 3).toDouble()).toFloat()
                }
            }

            MemoryDecayStrategy.NEURO_SCIENCE -> {
                val shortTermDecay = Math.exp(-ageDays / 1.0)
                val longTermRetention = 1.0 - Math.exp(-ageDays / 30.0)
                val neuroScienceDecay = shortTermDecay * 0.3 + longTermRetention * 0.7
                (baseConfidence * (1.0 - neuroScienceDecay * (1.0 - DEFAULT_DECAY_RATE))).toFloat()
            }
        }

        return decayed.coerceIn(MIN_CONFIDENCE_THRESHOLD, 1.0f)
    }

    suspend fun store(
        key: String,
        content: String,
        category: MemoryCategory = MemoryCategory.GENERAL,
        source: String? = null,
        subject: String? = null,
        predicate: String? = null,
        object_: String? = null,
        tags: List<String> = emptyList(),
    ): MemoryEntry = mutex.withLock {
        val memories = loadMemories()
        val now = Clock.System.now().toEpochMilliseconds()
        val existing = memories.indexOfFirst { it.key == key }
        val entry = if (existing >= 0) {
            val updated = memories[existing].copy(
                content = content,
                updatedAt = now,
                category = category,
                source = source ?: memories[existing].source,
                subject = subject ?: memories[existing].subject,
                predicate = predicate ?: memories[existing].predicate,
                object_ = object_ ?: memories[existing].object_,
                confidence = (memories[existing].confidence + 0.1f).coerceAtMost(1.0f),
                lastValidated = now,
                validationCount = memories[existing].validationCount + 1,
                tags = if (tags.isNotEmpty()) tags else memories[existing].tags,
            )
            memories[existing] = updated
            updated
        } else {
            val newEntry = MemoryEntry(
                key = key,
                content = content,
                createdAt = now,
                updatedAt = now,
                category = category,
                source = source,
                subject = subject,
                predicate = predicate,
                object_ = object_,
                confidence = 1.0f,
                lastValidated = now,
                validationCount = 0,
                tags = tags,
            )
            memories.add(newEntry)
            newEntry
        }
        saveMemories(memories)
        entry
    }

    suspend fun updateContent(key: String, content: String): MemoryEntry? = mutex.withLock {
        val memories = loadMemories()
        val index = memories.indexOfFirst { it.key == key }
        if (index < 0) return@withLock null
        val now = Clock.System.now().toEpochMilliseconds()
        val updated = memories[index].copy(content = content, updatedAt = now)
        memories[index] = updated
        saveMemories(memories)
        updated
    }

    suspend fun reinforceMemory(key: String): MemoryEntry? = mutex.withLock {
        val memories = loadMemories()
        val index = memories.indexOfFirst { it.key == key }
        if (index < 0) return@withLock null
        val now = Clock.System.now().toEpochMilliseconds()
        val entry = memories[index]
        val boost = if (entry.hitCount >= 5) REINFORCEMENT_BOOST * 1.5f else REINFORCEMENT_BOOST
        val updated = entry.copy(
            hitCount = entry.hitCount + 1,
            updatedAt = now,
            lastValidated = now,
            validationCount = entry.validationCount + 1,
            confidence = (entry.confidence + boost).coerceAtMost(1.0f),
            decayFactor = (entry.decayFactor * 1.05f).coerceAtMost(1.5f),
        )
        memories[index] = updated
        saveMemories(memories)
        updated
    }

    suspend fun validateMemory(key: String): MemoryEntry? = mutex.withLock {
        val memories = loadMemories()
        val index = memories.indexOfFirst { it.key == key }
        if (index < 0) return@withLock null
        val now = Clock.System.now().toEpochMilliseconds()
        val entry = memories[index]
        val updated = entry.copy(
            lastValidated = now,
            validationCount = entry.validationCount + 1,
            confidence = (entry.confidence + VALIDATION_BOOST).coerceAtMost(1.0f),
        )
        memories[index] = updated
        saveMemories(memories)
        updated
    }

    suspend fun recordAccess(key: String): MemoryEntry? = mutex.withLock {
        val memories = loadMemories()
        val index = memories.indexOfFirst { it.key == key }
        if (index < 0) return@withLock null
        val now = Clock.System.now().toEpochMilliseconds()
        val entry = memories[index]
        val updated = entry.copy(
            accessCount = entry.accessCount + 1,
            lastAccessed = now,
            confidence = (entry.confidence + 0.02f).coerceAtMost(1.0f),
        )
        memories[index] = updated
        saveMemories(memories)
        updated
    }

    suspend fun linkMemories(key1: String, key2: String): Boolean = mutex.withLock {
        val memories = loadMemories()
        val idx1 = memories.indexOfFirst { it.key == key1 }
        val idx2 = memories.indexOfFirst { it.key == key2 }
        if (idx1 < 0 || idx2 < 0) return@withLock false

        val now = Clock.System.now().toEpochMilliseconds()
        val m1 = memories[idx1]
        val m2 = memories[idx2]

        memories[idx1] = m1.copy(
            relatedKeys = (m1.relatedKeys + key2).distinct(),
            updatedAt = now,
        )
        memories[idx2] = m2.copy(
            relatedKeys = (m2.relatedKeys + key1).distinct(),
            updatedAt = now,
        )
        saveMemories(memories)
        true
    }

    suspend fun extractSemanticTriple(key: String, subject: String, predicate: String, object_: String): MemoryEntry? = mutex.withLock {
        val memories = loadMemories()
        val index = memories.indexOfFirst { it.key == key }
        if (index < 0) return@withLock null
        val now = Clock.System.now().toEpochMilliseconds()
        val updated = memories[index].copy(
            subject = subject,
            predicate = predicate,
            object_ = object_,
            updatedAt = now,
        )
        memories[index] = updated
        saveMemories(memories)
        updated
    }

    suspend fun applyDecay(strategy: MemoryDecayStrategy = MemoryDecayStrategy.NEURO_SCIENCE): Int = mutex.withLock {
        val memories = loadMemories()
        val now = Clock.System.now().toEpochMilliseconds()
        var decayedCount = 0

        val updated = memories.map { entry ->
            val decayedConfidence = calculateDecayedConfidence(entry, now, strategy)
            if (decayedConfidence < entry.confidence - 0.01f) {
                decayedCount++
                entry.copy(confidence = decayedConfidence, decayFactor = entry.decayFactor * 0.98f)
            } else {
                entry
            }
        }
        saveMemories(updated)
        decayedCount
    }

    suspend fun getPromotionCandidates(minHits: Int = 5, minConfidence: Float = 0.7f): List<MemoryEntry> = mutex.withLock {
        val now = Clock.System.now().toEpochMilliseconds()
        loadMemories().filter {
            it.hitCount >= minHits && calculateDecayedConfidence(it, now) >= minConfidence
        }
    }

    suspend fun getDecayedMemories(threshold: Float = 0.5f): List<MemoryEntry> = mutex.withLock {
        val now = Clock.System.now().toEpochMilliseconds()
        loadMemories().filter {
            calculateDecayedConfidence(it, now) < threshold
        }
    }

    suspend fun getMemoriesBySubject(subject: String): List<MemoryEntry> = mutex.withLock {
        loadMemories().filter { it.subject == subject }
    }

    suspend fun getMemoriesByPredicate(predicate: String): List<MemoryEntry> = mutex.withLock {
        loadMemories().filter { it.predicate == predicate }
    }

    suspend fun getRelatedMemories(key: String): List<MemoryEntry> = mutex.withLock {
        val entry = loadMemories().find { it.key == key } ?: return@withLock emptyList()
        loadMemories().filter { it.key in entry.relatedKeys }
    }

    suspend fun getFrequentMemories(limit: Int = 10): List<MemoryEntry> = mutex.withLock {
        loadMemories().sortedByDescending { it.accessCount }.take(limit)
    }

    suspend fun getRecentlyAccessedMemories(limit: Int = 10): List<MemoryEntry> = mutex.withLock {
        loadMemories().filter { it.lastAccessed != null }.sortedByDescending { it.lastAccessed }.take(limit)
    }

    suspend fun consolidateMemories(): Int = mutex.withLock {
        val memories = loadMemories()
        val now = Clock.System.now().toEpochMilliseconds()
        val toConsolidate = memories.filter {
            it.hitCount >= 3 && (now - it.lastValidated.let { v -> v ?: it.updatedAt }) > CONSOLIDATION_CROSSOVER_MS
        }
        if (toConsolidate.isEmpty()) return@withLock 0

        var consolidated = 0
        val updated = memories.map { entry ->
            if (entry in toConsolidate) {
                consolidated++
                entry.copy(
                    decayFactor = entry.decayFactor * 0.8f,
                    confidence = (entry.confidence * 1.2f).coerceAtMost(1.0f),
                )
            } else {
                entry
            }
        }
        saveMemories(updated)
        consolidated
    }

    suspend fun forget(key: String): Boolean = mutex.withLock {
        val memories = loadMemories()
        val removed = memories.removeAll { it.key == key }
        if (removed) saveMemories(memories)
        removed
    }

    suspend fun getAllMemories(): List<MemoryEntry> = mutex.withLock { loadMemories() }

    suspend fun clearAll(): Int = mutex.withLock {
        val memories = loadMemories()
        val count = memories.size
        saveMemories(emptyList())
        count
    }

    fun getMemoryStats(): MemoryStats {
        val memories = loadMemories()
        val now = Clock.System.now().toEpochMilliseconds()
        return MemoryStats(
            totalMemories = memories.size,
            byCategory = memories.groupBy { it.category }.mapValues { it.value.size },
            avgConfidence = memories.map { calculateDecayedConfidence(it, now) }.average().toFloat(),
            highConfidenceCount = memories.count { calculateDecayedConfidence(it, now) >= 0.7f },
            withSemanticTriples = memories.count { it.subject != null && it.predicate != null && it.object_ != null },
            totalReinforcements = memories.sumOf { it.hitCount },
            totalValidations = memories.sumOf { it.validationCount },
        )
    }
}

data class MemoryStats(
    val totalMemories: Int,
    val byCategory: Map<MemoryCategory, Int>,
    val avgConfidence: Float,
    val highConfidenceCount: Int,
    val withSemanticTriples: Int,
    val totalReinforcements: Int,
    val totalValidations: Int,
)
