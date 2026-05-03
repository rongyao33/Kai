package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Serializable
enum class ExperienceOutcome {
    SUCCESS,
    PARTIAL,
    FAILURE,
}

@Immutable
@Serializable
data class ExperienceEntry(
    val id: String,
    val conversationId: String,
    val taskSummary: String,
    val toolSequence: List<ToolStep>,
    val outcome: ExperienceOutcome,
    val crystallized: Boolean = false,
    val crystallizedSkillId: String? = null,
    val createdAt: Long,
    val tags: List<String> = emptyList(),
)

@Immutable
@Serializable
data class ToolStep(
    val toolName: String,
    val argsSummary: String,
    val resultSummary: String,
    val success: Boolean,
)

@OptIn(ExperimentalTime::class)
class ExperienceStore(private val appSettings: AppSettings) {

    private val json = SharedJson
    private val mutex = Mutex()

    private fun loadExperiences(): MutableList<ExperienceEntry> {
        val raw = appSettings.getExperiencesJson()
        if (raw.isBlank() || raw == "[]") return mutableListOf()
        return try {
            json.decodeFromString<List<ExperienceEntry>>(raw).toMutableList()
        } catch (e: Exception) {
            println("ExperienceStore: failed to load: ${e.message}")
            mutableListOf()
        }
    }

    private fun saveExperiences(experiences: List<ExperienceEntry>) {
        appSettings.setExperiencesJson(json.encodeToString(experiences))
    }

    suspend fun record(
        conversationId: String,
        taskSummary: String,
        toolSequence: List<ToolStep>,
        outcome: ExperienceOutcome,
        tags: List<String> = emptyList(),
    ): ExperienceEntry = mutex.withLock {
        val experiences = loadExperiences()
        val now = Clock.System.now().toEpochMilliseconds()
        val id = "exp-$now"
        val entry = ExperienceEntry(
            id = id,
            conversationId = conversationId,
            taskSummary = taskSummary,
            toolSequence = toolSequence,
            outcome = outcome,
            createdAt = now,
            tags = tags,
        )
        experiences.add(entry)
        saveExperiences(experiences)
        entry
    }

    suspend fun markCrystallized(experienceId: String, skillId: String): Boolean = mutex.withLock {
        val experiences = loadExperiences()
        val index = experiences.indexOfFirst { it.id == experienceId }
        if (index < 0) return@withLock false
        experiences[index] = experiences[index].copy(
            crystallized = true,
            crystallizedSkillId = skillId,
        )
        saveExperiences(experiences)
        true
    }

    fun getUnCrystallizedExperiences(): List<ExperienceEntry> =
        loadExperiences().filter { !it.crystallized && it.toolSequence.size >= 3 }

    fun getRecentExperiences(limit: Int = 20): List<ExperienceEntry> =
        loadExperiences().sortedByDescending { it.createdAt }.take(limit)

    fun getSuccessfulPatterns(): List<ExperienceEntry> =
        loadExperiences().filter { it.outcome == ExperienceOutcome.SUCCESS && it.toolSequence.size >= 3 }

    fun searchExperiences(query: String): List<ExperienceEntry> {
        val q = query.lowercase()
        return loadExperiences().filter { exp ->
            exp.taskSummary.lowercase().contains(q) ||
                exp.tags.any { it.lowercase().contains(q) } ||
                exp.toolSequence.any { step ->
                    step.toolName.lowercase().contains(q) ||
                        step.argsSummary.lowercase().contains(q)
                }
        }
    }

    fun getToolUsageStats(): Map<String, Int> =
        loadExperiences()
            .flatMap { it.toolSequence }
            .groupingBy { it.toolName }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(20)
            .toMap()

    suspend fun cleanup(): Int = mutex.withLock {
        val experiences = loadExperiences()
        val before = experiences.size
        val cutoff = Clock.System.now().toEpochMilliseconds() - (30L * 24 * 60 * 60 * 1000)
        val kept = experiences.filter { it.createdAt > cutoff || !it.crystallized }
        saveExperiences(kept)
        before - kept.size
    }

    suspend fun clearAll(): Int = mutex.withLock {
        val experiences = loadExperiences()
        val count = experiences.size
        saveExperiences(emptyList())
        count
    }
}
