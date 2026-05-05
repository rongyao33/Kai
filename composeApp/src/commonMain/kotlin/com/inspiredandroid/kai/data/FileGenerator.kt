package com.inspiredandroid.kai.data

import java.nio.charset.Charset

interface FileGenerator {
    val supportedExtensions: List<String>
    fun generate(content: String, options: Map<String, String> = emptyMap()): ByteArray
}

class PlainTextGenerator : FileGenerator {
    override val supportedExtensions = listOf("txt", "text")
    override fun generate(content: String, options: Map<String, String>): ByteArray {
        val encoding = options["encoding"] ?: "UTF-8"
        return content.toByteArray(Charset.forName(encoding))
    }
}

class CsvGenerator : FileGenerator {
    override val supportedExtensions = listOf("csv")
    override fun generate(content: String, options: Map<String, String>): ByteArray {
        val delimiter = options["delimiter"]?.firstOrNull() ?: ','
        val lines = content.lines().filter { it.isNotBlank() }

        val sb = StringBuilder()
        lines.forEachIndexed { index, line ->
            if (index == 0 && options["hasHeader"] != "false") {
                sb.appendLine(processHeaderLine(line, delimiter))
            } else {
                sb.appendLine(processDataLine(line, delimiter))
            }
        }

        val encoding = options["encoding"] ?: "UTF-8"
        val BOM = if (encoding == "UTF-8") "\uFEFF" else ""
        return (BOM + sb).toByteArray(Charset.forName(encoding))
    }

    private fun processHeaderLine(line: String, delimiter: Char): String {
        return line.split(delimiter).joinToString(delimiter.toString()) { escapeField(it, delimiter) }
    }

    private fun processDataLine(line: String, delimiter: Char): String {
        return line.split(delimiter).joinToString(delimiter.toString()) { escapeField(it, delimiter) }
    }

    private fun escapeField(field: String, delimiter: Char): String {
        val trimmed = field.trim()
        return if (trimmed.contains(delimiter) || trimmed.contains('"') || trimmed.contains('\n')) {
            "\"${trimmed.replace("\"", "\"\"")}\""
        } else {
            trimmed
        }
    }
}

class JsonGenerator : FileGenerator {
    override val supportedExtensions = listOf("json")
    override fun generate(content: String, options: Map<String, String>): ByteArray {
        val prettyPrint = options["prettyPrint"] != "false"
        val minified = if (prettyPrint) {
            try {
                val json = org.json.JSONObject(content)
                json.toString(2)
            } catch (e: Exception) {
                try {
                    val json = org.json.JSONArray(content)
                    json.toString(2)
                } catch (e: Exception) {
                    content
                }
            }
        } else {
            content
        }
        return minified.toByteArray(Charset.forName("UTF-8"))
    }
}

class HtmlGenerator : FileGenerator {
    override val supportedExtensions = listOf("html", "htm")
    override fun generate(content: String, options: Map<String, String>): ByteArray {
        val title = options["title"] ?: "Document"
        val css = options["css"] ?: getDefaultCss()

        val body = if (content.lines().any { it.trim().startsWith("<") }) {
            content
        } else {
            convertMarkdownToHtml(content)
        }

        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>$title</title>
                <style>$css</style>
            </head>
            <body>
                <div class="content">
                    $body
                </div>
            </body>
            </html>
        """.trimIndent()

        return html.toByteArray(Charset.forName("UTF-8"))
    }

    private fun getDefaultCss(): String = """
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            line-height: 1.6;
            max-width: 800px;
            margin: 0 auto;
            padding: 20px;
            color: #333;
        }
        pre {
            background: #f4f4f4;
            padding: 15px;
            border-radius: 5px;
            overflow-x: auto;
        }
        code {
            background: #f4f4f4;
            padding: 2px 5px;
            border-radius: 3px;
        }
        table {
            border-collapse: collapse;
            width: 100%;
            margin: 15px 0;
        }
        th, td {
            border: 1px solid #ddd;
            padding: 8px;
            text-align: left;
        }
        th {
            background: #f4f4f4;
        }
    """.trimIndent()

    private fun convertMarkdownToHtml(markdown: String): String {
        val lines = markdown.lines()
        val sb = StringBuilder()
        var inCodeBlock = false
        var inList = false

        lines.forEach { line ->
            when {
                line.startsWith("```") -> {
                    if (inCodeBlock) {
                        sb.appendLine("</code></pre>")
                    } else {
                        val lang = line.removePrefix("```").trim()
                        sb.appendLine("<pre><code class=\"language-$lang\">")
                    }
                    inCodeBlock = !inCodeBlock
                }
                inCodeBlock -> sb.appendLine(line)
                line.startsWith("# ") -> sb.appendLine("<h1>${line.removePrefix("# ")}</h1>")
                line.startsWith("## ") -> sb.appendLine("<h2>${line.removePrefix("## ")}</h2>")
                line.startsWith("### ") -> sb.appendLine("<h3>${line.removePrefix("### ")}</h3>")
                line.startsWith("- ") || line.startsWith("* ") -> {
                    if (!inList) sb.appendLine("<ul>")
                    sb.appendLine("<li>${line.removePrefix("- ").removePrefix("* ")}</li>")
                    inList = true
                }
                line.isBlank() -> {
                    if (inList) sb.appendLine("</ul>")
                    inList = false
                    sb.appendLine()
                }
                else -> sb.appendLine("<p>$line</p>")
            }
        }
        if (inList) sb.appendLine("</ul>")
        return sb.toString()
    }
}

class MarkdownGenerator : FileGenerator {
    override val supportedExtensions = listOf("md", "markdown")
    override fun generate(content: String, options: Map<String, String>): ByteArray {
        return content.toByteArray(Charset.forName("UTF-8"))
    }
}

class XmlGenerator : FileGenerator {
    override val supportedExtensions = listOf("xml")
    override fun generate(content: String, options: Map<String, String>): ByteArray {
        return content.toByteArray(Charset.forName("UTF-8"))
    }
}

object FileGeneratorRegistry {
    private val generators = listOf(
        PlainTextGenerator(),
        CsvGenerator(),
        JsonGenerator(),
        HtmlGenerator(),
        MarkdownGenerator(),
        XmlGenerator(),
        WordGenerator(),
    )

    fun getGenerator(extension: String): FileGenerator? {
        return generators.find { extension.lowercase() in it.supportedExtensions }
    }

    fun isSupported(extension: String): Boolean {
        return generators.any { extension.lowercase() in it.supportedExtensions }
    }

    fun supportedExtensions(): List<String> {
        return generators.flatMap { it.supportedExtensions }.distinct().sorted()
    }
}
