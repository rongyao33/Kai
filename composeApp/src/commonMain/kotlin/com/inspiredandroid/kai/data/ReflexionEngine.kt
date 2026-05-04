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

@OptIn(ExperimentalTime::class)
class ReflexionEngine(
    private val insightIndex: InsightIndex?,
    private val memoryStore: MemoryStore?,
    private val knowledgeGraph: KnowledgeGraphStore?,
    private val skillStore: SkillStore?,
) {
    private val reflexionHistory = mutableListOf<ToolReflexion>()
    
    companion object {
        private const val MAX_HISTORY = 50
        private const val RETRY_THRESHOLD = 0.7f
        private const val SELF_CORRECT_CONFIDENCE = 0.6f
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
    
    private fun performBasicValidation(
        toolName: String,
        args: Map<String, String>,
        result: String,
        success: Boolean,
    ): BasicValidation {
        val issues = mutableListOf<String>()
        var expectedPattern: String? = null
        var actualPattern: String? = null
        
        when {
            result.contains("error", ignoreCase = true) && success -> {
                issues.add("Result contains 'error' but marked as success")
            }
            result.contains("failed", ignoreCase = true) && success -> {
                issues.add("Result contains 'failed' but marked as success")
            }
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
            expectedPattern = expectedPattern,
            actualPattern = actualPattern,
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
    
    fun getRecentReflexions(count: Int = 10): List<ToolReflexion> {
        return reflexionHistory.takeLast(count)
    }
    
    fun getFailureReflexions(): List<ToolReflexion> {
        return reflexionHistory.filter { !it.success }
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
