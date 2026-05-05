package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import com.inspiredandroid.kai.util.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Immutable
@Serializable
data class KbFile(
    val id: String,
    val path: String,
    val name: String,
    val mimeType: String?,
    val size: Long,
    val modifiedAt: Long,
    val indexedAt: Long,
    val contentHash: String?,
)

@Immutable
@Serializable
data class KbChunk(
    val id: String,
    val fileId: String,
    val chunkIndex: Int,
    val title: String?,
    val content: String,
    val startOffset: Int,
    val endOffset: Int,
)

@Immutable
@Serializable
data class KbSearchResult(
    val chunk: KbChunk,
    val file: KbFile,
    val score: Double,
    val snippet: String,
)

@OptIn(ExperimentalTime::class)
class KnowledgeBaseStore(private val appSettings: AppSettings) {

    private val json = SharedJson
    private val mutex = Mutex()

    private var cachedFiles: MutableList<KbFile>? = null
    private var cachedChunks: MutableList<KbChunk>? = null
    private var filesDirty = true
    private var chunksDirty = true

    companion object {
        const val MAX_FILES = 10000
        const val MAX_CHUNKS = 100000
        const val CHUNK_SIZE = 512
    }

    private fun loadFiles(): MutableList<KbFile> {
        val cached = cachedFiles
        if (cached != null && !filesDirty) return cached

        val raw = appSettings.getKnowledgeBaseFilesJson()
        val files = if (raw.isBlank()) {
            mutableListOf()
        } else {
            try {
                json.decodeFromString<List<KbFile>>(raw).toMutableList()
            } catch (e: Exception) {
                Logger.e("KnowledgeBaseStore", "failed to load files: ${e.message}")
                mutableListOf()
            }
        }
        cachedFiles = files
        filesDirty = false
        return files
    }

    private fun loadChunks(): MutableList<KbChunk> {
        val cached = cachedChunks
        if (cached != null && !chunksDirty) return cached

        val raw = appSettings.getKnowledgeBaseChunksJson()
        val chunks = if (raw.isBlank()) {
            mutableListOf()
        } else {
            try {
                json.decodeFromString<List<KbChunk>>(raw).toMutableList()
            } catch (e: Exception) {
                Logger.e("KnowledgeBaseStore", "failed to load chunks: ${e.message}")
                mutableListOf()
            }
        }
        cachedChunks = chunks
        chunksDirty = false
        return chunks
    }

    private fun saveFiles(files: List<KbFile>) {
        val trimmed = files.takeLast(MAX_FILES)
        appSettings.setKnowledgeBaseFilesJson(json.encodeToString(trimmed))
        cachedFiles = trimmed.toMutableList()
        filesDirty = false
    }

    private fun saveChunks(chunks: List<KbChunk>) {
        val trimmed = chunks.takeLast(MAX_CHUNKS)
        appSettings.setKnowledgeBaseChunksJson(json.encodeToString(trimmed))
        cachedChunks = trimmed.toMutableList()
        chunksDirty = false
    }

    suspend fun addFile(file: KbFile, chunks: List<KbChunk>) = mutex.withLock {
        val files = loadFiles()
        val existingIndex = files.indexOfFirst { it.path == file.path }
        if (existingIndex >= 0) {
            val existingFileId = files[existingIndex].id
            files[existingIndex] = file
            val allChunks = loadChunks().filter { it.fileId != existingFileId } + chunks
            saveChunks(allChunks)
        } else {
            files.add(file)
            val allChunks = loadChunks() + chunks
            saveChunks(allChunks)
        }
        saveFiles(files)
    }

    suspend fun removeFile(fileId: String) = mutex.withLock {
        val files = loadFiles().filter { it.id != fileId }
        val chunks = loadChunks().filter { it.fileId != fileId }
        saveFiles(files)
        saveChunks(chunks)
    }

    suspend fun getFileByPath(path: String): KbFile? {
        return loadFiles().find { it.path == path }
    }

