package com.ailaohu.service.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音互动管理器（分层防御体系 - 同步层）
 * 
 * 核心功能：
 * 1. 协调语音识别与屏幕录制的音频冲突（零字节静音填充法）
 * 2. 检测录音占用状态并自动延迟重试
 * 3. 适配三星设备的 Bixby 音频抢占问题
 * 4. 音频模式动态切换 (NORMAL <-> IN_COMMUNICATION)
 * 
 * 关键策略：
 * - 录屏冲突使用"逻辑开关"而非"物理启停"
 * - 不停止录音，而是将发往录屏编码器的数据抹零
 * - 麦克风权限依然保留在当前进程
 */
@Singleton
class VoiceInteractionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioPathManager: AudioPathManager
) {
    companion object {
        private const val TAG = "VoiceInteraction"
        private const val RETRY_DELAY_MS = 300L
        private const val MAX_RETRY_COUNT = 3
        private const val AUDIO_SWITCH_BUFFER_MS = 100L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var currentRetryCount = 0
    private var isVoiceActive = false
    
    /**
     * 录屏静音标志
     * 当AI识别活跃时，录屏数据流应该被静音填充
     * 但不能物理静音麦克风，否则AI也无法录音
     */
    @Volatile
    var isAiRecognizing: Boolean = false
        private set
    
    var onVoiceStateChanged: ((Boolean) -> Unit)? = null
    var onAudioConflictDetected: ((String) -> Unit)? = null
    
    /**
     * 开始语音识别流程
     * 
     * 完整流程：
     * 1. 标记AI识别活跃（录屏数据流将被静音填充）
     * 2. 申请独占音频焦点
     * 3. 延迟等待 HAL 层释放资源
     * 4. 启动语音识别
     */
    fun startVoiceRecognition(
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d(TAG, "准备启动语音识别...")
        
        // 1. 标记AI识别活跃 - 录屏数据流将被静音填充而非物理停止
        isAiRecognizing = true
        audioPathManager.setScreenCaptureActive(true)
        
        // 2. 申请音频焦点（AudioPathManager内部会处理Mode切换和ACDB修复）
        val isFocusGranted = audioPathManager.prepareForRecognition()
        if (!isFocusGranted) {
            val errorMsg = "无法获取音频焦点，可能被其他应用占用"
            Log.e(TAG, errorMsg)
            onAudioConflictDetected?.invoke(errorMsg)
            
            if (currentRetryCount < MAX_RETRY_COUNT) {
                currentRetryCount++
                Log.w(TAG, "尝试第 $currentRetryCount 次延迟重试...")
                mainHandler.postDelayed({
                    startVoiceRecognition(onReady, onError)
                }, RETRY_DELAY_MS)
            } else {
                currentRetryCount = 0
                isAiRecognizing = false
                audioPathManager.setScreenCaptureActive(false)
                onError(errorMsg)
            }
            return
        }
        
        currentRetryCount = 0
        isVoiceActive = true
        onVoiceStateChanged?.invoke(true)
        
        // 3. 延迟等待硬件完成链路切换
        CoroutineScope(Dispatchers.Main).launch {
            delay(AUDIO_SWITCH_BUFFER_MS + audioPathManager.getSwitchDelayMillis())
            Log.d(TAG, "✅ 音频链路已就绪，可以开始识别")
            onReady()
        }
    }
    
    /**
     * 结束语音识别流程
     * 
     * 完整流程：
     * 1. 释放音频焦点
     * 2. 恢复音频模式为正常
     * 3. 恢复录屏数据流
     */
    fun endVoiceRecognition() {
        if (!isVoiceActive) return
        
        Log.d(TAG, "结束语音识别流程")
        
        // 1. 标记AI识别结束
        isAiRecognizing = false
        audioPathManager.setScreenCaptureActive(false)
        
        // 2. 释放AudioPathManager管理的音频资源
        try {
            audioPathManager.release()
        } catch (e: Exception) {
            Log.e(TAG, "释放音频焦点失败", e)
        }
        
        isVoiceActive = false
        onVoiceStateChanged?.invoke(false)
    }
    
    /**
     * 零字节静音填充法
     * 
     * 在录屏的数据读取线程中调用此方法
     * 当AI识别活跃时，将发往录屏编码器的数据抹零
     * 这样录屏文件不会出错，且麦克风权限依然保留在当前进程
     * 
     * @param buffer 原始音频数据
     * @param size 数据大小
     * @return 处理后的数据（如果是AI识别模式，返回静音数据）
     */
    fun processAudioDataForScreenCapture(buffer: ByteArray, size: Int): ByteArray {
        if (isAiRecognizing) {
            // 关键：不停止录音，而是将发往录屏编码器的数据抹零
            // 这样录屏文件不会出错，且麦克风权限依然保留在当前进程
            val silentBuffer = ByteArray(size)
            // ByteArray默认值就是0，相当于静音
            return silentBuffer
        }
        return buffer
    }
    
    /**
     * 获取当前音频模式
     */
    fun getCurrentAudioMode(): String {
        return when (audioManager.mode) {
            AudioManager.MODE_NORMAL -> "MODE_NORMAL"
            AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
            AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
            else -> "MODE_UNKNOWN (${audioManager.mode})"
        }
    }
    
    fun isInCall(): Boolean = audioManager.mode == AudioManager.MODE_IN_CALL
    
    fun resetRetryCount() { currentRetryCount = 0 }
    
    /**
     * 检测并处理录音冲突
     * 使用AudioPathManager的多音源回退机制
     */
    fun handleRecordingConflict(errorCode: Int, retryAction: () -> Unit) {
        Log.e(TAG, "检测到录音冲突，错误码: $errorCode")
        
        // 使用AudioPathManager的回退机制
        val nextSource = audioPathManager.fallbackAudioSource()
        if (nextSource != null) {
            Log.w(TAG, "回退到音频源: ${audioPathManager.audioSourceName(nextSource)}")
            mainHandler.postDelayed(retryAction, RETRY_DELAY_MS)
        } else {
            // 所有音源都失败，尝试强力重置HAL
            Log.w(TAG, "所有音源失败，尝试强力重置音频HAL")
            audioPathManager.forceResetAudioHal()
            audioPathManager.resetAudioSource()
            mainHandler.postDelayed(retryAction, RETRY_DELAY_MS * 2)
        }
    }
    
    fun cleanup() {
        Log.d(TAG, "清理 VoiceInteractionManager 资源")
        if (isVoiceActive) endVoiceRecognition()
        audioPathManager.release()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
