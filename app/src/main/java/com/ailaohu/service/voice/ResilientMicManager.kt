package com.ailaohu.service.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 健壮的麦克风管理器
 * 
 * 统一处理：
 * 1. 权限检查
 * 2. 音频焦点获取/释放
 * 3. 音频模式切换（NORMAL ↔ IN_COMMUNICATION）
 * 4. 三星设备预热延迟
 * 5. 资源状态同步
 */
@Singleton
class ResilientMicManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ResilientMic"
        const val SAMSUNG_WARMUP_DELAY = 400L // 三星设备预热延迟
        const val SILENCE_WATCHDOG_TIMEOUT = 3500L // 3.5秒无声音判定故障
        val isSamsungDevice: Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null
    private var isMicActive = false

    /**
     * 检查麦克风权限
     */
    fun hasRecordAudioPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 获取麦克风资源
     * 
     * 完整流程：
     * 1. 检查权限
     * 2. 强制重置音频模式（修复ACDB校准）
     * 3. 切换到通信模式
     * 4. 申请独占音频焦点
     * 5. 三星设备额外预热延迟
     */
    fun acquire(onReady: () -> Unit, onError: (String) -> Unit) {
        if (!hasRecordAudioPermission()) {
            onError("缺少麦克风权限")
            return
        }

        try {
            // 1. 强制回归正常模式（修复残留的Mode 3）
            if (audioManager.mode != AudioManager.MODE_NORMAL) {
                Log.d(TAG, "当前Mode: ${audioManager.mode}, 强制重置为MODE_NORMAL")
                audioManager.mode = AudioManager.MODE_NORMAL
            }

            // 2. 给HAL层响应时间
            Thread.sleep(50)

            // 3. 切入通信模式（加载ACDB校准数据）
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            Log.d(TAG, "已切换到MODE_IN_COMMUNICATION")
        } catch (e: Exception) {
            Log.e(TAG, "音频模式切换失败", e)
        }

        // 4. 申请独占音频焦点
        val focusGranted = requestExclusiveFocus()
        if (!focusGranted) {
            onError("无法获取音频焦点")
            return
        }

        isMicActive = true

        // 5. 三星设备额外预热延迟
        val warmupDelay = if (isSamsungDevice) SAMSUNG_WARMUP_DELAY else 200L
        Log.d(TAG, "麦克风资源已获取，预热延迟: ${warmupDelay}ms (三星设备=$isSamsungDevice)")

        mainHandler.postDelayed({
            onReady()
        }, warmupDelay)
    }

    /**
     * 释放麦克风资源
     * 必须在AutoGLM执行完操作后调用
     */
    fun release() {
        if (!isMicActive) return

        Log.d(TAG, "释放麦克风资源")

        // 1. 释放音频焦点
        releaseFocus()

        // 2. 恢复正常模式
        try {
            if (audioManager.mode != AudioManager.MODE_NORMAL) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } catch (e: Exception) {
            Log.e(TAG, "恢复MODE_NORMAL失败", e)
        }

        isMicActive = false
    }

    /**
     * 请求独占音频焦点
     */
    private fun requestExclusiveFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        Log.e(TAG, "音频焦点丢失")
                        isMicActive = false
                    }
                }
                .build()
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }

        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /**
     * 释放音频焦点
     */
    private fun releaseFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest!!)
        }
        focusRequest = null
    }

    fun isMicAcquired(): Boolean = isMicActive
}
