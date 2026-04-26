package com.ailaohu.service.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ailaohu.R
import com.ailaohu.ui.home.HomeActivity
import com.ailaohu.util.HapticManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WakeUpService : Service() {

    companion object {
        private const val TAG = "WakeUpService"
        private const val CHANNEL_ID = "wakeup_channel"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_START = "action_start_wakeup"
        private const val ACTION_STOP = "action_stop_wakeup"
        private const val RECORD_DURATION_MS = 4000L
        private const val COOLDOWN_AFTER_WAKE_MS = 5000L
        private const val RETRY_DELAY_MS = 3000L
        private const val CYCLE_GAP_MS = 500L

        private val WAKE_WORDS = listOf("沪沪", "呼呼", "护护", "小沪", "助手", "小助手")
        private val WAKE_PHRASES = listOf("你好沪沪", "沪沪你好", "嘿沪沪", "沪沪在吗")

        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, WakeUpService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WakeUpService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var asrService: ZhipuAsrService? = null
    private var isListeningForWakeWord = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        acquireWakeLock()
        asrService = ZhipuAsrService()
        Log.d(TAG, "唤醒服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
                startWakeWordDetection()
            }
            ACTION_STOP -> {
                stopWakeWordDetection()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopWakeWordDetection()
        releaseWakeLock()
        asrService = null
        isRunning = false
        serviceScope.cancel()
        super.onDestroy()
        Log.d(TAG, "唤醒服务已销毁")
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AILaoHu::WakeUpWakeLock"
            ).apply {
                acquire(12 * 60 * 60 * 1000L)
            }
            Log.d(TAG, "WakeLock已获取")
        } catch (e: Exception) {
            Log.e(TAG, "获取WakeLock失败", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "释放WakeLock失败", e)
        }
    }

    private fun startWakeWordDetection() {
        if (isListeningForWakeWord) return
        isListeningForWakeWord = true

        Log.d(TAG, "开始唤醒词检测循环")

        serviceScope.launch {
            while (isActive && isListeningForWakeWord) {
                try {
                    listenForWakeWord()
                } catch (e: Exception) {
                    Log.e(TAG, "唤醒词检测异常", e)
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    private suspend fun listenForWakeWord() {
        val currentAsr = asrService ?: return
        val recorder = SmartAudioRecorder(this)
        val tempFile = java.io.File(cacheDir, "wakeup_${System.currentTimeMillis()}.m4a")

        try {
            val success = recorder.startRecording(tempFile)
            if (!success) {
                Log.w(TAG, "唤醒录音启动失败，${RETRY_DELAY_MS}ms后重试")
                delay(RETRY_DELAY_MS)
                return
            }

            delay(RECORD_DURATION_MS)

            val audioFile = recorder.stopRecording()
            if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
                val result = currentAsr.transcribe(audioFile)

                if (result is ZhipuAsrResult.Success) {
                    val normalized = result.text.trim()
                    Log.d(TAG, "唤醒检测识别: '$normalized'")

                    val isWakeWord = WAKE_WORDS.any { normalized.contains(it) } ||
                            WAKE_PHRASES.any { normalized.contains(it) }

                    if (isWakeWord) {
                        Log.d(TAG, "检测到唤醒词")
                        onWakeWordDetected(normalized)
                        delay(COOLDOWN_AFTER_WAKE_MS)
                    }
                } else if (result is ZhipuAsrResult.Error) {
                    Log.e(TAG, "ASR错误: ${result.message}")
                }
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            delay(CYCLE_GAP_MS)
        }
    }

    private fun onWakeWordDetected(word: String) {
        HapticManager.vibrate(this, HapticManager.Pattern.SUCCESS)

        val intent = Intent(this, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("auto_start_listening", true)
            putExtra("wake_word", word)
        }
        startActivity(intent)

        Log.d(TAG, "已发送唤醒广播，启动语音交互")
    }

    private fun stopWakeWordDetection() {
        isListeningForWakeWord = false
        Log.d(TAG, "唤醒词检测已停止")
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, HomeActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("沪老助手待命中")
            .setContentText("说\"沪沪\"即可唤醒")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSound(null)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "语音唤醒服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台监听唤醒词"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
