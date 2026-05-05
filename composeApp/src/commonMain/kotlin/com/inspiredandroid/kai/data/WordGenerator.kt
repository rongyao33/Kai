package com.inspiredandroid.kai.data

import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

class WordGenerator : FileGenerator {
    override val supportedExtensions = listOf("docx")

    override fun generate(content: String, options: Map<String, String>): ByteArray {
        return try {
            createWordDocument(content)
        } catch (e: Exception) {
            content.toByteArray(Charset.forName("UTF-8"))
        }
    }

    private fun createWordDocument(content: String): ByteArray {
        val document = XWPFDocument()
        val lines = content.split("\n")

        for (line in lines) {
            val paragraph: XWPFParagraph = document.createParagraph()
            paragraph.createRun().setText(line)
        }

        val outputStream = ByteArrayOutputStream()
        document.write(outputStream)
        document.close()

        return outputStream.toByteArray()
    }
}