package com.zpocket.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 远程页期间的前台保活服务。
 *
 * 根因：切后台后进程被系统冻结（Android 12+ 缓存应用冻结）或被厂商省电策略查杀，
 * WebSocket 心跳停止，云端中继超时断开；切回前台时官方页面只能走重连动画并重新
 * 同步整个会话。前台服务让进程保持前台优先级，心跳不中断，页面感知不到离开过。
 *
 * 语义：只在远程页激活期间存活（进入时 start、离开时 stop，由 AppRoot 驱动）；
 * START_NOT_STICKY —— 被系统杀死后不自启，避免出现「通知还在但会话已丢」的假连接。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "后台保持连接",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "远程页打开期间保持与电脑端 ZCode 的连接"
            }
        )
        startForegroundCompat(buildNotification())
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keepalive)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在保持与电脑端 ZCode 的连接")
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun startForegroundCompat(notification: Notification) {
        // specialUse 类型是 Android 14 引入的，低版本传了不认识；两参形式全版本可用
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, KeepAliveService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
