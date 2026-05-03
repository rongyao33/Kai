package com.inspiredandroid.kai.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class ToolExecutionRecord(
    val toolName: String,
    val argsSummary: String,
    val resultSummary: String,
    val success: Boolean,
)

data class CrystallizationSuggestion(
    val experienceId: String,
    val suggestedSkillName: String,
    val suggestedDescription: String,
    val suggestedContent: String,
    val suggestedCategory: SkillCategory,
    val suggestedTags: List<String>,
    val confidence: Float,
    val reason: String,
)

@OptIn(ExperimentalTime::class)
class MetaLearningEngine(
    private val skillStore: SkillStore,
    private val experienceStore: ExperienceStore,
    private val insightIndex: InsightIndex,
    private val memoryStore: MemoryStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _currentSessionTools = MutableStateFlow<List<ToolExecutionRecord>>(emptyList())
    val currentSessionTools: StateFlow<List<ToolExecutionRecord>> = _currentSessionTools

    private val _pendingCrystallization = MutableStateFlow<List<CrystallizationSuggestion>>(emptyList())
    val pendingCrystallization: StateFlow<List<CrystallizationSuggestion>> = _pendingCrystallization

    fun recordToolExecution(toolName: String, argsSummary: String, resultSummary: String, success: Boolean) {
        _currentSessionTools.value = _currentSessionTools.value + ToolExecutionRecord(
            toolName = toolName,
            argsSummary = argsSummary,
            resultSummary = resultSummary,
            success = success,
        )
    }

    fun recordToolExecution(toolName: String, args: String, result: String) {
        val argsSummary = args.take(200)
        val resultSummary = result.take(500)
        val success = !result.lowercase().contains("error") && !result.lowercase().contains("failed")
        recordToolExecution(toolName, argsSummary, resultSummary, success)
    }

    fun clearSessionState() {
        _currentSessionTools.value = emptyList()
        _pendingCrystallization.value = emptyList()
    }

    fun resetSessionTracking() {
        _currentSessionTools.value = emptyList()
    }

    fun shouldSuggestCrystallization(): Boolean {
        val tools = _currentSessionTools.value
        if (tools.size < 5) return false
        val successRate = tools.count { it.success }.toFloat() / tools.size
        return successRate >= 0.6f
    }

    fun analyzeSessionForCrystallization(): List<CrystallizationSuggestion> {
        val tools = _currentSessionTools.value
        if (tools.size < 3) return emptyList()

        val successCount = tools.count { it.success }
        val outcome = when {
            successCount == tools.size -> ExperienceOutcome.SUCCESS
            successCount >= tools.size / 2 -> ExperienceOutcome.PARTIAL
            else -> ExperienceOutcome.FAILURE
        }

        val toolNames = tools.map { it.toolName }.distinct()
        val taskSummary = "Session with ${tools.size} tool calls: ${toolNames.take(3).joinToString(", ")}"
        val tags = extractTags(tools, taskSummary)
        val category = inferCategory(tools, taskSummary)
        val skillName = inferSkillName(taskSummary, toolNames)
        val description = buildDescription(taskSummary, tools, outcome)
        val content = buildSkillContent(tools, taskSummary, outcome)
        val confidence = calculateConfidence(tools, outcome)

        val experienceId = "exp-pending-${Clock.System.now().toEpochMilliseconds()}"

        return listOf(
            CrystallizationSuggestion(
                experienceId = experienceId,
                suggestedSkillName = skillName,
                suggestedDescription = description,
                suggestedContent = content,
                suggestedCategory = category,
                suggestedTags = tags,
                confidence = confidence,
                reason = buildCrystallizationReason(tools, outcome, confidence),
            ),
        )
    }

    fun analyzeSessionForCrystallization(conversationId: String, taskSummary: String): CrystallizationSuggestion? {
        val tools = _currentSessionTools.value
        if (tools.size < 3) return null

        val successCount = tools.count { it.success }
        val outcome = when {
            successCount == tools.size -> ExperienceOutcome.SUCCESS
            successCount >= tools.size / 2 -> ExperienceOutcome.PARTIAL
            else -> ExperienceOutcome.FAILURE
        }

        val toolNames = tools.map { it.toolName }.distinct()
        val tags = extractTags(tools, taskSummary)
        val category = inferCategory(tools, taskSummary)
        val skillName = inferSkillName(taskSummary, toolNames)
        val description = buildDescription(taskSummary, tools, outcome)
        val content = buildSkillContent(tools, taskSummary, outcome)
        val confidence = calculateConfidence(tools, outcome)

        val experienceId = "exp-pending-${Clock.System.now().toEpochMilliseconds()}"

        return CrystallizationSuggestion(
            experienceId = experienceId,
            suggestedSkillName = skillName,
            suggestedDescription = description,
            suggestedContent = content,
            suggestedCategory = category,
            suggestedTags = tags,
            confidence = confidence,
            reason = buildCrystallizationReason(tools, outcome, confidence),
        )
    }

    suspend fun crystallize(suggestion: CrystallizationSuggestion): SkillEntry? {
        val tools = _currentSessionTools.value
        val successCount = tools.count { it.success }
        val outcome = when {
            successCount == tools.size -> ExperienceOutcome.SUCCESS
            successCount >= tools.size / 2 -> ExperienceOutcome.PARTIAL
            else -> ExperienceOutcome.FAILURE
        }

        val experience = experienceStore.record(
            conversationId = "session-${Clock.System.now().toEpochMilliseconds()}",
            taskSummary = suggestion.suggestedDescription,
            toolSequence = tools.map { ToolStep(it.toolName, it.argsSummary, it.resultSummary, it.success) },
            outcome = outcome,
            tags = suggestion.suggestedTags,
        )

        val skill = skillStore.create(
            name = suggestion.suggestedSkillName,
            description = suggestion.suggestedDescription,
            content = suggestion.suggestedContent,
            category = suggestion.suggestedCategory,
            autoCreated = true,
            tags = suggestion.suggestedTags,
        )

        experienceStore.markCrystallized(experience.id, skill.id)

        if (outcome == ExperienceOutcome.SUCCESS && suggestion.confidence >= 0.7f) {
            scope.launch {
                insightIndex.addInsight(
                    insight = "Pattern: ${suggestion.suggestedDescription}",
                    type = InsightType.PATTERN,
                    sourceExperienceId = experience.id,
                    initialConfidence = suggestion.confidence,
                )
            }
        }

        if (outcome == ExperienceOutcome.FAILURE) {
            scope.launch {
                insightIndex.addInsight(
                    insight = "Avoid: ${suggestion.suggestedDescription} — approach failed (${tools.count { !it.success }}/${tools.size} steps failed)",
                    type = InsightType.AVOIDANCE,
                    sourceExperienceId = experience.id,
                    initialConfidence = 0.6f,
                )
            }
        }

        return skill
    }

    suspend fun crystallize(suggestion: CrystallizationSuggestion, conversationId: String): SkillEntry? {
        val tools = _currentSessionTools.value
        val successCount = tools.count { it.success }
        val outcome = when {
            successCount == tools.size -> ExperienceOutcome.SUCCESS
            successCount >= tools.size / 2 -> ExperienceOutcome.PARTIAL
            else -> ExperienceOutcome.FAILURE
        }

        val experience = experienceStore.record(
            conversationId = conversationId,
            taskSummary = suggestion.suggestedDescription,
            toolSequence = tools.map { ToolStep(it.toolName, it.argsSummary, it.resultSummary, it.success) },
            outcome = outcome,
            tags = suggestion.suggestedTags,
        )

        val skill = skillStore.create(
            name = suggestion.suggestedSkillName,
            description = suggestion.suggestedDescription,
            content = suggestion.suggestedContent,
            category = suggestion.suggestedCategory,
            autoCreated = true,
            tags = suggestion.suggestedTags,
        )

        experienceStore.markCrystallized(experience.id, skill.id)

        if (outcome == ExperienceOutcome.SUCCESS && suggestion.confidence >= 0.7f) {
            scope.launch {
                insightIndex.addInsight(
                    insight = "Pattern: ${suggestion.suggestedDescription}",
                    type = InsightType.PATTERN,
                    sourceExperienceId = experience.id,
                    initialConfidence = suggestion.confidence,
                )
            }
        }

        if (outcome == ExperienceOutcome.FAILURE) {
            scope.launch {
                insightIndex.addInsight(
                    insight = "Avoid: ${suggestion.suggestedDescription} — approach failed (${tools.count { !it.success }}/${tools.size} steps failed)",
                    type = InsightType.AVOIDANCE,
                    sourceExperienceId = experience.id,
                    initialConfidence = 0.6f,
                )
            }
        }

        return skill
    }

    suspend fun autoEvolve() {
        val uncrystallized = experienceStore.getUnCrystallizedExperiences()
        for (exp in uncrystallized) {
            if (exp.toolSequence.size < 3) continue

            val existingSkill = findMatchingSkill(exp)
            if (existingSkill != null) {
                val updatedContent = existingSkill.content + "\n\n---\nRefined from experience ${exp.id}:\n" +
                    exp.toolSequence.mapIndexed { i, step ->
                        "${i + 1}. ${step.toolName}: ${step.argsSummary} → ${if (step.success) "✓" else "✗"} ${step.resultSummary.take(100)}"
                    }.joinToString("\n")
                skillStore.update(
                    id = existingSkill.id,
                    content = updatedContent,
                )
                experienceStore.markCrystallized(exp.id, existingSkill.id)
            }
        }

        val allSkills = skillStore.getAllSkills()
        for (skill in allSkills) {
            if (skill.useCount == 0 && skill.autoCreated) {
                val ageDays = (Clock.System.now().toEpochMilliseconds() - skill.createdAt) / (24 * 60 * 60 * 1000)
                if (ageDays > 30) {
                    skillStore.delete(skill.id)
                }
            }
        }
    }

    private fun findMatchingSkill(experience: ExperienceEntry): SkillEntry? {
        val tags = experience.tags
        val summary = experience.taskSummary.lowercase()
        return skillStore.getAllSkills().firstOrNull { skill ->
            skill.tags.any { tag -> tags.any { it.lowercase() == tag.lowercase() } } ||
                skill.description.lowercase().let { desc ->
                    summary.split(" ").filter { it.length > 3 }.any { desc.contains(it) }
                }
        }
    }

    fun getRelevantInsightsForContext(userMessage: String): List<InsightEntry> {
        val words = userMessage.lowercase().split(Regex("\\s+")).filter { it.length > 3 }
        val allInsights = insightIndex.getActiveInsights()
        return allInsights.filter { insight ->
            val insightLower = insight.insight.lowercase()
            words.any { insightLower.contains(it) } ||
                insight.type == InsightType.AVOIDANCE && insight.confidence >= 0.7f
        }.take(5)
    }

    fun getRelevantSkillsForContext(userMessage: String): List<SkillEntry> {
        val words = userMessage.lowercase().split(Regex("\\s+")).filter { it.length > 3 }
        val allSkills = skillStore.getAllSkills().sortedByDescending { it.useCount }
        return allSkills.filter { skill ->
            val skillText = "${skill.name} ${skill.description} ${skill.tags.joinToString(" ")}".lowercase()
            words.any { skillText.contains(it) }
        }.take(3)
    }

    private fun extractTags(tools: List<ToolExecutionRecord>, summary: String): List<String> {
        val tagSet = mutableSetOf<String>()
        for (tool in tools) {
            tagSet.add(tool.toolName)
        }
        val words = summary.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }.take(5)
        tagSet.addAll(words)
        return tagSet.toList()
    }

    private fun inferCategory(tools: List<ToolExecutionRecord>, summary: String): SkillCategory {
        val summaryLower = summary.lowercase()
        val toolNames = tools.map { it.toolName }.toSet()
        val hasFailures = tools.any { !it.success }

        return when {
            hasFailures && (summaryLower.contains("debug") || summaryLower.contains("fix") || summaryLower.contains("error")) ->
                SkillCategory.TROUBLESHOOTING
            toolNames.contains("schedule_task") || toolNames.contains("execute_shell_command") ->
                SkillCategory.AUTOMATION
            summaryLower.contains("how to") || summaryLower.contains("what is") || summaryLower.contains("lookup") ->
                SkillCategory.REFERENCE
            else -> SkillCategory.WORKFLOW
        }
    }

    private fun inferSkillName(summary: String, toolNames: List<String>): String {
        val short = summary.take(60).replace(Regex("[^a-zA-Z0-9\\s]"), "").trim()
        return if (short.isNotBlank()) short else toolNames.joinToString("_").take(40)
    }

    private fun buildDescription(summary: String, tools: List<ToolExecutionRecord>, outcome: ExperienceOutcome): String {
        val toolList = tools.map { it.toolName }.distinct().joinToString(", ")
        val outcomeStr = when (outcome) {
            ExperienceOutcome.SUCCESS -> "Successful"
            ExperienceOutcome.PARTIAL -> "Partially successful"
            ExperienceOutcome.FAILURE -> "Failed"
        }
        return "$outcomeStr workflow using $toolList: ${summary.take(100)}"
    }

    private fun buildSkillContent(tools: List<ToolExecutionRecord>, summary: String, outcome: ExperienceOutcome): String {
        val sb = StringBuilder()
        sb.appendLine("# $summary")
        sb.appendLine()
        sb.appendLine("## Steps")
        tools.forEachIndexed { index, tool ->
            val marker = if (tool.success) "✓" else "✗"
            sb.appendLine("${index + 1}. [$marker] **${tool.toolName}**: ${tool.argsSummary.take(80)}")
            sb.appendLine("   Result: ${tool.resultSummary.take(150)}")
        }
        if (outcome == ExperienceOutcome.FAILURE) {
            sb.appendLine()
            sb.appendLine("## Notes")
            sb.appendLine("This approach failed. Consider alternative strategies.")
            val failedSteps = tools.filter { !it.success }
            if (failedSteps.isNotEmpty()) {
                sb.appendLine("Failed at: ${failedSteps.joinToString(", ") { it.toolName }}")
            }
        }
        return sb.toString()
    }

    private fun calculateConfidence(tools: List<ToolExecutionRecord>, outcome: ExperienceOutcome): Float {
        val baseConfidence = when (outcome) {
            ExperienceOutcome.SUCCESS -> 0.8f
            ExperienceOutcome.PARTIAL -> 0.5f
            ExperienceOutcome.FAILURE -> 0.3f
        }
        val lengthBonus = (tools.size.toFloat() / 10f).coerceAtMost(0.1f)
        return (baseConfidence + lengthBonus).coerceAtMost(1.0f)
    }

    private fun buildCrystallizationReason(tools: List<ToolExecutionRecord>, outcome: ExperienceOutcome, confidence: Float): String {
        val successRate = tools.count { it.success }.toFloat() / tools.size
        return "Completed ${tools.size} tool calls with ${"%.0f".format(successRate * 100)}% success rate. " +
            "Confidence: ${"%.0f".format(confidence * 100)}%. " +
            "This ${outcome.name.lowercase()} pattern is worth preserving for future reuse."
    }
}
