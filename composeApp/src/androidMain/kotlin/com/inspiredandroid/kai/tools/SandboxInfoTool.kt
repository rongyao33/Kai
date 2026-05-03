package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.SandboxSessions
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import com.inspiredandroid.kai.sandbox.LinuxSandboxManager
import com.inspiredandroid.kai.sandbox.SandboxState
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_sandbox_info_description
import kai.composeapp.generated.resources.tool_sandbox_info_name
import org.koin.java.KoinJavaComponent.inject

private const val TOOL_DESCRIPTION = """Get information about the Linux sandbox environment including status, disk usage, and available tools.

Returns:
- status: "ready", "not_installed", "installing", or "error"
- disk_usage_mb: Disk usage in megabytes
- available_tools: List of pre-installed tools
- arch: CPU architecture (aarch64, x86_64, etc.)
- home_path: Path to persistent home directory
- packages_installed: Whether base packages are installed

Use this to check sandbox health before running complex commands, or to understand the current environment capabilities."""

object SandboxInfoTool : Tool {
    private val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)

    override val schema = ToolSchema(
        name = "sandbox_info",
        description = TOOL_DESCRIPTION,
        parameters = emptyMap(),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val state = sandboxManager.state.value
        val status = when (state) {
            is SandboxState.Ready -> "ready"
            is SandboxState.NotInstalled -> "not_installed"
            is SandboxState.Downloading -> "downloading"
            is SandboxState.Extracting -> "extracting"
            is SandboxState.Installing -> "installing"
            is SandboxState.Error -> "error"
        }

        val diskUsage = if (state is SandboxState.Ready) {
            sandboxManager.getDiskUsageMB()
        } else {
            0L
        }

        val availableTools = listOf(
            "python3", "pip3", "node", "npm",
            "gcc", "g++", "make", "cmake",
            "git", "curl", "wget", "jq",
            "ssh", "scp", "sftp", "rsync",
            "sqlite3", "redis-cli",
            "ffmpeg", "convert",
            "rg", "fd", "bat", "tree",
            "vim", "nano", "micro",
            "gdb", "strace",
        )

        return mapOf(
            "status" to status,
            "disk_usage_mb" to diskUsage,
            "available_tools" to availableTools,
            "arch" to sandboxManager.getArch(),
            "home_path" to sandboxManager.homePath,
            "packages_installed" to sandboxManager.arePackagesInstalled(),
            "sessions_active" to sandboxManager.sessions.value.size,
        )
    }

    val toolInfo = ToolInfo(
        id = "sandbox_info",
        name = "Sandbox Info",
        description = "Get Linux sandbox environment information",
        nameRes = Res.string.tool_sandbox_info_name,
        descriptionRes = Res.string.tool_sandbox_info_description,
        isEnabled = false,
    )
}
