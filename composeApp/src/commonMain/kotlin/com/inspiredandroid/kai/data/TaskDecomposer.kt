package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable

@Immutable
data class DecompositionSuggestion(
    val shouldDecompose: Boolean,
    val reason: String,
    val suggestedSteps: List<DecomposedStep> = emptyList(),
    val parallelizableGroups: List<List<Int>> = emptyList(),
    val estimatedComplexity: TaskComplexity,
    val alternativeApproach: String? = null,
    val confidence: Float,
)

@Immutable
data class DecomposedStep(
    val order: Int,
    val description: String,
    val toolHint: String? = null,
    val expectedOutcome: String,
    val isHighRisk: Boolean = false,
    val dependencies: List<Int> = emptyList(),
)

enum class TaskComplexity {
    TRIVIAL,      // 1 tool call
    SIMPLE,       // 2-3 tool calls
    MODERATE,     // 4-5 tool calls
    COMPLEX,      // 6-10 tool calls
    VERY_COMPLEX, // 10+ tool calls
}

enum class DecompositionTrigger {
    EXPLICIT_USER_REQUEST,  // 用户明确要求分解
    HIGH_COMPLEXITY,         // 复杂度超过阈值
    MULTI_DOMAIN,            // 跨领域任务
    PARALLEL_OPPORTUNITY,    // 有并行执行机会
    FAILURE_RECOVERY,        // 失败后重试
    RESOURCE_INTENSIVE,      // 资源密集型任务
}

