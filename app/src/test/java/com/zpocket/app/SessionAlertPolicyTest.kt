package com.zpocket.app

import com.zpocket.app.ui.SessionAlertPolicy
import com.zpocket.app.ui.SessionAlertPolicy.AlertKind
import com.zpocket.app.ui.SessionAlertPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAlertPolicyTest {

    private val t0 = 1_000_000L

    private fun policy() = SessionAlertPolicy()

    private fun notified(
        p: SessionAlertPolicy,
        state: String?,
        nowMs: Long = t0,
        foreground: Boolean = false,
    ): AlertKind? =
        (p.onState(state, nowMs, foreground) as? Decision.Notify)?.kind

    @Test
    fun baselineIsSilentExceptAttentionStates() {
        // 基线 running/idle/completed：不提醒
        assertEquals(null, notified(policy(), "running"))
        assertEquals(null, notified(policy(), "idle"))
        assertEquals(null, notified(policy(), "completed"))
        // 基线即等待/出错（重连后仍有待处理事项）：提醒
        assertEquals(AlertKind.WAITING, notified(policy(), "waiting"))
        assertEquals(AlertKind.ERROR, notified(policy(), "error"))
    }

    @Test
    fun notifiesOnAttentionTransitions() {
        var p = policy()
        notified(p, "running")
        assertEquals(AlertKind.COMPLETED, notified(p, "completed"))

        p = policy()
        notified(p, "running")
        assertEquals(AlertKind.WAITING, notified(p, "waiting"))

        p = policy()
        notified(p, "running")
        assertEquals(AlertKind.ERROR, notified(p, "error"))
    }

    @Test
    fun silentStatesNeverNotify() {
        val p = policy()
        notified(p, "idle")
        assertEquals(null, notified(p, "running"))
        assertEquals(null, notified(p, "paused"))
        assertEquals(null, notified(p, "idle"))
    }

    @Test
    fun repeatedStateDoesNotRenotify() {
        val p = policy()
        notified(p, "running")
        assertEquals(AlertKind.COMPLETED, notified(p, "completed"))
        assertEquals(null, notified(p, "completed")) // 同状态重复上报
        assertEquals(null, notified(p, "completed"))
    }

    @Test
    fun foregroundSuppressesNotification() {
        val p = policy()
        notified(p, "running")
        assertEquals(null, notified(p, "completed", foreground = true))
        // 用户没看到（前台时静默），之后同一状态重复上报也不应再补发
        assertEquals(null, notified(p, "completed"))
    }

    @Test
    fun sameKindIsRateLimitedWithinTwoMinutes() {
        val p = policy()
        notified(p, "running")
        assertEquals(AlertKind.WAITING, notified(p, "waiting", nowMs = t0))
        // 回到 running 再等待：2 分钟内不重复提醒
        notified(p, "running", nowMs = t0 + 60_000)
        assertEquals(null, notified(p, "waiting", nowMs = t0 + 90_000))
        // 超过 2 分钟后恢复提醒
        notified(p, "running", nowMs = t0 + 130_000)
        assertEquals(AlertKind.WAITING, notified(p, "waiting", nowMs = t0 + 130_000))
    }

    @Test
    fun rateLimitIsPerKind() {
        val p = policy()
        notified(p, "waiting")
        // waiting 刚提醒过，但 error 是另一类，不受 waiting 的限频影响
        notified(p, "running")
        assertEquals(AlertKind.ERROR, notified(p, "error"))
    }

    @Test
    fun unknownStateIsIgnoredWithoutDisturbingTransitions() {
        val p = policy()
        notified(p, "running")
        // 页面改版/垃圾输入：不推进状态机
        assertEquals(null, notified(p, "garbage"))
        assertEquals(null, notified(p, "unknown"))
        assertEquals(null, notified(p, null))
        assertEquals(AlertKind.COMPLETED, notified(p, "completed"))
    }

    @Test
    fun unknownAsBaselineDoesNotConsumeIt() {
        val p = policy()
        assertEquals(null, notified(p, "garbage"))
        // 垃圾输入不算基线，随后的 running 仍是基线（静默），completed 正常提醒
        assertEquals(null, notified(p, "running"))
        assertEquals(AlertKind.COMPLETED, notified(p, "completed"))
    }

    @Test
    fun decisionSilentHasNoSideEffects() {
        // Sanity：Silent 决策存在且可判别（保证 sealed 分支齐全）
        val d = policy().onState("idle", t0, false)
        assertTrue(d is Decision.Silent)
    }
}
