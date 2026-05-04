package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import com.inspiredandroid.kai.util.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Immutable
@Serializable
data class KnowledgeGraphNode(
    val id: String,
    val type: NodeType,
    val name: String,
    val properties: Map<String, String> = emptyMap(),
    val createdAt: Long,
    val updatedAt: Long,
    val confidence: Float = 1.0f,
    val sourceMemoryKey: String? = null,
)

@Immutable
@Serializable
data class KnowledgeGraphEdge(
    val id: String,
    val sourceId: String,
    val targetId: String,
    val relation: String,
    val properties: Map<String, String> = emptyMap(),
    val createdAt: Long,
    val confidence: Float = 1.0f,
)

enum class NodeType {
    ENTITY,
    CONCEPT,
    PERSON,
    PLACE,
    OBJECT,
    EVENT,
    TOPIC,
}

@OptIn(ExperimentalTime::class)
class KnowledgeGraphStore(private val appSettings: AppSettings) {

    private val json = SharedJson
    private val mutex = Mutex()

    private var cachedNodes: MutableList<KnowledgeGraphNode>? = null
    private var cachedEdges: MutableList<KnowledgeGraphEdge>? = null
    private var nodesDirty = true
    private var edgesDirty = true

    companion object {
        const val MAX_NODES = 1000
        const val MAX_EDGES = 2000
        const val SIMILARITY_THRESHOLD = 0.8f
    }

    private fun loadNodes(): MutableList<KnowledgeGraphNode> {
        val cached = cachedNodes
        if (cached != null && !nodesDirty) return cached

        val raw = appSettings.getKnowledgeGraphNodesJson()
        val nodes = if (raw.isBlank()) {
            mutableListOf()
        } else {
            try {
                json.decodeFromString<List<KnowledgeGraphNode>>(raw).toMutableList()
            } catch (e: Exception) {
                Logger.e("KnowledgeGraphStore", "failed to load nodes: ${e.message}")
                mutableListOf()
            }
        }
        cachedNodes = nodes
        nodesDirty = false
        return nodes
    }

    private fun loadEdges(): MutableList<KnowledgeGraphEdge> {
        val cached = cachedEdges
        if (cached != null && !edgesDirty) return cached

        val raw = appSettings.getKnowledgeGraphEdgesJson()
        val edges = if (raw.isBlank()) {
            mutableListOf()
        } else {
            try {
                json.decodeFromString<List<KnowledgeGraphEdge>>(raw).toMutableList()
            } catch (e: Exception) {
                Logger.e("KnowledgeGraphStore", "failed to load edges: ${e.message}")
                mutableListOf()
            }
        }
        cachedEdges = edges
        edgesDirty = false
        return edges
    }

    private fun saveNodes(nodes: List<KnowledgeGraphNode>) {
        val trimmed = trimNodes(nodes)
        appSettings.setKnowledgeGraphNodesJson(json.encodeToString(trimmed))
        cachedNodes = trimmed.toMutableList()
        nodesDirty = false
    }

    private fun saveEdges(edges: List<KnowledgeGraphEdge>) {
        val trimmed = trimEdges(edges)
        appSettings.setKnowledgeGraphEdgesJson(json.encodeToString(trimmed))
        cachedEdges = trimmed.toMutableList()
        edgesDirty = false
    }

    private fun trimNodes(nodes: List<KnowledgeGraphNode>): List<KnowledgeGraphNode> {
        if (nodes.size <= MAX_NODES) return nodes
        return nodes.sortedByDescending { it.updatedAt }.take(MAX_NODES)
    }

    private fun trimEdges(edges: List<KnowledgeGraphEdge>): List<KnowledgeGraphEdge> {
        if (edges.size <= MAX_EDGES) return edges
        return edges.sortedByDescending { it.createdAt }.take(MAX_EDGES)
    }

    fun invalidateCache() {
        nodesDirty = true
        edgesDirty = true
    }

