package com.inspiredandroid.kai.data

actual class FileScanner {
    actual suspend fun scanDirectory(path: String): List<ScannedFile> = emptyList()
    actual fun getSupportedExtensions(): List<String> = emptyList()
}
