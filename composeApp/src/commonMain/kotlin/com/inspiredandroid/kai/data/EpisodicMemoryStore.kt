package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import com.inspiredandroid.kai.util.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Serializable
enum class EventType {
    USER_MESSAGE,
    ASSISTANT_MESSAGE,
    TOOL_CALL,
    TOOL_RESULT,
    TASK_START,
    TASK_COMPLETE,
    TASK_FAILED,
    MODE_CHANGE,
    ERROR_OCCURRED,
}

@Serializable
enum class TaskOutcome {
    SUCCESS,
    PARTIAL,
    FAILURE,
    UNKNOWN,
}

@Immutable
@Serializable
data class EpisodicEvent(
    val id: String,
    val conversationId: String,
    val timestamp: Long,
    val eventType: EventType,
    val content: String,
    val summary: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val outcome: TaskOutcome? = null,
    val durationMs: Long? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
)

@OptIn(ExperimentalTime::class)
class EpisodicMemoryStore(private val appSettings: AppSettings) {

    private val json = SharedJson
    private val mutex = Mutex()

    private var cachedEvents: MutableList<EpisodicEvent>? = null
    private var cacheDirty = true

    private fun loadEvents(): MutableList<EpisodicEvent> {
        val cached = cachedEvents
        if (cached != null && !cacheDirty) return cached

        val raw = appSettings.getEpisodicMemoryJson()
        val events = if (raw.isBlank()) {
            mutableListOf()
        } else {
            try {
                json.decodeFromString<List<EpisodicEvent>>(raw).toMutableList()
            } catch (e: Exception) {
                Logger.e("EpisodicMemoryStore", "failed to load events: ${e.message}")
                mutableListOf()
            }
        }
        cachedEvents = events
        cacheDirty = false
        return events
    }

    private fun saveEvents(events: List<EpisodicEvent>) {
        val trimmed = trimOldEvents(events)
        appSettings.setEpisodicMemoryJson(json.encodeToString(trimmed))
        cachedEvents = trimmed.toMutableList()
        cacheDirty = false
    }

    private fun trimOldEvents(events: List<EpisodicEvent>): List<EpisodicEvent> {
        val maxEvents = 1000
        if (events.size <= maxEvents) return events
        return events.sortedByDescending { it.timestamp }.take(maxEvents)
    }

    fun invalidateCache() {
        cacheDirty = true
    }

    suspend fun record(
        conversationId: String,
        eventType: EventType,
        content: String,
        summary: String? = null,
        metadata: Map<String, String> = emptyMap(),
        outcome: TaskOutcome? = null,
        durationMs: Long? = null,
        toolName: String? = null,
        toolArgs: String? = null,
        toolResult: String? = null,
    ): EpisodicEvent = mutex.withLock {
        val events = loadEvents()
        val now = Clock.System.now().toEpochMilliseconds()
        val event = EpisodicEvent(
            id = "ep-${now}-${events.size}",
            conversationId = conversationId,
            timestamp = now,
            eventType = eventType,
            content = content,
            summary = summary,
            metadata = metadata,
            outcome = outcome,
            durationMs = durationMs,
            toolName = toolName,
            toolArgs = toolArgs,
            toolResult = toolResult,
        )
        events.add(event)
        saveEvents(events)
        event
    }

    suspend fun recordToolExecution(
        conversationId: String,
        toolName: String,
        args: String,
        result: String,
        success: Boolean,
        durationMs: Long? = null,
    ): EpisodicEvent = mutex.withLock {
        val events = loadEvents()
        val now = Clock.System.now().toEpochMilliseconds()
        val event = EpisodicEvent(
            id = "ep-tool-${now}",
            conversationId = conversationId,
            timestamp = now,
            eventType = if (success) EventType.TOOL_RESULT else EventType.ERROR_OCCURRED,
            content = "Tool $toolName ${if (success) "completed" else "failed"}",
            summary = toolName,
            outcome = if (success) TaskOutcome.SUCCESS else TaskOutcome.FAILURE,
            durationMs = durationMs,
            toolName = toolName,
            toolArgs = args.take(500),
            toolResult = result.take(1000),
        )
        events.add(event)
        saveEvents(events)
        event
    }

    suspend fun recordTaskStart(
        conversationId: String,
        taskDescription: String,
        metadata: Map<String, String> = emptyMap(),
    ): EpisodicEvent = mutex.withLock {
        val events = loadEvents()
        val now = Clock.System.now().toEpochMilliseconds()
        val event = EpisodicEvent(
            id = "ep-task-${now}",
            conversationId = conversationId,
            timestamp = now,
            eventType = EventType.TASK_START,
            content = taskDescription,
            summary = taskDescription.take(100),
            metadata = metadata,
            outcome = TaskOutcome.UNKNOWN,
        )
        events.add(event)
        saveEvents(events)
        event
    }

