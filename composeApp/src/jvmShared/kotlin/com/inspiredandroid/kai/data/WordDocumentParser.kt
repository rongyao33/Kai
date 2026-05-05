package com.inspiredandroid.kai.data

import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import java.io.ByteArrayInputStream

actual fun parseWordDocument(bytes: ByteArray, fileName: String): String? {
    return try {
        val isDocx = fileName.endsWith(".docx", ignoreCase = true)
        val isDoc = fileName.endsWith(".doc", ignoreCase = true)

        if (!isDocx && !isDoc) return null

        val document = if (isDocx) {
            XWPFDocument(ByteArrayInputStream(bytes))
        } else {
            return null
        }

        val text = StringBuilder()
        for (paragraph in document.paragraphs) {
            val paragraphText = paragraph.text
            if (paragraphText.isNotBlank()) {
                text.appendLine(paragraphText)
            }
        }
        document.close()

        text.toString().ifBlank { null }
    } catch (e: Exception) {
        null
    }
}