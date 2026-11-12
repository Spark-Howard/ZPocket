package com.zpocket.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 离线消息缓存的数据模型与流程：
 * 注入脚本（RemoteScreen 的 MESSAGE_CAPTURE_JS）把当前打开会话的内容序列化为
 * 结构化 JSON，经 console 桥分块上报；[MessageReportAssembler] 把分块重组成
 * [MessageSnapshot]；[OfflineCachePolicy] 负责键派生、容量裁剪；[MessageCacheStore]
 * 按「连接 id → 会话列表」持久化（SharedPreferences + org.json，模式同 ConnectionStore），
 * 图片字节由 [OfflineImageStore] 落盘到 filesDir（离线后网络图片加载不出来，
 * 必须在连接期间把字节抓下来）。
 *
 * 前三个类不依赖 Android（org.json 在单测里用同名工件提供），可 JVM 单测。
 */

/** 消息片段：文本 / 代码块 / 图片。图片的 ref 在持久化前会被替换为内容键（见 OfflineImageStore.keyFor）。 */
sealed interface MessageSegment {
    data class Text(val text: String) : MessageSegment
    data class Code(val text: String, val lang: String) : MessageSegment
    data class Image(val ref: String, val width: Int, val height: Int) : MessageSegment
}

/** 一条消息。role = user / assistant / chat（未能识别时）。 */
data class CachedMessage(val role: String, val segments: List<MessageSegment>)

/** 一个缓存的会话。key 稳定标识会话（标题 + 首条消息摘要），同 key 的快照覆盖更新。 */
data class CachedSession(
    val key: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<CachedMessage>,
)

/** 注入脚本一次完整上报（分块重组后）的原始快照。 */
data class MessageSnapshot(val title: String?, val messages: List<CachedMessage>)

/**
 * console 桥分块重组器（纯逻辑，可单测）。
 *
 * 页面侧把快照 JSON 按 ≤24KB 切成 {sid,i,n,part} 分块逐条 console.log；
 * 本类按 sid 聚拢，收齐 n 块后拼接并解析为 [MessageSnapshot]。
 * 支持乱序/重复分块；信封或快照内容损坏时静默丢弃，不影响其他快照。
 */
class MessageReportAssembler {

    private class ChunkBuffer(val n: Int) {
        val parts = HashMap<Int, String>(n)
    }

    private val pending = LinkedHashMap<String, ChunkBuffer>()

    /** 喂入一个分块信封 JSON；该快照收齐时返回解析结果，否则返回 null。 */
    fun feed(chunkJson: String?): MessageSnapshot? {
        if (chunkJson.isNullOrBlank()) return null
        val env = runCatching { JSONObject(chunkJson) }.getOrNull() ?: return null
        val sid = env.optString("sid")
        val n = env.optInt("n", -1)
        val i = env.optInt("i", -1)
        val part = env.optString("part")
        if (sid.isEmpty() || sid.length > MAX_SID || n !in 1..MAX_CHUNKS ||
            i !in 0 until n || part.length > MAX_PART
        ) {
            return null
        }
        val buf = pending.getOrPut(sid) {
            // 半途而废的快照最多留 MAX_PENDING 个，防异常页面把缓冲撑爆
            if (pending.size >= MAX_PENDING) pending.remove(pending.keys.first())
            ChunkBuffer(n)
        }
        if (buf.n != n) {
            pending.remove(sid)
            return null
        }
        buf.parts[i] = part
        if (buf.parts.size < n) return null
        pending.remove(sid)
        val joined = StringBuilder(part.length * n)
        for (k in 0 until n) joined.append(buf.parts.getValue(k))
        return parseSnapshot(joined.toString())
    }

