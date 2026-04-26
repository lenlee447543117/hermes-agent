package com.ailaohu.ui.home

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.ailaohu.data.local.prefs.AppPreferences
import com.ailaohu.service.accessibility.AutoPilotService
import com.ailaohu.service.audio.AudioManagerHelper
import com.ailaohu.service.capture.ScreenCaptureManager
import com.ailaohu.service.face.FaceDetectionManager
import com.ailaohu.service.tts.TTSManager
import com.ailaohu.service.voice.CommandNormalizer
import com.ailaohu.service.voice.MultiStepTaskHandler
import com.ailaohu.service.voice.VoiceFeedbackPlayer
import com.ailaohu.service.voice.VoiceFloatingService
import com.ailaohu.service.voice.VoicePipelineState
import com.ailaohu.service.voice.VoiceRecognitionEngine
import com.ailaohu.service.voice.VoiceStateMachine
import com.ailaohu.service.voice.WakeUpService
import com.ailaohu.ui.setup.PermissionGuideActivity
import com.ailaohu.ui.settings.SettingsActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class HomeActivity : ComponentActivity(), FaceDetectionManager.FaceDetectionCallback {

    companion object {
        private const val TAG = "HomeActivity"
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1001
    }

    private var isRequestingMicPermission = false

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var ttsManager: TTSManager
    @Inject lateinit var voiceRecognitionEngine: VoiceRecognitionEngine
    @Inject lateinit var faceDetectionManager: FaceDetectionManager
    @Inject lateinit var audioManagerHelper: AudioManagerHelper
    @Inject lateinit var voiceStateMachine: VoiceStateMachine
    @Inject lateinit var commandNormalizer: CommandNormalizer
    @Inject lateinit var screenCaptureManager: ScreenCaptureManager
    @Inject lateinit var voiceCommandBridge: com.ailaohu.service.voice.VoiceCommandBridge
    @Inject lateinit var voiceFeedbackPlayer: VoiceFeedbackPlayer
    @Inject lateinit var multiStepTaskHandler: MultiStepTaskHandler

    private lateinit var viewModel: HomeViewModel
    private var hasGreeted = false
    private var isUIInitialized = false
    private var isVoiceEngineInitialized = false
    private var isTryingToStartListening = false
    private var isFloatingButtonShown = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        isRequestingMicPermission = false

        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true

        if (micGranted) {
            Log.d(TAG, "麦克风权限已授予")
            val allGranted = permissions.all { it.value }
            if (allGranted) {
                initVoiceEngine()
                initFaceDetection()
            }

            if (isTryingToStartListening) {
                isTryingToStartListening = false
                lifecycleScope.launch {
                    delay(300)
                    startListening()
                }
            }
        } else {
            Log.w(TAG, "麦克风权限被拒绝")
            isTryingToStartListening = false

            val shouldShowRationale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            } else {
                false
            }

            if (shouldShowRationale) {
                ttsManager.speak("需要麦克风权限才能使用语音功能，请点击设置允许麦克风权限")
                Toast.makeText(this, "需要麦克风权限才能使用语音功能", Toast.LENGTH_LONG).show()
            } else {
                ttsManager.speak("麦克风权限已被禁用，请在设置中开启")
                Toast.makeText(this, "麦克风权限已被禁用，请在设置中开启", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[HomeViewModel::class.java]

        lifecycleScope.launch {
            commandNormalizer.preloadContacts()
        }

        voiceFeedbackPlayer.initialize()
        setupFloatingButtonCallbacks()
        observePipelineState()
        observeFloatingState()
        handleWakeUpIntent(intent)
        handleDebugCommand(intent)
        checkAndNavigate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWakeUpIntent(intent)
        handleDebugCommand(intent)
    }

    private fun setupFloatingButtonCallbacks() {
        VoiceFloatingService.onMicToggle = {
            runOnUiThread {
                toggleVoiceRecognition()
            }
        }

        VoiceFloatingService.onCloseRequested = {
            isFloatingButtonShown = false
        }
    }

    private fun observeFloatingState() {
        lifecycleScope.launch {
            voiceStateMachine.pipelineState.collect { state ->
                VoiceFloatingService.updateState(state, viewModel.currentPartialText)
            }
        }

        lifecycleScope.launch {
            multiStepTaskHandler.taskState.collect { taskState ->
                if (taskState.isActive) {
                    VoiceFloatingService.updateState(
                        VoicePipelineState.EXECUTING,
                        "步骤${taskState.currentStepIndex + 1}/${taskState.steps.size}"
                    )
                }
            }
        }
    }

    private fun handleDebugCommand(intent: Intent?) {
        val debugCommand = intent?.getStringExtra("debug_command")
        if (!debugCommand.isNullOrEmpty()) {
            Log.d(TAG, "=== 收到调试命令: '$debugCommand' ===")
            lifecycleScope.launch {
                if (voiceRecognitionEngine.isCurrentlyListening()) {
                    voiceRecognitionEngine.stopListening(true)
                    delay(500)
                }
                if (ttsManager.isCurrentlySpeaking) {
                    ttsManager.stop()
                    delay(300)
                }
                if (isUIInitialized) {
                    viewModel.onVoiceRecognized(debugCommand)
                } else {
                    delay(2000)
                    viewModel.onVoiceRecognized(debugCommand)
                }
            }
        }
    }

    private fun handleWakeUpIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("auto_start_listening", false) == true) {
            val wakeWord = intent.getStringExtra("wake_word") ?: ""
            Log.d(TAG, "收到唤醒意图，唤醒词: $wakeWord")
            lifecycleScope.launch {
                delay(800)
                if (isVoiceEngineInitialized) {
                    ttsManager.speak("我在，请说")
                    delay(1500)
                    startListening()
                } else {
                    isTryingToStartListening = true
                }
            }
        }
    }

    private fun observePipelineState() {
        lifecycleScope.launch {
            voiceStateMachine.pipelineState.collect { state ->
                runOnUiThread {
                    when (state) {
                        VoicePipelineState.IDLE -> {
                            viewModel.onVoiceStateChanged(VoiceState.IDLE)
                        }
                        VoicePipelineState.LISTENING -> {
                            viewModel.onVoiceStateChanged(VoiceState.LISTENING)
                            voiceFeedbackPlayer.playStartTone()
                        }
                        VoicePipelineState.PROCESSING -> {
                            viewModel.onVoiceStateChanged(VoiceState.PROCESSING)
                        }
                        VoicePipelineState.SPEAKING -> {
                            viewModel.onVoiceStateChanged(VoiceState.SPEAKING)
                        }
                        VoicePipelineState.EXECUTING -> {
                            viewModel.onVoiceStateChanged(VoiceState.PROCESSING)
                        }
                    }
                    VoiceFloatingService.updateState(state)
                }
            }
        }
    }

    private fun checkAndNavigate() {
        lifecycleScope.launch {
            checkPermissionGuideStatus()
        }
    }

    private fun checkPermissionGuideStatus() {
        lifecycleScope.launch {
            if (isFinishing) return@launch

            val hasGuidedBefore = appPreferences.isPermissionGuided.first()
            val isCoreServiceLost = !isAccessibilityServiceEnabled()

            if (!hasGuidedBefore || isCoreServiceLost) {
                startActivity(Intent(this@HomeActivity, PermissionGuideActivity::class.java))
            } else {
                setupUI()
                requestMissingPermissions()
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val serviceName = ComponentName(this, AutoPilotService::class.java)
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabledServices.contains(serviceName.flattenToString())
        } catch (e: Exception) {
            Log.e(TAG, "检查无障碍服务状态失败", e)
            false
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkServiceStatus()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }

        if (isUIInitialized && !isVoiceEngineInitialized) {
            initVoiceEngine()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::faceDetectionManager.isInitialized) {
            faceDetectionManager.stop()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() 开始清理资源")

        if (::faceDetectionManager.isInitialized) {
            try { faceDetectionManager.stop() } catch (_: Exception) {}
        }

        try {
            if (voiceRecognitionEngine.isCurrentlyListening()) {
                voiceRecognitionEngine.stopListening(true)
            }
            voiceRecognitionEngine.destroy()
        } catch (_: Exception) {}

        voiceStateMachine.reset()
        voiceFeedbackPlayer.release()

        VoiceFloatingService.onMicToggle = null
        VoiceFloatingService.onCloseRequested = null
        if (isFloatingButtonShown) {
            VoiceFloatingService.hide(this)
            isFloatingButtonShown = false
        }

        isVoiceEngineInitialized = false
        isTryingToStartListening = false
        isRequestingMicPermission = false

        super.onDestroy()
    }

    private fun setupUI() {
        if (isUIInitialized) return
        isUIInitialized = true

        ttsManager.initialize()

        lifecycleScope.launch {
            voiceRecognitionEngine.setBackend(VoiceRecognitionEngine.Backend.LOCAL)
            voiceRecognitionEngine.destroy()
            isVoiceEngineInitialized = false

            if (!voiceRecognitionEngine.isReady()) {
                initVoiceEngine()
            }
        }

        lifecycleScope.launch {
            viewModel.restartListeningEvent.collect {
                Log.d(TAG, "收到重新开麦事件，自动开始录音")
                doStartListening()
            }
        }

        setContent {
            val uiState = viewModel.uiState.collectAsState().value

            HomeScreen(
                uiState = uiState,
                onMicClick = { onEnterChatClicked() },
                onSettingsClick = {
                    startActivity(Intent(this@HomeActivity, SettingsActivity::class.java))
                },
                onNotificationsClick = {
                    voiceStateMachine.speakAndWait("暂无新通知")
                },
                onMeClick = {
                    voiceStateMachine.speakAndWait("个人中心")
                }
            )
        }

        if (!hasGreeted) {
            hasGreeted = true
            lifecycleScope.launch {
                delay(1500)
                voiceStateMachine.speakAndWait("沪老助手已就绪，您可以看着我说你好，也可以点击进入对话按钮开始使用")
            }
        }
    }

    private fun onEnterChatClicked() {
        Log.d(TAG, "进入对话按钮被点击")

        if (!isFloatingButtonShown) {
            if (checkOverlayPermission()) {
                showFloatingButton()
                startListening()
            } else {
                requestOverlayPermission()
            }
        } else {
            toggleVoiceRecognition()
        }
    }

    private fun showFloatingButton() {
        VoiceFloatingService.show(this)
        isFloatingButtonShown = true
        Log.d(TAG, "悬浮语音按钮已显示")
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
            ttsManager.speak("请允许显示悬浮窗，这样我就能随时听您说话了")
        }
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (checkOverlayPermission()) {
                showFloatingButton()
                startListening()
            } else {
                ttsManager.speak("悬浮窗权限未获取，您仍可以在应用内使用语音功能")
                startListening()
            }
        }
    }

    private fun requestMissingPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_CONTACTS)
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            initVoiceEngine()
            initFaceDetection()
        }
    }

    private fun initFaceDetection() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        faceDetectionManager.setCallback(this)
        faceDetectionManager.start()
    }

    override fun onFaceDetected() {
        runOnUiThread {
            val pipelineState = voiceStateMachine.currentState()
            if (!voiceRecognitionEngine.isCurrentlyListening() &&
                pipelineState == VoicePipelineState.IDLE) {
                Toast.makeText(this, "检测到人脸，正在打开麦克风...", Toast.LENGTH_SHORT).show()
                startListening()
            }
        }
    }

    override fun onFaceLost() {}

    private fun initVoiceEngine() {
        if (voiceRecognitionEngine.isReady()) {
            isVoiceEngineInitialized = true
            return
        }

        voiceRecognitionEngine.setup(
            onReady = {
                isVoiceEngineInitialized = true
                Log.d(TAG, "语音识别准备就绪")
            },
            onResult = { text ->
                Log.d(TAG, "语音识别结果: $text")
                runOnUiThread {
                    voiceFeedbackPlayer.playEndTone()
                    val normalized = commandNormalizer.normalize(text)
                    voiceStateMachine.transitionTo(VoicePipelineState.PROCESSING, normalized)
                    viewModel.onVoiceRecognized(normalized)

                    val steps = multiStepTaskHandler.decomposeTask(normalized)
                    if (steps != null) {
                        multiStepTaskHandler.startMultiStepTask(normalized, steps) { stepIndex, stepDesc ->
                            Log.d(TAG, "执行步骤$stepIndex: $stepDesc")
                        }
                    }
                }
            },
            onError = { error ->
                Log.w(TAG, "语音识别错误: $error")
                runOnUiThread {
                    voiceFeedbackPlayer.playErrorTone()
                    voiceStateMachine.transitionTo(VoicePipelineState.IDLE, "error")
                    if (error.contains("权限")) {
                        voiceStateMachine.speakThenListen("请允许麦克风权限") {
                            doStartListening()
                        }
                    } else if (!error.contains("No match") && !error.contains("no speech")) {
                        voiceStateMachine.speakThenListen("没听清，请再说一次") {
                            doStartListening()
                        }
                    } else {
                        doStartListening()
                    }
                }
            },
            onPartialResult = { text ->
                runOnUiThread {
                    viewModel.onPartialVoiceResult(text)
                    VoiceFloatingService.updateState(VoicePipelineState.LISTENING, text)
                }
            },
            onEnd = {
                runOnUiThread {
                    voiceStateMachine.transitionTo(VoicePipelineState.PROCESSING, "listen_end")
                }
            },
            onVoicePackMissing = {
                runOnUiThread {
                    voiceStateMachine.transitionTo(VoicePipelineState.IDLE, "voice_pack_missing")
                    if (voiceRecognitionEngine.currentBackend == VoiceRecognitionEngine.Backend.ZHIPU_CLOUD) {
                        voiceStateMachine.speakThenListen("已为您开启云端增强模式，请说") {
                            doStartListening()
                        }
                    } else {
                        voiceStateMachine.speakAndWait(getVoicePackDownloadGuide())
                        voiceRecognitionEngine.openVoicePackDownloadPage()
                    }
                }
            }
        )
    }

    private fun toggleVoiceRecognition() {
        if (voiceRecognitionEngine.isCurrentlyListening()) {
            stopListening()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            isTryingToStartListening = true
            isRequestingMicPermission = true
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }

        if (!voiceRecognitionEngine.isReady()) {
            initVoiceEngine()
            lifecycleScope.launch {
                var attempts = 0
                while (attempts < 10 && !voiceRecognitionEngine.isReady() && !isFinishing) {
                    delay(100)
                    attempts++
                }
                if (voiceRecognitionEngine.isReady()) {
                    doStartListening()
                } else {
                    voiceStateMachine.speakAndWait("语音识别引擎正在初始化，请稍后再试")
                }
            }
            return
        }

        doStartListening()
    }

    private fun doStartListening() {
        voiceStateMachine.requestListen {
            voiceStateMachine.transitionTo(VoicePipelineState.LISTENING, "manual")

            if (ttsManager.isCurrentlySpeaking) {
                ttsManager.stop()
            }

            try {
                voiceRecognitionEngine.startContinuousListening()
                Log.d(TAG, "语音识别引擎已启动（连续模式）")
            } catch (e: Exception) {
                Log.e(TAG, "启动语音识别失败", e)
                voiceStateMachine.speakAndWait("启动语音识别失败，请稍后再试")
            }
        }
    }

    private fun stopListening() {
        try {
            voiceRecognitionEngine.stopListening(true)
            voiceFeedbackPlayer.playEndTone()
            voiceStateMachine.transitionTo(VoicePipelineState.PROCESSING, "manual_stop")
        } catch (e: Exception) {
            Log.e(TAG, "停止录音失败", e)
        }
    }

    private fun getVoicePackDownloadGuide(): String {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("samsung") ->
                "检测到语音包未安装，现在为您打开设置，请依次点击'常规管理'、'语言与输入法'、'语音输入'，然后下载并安装中文语音包"
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
                "检测到语音包未安装，现在为您打开设置，请依次点击'更多设置'、'语言与输入法'、'语音'，然后下载中文语音包"
            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                "检测到语音包未安装，现在为您打开设置，请依次点击'系统和更新'、'语言与输入法'、'语音'，然后下载中文语音包"
            manufacturer.contains("oppo") || manufacturer.contains("realme") ->
                "检测到语音包未安装，现在为您打开设置，请点击'语音助手'，然后下载中文语音包"
            manufacturer.contains("vivo") ->
                "检测到语音包未安装，现在为您打开设置，请点击'语音助手'，然后下载中文语音包"
            else ->
                "检测到语音包未安装，现在为您打开设置，请依次点击'系统'、'语言与输入法'、'语音输入'，然后下载中文语音包"
        }
    }
}
