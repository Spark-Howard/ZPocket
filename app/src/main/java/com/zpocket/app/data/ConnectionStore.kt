package com.zpocket.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** 一次与电脑端 ZCode 的远程连接（来自扫描的二维码链接）。 */
data class Connection(
    val id: String,
    val name: String,
    val url: String,
    val lastUsedAt: Long,
)

object RemoteUrl {

    /** 接受任何 http(s) 链接；桌面端二维码是 https://zcode.z.ai/remote/v3?sid=...&mid=...&name=... */
    fun isValid(url: String): Boolean {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = u.host ?: return false
        return (u.scheme == "https" || u.scheme == "http") && host.contains('.')
    }

    fun displayName(url: String): String {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return "ZCode 设备"
        return u.getQueryParameter("name")?.takeIf { it.isNotBlank() }
            ?: u.host?.takeIf { it.isNotBlank() }
            ?: "ZCode 设备"
    }

    fun displayHost(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull() ?: url
}

/** 连接历史，保存在本机 SharedPreferences，只存最近 20 条。 */
class ConnectionStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("zpocket_connections", Context.MODE_PRIVATE)

    fun load(): List<Connection> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                Connection(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    url = o.getString("url"),
                    lastUsedAt = o.getLong("lastUsedAt"),
                )
            }
        }.getOrDefault(emptyList()).sortedByDescending { it.lastUsedAt }
    }

    /** 记录一次连接（相同链接会刷新时间而不是重复添加），返回保存后的条目。 */
    fun record(url: String): Connection? {
        if (!RemoteUrl.isValid(url)) return null
        val entry = Connection(
            id = url,
            name = RemoteUrl.displayName(url),
            url = url,
            lastUsedAt = System.currentTimeMillis(),
        )
        val others = load().filterNot { it.id == entry.id }
        save((listOf(entry) + others).take(MAX_ENTRIES))
        return entry
    }

    fun delete(id: String) = save(load().filterNot { it.id == id })

    /** 启动时是否自动重连上次的设备（默认开）。 */
    fun isAutoConnectEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_CONNECT, true)

    fun setAutoConnectEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()

    private fun save(list: List<Connection>) {
        val array = JSONArray()
        list.forEach { c ->
            array.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("url", c.url)
                    .put("lastUsedAt", c.lastUsedAt)
            )
        }
        prefs.edit().putString(KEY_LIST, array.toString()).apply()
    }

    private companion object {
        const val KEY_LIST = "connections"
        const val KEY_AUTO_CONNECT = "auto_connect"
        const val MAX_ENTRIES = 20
    }
}
