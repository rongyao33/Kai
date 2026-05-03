package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.getAvailableTools
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_parallel_execute_description
import kai.composeapp.generated.resources.tool_parallel_execute_name

private const val TOOL_DESCRIPTION = """Execute multiple tools in parallel (up to 3 simultaneously). This enables faster task completion when tools don't depend on each other.

Parameters:
- calls: Array of tool calls to execute in parallel (max 3)
  - Each call has: tool_name, arguments

Example:
{
  "calls": [
    {"tool_name": "web_search", "arguments": {"query": "weather today"}},
    {"tool_name": "get_location_from_ip", "arguments": {}},
    {"tool_name": "deep_research", "arguments": {"query": "AI trends 2024", "depth": "quick"}}
  ]
}

Returns:
- results: Array of results in the same order as input calls
- success_count: Number of successful calls
- error_count: Number of failed calls
- total_time_ms: Total execution time

Use this when you need to:
- Gather information from multiple sources simultaneously
- Perform independent operations in parallel
- Speed up multi-step workflows"""

@Serializable
data class ToolCall(
    val tool_name: String,
    val arguments: Map<String, Any> = emptyMap(),
)

@Serializable
data class ToolResult(
    val tool_name: String,
    val success: Boolean,
    val result: Any?,
    val error: String? = null,
    val duration_ms: Long,
)

object ParallelExecuteTool : Tool {
    override val schema = ToolSchema(
        name = "parallel_execute",
        description = TOOL_DESCRIPTION,
        parameters = mapOf(
            "calls" to ParameterSchema(
                "array",
                "Array of tool calls to execute in parallel (max 3). Each call has tool_name and arguments.",
                true,
            ),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        @Suppress("UNCHECKED_CAST")
        val callsRaw = args["calls"] as? List<Map<String, Any>> ?: return mapOf(
            "success" to false,
            "error" to "calls parameter is required and must be an array",
        )

        if (callsRaw.isEmpty()) {
            return mapOf(
                "success" to false,
                "error" to "calls array cannot be empty",
            )
        }

        if (callsRaw.size > 3) {
            return mapOf(
                "success" to false,
                "error" to "Maximum 3 parallel calls allowed, got ${callsRaw.size}",
            )
        }

        val calls = callsRaw.map { call ->
            ToolCall(
                tool_name = call["tool_name"] as? String ?: "",
                arguments = (call["arguments"] as? Map<String, Any>) ?: emptyMap(),
            )
        }

        val invalidTools = calls.filter { it.tool_name.isBlank() }
        if (invalidTools.isNotEmpty()) {
            return mapOf(
                "success" to false,
                "error" to "All calls must have a valid tool_name",
            )
        }

        val tools = getAvailableTools()
        val missingTools = calls.map { it.tool_name }.filter { name ->
            tools.none { it.schema.name == name }
        }

        if (missingTools.isNotEmpty()) {
            return mapOf(
                "success" to false,
                "error" to "Unknown tools: ${missingTools.joinToString(", ")}",
            )
        }

        val startTime = System.currentTimeMillis()

        return try {
            val results = coroutineScope {
                calls.map { call ->
                    async { executeSingleTool(call, tools) }
                }.awaitAll()
            }

            val totalTime = System.currentTimeMillis() - startTime
            val successCount = results.count { it.success }
            val errorCount = results.count { !it.success }

            mapOf(
                "success" to true,
                "results" to results.map { result ->
                    mapOf(
                        "tool_name" to result.tool_name,
                        "success" to result.success,
                        "result" to result.result,
                        "error" to result.error,
                        "duration_ms" to result.duration_ms,
                    )
                },
                "success_count" to successCount,
                "error_count" to errorCount,
                "total_time_ms" to totalTime,
                "parallel_efficiency" to "${calls.size} tools executed in ${totalTime}ms",
            )
        } catch (e: Exception) {
            mapOf(
                "success" to false,
                "error" to "Parallel execution failed: ${e.message}",
            )
        }
    }

    private suspend fun executeSingleTool(call: ToolCall, tools: List<Tool>): ToolResult {
        val tool = tools.find { it.schema.name == call.tool_name }!!
        val startTime = System.currentTimeMillis()

        return try {
            val result = tool.execute(call.arguments)
            ToolResult(
                tool_name = call.tool_name,
                success = true,
                result = result,
                duration_ms = System.currentTimeMillis() - startTime,
            )
        } catch (e: Exception) {
            ToolResult(
                tool_name = call.tool_name,
                success = false,
                result = null,
                error = e.message ?: "Unknown error",
                duration_ms = System.currentTimeMillis() - startTime,
            )
        }
    }

    val toolInfo = ToolInfo(
        id = "parallel_execute",
        name = "Parallel Execute",
        description = "Execute up to 3 tools simultaneously for faster task completion",
        nameRes = Res.string.tool_parallel_execute_name,
        descriptionRes = Res.string.tool_parallel_execute_description,
        isEnabled = true,
    )
}
