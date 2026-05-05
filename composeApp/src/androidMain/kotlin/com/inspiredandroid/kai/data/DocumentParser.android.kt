package com.inspiredandroid.kai.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.inspiredandroid.kai.util.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.io.BufferedReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual class DocumentParser {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    actual suspend fun parseText(filePath: String): String? {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                Logger.w("DocumentParser", "File not found: $filePath")
                return null
            }

            when {
                filePath.endsWith(".pdf", ignoreCase = true) -> parsePdf(file)
                filePath.endsWith(".docx", ignoreCase = true) -> parseDocx(file)
                filePath.endsWith(".txt", ignoreCase = true) -> parseTxt(file)
                filePath.endsWith(".md", ignoreCase = true) -> parseTxt(file)
                isImageFile(filePath) -> parseImage(file)
                else -> {
                    Logger.w("DocumentParser", "Unsupported file type: $filePath")
                    null
                }
            }
        } catch (e: Exception) {
            Logger.e("DocumentParser", "Failed to parse $filePath: ${e.message}")
            null
        }
    }

    private fun isImageFile(filePath: String): Boolean {
        val lower = filePath.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
               lower.endsWith(".png") || lower.endsWith(".gif") ||
               lower.endsWith(".webp") || lower.endsWith(".bmp")
    }

    private suspend fun extractTextFromBitmap(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                continuation.resume(visionText.text)
            }
            .addOnFailureListener { e ->
                continuation.resumeWithException(e)
            }
    }

    private suspend fun parsePdf(file: File): String? {
        try {
            val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fileDescriptor)
            val pageCount = renderer.pageCount

            if (pageCount == 0) {
                renderer.close()
                fileDescriptor.close()
                Logger.w("DocumentParser", "PDF has no pages: ${file.name}")
                return null
            }

            val allText = StringBuilder()

            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)

                val scale = 2.0f
                val width = (page.width * scale).toInt()
                val height = (page.height * scale).toInt()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                try {
                    val pageText = extractTextFromBitmap(bitmap)
                    if (pageText.isNotBlank()) {
                        if (allText.isNotEmpty()) {
                            allText.append("\n\n--- Page ${i + 1} ---\n\n")
                        }
                        allText.append(pageText)
                    }
                } catch (e: Exception) {
                    Logger.w("DocumentParser", "Failed to extract text from page ${i + 1}: ${e.message}")
                } finally {
                    bitmap.recycle()
                }
            }

            renderer.close()
            fileDescriptor.close()

            val result = allText.toString()
            if (result.isBlank()) {
                Logger.w("DocumentParser", "No text extracted from PDF: ${file.name}")
                return null
            }

            Logger.d("DocumentParser", "Extracted ${result.length} characters from ${pageCount} pages")
            return result
        } catch (e: Exception) {
            Logger.e("DocumentParser", "Failed to parse PDF: ${e.message}")
            return null
        }
    }

    private suspend fun parseImage(file: File): String? {
        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: run {
                Logger.w("DocumentParser", "Failed to decode image: ${file.absolutePath}")
                return null
            }

            try {
                val text = extractTextFromBitmap(bitmap)
                if (text.isBlank()) {
                    Logger.w("DocumentParser", "No text found in image: ${file.name}")
                    return null
                }
                Logger.d("DocumentParser", "OCR extracted ${text.length} characters from ${file.name}")
                text
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Logger.e("DocumentParser", "Failed to OCR image: ${e.message}")
            null
        }
    }

    actual fun getTextChunks(content: String, chunkSize: Int): List<String> {
        if (content.isBlank()) return emptyList()

        val chunks = mutableListOf<String>()
        val paragraphs = content.split("\n\n")

        var currentChunk = StringBuilder()

        for (paragraph in paragraphs) {
            if (paragraph.isBlank()) continue

            if (currentChunk.length + paragraph.length + 2 <= chunkSize) {
                if (currentChunk.isNotEmpty()) {
                    currentChunk.append("\n\n")
                }
                currentChunk.append(paragraph)
            } else {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString())
                }
                currentChunk.clear()

                if (paragraph.length > chunkSize) {
                    val words = paragraph.split(" ")
                    for (word in words) {
                        if (currentChunk.length + word.length + 1 <= chunkSize) {
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

    private fun parseDocx(file: File): String? {
        FileInputStream(file).use { fis ->
            XWPFDocument(fis).use { document ->
                val paragraphs = document.paragraphs
                return paragraphs.joinToString("\n\n") { it.text }
            }
        }
    }

    private fun parseTxt(file: File): String? {
        return try {
            BufferedReader(FileReader(file)).use { reader ->
                reader.readText()
            }
        } catch (e: Exception) {
            Logger.e("DocumentParser", "Failed to read text file: ${e.message}")
            null
        }
    }
}
