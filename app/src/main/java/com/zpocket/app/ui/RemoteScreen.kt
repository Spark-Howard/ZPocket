package com.zpocket.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.zpocket.app.data.Connection

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RemoteScreen(
    connection: Connection,
    onBack: () -> Unit,
    onRescan: () -> Unit,
    onForget: () -> Unit,
) {
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(100) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    val initialHost = remember(connection.url) { Uri.parse(connection.url).host.orEmpty() }

    val webView = remember(connection.url) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = true
                // 支持页面 window.open / target=_blank（配合下方 onCreateWindow）
                setSupportMultipleWindows(true)
                // 去掉 WebView 标记，伪装成 Chrome，避免被站点检测拦截
                userAgentString = userAgentString
                    .replace("; wv", "")
                    .replace("Version/4.0 ", "")
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            // 文件下载：WebView 默认不处理下载，会表现为"一直加载但看不到"；
            // 交给系统下载管理器（带上登录 Cookie），旧机型没权限时跳系统浏览器
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                runCatching {
                    if (url.startsWith("http")) {
                        val dm = context.getSystemService(DownloadManager::class.java)
                        val canDownload = dm != null && (Build.VERSION.SDK_INT >= 29 ||
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            ) == PackageManager.PERMISSION_GRANTED)
                        if (dm != null && canDownload) {
                            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                            val request = DownloadManager.Request(Uri.parse(url)).apply {
                                setNotificationVisibility(
                                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                                )
                                CookieManager.getInstance().getCookie(url)?.let {
                                    addRequestHeader("Cookie", it)
                                }
                                addRequestHeader("User-Agent", userAgent)
                                setDestinationInExternalPublicDir(
                                    Environment.DIRECTORY_DOWNLOADS,
                                    name,
                                )
                                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                            }
                            dm.enqueue(request)
                            Toast.makeText(context, "开始下载：$name", Toast.LENGTH_SHORT).show()
                        } else {
                            openExternal(context, Uri.parse(url))
                            Toast.makeText(context, "已转交系统浏览器下载", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        openExternal(context, Uri.parse(url))
                    }
                }.onFailure {
                    Toast.makeText(context, "下载失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url
                    return when (url.scheme) {
                        "http", "https" -> {
                            val host = url.host.orEmpty()
                            if (host == initialHost || host.endsWith(".z.ai")) {
                                false
                            } else {
                                openExternal(context, url)
                                true
                            }
                        }

                        else -> {
                            openExternal(context, url)
                            true
                        }
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    CookieManager.getInstance().flush()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = newProgress
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.deny()
                }

                // target=_blank / window.open 的新窗口：站内链接在当前 WebView 打开，外链跳系统浏览器
                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message,
                ): Boolean {
                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                    val temp = WebView(view.context)
                    temp.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            v: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val url = request.url
                            val host = url.host.orEmpty()
                            if (url.scheme == "http" || url.scheme == "https") {
                                if (host == initialHost || host.endsWith(".z.ai")) {
                                    this@apply.loadUrl(url.toString())
                                } else {
                                    openExternal(context, url)
                                }
                            } else {
                                openExternal(context, url)
                            }
                            v.post { temp.destroy() }
                            return true
                        }
                    }
                    transport.webView = temp
                    resultMsg.sendToTarget()
                    return true
                }
            }
            loadUrl(connection.url)
        }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onBack()
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column {
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
                            connection.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            initialHost,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "菜单")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("刷新页面") },
                                onClick = {
                                    menuOpen = false
                                    webView.reload()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("重新扫码") },
                                onClick = {
                                    menuOpen = false
                                    onRescan()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("清除登录数据") },
                                onClick = {
                                    menuOpen = false
                                    confirmClear = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除连接记录") },
                                onClick = {
                                    menuOpen = false
                                    confirmForget = true
                                },
                            )
                        }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.destroy() },
                )
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除登录数据") },
            text = { Text("将清除 APP 内保存的 zcode.z.ai 登录状态和网页缓存，下次连接需要重新登录账号。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    webView.apply {
                        clearCache(true)
                        clearFormData()
                        clearHistory()
                        reload()
                    }
                }) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("删除连接记录") },
            text = { Text("将删除「${connection.name}」的连接记录并返回主页（网页登录状态保留）。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    onForget()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text("取消") }
            },
        )
    }
}

private fun openExternal(context: Context, url: Uri) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
