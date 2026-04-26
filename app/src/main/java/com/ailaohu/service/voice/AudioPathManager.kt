package com.ailaohu.service.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音频路径管理器（分层防御体系 - 管理层）
 * 
 * 解决的问题：
 * - AHAL底层配置失效 (status -22)
 * - ACDB校准丢失 (Error 19)
 * - 高优先级任务抢占音频链路
 * - Mode 3 导致录音流瞬间Standby
 * 
 * 核心策略：
 * 1. 强制重置Mode：MODE_NORMAL → MODE_IN_COMMUNICATION，修复ACDB校准
 * 2. 独占音频焦点：AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
 * 3. 多音源动态回退：VOICE_COMMUNICATION → VOICE_RECOGNITION → MIC → DEFAULT
 * 4. 硬件切换缓冲：200ms延迟等待HAL层释放资源
 */
@Singleton
class AudioPathManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AudioPathManager"
        const val SWITCH_DELAY_MS = 200L
        private const val HAL_RESET_DELAY_MS = 50L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null

    private var currentAudioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private var isPrepared = false
    private var isScreenCaptureActive = false

    /**
     * 音频源优先级排列（容灾级回退机制）
     * VOICE_COMMUNICATION: 通讯源，解决Device 38 (VA_MIC)属性为空
     * VOICE_RECOGNITION: 语音识别源，自带回声消除
     * MIC: 普通麦克风源
     * DEFAULT: 系统默认源
     */
    private val audioSourceFallbackChain = intArrayOf(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.DEFAULT
    )

    /**
     * 环境自检与焦点抢占 (Pre-flight Check)
     * 
     * 在请求麦克风前，强制重置音频状态，修复status -22 (参数错误)
     * 解决ACDB Error 19导致的校准数据缺失
     */
    fun prepareForRecognition(): Boolean {
        Log.d(TAG, "========== 开始音频环境自检 ==========")

        // 1. 强制回归正常模式，随后切入通讯模式以加载稳定的通话校准 (ACDB Fix)
        try {
            if (audioManager.mode != AudioManager.MODE_NORMAL) {
                Log.d(TAG, "当前Mode: ${audioManager.mode}, 强制重置为MODE_NORMAL")
                audioManager.mode = AudioManager.MODE_NORMAL
            }

            // 给HAL层一点响应时间
            Thread.sleep(HAL_RESET_DELAY_MS)

            // 切入通讯模式：加载ACDB校准数据，解决Error 19
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            Log.d(TAG, "已切换到MODE_IN_COMMUNICATION (ACDB校准加载)")
        } catch (e: Exception) {
            Log.e(TAG, "音频模式切换失败", e)
        }

        // 2. 申请独占焦点
        val result = requestExclusiveFocus()
        isPrepared = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "音频焦点获取结果: $isPrepared")

        // 3. 重置音频源到最高优先级
        currentAudioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION

        Log.d(TAG, "========== 音频环境自检完成 ==========")
        return isPrepared
    }

    /**
     * 请求独占音频焦点
     */
    private fun requestExclusiveFocus(): Int {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                handleAudioFocusChange(focusChange)
            }

            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener!!)
                .build()

            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }
    }

    /**
     * 获取当前音频源
     */
    fun getAudioSource(): Int = currentAudioSource

    /**
     * 获取下一个回退音频源
     * 按优先级链路回退：VOICE_COMMUNICATION → VOICE_RECOGNITION → MIC → DEFAULT
     * @return 回退后的音频源，如果所有音源都已尝试则返回null
     */
    fun fallbackAudioSource(): Int? {
        val currentIndex = audioSourceFallbackChain.indexOf(currentAudioSource)
        if (currentIndex < 0 || currentIndex >= audioSourceFallbackChain.size - 1) {
            Log.e(TAG, "所有音频源均已尝试失败")
            return null
        }
        val nextSource = audioSourceFallbackChain[currentIndex + 1]
        Log.w(TAG, "音频源回退: ${audioSourceName(currentAudioSource)} → ${audioSourceName(nextSource)}")
        currentAudioSource = nextSource
        return nextSource
    }

    /**
     * 重置音频源到最高优先级
     */
    fun resetAudioSource() {
        currentAudioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    }

    /**
     * 获取音频源名称（用于日志）
     */
    fun audioSourceName(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
            else -> "UNKNOWN($source)"
        }
    }

    /**
     * 设置录屏状态
     * 当录屏活跃时，需要特殊处理音频并发
     */
    fun setScreenCaptureActive(active: Boolean) {
        isScreenCaptureActive = active
        Log.d(TAG, "录屏状态: $active")
    }

    /**
     * 检查录屏是否活跃
     */
    fun isScreenCaptureActive(): Boolean = isScreenCaptureActive

    /**
     * 处理音频焦点变化
     */
    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.e(TAG, "AUDIOFOCUS_LOSS - 音频焦点永久丢失")
                isPrepared = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.w(TAG, "AUDIOFOCUS_LOSS_TRANSIENT - 临时丢失焦点")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.w(TAG, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "AUDIOFOCUS_GAIN - 重新获取音频焦点")
                isPrepared = true
            }
        }
    }

    /**
     * 释放音频资源
     * 必须在AutoGLM执行完"拨打电话"动作后显式调用
     * 否则手机会一直处于Mode 3导致后续识别全部失效
     */
    fun release() {
        Log.d(TAG, "释放AudioPathManager")

        // 1. 放弃音频焦点
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest!!)
        } else if (audioFocusChangeListener != null) {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }

        // 2. 重置为正常模式（极其重要！）
        try {
            if (audioManager.mode != AudioManager.MODE_NORMAL) {
                audioManager.mode = AudioManager.MODE_NORMAL
                Log.d(TAG, "已恢复MODE_NORMAL")
            }
        } catch (e: Exception) {
            Log.e(TAG, "恢复MODE_NORMAL失败", e)
        }

        focusRequest = null
        audioFocusChangeListener = null
        isPrepared = false
        currentAudioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    }

    /**
     * 检查是否已准备
     */
    fun isReady(): Boolean = isPrepared

    /**
     * 获取切换延迟，等待HAL层释放Mode3的硬件资源
     */
    fun getSwitchDelayMillis(): Long = SWITCH_DELAY_MS

    /**
     * 强力重置音频HAL
     * 当AudioRecord.startRecording()后getRecordingState依然不是RECORDSTATE_RECORDING时调用
     * 部分高通平台支持setParameters("reinit_audio_hal=1")
     */
    fun forceResetAudioHal() {
        Log.w(TAG, "执行强力音频HAL重置")
        try {
            // 先释放当前资源
            release()

            // 给HAL层充分时间完成重置
            Thread.sleep(100)

            // 尝试通过setParameters重置（部分高通平台支持）
            try {
                audioManager.setParameters("reinit_audio_hal=1")
                Log.d(TAG, "已发送reinit_audio_hal=1")
            } catch (e: Exception) {
                Log.d(TAG, "setParameters不支持，使用常规重置")
            }

            // 重新准备环境
            Thread.sleep(HAL_RESET_DELAY_MS)
        } catch (e: Exception) {
            Log.e(TAG, "强力重置音频HAL失败", e)
        }
    }
}