    suspend fun addNode(
        type: NodeType,
        name: String,
        properties: Map<String, String> = emptyMap(),
        sourceMemoryKey: String? = null,
    ): KnowledgeGraphNode = mutex.withLock {
        val nodes = loadNodes()
        val now = Clock.System.now().toEpochMilliseconds()

        val existing = nodes.find {
            it.name.equals(name, ignoreCase = true) && it.type == type
        }
        if (existing != null) {
            val updated = existing.copy(
                properties = properties,
                updatedAt = now,
                confidence = (existing.confidence + 0.1f).coerceAtMost(1.0f),
            )
            val index = nodes.indexOf(existing)
            nodes[index] = updated
            saveNodes(nodes)
            return@withLock updated
        }

        val node = KnowledgeGraphNode(
            id = "kg-node-${now}-${nodes.size}",
            type = type,
            name = name,
            properties = properties,
            createdAt = now,
            updatedAt = now,
            sourceMemoryKey = sourceMemoryKey,
        )
        nodes.add(node)
        saveNodes(nodes)
        node
    }

    suspend fun addEdge(
        sourceId: String,
        targetId: String,
        relation: String,
        properties: Map<String, String> = emptyMap(),
    ): KnowledgeGraphEdge? = mutex.withLock {
        val nodes = loadNodes()
        val edges = loadEdges()

        val sourceExists = nodes.any { it.id == sourceId }
        val targetExists = nodes.any { it.id == targetId }
        if (!sourceExists || !targetExists) return@withLock null

        val existing = edges.find {
            it.sourceId == sourceId && it.targetId == targetId && it.relation == relation
        }
        if (existing != null) {
            return@withLock existing
        }

        val now = Clock.System.now().toEpochMilliseconds()
        val edge = KnowledgeGraphEdge(
            id = "kg-edge-${now}",
            sourceId = sourceId,
            targetId = targetId,
            relation = relation,
            properties = properties,
            createdAt = now,
        )
        edges.add(edge)
        saveEdges(edges)
        edge
    }

    suspend fun addTriple(
        subject: String,
        predicate: String,
        object_: String,
        subjectType: NodeType = NodeType.ENTITY,
        objectType: NodeType = NodeType.ENTITY,
    ): Boolean = mutex.withLock {
        val now = Clock.System.now().toEpochMilliseconds()
        val nodes = loadNodes()

        val subjectNode = nodes.find {
            it.name.equals(subject, ignoreCase = true) && it.type == subjectType
        } ?: run {
            val newNode = KnowledgeGraphNode(
                id = "kg-node-${now}-subj",
                type = subjectType,
                name = subject,
                createdAt = now,
                updatedAt = now,
            )
            nodes.add(newNode)
            newNode
        }

        val objectNode = nodes.find {
            it.name.equals(object_, ignoreCase = true) && it.type == objectType
        } ?: run {
            val newNode = KnowledgeGraphNode(
                id = "kg-node-${now}-obj",
                type = objectType,
                name = object_,
                createdAt = now,
                updatedAt = now,
            )
            nodes.add(newNode)
            newNode
        }

        saveNodes(nodes)

        val edges = loadEdges()
        val existingEdge = edges.find {
            it.sourceId == subjectNode.id && it.targetId == objectNode.id && it.relation == predicate
        }
        if (existingEdge != null) return@withLock true

        val edge = KnowledgeGraphEdge(
            id = "kg-edge-${now}",
            sourceId = subjectNode.id,
            targetId = objectNode.id,
            relation = predicate,
            createdAt = now,
        )
        edges.add(edge)
        saveEdges(edges)
        true
    }

    fun getNodeById(id: String): KnowledgeGraphNode? =
        loadNodes().find { it.id == id }

    fun getNodeByName(name: String): KnowledgeGraphNode? =
        loadNodes().find { it.name.equals(name, ignoreCase = true) }

    fun getNodesByType(type: NodeType): List<KnowledgeGraphNode> =
        loadNodes().filter { it.type == type }

    fun getOutgoingEdges(nodeId: String): List<KnowledgeGraphEdge> =
        loadEdges().filter { it.sourceId == nodeId }

    fun getIncomingEdges(nodeId: String): List<KnowledgeGraphEdge> =
        loadEdges().filter { it.targetId == nodeId }

    fun getRelatedNodes(nodeId: String): List<KnowledgeGraphNode> {
        val edges = loadEdges()
        val nodeIds = edges
            .filter { it.sourceId == nodeId || it.targetId == nodeId }
            .flatMap { listOf(it.sourceId, it.targetId) }
            .filter { it != nodeId }
            .distinct()
        val nodes = loadNodes()
        return nodeIds.mapNotNull { id -> nodes.find { it.id == id } }
    }

