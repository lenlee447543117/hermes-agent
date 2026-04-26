package com.ailaohu.service.voice

import android.util.Log
import com.ailaohu.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// 智谱ASR返回结果
sealed class ZhipuAsrResult {
    data class Success(val text: String) : ZhipuAsrResult()
    data class Error(val message: String, val isBalanceLow: Boolean = false) : ZhipuAsrResult()
}

@Singleton
class ZhipuAsrService @Inject constructor() {
    companion object {
        private const val TAG = "ZhipuAsr"
        private const val API_URL = "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions"
        private const val MODEL = "glm-asr"
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 500L
        private val API_KEY: String get() = BuildConfig.ZHIPU_API_KEY
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val fallbackClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun transcribe(audioFile: File): ZhipuAsrResult {
        return withContext(Dispatchers.IO) {
            var lastException: Exception? = null

            for (attempt in 0..MAX_RETRIES) {
                try {
                    val result = doTranscribe(audioFile, useFallback = attempt > 0)
                    if (result != null) return@withContext result
                } catch (e: IOException) {
                    lastException = e
                    Log.w(TAG, "ASR请求失败(第${attempt + 1}次): ${e.message}")
                    if (attempt < MAX_RETRIES) {
                        kotlinx.coroutines.delay(RETRY_DELAY_MS * (attempt + 1))
                    }
                } catch (e: Exception) {
                    lastException = e
                    Log.e(TAG, "ASR异常(第${attempt + 1}次)", e)
                    break
                }
            }

            Log.e(TAG, "ASR所有重试失败", lastException)
            ZhipuAsrResult.Error("语音识别服务暂时不可用，请稍后再试")
        }
    }

    private fun doTranscribe(audioFile: File, useFallback: Boolean): ZhipuAsrResult? {
        val currentClient = if (useFallback) fallbackClient else client

        Log.d(TAG, "发送ASR请求: 文件=${audioFile.name}, 大小=${audioFile.length()}字节")
        Log.d(TAG, "API URL: $API_URL")
        Log.d(TAG, "Model: $MODEL")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart(
                "file", audioFile.name,
                audioFile.asRequestBody("audio/mpeg".toMediaTypeOrNull())
            )
            .addFormDataPart("stream", "false")
            .build()

        val request = Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer $API_KEY")
            .post(requestBody)
            .build()

        currentClient.newCall(request).execute().use { response ->
            val resBody = response.body?.string()
            Log.d(TAG, "ASR响应: code=${response.code}, body=$resBody")

            if (!response.isSuccessful) {
                Log.e(TAG, "ASR请求失败: code=${response.code}, body=$resBody")
                
                // 检测余额不足的情况
                val isBalanceLow = resBody?.contains("余额不足") == true 
                    || resBody?.contains("1113") == true 
                    || resBody?.contains("无可用资源包") == true
                
                if (isBalanceLow) {
                    Log.e(TAG, "检测到智谱API余额不足")
                    return ZhipuAsrResult.Error(
                        message = "语音识别服务余额不足，请联系管理员充值",
                        isBalanceLow = true
                    )
                }
                
                if (response.code in 500..599) {
                    throw IOException("服务端错误: ${response.code}")
                }
                return null
            }

            val json = JSONObject(resBody ?: "")
            Log.d(TAG, "完整JSON响应: $json")
            
            // 尝试多种可能的字段名
            var text = json.optString("text", "")
            if (text.isEmpty()) {
                text = json.optString("data", "")
            }
            if (text.isEmpty() && json.has("result")) {
                text = json.optString("result", "")
            }
            
            return if (text.isNotEmpty()) {
                Log.d(TAG, "ASR识别成功: $text")
                ZhipuAsrResult.Success(text)
            } else {
                Log.w(TAG, "ASR返回空文本，响应JSON: $json")
                null
            }
        }
    }
}
