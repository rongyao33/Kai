package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.MemoryStore
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema

object MemorySearchTools {

    val memorySearchToolInfo = ToolInfo(
        id = "memory_search",
        name = "Search Memories",
        description = "Search stored memories by keyword",
    )

    val memorySearchToolDefinitions = listOf(memorySearchToolInfo)

    fun memorySearchTool(memoryStore: MemoryStore) = object : Tool {
        override val schema = ToolSchema(
            name = "memory_search",
            description = "Search stored memories by keyword. Returns matching memories with their keys, content, categories, and hit counts. Use this to quickly find relevant memories before storing duplicates.",
            parameters = mapOf(
                "query" to ParameterSchema(type = "string", description = "Search query - keywords to find in memory keys or content", required = true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val query = args["query"]?.toString()?.lowercase()
                ?: return mapOf("success" to false, "error" to "Missing query")

            val allMemories = memoryStore.getAllMemories()
            val results = allMemories.filter { memory ->
                memory.key.lowercase().contains(query) ||
                    memory.content.lowercase().contains(query)
            }

            if (results.isEmpty()) {
                return mapOf(
                    "success" to true,
                    "count" to 0,
                    "results" to emptyList<Any>(),
                    "message" to "No memories found matching '$query'",
                )
            }

            return mapOf(
                "success" to true,
                "count" to results.size,
                "results" to results.map { m ->
                    mapOf(
                        "key" to m.key,
                        "content" to m.content,
                        "category" to m.category.name,
                        "hit_count" to m.hitCount,
                        "source" to m.source,
                    )
                },
            )
        }
    }

    fun getMemorySearchTools(memoryStore: MemoryStore): List<Tool> = listOf(
        memorySearchTool(memoryStore),
    )
}
