package com.ailaohu.service.watchdog

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ailaohu.R
import com.ailaohu.service.accessibility.AutoPilotService
import com.ailaohu.util.Constants

class WatchdogService : Service() {

    companion object {
        private const val TAG = "Watchdog"
        private const val NOTIFICATION_ID = 2001
        private const val CHECK_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, WatchdogService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var checkRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("AI沪老守护中"))
        startPeriodicCheck()
    }

    private fun startPeriodicCheck() {
        checkRunnable = object : Runnable {
            override fun run() {
                checkAccessibilityStatus()
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
        handler.post(checkRunnable!!)
    }

    private fun checkAccessibilityStatus() {
        if (!AutoPilotService.isRunning()) {
            Log.w(TAG, "无障碍服务已断开，发送告警通知")
            showDisconnectAlert()
        } else {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, createNotification("AI沪老守护中"))
        }
    }

    private fun showDisconnectAlert() {
        val manager = getSystemService(NotificationManager::class.java)
        val alertNotification = NotificationCompat.Builder(this, Constants.CHANNEL_WATCHDOG)
            .setContentTitle("⚠️ AI沪老需要您的注意")
            .setContentText("无障碍服务已断开，请重新开启以确保正常使用")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, alertNotification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.CHANNEL_WATCHDOG,
                "权限守护",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "监控无障碍服务状态"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, Constants.CHANNEL_WATCHDOG)
            .setContentTitle("AI沪老")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        checkRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
