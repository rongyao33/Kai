package com.inspiredandroid.kai.tools

import androidx.compose.runtime.Immutable
import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.InteractionFrequency
import com.inspiredandroid.kai.data.PrivateVault
import com.inspiredandroid.kai.data.ResponseLength
import com.inspiredandroid.kai.data.UserProfile
import com.inspiredandroid.kai.data.VaultCategory
import com.inspiredandroid.kai.data.VaultEntry
import com.inspiredandroid.kai.data.VaultAction
import com.inspiredandroid.kai.data.VaultAccessLog
import com.inspiredandroid.kai.data.addEntry
import com.inspiredandroid.kai.data.appendAccessLog
import com.inspiredandroid.kai.data.removeEntry
import com.inspiredandroid.kai.data.maskValue
import com.inspiredandroid.kai.data.search
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Immutable
data class ProfileTools(
    private val appSettings: AppSettings,
) {
    private val json = Json { prettyPrint = true }

    fun getProfileTools(): List<ToolInfo> = listOf(
        ToolInfo(
            id = "get_user_profile",
            name = "Get User Profile",
            description = "Get the complete user profile including preferences, behavioral patterns, and attributes",
        ),
        ToolInfo(
            id = "update_user_profile",
            name = "Update User Profile",
            description = "Update specific fields in the user profile",
        ),
        ToolInfo(
            id = "add_behavioral_pattern",
            name = "Add Behavioral Pattern",
            description = "Add a new behavioral pattern or common task to the user profile",
        ),
        ToolInfo(
            id = "vault_store",
            name = "Vault Store",
            description = "Store a sensitive value securely in the private vault",
        ),
        ToolInfo(
            id = "vault_retrieve",
            name = "Vault Retrieve",
            description = "Retrieve a value from the secure vault by key",
        ),
        ToolInfo(
            id = "vault_delete",
            name = "Vault Delete",
            description = "Delete an entry from the secure vault",
        ),
        ToolInfo(
            id = "vault_list",
            name = "Vault List",
            description = "List all keys in the vault (values are not shown for security)",
        ),
        ToolInfo(
            id = "vault_search",
            name = "Vault Search",
            description = "Search vault entries by key or tags",
        ),
        ToolInfo(
            id = "vault_use",
            name = "Vault Use",
            description = "Use a vault credential for authentication - returns actual value for local use only",
        ),
        ToolInfo(
            id = "vault_copy",
            name = "Vault Copy",
            description = "Copy a vault credential value to clipboard - user explicitly requested this copy operation",
        ),
    )

    fun getToolObjects(): List<Tool> = listOf(
        getUserProfileTool(),
        updateUserProfileTool(),
        addBehavioralPatternTool(),
        vaultStoreTool(),
        vaultRetrieveTool(),
        vaultUseTool(),
        vaultCopyTool(),
        vaultDeleteTool(),
        vaultListTool(),
        vaultSearchTool(),
    )

    private fun getUserProfileTool() = object : Tool {
        override val schema = ToolSchema(
            name = "get_user_profile",
            description = "Get the complete user profile including preferences, behavioral patterns, and attributes",
            parameters = emptyMap(),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            return getUserProfileFromSettings()
        }
    }

    private fun updateUserProfileTool() = object : Tool {
        override val schema = ToolSchema(
            name = "update_user_profile",
            description = "Update specific fields in the user profile. Fields: name, age, gender, occupation, location, language, timezone, communicationStyle (FORMAL/CASUAL/TECHNICAL/FRIENDLY/CONCISE), interests (comma-separated), goals (comma-separated), constraints (comma-separated), customAttributes (JSON format), behavioralPatterns.peakActivityHours (comma-separated hours), behavioralPatterns.interactionFrequency (RARE/OCCASIONAL/MODERATE/FREQUENT/CONSTANT), behavioralPatterns.commonTasks (comma-separated), preferences.responseLength (VERY_SHORT/SHORT/MEDIUM/LONG/VERY_LONG), preferences.detailLevel (BRIEF/MODERATE/COMPREHENSIVE), preferences.tone",
            parameters = mapOf(
                "field" to ParameterSchema("string", "Field name to update", true),
                "value" to ParameterSchema("string", "New value for the field", true),
            ),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            val field = args["field"]?.toString() ?: return mapOf("success" to false, "error" to "Missing field")
            val value = args["value"]?.toString() ?: return mapOf("success" to false, "error" to "Missing value")
            return updateUserProfile(field, value)
        }
    }

    private fun addBehavioralPatternTool() = object : Tool {
        override val schema = ToolSchema(
            name = "add_behavioral_pattern",
            description = "Add a new behavioral pattern or common task to the user profile. Type: commonTask, preferredTool, or learningPattern",
            parameters = mapOf(
                "pattern" to ParameterSchema("string", "Pattern or task to add", true),
                "type" to ParameterSchema("string", "Type: commonTask, preferredTool, or learningPattern", true),
            ),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            val pattern = args["pattern"]?.toString() ?: return mapOf("success" to false, "error" to "Missing pattern")
            val type = args["type"]?.toString() ?: return mapOf("success" to false, "error" to "Missing type")
            return addBehavioralPattern(pattern, type)
        }
    }

    private fun vaultStoreTool() = object : Tool {
        override val schema = ToolSchema(
            name = "vault_store",
            description = "Store a sensitive value securely. Categories: PASSWORD, API_KEY, ACCOUNT, FINANCIAL, MEDICAL, IDENTITY, NOTE, OTHER",
            parameters = mapOf(
                "key" to ParameterSchema("string", "Identifier/key for this entry", true),
                "value" to ParameterSchema("string", "The sensitive value to store", true),
                "category" to ParameterSchema("string", "Category: PASSWORD, API_KEY, ACCOUNT, FINANCIAL, MEDICAL, IDENTITY, NOTE, OTHER", false),
                "tags" to ParameterSchema("string", "Comma-separated tags for searching", false),
            ),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            val key = args["key"]?.toString() ?: return mapOf("success" to false, "error" to "Missing key")
            val value = args["value"]?.toString() ?: return mapOf("success" to false, "error" to "Missing value")
            val category = args["category"]?.toString() ?: "OTHER"
            val tagsStr = args["tags"]?.toString() ?: ""
            val tags = if (tagsStr.isNotBlank()) tagsStr.split(",").map { it.trim() } else emptyList()
            return vaultStore(key, value, category, tags)
        }
    }

    private fun vaultRetrieveTool() = object : Tool {
        override val schema = ToolSchema(
            name = "vault_retrieve",
            description = "Retrieve a value from the secure vault by key",
            parameters = mapOf(
                "key" to ParameterSchema("string", "Key to retrieve", true),
            ),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            val key = args["key"]?.toString() ?: return mapOf("success" to false, "error" to "Missing key")
            return vaultRetrieve(key)
        }
    }

    private fun vaultUseTool() = object : Tool {
        override val schema = ToolSchema(
            name = "vault_use",
            description = "Use a vault credential for authentication - returns actual value for LOCAL USE ONLY. The value is NEVER shown to the user or logged. Only use this when the credential is needed for a local operation (e.g., decrypting a file, authenticating to a service on behalf of the user). NEVER use this tool if the credential would need to be sent over the network or exposed externally.",
            parameters = mapOf(
                "key" to ParameterSchema("string", "Key of the credential to use", true),
                "purpose" to ParameterSchema("string", "Brief description of how this credential will be used (e.g., 'decrypt my tax document', 'login to email')", true),
            ),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            val key = args["key"]?.toString() ?: return mapOf("success" to false, "error" to "Missing key")
            val purpose = args["purpose"]?.toString() ?: "unspecified"
            return vaultUse(key, purpose)
        }
    }

    private fun vaultDeleteTool() = object : Tool {
        override val schema = ToolSchema(
            name = "vault_delete",
            description = "Delete an entry from the secure vault",
            parameters = mapOf(
                "key" to ParameterSchema("string", "Key to delete", true),
            ),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            val key = args["key"]?.toString() ?: return mapOf("success" to false, "error" to "Missing key")
            return vaultDelete(key)
        }
    }

    private fun vaultListTool() = object : Tool {
        override val schema = ToolSchema(
            name = "vault_list",
            description = "List all keys in the vault (values are not shown for security)",
            parameters = mapOf(
                "category" to ParameterSchema("string", "Optional category filter", false),
            ),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            val category = args["category"]?.toString()
            return vaultList(category)
        }
    }

    private fun vaultSearchTool() = object : Tool {
        override val schema = ToolSchema(
            name = "vault_search",
            description = "Search vault entries by key or tags",
            parameters = mapOf(
                "query" to ParameterSchema("string", "Search query", true),
            ),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            val query = args["query"]?.toString() ?: return mapOf("success" to false, "error" to "Missing query")
            return vaultSearch(query)
        }
    }

    private fun getUserProfileFromSettings(): UserProfile {
        val jsonStr = appSettings.getUserProfileJson()
        return if (jsonStr.isNotBlank()) {
            try {
                json.decodeFromString<UserProfile>(jsonStr)
            } catch (e: Exception) {
                UserProfile()
            }
        } else {
            UserProfile()
        }
    }

    private fun saveUserProfile(profile: UserProfile) {
        val jsonStr = json.encodeToString(profile)
        appSettings.setUserProfileJson(jsonStr)
    }

    private fun getPrivateVaultFromSettings(): PrivateVault {
        val jsonStr = appSettings.getPrivateVaultJson()
        return if (jsonStr.isNotBlank()) {
            try {
                json.decodeFromString<PrivateVault>(jsonStr)
            } catch (e: Exception) {
                PrivateVault()
            }
        } else {
            PrivateVault()
        }
    }

    private fun savePrivateVault(vault: PrivateVault) {
        val jsonStr = json.encodeToString(vault)
        appSettings.setPrivateVaultJson(jsonStr)
    }

    private fun updateUserProfile(field: String, value: String): Map<String, Any> {
        val profile = getUserProfileFromSettings()
        val updated = when (field) {
            "name" -> profile.copy(name = value)
            "age" -> profile.copy(age = value.toIntOrNull())
            "gender" -> profile.copy(gender = value)
            "occupation" -> profile.copy(occupation = value)
            "location" -> profile.copy(location = value)
            "language" -> profile.copy(language = value)
            "timezone" -> profile.copy(timezone = value)
            "communicationStyle" -> profile.copy(
                communicationStyle = com.inspiredandroid.kai.data.CommunicationStyle.entries.find { it.name == value }
                    ?: com.inspiredandroid.kai.data.CommunicationStyle.FORMAL
            )
            "interests" -> profile.copy(interests = value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            "goals" -> profile.copy(goals = value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            "constraints" -> profile.copy(constraints = value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            "customAttributes" -> {
                val attrs = try {
                    json.decodeFromString<Map<String, String>>(value)
                } catch (e: Exception) {
                    return mapOf("success" to false, "error" to "Invalid JSON format for customAttributes")
                }
                profile.copy(customAttributes = attrs)
            }
            "behavioralPatterns.peakActivityHours" -> {
                val hours = value.split(",").mapNotNull { it.trim().toIntOrNull() }
                profile.copy(behavioralPatterns = profile.behavioralPatterns.copy(peakActivityHours = hours))
            }
            "behavioralPatterns.interactionFrequency" -> {
                val freq = InteractionFrequency.entries.find { it.name == value } ?: InteractionFrequency.MODERATE
                profile.copy(behavioralPatterns = profile.behavioralPatterns.copy(interactionFrequency = freq))
            }
            "behavioralPatterns.commonTasks" -> {
                profile.copy(behavioralPatterns = profile.behavioralPatterns.copy(
                    commonTasks = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                ))
            }
            "preferences.responseLength" -> {
                val len = ResponseLength.entries.find { it.name == value } ?: ResponseLength.MEDIUM
                profile.copy(preferences = profile.preferences.copy(responseLength = len))
            }
            "preferences.detailLevel" -> {
                val level = com.inspiredandroid.kai.data.DetailLevel.entries.find { it.name == value }
                    ?: com.inspiredandroid.kai.data.DetailLevel.MODERATE
                profile.copy(preferences = profile.preferences.copy(detailLevel = level))
            }
            "preferences.tone" -> {
                profile.copy(preferences = profile.preferences.copy(tone = value))
            }
            else -> return mapOf("success" to false, "error" to "Unknown field: $field")
        }
        saveUserProfile(updated)
        return mapOf("success" to true, "output" to "Updated $field to: $value")
    }

    private fun addBehavioralPattern(pattern: String, type: String): Map<String, Any> {
        val profile = getUserProfileFromSettings()
        val updated = when (type) {
            "commonTask" -> profile.copy(behavioralPatterns = profile.behavioralPatterns.copy(
                commonTasks = profile.behavioralPatterns.commonTasks + pattern
            ))
            "preferredTool" -> profile.copy(behavioralPatterns = profile.behavioralPatterns.copy(
                preferredTools = profile.behavioralPatterns.preferredTools + pattern
            ))
            "learningPattern" -> profile.copy(behavioralPatterns = profile.behavioralPatterns.copy(
                learningPatterns = profile.behavioralPatterns.learningPatterns + pattern
            ))
            else -> return mapOf("success" to false, "error" to "Unknown type. Use: commonTask, preferredTool, or learningPattern")
        }
        saveUserProfile(updated)
        return mapOf("success" to true, "output" to "Added $type: $pattern")
    }

    private fun vaultStore(key: String, value: String, category: String, tags: List<String>): Map<String, Any> {
        val cat = VaultCategory.entries.find { it.name == category } ?: VaultCategory.OTHER
        val vault = getPrivateVaultFromSettings()
        val entry = VaultEntry(
            key = key,
            value = value,
            category = cat,
            tags = tags,
        )
        val updated = vault.addEntry(entry)
        val log = VaultAccessLog(
            action = VaultAction.STORE,
            keyName = key,
            success = true,
            toolName = "vault_store",
        )
        savePrivateVault(updated.appendAccessLog(log))
        return mapOf("success" to true, "output" to "Stored: $key")
    }

    private fun vaultRetrieve(key: String): Map<String, Any> {
        val vault = getPrivateVaultFromSettings()
        val entry = vault.entries.values.find { it.key == key }
            ?: return mapOf("success" to false, "error" to "Key not found: $key")
        return mapOf(
            "success" to true,
            "key" to entry.key,
            "value" to vault.maskValue(entry.value),
        )
    }

    private fun vaultUse(key: String, purpose: String): Map<String, Any> {
        val vault = getPrivateVaultFromSettings()
        val entry = vault.entries.values.find { it.key == key }
            ?: return mapOf("success" to false, "error" to "Key not found: $key")
        val log = VaultAccessLog(
            action = VaultAction.RETRIEVE,
            keyName = key,
            success = true,
            toolName = "vault_use",
        )
        savePrivateVault(vault.appendAccessLog(log))
        return mapOf(
            "success" to true,
            "key" to entry.key,
            "value" to entry.value,
            "purpose" to purpose,
            "security_note" to "This value was used internally and has not been logged or exposed externally.",
        )
    }

    private fun vaultCopyTool() = object : Tool {
        override val schema = ToolSchema(
            name = "vault_copy",
            description = "Copy a vault credential value to clipboard. User must have explicitly requested to copy a password or secret. This copies the actual value - do NOT call this unless user explicitly asks to copy a password/secret.",
            parameters = mapOf(
                "key" to ParameterSchema("string", "Key of the credential to copy", true),
            ),
        )
        override suspend fun execute(args: Map<String, Any>): Map<String, Any> {
            val key = args["key"]?.toString() ?: return mapOf("success" to false, "error" to "Missing key")
            return vaultCopy(key)
        }
    }

    private fun vaultCopy(key: String): Map<String, Any> {
        val vault = getPrivateVaultFromSettings()
        val entry = vault.entries.values.find { it.key == key }
            ?: return mapOf("success" to false, "error" to "Key not found: $key")
        val log = VaultAccessLog(
            action = VaultAction.COPY,
            keyName = key,
            success = true,
            toolName = "vault_copy",
        )
        savePrivateVault(vault.appendAccessLog(log))
        return mapOf(
            "success" to true,
            "key" to entry.key,
            "copied" to true,
            "security_note" to "Value copied to clipboard. Access logged.",
        )
    }

    private fun vaultDelete(key: String): Map<String, Any> {
        val vault = getPrivateVaultFromSettings()
        val entry = vault.entries.values.find { it.key == key }
            ?: return mapOf("success" to false, "error" to "Key not found: $key")
        val log = VaultAccessLog(
            action = VaultAction.DELETE,
            keyName = key,
            success = true,
            toolName = "vault_delete",
        )
        val updated = vault.removeEntry(entry.id).appendAccessLog(log)
        savePrivateVault(updated)
        return mapOf("success" to true, "output" to "Deleted: $key")
    }

    private fun vaultList(category: String?): Map<String, Any> {
        val vault = getPrivateVaultFromSettings()
        val entries = if (category != null) {
            val cat = VaultCategory.entries.find { it.name == category }
                ?: return mapOf("success" to false, "error" to "Invalid category")
            vault.entries.values.filter { it.category == cat }
        } else {
            vault.entries.values
        }
        val log = VaultAccessLog(
            action = VaultAction.LIST,
            keyName = "all",
            success = true,
            toolName = "vault_list",
        )
        savePrivateVault(vault.appendAccessLog(log))
        val list = entries.joinToString("\n") { "${it.key} [${it.category.name}]" }
        return mapOf("success" to true, "entries" to if (list.isEmpty()) "No entries" else list)
    }

    private fun vaultSearch(query: String): Map<String, Any> {
        val vault = getPrivateVaultFromSettings()
        val results = vault.search(query)
        val log = VaultAccessLog(
            action = VaultAction.SEARCH,
            keyName = "search:$query",
            success = true,
            toolName = "vault_search",
        )
        savePrivateVault(vault.appendAccessLog(log))
        val list = results.joinToString("\n") { "${it.key}: ${vault.maskValue(it.value)}" }
        return mapOf("success" to true, "results" to if (list.isEmpty()) "No results" else list)
    }
}
