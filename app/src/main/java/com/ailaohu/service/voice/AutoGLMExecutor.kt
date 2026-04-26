package com.ailaohu.service.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.ailaohu.BuildConfig
import com.ailaohu.data.local.prefs.AppPreferences
import com.ailaohu.data.remote.api.AutoGlmApiService
import com.ailaohu.data.remote.dto.AutoGlmRequest
import com.ailaohu.service.accessibility.AutoPilotService
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class AutoGlmActionStep(
    val type: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val endX: Float = 0f,
    val endY: Float = 0f,
    val text: String = "",
    val duration: Long = 500,
    val direction: String = "",
    val app: String = "",
    val number: String = "",
    val delay: Long = 300
)

data class AutoGlmActionResponse(
    @SerializedName("actions") val actions: List<AutoGlmActionStep> = emptyList(),
    @SerializedName("description") val description: String = ""
)

@Singleton
class AutoGLMExecutor @Inject constructor(
    private val autoGlmApiService: AutoGlmApiService,
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences
) {

    companion object {
        private const val TAG = "AutoGLM"
        private val API_KEY: String get() = BuildConfig.ZHIPU_API_KEY
    }

    private val gson = Gson()

    private suspend fun getModelConfig(): Pair<String, Pair<Float, Int>> {
        val model = appPreferences.aiModel.first()
        val temperature = 0.3f
        val maxTokens = 1024
        return model to (temperature to maxTokens)
    }

    /**
     * 执行AutoGLM命令，获取操作步骤并通过无障碍服务执行
     */
    suspend fun executeCommand(command: String): Result<String> {
        return try {
            Log.d(TAG, "执行AutoGLM命令: $command")

            val screenInfo = AutoPilotService.getScreenInfo() ?: "无法获取屏幕信息"
            val (model, params) = getModelConfig()
            val (temperature, maxTokens) = params

            val request = AutoGlmRequest(
                model = model,
                messages = listOf(
                    AutoGlmRequest.Message(
                        role = "system",
                        content = buildSystemPrompt()
                    ),
                    AutoGlmRequest.Message(
                        role = "user",
                        content = "当前屏幕信息：\n$screenInfo\n\n用户指令：$command"
                    )
                ),
                maxTokens = maxTokens,
                temperature = temperature
            )

            val response = autoGlmApiService.chatCompletion(
                authorization = "Bearer $API_KEY",
                request = request
            )

            val content = response.choices?.firstOrNull()?.message?.content ?: "操作完成"
            Log.d(TAG, "AutoGLM返回: $content")

            // 尝试解析返回的操作步骤并执行
            val executed = tryParseAndExecute(content)
            if (executed) {
                Result.success(content)
            } else {
                Result.success(content)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AutoGLM命令执行失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 带屏幕上下文执行AutoGLM命令
     */
    suspend fun executeWithScreenContext(command: String, screenDescription: String): Result<String> {
        return try {
            Log.d(TAG, "带屏幕上下文执行AutoGLM命令: $command")

            val (model, params) = getModelConfig()
            val (temperature, maxTokens) = params

            val request = AutoGlmRequest(
                model = model,
                messages = listOf(
                    AutoGlmRequest.Message(
                        role = "system",
                        content = buildSystemPrompt()
                    ),
                    AutoGlmRequest.Message(
                        role = "user",
                        content = "当前屏幕信息：$screenDescription\n\n用户指令：$command"
                    )
                ),
                maxTokens = maxTokens,
                temperature = temperature
            )

            val response = autoGlmApiService.chatCompletion(
                authorization = "Bearer $API_KEY",
                request = request
            )

            val content = response.choices?.firstOrNull()?.message?.content ?: "操作完成"
            Log.d(TAG, "AutoGLM返回: $content")

            tryParseAndExecute(content)
            Result.success(content)
        } catch (e: Exception) {
            Log.e(TAG, "AutoGLM带上下文命令执行失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 构建系统提示词，指导AutoGLM返回可执行的操作步骤
     */
    private fun buildSystemPrompt(): String {
        return """你是一个专为老年人服务的助老助手，名字叫"沪老"。你的核心原则是：最简单、最可靠的方案就是最好的。

【重要】场景化技能优先级：
当用户提到以下场景时，请严格遵循推荐的操作路径：

1. 【打车/叫车】
   - 首选方案：直接拨打助老打车热线 62580000（最快、最可靠）
   - 只有用户明确说"用软件打车"、"打开滴滴"、"用APP叫车"时，才打开滴滴或美团打车

2. 【紧急求助/救命】
   - 首选方案：拨打120急救电话
   - 如果情况严重，立即告知用户正在拨打120

3. 【联系子女】
   - 优先打开微信视频通话，而不是打电话
   - 如果微信不可用，再考虑直接拨号

4. 【日常操作】（打开微信、支付宝、拍照等）
   - 直接通过launch命令打开对应应用即可

【操作格式】
返回JSON格式，包含actions数组和description描述：
{
  "actions": [
    {"type": "click", "x": 0.5, "y": 0.3, "delay": 300},
    {"type": "swipe", "x": 0.5, "y": 0.7, "endX": 0.5, "endY": 0.3, "duration": 500},
    {"type": "type", "text": "输入内容"},
    {"type": "launch", "app": "com.tencent.mm"},
    {"type": "dial", "number": "62580000"},
    {"type": "back"},
    {"type": "home"},
    {"type": "scroll", "direction": "up"},
    {"type": "long_click", "x": 0.5, "y": 0.3, "duration": 500}
  ],
  "description": "正在为您执行操作"
}

【技术说明】
- 坐标使用相对值(0-1)，0表示左/上边缘，1表示右/下边缘
- type类型：click(点击)、swipe(滑动)、type(输入文本)、launch(打开应用)、dial(拨打电话号码)、back(返回)、home(主页)、scroll(滚动)、long_click(长按)
- 每个操作之间默认间隔300ms
- dial类型直接拨打指定号码，无需用户确认
- 如果无法确定操作步骤，请在description中说明原因并给出建议

【核心理念】
对老年人来说，最简单、最直接的操作就是最好的操作。不要过度复杂化，能一个电话解决的事就不要打开APP。"""
    }

    /**
     * 尝试解析AutoGLM返回的JSON并执行操作步骤
     */
    private fun tryParseAndExecute(content: String): Boolean {
        return try {
            // 尝试从返回内容中提取JSON
            val jsonStart = content.indexOf("{")
            val jsonEnd = content.lastIndexOf("}") + 1
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                Log.w(TAG, "AutoGLM返回内容中没有找到JSON操作步骤")
                return false
            }

            val jsonStr = content.substring(jsonStart, jsonEnd)
            val actionResponse = gson.fromJson(jsonStr, AutoGlmActionResponse::class.java)

            if (actionResponse.actions.isEmpty()) {
                Log.w(TAG, "AutoGLM返回的操作步骤为空")
                return false
            }

            // 在后台线程中逐步执行操作
            Thread {
                for (action in actionResponse.actions) {
                    executeAction(action)
                    Thread.sleep(action.delay)
                }
            }.start()

            Log.d(TAG, "成功解析并开始执行${actionResponse.actions.size}个操作步骤")
            true
        } catch (e: Exception) {
            Log.w(TAG, "解析AutoGLM操作步骤失败: ${e.message}")
            false
        }
    }

    /**
     * 执行单个操作步骤
     */
    private fun executeAction(action: AutoGlmActionStep) {
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
                    // launch需要通过Intent在主线程执行，这里只记录
                    Log.d(TAG, "需要打开应用: ${action.app}")
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
                "dial" -> {
                    if (action.number.isNotEmpty()) {
                        val intent = Intent(Intent.ACTION_CALL).apply {
                            data = Uri.parse("tel:${action.number}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        Log.d(TAG, "执行拨号: ${action.number}")
                    } else {
                        Log.w(TAG, "拨号号码为空")
                    }
                }
                else -> {
                    Log.w(TAG, "未知操作类型: ${action.type}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行操作失败: ${action.type}", e)
        }
    }
}
