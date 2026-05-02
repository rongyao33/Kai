package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.SkillCategory
import com.inspiredandroid.kai.data.SkillEntry
import com.inspiredandroid.kai.data.SkillStore
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema

object SkillTools {

    val skillCreateToolInfo = ToolInfo(
        id = "skill_create",
        name = "Create Skill",
        description = "Create a reusable skill from experience",
    )

    val skillSearchToolInfo = ToolInfo(
        id = "skill_search",
        name = "Search Skills",
        description = "Search stored skills by keyword",
    )

    val skillListToolInfo = ToolInfo(
        id = "skill_list",
        name = "List Skills",
        description = "List all stored skills",
    )

    val skillDeleteToolInfo = ToolInfo(
        id = "skill_delete",
        name = "Delete Skill",
        description = "Delete a stored skill",
    )

    val skillToolDefinitions = listOf(
        skillCreateToolInfo,
        skillSearchToolInfo,
        skillListToolInfo,
        skillDeleteToolInfo,
    )

    fun skillCreateTool(skillStore: SkillStore) = object : Tool {
        override val schema = ToolSchema(
            name = "skill_create",
            description = "Create a reusable skill document from a completed task or learned workflow. Skills are automatically loaded in future conversations when similar tasks arise. Use this after completing complex multi-step tasks (5+ tool calls) to crystallize the approach for future reuse.",
            parameters = mapOf(
                "name" to ParameterSchema(type = "string", description = "Short descriptive name for the skill (e.g. 'deploy_to_vercel', 'debug_python_imports')", required = true),
                "description" to ParameterSchema(type = "string", description = "Brief description of what this skill accomplishes", required = true),
                "content" to ParameterSchema(type = "string", description = "Detailed step-by-step instructions or procedure. Include specific commands, API calls, or decision trees that were used.", required = true),
                "category" to ParameterSchema(type = "string", description = "Category: WORKFLOW (multi-step process), TROUBLESHOOTING (debugging/fixing), REFERENCE (lookup info), AUTOMATION (automated task)", required = false),
                "tags" to ParameterSchema(type = "string", description = "Comma-separated tags for discoverability (e.g. 'python,debug,imports')", required = false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val name = args["name"]?.toString() ?: return mapOf("success" to false, "error" to "Missing name")
            val description = args["description"]?.toString() ?: return mapOf("success" to false, "error" to "Missing description")
            val content = args["content"]?.toString() ?: return mapOf("success" to false, "error" to "Missing content")
            val categoryStr = args["category"]?.toString()?.uppercase() ?: "WORKFLOW"
            val tagsStr = args["tags"]?.toString() ?: ""

            val category = try {
                SkillCategory.valueOf(categoryStr)
            } catch (_: Exception) {
                SkillCategory.WORKFLOW
            }

            val tags = if (tagsStr.isNotBlank()) tagsStr.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() } else emptyList()

            val entry = skillStore.create(
                name = name,
                description = description,
                content = content,
                category = category,
                autoCreated = false,
                tags = tags,
            )
            return mapOf(
                "success" to true,
                "id" to entry.id,
                "name" to entry.name,
                "category" to entry.category.name,
                "message" to "Skill '$name' created successfully. It will be available in future conversations.",
            )
        }
    }

    fun skillSearchTool(skillStore: SkillStore) = object : Tool {
        override val schema = ToolSchema(
            name = "skill_search",
            description = "Search stored skills by keyword. Returns matching skills with their descriptions and content. Use this before attempting a complex task to check if a similar workflow has been solved before.",
            parameters = mapOf(
                "query" to ParameterSchema(type = "string", description = "Search query to find relevant skills", required = true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val query = args["query"]?.toString() ?: return mapOf("success" to false, "error" to "Missing query")
            val results = skillStore.searchSkills(query)
            if (results.isEmpty()) {
                return mapOf("success" to true, "results" to emptyList<Any>(), "message" to "No skills found matching '$query'")
            }
            return mapOf(
                "success" to true,
                "count" to results.size,
                "results" to results.map { skill: SkillEntry ->
                    mapOf(
                        "id" to skill.id,
                        "name" to skill.name,
                        "description" to skill.description,
                        "category" to skill.category.name,
                        "content" to skill.content,
                        "use_count" to skill.useCount,
                        "tags" to skill.tags,
                    )
                },
            )
        }
    }

    fun skillListTool(skillStore: SkillStore) = object : Tool {
        override val schema = ToolSchema(
            name = "skill_list",
            description = "List all stored skills, optionally filtered by category. Returns skill names, descriptions, and usage counts.",
            parameters = mapOf(
                "category" to ParameterSchema(type = "string", description = "Optional filter: WORKFLOW, TROUBLESHOOTING, REFERENCE, or AUTOMATION", required = false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val categoryStr = args["category"]?.toString()?.uppercase()
            val skills = if (categoryStr != null) {
                val category = try {
                    SkillCategory.valueOf(categoryStr)
                } catch (_: Exception) {
                    return mapOf("success" to false, "error" to "Invalid category: $categoryStr")
                }
                skillStore.getSkillsByCategory(category)
            } else {
                skillStore.getAllSkills()
            }
            if (skills.isEmpty()) {
                return mapOf("success" to true, "skills" to emptyList<Any>(), "message" to "No skills stored yet")
            }
            return mapOf(
                "success" to true,
                "count" to skills.size,
                "skills" to skills.map { skill: SkillEntry ->
                    mapOf(
                        "id" to skill.id,
                        "name" to skill.name,
                        "description" to skill.description,
                        "category" to skill.category.name,
                        "use_count" to skill.useCount,
                        "auto_created" to skill.autoCreated,
                        "tags" to skill.tags,
                    )
                },
            )
        }
    }

    fun skillDeleteTool(skillStore: SkillStore) = object : Tool {
        override val schema = ToolSchema(
            name = "skill_delete",
            description = "Delete a stored skill by its ID. Use skill_list or skill_search to find the ID first.",
            parameters = mapOf(
                "id" to ParameterSchema(type = "string", description = "The ID of the skill to delete", required = true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val id = args["id"]?.toString() ?: return mapOf("success" to false, "error" to "Missing id")
            val removed = skillStore.delete(id)
            return mapOf("success" to removed, "id" to id, "message" to if (removed) "Skill deleted" else "Skill not found")
        }
    }

    fun getSkillTools(skillStore: SkillStore): List<Tool> = listOf(
        skillCreateTool(skillStore),
        skillSearchTool(skillStore),
        skillListTool(skillStore),
        skillDeleteTool(skillStore),
    )
}
