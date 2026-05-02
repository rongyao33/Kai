package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.ExperienceOutcome
import com.inspiredandroid.kai.data.InsightIndex
import com.inspiredandroid.kai.data.InsightType
import com.inspiredandroid.kai.data.MetaLearningEngine
import com.inspiredandroid.kai.data.SkillCategory
import com.inspiredandroid.kai.data.SkillEntry
import com.inspiredandroid.kai.data.SkillStore
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema

object MetaLearningTools {

    val skillEvolveToolInfo = ToolInfo(
        id = "skill_evolve",
        name = "Evolve Skill",
        description = "Evolve an existing skill with improved content",
    )

    val skillMergeToolInfo = ToolInfo(
        id = "skill_merge",
        name = "Merge Skills",
        description = "Merge multiple skills into a single evolved skill",
    )

    val insightAddToolInfo = ToolInfo(
        id = "insight_add",
        name = "Add Insight",
        description = "Record a learned insight for future reference",
    )

    val insightSearchToolInfo = ToolInfo(
        id = "insight_search",
        name = "Search Insights",
        description = "Search accumulated insights and patterns",
    )

    val metaLearningToolDefinitions = listOf(
        skillEvolveToolInfo,
        skillMergeToolInfo,
        insightAddToolInfo,
        insightSearchToolInfo,
    )

    fun skillEvolveTool(skillStore: SkillStore) = object : Tool {
        override val schema = ToolSchema(
            name = "skill_evolve",
            description = "Evolve an existing skill with improved or updated content. Creates a new version of the skill while deprecating the old one. Use this when you've found a better approach than what's documented in an existing skill.",
            parameters = mapOf(
                "id" to ParameterSchema(type = "string", description = "ID of the skill to evolve", required = true),
                "new_content" to ParameterSchema(type = "string", description = "Improved skill content with updated steps and notes", required = true),
                "new_description" to ParameterSchema(type = "string", description = "Optional updated description", required = false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val id = args["id"]?.toString() ?: return mapOf("success" to false, "error" to "Missing id")
            val newContent = args["new_content"]?.toString() ?: return mapOf("success" to false, "error" to "Missing new_content")
            val newDescription = args["new_description"]?.toString()

            val evolved = skillStore.evolve(id, newContent, newDescription)
                ?: return mapOf("success" to false, "error" to "Skill not found: $id")

            return mapOf(
                "success" to true,
                "id" to evolved.id,
                "name" to evolved.name,
                "generation" to evolved.evolutionGeneration,
                "parent_id" to id,
                "message" to "Skill '${evolved.name}' evolved to generation ${evolved.evolutionGeneration}",
            )
        }
    }

    fun skillMergeTool(skillStore: SkillStore) = object : Tool {
        override val schema = ToolSchema(
            name = "skill_merge",
            description = "Merge multiple related skills into a single comprehensive skill. The original skills are deprecated and replaced by the merged skill. Use this when you have overlapping or complementary skills that should be consolidated.",
            parameters = mapOf(
                "skill_ids" to ParameterSchema(type = "string", description = "Comma-separated list of skill IDs to merge", required = true),
                "name" to ParameterSchema(type = "string", description = "Name for the merged skill", required = true),
                "description" to ParameterSchema(type = "string", description = "Description of the merged skill", required = true),
                "content" to ParameterSchema(type = "string", description = "Combined and refined content for the merged skill", required = true),
                "category" to ParameterSchema(type = "string", description = "Category: WORKFLOW, TROUBLESHOOTING, REFERENCE, or AUTOMATION", required = false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val skillIdsStr = args["skill_ids"]?.toString() ?: return mapOf("success" to false, "error" to "Missing skill_ids")
            val name = args["name"]?.toString() ?: return mapOf("success" to false, "error" to "Missing name")
            val description = args["description"]?.toString() ?: return mapOf("success" to false, "error" to "Missing description")
            val content = args["content"]?.toString() ?: return mapOf("success" to false, "error" to "Missing content")
            val categoryStr = args["category"]?.toString()?.uppercase() ?: "WORKFLOW"

            val skillIds = skillIdsStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (skillIds.size < 2) return mapOf("success" to false, "error" to "Need at least 2 skills to merge")

            val category = try { SkillCategory.valueOf(categoryStr) } catch (_: Exception) { SkillCategory.WORKFLOW }

            val merged = skillStore.merge(skillIds, name, description, content, category)
                ?: return mapOf("success" to false, "error" to "Failed to merge skills. Check that all IDs exist.")

            return mapOf(
                "success" to true,
                "id" to merged.id,
                "name" to merged.name,
                "generation" to merged.evolutionGeneration,
                "merged_from" to skillIds,
                "message" to "Merged ${skillIds.size} skills into '${name}' (generation ${merged.evolutionGeneration})",
            )
        }
    }

    fun insightAddTool(insightIndex: InsightIndex) = object : Tool {
        override val schema = ToolSchema(
            name = "insight_add",
            description = "Record a learned insight, pattern, or principle for future reference. Insights are automatically injected into your context when relevant topics arise. Use this to capture meta-knowledge like 'user prefers concise answers' or 'API X requires authentication header'.",
            parameters = mapOf(
                "insight" to ParameterSchema(type = "string", description = "The insight or pattern to record", required = true),
                "type" to ParameterSchema(type = "string", description = "Type: PATTERN (recurring pattern), PREFERENCE (user preference), AVOIDANCE (thing to avoid), OPTIMIZATION (efficiency improvement), WORKFLOW (process knowledge)", required = false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val insight = args["insight"]?.toString() ?: return mapOf("success" to false, "error" to "Missing insight")
            val typeStr = args["type"]?.toString()?.uppercase() ?: "PATTERN"

            val type = try { InsightType.valueOf(typeStr) } catch (_: Exception) { InsightType.PATTERN }

            val entry = insightIndex.addInsight(insight = insight, type = type)
            return mapOf(
                "success" to true,
                "id" to entry.id,
                "type" to entry.type.name,
                "confidence" to entry.confidence,
                "evidence_count" to entry.evidenceCount,
                "message" to "Insight recorded. It will be surfaced in future conversations when relevant.",
            )
        }
    }

    fun insightSearchTool(insightIndex: InsightIndex) = object : Tool {
        override val schema = ToolSchema(
            name = "insight_search",
            description = "Search accumulated insights and patterns. Returns matching insights with their types and confidence levels. Use this to check if there are relevant patterns or preferences before starting a task.",
            parameters = mapOf(
                "query" to ParameterSchema(type = "string", description = "Search query to find relevant insights", required = true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val query = args["query"]?.toString() ?: return mapOf("success" to false, "error" to "Missing query")
            val results = insightIndex.searchInsights(query)
            if (results.isEmpty()) {
                return mapOf("success" to true, "results" to emptyList<Any>(), "message" to "No insights found matching '$query'")
            }
            return mapOf(
                "success" to true,
                "count" to results.size,
                "results" to results.map { insight ->
                    mapOf(
                        "id" to insight.id,
                        "insight" to insight.insight,
                        "type" to insight.type.name,
                        "confidence" to insight.confidence,
                        "evidence_count" to insight.evidenceCount,
                        "triggered_count" to insight.triggeredCount,
                    )
                },
            )
        }
    }

    fun getMetaLearningTools(skillStore: SkillStore, insightIndex: InsightIndex): List<Tool> = listOf(
        skillEvolveTool(skillStore),
        skillMergeTool(skillStore),
        insightAddTool(insightIndex),
        insightSearchTool(insightIndex),
    )
}
