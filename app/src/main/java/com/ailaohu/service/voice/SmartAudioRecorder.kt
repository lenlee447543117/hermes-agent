package com.ailaohu.service.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智能音频录音器（分层防御体系 - 采集层）
 * 
 * 核心特性：
 * 1. 多音源动态回退：VOICE_COMMUNICATION → VOICE_RECOGNITION → MIC → DEFAULT
 * 2. 空文件拦截与防抖：防止1214错误
 * 3. 带重试机制的录音启动：对抗Bixby瞬时拦截
 * 4. AHAL缓冲阻塞检测
 */
@Singleton
class SmartAudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioPathManager: AudioPathManager
) {

    /**
     * 次构造函数：用于非 Hilt 注入场景（如 WakeUpService）
     * 内部创建自己的 AudioPathManager 实例
     */
    constructor(context: Context) : this(context, AudioPathManager(context))

    private var mediaRecorder: MediaRecorder? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile
    private var isRecording = false
    private var startTimeMillis = 0L
    private var currentOutputFile: File? = null
    private var currentSourceName: String = ""

    companion object {
        private const val TAG = "SmartAudioRecorder"
        private const val MIN_RECORD_DURATION_MS = 800L
        private const val MIN_FILE_SIZE_BYTES = 1024 * 2
        private const val MAX_START_RETRIES = 3
        private const val RETRY_DELAY_MS = 300L
        private const val BITRATE_16K = 16000
        private const val SAMPLE_RATE_16K = 16000
    }

    /**
     * 使用AudioPathManager准备音频环境后启动录音
     * 采用多音源动态回退机制
     */
    suspend fun startRecording(outputFile: File): Boolean {
        if (isRecording) return false

        // 1. 通过AudioPathManager准备音频环境（Pre-flight Check）
        if (!audioPathManager.prepareForRecognition()) {
            Log.e(TAG, "❌ 音频环境准备失败，无法获取音频焦点")
            return false
        }

        // 2. 硬件切换缓冲：等待HAL层释放资源
        delay(audioPathManager.getSwitchDelayMillis())

        // 3. 尝试当前音频源录音，失败则自动回退
        currentOutputFile = outputFile
        var success = false

        // 重置音频源到最高优先级
        audioPathManager.resetAudioSource()

        while (!success && audioPathManager.getAudioSource() != -1) {
            val source = audioPathManager.getAudioSource()
            currentSourceName = audioPathManager.audioSourceName(source)
            Log.d(TAG, "尝试使用音频源: $currentSourceName")

            var retryCount = 0
            while (retryCount < MAX_START_RETRIES && !success) {
                try {
                    mediaRecorder = createRecorder(outputFile, source)
                    startTimeMillis = System.currentTimeMillis()
                    isRecording = true
                    success = true
                    Log.i(TAG, "✅ 录音启动成功 (音源=$currentSourceName, 尝试=${retryCount + 1})")
                } catch (e: IOException) {
                    Log.e(TAG, "录音IO错误 (音源=$currentSourceName, 尝试=${retryCount + 1}): ${e.message}")
                    releaseRecorder()
                    retryCount++
                    if (retryCount < MAX_START_RETRIES) delay(RETRY_DELAY_MS)
                } catch (e: RuntimeException) {
                    Log.e(TAG, "录音运行时错误 (音源=$currentSourceName, 尝试=${retryCount + 1}): ${e.message}")
                    releaseRecorder()
                    retryCount++
                    if (retryCount < MAX_START_RETRIES) delay(RETRY_DELAY_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "录音未知错误 (音源=$currentSourceName, 尝试=${retryCount + 1}): ${e.message}")
                    releaseRecorder()
                    retryCount++
                    if (retryCount < MAX_START_RETRIES) delay(RETRY_DELAY_MS)
                }
            }

            // 如果当前音源所有重试都失败，尝试回退到下一个音源
            if (!success) {
                val nextSource = audioPathManager.fallbackAudioSource()
                if (nextSource == null) {
                    Log.e(TAG, "❌ 所有音频源均已尝试失败")
                    break
                }
                // 回退后给HAL层一点响应时间
                delay(100)
            }
        }

        if (!success) {
            // 所有音源都失败，尝试强力重置HAL
            Log.w(TAG, "⚠️ 所有音源失败，尝试强力重置音频HAL")
            audioPathManager.forceResetAudioHal()
        }

        return success
    }

    /**
     * 创建MediaRecorder实例
     */
    private fun createRecorder(outputFile: File, audioSource: Int): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(audioSource)
            // 使用3GP格式 + AMR NB编码器，兼容性最好，智谱ASR支持
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setAudioChannels(1)
            setAudioSamplingRate(SAMPLE_RATE_16K)
            setAudioEncodingBitRate(BITRATE_16K)
            setOutputFile(outputFile.absolutePath)

            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaRecorder错误: what=$what, extra=$extra (音源=$currentSourceName)")
            }
            setOnInfoListener { _, what, extra ->
                Log.d(TAG, "MediaRecorder信息: what=$what, extra=$extra")
            }

            prepare()
            start()
        }
    }

    /**
     * 安全停止录音，返回有效文件（防1214错误）
     */
    fun stopRecording(): File? {
        if (!isRecording || mediaRecorder == null) return null

        try {
            mediaRecorder?.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "停止录音过快: ${e.message}")
        } finally {
            releaseRecorder()
            isRecording = false

            // 释放AudioPathManager管理的音频资源
            audioPathManager.release()
        }

        val duration = System.currentTimeMillis() - startTimeMillis
        val file = currentOutputFile
        currentOutputFile = null

        // 终极拦截：只有符合物理规律的文件，才允许送去智谱ASR
        if (file != null && file.exists()) {
            if (duration < MIN_RECORD_DURATION_MS) {
                Log.w(TAG, "录音太短 (${duration}ms). 丢弃防止ASR 1214错误.")
                file.delete()
                return null
            }
            if (file.length() < MIN_FILE_SIZE_BYTES) {
                Log.w(TAG, "文件太小 (${file.length()} bytes). 可能是空壳文件，丢弃.")
                file.delete()
                return null
            }
            Log.d(TAG, "✅ 录音文件有效 (时长=${duration}ms, 大小=${file.length()}bytes, 音源=$currentSourceName)")
            return file
        }
        return null
    }

    fun isRecordingActive(): Boolean = isRecording

    fun forceStop() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {}
        releaseRecorder()
        isRecording = false
        audioPathManager.release()
        currentOutputFile?.let {
            if (it.exists()) it.delete()
        }
        currentOutputFile = null
    }

    private fun releaseRecorder() {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "释放录音器失败: ${e.message}")
        } finally {
            mediaRecorder = null
        }
    }
}
