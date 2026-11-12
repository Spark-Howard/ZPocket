package com.zpocket.app

import com.zpocket.app.data.CachedMessage
import com.zpocket.app.data.CachedSession
import com.zpocket.app.data.MessageSegment
import com.zpocket.app.data.MessageSnapshot
import com.zpocket.app.data.OfflineCachePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 缓存策略：会话键派生、容量裁剪（单会话条数 / 会话数 / 总字符预算）。 */
class OfflineCachePolicyTest {

    private fun textMsg(role: String, text: String) =
        CachedMessage(role, listOf(MessageSegment.Text(text)))

    private fun session(title: String, updatedAt: Long, vararg texts: String) =
        CachedSession(
            key = "$title#k",
            title = title,
            updatedAt = updatedAt,
            messages = texts.map { textMsg("user", it) },
        )

    @Test
    fun `会话键由标题与首条消息摘要共同决定`() {
        val msgs = listOf(textMsg("user", "帮我修复登录bug"), textMsg("assistant", "好的"))
        val key1 = OfflineCachePolicy.sessionKey("会话标题", msgs)
        val key2 = OfflineCachePolicy.sessionKey("会话标题", msgs)
        assertEquals("同一输入键稳定", key1, key2)
        // 标题相同但内容不同 → 键不同（防同题会话互相覆盖）
        assertNotEquals(key1, OfflineCachePolicy.sessionKey("会话标题", listOf(textMsg("user", "另一个问题"))))
        // 内容相同但标题不同 → 键也不同（页面改版标题探测失效时不塌缩覆盖）
        assertNotEquals(key1, OfflineCachePolicy.sessionKey(null, msgs))
    }

    @Test
    fun `标题缺失时退化为首条用户消息首行前缀`() {
        assertEquals(
            "帮我修复登录bug，启动就崩",
            OfflineCachePolicy.displayTitle(null, listOf(textMsg("user", "帮我修复登录bug，启动就崩"))),
        )
        val longLine = "这一条用户消息特别长足足超过二十四个字用来验证标题截断逻辑是否生效"
        assertEquals(
            longLine.take(24),
            OfflineCachePolicy.displayTitle(null, listOf(textMsg("user", longLine))),
        )
        assertEquals("未命名会话", OfflineCachePolicy.displayTitle(null, listOf(textMsg("assistant", "回复"))))
        assertEquals("未命名会话", OfflineCachePolicy.displayTitle(null, emptyList()))
    }

    @Test
    fun `normalize 保留最近的消息并截断超长文本`() {
        val many = (0 until 250).map { textMsg("user", "消息$it") }
        val normalized = OfflineCachePolicy.normalize(MessageSnapshot("t", many), nowMs = 1L)
        assertEquals(OfflineCachePolicy.MAX_MESSAGES, normalized.messages.size)
        assertEquals("消息249", (normalized.messages.last().segments[0] as MessageSegment.Text).text)
        assertEquals("消息50", (normalized.messages.first().segments[0] as MessageSegment.Text).text)

        val long = "x".repeat(OfflineCachePolicy.MAX_SEGMENT_CHARS + 500)
        val seg = OfflineCachePolicy.normalize(
            MessageSnapshot(null, listOf(textMsg("user", long))), 1L,
        ).messages[0].segments[0] as MessageSegment.Text
        assertEquals(OfflineCachePolicy.MAX_SEGMENT_CHARS, seg.text.length)
    }

    @Test
    fun `normalize 截断单消息的段数与总字符`() {
        // 段数上限：30 个小段只保留前 20 个
        val tinySegs = CachedMessage("assistant", List(30) { MessageSegment.Text("ab") })
        val byCount = OfflineCachePolicy.normalize(MessageSnapshot(null, listOf(tinySegs)), 1L)
        assertEquals(OfflineCachePolicy.MAX_SEGS_PER_MESSAGE, byCount.messages.single().segments.size)

        // 总字符上限（恰好装满算下、不溢出）：三个 6000 字段 → 保留前两个共 12000，第三个被拒
        val longSeg = MessageSegment.Text("a".repeat(6000))
        val byChars = OfflineCachePolicy.normalize(
            MessageSnapshot(null, listOf(CachedMessage("assistant", listOf(longSeg, longSeg, longSeg)))), 1L,
        )
        val segs = byChars.messages.single().segments
        assertEquals(2, segs.size)
        assertEquals(OfflineCachePolicy.MAX_MESSAGE_CHARS, OfflineCachePolicy.totalChars(listOf(byChars)))
    }

    @Test
    fun `mergeSessions 覆盖同键并按更新时间排序`() {
        val existing = listOf(
            session("甲", updatedAt = 100),
            session("乙", updatedAt = 200),
        )
        val updated = session("乙", updatedAt = 300, "乙的新内容")
        val merged = OfflineCachePolicy.mergeSessions(existing, updated)
        assertEquals(listOf("乙", "甲"), merged.map { it.title })
        assertEquals("乙的新内容", (merged[0].messages[0].segments[0] as MessageSegment.Text).text)
    }

    @Test
    fun `mergeSessions 超出会话数上限丢最旧`() {
        val existing = (0 until OfflineCachePolicy.MAX_SESSIONS).map {
            session("会话$it", updatedAt = it.toLong())
        }
        val merged = OfflineCachePolicy.mergeSessions(existing, session("新会话", updatedAt = 999))
        assertEquals(OfflineCachePolicy.MAX_SESSIONS, merged.size)
        assertTrue("新会话在最前", merged.first().title == "新会话")
        assertTrue("最旧的被淘汰", merged.none { it.title == "会话0" })
    }

    @Test
    fun `mergeSessions 超出总字符预算丢最旧直到达标`() {
        val big = "y".repeat(200_000)
        val existing = listOf(
            session("旧1", updatedAt = 100, big),
            session("旧2", updatedAt = 200, big),
        )
        val merged = OfflineCachePolicy.mergeSessions(existing, session("新", updatedAt = 300, big))
        assertTrue(OfflineCachePolicy.totalChars(merged) <= OfflineCachePolicy.MAX_TOTAL_CHARS)
        // 600K → 丢最旧的旧1 剩 400K 达标：新 + 旧2 留下
        assertEquals(listOf("新", "旧2"), merged.map { it.title })
    }
}
