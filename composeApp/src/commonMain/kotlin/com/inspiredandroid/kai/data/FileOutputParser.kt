package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable

@Immutable
data class FileOutput(
    val filename: String,
    val extension: String,
    val content: String,
    val mimeType: String,
)

fun parseFileOutputs(text: String): List<FileOutput> {
    val outputs = mutableListOf<FileOutput>()
    val pattern = Regex("""\[FILE:([^\]]+)\]\s*\n?(.*?)\s*\[/FILE\]""", RegexOption.DOT_MATCHES_ALL)

    pattern.findAll(text).forEach { match ->
        val filename = match.groupValues[1].trim()
        val content = match.groupValues[2].trim()
        val extension = filename.substringAfterLast('.', "").lowercase()
        val mimeType = getMimeType(extension)

        if (filename.isNotBlank() && content.isNotBlank()) {
            outputs.add(FileOutput(filename, extension, content, mimeType))
        }
    }

    return outputs
}

fun getMimeType(extension: String): String = when (extension) {
    "csv" -> "text/csv"
    "json" -> "application/json"
    "txt" -> "text/plain"
    "html", "htm" -> "text/html"
    "md", "markdown" -> "text/markdown"
    "xml" -> "application/xml"
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}

fun getMimeTypeForFilename(filename: String): String {
    val extension = filename.substringAfterLast('.', "").lowercase()
    return getMimeType(extension)
}
