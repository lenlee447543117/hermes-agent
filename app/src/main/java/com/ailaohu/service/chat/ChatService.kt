package com.ailaohu.service.chat

import android.util.Log
import com.ailaohu.data.remote.dto.hermes.HermesChatRequest
import com.ailaohu.data.remote.dto.hermes.HermesChatMessage
import com.ailaohu.data.repository.HermesRepository
import com.ailaohu.data.local.prefs.AppPreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class ChatResult(
    val reply: String,
    val mode: String,
    val intent: String? = null,
    val targetPerson: String? = null
)

@Singleton
class ChatService @Inject constructor(
    private val hermesRepository: HermesRepository,
    private val appPreferences: AppPreferences
) {
    companion object {
        private const val TAG = "ChatService"
    }

    private val chatHistory = mutableListOf<HermesChatMessage>()
    private var lastSummaryTimestamp: Long = 0

    suspend fun chat(userMessage: String): ChatResult {
        val dialect = appPreferences.dialectMode.first()

        return try {
            val result = hermesRepository.chat(userMessage, dialect)

            result.fold(
                onSuccess = { response ->
                    chatHistory.add(HermesChatMessage(role = "user", content = userMessage))
                    chatHistory.add(HermesChatMessage(role = "assistant", content = response.reply))

                    if (chatHistory.size > 50) {
                        chatHistory.removeAt(0)
                        chatHistory.removeAt(0)
                    }

                    ChatResult(
                        reply = response.reply,
                        mode = response.mode,
                        intent = response.intent,
                        targetPerson = response.action_payload?.target_person
                    )
                },
                onFailure = { e ->
                    Log.w(TAG, "Hermes chat failed, using local fallback: ${e.message}")
                    localFallbackChat(userMessage, dialect)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Chat service error: ${e.message}", e)
            localFallbackChat(userMessage, appPreferences.dialectMode.first())
        }
    }

    private fun localFallbackChat(message: String, dialect: String): ChatResult {
        val reply = if (dialect == "shanghai") {
            when {
                message.contains("你好") || message.contains("嗨") -> "侬好呀！有啥事体伐？"
                message.contains("几点") -> "我帮侬看看辰光..."
                message.contains("天气") -> "今朝天气我帮侬查查..."
                message.contains("谢谢") -> "勿要客气，有啥需要随时叫我！"
                message.contains("再见") || message.contains("拜拜") -> "好额，有啥事体随时叫我，我一直在额！"
                else -> "嗯嗯，我听到侬讲额了。还有啥需要帮忙伐？"
            }
        } else {
            when {
                message.contains("你好") || message.contains("嗨") -> "您好！有什么需要帮忙的吗？"
                message.contains("几点") -> "我帮您看看时间..."
                message.contains("天气") -> "我帮您查查天气..."
                message.contains("谢谢") -> "不客气，有需要随时叫我！"
                message.contains("再见") || message.contains("拜拜") -> "好的，有需要随时叫我，我一直都在！"
                else -> "嗯嗯，我听到了。还有什么需要帮忙的吗？"
            }
        }

        return ChatResult(reply = reply, mode = "CHAT")
    }

    fun clearHistory() {
        chatHistory.clear()
    }

    fun getHistorySize(): Int = chatHistory.size
}
