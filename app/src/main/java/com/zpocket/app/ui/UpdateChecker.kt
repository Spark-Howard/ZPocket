package com.zpocket.app.ui

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 从 GitHub Releases 拉到的最新版本信息。 */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String?,
    val pageUrl: String,
)

object UpdateChecker {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/Spark-Howard/ZPocket/releases/latest"

    fun currentVersion(context: Context): String = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        info.versionName ?: "?"
    }.getOrDefault("?")

    /** "v1.2.0" / "1.2.3-beta" → [1,2,0]；解析不出的段按 0 处理。 */
    fun parseVersion(v: String): IntArray =
        v.trim().removePrefix("v").removePrefix("V")
            .split("-", "_", ".")
            .mapNotNull { it.toIntOrNull() }
            .toIntArray()

    fun isNewer(candidate: String, current: String): Boolean {
        val a = parseVersion(candidate)
        val b = parseVersion(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * 拉取 GitHub 最新 release。网络失败/解析失败返回 null，
     * 由调用方决定静默（自动检查）还是提示（手动检查）。
     */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "ZPocket-UpdateChecker")
            try {
                if (conn.responseCode != 200) return@runCatching null
                val json = JSONObject(
                    conn.inputStream.bufferedReader().use { it.readText() }
                )
                val tag = json.optString("tag_name").ifBlank { return@runCatching null }
                val apkUrl = json.optJSONArray("assets")?.let { assets ->
                    (0 until assets.length())
                        .mapNotNull { i ->
                            assets.optJSONObject(i)
                                ?.takeIf { it.optString("name").endsWith(".apk") }
                                ?.optString("browser_download_url")
                                ?.takeIf { it.isNotBlank() }
                        }
                        .firstOrNull()
                }
                UpdateInfo(
                    version = tag.removePrefix("v").removePrefix("V"),
                    notes = json.optString("body"),
                    apkUrl = apkUrl,
                    pageUrl = json.optString("html_url"),
                )
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}

/** 用系统下载管理器下载更新 APK，完成后自动拉起安装。 */
object ApkInstaller {

    fun downloadAndInstall(context: Context, url: String) {
        val dm = context.getSystemService(DownloadManager::class.java) ?: return
        val name = url.substringAfterLast('/')
        var downloadId = -1L

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return
                runCatching { ctx.unregisterReceiver(this) }
                openInstall(ctx, dm, downloadId)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            setMimeType("application/vnd.android.package-archive")
        }
        downloadId = dm.enqueue(request)
        Toast.makeText(context, "开始下载更新，完成后自动弹出安装", Toast.LENGTH_LONG).show()
    }

    private fun openInstall(context: Context, dm: DownloadManager, id: Long) {
        runCatching {
            val uri = dm.getUriForDownloadedFile(id) ?: return

            // Android 8+ 需要用户授予"安装未知应用"权限，未授予时引导去通知栏点开
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                    context,
                    "APK 已下载，请点击通知栏或在「下载」目录打开安装",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }

            val contentUri = when (uri.scheme) {
                "content" -> uri
                else -> FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    File(uri.path ?: return),
                )
            }
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }
}