    private fun parseSnapshot(json: String): MessageSnapshot? = runCatching {
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("messages") ?: return null
        val msgs = ArrayList<CachedMessage>(arr.length())
        for (m in 0 until arr.length()) {
            val mo = arr.optJSONObject(m) ?: continue
            val sa = mo.optJSONArray("segs") ?: continue
            val segs = ArrayList<MessageSegment>(sa.length())
            for (s in 0 until sa.length()) {
                val so = sa.optJSONObject(s) ?: continue
                when (so.optString("t")) {
                    "text" -> OfflineCachePolicy.text(so.optString("v"))?.let { segs.add(it) }
                    "code" -> OfflineCachePolicy.code(so.optString("v"), so.optString("lang"))
                        ?.let { segs.add(it) }

                    "img" -> segs.add(
                        MessageSegment.Image(so.optString("ref"), so.optInt("w"), so.optInt("h"))
                    )
                }
            }
            if (segs.isEmpty()) continue
            msgs.add(CachedMessage(mo.optString("role", "chat").ifBlank { "chat" }.take(12), segs))
        }
        if (msgs.isEmpty()) null else MessageSnapshot(obj.optString("title").ifBlank { null }, msgs)
    }.getOrNull()

    private companion object {
        // 200 块 × 24KB ≈ 4.8MB，覆盖 JS 侧单消息截断后的快照上限（多代码块长会话）
        const val MAX_CHUNKS = 200
        const val MAX_PART = 40000
        const val MAX_SID = 64
        // 极少有超过一份快照在途，2 份兜底防内存放大
        const val MAX_PENDING = 2
    }
}

/**
 * 缓存策略（纯逻辑，可单测）：会话键派生、单会话/总量裁剪。
 * 容量上限是为防 SharedPreferences 单键字符串和图片目录无限膨胀。
 */
object OfflineCachePolicy {
    const val MAX_MESSAGES = 200
    const val MAX_SEGMENT_CHARS = 6000
    const val MAX_SEGS_PER_MESSAGE = 20
    const val MAX_MESSAGE_CHARS = 12_000
    const val MAX_SESSIONS = 12
    const val MAX_TOTAL_CHARS = 500_000

    fun text(raw: String?): MessageSegment.Text? {
        val t = raw?.trim() ?: return null
        if (t.isEmpty()) return null
        return MessageSegment.Text(t.take(MAX_SEGMENT_CHARS))
    }

    fun code(raw: String?, lang: String?): MessageSegment.Code? {
        val t = raw?.trim() ?: return null
        if (t.isBlank()) return null
        return MessageSegment.Code(t.take(MAX_SEGMENT_CHARS), lang.orEmpty().take(24))
    }

    /** 展示标题：优先页面标题，缺失时退化为首条用户消息前缀。 */
    fun displayTitle(title: String?, messages: List<CachedMessage>): String {
        title?.takeIf { it.isNotBlank() }?.let { return it.take(80) }
        return messages.firstOrNull { it.role == "user" }?.segments
            ?.firstNotNullOfOrNull { seg ->
                when (seg) {
                    is MessageSegment.Text -> seg.text.lineSequence().firstOrNull()
                    is MessageSegment.Code -> seg.text.lineSequence().firstOrNull()
                    is MessageSegment.Image -> null
                }
            }?.take(24)?.ifBlank { null } ?: "未命名会话"
    }

    /**
     * 会话稳定键：标题 + 首条消息首段文本的摘要。仅靠标题会互相覆盖
     * （标题探测可能失效），叠加消息摘要避免「未命名会话」全部塌缩成一个桶。
     */
    fun sessionKey(title: String?, messages: List<CachedMessage>): String {
        val display = displayTitle(title, messages)
        val digest = messages.firstOrNull()?.segments?.firstNotNullOfOrNull { seg ->
            when (seg) {
                is MessageSegment.Text -> seg.text
                is MessageSegment.Code -> seg.text
                is MessageSegment.Image -> null
            }
        }?.take(120) ?: ""
        return "$display#${sha1Hex(digest).take(10)}"
    }

    /** 快照 → 可持久化的会话：单消息截断、保留最近的消息（容量上限统一收口在这里）。 */
    fun normalize(snapshot: MessageSnapshot, nowMs: Long): CachedSession {
        val msgs = snapshot.messages
            .mapNotNull(::capMessage)
            .takeLast(MAX_MESSAGES)
        return CachedSession(sessionKey(snapshot.title, msgs), displayTitle(snapshot.title, msgs), nowMs, msgs)
    }

