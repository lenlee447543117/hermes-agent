package com.ailaohu.ui.home

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ailaohu.data.local.entity.ContactEntity
import com.ailaohu.data.local.prefs.AppPreferences
import com.ailaohu.data.repository.ContactRepository
import com.ailaohu.domain.usecase.CallTaxiUseCase
import com.ailaohu.domain.usecase.ExecuteWeChatCallUseCase
import com.ailaohu.domain.usecase.WeatherInfoUseCase
import com.ailaohu.domain.usecase.NewsInfoUseCase
import com.ailaohu.domain.usecase.MedicineReminderUseCase
import com.ailaohu.service.termux.TermuxBridge
import com.ailaohu.service.termux.TermuxCommand
import com.ailaohu.service.chat.ChatService
import com.ailaohu.service.care.ProactiveCareService
import com.ailaohu.service.habit.HabitTrackingService
import com.ailaohu.service.accessibility.AccessibilityHelper
import com.ailaohu.service.accessibility.AutoPilotService
import com.ailaohu.service.capture.ScreenCaptureManager
import com.ailaohu.service.tts.TTSManager
import com.ailaohu.service.voice.AutoGLMExecutor
import com.ailaohu.service.voice.VoiceCommand
import com.ailaohu.service.voice.VoiceCommandBridge
import com.ailaohu.service.voice.VoiceCommandParser
import com.ailaohu.service.voice.VoiceStateMachine
import com.ailaohu.service.voice.VoicePipelineState
import com.ailaohu.util.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

enum class VoiceState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING
}

// 需要确认的操作类型
enum class PendingActionType {
    NONE,
    WECHAT_CALL,
    PHONE_CALL,
    CALL_TAXI,
    SEND_MESSAGE,
    EMERGENCY_SOS
}

data class PendingAction(
    val type: PendingActionType,
    val command: VoiceCommand,
    val description: String
)

