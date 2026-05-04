package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class UserProfile(
    val id: String = generateId(),
    val name: String = "",
    val age: Int? = null,
    val gender: String = "",
    val occupation: String = "",
    val location: String = "",
    val language: String = "zh-CN",
    val timezone: String = "Asia/Shanghai",
    val communicationStyle: CommunicationStyle = CommunicationStyle.FORMAL,
    val interests: List<String> = emptyList(),
    val preferences: UserPreferences = UserPreferences(),
    val behavioralPatterns: BehavioralPatterns = BehavioralPatterns(),
    val goals: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val customAttributes: Map<String, String> = emptyMap(),
    val lastUpdated: Long = System.currentTimeMillis(),
)

@Immutable
@Serializable
enum class CommunicationStyle {
    FORMAL,
    CASUAL,
    TECHNICAL,
    FRIENDLY,
    CONCISE,
}

@Immutable
@Serializable
data class UserPreferences(
    val responseLength: ResponseLength = ResponseLength.MEDIUM,
    val detailLevel: DetailLevel = DetailLevel.MODERATE,
    val tone: String = "professional",
    val notifications: NotificationPreferences = NotificationPreferences(),
    val privacy: PrivacyPreferences = PrivacyPreferences(),
)

@Immutable
@Serializable
enum class ResponseLength {
    VERY_SHORT,
    SHORT,
    MEDIUM,
    LONG,
    VERY_LONG,
}

@Immutable
@Serializable
enum class DetailLevel {
    BRIEF,
    MODERATE,
    COMPREHENSIVE,
}

@Immutable
@Serializable
data class NotificationPreferences(
    val pushEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val heartbeatAlerts: Boolean = true,
    val skillReminders: Boolean = true,
)

@Immutable
@Serializable
data class PrivacyPreferences(
    val shareAnalytics: Boolean = false,
    val saveConversationHistory: Boolean = true,
    val saveMemoryLogs: Boolean = true,
)

@Immutable
@Serializable
data class BehavioralPatterns(
    val peakActivityHours: List<Int> = listOf(9, 10, 11, 14, 15, 16),
    val commonTasks: List<String> = emptyList(),
    val preferredTools: List<String> = emptyList(),
    val averageSessionDuration: Long = 0,
    val interactionFrequency: InteractionFrequency = InteractionFrequency.MODERATE,
    val learningPatterns: List<String> = emptyList(),
)

@Immutable
@Serializable
enum class InteractionFrequency {
    RARE,
    OCCASIONAL,
    MODERATE,
    FREQUENT,
    CONSTANT,
}

private fun generateId(): String = java.util.UUID.randomUUID().toString()
