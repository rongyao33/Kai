package com.inspiredandroid.kai.tools

import androidx.compose.runtime.Immutable
import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.ChangeType
import com.inspiredandroid.kai.data.ConfigChange
import com.inspiredandroid.kai.data.ContentDeduplication
import com.inspiredandroid.kai.data.EditableSection
import com.inspiredandroid.kai.data.FeatureFlags
import com.inspiredandroid.kai.data.SharedJson
import com.inspiredandroid.kai.data.SystemConfig
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema

@Immutable
data class SystemConfigTools(
    private val appSettings: AppSettings,
) {
    fun getToolInfos(): List<ToolInfo> = listOf(
        ToolInfo(
            id = "edit_system_section",
            name = "Edit System Section",
            description = "Create or update a custom system prompt section",
        ),
        ToolInfo(
            id = "toggle_feature",
            name = "Toggle Feature",
            description = "Enable or disable a system feature flag",
        ),
        ToolInfo(
            id = "get_system_config",
            name = "Get System Config",
            description = "View current system configuration and feature flags",
        ),
        ToolInfo(
            id = "list_sections",
            name = "List Sections",
            description = "List all editable system sections",
        ),
        ToolInfo(
            id = "reset_section",
            name = "Reset Section",
            description = "Reset a section to its default content",
        ),
        ToolInfo(
            id = "get_change_history",
            name = "Get Change History",
            description = "View history of configuration changes",
        ),
    )

    fun getToolObjects(): List<Tool> = listOf(
        editSystemSectionTool(),
        toggleFeatureTool(),
        getSystemConfigTool(),
        listSectionsTool(),
        resetSectionTool(),
        getChangeHistoryTool(),
    )

    private fun editSystemSectionTool() = object : Tool {
        override val schema = ToolSchema(
            name = "edit_system_section",
            description = "Create or update a custom system prompt section. Sections allow you to add custom guidance that persists across conversations.",
            parameters = mapOf(
                "section_id" to ParameterSchema("string", "Unique identifier for the section (e.g., 'custom_rules', 'domain_knowledge')", true),
                "name" to ParameterSchema("string", "Human-readable name for the section", true),
                "content" to ParameterSchema("string", "The content/markdown for this section", true),
            ),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            val sectionId = args["section_id"]?.toString()
                ?: return mapOf("success" to false, "error" to "Missing section_id")
            val name = args["name"]?.toString()
                ?: return mapOf("success" to false, "error" to "Missing name")
            val content = args["content"]?.toString()
                ?: return mapOf("success" to false, "error" to "Missing content")
            return editSection(sectionId, name, content)
        }
    }

    private fun toggleFeatureTool() = object : Tool {
        override val schema = ToolSchema(
            name = "toggle_feature",
            description = "Enable or disable a system feature flag",
            parameters = mapOf(
                "feature" to ParameterSchema("string", "Feature name: enableReflexion, enableKnowledgeGraph, enableTaskDecomposition, enableDeepResearch, enableSoulLearning, enableFileOutput", true),
                "enabled" to ParameterSchema("boolean", "true to enable, false to disable", true),
            ),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            val feature = args["feature"]?.toString()
                ?: return mapOf("success" to false, "error" to "Missing feature")
            val enabled = args["enabled"]?.toString()?.toBooleanStrictOrNull()
                ?: return mapOf("success" to false, "error" to "Missing enabled")
            return toggleFeature(feature, enabled)
        }
    }

    private fun getSystemConfigTool() = object : Tool {
        override val schema = ToolSchema(
            name = "get_system_config",
            description = "View current system configuration including feature flags and custom sections",
            parameters = emptyMap(),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            return getConfig()
        }
    }

    private fun listSectionsTool() = object : Tool {
        override val schema = ToolSchema(
            name = "list_sections",
            description = "List all editable system sections with their current content",
            parameters = emptyMap(),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            return listSections()
        }
    }

    private fun resetSectionTool() = object : Tool {
        override val schema = ToolSchema(
            name = "reset_section",
            description = "Reset a section to its default content",
            parameters = mapOf(
                "section_id" to ParameterSchema("string", "Section ID to reset", true),
            ),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            val sectionId = args["section_id"]?.toString()
                ?: return mapOf("success" to false, "error" to "Missing section_id")
            return resetSection(sectionId)
        }
    }

    private fun getChangeHistoryTool() = object : Tool {
        override val schema = ToolSchema(
            name = "get_change_history",
            description = "View history of configuration changes",
            parameters = mapOf(
                "limit" to ParameterSchema("integer", "Maximum number of entries to return (default 20)", false),
            ),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            val limit = args["limit"]?.toString()?.toIntOrNull() ?: 20
            return getChangeHistory(limit)
        }
    }

    private fun getSystemConfig(): SystemConfig {
        val json = appSettings.getSystemConfigJson()
        return if (json.isNotBlank()) {
            try {
                SharedJson.decodeFromString<SystemConfig>(json)
            } catch (e: Exception) {
                SystemConfig()
            }
        } else {
            SystemConfig()
        }
    }

    private fun saveSystemConfig(config: SystemConfig) {
        val json = SharedJson.encodeToString(config)
        appSettings.setSystemConfigJson(json)
    }

    private fun editSection(sectionId: String, name: String, content: String): Map<String, Any> {
        if (sectionId.contains(".") || sectionId.contains("/")) {
            return mapOf("success" to false, "error" to "Section ID cannot contain '.' or '/'")
        }
        if (content.length > 10000) {
            return mapOf("success" to false, "error" to "Content exceeds maximum length of 10000 characters")
        }

        val config = getSystemConfig()

        // Check for duplicates against Soul
        val soulText = appSettings.getSoulText()
        if (soulText.isNotBlank()) {
            val soulSimilarity = ContentDeduplication.similarity(
                ContentDeduplication.normalize(content),
                ContentDeduplication.normalize(soulText)
            )
            if (soulSimilarity >= 0.8) {
                return mapOf(
                    "success" to false,
                    "error" to "Content is too similar to Soul",
                    "similarity" to soulSimilarity,
                    "suggestion" to "Content already exists in Soul. Use promote_learning to update Soul instead, or choose different content."
                )
            }
        }

        // Check for duplicates against other custom sections
        for ((otherId, otherSection) in config.editableSections) {
            if (otherId == sectionId) continue
            val sectionSimilarity = ContentDeduplication.similarity(
                ContentDeduplication.normalize(content),
                ContentDeduplication.normalize(otherSection.content)
            )
            if (sectionSimilarity >= 0.8) {
                return mapOf(
                    "success" to false,
                    "error" to "Content is too similar to existing section: ${otherSection.name}",
                    "similarity" to sectionSimilarity,
                    "suggestion" to "Update the existing section '${otherSection.name}' instead, or use different content."
                )
            }
        }

        val existing = config.editableSections[sectionId]
        val change = ConfigChange(
            changeType = if (existing != null) ChangeType.SECTION_UPDATED else ChangeType.SECTION_CREATED,
            targetId = sectionId,
            previousValue = existing?.content ?: "",
            newValue = content,
            toolName = "edit_system_section",
        )
        val section = EditableSection(
            id = sectionId,
            name = name,
            content = content,
            defaultContent = existing?.defaultContent ?: content,
        )
        val updated = config.copy(
            editableSections = config.editableSections + (sectionId to section),
            versionHistory = config.versionHistory + change,
        )
        saveSystemConfig(updated)
        return mapOf(
            "success" to true,
            "output" to "Section '$sectionId' ${if (existing != null) "updated" else "created"}"
        )
    }

    private fun toggleFeature(feature: String, enabled: Boolean): Map<String, Any> {
        val validFeatures = setOf(
            "enableReflexion",
            "enableKnowledgeGraph",
            "enableTaskDecomposition",
            "enableDeepResearch",
            "enableSoulLearning",
            "enableFileOutput",
        )
        if (feature !in validFeatures) {
            return mapOf("success" to false, "error" to "Unknown feature: $feature. Valid: ${validFeatures.joinToString()}")
        }

        val config = getSystemConfig()
        val currentFlags = config.featureFlags
        val newFlags = when (feature) {
            "enableReflexion" -> currentFlags.copy(enableReflexion = enabled)
            "enableKnowledgeGraph" -> currentFlags.copy(enableKnowledgeGraph = enabled)
            "enableTaskDecomposition" -> currentFlags.copy(enableTaskDecomposition = enabled)
            "enableDeepResearch" -> currentFlags.copy(enableDeepResearch = enabled)
            "enableSoulLearning" -> currentFlags.copy(enableSoulLearning = enabled)
            "enableFileOutput" -> currentFlags.copy(enableFileOutput = enabled)
            else -> return mapOf("success" to false, "error" to "Unhandled feature: $feature")
        }
        val change = ConfigChange(
            changeType = ChangeType.FEATURE_TOGGLED,
            targetId = feature,
            previousValue = when (feature) {
                "enableReflexion" -> currentFlags.enableReflexion.toString()
                "enableKnowledgeGraph" -> currentFlags.enableKnowledgeGraph.toString()
                "enableTaskDecomposition" -> currentFlags.enableTaskDecomposition.toString()
                "enableDeepResearch" -> currentFlags.enableDeepResearch.toString()
                "enableSoulLearning" -> currentFlags.enableSoulLearning.toString()
                "enableFileOutput" -> currentFlags.enableFileOutput.toString()
                else -> "unknown"
            },
            newValue = enabled.toString(),
            toolName = "toggle_feature",
        )
        val updated = config.copy(
            featureFlags = newFlags,
            versionHistory = config.versionHistory + change,
        )
        saveSystemConfig(updated)
        return mapOf("success" to true, "output" to "$feature is now ${if (enabled) "enabled" else "disabled"}")
    }

    private fun getConfig(): Map<String, Any> {
        val config = getSystemConfig()
        val sections = config.editableSections.mapValues { it.value.content }
        return mapOf(
            "success" to true,
            "featureFlags" to mapOf(
                "enableReflexion" to config.featureFlags.enableReflexion,
                "enableKnowledgeGraph" to config.featureFlags.enableKnowledgeGraph,
                "enableTaskDecomposition" to config.featureFlags.enableTaskDecomposition,
                "enableDeepResearch" to config.featureFlags.enableDeepResearch,
                "enableSoulLearning" to config.featureFlags.enableSoulLearning,
                "enableFileOutput" to config.featureFlags.enableFileOutput,
            ),
            "customSectionsCount" to config.editableSections.size,
            "customSections" to sections,
        )
    }

    private fun listSections(): Map<String, Any> {
        val config = getSystemConfig()
        val sections = config.editableSections.values.map { section ->
            mapOf(
                "id" to section.id,
                "name" to section.name,
                "content" to section.content,
                "lastModified" to section.lastModified,
            )
        }
        return mapOf(
            "success" to true,
            "sections" to sections,
            "count" to sections.size,
        )
    }

    private fun resetSection(sectionId: String): Map<String, Any> {
        val config = getSystemConfig()
        val section = config.editableSections[sectionId]
            ?: return mapOf("success" to false, "error" to "Section not found: $sectionId")

        if (section.defaultContent.isEmpty()) {
            return mapOf("success" to false, "error" to "No default content to reset to")
        }

        val change = ConfigChange(
            changeType = ChangeType.SECTION_DELETED,
            targetId = sectionId,
            previousValue = section.content,
            newValue = "",
            toolName = "reset_section",
        )
        val updatedSections = config.editableSections - sectionId
        val updated = config.copy(
            editableSections = updatedSections,
            versionHistory = config.versionHistory + change,
        )
        saveSystemConfig(updated)
        return mapOf("success" to true, "output" to "Section '$sectionId' has been removed")
    }

    private fun getChangeHistory(limit: Int): Map<String, Any> {
        val config = getSystemConfig()
        val history = config.versionHistory
            .sortedByDescending { it.timestamp }
            .take(limit)
            .map { change ->
                mapOf(
                    "timestamp" to change.timestamp,
                    "type" to change.changeType.name,
                    "target" to change.targetId,
                    "previous" to change.previousValue.take(100),
                    "new" to change.newValue.take(100),
                    "tool" to change.toolName,
                )
            }
        return mapOf(
            "success" to true,
            "history" to history,
            "count" to history.size,
        )
    }
}
