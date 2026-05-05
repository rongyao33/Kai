package com.inspiredandroid.kai.data

expect class FileScanner {
    suspend fun scanDirectory(path: String): List<ScannedFile>
    fun getSupportedExtensions(): List<String>
}

data class ScannedFile(
    val path: String,
    val name: String,
    val mimeType: String?,
    val size: Long,
    val modifiedAt: Long,
)