    /** 单消息上限：段数与总字符双截断（硬上限不溢出，保证快照分块数与 prefs 体积有界）。 */
    private fun capMessage(m: CachedMessage): CachedMessage? {
        val segs = ArrayList<MessageSegment>(minOf(m.segments.size, MAX_SEGS_PER_MESSAGE))
        var total = 0
        for (seg in m.segments) {
            if (segs.size >= MAX_SEGS_PER_MESSAGE) break
            val capped = capSegment(seg) ?: continue
            val len = when (capped) {
                is MessageSegment.Text -> capped.text.length
                is MessageSegment.Code -> capped.text.length
                is MessageSegment.Image -> 0
            }
            if (total + len > MAX_MESSAGE_CHARS) break
            segs.add(capped)
            total += len
        }
        return if (segs.isEmpty()) null else m.copy(segments = segs)
    }

    private fun capSegment(seg: MessageSegment): MessageSegment? = when (seg) {
        is MessageSegment.Text -> text(seg.text)
        is MessageSegment.Code -> code(seg.text, seg.lang)
        is MessageSegment.Image -> seg
    }

    /** 把更新后的会话并入列表：同 key 覆盖，按更新时间排序，超出数量/总量预算时丢最旧。 */
    fun mergeSessions(existing: List<CachedSession>, updated: CachedSession): List<CachedSession> {
        val others = existing.filterNot { it.key == updated.key }
        var list = (listOf(updated) + others).sortedByDescending { it.updatedAt }.take(MAX_SESSIONS)
        while (list.size > 1 && totalChars(list) > MAX_TOTAL_CHARS) {
            list = list.dropLast(1)
        }
        return list
    }

    fun totalChars(sessions: List<CachedSession>): Int = sessions.sumOf { s ->
        s.messages.sumOf { m ->
            m.segments.sumOf { seg ->
                when (seg) {
                    is MessageSegment.Text -> seg.text.length
                    is MessageSegment.Code -> seg.text.length
                    is MessageSegment.Image -> 0
                }
            }
        }
    }

    private fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/**
 * 离线消息持久化：每条连接一个 prefs 键，值为该连接的会话列表 JSON。
 * 模式与 ConnectionStore 一致（SharedPreferences + org.json 手工序列化 + runCatching 兜底）。
 */
class MessageCacheStore(context: Context) {

    private val app = context.applicationContext
    private val prefs =
        app.getSharedPreferences("zpocket_messages", Context.MODE_PRIVATE)

