package com.ailaohu.service.voice

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceFeedbackPlayer @Inject constructor() {
    companion object {
        private const val TAG = "VoiceFeedback"
        private const val TONE_DURATION_MS = 150
        private const val TONE_VOLUME = 80
    }

    private var isInitialized = false

    fun initialize() {
        isInitialized = true
        Log.d(TAG, "语音反馈播放器已初始化")
    }

    fun playStartTone() {
        playTone(ToneGenerator.TONE_PROP_BEEP, "开始")
    }

    fun playEndTone() {
        playTone(ToneGenerator.TONE_PROP_BEEP2, "结束")
    }

    fun playErrorTone() {
        playTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, "错误")
    }

    fun playSuccessTone() {
        Thread {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                Thread.sleep(150)
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                Thread.sleep(200)
                toneGen.release()
                Log.d(TAG, "播放成功提示音")
            } catch (e: Exception) {
                Log.w(TAG, "成功提示音播放失败", e)
            }
        }.start()
    }

    private fun playTone(toneType: Int, label: String) {
        Thread {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
                toneGen.startTone(toneType, TONE_DURATION_MS)
                Thread.sleep((TONE_DURATION_MS + 50).toLong())
                toneGen.release()
                Log.d(TAG, "播放${label}提示音")
            } catch (e: Exception) {
                Log.w(TAG, "${label}提示音播放失败", e)
            }
        }.start()
    }

    fun release() {
        isInitialized = false
    }
}
