package com.zpocket.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Message
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.zpocket.app.data.Connection
import org.json.JSONObject

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

    // 主框架加载失败时显示原生重试页（DNS/服务器错误等）
    var loadError by remember(connection.url) { mutableStateOf<String?>(null) }
    // WebView 渲染进程崩溃后重建计数
    var webViewGeneration by remember(connection.url) { mutableIntStateOf(0) }
    // 诊断报告（菜单"复制诊断信息"生成）
    var diagReport by remember { mutableStateOf<String?>(null) }
    // 状态探测报告（菜单"复制状态探测"生成，用于调优 TASK_OBSERVER_JS 的信号）
    var probeReport by remember { mutableStateOf<String?>(null) }

    // 任务状态通知的决策状态机：与 WebView 同生命周期（重建 WebView 即重置基线）
    val sessionAlerts = remember(connection.url, webViewGeneration) { SessionAlertPolicy() }

    // 网页文件上传（<input type=file>）：把选择结果回传给页面
    var pendingFileChooser by remember {
        mutableStateOf<ValueCallback<Array<Uri>>?>(null)
    }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileChooser
        pendingFileChooser = null
        callback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                ?: arrayOf()
        )
    }

    val webView = remember(connection.url, webViewGeneration) {
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
                // 固定 100%：系统"大字体"缩放会让窄容器一行只放一个字（竖排效果）
                textZoom = 100
                // 切后台不降渲染进程优先级，避免后台期间渲染进程被回收、回来变成错误页
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
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
                    if (url.startsWith("blob:")) {
                        Toast.makeText(
                            context,
                            "网页动态生成的文件暂不支持在 APP 内下载",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else if (url.startsWith("http")) {
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

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    loadError = null
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    CookieManager.getInstance().flush()
                    injectCompatCss(view)
                    injectTouchReorder(view)
                    injectTaskObserver(view)
                }

                // SPA 路由切换也会触发，保证注入始终存在
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    injectCompatCss(view)
                    injectTouchReorder(view)
                    injectTaskObserver(view)
                }

                // 主框架加载失败（DNS/断网等）：显示原生重试页，替代难看的系统错误页
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        loadError = error.description?.toString() ?: "网页加载失败"
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                        loadError = "服务器错误（HTTP ${errorResponse.statusCode}）"
                    }
                }

                // 渲染进程崩溃：销毁并重建 WebView，而不是留一个永远黑屏的页面
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    webViewGeneration += 1
                    loadError = "页面渲染进程异常退出，请重试"
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = newProgress
                }

                // 任务状态观察脚本（TASK_OBSERVER_JS）经 console 上报状态迁移，
                // 这里解析后交给 SessionNotifier 决定是否弹本地通知。
                // 不用 addJavascriptInterface：console 桥不向页面暴露任何对象，
                // 且 JSON 内容按不可信数据处理（解析失败静默丢弃）。
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val message = consoleMessage?.message()
                    if (message == null || !message.startsWith(STATE_REPORT_PREFIX)) {
                        return super.onConsoleMessage(consoleMessage)
                    }
                    val state = runCatching {
                        JSONObject(message.removePrefix(STATE_REPORT_PREFIX)).optString("state")
                    }.getOrNull()
                    if (!state.isNullOrEmpty()) {
                        SessionNotifier.handleStateReport(context, connection.name, sessionAlerts, state)
                    }
                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.deny()
                }

                // 网页 alert/confirm/prompt：WebView 默认会静默忽略，这里用原生对话框实现
                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    android.app.AlertDialog.Builder(context)
                        .setMessage(message)
                        .setPositiveButton("确定") { _, _ -> result?.confirm() }
                        .setOnCancelListener { result?.cancel() }
                        .show()
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    android.app.AlertDialog.Builder(context)
                        .setMessage(message)
                        .setPositiveButton("确定") { _, _ -> result?.confirm() }
                        .setNegativeButton("取消") { _, _ -> result?.cancel() }
                        .setOnCancelListener { result?.cancel() }
                        .show()
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult?,
                ): Boolean {
                    val input = EditText(context)
                    input.setText(defaultValue)
                    android.app.AlertDialog.Builder(context)
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton("确定") { _, _ ->
                            result?.confirm(input.text.toString())
                        }
                        .setNegativeButton("取消") { _, _ -> result?.cancel() }
                        .setOnCancelListener { result?.cancel() }
                        .show()
                    return true
                }

                // 网页文件上传（如从手机传文件给 ZCode）
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    val callback = filePathCallback ?: return false
                    val intent = fileChooserParams?.createIntent()
                    if (intent == null) {
                        callback.onReceiveValue(null)
                        return true
                    }
                    pendingFileChooser?.onReceiveValue(null)
                    pendingFileChooser = callback
                    runCatching {
                        fileChooserLauncher.launch(intent)
                    }.onFailure {
                        pendingFileChooser = null
                        callback.onReceiveValue(null)
                    }
                    return true
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
                                text = { Text("在浏览器中打开") },
                                onClick = {
                                    menuOpen = false
                                    webView.url?.let {
                                        openExternal(context, Uri.parse(it))
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("复制诊断信息") },
                                onClick = {
                                    menuOpen = false
                                    collectDiagnostics(webView) { diagReport = it }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("复制状态探测") },
                                onClick = {
                                    menuOpen = false
                                    collectStateProbe(webView) { probeReport = it }
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
                // key 变化（渲染进程崩溃后）时强制重建 AndroidView，换上新的 WebView 实例
                key(webViewGeneration) {
                    AndroidView(
                        factory = { webView },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { it.destroy() },
                    )
                }
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                loadError?.let { message ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("无法加载页面", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "请检查电脑端 ZCode 是否在线、手机网络是否可用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(28.dp))
                        Button(onClick = {
                            loadError = null
                            webView.reload()
                        }) {
                            Text("重试")
                        }
                        TextButton(onClick = onBack) {
                            Text("返回主页", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
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

    diagReport?.let { report ->
        AlertDialog(
            onDismissRequest = { diagReport = null },
            title = { Text("诊断信息") },
            text = {
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                        ClipData.newPlainText("zpocket-diag", report)
                    )
                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = { diagReport = null }) { Text("关闭") }
            },
        )
    }

    probeReport?.let { report ->
        AlertDialog(
            onDismissRequest = { probeReport = null },
            title = { Text("状态探测") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        report,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                        ClipData.newPlainText("zpocket-probe", report)
                    )
                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = { probeReport = null }) { Text("关闭") }
            },
        )
    }
}

/**
 * 官方远程页设置面板的窄屏兼容样式。
 *
 * 根因：设置行组件（"界面语言"等）使用
 * `grid-cols-[minmax(0,1fr)_192px]` —— 左列自适应、右列固定 192px，
 * 且 minmax 下限为 0。桌面端面板约 700px 宽没有问题；手机镜像面板
 * 约 270px 宽，左列被压到一个字宽，中文呈现竖列。
 * 这是官方页面在窄宽度下的响应式缺陷（与内核无关，浏览器同样如此）。
 *
 * 处理：窄视口下把该布局改为上下堆叠（与官方 wide 布局一致），
 * 并限制 260px 宽的下拉框不溢出。仅注入一次，SPA 路由切换后仍有效。
 *
 * 图表类缺陷（同样只在窄宽度下出现）：
 * - 每日 Token 趋势图硬编码 interval:0 渲染全部日期刻度，窄容器必然
 *   重叠 → 隔一个隐藏刻度并缩小字号（图表自带 Tooltip 可查看精确值）；
 * - 模型用量环形图 SVG 尺寸不随容器收缩 → max-width:100% 等比缩放，
 *   容器 overflow:hidden 兜底。
 */
private const val COMPAT_CSS_JS = """(function(){
  if (document.getElementById('zpocket-compat-css')) return;
  var s = document.createElement('style');
  s.id = 'zpocket-compat-css';
  s.textContent = "@media (max-width:700px){" +
    ".grid-cols-\\[minmax\\(0\\,1fr\\)_192px\\]{grid-template-columns:minmax(0,1fr)!important}" +
    ".grid-cols-\\[minmax\\(0\\,1fr\\)_280px\\]{grid-template-columns:minmax(0,1fr)!important}" +
    ".w-\\[260px\\]{max-width:100%}" +
    ".recharts-surface{max-width:100%!important;height:auto!important}" +
    "div:has(>.recharts-surface){overflow:hidden}" +
    ".recharts-cartesian-axis-tick:nth-of-type(2n){display:none}" +
    ".recharts-cartesian-axis-tick text{font-size:10px}" +
    "}";
  (document.head || document.documentElement).appendChild(s);
})()"""

private fun injectCompatCss(view: WebView) {
    view.evaluateJavascript(COMPAT_CSS_JS, null)
}

/**
 * 触摸长按拖拽排序（工作区等 dnd-kit 可排序列表）。
 *
 * 根因：官方远程页（remote/v4）工作区行把 dnd-kit PointerSensor 的拖拽监听
 * 铺在整行上，靠 pointerdown 后移动 8px 激活拖拽。桌面端鼠标正常；手机上
 * 行元素没有 touch-action:none，手指一动浏览器原生滚动就接管手势并发出
 * pointercancel，拖拽永远无法激活 —— 所以手机端只能长按（WebView 原生
 * contextmenu，等同桌面右键）弹菜单，无法拖动排序。
 *
 * 处理：注入"长按拖拽"手势 —— 按住可排序元素 280ms（短于右键菜单的 ~500ms）
 * 后合成 pointerdown 激活页面 dnd-kit，并锁定原生滚动（touchmove
 * preventDefault，避免 pointercancel 掐断拖拽）；之后的移动/松手事件全部
 * 由页面原有逻辑处理，排序结果照常同步到电脑端。
 *
 * 与既有手势共存：
 * - 按住不动 → 放行 contextmenu，右键菜单行为不变；
 * - 长按生效前大幅移动 → 是滚动，不干预；
 * - 拖动进行中到达的 contextmenu → 抑制，避免拖到一半弹菜单；
 * - 自带 touch-action:none 的元素（如分区拖拽手柄）原生就能拖，跳过不干预。
 *
 * 脚本幂等（window 标记），SPA 路由切换后重复注入无副作用。
 */
private const val TOUCH_REORDER_JS = """(function(){
  'use strict';
  if (window.__zpocketTouchReorder) return;
  if (typeof window.PointerEvent === 'undefined') return;
  window.__zpocketTouchReorder = true;
  var ARM_DELAY = 280, SCROLL_SLOP = 12, MENU_SLOP = 8;
  var pending = false, armed = false, moved = 0;
  var startX = 0, startY = 0, lastX = 0, lastY = 0;
  var touchId = null, row = null, timer = null, prevUserSelect = '';
  function dist(x1, y1, x2, y2) {
    return Math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2));
  }
  function cancelPending() {
    if (timer !== null) { clearTimeout(timer); timer = null; }
    pending = false; touchId = null; row = null;
  }
  function cleanup() {
    cancelPending();
    armed = false; moved = 0;
    if (document.body) document.body.style.userSelect = prevUserSelect;
  }
  function arm() {
    timer = null; pending = false;
    // 页面重渲染可能已把行节点换掉：事件发不进 React，只会白白锁住滚动
    if (!row || !row.isConnected) { cleanup(); return; }
    // 手指在 8~12px 间慢移再停住时，页面原生拖拽可能已被真实 pointerdown
    // 激活（aria-pressed 是 dnd-kit 对激活项的标记）：此时不能再叠加合成
    // 事件，否则两个 sensor 抢同一个指针
    if (row.getAttribute('aria-pressed') === 'true') { cleanup(); return; }
    armed = true; moved = 0;
    prevUserSelect = document.body.style.userSelect;
    document.body.style.userSelect = 'none';
    try { navigator.vibrate && navigator.vibrate(10); } catch (e) {}
    row.dispatchEvent(new PointerEvent('pointerdown', {
      bubbles: true, cancelable: true, composed: true,
      isPrimary: true, pointerId: 1, pointerType: 'touch',
      button: 0, buttons: 1, clientX: lastX, clientY: lastY,
    }));
  }
  document.addEventListener('touchstart', function (e) {
    if (armed || pending) cleanup();
    if (e.touches.length !== 1) return;
    var t = e.touches[0];
    var el = e.target.closest
      ? e.target.closest('[aria-roledescription="sortable"]')
      : null;
    if (!el || getComputedStyle(el).touchAction === 'none') return;
    pending = true; touchId = t.identifier;
    startX = lastX = t.clientX; startY = lastY = t.clientY;
    row = el;
    timer = setTimeout(arm, ARM_DELAY);
  }, { capture: true, passive: true });
  document.addEventListener('touchmove', function (e) {
    if (touchId === null) return;
    var t = null;
    for (var i = 0; i < e.touches.length; i++) {
      if (e.touches[i].identifier === touchId) { t = e.touches[i]; break; }
    }
    if (!t) return;
    lastX = t.clientX; lastY = t.clientY;
    moved = dist(startX, startY, lastX, lastY);
    if (!armed) {
      if (moved > SCROLL_SLOP) cancelPending();
      return;
    }
    e.preventDefault();
  }, { capture: true, passive: false });
  function onTouchEnd(e) {
    if (touchId === null) return;
    for (var i = 0; i < e.changedTouches.length; i++) {
      if (e.changedTouches[i].identifier === touchId) { cleanup(); break; }
    }
  }
  document.addEventListener('touchend', onTouchEnd, { capture: true, passive: true });
  document.addEventListener('touchcancel', onTouchEnd, { capture: true, passive: true });
  document.addEventListener('contextmenu', function (e) {
    if (!armed) { if (pending) cancelPending(); return; }
    if (moved > MENU_SLOP) {
      e.preventDefault();
      e.stopPropagation();
    } else {
      cleanup();
    }
  }, true);
})()"""

private fun injectTouchReorder(view: WebView) {
    view.evaluateJavascript(TOUCH_REORDER_JS, null)
}

/**
 * 会话状态观察器：检测官方远程页的任务状态迁移并上报给原生侧（→ 本地通知）。
 *
 * 背景：官方页（remote/v4）自带会话状态机 idle/running/waiting/paused/completed/
 * error（见其前端 bundle），但没有对外 API，状态只能从 DOM 侧观察。配对成功后的
 * 实时 DOM 无法离线获取，因此采用「语义锚点 + 图标信号」的分层启发式：
 * - 语义锚点：官方 CSS 里开发者命名的容器类 @container/composer（输入区）、
 *   @container/topoverlayer（顶层状态层），类名不随构建哈希变；
 * - 图标信号：lucide 图标以字面类名渲染（lucide-loader-circle /
 *   lucide-triangle-alert / lucide-circle-check / lucide-square 等），同为稳定信号。
 *
 * 判定优先级：error（可见警告图标）> running（输入区出现停止按钮或持续转圈的
 * loader）> completed（状态层出现对勾图标）> waiting（running 结束后输入框可用、
 * 无结束图标 = 在等用户回复）> idle（静止）。信号全部失效时返回 unknown ——
 * 静默不通知，不影响远程会话本身。
 *
 * 防误报：状态迁移需连续两次读数一致才认定（页面加载动画等瞬时信号自然过滤）；
 * MutationObserver（400ms 防抖）保实时性，4 秒轮询兜底防漏报（切后台后定时器
 * 仍执行，进程由保活前台服务维持）。只在状态变化时打一条
 * `__ZPOCKET_STATE__{...}` console，由原生侧 onConsoleMessage 接收。
 *
 * 调优：菜单「复制状态探测」导出当前判定依据（可见图标清单、锚点容器
 * innerHTML 片段）；官方页面改版导致识别失效时，据此修正下方信号表。
 */
private const val TASK_OBSERVER_JS = """(function(){
  'use strict';
  if (window.__zpocketTaskObserver) return;
  window.__zpocketTaskObserver = true;

  // ---- 信号表（lucide 图标字面类名，兼容新旧命名）----
  var ERROR_ICONS = ['triangle-alert', 'circle-alert', 'octagon-alert'];
  var STOP_ICONS = ['square', 'circle-stop', 'stop-circle', 'octagon-x'];
  var DONE_ICONS = ['circle-check', 'check-circle', 'circle-check-big', 'badge-check'];
  var LOADER_RE = /lucide-(loader[a-z0-9-]*)/;

  function visible(el) {
    if (!el || !el.isConnected) return false;
    var r = el.getBoundingClientRect();
    return r.width > 1 && r.height > 1;
  }
  // 收集 root 下可见的 lucide 图标名
  function collectIcons(root) {
    var names = [];
    try {
      var svgs = (root || document).querySelectorAll('svg[class*="lucide-"]');
      for (var i = 0; i < svgs.length; i++) {
        var el = svgs[i];
        if (!visible(el)) continue;
        var m = String(el.getAttribute('class') || '').match(/lucide-[a-z0-9-]+/g) || [];
        for (var j = 0; j < m.length; j++) {
          var n = m[j].slice(7);
          if (names.indexOf(n) < 0) names.push(n);
        }
      }
    } catch (e) {}
    return names;
  }
  function hasAny(names, want) {
    for (var i = 0; i < want.length; i++) {
      if (names.indexOf(want[i]) >= 0) return true;
    }
    return false;
  }
  // 语义容器锚点（官方 CSS 开发者命名）
  function composer() {
    return document.querySelector('[class*="@container/composer"]');
  }
  function overlay() {
    return document.querySelector('[class*="@container/topoverlayer"]');
  }
  // 输入框是否可用（等待用户回复的基本特征）
  function inputReady() {
    var scope = composer() || document;
    return !!scope.querySelector('textarea:not([disabled]), [contenteditable="true"]');
  }
  // root 内是否有"正在转圈"的 loader 图标（animate-spin 或计算样式动画名含 spin）
  function spinning(root) {
    if (!root) return false;
    try {
      var svgs = root.querySelectorAll('svg[class*="lucide-"]');
      for (var i = 0; i < svgs.length; i++) {
        var el = svgs[i];
        if (!visible(el)) continue;
        var cls = String(el.getAttribute('class') || '');
        if (!LOADER_RE.test(cls)) continue;
        if (cls.indexOf('animate-spin') >= 0) return true;
        if (String(getComputedStyle(el).animationName).indexOf('spin') >= 0) return true;
      }
    } catch (e) {}
    return false;
  }

  // ---- 状态判定 ----
  var prev = 'unknown';      // 上次已上报状态
  var candidate = 'unknown'; // 候选状态（需连续两次一致才认定）

  function classify() {
    var all = collectIcons(document);
    if (hasAny(all, ERROR_ICONS)) return 'error';
    var c = composer();
    var o = overlay();
    if (hasAny(c ? collectIcons(c) : [], STOP_ICONS)) return 'running';
    if (spinning(c) || spinning(o)) return 'running';
    var oIcons = o ? collectIcons(o) : [];
    if (hasAny(oIcons, DONE_ICONS) || oIcons.indexOf('check') >= 0) return 'completed';
    if (hasAny(all, DONE_ICONS)) return 'completed';
    if (inputReady()) {
      // 上一状态是 running/waiting：跑完一轮在等用户；静止状态下的可用输入框是空闲
      return (prev === 'running' || prev === 'waiting') ? 'waiting' : 'idle';
    }
    return 'unknown';
  }

  // ---- 上报与调度 ----
  function tick() {
    var raw;
    try { raw = classify(); } catch (e) { return; }
    if (raw !== candidate) { candidate = raw; return; }
    if (raw === 'unknown' || raw === prev) return;
    prev = raw;
    try { console.log('__ZPOCKET_STATE__' + JSON.stringify({ state: raw })); } catch (e) {}
  }

  var timer = null;
  function schedule() {
    if (timer !== null) return;
    timer = setTimeout(function() { timer = null; tick(); }, 400);
  }
  try {
    new MutationObserver(schedule).observe(
      document.documentElement,
      { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] }
    );
  } catch (e) {}
  setInterval(schedule, 4000);

  // ---- 调试导出（菜单「复制状态探测」调用，复用上面的闭包）----
  window.__zpocketTaskProbe = function() {
    var c = composer(), o = overlay();
    function html(el) {
      if (!el) return null;
      return String(el.innerHTML).replace(/\s+/g, ' ').slice(0, 600);
    }
    return {
      prev: prev,
      candidate: candidate,
      title: document.title,
      icons: collectIcons(document),
      composer: c ? { icons: collectIcons(c), inputReady: inputReady(), html: html(c) } : null,
      overlay: o ? { icons: collectIcons(o), html: html(o) } : null
    };
  };
})()"""

private fun injectTaskObserver(view: WebView) {
    view.evaluateJavascript(TASK_OBSERVER_JS, null)
}

/**
 * 收集页面渲染诊断：扫描当前页面上"宽度过窄导致文字竖排"的元素，
 * 连同 WebView 内核版本和视口尺寸一起返回，供排查布局问题。
 *
 * 使用方式：先在页面上打开出现问题的界面（如设置面板），再触发本诊断。
 */
private fun collectDiagnostics(webView: WebView, onReport: (String) -> Unit) {
    webView.evaluateJavascript(DIAGNOSTIC_JS) { raw ->
        val report = runCatching {
            val obj = JSONObject(raw ?: return@runCatching "诊断失败：页面未返回数据")
            val vp = obj.getJSONObject("viewport")
            val wv = runCatching {
                if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage() else null
            }.getOrNull()
            buildString {
                appendLine("WebView内核: ${wv?.packageName ?: "未知"} ${wv?.versionName ?: "未知"}")
                appendLine("视口: ${vp.optInt("w")}x${vp.optInt("h")} dpr=${vp.optDouble("dpr")}")
                appendLine("竖排元素样本:")
                val items = obj.optJSONArray("items")
                if (items == null || items.length() == 0) {
                    appendLine("（未发现明显竖排元素——请确认问题界面当前处于打开状态）")
                } else {
                    for (i in 0 until items.length()) {
                        appendLine(items.optJSONObject(i)?.toString().orEmpty())
                    }
                }
            }
        }.getOrDefault("诊断失败: ${raw?.take(200)}")
        onReport(report.take(4000))
    }
}

private const val DIAGNOSTIC_JS = """(function() {
  var items = [];
  try {
    var all = document.querySelectorAll('body *');
    for (var i = 0; i < all.length; i++) {
      if (items.length >= 10) break;
      var el = all[i];
      if (el.children && el.children.length > 0) continue;
      var t = (el.textContent || '').trim();
      if (t.length < 2) continue;
      var r = el.getBoundingClientRect();
      var cs = window.getComputedStyle(el);
      var fs = parseFloat(cs.fontSize) || 16;
      if (r.width > 0 && r.height > r.width * 1.8 && r.width < fs * 1.6) {
        items.push({
          tag: el.tagName,
          cls: String(el.className || '').slice(0, 80),
          w: Math.round(r.width),
          h: Math.round(r.height),
          fontSize: Math.round(fs),
          display: cs.display,
          whiteSpace: cs.whiteSpace,
          writingMode: cs.writingMode,
          text: t.slice(0, 16)
        });
      }
    }
  } catch (e) {
    items.push({error: String(e)});
  }
  return {
    viewport: {w: window.innerWidth, h: window.innerHeight, dpr: window.devicePixelRatio},
    items: items
  };
})()"""

/**
 * 任务状态观察脚本的上报前缀（须与 TASK_OBSERVER_JS 里 console.log 的字面前缀一致）。
 */
private const val STATE_REPORT_PREFIX = "__ZPOCKET_STATE__"

/**
 * 采集状态观察器当前的判定依据（可见图标、锚点容器结构），供官方页面改版后
 * 调优 TASK_OBSERVER_JS 的信号表。观察脚本未运行时（页面尚未加载完成）给出提示。
 */
private const val STATE_PROBE_JS = """(function() {
  if (!window.__zpocketTaskProbe) {
    return { error: '观察脚本未运行（页面可能尚未加载完成）' };
  }
  return window.__zpocketTaskProbe();
})()"""

private fun collectStateProbe(webView: WebView, onReport: (String) -> Unit) {
    webView.evaluateJavascript(STATE_PROBE_JS) { raw ->
        val report = runCatching {
            JSONObject(raw ?: return@runCatching "探测失败：页面未返回数据").toString(2)
        }.getOrDefault("探测失败: ${raw?.take(200)}")
        onReport(report.take(6000))
    }
}

private fun openExternal(context: Context, url: Uri) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
