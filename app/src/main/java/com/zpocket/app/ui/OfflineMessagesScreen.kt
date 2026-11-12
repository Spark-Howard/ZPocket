package com.zpocket.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zpocket.app.data.CachedMessage
import com.zpocket.app.data.CachedSession
import com.zpocket.app.data.Connection
import com.zpocket.app.data.MessageCacheStore
import com.zpocket.app.data.MessageSegment
import com.zpocket.app.data.OfflineImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 离线消息：某条连接缓存的会话列表。数据全部来自连接期间注入脚本的自动捕获，
 * 离线（页面加载失败）或不想连接时也能回看。
 */
@Composable
fun OfflineMessagesScreen(
    connection: Connection,
    store: MessageCacheStore,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    var sessions by remember { mutableStateOf(store.sessionsFor(connection.id)) }
    var confirmClear by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .height(56.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("离线消息", style = MaterialTheme.typography.titleMedium)
                        Text(
                            connection.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = sessions.isNotEmpty(),
                    ) {
                        Text("清除")
                    }
                }
            }

            if (sessions.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有离线缓存\n\n连接电脑端 ZCode 时浏览过的会话，\n会自动保存到这里供离线查看",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(sessions, key = { it.key }) { session ->
                        SessionRow(
                            session = session,
                            timeText = timeFormat.format(Date(session.updatedAt)),
                            onClick = { onOpenSession(session.key) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除离线缓存") },
            text = { Text("将删除「${connection.name}」缓存的全部离线会话和图片，下次连接时重新缓存。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    store.clearConnection(connection.id)
                    sessions = store.sessionsFor(connection.id)
                }) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

/** 离线转录详情：按消息顺序展示文本 / 代码块 / 图片，只读。 */
@Composable
fun OfflineTranscriptScreen(
    connection: Connection,
    sessionKey: String,
    store: MessageCacheStore,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val session = remember(sessionKey) {
        store.sessionsFor(connection.id).firstOrNull { it.key == sessionKey }
    }
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA) }
    var viewerBitmap by remember { mutableStateOf<Bitmap?>(null) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .height(56.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            session?.title ?: "离线消息",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        session?.let {
                            Text(
                                "更新于 ${timeFormat.format(Date(it.updatedAt))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (session == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "该会话的缓存已被清除或超出容量被淘汰",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "离线缓存 · 仅可查看，不可发送",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(session.messages) { message ->
                        MessageBubble(
                            message = message,
                            connectionId = connection.id,
                            onOpenImage = { viewerBitmap = it },
                        )
                    }
                }
            }
        }
    }

    viewerBitmap?.let { bmp ->
        Dialog(
            onDismissRequest = { viewerBitmap = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { viewerBitmap = null },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
private fun SessionRow(session: CachedSession, timeText: String, onClick: () -> Unit) {
    val images = session.messages.sumOf { m ->
        m.segments.count { it is MessageSegment.Image }
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                session.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "$timeText · ${session.messages.size} 条消息" + if (images > 0) " · $images 张图片" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: CachedMessage,
    connectionId: String,
    onOpenImage: (Bitmap) -> Unit,
) {
    val isUser = message.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message.segments.forEach { seg ->
                    when (seg) {
                        is MessageSegment.Text -> SelectionContainer {
                            Text(
                                seg.text,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        is MessageSegment.Code -> CodeBlock(seg.text, seg.lang)
                        is MessageSegment.Image -> CachedImage(
                            ref = seg.ref,
                            width = seg.width,
                            height = seg.height,
                            connectionId = connectionId,
                            onOpen = onOpenImage,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, lang: String) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                lang.ifBlank { "代码" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(code))
            }) {
                Text("复制", style = MaterialTheme.typography.labelMedium)
            }
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF16181D),
            contentColor = Color(0xFFD6D9DE),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                ),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/** 从图片缓存目录异步解码显示；文件不存在（未缓存到/被清理）时显示占位。 */
@Composable
private fun CachedImage(
    ref: String,
    width: Int,
    height: Int,
    connectionId: String,
    onOpen: (Bitmap) -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(ref) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(ref) {
        bitmap = withContext(Dispatchers.IO) {
            OfflineImageStore.fileFor(context, ref, connectionId)?.let { decodeSampled(it, 1080) }
        }
    }
    val bmp = bitmap
    if (bmp == null) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "图片未缓存（浏览时未加载到）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        val ratio = if (width > 0 && height > 0) width.toFloat() / height else null
        Image(
            bmp.asImageBitmap(),
            contentDescription = "离线图片",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .let { if (ratio != null && ratio in 0.2f..5f) it.aspectRatio(ratio) else it }
                .clickable { onOpen(bmp) },
        )
    }
}

private fun decodeSampled(file: File, targetWidth: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
    BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}.getOrNull()
