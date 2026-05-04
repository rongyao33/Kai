package com.inspiredandroid.kai.mcp

import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class McpTool(
    private val client: McpClient,
    private val metadata: McpToolMetadata,
) : Tool {

    override val schema: ToolSchema = ToolSchema(
        name = metadata.name,
        description = metadata.description,
        parameters = convertInputSchema(metadata.inputSchema),
    )

    override val timeout: Duration = 60.seconds

    override suspend fun execute(args: Map<String, Any>): Any {
        val jsonArgs = buildJsonObject {
            for ((key, value) in args) {
                put(key, anyToJsonElement(value))
            }
        }
        return try {
            val result = client.callTool(metadata.name, jsonArgs)
            mapOf("success" to true, "result" to result)
        } catch (e: Exception) {
            mapOf("success" to false, "error" to (e.message ?: "MCP tool call failed"))
        }
    }

    companion object {
        fun toolId(serverId: String, toolName: String): String = "mcp_${serverId}_$toolName"

        private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
            null -> JsonPrimitive(null)
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Double -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value.toDouble())
            is Map<*, *> -> buildJsonObject {
                for ((k, v) in value) {
                    if (k != null) put(k.toString(), anyToJsonElement(v))
                }
            }
            is List<*> -> buildJsonArray {
                for (item in value) add(anyToJsonElement(item))
            }
            else -> JsonPrimitive(value.toString())
        }

        fun convertInputSchema(inputSchema: JsonObject?): Map<String, ParameterSchema> {
            if (inputSchema == null) return emptyMap()
            val properties = inputSchema["properties"]?.jsonObject ?: return emptyMap()
            val required = try {
                inputSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
            } catch (_: Exception) {
                emptySet()
            }

            return buildMap {
                for ((name, prop) in properties) {
                    try {
                        val propObj = prop.jsonObject
                        val type = propObj["type"]?.jsonPrimitive?.content ?: "string"
                        val description = propObj["description"]?.jsonPrimitive?.content ?: ""
                        put(name, ParameterSchema(type, description, name in required, rawSchema = propObj))
                    } catch (_: Exception) {
                        // Skip malformed properties
                    }
                }
            }
        }
    }
}
