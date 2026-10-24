package com.zpocket.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zpocket.app.data.Connection
import com.zpocket.app.data.ConnectionStore
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object Scanner : Screen
    data class Remote(val connection: Connection) : Screen
}

/** 首次进入的页面：开了自动重连且有历史记录时，直接进上次的远程界面。 */
private fun initialScreen(store: ConnectionStore): Screen {
    if (!store.isAutoConnectEnabled()) return Screen.Home
    return store.load().firstOrNull()?.let { Screen.Remote(it) } ?: Screen.Home
}

@Composable
fun AppRoot(store: ConnectionStore, incomingUrl: MutableState<String?>) {
    var screen by remember { mutableStateOf(initialScreen(store)) }
    var history by remember { mutableStateOf(store.load()) }
    var autoConnect by remember { mutableStateOf(store.isAutoConnectEnabled()) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentVersion = remember { UpdateChecker.currentVersion(context) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun refresh() {
        history = store.load()
    }

    fun connect(url: String): Boolean {
        val conn = store.record(url) ?: return false
        refresh()
        screen = Screen.Remote(conn)
        return true
    }

    fun checkUpdate(manual: Boolean) {
        if (checking) return
        checking = true
        scope.launch {
            val latest = UpdateChecker.fetchLatest()
            checking = false
            when {
                latest == null ->
                    if (manual) {
                        Toast.makeText(context, "检查更新失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    }

                UpdateChecker.isNewer(latest.version, currentVersion) -> update = latest

                manual ->
                    Toast.makeText(
                        context,
                        "已是最新版本（v$currentVersion）",
                        Toast.LENGTH_SHORT,
                    ).show()
            }
        }
    }

    // 启动时静默检查一次；更新弹窗只在主页展示，不打断远程会话
    LaunchedEffect(Unit) { checkUpdate(manual = false) }

    LaunchedEffect(incomingUrl.value) {
        incomingUrl.value?.let {
            connect(it)
            incomingUrl.value = null
        }
    }

    when (val current = screen) {
        Screen.Home -> HomeScreen(
            history = history,
            autoConnect = autoConnect,
            currentVersion = currentVersion,
            checkingUpdate = checking,
            onAutoConnectChange = {
                autoConnect = it
                store.setAutoConnectEnabled(it)
            },
            onCheckUpdate = { checkUpdate(manual = true) },
            onScan = { screen = Screen.Scanner },
            onOpen = { connect(it.url) },
            onDelete = {
                store.delete(it.id)
                refresh()
            },
        )

        Screen.Scanner -> ScannerScreen(
            onBack = {
                refresh()
                screen = Screen.Home
            },
            onScanned = { url -> connect(url) },
        )

        is Screen.Remote -> RemoteScreen(
            connection = current.connection,
            onBack = {
                refresh()
                screen = Screen.Home
            },
            onRescan = { screen = Screen.Scanner },
            onForget = {
                store.delete(current.connection.id)
                refresh()
                screen = Screen.Home
            },
        )
    }

    // 更新弹窗：只在主页出现，避免打断正在进行的远程操控
    update?.let { info ->
        if (screen == Screen.Home) {
            AlertDialog(
                onDismissRequest = { update = null },
                title = { Text("发现新版本 v${info.version}") },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (info.notes.isNotBlank()) {
                            Text(
                                info.notes.take(800),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            "当前版本 v$currentVersion，可覆盖安装，登录态与连接历史保留。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        update = null
                        val apkUrl = info.apkUrl
                        if (apkUrl != null) {
                            ApkInstaller.downloadAndInstall(context, apkUrl)
                        } else {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(info.pageUrl),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    }) {
                        Text(if (info.apkUrl != null) "后台下载" else "打开下载页")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { update = null }) { Text("暂不更新") }
                },
            )
        }
    }
}
