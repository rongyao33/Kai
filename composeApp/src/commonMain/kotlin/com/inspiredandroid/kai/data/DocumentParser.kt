package com.inspiredandroid.kai.data

expect class DocumentParser {
    suspend fun parseText(filePath: String): String?
    fun getTextChunks(content: String, chunkSize: Int = 512): List<String>
}