    suspend fun recordTaskComplete(
        conversationId: String,
        taskDescription: String,
        outcome: TaskOutcome,
        durationMs: Long? = null,
        metadata: Map<String, String> = emptyMap(),
    ): EpisodicEvent = mutex.withLock {
        val events = loadEvents()
        val now = Clock.System.now().toEpochMilliseconds()
        val event = EpisodicEvent(
            id = "ep-task-${now}",
            conversationId = conversationId,
            timestamp = now,
            eventType = if (outcome == TaskOutcome.SUCCESS) EventType.TASK_COMPLETE else EventType.TASK_FAILED,
            content = taskDescription,
            summary = taskDescription.take(100),
            outcome = outcome,
            durationMs = durationMs,
            metadata = metadata,
        )
        events.add(event)
        saveEvents(events)
        event
    }

    fun getEventsForConversation(conversationId: String): List<EpisodicEvent> =
        loadEvents().filter { it.conversationId == conversationId }
            .sortedByDescending { it.timestamp }

    fun getEventsInTimeRange(
        conversationId: String,
        startTime: Long,
        endTime: Long,
    ): List<EpisodicEvent> = loadEvents()
        .filter {
            it.conversationId == conversationId &&
                it.timestamp >= startTime &&
                it.timestamp <= endTime
        }
        .sortedByDescending { it.timestamp }

    fun getLastNEvents(n: Int): List<EpisodicEvent> =
        loadEvents().sortedByDescending { it.timestamp }.take(n)

    fun getToolExecutions(conversationId: String): List<EpisodicEvent> =
        loadEvents().filter {
            it.conversationId == conversationId &&
                (it.eventType == EventType.TOOL_CALL || it.eventType == EventType.TOOL_RESULT)
        }.sortedByDescending { it.timestamp }

    fun getTaskHistory(conversationId: String): List<EpisodicEvent> =
        loadEvents().filter {
            it.conversationId == conversationId &&
                (it.eventType == EventType.TASK_START ||
                    it.eventType == EventType.TASK_COMPLETE ||
                    it.eventType == EventType.TASK_FAILED)
        }.sortedByDescending { it.timestamp }

    fun getFailedEvents(conversationId: String): List<EpisodicEvent> =
        loadEvents().filter {
            it.conversationId == conversationId &&
                (it.eventType == EventType.ERROR_OCCURRED || it.outcome == TaskOutcome.FAILURE)
        }.sortedByDescending { it.timestamp }

    fun searchEvents(query: String): List<EpisodicEvent> {
        val q = query.lowercase()
        return loadEvents().filter {
            it.content.lowercase().contains(q) ||
                it.summary?.lowercase()?.contains(q) == true ||
                it.toolName?.lowercase()?.contains(q) == true
        }.sortedByDescending { it.timestamp }
    }

    fun getEventStats(): EventStats {
        val events = loadEvents()
        return EventStats(
            totalEvents = events.size,
            byType = events.groupBy { it.eventType }.mapValues { it.value.size },
            byOutcome = events.filter { it.outcome != null }
                .groupBy { it.outcome!! }.mapValues { it.value.size },
            conversationCount = events.map { it.conversationId }.distinct().size,
        )
    }

    suspend fun cleanup(keepRecent: Int = 100): Int = mutex.withLock {
        val events = loadEvents()
        val before = events.size
        val recentCutoff = events.sortedByDescending { it.timestamp }
            .drop(keepRecent)
            .map { it.id }
            .toSet()

        val preserved = events.filter {
            it.eventType == EventType.TASK_START ||
                it.eventType == EventType.TASK_COMPLETE ||
                it.eventType == EventType.TASK_FAILED ||
                it.id !in recentCutoff
        }
        saveEvents(preserved)
        before - preserved.size
    }

    suspend fun clearAll(): Int = mutex.withLock {
        val events = loadEvents()
        val count = events.size
        saveEvents(emptyList())
        count
    }

    suspend fun clearConversation(conversationId: String): Int = mutex.withLock {
        val events = loadEvents()
        val before = events.size
        val remaining = events.filter { it.conversationId != conversationId }
        saveEvents(remaining)
        before - remaining.size
    }
}

data class EventStats(
    val totalEvents: Int,
    val byType: Map<EventType, Int>,
    val byOutcome: Map<TaskOutcome, Int>,
    val conversationCount: Int,
)
