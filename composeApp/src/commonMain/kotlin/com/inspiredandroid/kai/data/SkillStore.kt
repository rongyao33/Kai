package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Serializable
enum class SkillCategory {
    WORKFLOW,
    TROUBLESHOOTING,
    REFERENCE,
    AUTOMATION,
}

@Immutable
@Serializable
data class SkillEntry(
    val id: String,
    val name: String,
    val description: String,
    val content: String,
    val category: SkillCategory = SkillCategory.WORKFLOW,
    val createdAt: Long,
    val updatedAt: Long,
    val useCount: Int = 0,
    val lastUsedAt: Long = 0L,
    val autoCreated: Boolean = false,
    val tags: List<String> = emptyList(),
    val parentSkillIds: List<String> = emptyList(),
    val evolutionGeneration: Int = 0,
    val deprecated: Boolean = false,
)

@OptIn(ExperimentalTime::class)
class SkillStore(private val appSettings: AppSettings) {

    private val json = SharedJson
    private val mutex = Mutex()

    private fun loadSkills(): MutableList<SkillEntry> {
        val raw = appSettings.getSkillsJson()
        if (raw.isBlank()) return mutableListOf()
        return try {
            json.decodeFromString<List<SkillEntry>>(raw).toMutableList()
        } catch (e: Exception) {
            println("SkillStore: failed to load skills: ${e.message}")
            mutableListOf()
        }
    }

    private fun saveSkills(skills: List<SkillEntry>) {
        appSettings.setSkillsJson(json.encodeToString(skills))
    }

    suspend fun create(
        name: String,
        description: String,
        content: String,
        category: SkillCategory = SkillCategory.WORKFLOW,
        autoCreated: Boolean = false,
        tags: List<String> = emptyList(),
    ): SkillEntry = mutex.withLock {
        val skills = loadSkills()
        val now = Clock.System.now().toEpochMilliseconds()
        val id = "skill-${now}-${name.lowercase().replace(Regex("[^a-z0-9]"), "_").take(20)}"
        val entry = SkillEntry(
            id = id,
            name = name,
            description = description,
            content = content,
            category = category,
            createdAt = now,
            updatedAt = now,
            autoCreated = autoCreated,
            tags = tags,
        )
        skills.add(entry)
        saveSkills(skills)
        entry
    }

    suspend fun update(
        id: String,
        name: String? = null,
        description: String? = null,
        content: String? = null,
        category: SkillCategory? = null,
        tags: List<String>? = null,
    ): SkillEntry? = mutex.withLock {
        val skills = loadSkills()
        val index = skills.indexOfFirst { it.id == id }
        if (index < 0) return@withLock null
        val now = Clock.System.now().toEpochMilliseconds()
        val updated = skills[index].copy(
            name = name ?: skills[index].name,
            description = description ?: skills[index].description,
            content = content ?: skills[index].content,
            category = category ?: skills[index].category,
            tags = tags ?: skills[index].tags,
            updatedAt = now,
        )
        skills[index] = updated
        saveSkills(skills)
        updated
    }

    suspend fun incrementUseCount(id: String): SkillEntry? = mutex.withLock {
        val skills = loadSkills()
        val index = skills.indexOfFirst { it.id == id }
        if (index < 0) return@withLock null
        val now = Clock.System.now().toEpochMilliseconds()
        val updated = skills[index].copy(
            useCount = skills[index].useCount + 1,
            lastUsedAt = now,
        )
        skills[index] = updated
        saveSkills(skills)
        updated
    }

    suspend fun delete(id: String): Boolean = mutex.withLock {
        val skills = loadSkills()
        val removed = skills.removeAll { it.id == id }
        if (removed) saveSkills(skills)
        removed
    }

    fun getAllSkills(): List<SkillEntry> = loadSkills()

    fun getSkillById(id: String): SkillEntry? = loadSkills().find { it.id == id }

