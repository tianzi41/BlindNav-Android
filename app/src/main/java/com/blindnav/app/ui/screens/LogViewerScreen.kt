/**
 * LogViewerScreen.kt - 日志查看界面
 * 读取本应用的 logcat 日志，支持复制全部
 */
package com.blindnav.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var logLines by remember { mutableStateOf(loadLogs()) }
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { clearLogs(); logLines = loadLogs(); Toast.makeText(context, "已清除旧日志", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.Delete, "清除旧日志") }
                    // 刷新按钮
                    IconButton(onClick = { logLines = loadLogs() }) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                    // 复制全部按钮
                    IconButton(onClick = {
                        copyAllLogs(context, logLines)
                    }) {
                        Icon(Icons.Default.ContentCopy, "复制全部")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (logLines.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(logLines) { line ->
                    LogLineItem(line)
                }
            }
        }
    }
}

@Composable
private fun LogLineItem(line: String) {
    // 根据日志级别着色
    val color = when {
        line.contains(" E ") || line.contains("Error") -> MaterialTheme.colorScheme.error
        line.contains(" W ") || line.contains("Warning") -> MaterialTheme.colorScheme.tertiary
        line.contains(" I ") -> MaterialTheme.colorScheme.primary
        line.contains(" D ") -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    Text(
        text = line,
        color = color,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = 14.sp,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}

/**
 * 加载本应用的 logcat 日志
 */
private fun loadLogs(): List<String> {
    return try {
        val process = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "brief", "-s",
                "YoloOnnxEngine:*",
                "NavigationMaster:*",
                "BlindPathDetector:*",
                "MainViewModel:*",
                "CrosswalkDetector:*",
                "ObstacleDetector:*",
                "TrafficLightDetector:*",
                "AudioPlayerManager:*",
                "CameraManager:*")
        )
        val output = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        // 取最后 500 行，倒序显示（最新在上）
        output.takeLast(500).reversed()
    } catch (e: Exception) {
        listOf("读取日志失败: ${e.message}")
    }
}

/**
 * 清除 logcat 缓冲区（清除旧会话日志）
 */
private fun clearLogs() {
    try {
        Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
    } catch (_: Exception) {}
}

/**
 * 复制全部日志到剪贴板
 */
private fun copyAllLogs(context: Context, lines: List<String>) {
    val text = lines.joinToString("\n")
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("app_logs", text))
    Toast.makeText(context, "已复制 ${lines.size} 行日志", Toast.LENGTH_SHORT).show()
}
