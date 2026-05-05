package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.DocumentIndexer
import com.inspiredandroid.kai.data.KnowledgeBaseStore
import com.inspiredandroid.kai.data.ScanIndexResult
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema

object KnowledgeBaseTools {

    fun getToolInfos(): List<ToolInfo> = listOf(
        ToolInfo(
            id = "kb_search",
            name = "Knowledge Base Search",
            description = "Search the user's local documents for information",
        ),
        ToolInfo(
            id = "kb_query",
            name = "Knowledge Base Query",
            description = "Ask a question about the user's local documents",
        ),
        ToolInfo(
            id = "kb_index",
            name = "Knowledge Base Index",
            description = "Index documents into the knowledge base",
        ),
        ToolInfo(
            id = "kb_stats",
            name = "Knowledge Base Stats",
            description = "Get statistics about the knowledge base",
        ),
        ToolInfo(
            id = "kb_watch",
            name = "Knowledge Base Watch",
            description = "Start watching directories for file changes to automatically index new or modified documents",
        ),
        ToolInfo(
            id = "kb_unwatch",
            name = "Knowledge Base Unwatch",
            description = "Stop watching directories for file changes",
        ),
    )

    fun getTools(
        knowledgeBaseStore: KnowledgeBaseStore?,
        documentIndexer: DocumentIndexer?,
    ): List<Tool> {
        if (knowledgeBaseStore == null) return emptyList()
        return listOf(
            kbSearchTool(knowledgeBaseStore),
            kbQueryTool(knowledgeBaseStore),
            kbIndexTool(knowledgeBaseStore, documentIndexer),
            kbStatsTool(knowledgeBaseStore),
            kbWatchTool(knowledgeBaseStore, documentIndexer),
            kbUnwatchTool(documentIndexer),
        )
    }

