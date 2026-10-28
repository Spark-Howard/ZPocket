package com.zpocket.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.ContextCompat
import com.zpocket.app.KeepAliveService
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
    var keepAlive by remember { mutableStateOf(store.isKeepAliveEnabled()) }

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

    // 远程页激活期间启动前台保活服务，离开远程页/关闭开关/退出 APP 时停止。
    // 进程保持前台优先级就不会被冻结查杀，WebSocket 心跳不中断，
    // 切后台再回来就不会触发官方页面的重连动画和会话重新加载。
    DisposableEffect(screen is Screen.Remote, keepAlive) {
        val keep = screen is Screen.Remote && keepAlive
        if (keep) KeepAliveService.start(context)
        onDispose {
            if (keep) KeepAliveService.stop(context)
        }
    }

    // 首次进入远程页：请求通知权限（保活通知可见）+ 引导电池优化白名单，各只出现一次
    var showBatteryDialog by remember { mutableStateOf(false) }
    var promptBatteryAfterNotif by remember { mutableStateOf(false) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (promptBatteryAfterNotif) {
            promptBatteryAfterNotif = false
            showBatteryDialog = true
        }
    }
    LaunchedEffect(screen is Screen.Remote) {
        if (screen !is Screen.Remote) return@LaunchedEffect
        val powerManager = context.getSystemService(PowerManager::class.java)
        val alreadyWhitelisted =
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        val needBatteryPrompt =
            !store.isBatteryPromptShown() && !alreadyWhitelisted
        if (!needBatteryPrompt) return@LaunchedEffect
        store.markBatteryPromptShown()
        val notifGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (notifGranted) {
            showBatteryDialog = true
        } else {
            promptBatteryAfterNotif = true
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
            keepAlive = keepAlive,
            currentVersion = currentVersion,
            checkingUpdate = checking,
            onAutoConnectChange = {
                autoConnect = it
                store.setAutoConnectEnabled(it)
            },
            onKeepAliveChange = {
                keepAlive = it
                store.setKeepAliveEnabled(it)
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

    // 电池优化白名单引导：切后台被系统冻结是断线重连的根因之一，白名单是前台服务之外的第二道保险
    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            title = { Text("保持后台连接") },
            text = {
                Text(
                    "切到其他应用或锁屏时，系统可能冻结 ZPocket 导致断线重连。" +
                        "允许「忽略电池优化」后，短时间内切回无需重新连接，仅轻微增加耗电。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryDialog = false
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${context.packageName}"))
                        )
                    }
                }) { Text("去设置") }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryDialog = false }) { Text("暂不") }
            },
        )
    }
}
