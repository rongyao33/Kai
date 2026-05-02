package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.Conversation
import com.inspiredandroid.kai.data.ConversationStorage
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object SessionSearchTools {

    val sessionSearchToolInfo = ToolInfo(
        id = "search_conversations",
        name = "Search Conversations",
        description = "Search across all past conversations for relevant information",
    )

    val sessionSearchToolDefinitions = listOf(sessionSearchToolInfo)

    fun sessionSearchTool(conversationStorage: ConversationStorage) = object : Tool {
        override val schema = ToolSchema(
            name = "search_conversations",
            description = "Search across all past conversations for relevant information. Returns matching conversation snippets with context. Use this when you need to recall something discussed in a previous session.",
            parameters = mapOf(
                "query" to ParameterSchema(type = "string", description = "Search query - keywords or phrases to find in past conversations", required = true),
                "limit" to ParameterSchema(type = "integer", description = "Maximum number of results to return (default: 5, max: 10)", required = false),
            ),
        )

        override val timeout: Duration = 15.seconds

        override suspend fun execute(args: Map<String, Any>): Any {
            val query = args["query"]?.toString()?.lowercase()
                ?: return mapOf("success" to false, "error" to "Missing query")
            val limit = (args["limit"]?.toString()?.toIntOrNull()?.coerceIn(1, 10)) ?: 5

            val conversations = conversationStorage.conversations.value
            val results = mutableListOf<Map<String, Any>>()

            for (conv in conversations) {
                if (results.size >= limit) break
                if (conv.type == Conversation.TYPE_HEARTBEAT) continue

                val matchingMessages = conv.messages.filter { msg ->
                    msg.content.lowercase().contains(query)
                }

                if (matchingMessages.isNotEmpty()) {
                    val bestMatch = matchingMessages.first()
                    val contextStart = maxOf(0, conv.messages.indexOf(bestMatch) - 1)
                    val contextEnd = minOf(conv.messages.size, contextStart + 4)
                    val contextMessages = conv.messages.subList(contextStart, contextEnd)
                        .map { msg ->
                            mapOf(
                                "role" to msg.role,
                                "content" to msg.content.take(500),
                            )
                        }

                    results.add(mapOf(
                        "conversation_id" to conv.id,
                        "conversation_title" to (conv.title.ifBlank { "Untitled" }),
                        "conversation_type" to conv.type,
                        "matching_context" to contextMessages,
                        "timestamp" to conv.updatedAt,
                    ))
                }
            }

            return mapOf(
                "success" to true,
                "query" to query,
                "count" to results.size,
                "results" to results,
                "message" to if (results.isEmpty()) "No conversations found matching '$query'" else "Found ${results.size} matching conversation(s)",
            )
        }
    }

    fun getSessionSearchTools(conversationStorage: ConversationStorage): List<Tool> = listOf(
        sessionSearchTool(conversationStorage),
    )
}
