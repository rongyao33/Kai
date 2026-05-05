package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay

enum class ToolExecutionStatus {
    WAITING,
    RUNNING,
    SUCCESS,
    FAILED,
}

data class ToolExecutionNode(
    val id: String,
    val name: String,
    val status: ToolExecutionStatus,
    val durationMs: Long? = null,
    val error: String? = null,
    val isParallel: Boolean = false,
)

@Composable
internal fun ToolExecutionFlow(
    executingTools: ImmutableList<Pair<String, String>>,
    toolExecutions: ImmutableList<ToolExecutionNode>? = null,
    isStatusOnly: Boolean = false,
    statusText: String? = null,
    modifier: Modifier = Modifier,
) {
    val nodes: ImmutableList<ToolExecutionNode> = toolExecutions
        ?: executingTools.map { (id, name) ->
            ToolExecutionNode(
                id = id,
                name = name,
                status = ToolExecutionStatus.RUNNING,
                durationMs = null,
            )
        }.toImmutableList()

    var expanded by remember { mutableStateOf(true) }

    val summary = statusText ?: when {
        executingTools.isEmpty() && nodes.isEmpty() -> null
        nodes.size == 1 -> nodes.first().name
        else -> "${nodes.size} tools"
    }

    val headerText = when {
        isStatusOnly -> summary ?: "Working..."
        nodes.size == 1 -> "Executing: ${nodes.first().name}"
        else -> "Executing ${nodes.size} tools"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = nodes.isNotEmpty()) { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PulsingDot(
                        color = when {
                            nodes.any { it.status == ToolExecutionStatus.FAILED } -> Color(0xFFE53935)
                            nodes.any { it.status == ToolExecutionStatus.RUNNING } -> Color(0xFFFFA726)
                            nodes.all { it.status == ToolExecutionStatus.SUCCESS } -> Color(0xFF66BB6A)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = headerText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (nodes.isNotEmpty()) {
                    Text(
                        text = if (expanded) "▲" else "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (expanded && nodes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                FlowGraph(nodes = nodes)
            }
        }
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulsing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulsing_alpha",
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

@Composable
private fun FlowGraph(nodes: ImmutableList<ToolExecutionNode>) {
    Column(
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        nodes.forEachIndexed { index, node ->
            FlowNodeRow(
                node = node,
                showConnector = index < nodes.size - 1,
                isLast = index == nodes.size - 1,
            )
        }
    }
}

@Composable
private fun FlowNodeRow(
    node: ToolExecutionNode,
    showConnector: Boolean,
    isLast: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusIcon(status = node.status, size = 24.dp)
            if (showConnector) {
                Canvas(modifier = Modifier.size(2.dp, 24.dp)) {
                    drawLine(
                        color = Color(0xFF9E9E9E),
                        start = Offset(size.width / 2, 0f),
                        end = Offset(size.width / 2, size.height),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (node.status) {
                        ToolExecutionStatus.FAILED -> Color(0xFFE53935)
                        ToolExecutionStatus.RUNNING -> Color(0xFFFFA726)
                        ToolExecutionStatus.SUCCESS -> MaterialTheme.colorScheme.onSurface
                        ToolExecutionStatus.WAITING -> MaterialTheme.colorScheme.onSurface
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (node.status) {
                        ToolExecutionStatus.RUNNING -> {
                            val infiniteTransition = rememberInfiniteTransition(label = "running")
                            val dotAlpha by infiniteTransition.animateFloat(
                                initialValue = 0.3f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(500),
                                    repeatMode = RepeatMode.Reverse,
                                ),
                                label = "running_alpha",
                            )
                            Text(
                                text = formatDuration(node.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dotAlpha),
                            )
                        }
                        ToolExecutionStatus.SUCCESS -> {
                            Text(
                                text = formatDuration(node.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF66BB6A),
                            )
                        }
                        ToolExecutionStatus.FAILED -> {
                            Text(
                                text = "Error",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE53935),
                            )
                        }
                        ToolExecutionStatus.WAITING -> {
                            Text(
                                text = "Waiting",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (node.error != null) {
                Text(
                    text = node.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE53935),
                    maxLines = 2,
                )
            }
            if (showConnector || !isLast) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun StatusIcon(status: ToolExecutionStatus, size: androidx.compose.ui.unit.Dp) {
    val backgroundColor = when (status) {
        ToolExecutionStatus.WAITING -> Color(0xFF9E9E9E)
        ToolExecutionStatus.RUNNING -> Color(0xFFFFA726)
        ToolExecutionStatus.SUCCESS -> Color(0xFF66BB6A)
        ToolExecutionStatus.FAILED -> Color(0xFFE53935)
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            ToolExecutionStatus.SUCCESS -> Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
            ToolExecutionStatus.FAILED -> Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
            ToolExecutionStatus.RUNNING -> {
                val infiniteTransition = rememberInfiniteTransition(label = "spinning")
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = LinearEasing),
                    ),
                    label = "spinning_rotation",
                )
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(rotation),
                )
            }
            ToolExecutionStatus.WAITING -> {}
        }
    }
}

private fun formatDuration(durationMs: Long?): String {
    if (durationMs == null) return ""
    return when {
        durationMs < 1000 -> "${durationMs}ms"
        durationMs < 60000 -> "${durationMs / 1000}.${(durationMs % 1000) / 100}s"
        else -> "${durationMs / 60000}m ${(durationMs % 60000) / 1000}s"
    }
}