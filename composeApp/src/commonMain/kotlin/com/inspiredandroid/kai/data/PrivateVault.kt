package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class PrivateVault(
    val entries: Map<String, VaultEntry> = emptyMap(),
    val accessLog: List<VaultAccessLog> = emptyList(),
)

@Immutable
@Serializable
data class VaultEntry(
    val id: String = generateId(),
    val key: String,
    val value: String,
    val category: VaultCategory = VaultCategory.OTHER,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val tags: List<String> = emptyList(),
)

@Immutable
@Serializable
enum class VaultCategory {
    PASSWORD,
    API_KEY,
    ACCOUNT,
    FINANCIAL,
    MEDICAL,
    IDENTITY,
    NOTE,
    OTHER,
}

@Immutable
@Serializable
data class SecureCredential(
    val id: String = generateId(),
    val service: String,
    val username: String,
    val password: String,
    val url: String = "",
    val notes: String = "",
    val lastUpdated: Long = System.currentTimeMillis(),
)

@Immutable
@Serializable
enum class VaultAction {
    STORE,
    RETRIEVE,
    DELETE,
    LIST,
    SEARCH,
}

@Immutable
@Serializable
data class VaultAccessLog(
    val timestamp: Long = System.currentTimeMillis(),
    val action: VaultAction,
    val keyName: String,
    val success: Boolean,
    val toolName: String = "unknown",
)

fun PrivateVault.getPasswords(): List<VaultEntry> =
    entries.values.filter { it.category == VaultCategory.PASSWORD }

fun PrivateVault.getApiKeys(): List<VaultEntry> =
    entries.values.filter { it.category == VaultCategory.API_KEY }

fun PrivateVault.getAccounts(): List<VaultEntry> =
    entries.values.filter { it.category == VaultCategory.ACCOUNT }

fun PrivateVault.search(query: String): List<VaultEntry> {
    val lowerQuery = query.lowercase()
    return entries.values.filter { entry ->
        entry.key.lowercase().contains(lowerQuery) ||
            entry.value.lowercase().contains(lowerQuery) ||
            entry.tags.any { it.lowercase().contains(lowerQuery) } ||
            entry.metadata.values.any { it.lowercase().contains(lowerQuery) }
    }
}

fun PrivateVault.addEntry(entry: VaultEntry): PrivateVault =
    copy(entries = entries + (entry.id to entry))

fun PrivateVault.updateEntry(id: String, updated: VaultEntry): PrivateVault =
    copy(entries = entries + (id to updated.copy(updatedAt = System.currentTimeMillis())))

fun PrivateVault.removeEntry(id: String): PrivateVault =
    copy(entries = entries - id)

fun PrivateVault.getEntry(id: String): VaultEntry? = entries[id]

fun PrivateVault.appendAccessLog(log: VaultAccessLog): PrivateVault {
    val updatedLog = accessLog + log
    return copy(accessLog = updatedLog)
}

fun PrivateVault.maskValue(value: String): String = "••••••••"

fun PrivateVault.getEntryByKey(key: String): VaultEntry? =
    entries.values.find { it.key == key }

fun PrivateVault.removeEntryByKey(key: String): PrivateVault {
    val entry = getEntryByKey(key)
    return if (entry != null) {
        copy(entries = entries - entry.id)
    } else {
        this
    }
}

private fun generateId(): String = java.util.UUID.randomUUID().toString()