    suspend fun search(query: String, limit: Int = 10): List<KbSearchResult> = mutex.withLock {
        val files = loadFiles()
        val chunks = loadChunks()
        val queryLower = query.lowercase()

        val queryWords = queryLower.split(Regex("\\s+")).filter { it.length > 2 }

        val scored = chunks.mapNotNull { chunk ->
            val file = files.find { it.id == chunk.fileId } ?: return@mapNotNull null
            val contentLower = chunk.content.lowercase()

            var score = 0.0
            for (word in queryWords) {
                if (contentLower.contains(word)) {
                    score += 1.0
                    if (chunk.title?.lowercase()?.contains(word) == true) {
                        score += 0.5
                    }
                }
            }

            if (score > 0) {
                val snippet = createSnippet(chunk.content, queryLower)
                KbSearchResult(chunk, file, score, snippet)
            } else {
                null
            }
        }

        scored.sortedByDescending { it.score }.take(limit)
    }

    private fun createSnippet(content: String, query: String): String {
        val queryWords = query.split(Regex("\\s+")).filter { it.length > 2 }

        var bestPos = 0
        for (word in queryWords) {
            val pos = content.lowercase().indexOf(word)
            if (pos >= 0) {
                bestPos = pos
                break
            }
        }

        val start = maxOf(0, bestPos - 50)
        val end = minOf(content.length, bestPos + 150)
        var snippet = content.substring(start, end)

        if (start > 0) snippet = "..." + snippet
        if (end < content.length) snippet = snippet + "..."

        return snippet
    }

    suspend fun getStats(): Map<String, Any> = mutex.withLock {
        mapOf(
            "fileCount" to loadFiles().size,
            "chunkCount" to loadChunks().size,
        )
    }

    suspend fun clear() = mutex.withLock {
        saveFiles(emptyList())
        saveChunks(emptyList())
    }

    suspend fun indexDocument(
        path: String,
        name: String,
        mimeType: String?,
        size: Long,
        modifiedAt: Long,
        content: String,
    ): Boolean = mutex.withLock {
        try {
            val chunks = createChunks(content)
            val fileId = "${Clock.System.now().toEpochMilliseconds()}_${path.hashCode()}"
            val now = Clock.System.now().toEpochMilliseconds()

            val file = KbFile(
                id = fileId,
                path = path,
                name = name,
                mimeType = mimeType,
                size = size,
                modifiedAt = modifiedAt,
                indexedAt = now,
                contentHash = null,
            )

            val kbChunks = chunks.mapIndexed { index, chunkContent ->
                KbChunk(
                    id = "${fileId}_$index",
                    fileId = fileId,
                    chunkIndex = index,
                    title = name,
                    content = chunkContent,
                    startOffset = index * CHUNK_SIZE,
                    endOffset = (index + 1) * CHUNK_SIZE,
                )
            }

            addFile(file, kbChunks)
            true
        } catch (e: Exception) {
            Logger.e("KnowledgeBaseStore", "failed to index document: ${e.message}")
            false
        }
    }

    private fun createChunks(content: String): List<String> {
        if (content.isBlank()) return emptyList()

        val chunks = mutableListOf<String>()
        val paragraphs = content.split("\n\n")

        var currentChunk = StringBuilder()

        for (paragraph in paragraphs) {
            if (paragraph.isBlank()) continue

            if (currentChunk.length + paragraph.length + 2 <= CHUNK_SIZE) {
                if (currentChunk.isNotEmpty()) {
                    currentChunk.append("\n\n")
                }
                currentChunk.append(paragraph)
            } else {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString())
                }
                currentChunk.clear()

                if (paragraph.length > CHUNK_SIZE) {
                    val words = paragraph.split(" ")
                    for (word in words) {
                        if (currentChunk.length + word.length + 1 <= CHUNK_SIZE) {
                            if (currentChunk.isNotEmpty()) {
                                currentChunk.append(" ")
                            }
                            currentChunk.append(word)
                        } else {
                            if (currentChunk.isNotEmpty()) {
                                chunks.add(currentChunk.toString())
                            }
                            currentChunk.clear()
                            currentChunk.append(word)
                        }
                    }
                } else {
                    currentChunk.append(paragraph)
                }
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString())
        }

        return chunks
    }
}
