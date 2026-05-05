package com.inspiredandroid.kai.data

actual class DocumentParser {
    actual fun parseText(filePath: String): String? = null
    actual fun getTextChunks(content: String, chunkSize: Int): List<String> = emptyList()
}
