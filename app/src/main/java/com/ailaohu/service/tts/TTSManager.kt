package com.ailaohu.service.tts

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TTSManager @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        private const val TAG = "TTSManager"
    }

    // TTS 状态枚举
    enum class TTSStatus {
        Checking,
        NotInstalled,
        Installed
    }

    private var systemTts: TextToSpeech? = null
    private var isInitialized = false
    private var isLanguageSupported = false
    private val handler = Handler(Looper.getMainLooper())

    // 使用 StateFlow 管理状态，避免竞态条件
    private val _ttsStatus = MutableStateFlow(TTSStatus.Checking)
    val ttsStatus: StateFlow<TTSStatus> = _ttsStatus.asStateFlow()

    // 语音播报完成回调
    private var onSpeakCompleteCallback: (() -> Unit)? = null

    // 当前是否正在播报
    private var isSpeaking = false

    val isCurrentlySpeaking: Boolean get() = isSpeaking
    val isReady: Boolean get() = isInitialized && isLanguageSupported

    /**
     * 带延迟的初始化，避开三星系统启动时的音频参数加载高峰
     */
    fun initializeWithDelay() {
        handler.postDelayed({
            initialize()
        }, 500) // 延迟 500ms 避开三星系统启动时的音频参数加载高峰
    }

    fun initialize() {
        if (isInitialized && systemTts != null) {
            _ttsStatus.value = if (isLanguageSupported) TTSStatus.Installed else TTSStatus.NotInstalled
            return
        }

        _ttsStatus.value = TTSStatus.Checking

        systemTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val langResult = systemTts?.isLanguageAvailable(Locale.CHINESE)
                
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "系统不支持中文语音包，需要安装")
                    isLanguageSupported = false
                    isInitialized = true
                    _ttsStatus.value = TTSStatus.NotInstalled
                } else {
                    isLanguageSupported = true
                    // 老人友好：语速稍慢，让老人听得更清楚
                    systemTts?.setSpeechRate(0.85f)
                    systemTts?.setPitch(1.05f)

                    // 设置播报进度监听器，用于检测播报完成
                    systemTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            isSpeaking = true
                            Log.d(TAG, "开始播报: $utteranceId")
                        }

                        override fun onDone(utteranceId: String?) {
                            isSpeaking = false
                            Log.d(TAG, "播报完成: $utteranceId")
                            // 通知播报完成
                            onSpeakCompleteCallback?.invoke()
                            onSpeakCompleteCallback = null
                        }

                        override fun onError(utteranceId: String?) {
                            isSpeaking = false
                            Log.e(TAG, "播报错误: $utteranceId")
                            onSpeakCompleteCallback?.invoke()
                            onSpeakCompleteCallback = null
                        }
                    })

                    isInitialized = true
                    _ttsStatus.value = TTSStatus.Installed
                    Log.d(TAG, "系统TTS初始化成功，中文语音包可用")
                }
            } else {
                Log.e(TAG, "系统TTS初始化失败")
                isInitialized = false
                isLanguageSupported = false
                _ttsStatus.value = TTSStatus.NotInstalled
            }
        }
    }

    fun initializeWithEngine(enginePackageName: String, locale: Locale) {
        shutdown()

        systemTts = TextToSpeech(context, { status ->
            if (status == TextToSpeech.SUCCESS) {
                val langResult = systemTts?.setLanguage(locale)

                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "引擎 $enginePackageName 不支持 $locale")
                    isLanguageSupported = false
                    isInitialized = true
                } else {
                    isLanguageSupported = true
                    systemTts?.setSpeechRate(0.85f)
                    systemTts?.setPitch(1.05f)

                    systemTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            isSpeaking = true
                            Log.d(TAG, "开始播报: $utteranceId")
                        }

                        override fun onDone(utteranceId: String?) {
                            isSpeaking = false
                            Log.d(TAG, "播报完成: $utteranceId")
                            onSpeakCompleteCallback?.invoke()
                            onSpeakCompleteCallback = null
                        }

                        override fun onError(utteranceId: String?) {
                            isSpeaking = false
                            Log.e(TAG, "播报错误: $utteranceId")
                            onSpeakCompleteCallback?.invoke()
                            onSpeakCompleteCallback = null
                        }
                    })

                    isInitialized = true
                    Log.d(TAG, "TTS初始化成功，引擎: $enginePackageName, 语言: $locale")
                }
            } else {
                Log.e(TAG, "引擎 $enginePackageName 初始化失败")
                isInitialized = false
                isLanguageSupported = false
            }
        }, enginePackageName)
    }

    /**
     * 检查TTS语音包是否存在
     * @return true 如果语音包存在
     */
    fun isTtsDataAvailable(): Boolean {
        if (systemTts == null || !isInitialized) return false
        val result = systemTts?.setLanguage(Locale.CHINESE)
        return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /**
     * 打开TTS语音包安装设置页面
     */
    fun openTtsSettings() {
        try {
            val intent = Intent().apply {
                action = TextToSpeech.Engine.ACTION_CHECK_TTS_DATA
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法打开TTS设置页面", e)
            try {
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "无法打开通用设置页面", e2)
            }
        }
    }

    /**
     * 语音播报文本
     * @param text 要播报的文本
     */
    fun speak(text: String) {
        if (!isInitialized) {
            Log.w(TAG, "TTS未初始化，尝试重新初始化")
            initialize()
            return
        }
        
        if (!isLanguageSupported) {
            Log.w(TAG, "TTS不支持中文语音包")
            openTtsSettings()
            return
        }
        
        val utteranceId = UUID.randomUUID().toString()
        systemTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * 语音播报文本，播报完成后回调
     * @param text 要播报的文本
     * @param onComplete 播报完成后的回调
     */
    fun speakWithCallback(text: String, onComplete: (() -> Unit)? = null) {
        if (!isInitialized) {
            Log.w(TAG, "TTS未初始化，尝试重新初始化")
            initialize()
            return
        }
        if (!isLanguageSupported) {
            Log.w(TAG, "TTS不支持中文语音包")
            openTtsSettings()
            return
        }
        onSpeakCompleteCallback = onComplete
        val utteranceId = UUID.randomUUID().toString()
        systemTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * 将文本加入播报队列（不中断当前播报）
     */
    fun enqueue(text: String) {
        if (!isInitialized) {
            initialize()
            return
        }
        if (!isLanguageSupported) {
            Log.w(TAG, "TTS不支持中文语音包")
            openTtsSettings()
            return
        }
        val utteranceId = UUID.randomUUID().toString()
        systemTts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    fun stop() {
        systemTts?.stop()
        isSpeaking = false
        onSpeakCompleteCallback = null
    }

    fun updateSpeechRate(rate: Float) {
        systemTts?.setSpeechRate(rate)
        Log.d(TAG, "语速已更新: $rate")
    }

    fun updatePitch(pitch: Float) {
        systemTts?.setPitch(pitch)
        Log.d(TAG, "音调已更新: $pitch")
    }

    fun updateLanguage(locale: Locale) {
        val result = systemTts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "不支持的语言: $locale")
            isLanguageSupported = false
        } else {
            isLanguageSupported = true
            Log.d(TAG, "语言已更新: $locale")
        }
    }

    fun shutdown() {
        systemTts?.shutdown()
        systemTts = null
        isInitialized = false
        isLanguageSupported = false
        isSpeaking = false
        onSpeakCompleteCallback = null
    }
}