    private fun kbSearchTool(store: KnowledgeBaseStore) = object : Tool {
        override val schema = ToolSchema(
            name = "kb_search",
            description = "Search the user's local documents (PDF, DOCX, TXT) for information. Use this when user asks to find something in their documents, search for files, or look up information from saved documents.",
            parameters = mapOf(
                "query" to ParameterSchema(
                    type = "string",
                    description = "Search query - use keywords from what user is looking for",
                    required = true,
                ),
                "limit" to ParameterSchema(
                    type = "integer",
                    description = "Maximum number of results to return (default: 5)",
                    required = false,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val query = args["query"]?.toString()
                ?: return mapOf("success" to false, "error" to "Missing query")
            val limit = (args["limit"] as? Number)?.toInt() ?: 5

            val results = store.search(query, limit)

            if (results.isEmpty()) {
                return mapOf(
                    "success" to true,
                    "results" to emptyList<Map<String, Any>>(),
                    "message" to "No results found for '$query'",
                )
            }

            return mapOf(
                "success" to true,
                "results" to results.map { result ->
                    mapOf(
                        "file_name" to result.file.name,
                        "file_path" to result.file.path,
                        "snippet" to result.snippet,
                        "score" to result.score,
                    )
                },
                "total_found" to results.size,
            )
        }
    }

    private fun kbQueryTool(store: KnowledgeBaseStore) = object : Tool {
        override val schema = ToolSchema(
            name = "kb_query",
            description = "Ask a question about the user's local documents. The AI will search relevant documents and generate an answer with citations.",
            parameters = mapOf(
                "question" to ParameterSchema(
                    type = "string",
                    description = "The question to answer based on local documents",
                    required = true,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val question = args["question"]?.toString()
                ?: return mapOf("success" to false, "error" to "Missing question")

            val results = store.search(question, 5)

            if (results.isEmpty()) {
                return mapOf(
                    "success" to true,
                    "answer" to "No relevant documents found to answer your question.",
                    "sources" to emptyList<Map<String, Any>>(),
                )
            }

            val context = results.take(3).joinToString("\n\n") { result ->
                "[${result.file.name}]\n${result.snippet}"
            }

            return mapOf(
                "success" to true,
                "answer" to "Based on the documents I found:\n\n$context",
                "sources" to results.take(3).map { result ->
                    mapOf(
                        "file" to result.file.name,
                        "snippet" to result.snippet,
                    )
                },
            )
        }
    }

    private fun kbIndexTool(
        store: KnowledgeBaseStore,
        indexer: DocumentIndexer?,
    ) = object : Tool {
        override val schema = ToolSchema(
            name = "kb_index",
            description = "Index a document or scan a directory to add documents to the knowledge base. Use this when user asks to index, add, or scan documents into the knowledge base.",
            parameters = mapOf(
                "path" to ParameterSchema(
                    type = "string",
                    description = "File path or directory to index. If empty, scans common document directories.",
                    required = false,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            if (indexer == null) {
                return mapOf(
                    "success" to false,
                    "error" to "Document indexing is not available on this platform",
                )
            }

            val path = args["path"]?.toString() ?: ""

            val result = if (path.isBlank()) {
                indexer.scanAndIndex()
            } else {
                val indexResult = indexer.indexFile(path)
                ScanIndexResult(
                    indexed = if (indexResult.success) 1 else 0,
                    failed = if (indexResult.success) 0 else 1,
                    message = indexResult.message,
                )
            }

            return mapOf(
                "success" to true,
                "indexed" to result.indexed,
                "failed" to result.failed,
                "message" to result.message,
            )
        }
    }

    private fun kbStatsTool(store: KnowledgeBaseStore) = object : Tool {
        override val schema = ToolSchema(
            name = "kb_stats",
            description = "Get statistics about the indexed documents in the knowledge base",
            parameters = emptyMap(),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val stats = store.getStats()
            return mapOf(
                "success" to true,
                "file_count" to stats["fileCount"],
                "chunk_count" to stats["chunkCount"],
            )
        }
    }

    private fun kbWatchTool(
        store: KnowledgeBaseStore,
        indexer: DocumentIndexer?,
    ) = object : Tool {
        override val schema = ToolSchema(
            name = "kb_watch",
            description = "Start watching directories for file changes to automatically index new or modified documents. Call kb_unwatch to stop.",
            parameters = mapOf(
                "paths" to ParameterSchema(
                    type = "string",
                    description = "Comma-separated list of directory paths to watch. If empty, uses common document directories.",
                    required = false,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            if (indexer == null) {
                return mapOf(
                    "success" to false,
                    "error" to "Document indexing is not available on this platform",
                )
            }

            if (indexer.isIncrementalIndexingActive()) {
                return mapOf(
                    "success" to true,
                    "message" to "Already watching for file changes",
                    "active" to true,
                )
            }

            val pathsArg = args["paths"]?.toString() ?: ""
            val paths = if (pathsArg.isBlank()) {
                listOf(
                    "/storage/emulated/0/Documents",
                    "/storage/emulated/0/Download",
                    "/storage/emulated/0/DCIM",
                )
            } else {
                pathsArg.split(",").map { it.trim() }.filter { it.isNotBlank() }
            }

            indexer.startIncrementalIndexing(paths)

            return mapOf(
                "success" to true,
                "message" to "Started watching ${paths.size} directories for changes",
                "watching" to true,
                "paths" to paths,
            )
        }
    }

    private fun kbUnwatchTool(indexer: DocumentIndexer?) = object : Tool {
        override val schema = ToolSchema(
            name = "kb_unwatch",
            description = "Stop watching directories for file changes",
            parameters = emptyMap(),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            if (indexer == null) {
                return mapOf(
                    "success" to false,
                    "error" to "Document indexing is not available on this platform",
                )
            }

            if (!indexer.isIncrementalIndexingActive()) {
                return mapOf(
                    "success" to true,
                    "message" to "Not currently watching any directories",
                    "watching" to false,
                )
            }

            indexer.stopIncrementalIndexing()

            return mapOf(
                "success" to true,
                "message" to "Stopped watching for file changes",
                "watching" to false,
            )
        }
    }
}