    fun sessionsFor(connectionId: String): List<CachedSession> {
        val raw = prefs.getString(key(connectionId), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> decodeSession(arr.optJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    fun sessionCount(connectionId: String): Int = sessionsFor(connectionId).size

    /**
     * 落一份快照；内容与已存版本相同（页面重复上报）时跳过。图片引用会先替换为
     * 内容键——ref 可能是几百 KB 的 data: URL，不能原样进 prefs。
     * 返回是否真的写入了新内容。
     */
    fun saveSnapshot(connectionId: String, snapshot: MessageSnapshot, nowMs: Long): Boolean {
        val mapped = snapshot.messages.map { m ->
            m.copy(segments = m.segments.map { seg ->
                if (seg is MessageSegment.Image) {
                    seg.copy(ref = OfflineImageStore.keyFor(seg.ref))
                } else {
                    seg
                }
            })
        }
        val session = OfflineCachePolicy.normalize(MessageSnapshot(snapshot.title, mapped), nowMs)
        val existing = sessionsFor(connectionId)
        if (existing.firstOrNull { it.key == session.key }?.messages == session.messages) {
            return false
        }
        val merged = OfflineCachePolicy.mergeSessions(existing, session)
        prefs.edit().putString(key(connectionId), encodeSessions(merged).toString()).apply()
        return true
    }

    /** 清空一条连接的离线消息（含图片文件）。 */
    fun clearConnection(connectionId: String) {
        prefs.edit().remove(key(connectionId)).apply()
        OfflineImageStore.clearConnection(app, connectionId)
    }

    /** 「离线消息缓存」开关（默认开）：关闭后停止更新缓存，已存数据保留。 */
    fun isOfflineCacheEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setOfflineCacheEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()

    private fun key(connectionId: String) = "$KEY_PREFIX$connectionId"

    private fun encodeSessions(list: List<CachedSession>): JSONArray {
        val arr = JSONArray()
        list.forEach { s ->
            val mArr = JSONArray()
            s.messages.forEach { m ->
                val sArr = JSONArray()
                m.segments.forEach { seg ->
                    val o = JSONObject()
                    when (seg) {
                        is MessageSegment.Text -> o.put("t", "text").put("v", seg.text)
                        is MessageSegment.Code -> o.put("t", "code").put("v", seg.text).put("lang", seg.lang)
                        is MessageSegment.Image ->
                            o.put("t", "img").put("ref", seg.ref).put("w", seg.width).put("h", seg.height)
                    }
                    sArr.put(o)
                }
                mArr.put(JSONObject().put("role", m.role).put("segs", sArr))
            }
            arr.put(
                JSONObject()
                    .put("key", s.key)
                    .put("title", s.title)
                    .put("updatedAt", s.updatedAt)
                    .put("messages", mArr)
            )
        }
        return arr
    }

    private fun decodeSession(o: JSONObject?): CachedSession? {
        o ?: return null
        return runCatching {
            val mArr = o.optJSONArray("messages") ?: return null
            val msgs = ArrayList<CachedMessage>(mArr.length())
            for (m in 0 until mArr.length()) {
                val mo = mArr.optJSONObject(m) ?: continue
                val sArr = mo.optJSONArray("segs") ?: continue
                val segs = ArrayList<MessageSegment>(sArr.length())
                for (s in 0 until sArr.length()) {
                    val so = sArr.optJSONObject(s) ?: continue
                    when (so.optString("t")) {
                        "text" -> MessageSegment.Text(so.optString("v"))
                        "code" -> MessageSegment.Code(so.optString("v"), so.optString("lang"))
                        "img" -> MessageSegment.Image(so.optString("ref"), so.optInt("w"), so.optInt("h"))
                        else -> null
                    }?.let { segs.add(it) }
                }
                if (segs.isNotEmpty()) {
                    msgs.add(CachedMessage(mo.optString("role", "chat"), segs))
                }
            }
            if (msgs.isEmpty()) null else CachedSession(
                key = o.getString("key"),
                title = o.optString("title"),
                updatedAt = o.getLong("updatedAt"),
                messages = msgs,
            )
        }.getOrNull()
    }

    private companion object {
        const val KEY_PREFIX = "conn:"
        const val KEY_ENABLED = "offline_cache"
    }
}

/**
 * 离线消息图片的字节缓存：ref（http(s) URL 或 data: URL）→ filesDir 下按内容键命名的文件。
 * 下载带页面同款登录 Cookie；统一在独立线程池执行，失败静默（离线查看时显示占位）。
 */
object OfflineImageStore {

    private const val MAX_IMAGE_BYTES = 400 * 1024
    private const val MAX_SCALE_EDGE = 1280
    private const val DIR_BUDGET_BYTES = 12L * 1024 * 1024
    private const val EXT = ".img"

    private val executor = Executors.newFixedThreadPool(2)
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    /** 图片引用 → 本地文件名键。同一图片（相同 URL/字节）全局只存一份。 */
    fun keyFor(ref: String): String =
        MessageDigest.getInstance("SHA-1").digest(ref.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    fun fileFor(context: Context, key: String, connectionId: String): File? {
        val dir = rootDir(context, connectionId) ?: return null
        return File(dir, "$key$EXT").takeIf { it.isFile }
    }

    /** 确保图片已落盘；已存在或在途时直接返回，否则按 ref 类型分派后台任务。 */
    fun ensureCached(context: Context, ref: String, connectionId: String) {
        if (!ref.startsWith("http") && !ref.startsWith("data:")) return
        val app = context.applicationContext
        val key = keyFor(ref)
        val dir = rootDir(app, connectionId) ?: return
        if (File(dir, "$key$EXT").exists()) return
        val tag = "$connectionId/$key"
        if (inFlight.putIfAbsent(tag, true) != null) return
        executor.execute {
            try {
                val out = File(dir, "$key$EXT")
                if (out.isFile) return@execute
                val bytes = runCatching {
                    if (ref.startsWith("data:")) decodeDataUrl(ref) else download(app, ref)
                }.getOrNull() ?: return@execute
                val finalBytes =
                    if (bytes.size > MAX_IMAGE_BYTES) downscale(bytes) ?: return@execute else bytes
                val tmp = File(dir, "$key.tmp")
                runCatching {
                    FileOutputStream(tmp).use { it.write(finalBytes) }
                    tmp.renameTo(out)
                }
                enforceBudget(dir)
            } finally {
                inFlight.remove(tag)
            }
        }
    }

    fun clearConnection(context: Context, connectionId: String) {
        runCatching { rootDir(context, connectionId)?.deleteRecursively() }
    }

    /** 每条连接一个子目录，清缓存/删记录时整目录删除，互不影响。 */
    private fun rootDir(context: Context, connectionId: String): File? = runCatching {
        val app = context.applicationContext
        val dirName = MessageDigest.getInstance("SHA-1").digest(connectionId.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        File(File(app.filesDir, "zpocket_images"), dirName).also { it.mkdirs() }
    }.getOrNull()

    private fun download(context: Context, url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            // 带上 WebView 的登录态：会话图片地址大多需要登录才能访问
            runCatching { CookieManager.getInstance().getCookie(url) }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
            conn.setRequestProperty("User-Agent", System.getProperty("http.agent") ?: "ZPocket")
            if (conn.responseCode !in 200..299) return null
            val input = conn.inputStream ?: return null
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var read: Int
            var total = 0
            while (input.read(chunk).also { read = it } >= 0) {
                total += read
                if (total > 8 * 1024 * 1024) return null // 异常大图直接放弃
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeDataUrl(ref: String): ByteArray? {
        val base64 = ref.substringAfter(";base64,", "").takeIf { it.isNotEmpty() } ?: return null
        return Base64.decode(base64, Base64.DEFAULT)
    }

    /** 超过单张上限的图片：解码后把长边缩到 MAX_SCALE_EDGE 再转 JPEG。 */
    private fun downscale(bytes: ByteArray): ByteArray? = runCatching {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scale = MAX_SCALE_EDGE.toFloat() / maxOf(src.width, src.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else {
            src
        }
        java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }.getOrNull()

    /** 图片目录总量超预算时按最后修改时间从旧到新删除（极端场景兜底）。 */
    private fun enforceBudget(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DIR_BUDGET_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= DIR_BUDGET_BYTES) return
            val size = f.length()
            if (f.delete()) total -= size
        }
    }
}

/**
 * RemoteScreen console 桥的落库入口：开关检查 → 持久化快照 → 触发图片字节缓存。
 * 与 SessionNotifier 同一哲学：缓存是增强体验，任何失败静默，不影响远程会话。
 */
object OfflineMessageCache {

    // 单线程顺序写：快照落库涉及整表 JSON 解析/序列化（可达几百 KB），
    // 流式输出时每秒多次，不能在主线程做；单线程保证写入顺序。
    private val writeExecutor = Executors.newSingleThreadExecutor()

    fun handleSnapshot(context: Context, connectionId: String, snapshot: MessageSnapshot) {
        val app = context.applicationContext
        val store = MessageCacheStore(app)
        // 开关检查留在主线程（廉价），重活全部移交后台
        if (!store.isOfflineCacheEnabled()) return
        writeExecutor.execute {
            store.saveSnapshot(connectionId, snapshot, System.currentTimeMillis())
            snapshot.messages.forEach { m ->
                m.segments.forEach { seg ->
                    if (seg is MessageSegment.Image) {
                        OfflineImageStore.ensureCached(app, seg.ref, connectionId)
                    }
                }
            }
        }
    }
}
