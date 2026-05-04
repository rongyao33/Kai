package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class SystemConfig(
    val editableSections: Map<String, EditableSection> = emptyMap(),
    val featureFlags: FeatureFlags = FeatureFlags(),
    val versionHistory: List<ConfigChange> = emptyList(),
)

@Immutable
@Serializable
data class EditableSection(
    val id: String,
    val name: String,
    val content: String,
    val defaultContent: String,
    val lastModified: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
)

@Immutable
@Serializable
data class FeatureFlags(
    val enableReflexion: Boolean = true,
    val enableKnowledgeGraph: Boolean = true,
    val enableTaskDecomposition: Boolean = true,
    val enableDeepResearch: Boolean = true,
    val maxConcurrentTools: Int = 3,
    val memoryRetentionDays: Int = 30,
    val enableSoulLearning: Boolean = true,
    val enableFileOutput: Boolean = true,
)

@Immutable
@Serializable
data class ConfigChange(
    val id: String = generateId(),
    val timestamp: Long = System.currentTimeMillis(),
    val changeType: ChangeType,
    val targetId: String,
    val previousValue: String,
    val newValue: String,
    val toolName: String,
)

@Immutable
@Serializable
enum class ChangeType {
    SECTION_CREATED,
    SECTION_UPDATED,
    SECTION_DELETED,
    FEATURE_TOGGLED,
    CONFIG_RESET,
}

private fun generateId(): String = java.util.UUID.randomUUID().toString()
