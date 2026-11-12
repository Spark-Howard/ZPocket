package com.zpocket.app

import com.zpocket.app.data.MessageReportAssembler
import com.zpocket.app.data.MessageSegment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * console 桥分块重组器的行为：按 sid 聚块、乱序/重复容错、坏数据静默丢弃。
 * 页面侧把快照 JSON 切成 {sid,i,n,part} 分块逐条 console.log，这里模拟该协议。
 */
class MessageReportAssemblerTest {

    private fun snapshotJson(title: String?, vararg roles: String): String {
        val messages = JSONArray()
        roles.forEach { role ->
            messages.put(
                JSONObject()
                    .put("role", role)
                    .put(
                        "segs",
                        JSONArray().put(
                            JSONObject().put("t", "text").put("v", "$role 的消息内容")
                        )
                    )
            )
        }
        val obj = JSONObject()
        if (title != null) obj.put("title", title)
        obj.put("messages", messages)
        return obj.toString()
    }

    /** 按页面侧的切分逻辑把快照 JSON 拆成分块信封列表。 */
    private fun chunks(sid: String, json: String, chunkSize: Int = 24): List<String> {
        val n = (json.length + chunkSize - 1) / chunkSize
        return (0 until n).map { i ->
            JSONObject()
                .put("sid", sid)
                .put("i", i)
                .put("n", n)
                .put("part", json.substring(i * chunkSize, minOf((i + 1) * chunkSize, json.length)))
                .toString()
        }
    }

    @Test
    fun `按序喂入全部分块后返回完整快照`() {
        val assembler = MessageReportAssembler()
        val cs = chunks("s1", snapshotJson("标题", "user", "assistant"))
        assertTrue(cs.size > 1)
        for (i in 0 until cs.size - 1) assertNull("第 $i 块不应提前产出", assembler.feed(cs[i]))
        val snap = assembler.feed(cs.last())!!
        assertEquals("标题", snap.title)
        assertEquals(2, snap.messages.size)
        assertEquals("user", snap.messages[0].role)
        assertEquals(
            "assistant 的消息内容",
            (snap.messages[1].segments[0] as MessageSegment.Text).text,
        )
    }

    @Test
    fun `乱序与重复分块仍可重组`() {
        val assembler = MessageReportAssembler()
        val cs = chunks("s1", snapshotJson(null, "user", "assistant", "chat"))
        assertTrue(cs.size >= 3)
        // 乱序喂入除最后一块外的全部，中途插一块重复的
        cs.dropLast(1).reversed().forEach { assertNull(assembler.feed(it)) }
        assertNull(assembler.feed(cs[0])) // 重复块幂等
        val snap = assembler.feed(cs.last()) ?: error("收齐后应产出快照")
        assertEquals(3, snap.messages.size)
        assertNull(snap.title)
    }

    @Test
    fun `两个快照交错喂入各自独立重组`() {
        val assembler = MessageReportAssembler()
        val a = chunks("a", snapshotJson("会话A", "user"))
        val b = chunks("b", snapshotJson("会话B", "assistant"))
        assertTrue(a.size >= 2 && b.size >= 2)
        assertNull(assembler.feed(a[0]))
        assertNull(assembler.feed(b[0]))
        // 先收齐 b：a 还缺块，互不干扰
        var producedB: String? = null
        b.drop(1).forEach { producedB = assembler.feed(it)?.title ?: producedB }
        assertEquals("会话B", producedB)
        var producedA: String? = null
        a.drop(1).forEach { producedA = assembler.feed(it)?.title ?: producedA }
        assertEquals("会话A", producedA)
    }

    @Test
    fun `坏数据静默丢弃且缓冲不受污染`() {
        val assembler = MessageReportAssembler()
        assertNull(assembler.feed(null))
        assertNull(assembler.feed(""))
        assertNull(assembler.feed("不是JSON"))
        // 缺块（n=2 只来一块）与超界参数
        assertNull(
            assembler.feed(
                JSONObject().put("sid", "x").put("i", 0).put("n", 2).put("part", "a").toString()
            )
        )
        assertNull(
            assembler.feed(
                JSONObject().put("sid", "x").put("i", 0).put("n", 99).put("part", "a").toString()
            )
        )
        // 快照内容损坏：拼完后解析失败返回 null
        val bad = chunks("bad", "{\"messages\":[{\"role\":")
        bad.dropLast(1).forEach { assertNull(assembler.feed(it)) }
        assertNull(assembler.feed(bad.last()))
        // 缓冲未受污染：新的快照照常重组
        val good = chunks("good", snapshotJson("恢复", "user"))
        good.dropLast(1).forEach { assertNull(assembler.feed(it)) }
        assertEquals("恢复", assembler.feed(good.last())!!.title)
    }

    @Test
    fun `代码块与图片片段解析`() {
        val assembler = MessageReportAssembler()
        val json = JSONObject().put(
            "messages",
            JSONArray().put(
                JSONObject()
                    .put("role", "assistant")
                    .put(
                        "segs",
                        JSONArray()
                            .put(JSONObject().put("t", "text").put("v", "看这段："))
                            .put(JSONObject().put("t", "code").put("v", "val x = 1").put("lang", "kotlin"))
                            .put(JSONObject().put("t", "img").put("ref", "https://x/y.png").put("w", 300).put("h", 200))
                            .put(JSONObject().put("t", "unknown").put("v", "忽略"))
                    )
            )
        ).toString()
        val snap = assembler.feed(chunks("s", json, chunkSize = 4096).single())!!
        val segs = snap.messages[0].segments
        assertEquals(3, segs.size)
        assertEquals(MessageSegment.Code("val x = 1", "kotlin"), segs[1])
        assertEquals(MessageSegment.Image("https://x/y.png", 300, 200), segs[2])
    }
}