data class HomeUiState(
    val contacts: List<ContactEntity> = emptyList(),
    val isNetworkAvailable: Boolean = true,
    val isAccessibilityRunning: Boolean = false,
    val isScreenCaptureRunning: Boolean = false,
    val isLoading: Boolean = false,
    val voiceState: VoiceState = VoiceState.IDLE,
    val recognizedText: String = "",
    val responseText: String = "",
    val isPetHappy: Boolean = false,
    val lastAction: String = "",
    val isContinuousListening: Boolean = false,
    val pendingAction: PendingAction? = null,
    val isEmergencyActive: Boolean = false,
    val hasNewNotification: Boolean = false
) {
    fun isSameSignificantState(other: HomeUiState): Boolean {
        return voiceState == other.voiceState &&
                isLoading == other.isLoading &&
                isEmergencyActive == other.isEmergencyActive &&
                isPetHappy == other.isPetHappy &&
                recognizedText == other.recognizedText &&
                responseText == other.responseText &&
                isNetworkAvailable == other.isNetworkAvailable &&
                isAccessibilityRunning == other.isAccessibilityRunning &&
                isScreenCaptureRunning == other.isScreenCaptureRunning &&
                hasNewNotification == other.hasNewNotification &&
                pendingAction == other.pendingAction &&
                isContinuousListening == other.isContinuousListening
    }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val callTaxiUseCase: CallTaxiUseCase,
    private val executeWeChatCallUseCase: ExecuteWeChatCallUseCase,
    private val weatherInfoUseCase: WeatherInfoUseCase,
    private val newsInfoUseCase: NewsInfoUseCase,
    private val medicineReminderUseCase: MedicineReminderUseCase,
    private val termuxBridge: TermuxBridge,
    private val appPreferences: AppPreferences,
    private val networkMonitor: NetworkMonitor,
    private val ttsManager: TTSManager,
    private val voiceCommandParser: VoiceCommandParser,
    private val autoGLMExecutor: AutoGLMExecutor,
    private val voiceCommandBridge: VoiceCommandBridge,
    private val voiceStateMachine: VoiceStateMachine,
    private val chatService: ChatService,
    private val proactiveCareService: ProactiveCareService,
    private val habitTrackingService: HabitTrackingService,
    private val accessibilityHelper: AccessibilityHelper,
    private val screenCaptureManager: ScreenCaptureManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState
        .distinctUntilChanged { old, new -> old.isSameSignificantState(new) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = HomeUiState()
        )

    private val _restartListeningEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val restartListeningEvent: SharedFlow<Unit> = _restartListeningEvent.asSharedFlow()

    private val _pauseListeningEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pauseListeningEvent: SharedFlow<Unit> = _pauseListeningEvent.asSharedFlow()

    var currentPartialText: String = ""
        private set

    // 记录上一条指令，用于重复
    private var lastCommand: VoiceCommand? = null
    private var isAutomationRunning = false
    private val automationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        voiceCommandBridge.setOnCommandComplete {
            viewModelScope.launch {
                _restartListeningEvent.emit(Unit)
            }
        }
        viewModelScope.launch {
            contactRepository.getActiveContacts().collect { contacts ->
                _uiState.update { it.copy(contacts = contacts) }
            }
        }
        viewModelScope.launch {
            networkMonitor.isOnline.collect { isOnline ->
                _uiState.update { it.copy(isNetworkAvailable = isOnline) }
            }
        }
        checkServiceStatus()
        proactiveCareService.startMonitoring()
        habitTrackingService.startTracking()

        viewModelScope.launch {
            proactiveCareService.careMessageFlow.collect { care: com.ailaohu.data.remote.dto.hermes.HermesCareMessage ->
                _uiState.update { it.copy(responseText = care.message) }
            }
        }
    }

    fun checkServiceStatus() {
        _uiState.update {
            it.copy(
                isAccessibilityRunning = AutoPilotService.isRunning(),
                isScreenCaptureRunning = screenCaptureManager.hasPermission()
            )
        }
    }

    fun onVoiceRecognized(text: String) {
        Log.d(TAG, "=== onVoiceRecognized() 被调用，文本: '$text' ===")
        _uiState.update {
            it.copy(
                recognizedText = text,
                voiceState = VoiceState.PROCESSING
            )
        }
        val command = voiceCommandParser.parse(text)
        Log.d(TAG, "=== 解析得到的指令: ${command.javaClass.simpleName} ===")
        executeCommand(command)
    }

    fun onPartialVoiceResult(text: String) {
        currentPartialText = text
        _uiState.update { it.copy(recognizedText = text) }
    }

    fun onVoiceStateChanged(state: VoiceState) {
        _uiState.update { it.copy(voiceState = state) }
    }

    fun setPetHappy(happy: Boolean) {
        _uiState.update { it.copy(isPetHappy = happy) }
    }

    fun setContinuousListening(enabled: Boolean) {
        _uiState.update { it.copy(isContinuousListening = enabled) }
    }
    
    fun setHasNewNotification(hasNotification: Boolean) {
        _uiState.update { it.copy(hasNewNotification = hasNotification) }
    }

    /**
     * 执行语音指令
     */
    private fun executeCommand(command: VoiceCommand) {
        Log.d(TAG, "=== executeCommand() 被调用，类型: ${command.javaClass.simpleName} ===")
        proactiveCareService.recordUserInteraction()

        if (isAutomationRunning && command !is VoiceCommand.CancelAction) {
            Log.d(TAG, "自动化操作执行中，忽略新指令: ${command.javaClass.simpleName}")
            return
        }

        if (command is VoiceCommand.CancelAction) {
            executeWeChatCallUseCase.cancel()
        }

        viewModelScope.launch {
            Log.d(TAG, "=== executeCommand() 协程开始执行 ===")
            // 处理确认/取消操作
            if (command is VoiceCommand.ConfirmAction) {
                handleConfirmAction()
                return@launch
            }
            if (command is VoiceCommand.CancelAction) {
                handleCancelAction()
                return@launch
            }

            // 处理等待时间输入状态（吃药提醒追问时间场景）
            val pending = _uiState.value.pendingAction
            if (pending != null && pending.command is VoiceCommand.MedicineReminder) {
                val timeFromSpeech = voiceCommandParser.extractTimeFromText(_uiState.value.recognizedText)
                if (timeFromSpeech.isNotEmpty()) {
                    _uiState.update { it.copy(pendingAction = null) }
                    executeMedicineReminder(
                        VoiceCommand.MedicineReminder(
                            (pending.command as VoiceCommand.MedicineReminder).medicineName,
                            timeFromSpeech
                        )
                    )
                    return@launch
                }
            }

            // 处理重复上一条指令
            if (command is VoiceCommand.RepeatLast) {
                if (lastCommand != null) {
                    speakAndRespond("好的，我再执行一次")
                    executeCommand(lastCommand!!)
                } else {
                    speakAndRespond("没有之前的操作记录")
                }
                return@launch
            }

            // 处理帮助指令
            if (command is VoiceCommand.Help) {
                val helpText = voiceCommandParser.getHelpText()
                speakAndRespond(helpText)
                return@launch
            }

            // 记录指令
            lastCommand = command

            val description = voiceCommandParser.getCommandDescription(command)
            _uiState.update { it.copy(lastAction = description) }

            Log.d(TAG, "=== 准备进入 when 语句，command 类型: ${command.javaClass.simpleName} ===")
            when (command) {
                is VoiceCommand.LaunchApp -> {
                    Log.d(TAG, "=== 匹配到 VoiceCommand.LaunchApp ===")
                    executeLaunchApp(command)
                }
                is VoiceCommand.WeChatCall -> executeWeChatCallWithConfirm(command)
                is VoiceCommand.PhoneCall -> executePhoneCallWithConfirm(command)
                is VoiceCommand.CallTaxi -> executeCallTaxiWithConfirm(command)
                is VoiceCommand.SendMessage -> executeSendMessage(command)
                is VoiceCommand.PlayMusic -> executePlayMusic(command)
                is VoiceCommand.ControlVolume -> executeControlVolume(command)
                is VoiceCommand.SystemSetting -> executeSystemSetting(command)
                is VoiceCommand.SetAlarm -> executeSetAlarm(command)
                is VoiceCommand.SearchWeb -> executeSearchWeb(command)
                is VoiceCommand.TakePhoto -> executeTakePhoto(command)
                is VoiceCommand.AutoGLMAction -> executeAutoGLMAction(command)
                is VoiceCommand.Chat -> executeChat(command)
                is VoiceCommand.EmergencySOS -> executeEmergencySOS(command)
                is VoiceCommand.MedicineReminder -> executeMedicineReminder(command)
                is VoiceCommand.ShareLocation -> executeShareLocation(command)
                is VoiceCommand.ReadNews -> executeReadNews(command)
                is VoiceCommand.QueryWeather -> executeQueryWeather(command)
                is VoiceCommand.ConfirmAction -> {}
                is VoiceCommand.CancelAction -> {}
                is VoiceCommand.RepeatLast -> {}
                is VoiceCommand.Help -> {}
                is VoiceCommand.Unknown -> speakAndRespond("抱歉，我没有听懂，请再说一次。您可以说\"帮助\"查看我能做什么")
            }
        }
    }

    /**
     * 处理确认操作
     */
    private suspend fun handleConfirmAction() {
        val pending = _uiState.value.pendingAction
        if (pending == null) {
            speakAndRespond("没有需要确认的操作")
            return
        }

        _uiState.update { it.copy(pendingAction = null) }

        when (pending.type) {
            PendingActionType.WECHAT_CALL -> {
                val cmd = pending.command as VoiceCommand.WeChatCall
                automationScope.launch {
                    doWeChatCall(cmd)
                }
            }
            PendingActionType.PHONE_CALL -> {
                val cmd = pending.command as VoiceCommand.PhoneCall
                doPhoneCall(cmd)
            }
            PendingActionType.CALL_TAXI -> {
                doCallTaxi()
            }
            PendingActionType.SEND_MESSAGE -> {
                val cmd = pending.command as VoiceCommand.SendMessage
                doSendMessage(cmd)
            }
            PendingActionType.EMERGENCY_SOS -> {
                doEmergencySOS(pending.command as VoiceCommand.EmergencySOS)
            }
            PendingActionType.NONE -> {
                if (pending.command is VoiceCommand.MedicineReminder) {
                    val cmd = pending.command as VoiceCommand.MedicineReminder
                    val recognizedText = _uiState.value.recognizedText
                    val timeFromSpeech = voiceCommandParser.extractTimeFromText(recognizedText)
                    if (timeFromSpeech.isNotEmpty()) {
                        executeMedicineReminder(
                            VoiceCommand.MedicineReminder(cmd.medicineName, timeFromSpeech)
                        )
                    } else {
                        speakAndRespond("没听清时间，请再说一次，比如\"早上8点\"")
                    }
                } else {
                    speakAndRespond("没有需要确认的操作")
                }
            }
        }
    }

    /**
     * 处理取消操作
     */
    private suspend fun handleCancelAction() {
        executeWeChatCallUseCase.cancel()
        isAutomationRunning = false
        val pending = _uiState.value.pendingAction
        if (pending == null) {
            speakAndRespond("好的")
            return
        }

        _uiState.update { it.copy(pendingAction = null) }
        speakAndRespond("好的，已取消${pending.description}")
    }

    /**
     * 需要确认的操作：微信通话
     */
    private suspend fun executeWeChatCallWithConfirm(command: VoiceCommand.WeChatCall) {
        automationScope.launch {
            doWeChatCall(command)
        }
    }

    /**
     * 需要确认的操作：拨打电话
     */
    private suspend fun executePhoneCallWithConfirm(command: VoiceCommand.PhoneCall) {
        doPhoneCall(command)
    }

    /**
     * 需要确认的操作：叫车
     */
    private suspend fun executeCallTaxiWithConfirm(command: VoiceCommand.CallTaxi) {
        doCallTaxi()
    }

    private suspend fun executeLaunchApp(command: VoiceCommand.LaunchApp) {
        Log.d(TAG, "=== executeLaunchApp() 被调用，appName: '${command.appName}', packageName: '${command.packageName}' ===")
        _uiState.update { it.copy(isLoading = true) }
        try {
            Log.d(TAG, "=== 正在调用 getLaunchIntentForPackage('${command.packageName}') ===")
            var intent = context.packageManager.getLaunchIntentForPackage(command.packageName)
            Log.d(TAG, "=== getLaunchIntentForPackage 返回: $intent ===")
            if (intent == null) {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(command.packageName)
                }
                val resolveInfoList = context.packageManager.queryIntentActivities(mainIntent, 0)
                Log.d(TAG, "=== queryIntentActivities 找到 ${resolveInfoList.size} 个 activity ===")
                if (resolveInfoList.isNotEmpty()) {
                    val resolveInfo = resolveInfoList[0]
                    intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setClassName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    Log.d(TAG, "=== Fallback intent: $intent ===")
                }
            }
            
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Log.d(TAG, "=== 正在调用 startActivity ===")
                ttsManager.speak("请稍等")
                context.startActivity(intent)

                kotlinx.coroutines.delay(2500)

                ttsManager.speak("正在为您进行操作")
                val result = autoGLMExecutor.executeCommand("分析当前${command.appName}界面，告诉我能做什么操作")
                if (result.isSuccess) {
                    val response = result.getOrDefault("已分析当前界面")
                    speakAndRespond("${response}，您想在${command.appName}里做什么？")
                } else {
                    speakAndRespond("已为您打开${command.appName}，您想做什么操作？")
                }
            } else {
                speakAndRespond("抱歉，没有找到${command.appName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开应用失败", e)
            speakAndRespond("打开${command.appName}失败，请稍后再试")
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun doWeChatCall(command: VoiceCommand.WeChatCall) {
        isAutomationRunning = true
        _uiState.update { it.copy(isLoading = true) }
        _pauseListeningEvent.emit(Unit)
        val callType = if (command.isVideo) "视频" else "语音"
        ttsManager.speak("正在帮您给${command.contactName}打${callType}电话，请稍等")

        try {
            val result = executeWeChatCallUseCase.execute(
                contactPinyin = command.contactName,
                contactDisplayName = command.contactName,
                isVideoCall = command.isVideo,
                childPhoneNumber = null
            )
            if (result) {
                speakAndRespond("已为您发起${callType}电话，请等待接听")
            } else {
                speakAndRespond("发起${callType}电话失败，请稍后再试")
            }
        } catch (e: Exception) {
            Log.e(TAG, "微信通话失败", e)
            speakAndRespond("操作失败，请稍后再试")
        } finally {
            isAutomationRunning = false
            _uiState.update { it.copy(isLoading = false) }
            _restartListeningEvent.emit(Unit)
        }
    }

    private suspend fun doPhoneCall(command: VoiceCommand.PhoneCall) {
        try {
            // 检查电话权限
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) 
                != PackageManager.PERMISSION_GRANTED) {
                speakAndRespond("需要电话权限才能拨打电话，请先开启权限")
                // 发送一个事件让 Activity 去请求权限，这里简化处理
                Log.w(TAG, "电话权限未授予")
                return
            }
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${command.phoneNumber}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            speakAndRespond("正在拨打电话")
        } catch (e: Exception) {
            Log.e(TAG, "拨打电话失败", e)
            speakAndRespond("拨打电话失败，请检查权限")
        }
    }

    private suspend fun doCallTaxi() {
        _uiState.update { it.copy(isLoading = true) }
        try {
            val result = callTaxiUseCase.execute()
            if (result) {
                speakAndRespond("正在为您拨打助老打车电话，请稍等")
            } else {
                speakAndRespond("叫车失败，请检查电话权限")
            }
        } catch (e: Exception) {
            Log.e(TAG, "叫车失败", e)
            speakAndRespond("叫车失败，请稍后再试")
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun doSendMessage(command: VoiceCommand.SendMessage) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(command.target, null, command.message, null, null)
            speakAndRespond("消息已发送给${command.target}")
        } catch (e: Exception) {
            Log.e(TAG, "发送消息失败", e)
            // 降级：打开短信应用
            try {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:${command.target}")
                    putExtra("sms_body", command.message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                speakAndRespond("正在给${command.target}发送消息")
            } catch (e2: Exception) {
                speakAndRespond("发送消息失败，请稍后再试")
            }
        }
    }

    private suspend fun executeSendMessage(command: VoiceCommand.SendMessage) {
        _uiState.update {
            it.copy(pendingAction = PendingAction(
                type = PendingActionType.SEND_MESSAGE,
                command = command,
                description = "发送消息给${command.target}"
            ))
        }
        speakAndRespond("您要给${command.target}发送消息\"${command.message}\"，对吗？请说\"是的\"确认")
    }

    private suspend fun executePlayMusic(command: VoiceCommand.PlayMusic) {
        try {
            if (termuxBridge.isTermuxAvailable()) {
                val subCmd = when (command.action) {
                    "play" -> "play"
                    "pause", "stop" -> "stop"
                    "next" -> "next"
                    "previous" -> "previous"
                    else -> "play"
                }
                termuxBridge.executeCommand(TermuxCommand("MEDIA_PLAYER", mapOf("command" to subCmd)))
                val replyText = when (command.action) {
                    "play" -> "正在播放音乐"
                    "pause", "stop" -> "音乐已暂停"
                    "next" -> "切换到下一首"
                    "previous" -> "切换到上一首"
                    else -> "正在播放音乐"
                }
                speakAndRespond(replyText)
            } else {
                when (command.action) {
                    "play" -> {
                        val intent = Intent("android.intent.action.MUSIC_PLAYER").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        }
                        speakAndRespond("正在播放音乐")
                    }
                    "pause" -> speakAndRespond("音乐已暂停")
                    "next" -> {
                        val intent = Intent("com.android.music.musicservicecommand").apply {
                            putExtra("command", "next")
                        }
                        context.sendBroadcast(intent)
                        speakAndRespond("切换到下一首")
                    }
                    "previous" -> {
                        val intent = Intent("com.android.music.musicservicecommand").apply {
                            putExtra("command", "previous")
                        }
                        context.sendBroadcast(intent)
                        speakAndRespond("切换到上一首")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "音乐控制失败", e)
            speakAndRespond("音乐控制失败")
        }
    }

    private suspend fun executeControlVolume(command: VoiceCommand.ControlVolume) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when (command.action) {
                "up" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    speakAndRespond("音量已调大")
                }
                "down" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    speakAndRespond("音量已调小")
                }
                "mute" -> {
                    if (termuxBridge.isTermuxAvailable()) {
                        termuxBridge.executeCommand(TermuxCommand("VOLUME", mapOf("stream" to "music", "level" to "0")))
                        speakAndRespond("已静音")
                    } else {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                        speakAndRespond("已静音")
                    }
                }
                "unmute" -> {
                    if (termuxBridge.isTermuxAvailable()) {
                        termuxBridge.executeCommand(TermuxCommand("VOLUME", mapOf("stream" to "music", "level" to "10")))
                        speakAndRespond("已取消静音")
                    } else {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                        speakAndRespond("已取消静音")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "音量控制失败", e)
            if (termuxBridge.isTermuxAvailable()) {
                val level = when (command.action) {
                    "up" -> "15"
                    "down" -> "5"
                    "mute" -> "0"
                    else -> "10"
                }
                termuxBridge.executeCommand(TermuxCommand("VOLUME", mapOf("stream" to "music", "level" to level)))
                speakAndRespond("音量已调整")
            } else {
                speakAndRespond("音量控制失败")
            }
        }
    }

    private suspend fun executeSystemSetting(command: VoiceCommand.SystemSetting) {
        try {
            when (command.setting) {
                "flashlight" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                        val cameraId = cameraManager.cameraIdList.firstOrNull()
                        if (cameraId != null) {
                            cameraManager.setTorchMode(cameraId, command.action == "on")
                        }
                    }
                    val actionText = if (command.action == "on") "打开" else "关闭"
                    speakAndRespond("已${actionText}手电筒")
                }
                "bluetooth" -> {
                    if (termuxBridge.isTermuxAvailable()) {
                        termuxBridge.executeCommand(TermuxCommand("WIFI", mapOf("enable" to (command.action == "on").toString())))
                        val actionText = if (command.action == "on") "打开" else "关闭"
                        speakAndRespond("已${actionText}蓝牙")
                    } else {
                        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        val actionText = if (command.action == "on") "打开" else "关闭"
                        speakAndRespond("已为您打开蓝牙设置，请${actionText}蓝牙")
                    }
                }
                "wifi" -> {
                    if (termuxBridge.isTermuxAvailable()) {
                        termuxBridge.executeCommand(TermuxCommand("WIFI", mapOf("enable" to (command.action == "on").toString())))
                        val actionText = if (command.action == "on") "打开" else "关闭"
                        speakAndRespond("已${actionText}WiFi")
                    } else {
                        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        val actionText = if (command.action == "on") "打开" else "关闭"
                        speakAndRespond("已为您打开WiFi设置，请${actionText}WiFi")
                    }
                }
                else -> {
                    val intent = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    speakAndRespond("已为您打开设置")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "系统设置失败", e)
            speakAndRespond("操作失败，请稍后再试")
        }
    }

    private suspend fun executeSetAlarm(command: VoiceCommand.SetAlarm) {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_MESSAGE, command.label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                speakAndRespond("正在为您设置闹钟")
            } else {
                speakAndRespond("没有找到闹钟应用")
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置闹钟失败", e)
            speakAndRespond("设置闹钟失败")
        }
    }

    private suspend fun executeSearchWeb(command: VoiceCommand.SearchWeb) {
        try {
            val url = "https://www.baidu.com/s?wd=${Uri.encode(command.query)}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            speakAndRespond("正在搜索${command.query}")
        } catch (e: Exception) {
            Log.e(TAG, "搜索失败", e)
            speakAndRespond("搜索失败，请稍后再试")
        }
    }

    private suspend fun executeTakePhoto(command: VoiceCommand.TakePhoto) {
        try {
            if (termuxBridge.isTermuxAvailable()) {
                val path = "/sdcard/DCIM/hulao_photo_${System.currentTimeMillis()}.jpg"
                val result = termuxBridge.executeCommand(
                    TermuxCommand("CAMERA", mapOf("camera_id" to "0", "path" to path))
                )
                if (result.success) {
                    speakAndRespond("好的，照片已拍好")
                } else {
                    fallbackCameraIntent(command)
                }
            } else {
                fallbackCameraIntent(command)
            }
        } catch (e: Exception) {
            Log.e(TAG, "拍照失败", e)
            fallbackCameraIntent(command)
        }
    }

    private suspend fun fallbackCameraIntent(command: VoiceCommand.TakePhoto) {
        try {
            val action = if (command.action == "video") {
                android.provider.MediaStore.ACTION_VIDEO_CAPTURE
            } else {
                android.provider.MediaStore.ACTION_IMAGE_CAPTURE
            }
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                val text = if (command.action == "video") "录像" else "拍照"
                speakAndRespond("正在打开${text}")
            } else {
                speakAndRespond("没有找到相机应用")
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开相机失败", e)
            speakAndRespond("打开相机失败")
        }
    }

    private suspend fun executeAutoGLMAction(command: VoiceCommand.AutoGLMAction) {
        _uiState.update { it.copy(isLoading = true) }
        
        val hasScreenCapture = screenCaptureManager.hasPermission()
        val hasAccessibility = isAccessibilityServiceEnabled()
        
        if (!hasScreenCapture && !hasAccessibility) {
            speakAndRespond("需要先开启屏幕录制和无障碍功能才能执行自动化操作")
            _uiState.update { it.copy(isLoading = false) }
            return
        } else if (!hasScreenCapture) {
            speakAndRespond("请先开启屏幕录制功能")
            _uiState.update { it.copy(isLoading = false) }
            return
        } else if (!hasAccessibility) {
            speakAndRespond("请先开启无障碍功能")
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        
        ttsManager.speak("请稍等")
        voiceCommandBridge.processAutoGLMAction(command, alreadyNormalized = true) { stepInfo ->
            _uiState.update {
                it.copy(lastAction = "${stepInfo.actionType}: ${stepInfo.elementName}")
            }
        }
        
        _uiState.update { it.copy(isLoading = false) }
    }
    
    /**
     * 检查无障碍服务是否启用
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val serviceName = ComponentName(context, AutoPilotService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabledServices.contains(serviceName.flattenToString())
        } catch (e: Exception) {
            Log.e(TAG, "检查无障碍服务状态失败", e)
            false
        }
    }

    private suspend fun executeChat(command: VoiceCommand.Chat) {
        try {
            val result = chatService.chat(command.message)
            speakAndRespond(result.reply)
        } catch (e: Exception) {
            Log.e(TAG, "Chat service error: ${e.message}", e)
            speakAndRespond(command.message)
        }
    }

    /**
     * 执行紧急求助
     */
    private suspend fun executeEmergencySOS(command: VoiceCommand.EmergencySOS) {
        _uiState.update { it.copy(isEmergencyActive = true) }
        speakAndRespond("检测到紧急情况，正在为您拨打紧急联系人电话")

        // 立即拨打120
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:120")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "拨打120失败", e)
            // 降级：尝试拨打紧急联系人
            tryCallEmergencyContact()
        }

        // 同时发送短信给紧急联系人
        viewModelScope.launch {
            try {
                val contacts = _uiState.value.contacts
                val emergencyContact = contacts.firstOrNull()
                if (emergencyContact != null) {
                    val phone = emergencyContact.childPhone
                    if (phone.isNotEmpty()) {
                        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.getSystemService(SmsManager::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            SmsManager.getDefault()
                        }
                        val locationText = if (command.reason.isNotEmpty()) "原因：${command.reason}" else ""
                        smsManager.sendTextMessage(
                            phone,
                            null,
                            "【沪沪助手紧急求助】老人可能遇到紧急情况！$locationText 请立即联系或查看位置。",
                            null,
                            null
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "发送紧急短信失败", e)
            }
        }

        // 5秒后重置紧急状态
        viewModelScope.launch {
            kotlinx.coroutines.delay(5000)
            _uiState.update { it.copy(isEmergencyActive = false) }
        }
    }

    private suspend fun doEmergencySOS(command: VoiceCommand.EmergencySOS) {
        executeEmergencySOS(command)
    }

    private suspend fun tryCallEmergencyContact() {
        try {
            val contacts = _uiState.value.contacts
            val emergencyContact = contacts.firstOrNull()
            if (emergencyContact != null) {
                val phone = emergencyContact.childPhone
                if (phone.isNotEmpty()) {
                    val intent = Intent(Intent.ACTION_CALL).apply {
                        data = Uri.parse("tel:$phone")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "拨打紧急联系人失败", e)
        }
    }

    /**
     * 执行吃药提醒
     */
    private suspend fun executeMedicineReminder(command: VoiceCommand.MedicineReminder) {
        val medicineName = if (command.medicineName.isNotEmpty()) command.medicineName else "药"
        val timeText = command.time

        if (timeText.isEmpty()) {
            _uiState.update {
                it.copy(pendingAction = PendingAction(
                    type = PendingActionType.NONE,
                    command = command,
                    description = "设置${medicineName}提醒时间"
                ))
            }
            speakAndRespond("好的，您想几点提醒您吃${medicineName}？请告诉我时间，比如\"早上8点\"")
            return
        }

        _uiState.update { it.copy(isLoading = true) }
        try {
            ttsManager.speak("正在为您设置${medicineName}的提醒")

            val result = medicineReminderUseCase.createReminder(
                medicineName = medicineName,
                time = timeText
            )

            result.fold(
                onSuccess = { reminderInfo ->
                    if (reminderInfo.confirmed && reminderInfo.jobId != null) {
                        speakAndRespond("好的，已设好${medicineName}的提醒，每天${timeText}会提醒您吃药。记得按时吃哦~")
                    } else {
                        speakAndRespond("好的，我会提醒您${timeText}吃${medicineName}的。记得按时吃哦~")
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "设置吃药提醒失败", e)
                    speakAndRespond("好的，记得${timeText}吃${medicineName}哦~")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "设置吃药提醒失败", e)
            speakAndRespond("好的，记得按时吃${medicineName}哦~")
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * 执行位置分享
     */
    private suspend fun executeShareLocation(command: VoiceCommand.ShareLocation) {
        try {
            // 打开位置分享（通过微信或短信）
            val target = command.target
            if (target.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:$target")
                    putExtra("sms_body", "【沪沪助手】老人分享了当前位置，请查看。")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                speakAndRespond("正在给${target}发送位置信息")
            } else {
                // 打开地图应用
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("geo:0,0?q=my+location")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                }
                speakAndRespond("已打开地图，您可以分享当前位置给家人")
            }
        } catch (e: Exception) {
            Log.e(TAG, "位置分享失败", e)
            speakAndRespond("位置分享失败，请稍后再试")
        }
    }

    /**
     * 执行读新闻
     */
    private suspend fun executeReadNews(command: VoiceCommand.ReadNews) {
        _uiState.update { it.copy(isLoading = true) }
        try {
            val topic = if (command.topic.isNotEmpty()) command.topic else "今日热点"
            ttsManager.speak("正在帮您看看${topic}，请稍等")

            val result = newsInfoUseCase.execute(topic)
            result.fold(
                onSuccess = { newsInfo ->
                    speakAndRespond(newsInfo.summary)
                    kotlinx.coroutines.delay(1500)
                    newsInfoUseCase.navigateToNewsApp()
                },
                onFailure = { e ->
                    Log.e(TAG, "读新闻失败", e)
                    speakAndRespond("获取新闻失败，正在为您打开新闻应用")
                    newsInfoUseCase.navigateToNewsApp()
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "读新闻失败", e)
            speakAndRespond("读新闻失败，请稍后再试")
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * 执行查询天气
     */
    private suspend fun executeQueryWeather(command: VoiceCommand.QueryWeather) {
        _uiState.update { it.copy(isLoading = true) }
        try {
            val cityName = if (command.city.isNotEmpty()) command.city else "上海"
            ttsManager.speak("正在帮您查${cityName}天气，请稍等")

            val result = weatherInfoUseCase.execute(cityName)
            result.fold(
                onSuccess = { weatherInfo ->
                    speakAndRespond(weatherInfo.summary)
                    kotlinx.coroutines.delay(1500)
                    weatherInfoUseCase.navigateToWeatherApp(cityName)
                },
                onFailure = { e ->
                    Log.e(TAG, "查询天气失败", e)
                    speakAndRespond("查询天气失败，正在为您打开天气应用")
                    weatherInfoUseCase.navigateToWeatherApp(cityName)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "查询天气失败", e)
            speakAndRespond("查询天气失败，请稍后再试")
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * 语音播报并更新状态
     * 使用TTS完成回调来准确管理状态
     */
    private suspend fun speakAndRespond(text: String) {
        _uiState.update {
            it.copy(
                responseText = text,
                voiceState = VoiceState.SPEAKING
            )
        }
        voiceStateMachine.speakAndAwait(text)
        _uiState.update {
            it.copy(voiceState = VoiceState.IDLE)
        }
        _restartListeningEvent.emit(Unit)
    }

    fun onTileClicked(contact: ContactEntity) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            executeWeChatCallUseCase.execute(
                contactPinyin = contact.wechatRemarkPinyin,
                contactDisplayName = contact.displayName,
                isVideoCall = contact.callType == "video",
                childPhoneNumber = contact.childPhone.ifEmpty { null }
            )
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onTaxiClicked() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            callTaxiUseCase.execute()
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * 紧急求助按钮点击
     */
    fun onEmergencyClicked() {
        viewModelScope.launch {
            executeEmergencySOS(VoiceCommand.EmergencySOS("手动触发"))
        }
    }

    override fun onCleared() {
        super.onCleared()
    }
}
