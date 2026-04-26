package com.ailaohu.ui.home.mvi

import android.util.Log
import com.ailaohu.data.local.prefs.AppPreferences
import com.ailaohu.domain.usecase.CallTaxiUseCase
import com.ailaohu.domain.usecase.ExecuteWeChatCallUseCase
import com.ailaohu.service.chat.ChatService
import com.ailaohu.service.dialect.DialectManager
import com.ailaohu.service.habit.HabitTrackingService
import com.ailaohu.service.voice.VoiceCommand
import com.ailaohu.service.voice.VoiceCommandBridge
import javax.inject.Inject

class CommandProcessor @Inject constructor(
    private val weChatCallUseCase: ExecuteWeChatCallUseCase,
    private val callTaxiUseCase: CallTaxiUseCase,
    private val chatService: ChatService,
    private val voiceCommandBridge: VoiceCommandBridge,
    private val dialectManager: DialectManager,
    private val habitTrackingService: HabitTrackingService,
    private val appPreferences: AppPreferences
) {
    companion object {
        private const val TAG = "CommandProcessor"
    }

    data class ProcessResult(
        val replyText: String,
        val shouldSpeak: Boolean = true,
        val intent: String? = null,
        val mode: String = "COMMAND"
    )

    suspend fun process(command: VoiceCommand, dialect: String = "shanghai"): ProcessResult {
        habitTrackingService.recordAction(
            intent = command.javaClass.simpleName,
            target = getTargetFromCommand(command)
        )

        return when (command) {
            is VoiceCommand.WeChatCall -> processWeChatCall(command, dialect)
            is VoiceCommand.PhoneCall -> processPhoneCall(command, dialect)
            is VoiceCommand.CallTaxi -> processCallTaxi(command, dialect)
            is VoiceCommand.LaunchApp -> processLaunchApp(command, dialect)
            is VoiceCommand.Chat -> processChat(command, dialect)
            is VoiceCommand.EmergencySOS -> processEmergency(command, dialect)
            is VoiceCommand.ControlVolume -> processVolumeControl(command, dialect)
            is VoiceCommand.SystemSetting -> processSystemSetting(command, dialect)
            is VoiceCommand.QueryWeather -> processWeather(command, dialect)
            is VoiceCommand.ReadNews -> processNews(command, dialect)
            is VoiceCommand.MedicineReminder -> processMedicineReminder(command, dialect)
            is VoiceCommand.AutoGLMAction -> processAutoGLM(command, dialect)
            else -> ProcessResult(
                replyText = dialectManager.getComfort(dialect),
                mode = "CHAT"
            )
        }
    }

    private suspend fun processWeChatCall(cmd: VoiceCommand.WeChatCall, dialect: String): ProcessResult {
        val callTypeStr = if (cmd.isVideo) "视频" else "语音"
        val result = weChatCallUseCase.execute(
            contactPinyin = cmd.contactName,
            contactDisplayName = cmd.contactName,
            isVideoCall = cmd.isVideo
        )
        val reply = if (result) dialectManager.getConfirm(dialect) else "联系${cmd.contactName}遇到了问题，勿要紧，等一歇再试"
        return ProcessResult(replyText = reply, intent = "WECHAT_${callTypeStr.uppercase()}_CALL")
    }

    private suspend fun processPhoneCall(cmd: VoiceCommand.PhoneCall, dialect: String): ProcessResult {
        val result = callTaxiUseCase.execute()
        val reply = if (result) dialectManager.getConfirm(dialect) else dialectManager.getError(dialect)
        return ProcessResult(replyText = reply, intent = "PHONE_CALL")
    }

    private suspend fun processCallTaxi(cmd: VoiceCommand.CallTaxi, dialect: String): ProcessResult {
        val result = callTaxiUseCase.execute()
        val reply = if (result) "已帮您拨打62580000助老打车热线" else dialectManager.getError(dialect)
        return ProcessResult(replyText = reply, intent = "CALL_TAXI")
    }

    private suspend fun processLaunchApp(cmd: VoiceCommand.LaunchApp, dialect: String): ProcessResult {
        val reply = "正在帮您打开${cmd.appName}"
        return ProcessResult(replyText = reply, intent = "LAUNCH_APP")
    }

    private suspend fun processChat(cmd: VoiceCommand.Chat, dialect: String): ProcessResult {
        val result = chatService.chat(cmd.message)
        return ProcessResult(
            replyText = result.reply,
            intent = result.intent,
            mode = result.mode
        )
    }

    private suspend fun processEmergency(cmd: VoiceCommand.EmergencySOS, dialect: String): ProcessResult {
        return ProcessResult(
            replyText = "正在为您拨打紧急求助电话",
            intent = "EMERGENCY_SOS"
        )
    }

    private suspend fun processVolumeControl(cmd: VoiceCommand.ControlVolume, dialect: String): ProcessResult {
        return ProcessResult(replyText = "已帮您调整音量", intent = "CONTROL_VOLUME")
    }

    private suspend fun processSystemSetting(cmd: VoiceCommand.SystemSetting, dialect: String): ProcessResult {
        return ProcessResult(replyText = "已帮您${cmd.action}", intent = "SYSTEM_SETTING")
    }

    private suspend fun processWeather(cmd: VoiceCommand.QueryWeather, dialect: String): ProcessResult {
        return ProcessResult(replyText = "正在帮您查天气", intent = "QUERY_WEATHER")
    }

    private suspend fun processNews(cmd: VoiceCommand.ReadNews, dialect: String): ProcessResult {
        return ProcessResult(replyText = "正在帮您看新闻", intent = "READ_NEWS")
    }

    private suspend fun processMedicineReminder(cmd: VoiceCommand.MedicineReminder, dialect: String): ProcessResult {
        return ProcessResult(replyText = "好的，已设置吃药提醒", intent = "MEDICINE_REMINDER")
    }

    private fun processAutoGLM(cmd: VoiceCommand.AutoGLMAction, dialect: String): ProcessResult {
        return try {
            voiceCommandBridge.processCommand(cmd.command)
            ProcessResult(replyText = dialectManager.getConfirm(dialect), intent = "AUTOGLM")
        } catch (e: Exception) {
            ProcessResult(replyText = dialectManager.getError(dialect), intent = "AUTOGLM")
        }
    }

    private fun getTargetFromCommand(command: VoiceCommand): String? {
        return when (command) {
            is VoiceCommand.WeChatCall -> command.contactName
            is VoiceCommand.PhoneCall -> command.phoneNumber
            is VoiceCommand.LaunchApp -> command.appName
            else -> null
        }
    }
}
