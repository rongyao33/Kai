@file:OptIn(ExperimentalComposeUiApi::class)

package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.data.FileOutput
import com.inspiredandroid.kai.ui.handCursor

@Composable
internal fun FileOutputMessage(
    files: List<FileOutput>,
    onSave: (FileOutput) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        files.forEach { file ->
            FileOutputCard(file = file, onSave = onSave)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FileOutputCard(
    file: FileOutput,
    onSave: (FileOutput) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(
            extension = file.extension,
            modifier = Modifier.size(40.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.filename,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = getFileTypeDescription(file.extension),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SaveFileButton(onClick = { onSave(file) })
    }
}

@Composable
private fun FileIcon(
    extension: String,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = getFileIconColor(extension)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun SaveFileButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .handCursor()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = "Save",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Save",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private fun getFileIconColor(extension: String): androidx.compose.ui.graphics.Color {
    return when (extension.lowercase()) {
        "csv" -> androidx.compose.ui.graphics.Color(0xFFE8F5E9)
        "json" -> androidx.compose.ui.graphics.Color(0xFFFFF3E0)
        "html", "htm" -> androidx.compose.ui.graphics.Color(0xFFE3F2FD)
        "md", "markdown" -> androidx.compose.ui.graphics.Color(0xFFF3E5F5)
        "txt" -> androidx.compose.ui.graphics.Color(0xFFECEFF1)
        "xml" -> androidx.compose.ui.graphics.Color(0xFFE8F5E9)
        "pdf" -> androidx.compose.ui.graphics.Color(0xFFFFEBEE)
        else -> androidx.compose.ui.graphics.Color(0xFFECEFF1)
    }
}

private fun getFileTypeDescription(extension: String): String {
    return when (extension.lowercase()) {
        "csv" -> "CSV Spreadsheet"
        "json" -> "JSON Data"
        "html", "htm" -> "HTML Document"
        "md", "markdown" -> "Markdown"
        "txt" -> "Plain Text"
        "xml" -> "XML Data"
        "pdf" -> "PDF Document"
        else -> "File"
    }
}
