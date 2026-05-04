package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.SandboxSessions
import com.inspiredandroid.kai.data.currentConversationIdOrNull
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import com.inspiredandroid.kai.sandbox.LinuxSandboxManager
import com.inspiredandroid.kai.sandbox.SandboxState
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_git_cli_description
import kai.composeapp.generated.resources.tool_git_cli_name
import org.koin.java.KoinJavaComponent.inject

private const val TOOL_DESCRIPTION = """Execute Git commands in the Alpine Linux sandbox. Git is pre-installed and fully functional.

Common commands:
- status: Check repository status
- log: View commit history
- diff: Show changes
- branch: List/create branches
- checkout: Switch branches
- merge: Merge branches
- pull: Fetch and merge from remote
- push: Push to remote
- commit: Create a commit
- add: Stage files
- reset: Unstage or reset commits
- stash: Temporarily save changes
- clone: Clone a repository
- remote: Manage remote repositories

Parameters:
- command: The git command to execute (without 'git' prefix)
- repo_path: Path to repository (default: /root)
- timeout: Timeout in seconds (default 30, max 120)

Examples:
- command: "status" -> git status
- command: "log --oneline -10" -> git log --oneline -10
- command: "commit -m 'message'" -> git commit -m 'message'"""

object GitCliTool : Tool {
    private val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)

    override val schema = ToolSchema(
        name = "git_cli",
        description = TOOL_DESCRIPTION,
        parameters = mapOf(
            "command" to ParameterSchema("string", "Git command to execute (without 'git' prefix)", true),
            "repo_path" to ParameterSchema("string", "Path to git repository (default: /root)", false),
            "timeout" to ParameterSchema("integer", "Timeout in seconds (default 30, max 120)", false),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val command = args["command"] as? String ?: return mapOf("error" to "Command is required")
        val repoPath = args["repo_path"] as? String ?: "/root"
        val timeoutSeconds = ((args["timeout"] as? Number)?.toLong() ?: 30L).coerceIn(1, 120L)

        val state = sandboxManager.state.value
        if (state !is SandboxState.Ready) {
            return mapOf(
                "success" to false,
                "error" to "Linux sandbox is not ready. Current state: $state",
            )
        }

        val fullCommand = buildGitCommand(command, repoPath)
        val sessionId = currentConversationIdOrNull() ?: SandboxSessions.DEFAULT

        return try {
            sandboxManager.shellFor(sessionId).run(
                command = fullCommand,
                timeoutSeconds = timeoutSeconds,
                displayCommand = fullCommand,
            )
        } catch (e: Exception) {
            mapOf(
                "success" to false,
                "error" to (e.message ?: "Git command execution failed"),
                "command" to fullCommand,
            )
        }
    }

    private fun buildGitCommand(command: String, repoPath: String): String {
        val sanitizedCommand = command.trim()
            .replace(";", " ")
            .replace("&&", " ")
            .replace("||", " ")
            .replace("|", " ")
            .replace("`", "")
            .replace("\$", "")

        return if (repoPath == "/root" || repoPath == ".") {
            "git $sanitizedCommand"
        } else {
            "git -C \"$repoPath\" $sanitizedCommand"
        }
    }

    val toolInfo = ToolInfo(
        id = "git_cli",
        name = "Git CLI",
        description = "Execute Git commands for repository management",
        nameRes = Res.string.tool_git_cli_name,
        descriptionRes = Res.string.tool_git_cli_description,
        isEnabled = true,
    )
}
