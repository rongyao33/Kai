package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class KGSearchResult(
    val type: KGResultType,
    val id: String,
    val content: String,
    val relevanceScore: Float,
    val metadata: Map<String, String> = emptyMap(),
)

enum class KGResultType {
    NODE,
    EDGE,
    SEMANTIC,
    PATH,
}

@Immutable
data class KGContext(
    val directRelations: List<KGSearchResult>,
    val semanticMatches: List<SemanticMemoryEntry>,
    val inferredPaths: List<ReasoningPath>,
    val confidence: Float,
)

@Immutable
@Serializable
data class ReasoningPath(
    val pathId: String,
    val nodes: List<String>,
    val edges: List<String>,
    val description: String,
    val relevanceScore: Float,
)

@Immutable
@Serializable
data class KGSearchOptions(
    val includeSemantic: Boolean = true,
    val includeRelations: Boolean = true,
    val maxPathDepth: Int = 2,
    val semanticTopK: Int = 5,
    val relationTopK: Int = 10,
)

class EnhancedKnowledgeGraph(
    private val knowledgeGraph: KnowledgeGraphStore,
    private val semanticMemory: SemanticMemoryStore?,
    private val insightIndex: InsightIndex?,
    private val skillStore: SkillStore?,
) {
    suspend fun kgEnhancedRetrieval(
        query: String,
        options: KGSearchOptions = KGSearchOptions(),
        topK: Int = 10,
    ): List<KGSearchResult> {
        val results = mutableListOf<KGSearchResult>()

        if (options.includeRelations) {
            val kgNodes = knowledgeGraph.searchNodes(query)
            kgNodes.take(options.relationTopK).forEach { node ->
                results.add(
                    KGSearchResult(
                        type = KGResultType.NODE,
                        id = node.id,
                        content = buildNodeContent(node),
                        relevanceScore = 0.9f,
                        metadata = mapOf(
                            "name" to node.name,
                            "type" to node.type.name,
                        ),
                    )
                )
            }

            val outgoingEdges = knowledgeGraph.getOutgoingEdges(query)
            outgoingEdges.take(options.relationTopK).forEach { edge ->
                val targetNode = knowledgeGraph.getNodeById(edge.targetId)
                results.add(
                    KGSearchResult(
                        type = KGResultType.EDGE,
                        id = edge.id,
                        content = "${edge.relation}: ${targetNode?.name ?: "unknown"}",
                        relevanceScore = edge.confidence * 0.8f,
                        metadata = mapOf(
                            "relation" to edge.relation,
                            "source" to edge.sourceId,
                            "target" to edge.targetId,
                        ),
                    )
                )
            }
        }

        if (options.includeSemantic && semanticMemory != null) {
            val semanticResults = semanticMemory.searchByContent(query, options.semanticTopK)
            semanticResults.forEach { result ->
                results.add(
                    KGSearchResult(
                        type = KGResultType.SEMANTIC,
                        id = result.entry.id,
                        content = result.entry.content,
                        relevanceScore = result.similarity * 0.7f,
                        metadata = mapOf(
                            "category" to result.entry.metadata.category.name,
                            "keywords" to result.entry.metadata.keywords.joinToString(","),
                        ),
                    )
                )
            }
        }

        return results
            .sortedByDescending { it.relevanceScore }
            .take(topK)
    }

    suspend fun retrieveContextForTask(
        task: String,
        options: KGSearchOptions = KGSearchOptions(),
    ): KGContext {
        val kgResults = kgEnhancedRetrieval(task, options, topK = 10)

        val semanticResults = if (semanticMemory != null) {
            semanticMemory.searchByContent(task, options.semanticTopK).map { it.entry }
        } else {
            emptyList()
        }

        val paths = if (kgResults.isNotEmpty()) {
            inferPathsFromResults(kgResults, task, options.maxPathDepth)
        } else {
            emptyList()
        }

        val avgConfidence = kgResults.takeIf { it.isNotEmpty() }
            ?.map { it.relevanceScore }
            ?.average()
            ?.toFloat()
            ?: 0f

        return KGContext(
            directRelations = kgResults,
            semanticMatches = semanticResults,
            inferredPaths = paths,
            confidence = avgConfidence,
        )
    }

    suspend fun findSuccessPaths(toolName: String): List<ReasoningPath> {
        val results = mutableListOf<ReasoningPath>()

        val outgoing = knowledgeGraph.getOutgoingEdges(toolName)
        outgoing.filter { it.relation == "solved_by" || it.relation == "success_with" }
            .forEach { edge ->
                val targetNode = knowledgeGraph.getNodeById(edge.targetId)
                if (targetNode != null) {
                    results.add(
                        ReasoningPath(
                            pathId = edge.id,
                            nodes = listOf(toolName, targetNode.name),
                            edges = listOf(edge.relation),
                            description = "Successfully used $toolName to achieve ${targetNode.name}",
                            relevanceScore = edge.confidence,
                        )
                    )
                }
            }

        return results
    }

    suspend fun findReasoningPaths(
        query: String,
        maxDepth: Int = 3,
    ): List<ReasoningPath> {
        val paths = mutableListOf<ReasoningPath>()

        val queryNodes = knowledgeGraph.searchNodes(query)
        if (queryNodes.isEmpty()) return paths

        val startNode = queryNodes.first()

        val relatedNodes = knowledgeGraph.searchNodes(query).drop(1).take(5)
        val allTargets = (listOf(startNode) + relatedNodes).map { it.id }.distinct()

        for (i in allTargets.indices) {
            for (j in (i + 1) until allTargets.size) {
                val sourceId = allTargets[i]
                val targetId = allTargets[j]

                val foundPaths = knowledgeGraph.getRelationPaths(sourceId, targetId, maxDepth)
                foundPaths.take(2).forEach { pathEdges ->
                    if (pathEdges.isNotEmpty()) {
                        val nodeList = mutableListOf<String>()
                        val edgeList = mutableListOf<String>()

                        pathEdges.forEach { edge ->
                            val sourceNode = knowledgeGraph.getNodeById(edge.sourceId)
                            val targetNode = knowledgeGraph.getNodeById(edge.targetId)
                            sourceNode?.let { nodeList.add(it.name) }
                            edgeList.add(edge.relation)
                            targetNode?.let { nodeList.add(it.name) }
                        }

                        if (nodeList.size >= 2) {
                            paths.add(
                                ReasoningPath(
                                    pathId = pathEdges.first().id,
                                    nodes = nodeList.distinct(),
                                    edges = edgeList,
                                    description = nodeList.joinToString(" → "),
                                    relevanceScore = pathEdges.map { it.confidence }.average().toFloat(),
                                )
                            )
                        }
                    }
                }
            }
        }

        return paths.sortedByDescending { it.relevanceScore }.take(5)
    }

    suspend fun identifyMissingRelations(): List<KnowledgeGap> {
        val gaps = mutableListOf<KnowledgeGap>()

        val nodes = knowledgeGraph.searchNodes("").take(20)
        nodes.take(5).forEach { node ->
            val outgoing = knowledgeGraph.getOutgoingEdges(node.id)
            if (outgoing.size < 2) {
                gaps.add(
                    KnowledgeGap(
                        subject = node.name,
                        predicate = "needs_more_connections",
                        object_ = "explore",
                        importance = 0.5f,
                        suggestedExploration = "Explore connections from ${node.name}",
                    )
                )
            }
        }

        return gaps.sortedByDescending { it.importance }
    }

    suspend fun proactivelyCompleteKnowledge(): List<ProactiveInference> {
        val inferences = mutableListOf<ProactiveInference>()

        val skills = skillStore?.getAllSkills() ?: emptyList()
        skills.take(5).forEach { skill ->
            val relatedTools = knowledgeGraph.searchNodes(skill.name)
            if (relatedTools.isEmpty()) {
                inferences.add(
                    ProactiveInference(
                        type = InferenceType.RELATION_DISCOVERY,
                        subject = skill.name,
                        predicate = "related_to",
                        object_ = "tools",
                        confidence = 0.6f,
                        reasoning = "Skill '${skill.name}' has no documented tool connections yet",
                    )
                )
            }
        }

        return inferences.sortedByDescending { it.confidence }.take(5)
    }

    suspend fun findRelatedSkills(query: String): List<SkillEntry> {
        val skillList = skillStore?.getAllSkills() ?: return emptyList()
        val queryLower = query.lowercase()

        return skillList.filter { skill ->
            skill.name.lowercase().contains(queryLower) ||
            skill.description.lowercase().contains(queryLower) ||
            skill.tags.any { it.lowercase().contains(queryLower) }
        }.sortedByDescending { it.useCount }.take(5)
    }

    suspend fun getRelatedInsights(query: String): List<InsightEntry> {
        val insights = insightIndex?.getActiveInsights() ?: return emptyList()
        val queryLower = query.lowercase()

        return insights.filter { insight ->
            insight.insight.lowercase().contains(queryLower) ||
            insight.type.name.lowercase().contains(queryLower)
        }.sortedByDescending { it.confidence }.take(5)
    }

    suspend fun identifyFailurePatterns(toolName: String): List<FailurePattern> {
        val insights = insightIndex?.getActiveInsights() ?: return emptyList()

        val failureInsights = insights.filter {
            it.type == InsightType.AVOIDANCE && it.insight.contains(toolName, ignoreCase = true)
        }

        return failureInsights.map { insight ->
            FailurePattern(
                toolName = toolName,
                pattern = insight.insight,
                confidence = insight.confidence,
                evidenceCount = insight.evidenceCount,
            )
        }
    }

    suspend fun getCapabilityContext(toolName: String): CapabilityContext {
        val successPaths = findSuccessPaths(toolName)
        val failurePatterns = identifyFailurePatterns(toolName)
        val relatedSkills = findRelatedSkills(toolName)
        val relatedInsights = getRelatedInsights(toolName)

        return CapabilityContext(
            toolName = toolName,
            successApproaches = successPaths.map { it.description },
            failurePatterns = failurePatterns.map { it.pattern },
            relatedSkills = relatedSkills.map { it.name },
            highConfidenceInsights = relatedInsights.filter { it.confidence >= 0.7f }.map { it.insight },
        )
    }

    private fun inferPathsFromResults(
        results: List<KGSearchResult>,
        query: String,
        maxDepth: Int,
    ): List<ReasoningPath> {
        val paths = mutableListOf<ReasoningPath>()

        val nodeIds = results.filter { it.type == KGResultType.NODE }.map { it.id }.distinct().take(5)

        for (i in nodeIds.indices) {
            for (j in (i + 1) until nodeIds.size) {
                val sourceId = nodeIds[i]
                val targetId = nodeIds[j]

                val foundPaths = knowledgeGraph.getRelationPaths(sourceId, targetId, maxDepth)
                foundPaths.take(2).forEach { pathEdges ->
                    if (pathEdges.isNotEmpty()) {
                        val nodeList = mutableListOf<String>()
                        val edgeList = mutableListOf<String>()

                        for (edge in pathEdges) {
                            val sourceNode = knowledgeGraph.getNodeById(edge.sourceId)
                            val targetNode = knowledgeGraph.getNodeById(edge.targetId)
                            if (sourceNode != null) nodeList.add(sourceNode.name)
                            edgeList.add(edge.relation)
                            if (targetNode != null) nodeList.add(targetNode.name)
                        }

                        if (nodeList.isNotEmpty()) {
                            paths.add(
                                ReasoningPath(
                                    pathId = pathEdges.firstOrNull()?.id ?: "path-${System.nanoTime()}",
                                    nodes = nodeList.distinct(),
                                    edges = edgeList,
                                    description = nodeList.joinToString(" → "),
                                    relevanceScore = pathEdges.map { it.confidence }.average().toFloat(),
                                )
                            )
                        }
                    }
                }
            }
        }

        return paths.sortedByDescending { it.relevanceScore }.take(5)
    }

    private fun buildNodeContent(node: KnowledgeGraphNode): String {
        val sb = StringBuilder()
        sb.append(node.name)
        if (node.properties.isNotEmpty()) {
            sb.append(" (")
            sb.append(node.properties.entries.take(3).joinToString(", ") { "${it.key}: ${it.value}" })
            sb.append(")")
        }
        return sb.toString()
    }
}

@Immutable
@Serializable
data class FailurePattern(
    val toolName: String,
    val pattern: String,
    val confidence: Float,
    val evidenceCount: Int,
)

@Immutable
@Serializable
data class CapabilityContext(
    val toolName: String,
    val successApproaches: List<String>,
    val failurePatterns: List<String>,
    val relatedSkills: List<String>,
    val highConfidenceInsights: List<String>,
)

@Immutable
data class KnowledgeGap(
    val subject: String,
    val predicate: String,
    val object_: String,
    val importance: Float,
    val suggestedExploration: String,
)

@Immutable
data class ProactiveInference(
    val type: InferenceType,
    val subject: String,
    val predicate: String,
    val object_: String,
    val confidence: Float,
    val reasoning: String,
)

enum class InferenceType {
    RELATION_DISCOVERY,
    MISSING_LINK,
    PATTERN_COMPLETION,
    CAUSAL_INFERENCE,
}
