package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class ReflexionResult(
    val needsRetry: Boolean,
    val canSelfCorrect: Boolean,
    val retrySuggestion: String? = null,
    val correctedArgs: Map<String, String>? = null,
    val learningPoint: String? = null,
    val confidence: Float,
)

@Immutable
data class ToolReflexion(
    val id: String,
    val toolName: String,
    val args: Map<String, String>,
    val result: String,
    val success: Boolean,
    val basicValidation: BasicValidation,
    val deepAnalysis: DeepAnalysis? = null,
    val timestamp: Long,
)

@Immutable
data class BasicValidation(
    val isValid: Boolean,
    val expectedPattern: String? = null,
    val actualPattern: String? = null,
    val issues: List<String> = emptyList(),
)

@Immutable
data class DeepAnalysis(
    val rootCause: String,
    val suggestion: String,
    val canSelfCorrect: Boolean,
    val alternativeApproach: String? = null,
    val lessonsLearned: List<String> = emptyList(),
)

enum class ReflexionStrategy {
    VERIFICATION,
    ALTERNATIVE,
    COMPOSITION,
}

@Immutable
data class SessionReflexion(
    val sessionId: String,
    val outcome: SessionOutcome,
    val toolSequence: List<String>,
    val totalSteps: Int,
    val failedSteps: Int,
    val criticalFailurePoint: Int? = null,
    val lesson: String,
    val alternativeStrategy: String? = null,
    val tags: List<String> = emptyList(),
    val timestamp: Long,
)

enum class SessionOutcome {
    SUCCESS,
    SUCCESS_WITH_DIFFICULTY,
    PARTIAL_FAILURE,
    COMPLETE_FAILURE,
}

@Immutable
data class CrossSessionReflexion(
    val sessionCount: Int,
    val failurePatterns: List<CrossSessionFailurePattern>,
    val skillCombinationSuggestions: List<CrossSessionSkillCombination>,
    val knowledgeGaps: List<CrossSessionKnowledgeGap>,
    val improvementAreas: List<String>,
    val timestamp: Long,
)

@Immutable
data class CrossSessionFailurePattern(
    val toolName: String,
    val occurrenceCount: Int,
    val commonRootCause: String,
    val suggestedFix: String,
    val confidence: Float,
)

@Immutable
data class CrossSessionSkillCombination(
    val skill1: String,
    val skill2: String,
    val combinedUseCase: String,
    val estimatedSuccessRate: Float,
)

@Immutable
data class CrossSessionKnowledgeGap(
    val subject: String,
    val predicate: String,
    val obj: String,
    val importance: Float,
    val suggestedExploration: String,
)

