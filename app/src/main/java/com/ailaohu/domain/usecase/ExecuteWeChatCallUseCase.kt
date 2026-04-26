package com.ailaohu.domain.usecase

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ailaohu.service.accessibility.AutoPilotService
import com.ailaohu.service.capture.ScreenCaptureManager
import com.ailaohu.service.sms.SmsFallbackService
import com.ailaohu.service.tts.TTSManager
import com.ailaohu.service.vlm.ScreenCoordinate
import com.ailaohu.service.vlm.VLMService
import com.ailaohu.service.vlm.PromptBuilder
import com.ailaohu.util.Constants
import com.ailaohu.util.HapticManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject

class ExecuteWeChatCallUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vlmService: VLMService,
    private val ttsManager: TTSManager,
    private val hapticManager: HapticManager,
    private val smsFallbackService: SmsFallbackService,
    private val screenCaptureManager: ScreenCaptureManager
) {
    companion object {
        private const val TAG = "WeChatCall"
        private const val MAX_VLM_RETRIES = 3
        private const val SEARCH_INPUT_DELAY = 1500L
        private const val SEARCH_RESULT_DELAY = 2000L
        private const val CALL_CONNECT_DELAY = 3000L
    }

    suspend fun execute(
        contactPinyin: String,
        contactDisplayName: String,
        isVideoCall: Boolean = true,
        childPhoneNumber: String? = null
    ): Boolean {
        hapticManager.mediumFeedback()
        val callType = if (isVideoCall) "视频" else "语音"
        ttsManager.speak("正在帮您联系${contactDisplayName}，请稍等")

        try {
            if (!checkAndLaunchWeChat()) {
                return handleFailure(contactDisplayName, childPhoneNumber, "无法启动微信")
            }
            delay(Constants.STEP_DELAY_MEDIUM)

            if (!clickSearchButton()) {
                return handleFailure(contactDisplayName, childPhoneNumber, "找不到搜索按钮")
            }
            delay(Constants.STEP_DELAY_SHORT)

            if (!inputSearchText(contactDisplayName)) {
                return handleFailure(contactDisplayName, childPhoneNumber, "无法输入联系人名称")
            }
            delay(SEARCH_INPUT_DELAY)

            if (!clickSearchResult(contactDisplayName)) {
                return handleFailure(contactDisplayName, childPhoneNumber, "找不到${contactDisplayName}")
            }
            delay(SEARCH_RESULT_DELAY)

            if (!clickCallButton(isVideoCall)) {
                return handleFailure(contactDisplayName, childPhoneNumber, "找不到${callType}通话按钮")
            }

            delay(CALL_CONNECT_DELAY)
            if (!verifyCallConnected(isVideoCall)) {
                ttsManager.speak("正在等待对方接听，请耐心等待")
            } else {
                ttsManager.speak("已为您发起${callType}通话，请稍等对方接听")
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "微信通话自动化异常", e)
            return handleFailure(contactDisplayName, childPhoneNumber, "操作异常：${e.message}")
        }
    }

    private fun checkAndLaunchWeChat(): Boolean {
        val screenInfo = AutoPilotService.getScreenInfo() ?: ""
        val isWeChatForeground = screenInfo.contains("com.tencent.mm") ||
                screenInfo.contains("微信") ||
                screenInfo.contains("WeChat")

        if (isWeChatForeground) {
            Log.d(TAG, "微信已在前台")
            return true
        }

        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(Constants.WECHAT_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                Log.d(TAG, "已启动微信")
                true
            } else {
                Log.e(TAG, "设备未安装微信")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动微信失败", e)
            false
        }
    }

    private suspend fun clickSearchButton(): Boolean {
        val coord = findElementWithVlm(PromptBuilder.buildWeChatSearchButtonPrompt())
        if (coord != null) {
            clickCoordinate(coord)
            return true
        }

        Log.w(TAG, "VLM未找到搜索按钮，尝试无障碍节点查找")
        val screenInfo = AutoPilotService.getScreenInfo() ?: ""
        if (screenInfo.contains("搜索")) {
            AutoPilotService.performClick(0.92f, 0.04f)
            return true
        }

        return false
    }

    private fun inputSearchText(contactName: String): Boolean {
        val typed = AutoPilotService.performTypeText(contactName)
        if (typed) {
            Log.d(TAG, "成功输入联系人名称: $contactName")
            return true
        }

        Log.w(TAG, "无障碍输入失败，尝试剪贴板方式")
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("search", contactName))
            AutoPilotService.performLongClick(0.5f, 0.12f)
            Thread.sleep(300)
            true
        } catch (e: Exception) {
            Log.e(TAG, "剪贴板输入也失败", e)
            false
        }
    }

    private suspend fun clickSearchResult(contactName: String): Boolean {
        val coord = findElementWithVlm(
            PromptBuilder.buildFindElementPrompt("搜索结果中包含「$contactName」的联系人条目")
        )
        if (coord != null) {
            clickCoordinate(coord)
            return true
        }

        Log.w(TAG, "VLM未找到搜索结果，尝试点击第一个结果区域")
        AutoPilotService.performClick(0.5f, 0.25f)
        return true
    }

    private suspend fun clickCallButton(isVideoCall: Boolean): Boolean {
        val plusCoord = findElementWithVlm(PromptBuilder.buildWeChatVideoCallPrompt())
        if (plusCoord != null) {
            clickCoordinate(plusCoord)
            delay(Constants.STEP_DELAY_SHORT)
        } else {
            Log.w(TAG, "VLM未找到加号按钮，尝试固定位置点击")
            AutoPilotService.performClick(0.92f, 0.92f)
            delay(Constants.STEP_DELAY_SHORT)
        }

        val callPrompt = if (isVideoCall) PromptBuilder.buildWeChatVideoCallOptionPrompt()
        else PromptBuilder.buildWeChatVoiceCallOptionPrompt()
        val callCoord = findElementWithVlm(callPrompt)
        if (callCoord != null) {
            clickCoordinate(callCoord)
            return true
        }

        Log.w(TAG, "VLM未找到通话按钮，尝试无障碍节点查找")
        val screenInfo = AutoPilotService.getScreenInfo() ?: ""
        val targetText = if (isVideoCall) "视频通话" else "语音通话"
        if (screenInfo.contains(targetText)) {
            AutoPilotService.performClick(0.5f, 0.55f)
            return true
        }

        return false
    }

    private suspend fun verifyCallConnected(isVideoCall: Boolean): Boolean {
        delay(2000)
        val screenInfo = AutoPilotService.getScreenInfo() ?: ""
        val connectedIndicators = listOf("呼叫中", "正在呼叫", "等待接听", "已接通", "免提", "静音", "挂断")
        val isLikelyConnected = connectedIndicators.any { screenInfo.contains(it) }

        if (!isLikelyConnected) {
            val busyIndicators = listOf("对方忙", "无人接听", "已拒绝", "已取消", "网络异常")
            val isBusy = busyIndicators.any { screenInfo.contains(it) }
            if (isBusy) {
                val dialectMsg = if (isBusy) "对方暂时无法接听，等一歇再试好伐？" else "呼叫遇到了点问题"
                ttsManager.speak(dialectMsg)
                AutoPilotService.performBack()
                return false
            }
        }

        return isLikelyConnected
    }

    private suspend fun findElementWithVlm(prompt: String): ScreenCoordinate? {
        if (!screenCaptureManager.hasPermission()) {
            Log.w(TAG, "无截屏权限，无法使用VLM")
            return null
        }

        repeat(MAX_VLM_RETRIES) { attempt ->
            try {
                val bitmap = screenCaptureManager.captureScreen()
                if (bitmap == null) {
                    Log.w(TAG, "截屏失败，第${attempt + 1}次重试")
                    delay(Constants.RETRY_DELAY)
                    return@repeat
                }

                val base64Image = com.ailaohu.util.BitmapUtils.bitmapToBase64(bitmap, 720, 70)
                val result = vlmService.findElement(base64Image, prompt)
                if (result != null) {
                    Log.d(TAG, "VLM定位成功: prompt=$prompt, coord=(${result.x}, ${result.y})")
                    return result
                }

                Log.w(TAG, "VLM未找到元素，第${attempt + 1}次重试")
                delay(Constants.RETRY_DELAY)
            } catch (e: Exception) {
                Log.e(TAG, "VLM查找异常，第${attempt + 1}次: ${e.message}")
                delay(Constants.RETRY_DELAY)
            }
        }
        return null
    }

    private fun clickCoordinate(coord: ScreenCoordinate) {
        val randomDelay = (300..600).random().toLong()
        Thread.sleep(randomDelay)
        AutoPilotService.performClick(coord.x, coord.y)
    }

    private fun handleFailure(contactName: String, childPhone: String?, reason: String): Boolean {
        Log.e(TAG, "微信通话失败: $reason")
        ttsManager.speak("${reason}，正在尝试短信通知")
        if (!childPhone.isNullOrEmpty()) {
            smsFallbackService.sendSms(
                childPhone,
                "【爱唠胡】${contactName}，爷爷/奶奶正在尝试联系您，请及时回电"
            )
        }
        return false
    }
}
