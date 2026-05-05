package com.inspiredandroid.kai.email

import com.inspiredandroid.kai.util.Logger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Minimal SMTP client for sending email replies.
 * Supports EHLO, AUTH LOGIN, STARTTLS, MAIL FROM, RCPT TO, DATA.
 */
class SmtpClient(
    private val host: String,
    private val port: Int = 587,
    private val useStartTls: Boolean = true,
) {
    private var connection: EmailConnection? = null

    suspend fun connect() {
        connection = createEmailConnection(host, port, tls = !useStartTls)
        // Read server greeting
        readResponse()
    }

    suspend fun ehlo(domain: String = "localhost") {
        writeLine("EHLO $domain")
        readResponse()
    }

    suspend fun startTls() {
        if (!useStartTls) return
        writeLine("STARTTLS")
        val response = readResponse()
        if (!response.startsWith("220")) {
            try { quit() } catch (e: Exception) { Logger.w("SmtpClient", "quit failed: ${e.message}") }
            throw Exception("STARTTLS failed: $response")
        }
        connection?.upgradeToTls(host)
        ehlo()
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun authenticate(username: String, password: String) {
        writeLine("AUTH LOGIN")
        val authResponse = readResponse()
        if (!authResponse.startsWith("334")) {
            throw Exception("AUTH LOGIN not supported: $authResponse")
        }

        // Send base64-encoded username
        writeLine(Base64.encode(username.encodeToByteArray()))
        val userResponse = readResponse()
        if (!userResponse.startsWith("334")) {
            throw Exception("Auth username rejected: $userResponse")
        }

        // Send base64-encoded password
        writeLine(Base64.encode(password.encodeToByteArray()))
        val passResponse = readResponse()
        if (!passResponse.startsWith("235")) {
            throw Exception("Authentication failed: $passResponse")
        }
    }

    suspend fun sendReply(
        from: String,
        to: String,
        subject: String,
        body: String,
        inReplyTo: String? = null,
    ): Boolean {
        val sanitizedFrom = from.sanitizeHeader()
        val sanitizedTo = to.sanitizeHeader()
        val sanitizedSubject = subject.sanitizeHeader()
        val sanitizedInReplyTo = inReplyTo?.sanitizeHeader()

        writeLine("MAIL FROM:<$sanitizedFrom>")
        var response = readResponse()
        if (!response.startsWith("250")) throw Exception("MAIL FROM failed: $response")

        writeLine("RCPT TO:<$sanitizedTo>")
        response = readResponse()
        if (!response.startsWith("250")) throw Exception("RCPT TO failed: $response")

        writeLine("DATA")
        response = readResponse()
        if (!response.startsWith("354")) throw Exception("DATA failed: $response")

        val headers = buildString {
            appendLine("From: $sanitizedFrom")
            appendLine("To: $sanitizedTo")
            appendLine("Subject: $sanitizedSubject")
            appendLine("MIME-Version: 1.0")
            appendLine("Content-Type: text/plain; charset=UTF-8")
            if (sanitizedInReplyTo != null) {
                appendLine("In-Reply-To: $sanitizedInReplyTo")
                appendLine("References: $sanitizedInReplyTo")
            }
            appendLine()
        }

        // Send message content line by line, escaping leading dots
        val fullMessage = headers + body
        for (line in fullMessage.lines()) {
            val escaped = if (line.startsWith(".")) ".$line" else line
            writeLine(escaped)
        }

        // End with <CRLF>.<CRLF>
        writeLine(".")
        response = readResponse()
        return response.startsWith("250")
    }

    suspend fun quit() {
        try {
            writeLine("QUIT")
            readResponse()
            connection?.close()
        } catch (_: Exception) {
            // Best-effort quit
        } finally {
            connection = null
        }
    }

    private suspend fun writeLine(line: String) {
        val conn = connection ?: throw IllegalStateException("Not connected")
        conn.writeLine(line)
    }

    private suspend fun readResponse(): String {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val result = StringBuilder()
        var lineCount = 0
        val maxLines = 100
        while (lineCount < maxLines) {
            val line = conn.readLine()
            result.appendLine(line)
            lineCount++
            if (line.length >= 4 && line[3] == ' ') break
            if (line.length < 4) break
        }
        return result.toString().trim()
    }
}

private fun String.sanitizeHeader(): String = replace("\r", "").replace("\n", "")