    fun searchSkills(query: String): List<SkillEntry> {
        val q = query.lowercase()
        return loadSkills().filter { skill ->
            skill.name.lowercase().contains(q) ||
                skill.description.lowercase().contains(q) ||
                skill.content.lowercase().contains(q) ||
                skill.tags.any { it.lowercase().contains(q) }
        }
    }

    fun getSkillsByCategory(category: SkillCategory): List<SkillEntry> =
        loadSkills().filter { it.category == category }

    fun getMostUsedSkills(limit: Int = 10): List<SkillEntry> =
        loadSkills().filter { !it.deprecated }.sortedByDescending { it.useCount }.take(limit)

    suspend fun merge(
        skillIds: List<String>,
        newName: String,
        newDescription: String,
        newContent: String,
        category: SkillCategory = SkillCategory.WORKFLOW,
        tags: List<String> = emptyList(),
    ): SkillEntry? = mutex.withLock {
        val skills = loadSkills()
        val toMerge = skillIds.mapNotNull { id -> skills.find { it.id == id } }
        if (toMerge.size < 2) return@withLock null

        val now = Clock.System.now().toEpochMilliseconds()
        val mergedTags = (tags + toMerge.flatMap { it.tags }).distinct()
        val maxGeneration = toMerge.maxOf { it.evolutionGeneration }

        val merged = SkillEntry(
            id = "skill-${now}-${newName.lowercase().replace(Regex("[^a-z0-9]"), "_").take(20)}",
            name = newName,
            description = newDescription,
            content = newContent,
            category = category,
            createdAt = now,
            updatedAt = now,
            autoCreated = true,
            tags = mergedTags,
            parentSkillIds = skillIds,
            evolutionGeneration = maxGeneration + 1,
        )

        for (id in skillIds) {
            val index = skills.indexOfFirst { it.id == id }
            if (index >= 0) {
                skills[index] = skills[index].copy(deprecated = true, updatedAt = now)
            }
        }

        skills.add(merged)
        saveSkills(skills)
        merged
    }

    suspend fun evolve(
        id: String,
        newContent: String,
        newDescription: String? = null,
    ): SkillEntry? = mutex.withLock {
        val skills = loadSkills()
        val index = skills.indexOfFirst { it.id == id }
        if (index < 0) return@withLock null
        val now = Clock.System.now().toEpochMilliseconds()
        val old = skills[index]

        val evolved = SkillEntry(
            id = "skill-${now}-${old.name.lowercase().replace(Regex("[^a-z0-9]"), "_").take(20)}",
            name = old.name,
            description = newDescription ?: old.description,
            content = newContent,
            category = old.category,
            createdAt = now,
            updatedAt = now,
            autoCreated = true,
            tags = old.tags,
            parentSkillIds = listOf(old.id),
            evolutionGeneration = old.evolutionGeneration + 1,
        )

        skills[index] = old.copy(deprecated = true, updatedAt = now)
        skills.add(evolved)
        saveSkills(skills)
        evolved
    }

    fun getActiveSkills(): List<SkillEntry> =
        loadSkills().filter { !it.deprecated }

    fun getSkillTree(): Map<SkillCategory, List<SkillEntry>> {
        val skills = loadSkills()
        return skills.filter { !it.deprecated }.groupBy { it.category }
    }

    fun getEvolutionHistory(skillId: String): List<SkillEntry> {
        val skills = loadSkills()
        val result = mutableListOf<SkillEntry>()
        var current = skills.find { it.id == skillId } ?: return result
        result.add(current)
        while (current.parentSkillIds.isNotEmpty()) {
            val parents = current.parentSkillIds.mapNotNull { pid -> skills.find { it.id == pid } }
            if (parents.isEmpty()) break
            result.addAll(0, parents)
            current = parents.first()
        }
        return result
    }

    suspend fun clearAll(): Int = mutex.withLock {
        val skills = loadSkills()
        val count = skills.size
        saveSkills(emptyList())
        count
    }
}
