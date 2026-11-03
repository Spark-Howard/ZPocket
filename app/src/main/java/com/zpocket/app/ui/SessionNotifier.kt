package com.zpocket.app.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zpocket.app.MainActivity
import com.zpocket.app.R
import com.zpocket.app.data.ConnectionStore

/**
 * 会话状态通知的决策状态机（纯逻辑，不依赖 Android，可单测）。
 *
 * 输入：注入脚本上报的会话状态（idle/running/waiting/paused/completed/error）
 * 与当前时间、APP 是否前台；输出：是否弹通知。
 *
 * 规则：
 * - 只在「迁移进入」等待/完成/出错三种状态时提醒；running/idle/paused 静默；
 * - APP 前台时一律静默（用户正看着页面，通知冗余）；
 * - 首个有效状态为基线，不提醒 —— 例外是基线即 waiting/error（重连后仍有
 *   待处理事项，值得提醒）；
 * - 同一类提醒 2 分钟内不重复（agent 反复提问/重试时防轰炸）。
 */
class SessionAlertPolicy {

    /** 提醒类型与通知文案。 */
    enum class AlertKind(val title: String, val body: String) {
        WAITING("ZCode 等你回复", "会话在等你确认或回答"),
        COMPLETED("任务已完成", "ZCode 跑完了当前任务"),
        ERROR("ZCode 运行出错", "会话出错了，点开看看"),
    }

    sealed interface Decision {
        data object Silent : Decision
        data class Notify(val kind: AlertKind) : Decision
    }

    private var hasBaseline = false
    private var prevState: String? = null
    private val lastNotifiedAtMs = mutableMapOf<AlertKind, Long>()

    fun onState(state: String?, nowMs: Long, appForeground: Boolean): Decision {
        // 未知/垃圾输入直接忽略，不推进状态机（页面改版时最多"不提醒"，不会误报）
        val kind = when (state) {
            "waiting" -> AlertKind.WAITING
            "completed" -> AlertKind.COMPLETED
            "error" -> AlertKind.ERROR
            "idle", "running", "paused" -> null
            else -> return Decision.Silent
        }
        val firstReport = !hasBaseline
        val isTransition = state != prevState
        hasBaseline = true
        prevState = state
        if (kind == null) return Decision.Silent
        if (!isTransition && !firstReport) return Decision.Silent
        // 基线即待处理状态（重连场景）允许提醒，其余基线静默
        if (firstReport && state == "completed") return Decision.Silent
        if (appForeground) return Decision.Silent
        val last = lastNotifiedAtMs[kind] ?: 0L
        if (nowMs - last < RATE_LIMIT_MS) return Decision.Silent
        lastNotifiedAtMs[kind] = nowMs
        return Decision.Notify(kind)
    }

    companion object {
        private const val RATE_LIMIT_MS = 2 * 60 * 1000L
    }
}

/**
 * 把远程页观察脚本（TASK_OBSERVER_JS）上报的状态转成本地通知。
 *
 * 由 RemoteScreen 的 onConsoleMessage 桥接到这里；所有异常路径静默跳过 ——
 * 通知是增强体验，任何失败（权限被拒、开关关闭、系统异常）都不影响远程会话。
 */
object SessionNotifier {

    private const val CHANNEL_ID = "task_status"
    private const val NOTIFICATION_ID = 2

    fun handleStateReport(
        context: Context,
        deviceName: String,
        policy: SessionAlertPolicy,
        state: String,
    ) {
        val kind = (policy.onState(state, System.currentTimeMillis(), MainActivity.isForeground)
            as? SessionAlertPolicy.Decision.Notify)?.kind ?: return
        val app = context.applicationContext
        // 开关触发时实时读取（不走 WebView 闭包，避免 Compose 闭包捕获旧值）
        if (!ConnectionStore(app).isTaskNotifyEnabled()) return
        // Android 13+ 通知是运行时权限：未授权/被系统关掉时静默跳过，不打扰远程会话
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(app)
        if (!manager.areNotificationsEnabled()) return
        runCatching {
            manager.notify(NOTIFICATION_ID, buildNotification(app, deviceName, kind))
        }
    }

    private fun buildNotification(
        context: Context,
        deviceName: String,
        kind: SessionAlertPolicy.AlertKind,
    ): Notification {
        getSystemService(context).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "任务状态通知",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "任务完成、等待确认或出错时提醒"
                enableVibration(true)
            }
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle(kind.title)
            .setContentText("「$deviceName」${kind.body}")
            // 点击回到 APP：自动重连开关打开时直接回到会话页
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun getSystemService(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
            ?: throw IllegalStateException("NotificationManager 不可用")
}