    fun getRelationPaths(fromId: String, toId: String, maxDepth: Int = 3): List<List<KnowledgeGraphEdge>> {
        val edges = loadEdges()
        val result = mutableListOf<List<KnowledgeGraphEdge>>()
        val visited = mutableSetOf<String>()

        fun dfs(current: String, target: String, path: List<KnowledgeGraphEdge>, depth: Int) {
            if (depth > maxDepth) return
            if (current == target && path.isNotEmpty()) {
                result.add(path)
                return
            }
            visited.add(current)
            for (edge in edges) {
                val next = if (edge.sourceId == current) edge.targetId else if (edge.targetId == current) edge.sourceId else continue
                if (next in visited) continue
                dfs(next, target, path + edge, depth + 1)
            }
            visited.remove(current)
        }

        dfs(fromId, toId, emptyList(), 0)
        return result
    }

    fun searchNodes(query: String): List<KnowledgeGraphNode> {
        val q = query.lowercase()
        return loadNodes().filter {
            it.name.lowercase().contains(q) ||
                it.properties.values.any { v -> v.lowercase().contains(q) }
        }
    }

    fun searchByRelation(relation: String): List<KnowledgeGraphEdge> =
        loadEdges().filter { it.relation.equals(relation, ignoreCase = true) }

    suspend fun updateNode(
        id: String,
        name: String? = null,
        properties: Map<String, String>? = null,
    ): KnowledgeGraphNode? = mutex.withLock {
        val nodes = loadNodes()
        val index = nodes.indexOfFirst { it.id == id }
        if (index < 0) return@withLock null

        val now = Clock.System.now().toEpochMilliseconds()
        val updated = nodes[index].copy(
            name = name ?: nodes[index].name,
            properties = properties ?: nodes[index].properties,
            updatedAt = now,
        )
        nodes[index] = updated
        saveNodes(nodes)
        updated
    }

    suspend fun deleteNode(id: String): Boolean = mutex.withLock {
        val nodes = loadNodes()
        val edges = loadEdges()

        val nodeExists = nodes.any { it.id == id }
        if (!nodeExists) return@withLock false

        val updatedNodes = nodes.filter { it.id != id }
        val updatedEdges = edges.filter { it.sourceId != id && it.targetId != id }

        saveNodes(updatedNodes)
        saveEdges(updatedEdges)
        true
    }

    suspend fun deleteEdge(id: String): Boolean = mutex.withLock {
        val edges = loadEdges()
        val removed = edges.removeAll { it.id == id }
        if (removed) saveEdges(edges)
        removed
    }

    fun getStats(): KnowledgeGraphStats {
        val nodes = loadNodes()
        val edges = loadEdges()
        return KnowledgeGraphStats(
            totalNodes = nodes.size,
            totalEdges = edges.size,
            byType = nodes.groupBy { it.type }.mapValues { it.value.size },
            avgNodeConnections = if (nodes.isNotEmpty()) {
                nodes.sumOf { node ->
                    edges.count { it.sourceId == node.id || it.targetId == node.id }.toDouble()
                }.toFloat() / nodes.size
            } else 0f,
            mostConnectedNode = nodes.maxByOrNull { node ->
                edges.count { it.sourceId == node.id || it.targetId == node.id }
            }?.name,
        )
    }

    suspend fun cleanup(): Int = mutex.withLock {
        val nodes = loadNodes()
        val edges = loadEdges()
        val before = nodes.size + edges.size

        val orphanedEdgeIds = edges.map { it.id }.filter { edgeId ->
            val edge = edges.find { it.id == edgeId }!!
            !nodes.any { it.id == edge.sourceId || it.id == edge.targetId }
        }.toSet()

        val updatedEdges = edges.filter { it.id !in orphanedEdgeIds }
        saveEdges(updatedEdges)

        before - (nodes.size + updatedEdges.size)
    }

    suspend fun clearAll(): Pair<Int, Int> = mutex.withLock {
        val nodes = loadNodes()
        val edges = loadEdges()
        val nodeCount = nodes.size
        val edgeCount = edges.size

        saveNodes(emptyList())
        saveEdges(emptyList())
        Pair(nodeCount, edgeCount)
    }
}

data class KnowledgeGraphStats(
    val totalNodes: Int,
    val totalEdges: Int,
    val byType: Map<NodeType, Int>,
    val avgNodeConnections: Float,
    val mostConnectedNode: String?,
)
