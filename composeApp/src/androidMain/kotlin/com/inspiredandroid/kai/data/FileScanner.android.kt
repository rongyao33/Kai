package com.inspiredandroid.kai.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.inspiredandroid.kai.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual class FileScanner(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    private val supportedExtensions = listOf(
        "pdf" to "application/pdf",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "doc" to "application/msword",
        "txt" to "text/plain",
        "md" to "text/markdown",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
    )

    actual suspend fun scanDirectory(path: String): List<ScannedFile> = withContext(Dispatchers.IO) {
        val files = mutableListOf<ScannedFile>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )

        val selectionArgs = mutableListOf<String>()
        val selection = buildString {
            append("(")
            supportedExtensions.forEachIndexed { index, (ext, _) ->
                if (index > 0) append(" OR ")
                append("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
                selectionArgs.add("%.$ext")
            }
            append(")")
            if (path.isNotBlank()) {
                append(" AND ${MediaStore.Files.FileColumns.DATA} LIKE ?")
                selectionArgs.add("$path%")
            }
        }

        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs.toTypedArray(),
                sortOrder,
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val pathColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val mimeColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

                while (it.moveToNext()) {
                    val name = it.getString(nameColumn) ?: "unknown"
                    val filePath = it.getString(pathColumn) ?: continue
                    val mimeType = it.getString(mimeColumn)
                    val size = it.getLong(sizeColumn)
                    val modifiedAt = it.getLong(dateColumn) * 1000

                    files.add(
                        ScannedFile(
                            path = filePath,
                            name = name,
                            mimeType = mimeType,
                            size = size,
                            modifiedAt = modifiedAt,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            Logger.e("FileScanner", "Failed to scan: ${e.message}")
        } finally {
            cursor?.close()
        }

        files
    }

    actual fun getSupportedExtensions(): List<String> = supportedExtensions.map { it.first }
}
