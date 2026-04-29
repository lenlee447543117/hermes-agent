package com.ailaohu.service.voice

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.ailaohu.service.accessibility.AutoPilotService
import com.ailaohu.service.capture.ScreenCaptureManager
import com.ailaohu.service.tts.TTSManager
import com.ailaohu.service.vlm.VLMService
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class StepInfo(
    val actionType: String,
    val elementName: String = "",
    val description: String = ""
)

@Singleton
class VoiceCommandBridge @Inject constructor(
    private val autoGLMExecutor: AutoGLMExecutor,
    private val ttsManager: TTSManager,
    private val commandNormalizer: CommandNormalizer,
    private val stateMachine: VoiceStateMachine,
    private val screenCaptureManager: ScreenCaptureManager,
    private val vlmService: VLMService,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "VoiceBridge"
    }

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile
    private var isExecuting = false
    private var onCommandComplete: (() -> Unit)? = null
    private val gson = Gson()

    fun setOnCommandComplete(callback: (() -> Unit)?) {
        onCommandComplete = callback
    }

    fun processCommand(rawText: String, alreadyNormalized: Boolean = false, onStepUpdate: ((StepInfo) -> Unit)? = null) {
        if (isExecuting) {
            Log.w(TAG, "已有任务在执行中，忽略新指令")
            ttsManager.enqueue("正在执行中，请稍等")
            return
        }

        val refinedText = if (alreadyNormalized) rawText else commandNormalizer.normalize(rawText)
        Log.d(TAG, "指令处理: '$rawText' → '$refinedText'")

        stateMachine.transitionTo(VoicePipelineState.EXECUTING, refinedText)
        ttsManager.speak("正在为您进行操作")

        bridgeScope.launch {
            isExecuting = true
            try {
                val hasScreenCapture = screenCaptureManager.hasPermission()
                val hasAccessibility = AutoPilotService.isRunning()

                if (!hasScreenCapture && !hasAccessibility) {
                    stateMachine.speakAndWait("需要先开启屏幕录制和无障碍功能才能执行操作")
                    return@launch
                }

                // 获取屏幕截图
                val base64Image = if (hasScreenCapture) {
                    screenCaptureManager.getScreenshotBase64()
                } else {
                    null
                }

                if (base64Image != null) {
                    Log.d(TAG, "已获取屏幕截图，使用AutoGLM-Phone模型")
                    // 使用AutoGLM-Phone模型
                    val result = vlmService.executeAutoGLMPhone(refinedText, base64Image)

                    if (result.isSuccess) {
                        val content = result.getOrDefault("操作完成")
                        val (steps, actionResponse) = parseStepsFromResponse(content)

                        // 执行操作步骤
                        if (actionResponse != null && actionResponse.actions.isNotEmpty()) {
                            Log.d(TAG, "开始执行${actionResponse.actions.size}个操作步骤")
                            executeActions(actionResponse.actions, steps, onStepUpdate)
                        } else {
                            for (step in steps) {
                                val feedback = generateStepFeedback(step)
                                stateMachine.enqueueStepFeedback(feedback)
                                onStepUpdate?.invoke(step)
                            }
                        }

                        stateMachine.speakAndWait("操作完成，您还可以继续对我说")
                    } else {
                        Log.w(TAG, "AutoGLM-Phone调用失败，回退到普通AutoGLM")
                        executeFallbackAutoGLM(refinedText, onStepUpdate)
                    }
                } else {
                    Log.d(TAG, "没有屏幕截图，使用普通AutoGLM")
                    executeFallbackAutoGLM(refinedText, onStepUpdate)
                }
            } catch (e: Exception) {
                Log.e(TAG, "指令执行异常", e)
                stateMachine.speakAndWait("执行出错，请稍后再试")
            } finally {
                isExecuting = false
                onCommandComplete?.invoke()
            }
        }
    }

    private suspend fun executeFallbackAutoGLM(command: String, onStepUpdate: ((StepInfo) -> Unit)? = null) {
        val screenDescription = captureScreenDescription()
        val result = autoGLMExecutor.executeWithScreenContext(command, screenDescription)

        if (result.isSuccess) {
            val content = result.getOrDefault("操作完成")
            val (steps, actionResponse) = parseStepsFromResponse(content)

            // 执行操作步骤
            if (actionResponse != null && actionResponse.actions.isNotEmpty()) {
                Log.d(TAG, "开始执行${actionResponse.actions.size}个操作步骤")
                executeActions(actionResponse.actions, steps, onStepUpdate)
            } else {
                for (step in steps) {
                    val feedback = generateStepFeedback(step)
                    stateMachine.enqueueStepFeedback(feedback)
                    onStepUpdate?.invoke(step)
                }
            }

            stateMachine.speakAndWait("操作完成，您还可以继续对我说")
        } else {
            stateMachine.speakAndWait("执行出错，请查看屏幕提示")
        }
    }

    fun processAutoGLMAction(command: VoiceCommand.AutoGLMAction, alreadyNormalized: Boolean = false, onStepUpdate: ((StepInfo) -> Unit)? = null) {
        processCommand(command.command, alreadyNormalized = alreadyNormalized, onStepUpdate = onStepUpdate)
    }

    private suspend fun captureScreenDescription(): String {
        return if (screenCaptureManager.hasPermission()) {
            val bitmap = screenCaptureManager.captureScreen()
            if (bitmap != null) "已获取当前屏幕画面" else "无法获取屏幕画面"
        } else {
            "屏幕捕获权限不可用"
        }
    }

    private fun parseStepsFromResponse(content: String): Pair<List<StepInfo>, AutoGlmActionResponse?> {
        val steps = mutableListOf<StepInfo>()
        var actionResponse: AutoGlmActionResponse? = null
        try {
            val jsonStart = content.indexOf("{")
            val jsonEnd = content.lastIndexOf("}") + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonStr = content.substring(jsonStart, jsonEnd)
                actionResponse = gson.fromJson(jsonStr, AutoGlmActionResponse::class.java)
            }

            if (actionResponse != null && actionResponse.actions.isNotEmpty()) {
                for (action in actionResponse.actions) {
                    steps.add(
                        StepInfo(
                            actionType = action.type,
                            elementName = when (action.type) {
                                "click" -> "位置(${action.x},${action.y})"
                                "swipe" -> "从(${action.x},${action.y})滑到(${action.endX},${action.endY})"
                                "type" -> action.text
                                "launch" -> action.app
                                "dial" -> action.number
                                else -> ""
                            },
                            description = actionResponse.description
                        )
                    )
                }
            } else {
                val doActions = parseDoActionFormat(content)
                if (doActions.isNotEmpty()) {
                    actionResponse = AutoGlmActionResponse(actions = doActions, description = "AutoGLM操作")
                    for (action in doActions) {
                        steps.add(
                            StepInfo(
                                actionType = action.type,
                                elementName = when (action.type) {
                                    "click" -> "位置(${action.x},${action.y})"
                                    "swipe" -> "从(${action.x},${action.y})滑到(${action.endX},${action.endY})"
                                    "type" -> action.text
                                    "launch" -> action.app
                                    "dial" -> action.number
                                    else -> ""
                                },
                                description = "AutoGLM操作"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析步骤失败: ${e.message}")
        }
        return Pair(steps, actionResponse)
    }

    private fun parseDoActionFormat(content: String): List<AutoGlmActionStep> {
        val actions = mutableListOf<AutoGlmActionStep>()
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        val doPattern = Regex("""do\(action\s*=\s*"(\w+)"\s*,\s*element\s*=\s*\[(\d+)\s*,\s*(\d+)\]\)""")
        for (match in doPattern.findAll(content)) {
            val actionType = match.groupValues[1]
            val absX = match.groupValues[2].toIntOrNull() ?: continue
            val absY = match.groupValues[3].toIntOrNull() ?: continue
            val normX = (absX / screenWidth).coerceIn(0f, 1f)
            val normY = (absY / screenHeight).coerceIn(0f, 1f)
            when (actionType.lowercase()) {
                "tap", "click" -> {
                    actions.add(AutoGlmActionStep(type = "click", x = normX, y = normY))
                    Log.d(TAG, "解析do格式: Tap($absX,$absY) → click($normX,$normY)")
                }
                "swipe" -> {
                    actions.add(AutoGlmActionStep(type = "swipe", x = normX, y = normY))
                }
                "type", "input" -> {
                    actions.add(AutoGlmActionStep(type = "type", x = normX, y = normY))
                }
                else -> {
                    actions.add(AutoGlmActionStep(type = "click", x = normX, y = normY))
                }
            }
        }

        val launchPattern = Regex("""do\(action\s*=\s*"Launch"\s*,\s*app\s*=\s*"([^"]+)"\)""")
        for (match in launchPattern.findAll(content)) {
            val app = match.groupValues[1]
            actions.add(AutoGlmActionStep(type = "launch", app = app))
            Log.d(TAG, "解析do格式: Launch($app)")
        }

        val typePattern = Regex("""do\(action\s*=\s*"Type"\s*,\s*text\s*=\s*"([^"]+)"\)""")
        for (match in typePattern.findAll(content)) {
            val text = match.groupValues[1]
            actions.add(AutoGlmActionStep(type = "type", text = text))
            Log.d(TAG, "解析do格式: Type($text)")
        }

        return actions
    }

    /**
     * 执行操作步骤
     */
    private suspend fun executeActions(
        actions: List<AutoGlmActionStep>,
        steps: List<StepInfo>,
        onStepUpdate: ((StepInfo) -> Unit)?
    ) {
        withContext(Dispatchers.Main) {
            for ((index, action) in actions.withIndex()) {
                // 更新UI
                if (index < steps.size) {
                    val feedback = generateStepFeedback(steps[index])
                    stateMachine.enqueueStepFeedback(feedback)
                    onStepUpdate?.invoke(steps[index])
                }

                // 执行操作
                executeSingleAction(action)

                // 等待延迟
                if (index < actions.size - 1) {
                    delay(action.delay)
                }
            }
        }
    }

    /**
     * 执行单个操作
     */
    private fun executeSingleAction(action: AutoGlmActionStep) {
        try {
            when (action.type) {
                "click" -> {
                    AutoPilotService.performClick(action.x, action.y)
                    Log.d(TAG, "执行点击: (${action.x}, ${action.y})")
                }
                "swipe" -> {
                    AutoPilotService.performSwipe(action.x, action.y, action.endX, action.endY, action.duration)
                    Log.d(TAG, "执行滑动: (${action.x},${action.y})→(${action.endX},${action.endY})")
                }
                "type" -> {
                    AutoPilotService.performTypeText(action.text)
                    Log.d(TAG, "执行输入: ${action.text}")
                }
                "launch" -> {
                    if (action.app.isNotEmpty()) {
                        val intent = context.packageManager.getLaunchIntentForPackage(action.app)
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            Log.d(TAG, "执行打开应用: ${action.app}")
                        } else {
                            Log.w(TAG, "未找到应用: ${action.app}")
                        }
                    }
                }
                "dial" -> {
                    if (action.number.isNotEmpty()) {
                        val intent = Intent(Intent.ACTION_CALL).apply {
                            data = Uri.parse("tel:${action.number}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        Log.d(TAG, "执行拨号: ${action.number}")
                    }
                }
                "back" -> {
                    AutoPilotService.performBack()
                    Log.d(TAG, "执行返回")
                }
                "home" -> {
                    AutoPilotService.performHome()
                    Log.d(TAG, "执行回主页")
                }
                "scroll" -> {
                    AutoPilotService.performScroll(action.direction)
                    Log.d(TAG, "执行滚动: ${action.direction}")
                }
                "long_click" -> {
                    AutoPilotService.performLongClick(action.x, action.y, action.duration)
                    Log.d(TAG, "执行长按: (${action.x}, ${action.y})")
                }
                else -> {
                    Log.w(TAG, "未知操作类型: ${action.type}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行操作失败: ${action.type}", e)
        }
    }

    private fun generateStepFeedback(step: StepInfo): String {
        return when (step.actionType) {
            "click" -> "点击${step.elementName}"
            "swipe" -> "滑动屏幕"
            "type" -> "输入文本"
            "launch" -> "启动应用"
            "dial" -> "拨打电话"
            "back" -> "返回上一页"
            "home" -> "回到主页"
            "scroll" -> "滚动屏幕"
            "long_click" -> "长按${step.elementName}"
            else -> "正在操作"
        }
    }

    fun cancelExecution() {
        isExecuting = false
        if (stateMachine.currentState() == VoicePipelineState.EXECUTING) {
            stateMachine.transitionTo(VoicePipelineState.IDLE, "cancelled")
        }
        ttsManager.stop()
    }

    fun destroy() {
        cancelExecution()
        bridgeScope.cancel()
    }
}
