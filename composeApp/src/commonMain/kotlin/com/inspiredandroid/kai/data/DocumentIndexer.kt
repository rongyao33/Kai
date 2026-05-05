package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DocumentIndexer(
    private val documentParser: DocumentParser?,
    private val fileScanner: FileScanner?,
    private val knowledgeBaseStore: KnowledgeBaseStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fileObserverManager: FileObserverManager? = null

    suspend fun indexFile(path: String): IndexResult {
        if (documentParser == null) {
            return IndexResult(false, "Document parser not available on this platform")
        }

        val content = documentParser.parseText(path)
        if (content.isNullOrBlank()) {
            return IndexResult(false, "Failed to parse document or document is empty")
        }

        val name = path.substringAfterLast("/")
        val mimeType = when {
            path.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            path.endsWith(".docx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            path.endsWith(".doc", ignoreCase = true) -> "application/msword"
            path.endsWith(".txt", ignoreCase = true) -> "text/plain"
            path.endsWith(".md", ignoreCase = true) -> "text/markdown"
            path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            path.endsWith(".png", ignoreCase = true) -> "image/png"
            path.endsWith(".gif", ignoreCase = true) -> "image/gif"
            path.endsWith(".webp", ignoreCase = true) -> "image/webp"
            path.endsWith(".bmp", ignoreCase = true) -> "image/bmp"
            else -> null
        }

        val success = knowledgeBaseStore.indexDocument(
            path = path,
            name = name,
            mimeType = mimeType,
            size = 0,
            modifiedAt = System.currentTimeMillis(),
            content = content,
        )

        return if (success) {
            IndexResult(true, "Document indexed successfully: $name")
        } else {
            IndexResult(false, "Failed to index document")
        }
    }

    suspend fun scanAndIndex(path: String = ""): ScanIndexResult {
        if (fileScanner == null || documentParser == null) {
            return ScanIndexResult(0, 0, "File scanner not available on this platform")
        }

        val files = fileScanner.scanDirectory(path)
        if (files.isEmpty()) {
            return ScanIndexResult(0, 0, "No documents found")
        }

        var indexed = 0
        var failed = 0

        for (file in files) {
            val existing = knowledgeBaseStore.getFileByPath(file.path)
            if (existing != null && existing.modifiedAt >= file.modifiedAt) {
                continue
            }

            val content = documentParser.parseText(file.path)
            if (content.isNullOrBlank()) {
                failed++
                continue
            }

            val success = knowledgeBaseStore.indexDocument(
                path = file.path,
                name = file.name,
                mimeType = file.mimeType,
                size = file.size,
                modifiedAt = file.modifiedAt,
                content = content,
            )

            if (success) {
                indexed++
            } else {
                failed++
            }
        }

        return ScanIndexResult(indexed, failed, "Indexed $indexed documents, $failed failed")
    }

    fun startIncrementalIndexing(paths: List<String>) {
        if (fileObserverManager != null) {
            Logger.d("DocumentIndexer", "Already watching")
            return
        }
        if (documentParser == null) {
            Logger.w("DocumentIndexer", "Document parser not available, cannot start incremental indexing")
            return
        }

        fileObserverManager = createFileObserverManager { changedPath: String ->
            scope.launch {
                try {
                    Logger.d("DocumentIndexer", "File changed: $changedPath")
                    indexFile(changedPath)
                } catch (e: Exception) {
                    Logger.e("DocumentIndexer", "Error indexing file: $changedPath", e)
                }
            }
        }
        fileObserverManager?.startWatching(paths)
        Logger.d("DocumentIndexer", "Started incremental indexing for ${paths.size} paths")
    }

    fun stopIncrementalIndexing() {
        fileObserverManager?.stopWatching()
        fileObserverManager = null
        Logger.d("DocumentIndexer", "Stopped incremental indexing")
    }

    fun isIncrementalIndexingActive(): Boolean {
        return fileObserverManager?.isWatching() ?: false
    }
}

data class IndexResult(
    val success: Boolean,
    val message: String,
)

data class ScanIndexResult(
    val indexed: Int,
    val failed: Int,
    val message: String,
)
