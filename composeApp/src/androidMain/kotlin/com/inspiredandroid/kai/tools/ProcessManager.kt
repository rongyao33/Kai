package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.sandbox.LinuxSandboxManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ProcessManager(private val sandboxManager: LinuxSandboxManager) {

    data class SessionResult(
        val stdout: String = "",
        val stderr: String = "",
        val exitCode: Int? = null,
        val timedOut: Boolean = false,
    )

    class Session(
        val id: String,
        val command: String,
        val startTime: Long,
    ) {
        @Volatile var finished: Boolean = false
            private set
        private val _result = AtomicReference<SessionResult>(SessionResult())

        fun setResult(result: SessionResult) {
            _result.set(result)
            finished = true
        }

        val stdout: String get() = _result.get().stdout
        val stderr: String get() = _result.get().stderr
        val exitCode: Int? get() = _result.get().exitCode
        val timedOut: Boolean get() = _result.get().timedOut
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val nextId = AtomicInteger(1)
    private val MAX_FINISHED_SESSIONS = 50

    fun startBackground(
        command: String,
        timeoutSeconds: Long,
        workingDir: String,
        envMap: Map<String, String>,
    ): Map<String, Any> {
        val sessionId = "bg-${nextId.getAndIncrement()}"
        val session = Session(
            id = sessionId,
            command = command,
            startTime = System.currentTimeMillis(),
        )
        sessions[sessionId] = session

        val executor = sandboxManager.createProotExecutor()
        CompletableFuture.runAsync {
            val result = executor.execute(command, timeoutSeconds, workingDir, envMap)
            session.setResult(SessionResult(
                stdout = result["stdout"] as? String ?: "",
                stderr = result["stderr"] as? String ?: "",
                exitCode = result["exit_code"] as? Int ?: -1,
                timedOut = result["timed_out"] as? Boolean ?: false,
            ))
            evictOldSessions()
        }

        return mapOf(
            "success" to true,
            "session_id" to sessionId,
            "status" to "running",
            "message" to "Process started in background. Use manage_process tool to check status.",
        )
    }

    private fun evictOldSessions() {
        val finishedSessions = sessions.values.filter { it.finished }
            .sortedByDescending { it.startTime }
        if (finishedSessions.size > MAX_FINISHED_SESSIONS) {
            finishedSessions.drop(MAX_FINISHED_SESSIONS).forEach { sessions.remove(it.id) }
        }
    }

    fun list(): Map<String, Any> {
        val running = sessions.values.filter { !it.finished }.map { it.toInfo() }
        val finished = sessions.values.filter { it.finished }.map { it.toInfo() }
        return mapOf(
            "running" to running,
            "finished" to finished,
            "total" to sessions.size,
        )
    }

    fun log(sessionId: String, offset: Int, limit: Int): Map<String, Any> {
        val session = sessions[sessionId]
            ?: return mapOf("success" to false, "error" to "Unknown session: $sessionId")

        val stdoutLines = session.stdout.lines()
        val sliced = stdoutLines.drop(offset).take(limit).joinToString("\n")

        return mapOf(
            "success" to true,
            "session_id" to sessionId,
            "status" to if (session.finished) "finished" else "running",
            "exit_code" to (session.exitCode ?: -1),
            "stdout" to sliced,
            "stderr" to session.stderr.takeLast(2000),
            "total_stdout_lines" to stdoutLines.size,
            "offset" to offset,
            "timed_out" to session.timedOut,
        )
    }

    fun kill(sessionId: String): Map<String, Any> {
        val session = sessions[sessionId]
            ?: return mapOf("success" to false, "error" to "Unknown session: $sessionId")

        if (session.finished) {
            return mapOf("success" to true, "message" to "Process already finished", "exit_code" to (session.exitCode ?: -1))
        }

        session.setResult(SessionResult(
            stdout = session.stdout,
            stderr = session.stderr,
            exitCode = -1,
            timedOut = true,
        ))
        return mapOf("success" to true, "message" to "Process marked as terminated")
    }

    fun remove(sessionId: String): Map<String, Any> {
        sessions.remove(sessionId)
            ?: return mapOf("success" to false, "error" to "Unknown session: $sessionId")
        return mapOf("success" to true, "message" to "Session removed")
    }

    private fun Session.toInfo(): Map<String, Any> = mapOf(
        "session_id" to id,
        "command" to command,
        "status" to if (finished) "finished" else "running",
        "exit_code" to (exitCode ?: -1),
        "duration_seconds" to ((System.currentTimeMillis() - startTime) / 1000),
        "timed_out" to timedOut,
        "stdout_length" to stdout.length,
    )
}
