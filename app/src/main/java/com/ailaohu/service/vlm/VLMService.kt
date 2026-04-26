package com.ailaohu.service.vlm

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.ailaohu.BuildConfig
import com.ailaohu.data.remote.api.AutoGlmApiService
import com.ailaohu.data.remote.dto.AutoGlmRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VLMService @Inject constructor(
    private val autoGlmApiService: AutoGlmApiService,
    private val cacheManager: VLMCacheManager
) {
    companion object {
        private const val TAG = "VLMService"
        private val AUTOGLM_API_KEY: String get() = BuildConfig.ZHIPU_API_KEY
    }

    /**
     * 调用AutoGLM-Phone模型，发送语音指令和屏幕截图
     * @param command 用户语音指令
     * @param base64Image 屏幕截图的base64编码
     * @return 模型返回的内容
     */
    suspend fun executeAutoGLMPhone(
        command: String,
        base64Image: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "调用AutoGLM-Phone模型")
            
            // 构建AutoGLM-Phone请求
            val messages = mutableListOf<AutoGlmRequest.Message>().apply {
                // 系统提示
                add(
                    AutoGlmRequest.Message(
                        role = "system",
                        content = buildAutoGLMPhoneSystemPrompt()
                    )
                )
                
                // 用户消息 - 使用多模态格式
                val userContent = buildList {
                    // 先添加图片（如果有）
                    base64Image?.let {
                        add(
                            AutoGlmRequest.ContentPart(
                                type = "image_url",
                                imageUrl = AutoGlmRequest.ImageUrl(
                                    url = "data:image/jpeg;base64,$it"
                                )
                            )
                        )
                    }
                    // 再添加文本
                    add(
                        AutoGlmRequest.ContentPart(
                            type = "text",
                            text = command
                        )
                    )
                }
                
                add(AutoGlmRequest.Message(role = "user", content = userContent))
            }

            val request = AutoGlmRequest(
                model = "autoglm-phone",
                messages = messages,
                maxTokens = 2048,
                temperature = 0.7f
            )

            Log.d(TAG, "发送请求到AutoGLM-Phone，模型: autoglm-phone")
            
            // 调用API
            val response = autoGlmApiService.chatCompletion(
                authorization = "Bearer $AUTOGLM_API_KEY",
                request = request
            )

            // 解析响应
            val content = response.choices?.firstOrNull()?.message?.content
                ?: run {
                    Log.w(TAG, "AutoGLM-Phone响应为空")
                    return@withContext Result.failure(IllegalStateException("响应为空"))
                }

            Log.d(TAG, "AutoGLM-Phone返回: $content")
            Result.success(content)
        } catch (e: Exception) {
            Log.e(TAG, "AutoGLM-Phone调用失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 构建AutoGLM-Phone的系统提示词
     */
    private fun buildAutoGLMPhoneSystemPrompt(): String {
        return """你是一个专为老年人服务的助老手机助手，名字叫"沪老"。你的核心原则是：最简单、最可靠的方案就是最好的。

【功能说明】
你可以看到当前手机屏幕的画面，理解用户的语音指令，并自动规划和执行操作步骤。

【返回格式】
请返回JSON格式，包含actions数组和description描述：
{
  "actions": [
    {"type": "click", "x": 0.5, "y": 0.3, "delay": 300},
    {"type": "swipe", "x": 0.5, "y": 0.7, "endX": 0.5, "endY": 0.3, "duration": 500},
    {"type": "type", "text": "输入内容"},
    {"type": "launch", "app": "com.tencent.mm"},
    {"type": "dial", "number": "62580000"},
    {"type": "back"},
    {"type": "home"},
    {"type": "long_click", "x": 0.5, "y": 0.3, "duration": 500}
  ],
  "description": "正在为您执行操作"
}

【坐标说明】
- 坐标使用相对值(0-1)，0表示左/上边缘，1表示右/下边缘
- 如果屏幕截图中有要点击的按钮，直接点击按钮中心即可

【支持的操作】
- click(点击)
- swipe(滑动)
- type(输入文本)
- launch(打开应用，app参数为包名，如com.tencent.mm是微信)
- dial(拨打电话号码)
- back(返回上一页)
- home(回主页)
- long_click(长按)

【优先处理方案】
1. 打车首选方案：直接拨打助老打车热线 62580000
2. 紧急求助：拨打120急救电话
3. 联系子女：优先使用微信视频通话
4. 日常操作：最直接的方式完成

现在，请根据用户的语音指令和屏幕画面，规划并执行操作步骤。"""
    }

    /**
     * 在屏幕图像中查找指定的元素（兼容旧版功能）
     * @param base64Image 屏幕图像的base64编码
     * @param targetDescription 目标元素的描述
     * @return 元素的屏幕坐标（归一化坐标，范围0-1），如果未找到则返回null
     */
    suspend fun findElement(
        base64Image: String,
        targetDescription: String
    ): ScreenCoordinate? = withContext(Dispatchers.IO) {
        try {
            if (base64Image.isEmpty()) {
                Log.w(TAG, "空的base64图像数据")
                return@withContext null
            }
            
            // 检查缓存
            cacheManager.getCachedCoordinate(base64Image, targetDescription)?.let {
                Log.d(TAG, "缓存命中: $targetDescription")
                return@withContext it
            }

            // 使用AutoGLM进行查找
            val messages = mutableListOf<AutoGlmRequest.Message>().apply {
                add(
                    AutoGlmRequest.Message(
                        role = "system",
                        content = "你是一个手机屏幕元素定位助手。请根据用户提供的屏幕截图和描述，返回目标元素在屏幕上的坐标。"
                    )
                )
                val userContent = listOf(
                    AutoGlmRequest.ContentPart(
                        type = "image_url",
                        imageUrl = AutoGlmRequest.ImageUrl(
                            url = "data:image/jpeg;base64,$base64Image"
                        )
                    ),
                    AutoGlmRequest.ContentPart(
                        type = "text",
                        text = "请找到并返回这个元素的坐标：$targetDescription。只返回JSON格式：{\"x\":0.5,\"y\":0.5}"
                    )
                )
                add(AutoGlmRequest.Message(role = "user", content = userContent))
            }

            val request = AutoGlmRequest(
                model = "GLM-4.6V-Flash",
                messages = messages,
                maxTokens = 512
            )

            val response = autoGlmApiService.chatCompletion(
                authorization = "Bearer $AUTOGLM_API_KEY",
                request = request
            )

            val content = response.choices?.firstOrNull()?.message?.content ?: return@withContext null
            val json = JSONObject(content)

            val x = json.getDouble("x").toFloat()
            val y = json.getDouble("y").toFloat()
            
            // 验证坐标范围
            if (x < 0 || y < 0 || x > 1 || y > 1) {
                Log.w(TAG, "VLM返回的坐标超出范围: ($x, $y)")
                return@withContext null
            }
            
            // 缓存结果
            val coord = ScreenCoordinate(x, y, 1.0f)
            cacheManager.cacheCoordinate(base64Image, targetDescription, coord)
            Log.d(TAG, "VLM找到元素: $targetDescription → ($x, $y)")
            coord
        } catch (e: Exception) {
            Log.e(TAG, "查找元素失败: ${e.message}", e)
            null
        }
    }

    /**
     * 用于ExecuteWeChatCallUseCase的方法（兼容旧版功能）
     * 查找屏幕元素
     * @param prompt 查找提示
     * @return 元素坐标
     */
    suspend fun findScreenElement(prompt: String): ScreenCoordinate? {
        Log.w(TAG, "findScreenElement方法需要屏幕截图，请提供")
        return null
    }

    /**
     * 直接发送音频+截图给 AutoGLM-Phone
     * 这是最优方案，不需要本地 ASR
     * @param wavAudioData WAV 格式的音频数据（16kHz 单声道）
     * @param bitmap 屏幕截图
     * @return 模型返回的内容
     */
    suspend fun executeAudioAndImage(
        wavAudioData: ByteArray,
        bitmap: Bitmap
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "发送音频+截图给 AutoGLM-Phone")

            val base64Audio = Base64.encodeToString(wavAudioData, Base64.NO_WRAP)
            val base64Image = bitmapToBase64(bitmap)

            val messages = mutableListOf<AutoGlmRequest.Message>().apply {
                add(
                    AutoGlmRequest.Message(
                        role = "system",
                        content = buildAutoGLMPhoneSystemPrompt()
                    )
                )

                val userContent = buildList {
                    add(
                        AutoGlmRequest.ContentPart(
                            type = "input_audio",
                            inputAudio = AutoGlmRequest.InputAudio(
                                data = base64Audio,
                                format = "wav"
                            )
                        )
                    )
                    add(
                        AutoGlmRequest.ContentPart(
                            type = "image_url",
                            imageUrl = AutoGlmRequest.ImageUrl(
                                url = "data:image/jpeg;base64,$base64Image"
                            )
                        )
                    )
                }

                add(AutoGlmRequest.Message(role = "user", content = userContent))
            }

            val request = AutoGlmRequest(
                model = "autoglm-phone",
                messages = messages,
                maxTokens = 2048,
                temperature = 0.7f
            )

            Log.d(TAG, "发送请求到 AutoGLM-Phone")

            val response = autoGlmApiService.chatCompletion(
                authorization = "Bearer $AUTOGLM_API_KEY",
                request = request
            )

            val content = response.choices?.firstOrNull()?.message?.content
                ?: run {
                    Log.w(TAG, "AutoGLM-Phone 响应为空")
                    return@withContext Result.failure(IllegalStateException("响应为空"))
                }

            Log.d(TAG, "AutoGLM-Phone 返回: $content")
            Result.success(content)
        } catch (e: Exception) {
            Log.e(TAG, "AutoGLM-Phone 调用失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 将 Bitmap 转换为 base64
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
