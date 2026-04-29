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
        private const val MAX_VLM_RETRIES = 2
        private const val VLM_RETRY_DELAY = 8000L
        private const val SEARCH_INPUT_DELAY = 2000L
        private const val SEARCH_RESULT_DELAY = 2500L
        private const val CALL_CONNECT_DELAY = 3000L
        private const val MENU_POPUP_DELAY = 2000L
    }

    private var cancelled = false

    fun cancel() {
        cancelled = true
        Log.d(TAG, "微信通话操作被取消")
    }

    suspend fun execute(
        contactPinyin: String,
        contactDisplayName: String,
        isVideoCall: Boolean = true,
        childPhoneNumber: String? = null
    ): Boolean {
        cancelled = false
        hapticManager.mediumFeedback()
        val callType = if (isVideoCall) "视频" else "语音"
        ttsManager.speak("正在帮您联系${contactDisplayName}，请稍等")

        try {
            if (cancelled) return handleCancel()
            ttsManager.speak("正在打开微信")
            if (!checkAndLaunchWeChat()) {
                return handleFailure(contactDisplayName, childPhoneNumber, "无法启动微信")
            }
            delay(Constants.STEP_DELAY_MEDIUM)

            if (cancelled) return handleCancel()
            ttsManager.speak("正在搜索联系人")
            if (!clickSearchButton()) {
                return handleFailure(contactDisplayName, childPhoneNumber, "找不到搜索按钮")
            }
            delay(Constants.STEP_DELAY_SHORT)

            if (cancelled) return handleCancel()
            if (!inputSearchText(contactDisplayName, contactPinyin)) {
                return handleFailure(contactDisplayName, childPhoneNumber, "无法输入联系人名称")
            }
            delay(SEARCH_INPUT_DELAY)

            if (cancelled) return handleCancel()
            ttsManager.speak("正在查找${contactDisplayName}")
            if (!clickSearchResult(contactDisplayName)) {
                return handleFailure(contactDisplayName, childPhoneNumber, "找不到${contactDisplayName}")
            }
            delay(SEARCH_RESULT_DELAY)

            if (cancelled) return handleCancel()
            ttsManager.speak("正在发起${callType}通话")
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

    private fun handleCancel(): Boolean {
        Log.d(TAG, "操作已取消")
        ttsManager.speak("已取消操作")
        return false
    }

    private fun checkAndLaunchWeChat(): Boolean {
        val screenInfo = AutoPilotService.getScreenInfo() ?: ""
        val isWeChatForeground = screenInfo.contains("com.tencent.mm") ||
                screenInfo.contains("微信") ||
                screenInfo.contains("WeChat")

        if (isWeChatForeground) {
            Log.d(TAG, "微信已在前台，按返回键确保回到主页")
            AutoPilotService.performBack()
            Thread.sleep(500)
            AutoPilotService.performBack()
            Thread.sleep(300)
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
        Log.d(TAG, "尝试无障碍节点查找搜索按钮")
        val searchClicked = AutoPilotService.clickNodeByDesc("搜尋")
                || AutoPilotService.clickNodeByDesc("搜索")
                || AutoPilotService.clickNodeByDesc("Search")
                || AutoPilotService.clickNodeByText("搜索")
                || AutoPilotService.clickNodeByResourceId("com.tencent.mm:id/jha")
        if (searchClicked) {
            Log.d(TAG, "通过无障碍节点点击搜索成功")
            return true
        }

        Log.w(TAG, "无障碍未找到搜索节点，尝试VLM识别")
        val coord = findElementWithVlm(PromptBuilder.buildWeChatSearchButtonPrompt())
        if (coord != null) {
            clickCoordinate(coord)
            return true
        }

        Log.w(TAG, "VLM也未找到搜索按钮，尝试点击搜索图标区域")
        AutoPilotService.performClick(0.84f, 0.058f)
        delay(500)
        return true
    }

    private fun inputSearchText(contactName: String, contactPinyin: String): Boolean {
        val searchText = if (contactPinyin.matches(Regex("^[a-zA-Z]+$"))) {
            Log.d(TAG, "使用拼音搜索: $contactPinyin")
            contactPinyin.lowercase()
        } else {
            Log.d(TAG, "使用名称搜索: $contactName")
            contactName
        }

        val typed = AutoPilotService.performTypeText(searchText)
        if (typed) {
            Log.d(TAG, "成功输入搜索文本: $searchText")
            return true
        }

        Log.w(TAG, "无障碍输入失败，尝试剪贴板方式")
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("search", searchText))
            AutoPilotService.performLongClick(0.5f, 0.12f)
            Thread.sleep(300)
            true
        } catch (e: Exception) {
            Log.e(TAG, "剪贴板输入也失败", e)
            false
        }
    }

    private suspend fun clickSearchResult(contactName: String): Boolean {
        delay(500)

        Log.d(TAG, "尝试无障碍节点查找搜索结果: $contactName")
        val nodeClicked = AutoPilotService.clickNodeByText(contactName)
                || AutoPilotService.clickNodeByDesc(contactName)
        if (nodeClicked) {
            Log.d(TAG, "通过无障碍节点点击搜索结果成功")
            return true
        }

        val firstResultClicked = AutoPilotService.clickNodeByDesc("聯絡人")
                || AutoPilotService.clickNodeByDesc("联系人")
        if (firstResultClicked) {
            Log.d(TAG, "点击第一个联系人分类结果")
            return true
        }

        Log.w(TAG, "无障碍未找到搜索结果，尝试VLM识别")
        val coord = findElementWithVlm(
            PromptBuilder.buildFindElementPrompt("搜索结果中包含「$contactName」的联系人条目")
        )
        if (coord != null) {
            clickCoordinate(coord)
            return true
        }

        Log.w(TAG, "VLM也未找到搜索结果，尝试点击搜索结果列表第一个区域")
        AutoPilotService.performClick(0.5f, 0.20f)
        return true
    }

    private suspend fun clickCallButton(isVideoCall: Boolean): Boolean {
        delay(1500)

        Log.d(TAG, "尝试无障碍节点查找加号按钮")
        val plusClicked = AutoPilotService.clickNodeByDesc("更多功能按鈕，已收起")
                || AutoPilotService.clickNodeByDesc("更多功能按钮，已收起")
                || AutoPilotService.clickNodeByDesc("更多功能")
                || AutoPilotService.clickNodeByDesc("更多功能按钮")
                || AutoPilotService.clickNodeByDesc("加号")
                || AutoPilotService.clickNodeByResourceId("com.tencent.mm:id/bjz")
                || AutoPilotService.clickNodeByResourceId("com.tencent.mm:id/jga")
        if (plusClicked) {
            Log.d(TAG, "通过无障碍节点点击加号成功")
            delay(MENU_POPUP_DELAY)
        } else {
            Log.w(TAG, "无障碍未找到加号按钮，尝试VLM识别")
            val plusCoord = findElementWithVlm(PromptBuilder.buildWeChatVideoCallPrompt())
            if (plusCoord != null) {
                clickCoordinate(plusCoord)
                Log.d(TAG, "已通过VLM点击加号按钮，等待菜单弹出")
                delay(MENU_POPUP_DELAY)
            } else {
                Log.w(TAG, "VLM也未找到加号按钮，点击右下角+按钮区域")
                AutoPilotService.performClick(0.94f, 0.95f)
                delay(MENU_POPUP_DELAY)
            }
        }

        delay(500)

        Log.d(TAG, "尝试无障碍节点查找通话按钮")
        val callClicked = if (isVideoCall) {
            AutoPilotService.clickNodeByText("视频通话")
                    || AutoPilotService.clickNodeByText("視訊通話")
                    || AutoPilotService.clickNodeByDesc("视频通话")
                    || AutoPilotService.clickNodeByDesc("視訊通話")
        } else {
            AutoPilotService.clickNodeByText("语音通话")
                    || AutoPilotService.clickNodeByText("語音通話")
                    || AutoPilotService.clickNodeByDesc("语音通话")
                    || AutoPilotService.clickNodeByDesc("語音通話")
        }
        if (callClicked) {
            Log.d(TAG, "通过无障碍节点点击通话按钮成功")
            delay(1500)
            handleCallTypeSelection(isVideoCall)
            return true
        }

        Log.w(TAG, "无障碍未找到通话按钮，尝试VLM识别")
        val callPrompt = if (isVideoCall) PromptBuilder.buildWeChatVideoCallOptionPrompt()
        else PromptBuilder.buildWeChatVoiceCallOptionPrompt()
        val callCoord = findElementWithVlm(callPrompt)
        if (callCoord != null) {
            clickCoordinate(callCoord)
            delay(1500)
            handleCallTypeSelection(isVideoCall)
            return true
        }

        Log.w(TAG, "VLM也未找到通话按钮，尝试固定位置点击")
        if (isVideoCall) {
            AutoPilotService.performClick(0.62f, 0.71f)
        } else {
            AutoPilotService.performClick(0.62f, 0.81f)
        }

        delay(1500)
        handleCallTypeSelection(isVideoCall)
        return true
    }

    private suspend fun handleCallTypeSelection(isVideoCall: Boolean) {
        delay(1000)
        val hasVideoOption = AutoPilotService.hasNodeByText("视频通话")
                || AutoPilotService.hasNodeByText("視訊通話")
        val hasVoiceOption = AutoPilotService.hasNodeByText("语音通话")
                || AutoPilotService.hasNodeByText("語音通話")

        if (hasVideoOption && hasVoiceOption) {
            Log.d(TAG, "检测到通话类型选择菜单（语音/视频）")
            if (isVideoCall) {
                val selected = AutoPilotService.clickNodeByText("视频通话")
                        || AutoPilotService.clickNodeByText("視訊通話")
                Log.d(TAG, "选择视频通话: $selected")
            } else {
                val selected = AutoPilotService.clickNodeByText("语音通话")
                        || AutoPilotService.clickNodeByText("語音通話")
                Log.d(TAG, "选择语音通话: $selected")
            }
        }
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
                val dialectMsg = if (isBusy) "对方暂时无法接听，请稍后再试" else "呼叫遇到了点问题"
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
            if (cancelled) return null
            try {
                val bitmap = screenCaptureManager.captureScreen()
                if (bitmap == null) {
                    Log.w(TAG, "截屏失败，第${attempt + 1}次重试")
                    delay(VLM_RETRY_DELAY)
                    return@repeat
                }

                val base64Image = com.ailaohu.util.BitmapUtils.bitmapToBase64(bitmap, 720, 70)
                val result = vlmService.findElement(base64Image, prompt)
                if (result != null) {
                    Log.d(TAG, "VLM定位成功: coord=(${result.x}, ${result.y})")
                    return result
                }

                Log.w(TAG, "VLM未找到元素，第${attempt + 1}次重试")
                delay(VLM_RETRY_DELAY)
            } catch (e: Exception) {
                Log.e(TAG, "VLM查找异常，第${attempt + 1}次: ${e.message}")
                if (e.message?.contains("429") == true) {
                    val backoffDelay = VLM_RETRY_DELAY * (attempt + 1)
                    Log.w(TAG, "API限流，等待${backoffDelay}ms后重试")
                    delay(backoffDelay)
                } else {
                    delay(VLM_RETRY_DELAY)
                }
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
        ttsManager.speak("发起视频电话失败，请稍后再试")
        if (!childPhone.isNullOrEmpty()) {
            smsFallbackService.sendSms(
                childPhone,
                "【爱唠胡】${contactName}，爷爷/奶奶正在尝试联系您，请及时回电"
            )
        }
        return false
    }
}
