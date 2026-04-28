package com.ailaohu.service.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音识别引擎类
 * 支持本地识别和智谱云端识别两种模式，具备自动切换和错误恢复能力
 * 
 * 三级防御体系：
 * 1. 预热延迟：三星设备400ms，其他设备200ms
 * 2. 静音监控：3.5秒无声音判定故障，自动降级
 * 3. 错误降级：ERROR_AUDIO时立即切换到智谱云端
 */
@Singleton
class VoiceRecognitionEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val zhipuService: ZhipuAsrService,
    private val smartAudioRecorder: SmartAudioRecorder,
    private val audioPathManager: AudioPathManager,
    private val voiceInteractionManager: VoiceInteractionManager,
    private val resilientMicManager: ResilientMicManager
) {
    /**
     * 语音识别后端枚举
     * LOCAL: 本地系统语音识别
     * ZHIPU_CLOUD: 智谱云端语音识别
     * DIRECT: 直接发送音频+截图给AutoGLM-Phone，不转文字
     */
    enum class Backend { LOCAL, ZHIPU_CLOUD, DIRECT }

    companion object {
        private const val TAG = "VoiceEngine"
        private const val RESTART_DELAY_MS = 600L
        private const val MAX_CONSECUTIVE_ERRORS = 5
        private const val ENGINE_TEST_TIMEOUT_MS = 2000L
        private const val MIN_LISTENING_DURATION_MS = 800L
        private const val QUICK_ERROR_MAX_RETRIES = 2
        private const val VAD_COMPLETE_SILENCE_MS = 5000L
        private const val VAD_POSSIBLY_COMPLETE_SILENCE_MS = 3000L
        private const val SILENCE_WATCHDOG_TIMEOUT = 3500L // 三级防御：3.5秒静音监控
    }

    /**
     * 当前使用的语音识别后端
     */
    var currentBackend = Backend.LOCAL
        private set

    /**
     * 设置语音识别后端
     */
    fun setBackend(backend: Backend) {
        currentBackend = backend
    }
    
    private var speechRecognizer: SpeechRecognizer? = null // 本地语音识别器
    private var tempAudioFile: File? = null // 临时音频文件

    private var isListening = false // 是否正在监听
    private var continuousMode = false // 是否为持续监听模式
    private var consecutiveErrors = 0 // 连续错误次数
    private var manuallyStopped = false // 是否手动停止
    private var isEngineReady = false // 引擎是否就绪
    private var isEngineTesting = false // 是否正在测试引擎
    private var isStoppingTest = false // 是否正在停止测试
    private var isSetupComplete = false // 设置是否完成
    private var currentLanguage: String = "zh-CN" // 当前语言
    private var hasTriedFallback = false // 是否尝试过回退
    private var listeningStartTime: Long = 0L // 监听开始时间
    private var quickErrorRetries = 0 // 快速错误重试次数

    // 存储当前的音频焦点请求
    private var currentAudioFocusRequest: AudioFocusRequest? = null
    // 音频焦点监听器
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.e(TAG, "失去音频焦点 - 被系统强制夺回")
                // 如果焦点被系统强制夺回，需要处理重连逻辑
                if (isListening) {
                    Log.w(TAG, "录音过程中失去焦点，尝试重新获取")
                    reAcquireAudioFocus()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.w(TAG, "临时失去音频焦点")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.w(TAG, "失去焦点但可以降低音量继续")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "获得音频焦点")
            }
        }
    }

    // 回调函数
    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onReadyCallback: (() -> Unit)? = null
    private var onPartialResultCallback: ((String) -> Unit)? = null
    private var onEndCallback: (() -> Unit)? = null
    private var onStateChangedCallback: ((Boolean) -> Unit)? = null
    private var onVoicePackMissingCallback: (() -> Unit)? = null
    private var onMicReadyCallback: (() -> Unit)? = null
    private var onAudioFileReadyCallback: ((File) -> Unit)? = null // 新回调：音频文件准备好

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 引擎测试超时任务
     * 如果本地引擎初始化超时，自动切换到智谱云端模式
     */
    private val testTimeoutRunnable = Runnable {
        if (isEngineTesting && currentBackend == Backend.LOCAL) {
            // 本地引擎初始化超时，强制切换到智谱模式！
            Log.w(TAG, "本地语音引擎初始化超时，强制切换到智谱云端模式")
            isEngineTesting = false
            isStoppingTest = false
            
            // 直接切换到智谱云端模式
            switchToZhipuBackend()
        } else if (isEngineTesting) {
            isStoppingTest = true
            isEngineTesting = false
            isEngineReady = true
            isSetupComplete = true
            Log.d(TAG, "语音识别引擎初始化完成（测试超时通过）")
            stopTestListening()
            onReadyCallback?.invoke()
        }
    }

    /**
     * 重启任务
     * 用于持续监听模式下的自动重启
     */
    private val restartRunnable = Runnable {
        if (continuousMode && !manuallyStopped) {
            Log.d(TAG, "持续监听模式：自动重启语音识别")
            if (currentBackend == Backend.LOCAL) {
                startListeningInternal()
            } else {
                startZhipuRecording()
            }
        }
    }

    /**
     * 语音识别监听器
     * 处理各种语音识别事件和回调
     */
    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "语音识别准备就绪")
            isListening = true
            manuallyStopped = false
            listeningStartTime = SystemClock.elapsedRealtime()

            // 三级防御 - 第二级：启动静音监控Watchdog
            startSilenceWatchdog()

            if (isEngineTesting) {
                mainHandler.removeCallbacks(testTimeoutRunnable)
                mainHandler.postDelayed({
                    if (isEngineTesting) {
                        isStoppingTest = true
                        isEngineTesting = false
                        isEngineReady = true
                        isSetupComplete = true
                        Log.d(TAG, "语音识别引擎初始化完成（延迟确认）")
                        stopTestListening()
                        onReadyCallback?.invoke()
                    }
                }, 300)
                return
            }

            quickErrorRetries = 0
            onStateChangedCallback?.invoke(true)
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "检测到语音输入开始")
            // 检测到语音输入，取消静音监控
            cancelSilenceWatchdog()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // 三级防御 - 第二级补充：分贝监测
            // rmsdB通常在-2到10之间，如果持续为负数或固定值，说明录音流是空的（Standby状态）
            if (rmsdB < 0) {
                Log.w(TAG, "⚠️ 警告：麦克风输入电平异常，可能链路已断开 (rmsdB=$rmsdB)")
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "语音输入结束")
            isListening = false
            cancelSilenceWatchdog()
            onStateChangedCallback?.invoke(false)
            onEndCallback?.invoke()
        }

        override fun onError(error: Int) {
            Log.e(TAG, "原生引擎错误: $error")
            isListening = false
            onStateChangedCallback?.invoke(false)

            // 取消静音监控
            cancelSilenceWatchdog()

            // 三级防御 - 第三级：ERROR_AUDIO时立即降级到智谱云端
            if (error == SpeechRecognizer.ERROR_AUDIO && currentBackend == Backend.LOCAL) {
                Log.w(TAG, "🚨 三级防御触发：检测到音频链路错误(ERROR_AUDIO=$error)，立即降级到智谱云端")
                switchToZhipuBackend()
                return
            }

            // 三星手机核心修复点：当发生绑定错误或包缺失时触发智谱
            if ((error == SpeechRecognizer.ERROR_CLIENT || error == 10 || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) && currentBackend == Backend.LOCAL) {
                if (!isEngineTesting) {
                    switchToZhipuBackend()
                    return
                }
            }

            Log.d(TAG, "onError: error=$error, isEngineTesting=$isEngineTesting, isStoppingTest=$isStoppingTest, hasTriedFallback=$hasTriedFallback")

            if (isStoppingTest) {
                isStoppingTest = false
                Log.d(TAG, "测试停止阶段产生的错误，已忽略 (code=$error)")
                return
            }

            val isTestPhase = isEngineTesting
            isEngineTesting = false

            if (isTestPhase) {
                // 在测试阶段，不管什么错误，都标记为就绪，让用户先尝试
                Log.d(TAG, "测试阶段遇到错误(code=$error)，但仍标记为就绪，让用户尝试")
                isSetupComplete = true
                mainHandler.removeCallbacks(testTimeoutRunnable)
                isEngineReady = true
                onReadyCallback?.invoke()
                return
            }

            if (error == SpeechRecognizer.ERROR_CLIENT || error == 10) {
                if (!hasTriedFallback) {
                    hasTriedFallback = true
                    isEngineReady = false
                    isSetupComplete = false
                    mainHandler.removeCallbacks(testTimeoutRunnable)
                    Log.d(TAG, "OEM语音识别服务绑定失败，尝试回退到默认服务")
                    tryFallbackToDefaultService()
                    return
                }
                isEngineReady = false
                isSetupComplete = true
                mainHandler.removeCallbacks(testTimeoutRunnable)
                Log.w(TAG, "语音识别错误: 语音识别服务不可用 (code=$error)")
                onVoicePackMissingCallback?.invoke()
                return
            }

            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到语音，请再说一次"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "说话时间太短"
                SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "请允许麦克风权限"
                SpeechRecognizer.ERROR_NETWORK -> "网络错误，请检查网络"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                else -> "未知错误($error)"
            }
            Log.w(TAG, "语音识别错误: $errorMessage (code=$error)")

            if (isTestPhase) {
                isSetupComplete = true
                mainHandler.removeCallbacks(testTimeoutRunnable)
                isEngineReady = true
                onReadyCallback?.invoke()
                return
            }

            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                val listeningDuration = SystemClock.elapsedRealtime() - listeningStartTime
                if (listeningDuration < MIN_LISTENING_DURATION_MS && quickErrorRetries < QUICK_ERROR_MAX_RETRIES) {
                    quickErrorRetries++
                    Log.d(TAG, "监听时间过短(${listeningDuration}ms)，自动重试 (第${quickErrorRetries}次)")
                    mainHandler.postDelayed({ 
                        if (currentBackend == Backend.LOCAL) {
                            startListeningInternal()
                        } else {
                            startZhipuRecording()
                        }
                    }, 200L)
                    return
                }
                quickErrorRetries = 0

                consecutiveErrors++
                if (continuousMode && !manuallyStopped) {
                    if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
                        scheduleRestart()
                        return
                    }
                } else {
                    onErrorCallback?.invoke(errorMessage)
                    return
                }
            }

            quickErrorRetries = 0
            consecutiveErrors = 0
            onErrorCallback?.invoke(errorMessage)

            if (continuousMode && !manuallyStopped && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                scheduleRestart()
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            consecutiveErrors = 0
            quickErrorRetries = 0
            onStateChangedCallback?.invoke(false)

            if (isStoppingTest) {
                isStoppingTest = false
                Log.d(TAG, "测试停止阶段产生的结果，已忽略")
                return
            }

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotEmpty()) {
                Log.d(TAG, "语音识别结果: $text")
                onResultCallback?.invoke(text)
            } else {
                Log.d(TAG, "语音识别结果为空，通知调用方")
                onErrorCallback?.invoke("没有识别到语音，请再说一次")
                if (continuousMode && !manuallyStopped) {
                    scheduleRestart()
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotEmpty()) {
                onPartialResultCallback?.invoke(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * 切换到智谱云端识别后端
     * 当本地引擎不可用时自动调用此方法
     */
    private fun switchToZhipuBackend() {
        Log.w(TAG, "!!! 关键触发：三星/原生引擎失效，切换到智谱云端识别")
        currentBackend = Backend.ZHIPU_CLOUD
        
        // 直接标记引擎为就绪状态，不需要测试本地引擎
        isEngineReady = true
        isSetupComplete = true
        isEngineTesting = false
        isStoppingTest = false
        
        // 清理本地引擎资源
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "销毁本地语音识别引擎失败", e)
        }
        speechRecognizer = null
        
        Log.d(TAG, "已切换到智谱云端识别后端")
        onReadyCallback?.invoke()
    }
    
    /**
     * 切换到本地识别后端
     * 当智谱API余额不足时调用
     */
    private fun switchToLocalBackend() {
        Log.w(TAG, "切换到本地语音识别后端")
        currentBackend = Backend.LOCAL
        
        // 重置状态，重新初始化本地引擎
        isEngineReady = false
        isSetupComplete = false
        isEngineTesting = false
        isStoppingTest = false
        
        // 标记需要重新初始化
        hasTriedFallback = false
        
        Log.d(TAG, "已切换到本地语音识别后端")
        
        // 重新初始化本地引擎
        // 注意：我们假设setup()已经被调用过，所以直接通知重新开始
        onVoicePackMissingCallback?.invoke()
    }
    
    /**
     * 切换到DIRECT识别后端（直接发送音频+截图给AutoGLM-Phone）
     */
    fun switchToDirectBackend() {
        Log.w(TAG, "切换到DIRECT识别后端（音频+截图直接发送给AutoGLM-Phone")
        currentBackend = Backend.DIRECT
        
        // 直接标记引擎为就绪状态，不需要测试本地引擎
        isEngineReady = true
        isSetupComplete = true
        isEngineTesting = false
        isStoppingTest = false
        
        // 清理本地引擎资源
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "销毁本地语音识别引擎失败", e)
        }
        speechRecognizer = null
        
        Log.d(TAG, "已切换到DIRECT识别后端")
        onReadyCallback?.invoke()
    }

    /**
     * 检查语音识别是否可用
     */
    fun isAvailable(): Boolean {
        if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
            return true
        }
        return checkOEMRecognitionService()
    }

    /**
     * 检查OEM语音识别服务是否可用
     */
    private fun checkOEMRecognitionService(): Boolean {
        return getOEMRecognitionServiceComponent() != null
    }

    /**
     * 获取OEM语音识别服务组件
     * 根据设备厂商选择合适的语音识别服务
     */
    private fun getOEMRecognitionServiceComponent(): android.content.ComponentName? {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val oemServices = when {
            manufacturer.contains("samsung") -> listOf(
                "com.samsung.android.bixby.agent/com.samsung.android.bixby.agent.app.mainui.voiceinteraction.RecognitionServiceTrampoline",
                "com.samsung.android.intellivoiceservice/com.samsung.android.intellivoiceservice.asr.SpeechRecognizerService"
            )
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> listOf(
                "com.miui.voiceassist/com.miui.voiceassist.RecognitionService",
                "com.xiaomi.voice/com.xiaomi.voice.RecognitionService"
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
                "com.huawei.vassistant/com.huawei.vassistant.RecognitionService",
                "com.huawei.intelligent/com.huawei.intelligent.RecognitionService"
            )
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> listOf(
                "com.coloros.speechassist/com.coloros.speechassist.RecognitionService"
            )
            manufacturer.contains("vivo") -> listOf(
                "com.vivo.voice/com.vivo.voice.RecognitionService"
            )
            else -> emptyList()
        }

        for (serviceStr in oemServices) {
            val parts = serviceStr.split("/")
            if (parts.size == 2) {
                val pkg = parts[0]
                val cls = parts[1]
                try {
                    appContext.packageManager.getPackageInfo(pkg, 0)
                    Log.d(TAG, "检测到OEM语音识别服务: $serviceStr")
                    return android.content.ComponentName(pkg, cls)
                } catch (e: Exception) {
                }
            }
        }
        return null
    }

    /**
     * 检查引擎是否就绪
     */
    fun isReady(): Boolean {
        // DIRECT模式下，只要 isSetupComplete 和 isEngineReady 为 true 就算就绪
        if (currentBackend == Backend.DIRECT) {
            Log.d(TAG, "DIRECT模式就绪检查: isSetupComplete=$isSetupComplete, isEngineReady=$isEngineReady")
            return isSetupComplete && isEngineReady
        }
        // 智谱云端模式下，只要 isSetupComplete 和 isEngineReady 为 true 就算就绪
        if (currentBackend == Backend.ZHIPU_CLOUD) {
            Log.d(TAG, "智谱模式就绪检查: isSetupComplete=$isSetupComplete, isEngineReady=$isEngineReady")
            return isSetupComplete && isEngineReady
        }
        // 本地模式需要 speechRecognizer 存在
        Log.d(TAG, "本地模式就绪检查: isSetupComplete=$isSetupComplete, isEngineReady=$isEngineReady, speechRecognizer=${speechRecognizer != null}")
        return isSetupComplete && isEngineReady && speechRecognizer != null
    }

    /**
     * 设置语音识别引擎
     * @param onReady 引擎就绪回调
     * @param onResult 识别结果回调
     * @param onError 错误回调
     * @param onPartialResult 部分结果回调
     * @param onEnd 结束回调
     * @param onStateChanged 状态变化回调
     * @param onVoicePackMissing 语音包缺失回调
     * @param onAudioFileReady 音频文件准备好回调（DIRECT模式使用）
     */
    fun setup(
        onReady: (() -> Unit)? = null,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onEnd: (() -> Unit)? = null,
        onStateChanged: ((Boolean) -> Unit)? = null,
        onVoicePackMissing: (() -> Unit)? = null,
        onAudioFileReady: ((File) -> Unit)? = null,
        onMicReady: (() -> Unit)? = null
    ) {
        onReadyCallback = onReady
        onResultCallback = onResult
        onErrorCallback = onError
        onPartialResultCallback = onPartialResult
        onEndCallback = onEnd
        onStateChangedCallback = onStateChanged
        onVoicePackMissingCallback = onVoicePackMissing
        onAudioFileReadyCallback = onAudioFileReady
        onMicReadyCallback = onMicReady

        // 防止初始化测试死循环
        if (isEngineTesting) {
            Log.d(TAG, "语音识别引擎正在初始化测试中，跳过重复请求")
            return
        }

        if (isReady()) {
            Log.d(TAG, "语音识别引擎已就绪 ($currentBackend)，无需重复初始化")
            onReadyCallback?.invoke()
            return
        }

        destroy()

        hasTriedFallback = false
        val bestComponent = VoiceRecognitionScanner.getBestRecognizerComponent(appContext)
        speechRecognizer = if (bestComponent != null) {
            Log.d(TAG, "使用扫描到的语音识别服务: ${bestComponent.packageName}/${bestComponent.className}")
            SpeechRecognizer.createSpeechRecognizer(appContext, bestComponent)
        } else {
            val oemComponent = getOEMRecognitionServiceComponent()
            if (oemComponent != null) {
                Log.d(TAG, "使用OEM语音识别服务: ${oemComponent.packageName}/${oemComponent.className}")
                SpeechRecognizer.createSpeechRecognizer(appContext, oemComponent)
            } else {
                Log.d(TAG, "使用默认语音识别服务")
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }
        }
        speechRecognizer?.setRecognitionListener(recognitionListener)

        isEngineTesting = true
        isStoppingTest = false
        isEngineReady = false
        isSetupComplete = false

        val locale = if (currentLanguage == "zh-CN") {
            java.util.Locale.SIMPLIFIED_CHINESE
        } else if (currentLanguage == "zh-TW") {
            java.util.Locale.TRADITIONAL_CHINESE
        } else {
            java.util.Locale(currentLanguage)
        }
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toString())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toString())
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, locale.toString())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // 强制指定为中文，防止系统回退到英文模型
            putExtra("android.speech.extra.LANGUAGE", currentLanguage)
            putExtra("android.speech.extra.DICTATION_MODE", true)
        }

        mainHandler.postDelayed(testTimeoutRunnable, ENGINE_TEST_TIMEOUT_MS)

        try {
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "语音识别引擎测试监听已启动")
        } catch (e: Exception) {
            isEngineTesting = false
            isStoppingTest = false
            isEngineReady = false
            isSetupComplete = true
            mainHandler.removeCallbacks(testTimeoutRunnable)
            Log.e(TAG, "语音识别引擎初始化失败", e)
            onVoicePackMissingCallback?.invoke()
        }
    }

    /**
     * 停止测试监听
     */
    private fun stopTestListening() {
        try {
            if (isListening) {
                speechRecognizer?.stopListening()
                isListening = false
                Log.d(TAG, "测试监听已停止")
            } else {
                isStoppingTest = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "停止测试监听失败", e)
            isStoppingTest = false
        }
    }

    /**
     * 尝试回退到默认语音识别服务
     */
    private fun tryFallbackToDefaultService() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "销毁OEM语音识别引擎失败", e)
        }
        speechRecognizer = null

        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            Log.w(TAG, "默认语音识别服务也不可用，切换到云端")
            switchToZhipuBackend()
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        speechRecognizer?.setRecognitionListener(recognitionListener)

        isEngineTesting = true
        isStoppingTest = false
        isEngineReady = false
        isSetupComplete = false

        val locale = if (currentLanguage == "zh-CN") {
            java.util.Locale.SIMPLIFIED_CHINESE
        } else if (currentLanguage == "zh-TW") {
            java.util.Locale.TRADITIONAL_CHINESE
        } else {
            java.util.Locale(currentLanguage)
        }
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toString())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toString())
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, locale.toString())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra("android.speech.extra.LANGUAGE", currentLanguage)
            putExtra("android.speech.extra.DICTATION_MODE", true)
        }

        mainHandler.postDelayed(testTimeoutRunnable, ENGINE_TEST_TIMEOUT_MS)

        try {
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "回退到默认语音识别服务，测试监听已启动")
        } catch (e: Exception) {
            isEngineTesting = false
            isStoppingTest = false
            Log.e(TAG, "回退到默认语音识别服务失败，切换到云端", e)
            switchToZhipuBackend()
        }
    }

    /**
     * 开始监听（单次模式）
     */
    fun startListening() {
        continuousMode = false
        manuallyStopped = false
        quickErrorRetries = 0
        if (currentBackend == Backend.ZHIPU_CLOUD || currentBackend == Backend.DIRECT) {
            startZhipuRecording()
        } else {
            startListeningInternal()
        }
    }

    /**
     * 开始持续监听
     */
    fun startContinuousListening() {
        continuousMode = true
        manuallyStopped = false
        consecutiveErrors = 0
        quickErrorRetries = 0
        Log.d(TAG, "启动持续监听模式")
        if (currentBackend == Backend.ZHIPU_CLOUD || currentBackend == Backend.DIRECT) {
            startZhipuRecording()
        } else {
            startListeningInternal()
        }
    }

    /**
     * 停止监听
     * @param stopContinuous 是否停止持续监听模式
     */
    fun stopListening(stopContinuous: Boolean = true) {
        if (stopContinuous) {
            manuallyStopped = true
            continuousMode = false
            cancelScheduledRestart()
        }

        // 取消静音监控
        cancelSilenceWatchdog()

        if (currentBackend == Backend.ZHIPU_CLOUD || currentBackend == Backend.DIRECT) {
            stopZhipuAndRecognize()
        } else {
            if (isListening) {
                try {
                    speechRecognizer?.stopListening()
                } catch (e: Exception) {
                    Log.e(TAG, "停止监听失败", e)
                }
                isListening = false
                onStateChangedCallback?.invoke(false)
                Log.d(TAG, "停止语音监听")
            }
            
            // 结束语音互动管理流程
            voiceInteractionManager.endVoiceRecognition()

            // 释放ResilientMicManager
            try {
                resilientMicManager.release()
            } catch (e: Exception) {
                Log.e(TAG, "释放ResilientMicManager失败", e)
            }

            // 停止前台服务
            try {
                VoiceRecordingForegroundService.stopService(appContext)
                Log.d(TAG, "✅ 录音前台服务已停止")
            } catch (e: Exception) {
                Log.e(TAG, "停止前台服务失败", e)
            }

            // 释放AudioPathManager管理的音频资源
            try {
                audioPathManager.release()
                Log.d(TAG, "✅ AudioPathManager已释放")
            } catch (e: Exception) {
                Log.e(TAG, "释放AudioPathManager失败", e)
            }
        }
    }

    /**
     * 内部开始监听方法（本地模式）
     * 
     * 三级防御体系完整流程：
     * 第一级：ResilientMicManager.acquire() - 预热延迟（三星400ms/其他200ms）
     * 第二级：onReadyForSpeech → startSilenceWatchdog() - 3.5秒静音监控
     * 第三级：onError(ERROR_AUDIO) → switchToZhipuBackend() - 立即降级
     */
    private fun startListeningInternal() {
        if (speechRecognizer == null || !isEngineReady) {
            Log.w(TAG, "语音识别引擎未就绪，请先调用setup()")
            onErrorCallback?.invoke("语音识别引擎未就绪，请稍后再试")
            return
        }

        if (isListening) {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "停止当前监听失败", e)
            }
            isListening = false
        }

        // 0. 启动前台服务，提升进程优先级，防止被系统冻结
        try {
            VoiceRecordingForegroundService.startService(appContext)
            Log.d(TAG, "✅ 录音前台服务已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败", e)
        }

        // 1. 三级防御 - 第一级：通过ResilientMicManager获取麦克风资源（含预热延迟）
        resilientMicManager.acquire(
            onReady = {
                Log.d(TAG, "✅ 三级防御-第一级：ResilientMicManager已获取麦克风资源")
                
                // 2. 使用VoiceInteractionManager协调音频使用（同步层）
                voiceInteractionManager.startVoiceRecognition(
                    onReady = {
                        // 音频链路准备就绪，启动识别
                        CoroutineScope(Dispatchers.Main).launch {
                            val locale = if (currentLanguage == "zh-CN") {
                                java.util.Locale.SIMPLIFIED_CHINESE
                            } else if (currentLanguage == "zh-TW") {
                                java.util.Locale.TRADITIONAL_CHINESE
                            } else {
                                java.util.Locale(currentLanguage)
                            }
                            
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toString())
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toString())
                                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, locale.toString())
                                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, VAD_COMPLETE_SILENCE_MS)
                                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, VAD_POSSIBLY_COMPLETE_SILENCE_MS)
                                putExtra("android.speech.extra.LANGUAGE", currentLanguage)
                                putExtra("android.speech.extra.DICTATION_MODE", true)
                            }

                            try {
                                speechRecognizer?.startListening(intent)
                                isListening = true
                                listeningStartTime = SystemClock.elapsedRealtime()
                                onStateChangedCallback?.invoke(true)
                                Log.d(TAG, "✅ 开始本地语音监听 (持续模式=$continuousMode)")
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ 启动本地语音监听失败", e)
                                isListening = false
                                voiceInteractionManager.endVoiceRecognition()
                                resilientMicManager.release()
                                onErrorCallback?.invoke("启动语音监听失败")
                                // 本地失败，尝试切换到智谱
                                if (currentBackend == Backend.LOCAL) {
                                    switchToZhipuBackend()
                                }
                            }
                        }
                    },
                    onError = { errorMsg ->
                        // 音频链路准备失败
                        resilientMicManager.release()
                        onErrorCallback?.invoke(errorMsg)
                    }
                )
            },
            onError = { errorMsg ->
                // 三级防御 - 第一级失败：麦克风资源获取失败
                Log.e(TAG, "❌ 三级防御-第一级失败：ResilientMicManager获取麦克风失败 - $errorMsg")
                onErrorCallback?.invoke(errorMsg)
            }
        )
    }

    private var maxRecordingDurationMs = 30000L // 最大录音时长（毫秒）
    
    /**
     * 最大录音时长超时任务
     */
    private val maxRecordingRunnable = Runnable {
        if (currentBackend == Backend.ZHIPU_CLOUD && isListening) {
            Log.d(TAG, "智谱录音达到最大时长，自动停止并发送识别")
            stopZhipuAndRecognize()
        }
    }

    /**
     * 开始智谱录音（云端模式）
     * 
     * 三级防御体系完整流程：
     * 第一级：ResilientMicManager.acquire() - 预热延迟（三星400ms/其他200ms）
     * 第二级：SmartAudioRecorder - 多音源动态回退
     * 第三级：空文件拦截 - 防止1214错误
     */
    private fun startZhipuRecording() {
        Log.d(TAG, "准备启动智谱录音，当前状态: isReady()=${isReady()}, isSetupComplete=$isSetupComplete, isEngineReady=$isEngineReady, isListening=$isListening")

        // 三级防御 - 第一级：通过ResilientMicManager获取麦克风资源（含预热延迟）
        resilientMicManager.acquire(
            onReady = {
                Log.d(TAG, "✅ 三级防御-第一级：ResilientMicManager已获取麦克风资源")
                
                // 使用VoiceInteractionManager协调音频使用（同步层）
                voiceInteractionManager.startVoiceRecognition(
                    onReady = {
                        // 使用协程启动SmartAudioRecorder
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                tempAudioFile = File(appContext.cacheDir, "temp_voice_${System.currentTimeMillis()}.mp3")
                                Log.d(TAG, "临时音频文件: ${tempAudioFile?.absolutePath}")

                                // 使用SmartAudioRecorder启动录音（带多音源回退机制）
                                val success = smartAudioRecorder.startRecording(tempAudioFile!!)

                                withContext(Dispatchers.Main) {
                                    if (success) {
                                        isListening = true
                                        manuallyStopped = false
                                        listeningStartTime = SystemClock.elapsedRealtime()
                                        onStateChangedCallback?.invoke(true)
                                        onReadyCallback?.invoke()
                                        onMicReadyCallback?.invoke()
                                        mainHandler.postDelayed(maxRecordingRunnable, maxRecordingDurationMs)
                                        Log.d(TAG, "✅ 智谱云端识别录音已成功启动！")
                                    } else {
                                        Log.e(TAG, "❌ 启动智谱录音失败 - SmartAudioRecorder所有音源均失败")
                                        isListening = false
                                        voiceInteractionManager.endVoiceRecognition()
                                        resilientMicManager.release()
                                        onStateChangedCallback?.invoke(false)
                                        onErrorCallback?.invoke("麦克风启动失败，请检查是否有其他应用占用")
                                    }
                                }

                            } catch (e: Exception) {
                                Log.e(TAG, "❌ 启动智谱录音失败", e)
                                withContext(Dispatchers.Main) {
                                    isListening = false
                                    voiceInteractionManager.endVoiceRecognition()
                                    resilientMicManager.release()
                                    onStateChangedCallback?.invoke(false)
                                    onErrorCallback?.invoke("启动录音失败，请检查麦克风权限")
                                }
                            }
                        }
                    },
                    onError = { errorMsg ->
                        resilientMicManager.release()
                        onErrorCallback?.invoke(errorMsg)
                    }
                )
            },
            onError = { errorMsg ->
                // 三级防御 - 第一级失败：麦克风资源获取失败
                Log.e(TAG, "❌ 三级防御-第一级失败：ResilientMicManager获取麦克风失败 - $errorMsg")
                onErrorCallback?.invoke(errorMsg)
            }
        )
    }

    /**
     * 停止智谱录音并识别，或直接发送音频文件（DIRECT模式）
     */
    private fun stopZhipuAndRecognize() {
        mainHandler.removeCallbacks(maxRecordingRunnable)
        isListening = false
        onStateChangedCallback?.invoke(false)
        
        // 结束语音互动管理流程
        voiceInteractionManager.endVoiceRecognition()

        // 使用SmartAudioRecorder安全停止录音
        val validAudioFile = smartAudioRecorder.stopRecording()

        if (validAudioFile != null) {
            // 检查是否为DIRECT模式，直接发送音频文件
            if (currentBackend == Backend.DIRECT) {
                Log.d(TAG, "✅ DIRECT模式：直接发送音频文件给AutoGLM-Phone")
                onAudioFileReadyCallback?.invoke(validAudioFile)
            } else {
                Log.d(TAG, "✅ 录音文件有效，发送到智谱ASR")

                // 发送到智谱云端识别
                CoroutineScope(Dispatchers.IO).launch {
                    val result = zhipuService.transcribe(validAudioFile)
                    withContext(Dispatchers.Main) {
                        validAudioFile.delete()
                        when (result) {
                            is ZhipuAsrResult.Success -> {
                                Log.d(TAG, "智谱云端识别结果: ${result.text}")
                                consecutiveErrors = 0
                                quickErrorRetries = 0
                                onResultCallback?.invoke(result.text)
                            }
                            is ZhipuAsrResult.Error -> {
                                Log.w(TAG, "智谱云端识别错误: ${result.message}, 余额不足: ${result.isBalanceLow}")
                                if (result.isBalanceLow) {
                                    // 余额不足，切换到本地识别模式
                                    Log.e(TAG, "智谱API余额不足，切换到本地语音识别")
                                    switchToLocalBackend()
                                    onErrorCallback?.invoke("语音识别服务余额不足，已切换到本地识别，请再说一次")
                                } else {
                                    handleRecognitionError()
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Log.w(TAG, "⚠️ 录音文件无效（太短或空文件）")
            handleRecognitionError()
        }
    }

    /**
     * 处理识别错误
     */
    private fun handleRecognitionError() {
        if (continuousMode && !manuallyStopped) {
            quickErrorRetries++
            if (quickErrorRetries > QUICK_ERROR_MAX_RETRIES) {
                Log.e(TAG, "快速错误次数过多，尝试切换到本地识别")
                onErrorCallback?.invoke("录音异常，请稍候再试")
                quickErrorRetries = 0
            } else {
                scheduleRestart()
            }
        } else {
            onErrorCallback?.invoke("说话时间太短或麦克风异常")
        }
    }

    /**
     * 销毁引擎
     * 释放所有资源
     */
    fun destroy() {
        manuallyStopped = true
        continuousMode = false
        cancelScheduledRestart()
        cancelSilenceWatchdog()
        mainHandler.removeCallbacks(testTimeoutRunnable)
        mainHandler.removeCallbacks(maxRecordingRunnable)
        stopListening(false)

        // 清理VoiceInteractionManager
        try {
            voiceInteractionManager.cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "清理VoiceInteractionManager失败", e)
        }

        // 释放ResilientMicManager
        try {
            resilientMicManager.release()
        } catch (e: Exception) {
            Log.e(TAG, "释放ResilientMicManager失败", e)
        }

        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "销毁语音识别引擎失败", e)
        }
        speechRecognizer = null
        tempAudioFile = null
        isListening = false
        isEngineReady = false
        isEngineTesting = false
        isStoppingTest = false
        isSetupComplete = false
        // 不重置 currentBackend，保留用户在设置中选择的模式
        Log.d(TAG, "语音识别引擎已销毁，当前后端: $currentBackend")
    }

    /**
     * 重置引擎
     */
    fun resetEngine() {
        Log.d(TAG, "重置语音识别引擎")
        destroy()
    }

    /**
     * 检查当前是否正在监听
     */
    fun isCurrentlyListening(): Boolean = isListening

    /**
     * 检查是否为持续监听模式
     */
    fun isContinuousMode(): Boolean = continuousMode

    /**
     * 更新识别语言
     */
    fun updateLanguage(language: String) {
        currentLanguage = language
        Log.d(TAG, "语音识别语言已更新: $language")
    }

    /**
     * 安排重启
     */
    private fun scheduleRestart() {
        cancelScheduledRestart()
        mainHandler.postDelayed(restartRunnable, RESTART_DELAY_MS)
    }

    /**
     * 取消安排的重启
     */
    private fun cancelScheduledRestart() {
        mainHandler.removeCallbacks(restartRunnable)
    }

    /**
     * 打开语音包下载页面
     * 根据设备厂商跳转到对应的设置页面
     */
    fun openVoicePackDownloadPage() {
        try {
            val intent = when {
                isSamsungDevice() -> getSamsungVoiceSettingsIntent()
                isXiaomiDevice() -> getXiaomiVoiceSettingsIntent()
                isHuaweiDevice() -> getHuaweiVoiceSettingsIntent()
                isOppoDevice() -> getOppoVoiceSettingsIntent()
                isVivoDevice() -> getVivoVoiceSettingsIntent()
                else -> Intent(Settings.ACTION_SETTINGS)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开设置页面失败", e)
            try {
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "打开通用设置页面也失败", e2)
            }
        }
    }

    /**
     * 检查是否为三星设备
     */
    private fun isSamsungDevice(): Boolean {
        return android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }

    /**
     * 检查是否为小米设备
     */
    private fun isXiaomiDevice(): Boolean {
        return android.os.Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ||
                android.os.Build.BRAND.equals("xiaomi", ignoreCase = true)
    }

    /**
     * 检查是否为华为设备
     */
    private fun isHuaweiDevice(): Boolean {
        return android.os.Build.MANUFACTURER.equals("huawei", ignoreCase = true) ||
                android.os.Build.MANUFACTURER.equals("honor", ignoreCase = true)
    }

    /**
     * 检查是否为Oppo设备
     */
    private fun isOppoDevice(): Boolean {
        return android.os.Build.MANUFACTURER.equals("oppo", ignoreCase = true) ||
                android.os.Build.MANUFACTURER.equals("realme", ignoreCase = true)
    }

    /**
     * 检查是否为Vivo设备
     */
    private fun isVivoDevice(): Boolean {
        return android.os.Build.MANUFACTURER.equals("vivo", ignoreCase = true)
    }

    /**
     * 获取三星语音设置Intent
     */
    private fun getSamsungVoiceSettingsIntent(): Intent {
        val intents = listOf(
            Intent().apply {
                setClassName(
                    "com.android.settings",
                    "com.android.settings.Settings\$SmartInputSettingsActivity"
                )
            },
            Intent().apply {
                setClassName(
                    "com.samsung.android.settings",
                    "com.samsung.android.settings.languageandinput.LanguageAndInputSettingsActivity"
                )
            },
            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        )
        for (intent in intents) {
            try {
                if (appContext.packageManager.resolveActivity(intent, 0) != null) {
                    return intent
                }
            } catch (_: Exception) {}
        }
        return Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
    }

    /**
     * 获取小米语音设置Intent
     */
    private fun getXiaomiVoiceSettingsIntent(): Intent {
        return Intent().apply {
            setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$LanguageAndInputSettingsActivity"
            )
        }
    }

    /**
     * 获取华为语音设置Intent
     */
    private fun getHuaweiVoiceSettingsIntent(): Intent {
        return Intent().apply {
            setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$LanguageAndInputSettingsActivity"
            )
        }
    }

    /**
     * 获取Oppo语音设置Intent
     */
    private fun getOppoVoiceSettingsIntent(): Intent {
        return Intent().apply {
            setClassName(
                "com.coloros.speechassist",
                "com.coloros.speechassist.MainActivity"
            )
        }
    }

    /**
     * 获取Vivo语音设置Intent
     */
    private fun getVivoVoiceSettingsIntent(): Intent {
        return Intent().apply {
            setClassName(
                "com.vivo.voice",
                "com.vivo.voice.main.MainActivity"
            )
        }
    }

    /**
     * 重新获取音频焦点
     */
    private fun reAcquireAudioFocus() {
        try {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            Log.d(TAG, "尝试重新获取音频焦点...")

            // 先放弃之前的焦点
            if (currentAudioFocusRequest != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.abandonAudioFocusRequest(currentAudioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }

            // 重新设置通信模式
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // 重新创建焦点请求
            currentAudioFocusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
            } else {
                null
            }

            // 重新请求焦点
            val focusResult = if (currentAudioFocusRequest != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.requestAudioFocus(currentAudioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }

            Log.d(TAG, "重新获取音频焦点结果: $focusResult")

            if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.d(TAG, "成功重新获取音频焦点")
            } else {
                Log.e(TAG, "无法重新获取音频焦点")
                onErrorCallback?.invoke("录音被系统中断，请重新尝试")
            }
        } catch (e: Exception) {
            Log.e(TAG, "重新获取音频焦点失败", e)
        }
    }

    // ========== 三级防御 - 第二级：静音监控Watchdog ==========

    private var silenceWatchdogRunnable: Runnable? = null

    /**
     * 启动静音监控Watchdog
     * 如果3.5秒内没有检测到语音输入（onBeginningOfSpeech），判定为硬件故障
     * 自动降级到智谱云端识别
     */
    private fun startSilenceWatchdog() {
        cancelSilenceWatchdog()
        silenceWatchdogRunnable = Runnable {
            if (isListening && currentBackend == Backend.LOCAL) {
                Log.w(TAG, "🚨 三级防御 - 静音监控触发：${SILENCE_WATCHDOG_TIMEOUT}ms内未检测到语音输入，判定为硬件故障")
                Log.w(TAG, "可能原因：AHAL底层断流、Bixby抢占、PAL强制Standby")
                // 停止当前监听
                try {
                    speechRecognizer?.stopListening()
                } catch (e: Exception) {
                    Log.e(TAG, "停止监听失败", e)
                }
                isListening = false
                onStateChangedCallback?.invoke(false)
                // 立即降级到智谱云端
                switchToZhipuBackend()
            }
        }
        mainHandler.postDelayed(silenceWatchdogRunnable!!, SILENCE_WATCHDOG_TIMEOUT)
        Log.d(TAG, "静音监控Watchdog已启动 (${SILENCE_WATCHDOG_TIMEOUT}ms)")
    }

    /**
     * 取消静音监控Watchdog
     */
    private fun cancelSilenceWatchdog() {
        silenceWatchdogRunnable?.let {
            mainHandler.removeCallbacks(it)
            silenceWatchdogRunnable = null
        }
    }
}
