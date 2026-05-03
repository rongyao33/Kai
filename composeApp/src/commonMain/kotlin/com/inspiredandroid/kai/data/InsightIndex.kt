package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import com.inspiredandroid.kai.util.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Serializable
enum class InsightType {
    PATTERN,
    PREFERENCE,
    AVOIDANCE,
    OPTIMIZATION,
    WORKFLOW,
}

@Immutable
@Serializable
data class InsightEntry(
    val id: String,
    val insight: String,
    val type: InsightType,
    val confidence: Float = 0.5f,
    val evidenceCount: Int = 1,
    val sourceExperienceIds: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val lastTriggeredAt: Long = 0L,
    val triggeredCount: Int = 0,
    val deprecated: Boolean = false,
)

@OptIn(ExperimentalTime::class)
class InsightIndex(private val appSettings: AppSettings) {

    private val json = SharedJson
    private val mutex = Mutex()

    private fun loadInsights(): MutableList<InsightEntry> {
        val raw = appSettings.getInsightsJson()
        if (raw.isBlank() || raw == "[]") return mutableListOf()
        return try {
            json.decodeFromString<List<InsightEntry>>(raw).toMutableList()
        } catch (e: Exception) {
            println("InsightIndex: failed to load: ${e.message}")
            mutableListOf()
        }
    }

    private fun saveInsights(insights: List<InsightEntry>) {
        appSettings.setInsightsJson(json.encodeToString(insights))
    }

    suspend fun addInsight(
        insight: String,
        type: InsightType,
        sourceExperienceId: String? = null,
        initialConfidence: Float = 0.5f,
    ): InsightEntry = mutex.withLock {
        val insights = loadInsights()
        val now = Clock.System.now().toEpochMilliseconds()

        val existing = insights.find {
            it.insight.lowercase().trim() == insight.lowercase().trim() && !it.deprecated
        }

        if (existing != null) {
            val updated = existing.copy(
                confidence = (existing.confidence + initialConfidence) / 2f,
                evidenceCount = existing.evidenceCount + 1,
                sourceExperienceIds = if (sourceExperienceId != null) {
                    (existing.sourceExperienceIds + sourceExperienceId).distinct()
                } else {
                    existing.sourceExperienceIds
                },
                updatedAt = now,
            )
            val index = insights.indexOf(existing)
            insights[index] = updated
            saveInsights(insights)
            updated
        } else {
            val id = "insight-$now"
            val entry = InsightEntry(
                id = id,
                insight = insight,
                type = type,
                confidence = initialConfidence,
                evidenceCount = 1,
                sourceExperienceIds = if (sourceExperienceId != null) listOf(sourceExperienceId) else emptyList(),
                createdAt = now,
                updatedAt = now,
            )
            insights.add(entry)
            saveInsights(insights)
            entry
        }
    }

    suspend fun recordTrigger(insightId: String): InsightEntry? = mutex.withLock {
        val insights = loadInsights()
        val index = insights.indexOfFirst { it.id == insightId }
        if (index < 0) return@withLock null
        val now = Clock.System.now().toEpochMilliseconds()
        val updated = insights[index].copy(
            triggeredCount = insights[index].triggeredCount + 1,
            lastTriggeredAt = now,
            updatedAt = now,
        )
        insights[index] = updated
        saveInsights(insights)
        updated
    }

    suspend fun deprecate(insightId: String): Boolean = mutex.withLock {
        val insights = loadInsights()
        val index = insights.indexOfFirst { it.id == insightId }
        if (index < 0) return@withLock false
        insights[index] = insights[index].copy(deprecated = true, updatedAt = Clock.System.now().toEpochMilliseconds())
        saveInsights(insights)
        true
    }

    fun getActiveInsights(): List<InsightEntry> =
        loadInsights().filter { !it.deprecated }.sortedByDescending { it.confidence }

    fun getHighConfidenceInsights(minConfidence: Float = 0.7f): List<InsightEntry> =
        loadInsights().filter { !it.deprecated && it.confidence >= minConfidence }
            .sortedByDescending { it.confidence }

    fun searchInsights(query: String): List<InsightEntry> {
        val q = query.lowercase()
        return loadInsights().filter { !it.deprecated && it.insight.lowercase().contains(q) }
            .sortedByDescending { it.confidence }
    }

    fun getInsightsByType(type: InsightType): List<InsightEntry> =
        loadInsights().filter { !it.deprecated && it.type == type }
            .sortedByDescending { it.confidence }

    fun getMostTriggeredInsights(limit: Int = 10): List<InsightEntry> =
        loadInsights().filter { !it.deprecated }
            .sortedByDescending { it.triggeredCount }
            .take(limit)

    suspend fun clearAll(): Int = mutex.withLock {
        val insights = loadInsights()
        val count = insights.size
        saveInsights(emptyList())
        count
    }
}