@OptIn(ExperimentalTime::class)
class ReflexionEngine(
    private val insightIndex: InsightIndex?,
    private val memoryStore: MemoryStore?,
    private val knowledgeGraph: KnowledgeGraphStore?,
    private val skillStore: SkillStore?,
) {
    private val reflexionHistory = mutableListOf<ToolReflexion>()
    private val sessionReflexions = mutableListOf<SessionReflexion>()
    
    companion object {
        private const val MAX_HISTORY = 50
        private const val MAX_SESSION_HISTORY = 20
        private const val RETRY_THRESHOLD = 0.7f
        private const val SELF_CORRECT_CONFIDENCE = 0.6f
        private const val CROSS_SESSION_MIN_SAMPLES = 5
    }
    
    fun reflectOnToolResult(
        toolName: String,
        args: Map<String, String>,
        result: String,
        success: Boolean,
    ): ReflexionResult {
        val resultStr = result

        val basicValidation = performBasicValidation(toolName, args, resultStr, success)

        val deepAnalysis = if (!success) {
            performDeepAnalysis(toolName, args, resultStr)
        } else {
            null
        }

        val reflexion = ToolReflexion(
            id = "reflex-${Clock.System.now().toEpochMilliseconds()}",
            toolName = toolName,
            args = args,
            result = resultStr,
            success = success,
            basicValidation = basicValidation,
            deepAnalysis = deepAnalysis,
            timestamp = Clock.System.now().toEpochMilliseconds(),
        )

        addToHistory(reflexion)

        return buildReflexionResult(reflexion, basicValidation, deepAnalysis)
    }
    
    fun reflectOnToolExecution(
        toolName: String,
        argsSummary: String,
        resultSummary: String,
        success: Boolean,
    ): ReflexionResult {
        val basicValidation = performBasicValidationSimple(toolName, resultSummary, success)
        
        val deepAnalysis = if (!success) {
            performDeepAnalysisSimple(toolName, resultSummary)
        } else {
            null
        }
        
        return ReflexionResult(
            needsRetry = !basicValidation.isValid && deepAnalysis?.canSelfCorrect == true,
            canSelfCorrect = deepAnalysis?.canSelfCorrect ?: false,
            retrySuggestion = deepAnalysis?.suggestion,
            correctedArgs = null,
            learningPoint = deepAnalysis?.lessonsLearned?.firstOrNull(),
            confidence = if (success) 0.9f else basicValidation.issues.size.let { 1f - (it * 0.2f) },
        )
    }
    
    fun reflectOnSessionComplete(
        sessionId: String,
        toolSequence: List<ToolReflexion>,
        userGoal: String,
    ): SessionReflexion {
        val totalSteps = toolSequence.size
        val failedSteps = toolSequence.count { !it.success }
        val success = failedSteps == 0
        val partialFailure = failedSteps in 1..(totalSteps / 2)
        
        val outcome = when {
            success -> SessionOutcome.SUCCESS
            failedSteps == totalSteps -> SessionOutcome.COMPLETE_FAILURE
            partialFailure -> SessionOutcome.PARTIAL_FAILURE
            else -> SessionOutcome.SUCCESS_WITH_DIFFICULTY
        }
        
        val criticalFailurePoint = if (failedSteps > 0) {
            toolSequence.indexOfFirst { !it.success }
        } else null
        
        val lesson = buildSessionLesson(toolSequence, outcome, userGoal)
        
        val alternativeStrategy = if (outcome != SessionOutcome.SUCCESS) {
            buildAlternativeStrategy(toolSequence, outcome)
        } else null
        
        val tags = extractSessionTags(toolSequence, outcome)
        
        val sessionReflexion = SessionReflexion(
            sessionId = sessionId,
            outcome = outcome,
            toolSequence = toolSequence.map { it.toolName },
            totalSteps = totalSteps,
            failedSteps = failedSteps,
            criticalFailurePoint = criticalFailurePoint,
            lesson = lesson,
            alternativeStrategy = alternativeStrategy,
            tags = tags,
            timestamp = Clock.System.now().toEpochMilliseconds(),
        )
        
        addSessionReflexion(sessionReflexion)
        
        return sessionReflexion
    }
    
    suspend fun reflexionAcrossSessions(): CrossSessionReflexion {
        if (sessionReflexions.size < CROSS_SESSION_MIN_SAMPLES) {
            return CrossSessionReflexion(
                sessionCount = sessionReflexions.size,
                failurePatterns = emptyList(),
                skillCombinationSuggestions = emptyList(),
                knowledgeGaps = emptyList(),
                improvementAreas = listOf("Not enough session data for cross-session analysis"),
                timestamp = Clock.System.now().toEpochMilliseconds(),
            )
        }
        
        val failurePatterns = analyzeFailurePatterns()
        val skillCombinations = analyzeSkillCombinations()
        val knowledgeGaps = analyzeKnowledgeGaps()
        val improvementAreas = suggestImprovements(failurePatterns, skillCombinations)
        
        return CrossSessionReflexion(
            sessionCount = sessionReflexions.size,
            failurePatterns = failurePatterns,
            skillCombinationSuggestions = skillCombinations,
            knowledgeGaps = knowledgeGaps,
            improvementAreas = improvementAreas,
            timestamp = Clock.System.now().toEpochMilliseconds(),
        )
    }
    
    private fun analyzeFailurePatterns(): List<CrossSessionFailurePattern> {
        val failureByTool = sessionReflexions
            .filter { it.outcome != SessionOutcome.SUCCESS }
            .flatMap { session -> 
                session.toolSequence.mapIndexedNotNull { index, tool ->
                    if (session.failedSteps > 0 && index == session.criticalFailurePoint) {
                        tool to session.lesson
                    } else null
                }
            }
            .groupBy { it.first }
        
        return failureByTool.map { (tool, lessons) ->
            val rootCause = lessons
                .groupingBy { it.second }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key ?: "Unknown"
            
            CrossSessionFailurePattern(
                toolName = tool,
                occurrenceCount = lessons.size,
                commonRootCause = rootCause,
                suggestedFix = buildSuggestedFix(tool, rootCause),
                confidence = minOf(lessons.size / 10f, 1f),
            )
        }.sortedByDescending { it.occurrenceCount }
         .take(5)
    }
    
    private fun analyzeSkillCombinations(): List<CrossSessionSkillCombination> {
        val sequentialTools = sessionReflexions
            .filter { it.outcome == SessionOutcome.SUCCESS }
            .map { session -> 
                session.toolSequence.windowed(2, 1).map { pair -> pair[0] to pair[1] }
            }
            .flatten()
            .groupBy { it }
            .mapValues { (_, pairs) -> pairs.size }
        
        return sequentialTools
            .filter { (_, count) -> count >= 2 }
            .map { (pair, count) ->
                val (tool1, tool2) = pair
                CrossSessionSkillCombination(
                    skill1 = tool1,
                    skill2 = tool2,
                    combinedUseCase = "Often used together: $tool1 then $tool2",
                    estimatedSuccessRate = minOf(count / 5f, 0.9f),
                )
            }
            .sortedByDescending { it.estimatedSuccessRate }
            .take(3)
    }
    
    private fun analyzeKnowledgeGaps(): List<CrossSessionKnowledgeGap> {
        val gaps = mutableListOf<CrossSessionKnowledgeGap>()
        
        val failedTools = sessionReflexions
            .filter { it.outcome == SessionOutcome.COMPLETE_FAILURE }
            .flatMap { it.toolSequence }
            .distinct()
        
        failedTools.take(3).forEach { tool ->
            gaps.add(
                CrossSessionKnowledgeGap(
                    subject = tool,
                    predicate = "fails_because",
                    obj = "unknown_prerequisite",
                    importance = 0.7f,
                    suggestedExploration = "Investigate what conditions are needed for $tool to succeed",
                )
            )
        }
        
        return gaps
    }
    
    private fun suggestImprovements(
        failurePatterns: List<CrossSessionFailurePattern>,
        skillCombinations: List<CrossSessionSkillCombination>,
    ): List<String> {
        val improvements = mutableListOf<String>()
        
        if (failurePatterns.isNotEmpty()) {
            val topFailure = failurePatterns.first()
            improvements.add("Focus on improving reliability of '${topFailure.toolName}' - failed ${topFailure.occurrenceCount} times")
        }
        
        if (skillCombinations.isNotEmpty()) {
            improvements.add("Consider automating the '${skillCombinations.first().skill1}' → '${skillCombinations.first().skill2}' workflow")
        }
        
        val highFailureRateSessions = sessionReflexions.count { 
            it.outcome == SessionOutcome.PARTIAL_FAILURE || it.outcome == SessionOutcome.COMPLETE_FAILURE 
        }
        if (highFailureRateSessions > sessionReflexions.size / 3) {
            improvements.add("High failure rate detected. Consider adding more pre-execution validation")
        }
        
        return improvements.take(3)
    }
    
    private fun buildSessionLesson(
        toolSequence: List<ToolReflexion>,
        outcome: SessionOutcome,
        userGoal: String,
    ): String {
        return when (outcome) {
            SessionOutcome.SUCCESS -> {
                val tools = toolSequence.joinToString(" → ") { it.toolName }
                "Successfully completed: $tools"
            }
            SessionOutcome.SUCCESS_WITH_DIFFICULTY -> {
                val failedTool = toolSequence.find { !it.success }?.toolName ?: "unknown"
                "Completed with difficulty. Failed at: $failedTool. Consider retry strategy."
            }
            SessionOutcome.PARTIAL_FAILURE -> {
                val failedCount = toolSequence.count { !it.success }
                "Partial failure: $failedCount/${toolSequence.size} steps failed. Review sequence."
            }
            SessionOutcome.COMPLETE_FAILURE -> {
                val firstFailure = toolSequence.firstOrNull { !it.success }
                "Complete failure at: ${firstFailure?.toolName}. Root cause: ${firstFailure?.deepAnalysis?.rootCause ?: "unknown"}"
            }
        }
    }
    
    private fun buildAlternativeStrategy(
        toolSequence: List<ToolReflexion>,
        outcome: SessionOutcome,
    ): String? {
        val failurePoint = toolSequence.indexOfFirst { !it.success }
        if (failurePoint < 0) return null
        
        val failedTool = toolSequence[failurePoint]
        val alternatives = failedTool.deepAnalysis?.alternativeApproach
        
        return when {
            alternatives != null -> "Alternative: $alternatives"
            failurePoint > 0 -> "Try executing ${toolSequence[failurePoint - 1].toolName} again before ${failedTool.toolName}"
            else -> "Consider skipping ${failedTool.toolName} and trying alternative approach"
        }
    }
    
    private fun extractSessionTags(
        toolSequence: List<ToolReflexion>,
        outcome: SessionOutcome,
    ): List<String> {
        val tags = mutableListOf<String>()
        
        val tools = toolSequence.map { it.toolName }
        if (tools.any { it.contains("search", ignoreCase = true) }) tags.add("research")
        if (tools.any { it.contains("email", ignoreCase = true) }) tags.add("communication")
        if (tools.any { it.contains("file", ignoreCase = true) }) tags.add("file_operations")
        if (tools.any { it.contains("schedule", ignoreCase = true) }) tags.add("scheduling")
        
        when (outcome) {
            SessionOutcome.SUCCESS -> tags.add("successful")
            SessionOutcome.SUCCESS_WITH_DIFFICULTY -> tags.add("recovered")
            SessionOutcome.PARTIAL_FAILURE -> tags.add("partial")
            SessionOutcome.COMPLETE_FAILURE -> tags.add("failed")
        }
        
        return tags
    }
    
    private fun buildSuggestedFix(toolName: String, rootCause: String): String {
        return when {
            rootCause.contains("timeout", ignoreCase = true) -> 
                "Add retry with exponential backoff for $toolName"
            rootCause.contains("permission", ignoreCase = true) -> 
                "Check and grant required permissions before $toolName"
            rootCause.contains("not found", ignoreCase = true) -> 
                "Add existence check before executing $toolName"
            else -> "Review input parameters for $toolName"
        }
    }
    
    private fun performBasicValidation(
        toolName: String,
        args: Map<String, String>,
        result: String,
        success: Boolean,
    ): BasicValidation {
        val issues = mutableListOf<String>()
        
        if (result.contains("error", ignoreCase = true) && success) {
            issues.add("Result contains 'error' but marked as success")
        }
        
        if (result.contains("failed", ignoreCase = true) && success) {
            issues.add("Result contains 'failed' but marked as success")
        }
        
        val resultLower = result.lowercase()
        when {
            toolName.contains("search", ignoreCase = true) -> {
                if (resultLower.contains("no results") || resultLower.contains("empty")) {
                    issues.add("Search returned no results")
                }
            }
            toolName.contains("execute", ignoreCase = true) -> {
                if (resultLower.contains("permission denied") || resultLower.contains("access denied")) {
                    issues.add("Permission or access issue detected")
                }
            }
            toolName.contains("read", ignoreCase = true) || toolName.contains("get", ignoreCase = true) -> {
                if (resultLower.contains("not found") || resultLower.contains("does not exist")) {
                    issues.add("Requested resource not found")
                }
            }
        }
        
        if (args.isEmpty() && toolName !in listOf("noop", "ping")) {
            issues.add("No arguments provided for $toolName")
        }
        
        return BasicValidation(
            isValid = issues.isEmpty(),
            expectedPattern = null,
            actualPattern = null,
            issues = issues,
        )
    }
    
    private fun performBasicValidationSimple(
        toolName: String,
        result: String,
        success: Boolean,
    ): BasicValidation {
        val issues = mutableListOf<String>()
        
        if (result.contains("error", ignoreCase = true) && success) {
            issues.add("Result contains 'error' but marked as success")
        }
        
        if (result.contains("failed", ignoreCase = true) && success) {
            issues.add("Result contains 'failed' but marked as success")
        }
        
        return BasicValidation(
            isValid = issues.isEmpty(),
            issues = issues,
        )
    }
    
    private fun performDeepAnalysis(
        toolName: String,
        args: Map<String, String>,
        result: String,
    ): DeepAnalysis {
        val rootCause = when {
            result.contains("timeout", ignoreCase = true) -> 
                "Operation timed out - likely network issue or resource unavailability"
            result.contains("permission denied", ignoreCase = true) || result.contains("access denied", ignoreCase = true) ->
                "Insufficient permissions - check user rights or tool configuration"
            result.contains("not found", ignoreCase = true) || result.contains("does not exist", ignoreCase = true) ->
                "Target resource doesn't exist or path is incorrect"
            result.contains("invalid", ignoreCase = true) || result.contains("malformed", ignoreCase = true) ->
                "Input arguments are invalid or malformed"
            result.contains("rate limit", ignoreCase = true) || result.contains("too many requests", ignoreCase = true) ->
                "Rate limiting triggered - consider adding delay or batching"
            result.contains("connection", ignoreCase = true) || result.contains("unreachable", ignoreCase = true) ->
                "Network connectivity issue"
            else ->
                "Unknown error - requires manual investigation"
        }
        
        val suggestion = buildSuggestion(toolName, args, result, rootCause)
        
        val alternativeApproach = when {
            toolName.contains("execute_shell") && result.contains("permission") ->
                "Try using a different command or check if elevated privileges are needed"
            toolName.contains("search") && result.contains("no results") ->
                "Try broader search terms or different search strategy"
            toolName.contains("read") && result.contains("not found") ->
                "Verify the path is correct or use list tools to explore available resources"
            else -> null
        }
        
        val lessons = listOf(
            "Tool: $toolName failed with: ${result.take(100)}",
            "Root cause assessment: $rootCause",
            "Suggested approach: $suggestion",
        )
        
        return DeepAnalysis(
            rootCause = rootCause,
            suggestion = suggestion,
            canSelfCorrect = alternativeApproach != null || suggestion.isNotBlank(),
            alternativeApproach = alternativeApproach,
            lessonsLearned = lessons,
        )
    }
    
    private fun performDeepAnalysisSimple(
        toolName: String,
        result: String,
    ): DeepAnalysis {
        val rootCause = when {
            result.contains("timeout", ignoreCase = true) -> "Operation timed out"
            result.contains("permission denied", ignoreCase = true) -> "Permission issue"
            result.contains("not found", ignoreCase = true) -> "Resource not found"
            result.contains("invalid", ignoreCase = true) -> "Invalid input"
            else -> "Unknown error"
        }
        
        val suggestion = buildSimpleSuggestion(toolName, result, rootCause)
        
        return DeepAnalysis(
            rootCause = rootCause,
            suggestion = suggestion,
            canSelfCorrect = suggestion.isNotBlank(),
            lessonsLearned = listOf("Failed: $toolName - $rootCause"),
        )
    }
    
    private fun buildSuggestion(
        toolName: String,
        args: Map<String, String>,
        result: String,
        rootCause: String,
    ): String {
        return when {
            rootCause.contains("timeout") ->
                "Consider adding retry logic with exponential backoff or check network connectivity"
            rootCause.contains("permission") ->
                "Verify tool permissions or try an alternative approach that doesn't require elevated access"
            rootCause.contains("not found") ->
                "List available resources first or validate the target path before attempting access"
            rootCause.contains("invalid") ->
                "Review and correct the input arguments: ${args.keys.joinToString(", ")}"
            rootCause.contains("rate limit") ->
                "Add delay between requests or implement request batching"
            else ->
                "Review the error details and consider breaking the task into smaller steps"
        }
    }
    
    private fun buildSimpleSuggestion(toolName: String, result: String, rootCause: String): String {
        return when {
            rootCause.contains("timeout") -> "Retry with timeout handling"
            rootCause.contains("permission") -> "Check permissions or use alternative"
            rootCause.contains("not found") -> "Verify target exists first"
            rootCause.contains("invalid") -> "Fix input parameters"
            else -> "Review error and retry"
        }
    }
    
    private fun buildReflexionResult(
        reflexion: ToolReflexion,
        validation: BasicValidation,
        analysis: DeepAnalysis?,
    ): ReflexionResult {
        val canSelfCorrect = analysis != null && 
            reflexionHistory.count { !it.success } < 3 &&
            analysis.suggestion.isNotBlank()
        
        val needsRetry = !validation.isValid && canSelfCorrect
        
        return ReflexionResult(
            needsRetry = needsRetry,
            canSelfCorrect = canSelfCorrect,
            retrySuggestion = if (needsRetry) analysis.suggestion else null,
            correctedArgs = null,
            learningPoint = analysis?.lessonsLearned?.firstOrNull(),
            confidence = if (validation.isValid) RETRY_THRESHOLD else 0.5f,
        )
    }
    
    private fun addToHistory(reflexion: ToolReflexion) {
        reflexionHistory.add(reflexion)
        if (reflexionHistory.size > MAX_HISTORY) {
            reflexionHistory.removeAt(0)
        }
    }
    
    private fun addSessionReflexion(reflexion: SessionReflexion) {
        sessionReflexions.add(reflexion)
        if (sessionReflexions.size > MAX_SESSION_HISTORY) {
            sessionReflexions.removeAt(0)
        }
    }
    
    fun getRecentReflexions(count: Int = 10): List<ToolReflexion> {
        return reflexionHistory.takeLast(count)
    }
    
    fun getFailureReflexions(): List<ToolReflexion> {
        return reflexionHistory.filter { !it.success }
    }
    
    fun getSessionReflexions(): List<SessionReflexion> {
        return sessionReflexions.toList()
    }
    
    suspend fun storeLearningPoint(toolName: String, lesson: String, type: InsightType) {
        insightIndex?.addInsight(
            insight = "$toolName: $lesson",
            type = type,
            sourceExperienceId = null,
            initialConfidence = 0.6f,
        )
    }
    
    suspend fun storeAvoidanceInsight(toolName: String, failureDescription: String) {
        insightIndex?.addInsight(
            insight = "Avoid: $failureDescription",
            type = InsightType.AVOIDANCE,
            sourceExperienceId = null,
            initialConfidence = 0.5f,
        )
    }
}