class TaskDecomposer(
    private val skillStore: SkillStore,
    private val experienceStore: ExperienceStore,
    private val insightIndex: InsightIndex?,
    private val reflexionEngine: ReflexionEngine,
    private val enhancedKnowledgeGraph: EnhancedKnowledgeGraph,
) {
    companion object {
        private const val COMPLEXITY_THRESHOLD = 5
        private const val TRIVIAL_THRESHOLD = 2
        private const val SIMPLE_THRESHOLD = 3
        
        private val HIGH_RISK_TOOLS = setOf(
            "send_email",
            "send_sms", 
            "delete_data",
            "execute_shell_command",
            "modify_settings",
            "schedule_task",
        )
    }
    
    suspend fun analyzeTask(
        userMessage: String,
        availableTools: List<String>,
        recentFailures: List<String> = emptyList(),
    ): DecompositionSuggestion {
        val complexity = estimateComplexity(userMessage, availableTools)
        
        val shouldDecompose = when (complexity) {
            TaskComplexity.TRIVIAL -> false
            TaskComplexity.SIMPLE -> checkSimpleTaskNeedsDecomposition(userMessage, availableTools)
            TaskComplexity.MODERATE -> checkModerateTaskNeedsDecomposition(userMessage, availableTools)
            TaskComplexity.COMPLEX,
            TaskComplexity.VERY_COMPLEX -> true
        }
        
        val suggestedSteps = if (shouldDecompose) {
            generateDecompositionHints(userMessage, availableTools, complexity)
        } else {
            emptyList()
        }
        
        val parallelizable = if (shouldDecompose) {
            identifyParallelizableSteps(suggestedSteps)
        } else {
            emptyList()
        }
        
        val alternativeApproach = if (shouldDecompose) {
            checkForAlternativeApproach(userMessage, recentFailures)
        } else {
            null
        }
        
        return DecompositionSuggestion(
            shouldDecompose = shouldDecompose,
            reason = buildReason(complexity, shouldDecompose),
            suggestedSteps = suggestedSteps,
            parallelizableGroups = parallelizable,
            estimatedComplexity = complexity,
            alternativeApproach = alternativeApproach,
            confidence = calculateConfidence(complexity, suggestedSteps.isNotEmpty()),
        )
    }
    
    fun estimateComplexity(
        userMessage: String,
        availableTools: List<String>,
    ): TaskComplexity {
        val message = userMessage.lowercase()
        
        val complexityIndicators = listOf(
            Pair(3, listOf("and then", "after that", "next step", "finally", "lastly")),
            Pair(2, listOf("several", "multiple", "different", "various")),
            Pair(2, listOf("analyze", "compare", "evaluate", "review")),
            Pair(3, listOf("research", "investigate", "find all", "search for")),
            Pair(2, listOf("create", "generate", "build", "make")),
            Pair(2, listOf("update", "modify", "change", "edit")),
            Pair(3, listOf("all files", "every file", "each file", "bulk")),
            Pair(2, listOf("schedule", "remind", "automate")),
        )
        
        var score = 0
        complexityIndicators.forEach { (weight, keywords) ->
            if (keywords.any { message.contains(it) }) {
                score += weight
            }
        }
        
        val domainIndicators = countMultiDomainIndicators(message)
        score += domainIndicators * 2
        
        val historicalComplexity = getHistoricalComplexity(availableTools)
        score += historicalComplexity
        
        return when {
            score <= TRIVIAL_THRESHOLD -> TaskComplexity.TRIVIAL
            score <= SIMPLE_THRESHOLD -> TaskComplexity.SIMPLE
            score <= COMPLEXITY_THRESHOLD -> TaskComplexity.MODERATE
            score <= COMPLEXITY_THRESHOLD * 2 -> TaskComplexity.COMPLEX
            else -> TaskComplexity.VERY_COMPLEX
        }
    }
    
    private fun countMultiDomainIndicators(message: String): Int {
        val domains = mapOf(
            "email" to listOf("email", "mail", "inbox", "gmail"),
            "calendar" to listOf("calendar", "schedule", "meeting", "event"),
            "file" to listOf("file", "folder", "directory", "document"),
            "web" to listOf("web", "search", "browse", "internet"),
            "code" to listOf("code", "git", "repository", "commit"),
            "data" to listOf("database", "data", "query", "sql"),
            "system" to listOf("system", "process", "memory", "cpu"),
        )
        
        var count = 0
        val foundDomains = mutableSetOf<String>()
        
        domains.forEach { (domain, keywords) ->
            if (keywords.any { message.contains(it) }) {
                foundDomains.add(domain)
            }
        }
        
        return foundDomains.size.coerceAtMost(3)
    }
    
    private fun getHistoricalComplexity(tools: List<String>): Int {
        return when {
            tools.size >= 5 -> 3
            tools.size >= 3 -> 2
            tools.size >= 2 -> 1
            else -> 0
        }
    }
    
    private fun checkSimpleTaskNeedsDecomposition(
        message: String,
        tools: List<String>,
    ): Boolean {
        val hasParallelKeywords = listOf("both", "all", "each", "every", "simultaneously")
            .any { message.contains(it) }
        
        if (hasParallelKeywords && tools.size >= 2) {
            return true
        }
        
        val hasSequentialKeywords = listOf("and then", "after", "before", "waiting for")
            .any { message.contains(it) }
        
        if (hasSequentialKeywords && tools.size >= 3) {
            return true
        }
        
        return false
    }
    
    private fun checkModerateTaskNeedsDecomposition(
        message: String,
        tools: List<String>,
    ): Boolean {
        return tools.size >= 4 || message.length > 200
    }
    
    private fun generateDecompositionHints(
        message: String,
        availableTools: List<String>,
        complexity: TaskComplexity,
    ): List<DecomposedStep> {
        val steps = mutableListOf<DecomposedStep>()
        
        val relevantSkills = skillStore.getAllSkills()
            .filter { skill ->
                val skillText = "${skill.name} ${skill.description}".lowercase()
                message.lowercase().split(" ").any { skillText.contains(it) }
            }
            .take(3)
        
        if (relevantSkills.isNotEmpty()) {
            steps.add(
                DecomposedStep(
                    order = 1,
                    description = "Check if existing skill can handle this task",
                    toolHint = "skill_search",
                    expectedOutcome = "Found matching skill or continue to custom approach",
                    isHighRisk = false,
                )
            )
        }
        
        val toolGroups = availableTools.chunked(3)
        var order = steps.size + 1
        
        toolGroups.forEachIndexed { index, tools ->
            val isLast = index == toolGroups.lastIndex
            steps.add(
                DecomposedStep(
                    order = order++,
                    description = "Execute: ${tools.joinToString(", ")}",
                    toolHint = tools.firstOrNull(),
                    expectedOutcome = "Complete ${tools.size} related operations",
                    isHighRisk = tools.any { it in HIGH_RISK_TOOLS },
                )
            )
        }
        
        return steps
    }
    
    private fun identifyParallelizableSteps(steps: List<DecomposedStep>): List<List<Int>> {
        if (steps.size < 3) return emptyList()
        
        val parallelGroups = mutableListOf<List<Int>>()
        
        val independentSteps = steps.filter { step ->
            steps.none { other -> 
                other.order != step.order && 
                step.dependencies.isEmpty() &&
                !other.dependencies.contains(step.order)
            }
        }
        
        if (independentSteps.size >= 2) {
            parallelGroups.add(independentSteps.map { it.order })
        }
        
        return parallelGroups
    }
    
    private suspend fun checkForAlternativeApproach(
        message: String,
        recentFailures: List<String>,
    ): String? {
        if (recentFailures.isEmpty()) return null
        
        val failurePatterns = recentFailures
            .mapNotNull { failure ->
                insightIndex?.getActiveInsights()
                    ?.filter { it.type == InsightType.AVOIDANCE && it.insight.contains(failure, ignoreCase = true) }
                    ?.firstOrNull()
            }
            .take(2)
        
        return if (failurePatterns.isNotEmpty()) {
            "Previous approach failed. Consider alternative: ${failurePatterns.joinToString("; ") { it.insight }}"
        } else {
            null
        }
    }
    
    private fun buildReason(complexity: TaskComplexity, shouldDecompose: Boolean): String {
        return when {
            shouldDecompose && complexity == TaskComplexity.VERY_COMPLEX -> 
                "Task is very complex (10+ steps estimated). Breaking into smaller steps improves success rate."
            shouldDecompose && complexity == TaskComplexity.COMPLEX ->
                "Task involves multiple domains or steps. Decomposition helps track progress and handle dependencies."
            shouldDecompose && complexity == TaskComplexity.MODERATE ->
                "Moderate complexity detected. Consider decomposing for better error handling."
            !shouldDecompose && complexity == TaskComplexity.TRIVIAL ->
                "Task can be completed in a single step."
            !shouldDecompose && complexity == TaskComplexity.SIMPLE ->
                "Task is straightforward. Direct execution is more efficient."
            else -> "Complexity: ${complexity.name}"
        }
    }
    
    private fun calculateConfidence(complexity: TaskComplexity, hasSuggestions: Boolean): Float {
        val baseConfidence = when (complexity) {
            TaskComplexity.TRIVIAL -> 0.95f
            TaskComplexity.SIMPLE -> 0.85f
            TaskComplexity.MODERATE -> 0.75f
            TaskComplexity.COMPLEX -> 0.70f
            TaskComplexity.VERY_COMPLEX -> 0.60f
        }
        
        return if (hasSuggestions) {
            baseConfidence + 0.05f
        } else {
            baseConfidence
        }
    }
    
    suspend fun shouldUseSkill(
        task: String,
        skill: SkillEntry,
    ): SkillMatchResult {
        val taskLower = task.lowercase()
        val skillText = "${skill.name} ${skill.description} ${skill.tags.joinToString(" ")}".lowercase()
        
        val keywordMatches = taskLower.split(Regex("\\s+"))
            .filter { it.length > 3 }
            .count { skillText.contains(it) }
        
        val similarityScore = keywordMatches.toFloat() / maxOf(task.split(" ").size, 1)
        
        val performanceScore = if (skill.useCount > 0) {
            minOf(skill.useCount / 10f, 0.2f)
        } else {
            0f
        }
        
        val recencyScore = if (skill.lastUsedAt > 0) {
            val daysSinceUse = (System.currentTimeMillis() - skill.lastUsedAt) / (1000 * 60 * 60 * 24)
            when {
                daysSinceUse < 7 -> 0.15f
                daysSinceUse < 30 -> 0.10f
                else -> 0.05f
            }
        } else {
            0f
        }
        
        val totalScore = (similarityScore * 0.5f) + performanceScore + recencyScore
        
        return SkillMatchResult(
            shouldUse = totalScore >= 0.4f && similarityScore >= 0.3f,
            confidence = totalScore.coerceIn(0f, 1f),
            reason = buildMatchReason(similarityScore, performanceScore, recencyScore),
            skill = skill,
        )
    }
    
    private fun buildMatchReason(
        similarity: Float,
        performance: Float,
        recency: Float,
    ): String {
        return buildString {
            if (similarity >= 0.3f) append("High keyword match. ")
            else append("Low keyword match. ")
            
            if (performance >= 0.15f) append("Frequently used skill. ")
            else if (performance >= 0.05f) append("Previously used. ")
            
            if (recency >= 0.1f) append("Recently used.")
            else if (recency >= 0.05f) append("Used before.")
        }
    }
    
    suspend fun getDecompositionHints(
        task: String,
        context: KGContext?,
    ): String {
        val hints = StringBuilder()
        
        hints.append("## Task Decomposition Analysis\n\n")
        
        val complexity = estimateComplexity(task, emptyList())
        hints.append("**Estimated Complexity**: ${complexity.name}\n\n")
        
        if (complexity == TaskComplexity.TRIVIAL || complexity == TaskComplexity.SIMPLE) {
            hints.append("This task appears straightforward. Consider direct execution.\n")
            return hints.toString()
        }
        
        hints.append("**Suggested Approach**:\n")
        
        context?.directRelations?.take(3)?.forEach { result ->
            hints.append("- ${result.content}\n")
        }
        
        hints.append("\n**Steps to Consider**:\n")
        
        val suggestedSteps = generateDecompositionHints(task, emptyList(), complexity)
        suggestedSteps.forEachIndexed { index, step ->
            hints.append("${index + 1}. ${step.description}")
            if (step.toolHint != null) {
                hints.append(" (hint: try ${step.toolHint})")
            }
            hints.append("\n")
            
            if (step.isHighRisk) {
                hints.append("   ⚠️ This step may require confirmation\n")
            }
        }
        
        return hints.toString()
    }
}

@Immutable
data class SkillMatchResult(
    val shouldUse: Boolean,
    val confidence: Float,
    val reason: String,
    val skill: SkillEntry,
)
