package com.ailaohu.service.dialect

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

enum class InteractionMode {
    COMMAND,
    CHAT
}

@Singleton
class InteractionModeDetector @Inject constructor(
    private val dialectManager: DialectManager
) {
    companion object {
        private const val TAG = "ModeDetector"

        private val COMMAND_KEYWORDS = listOf(
            "打电话", "打视频", "打语音", "微信", "叫车", "打车",
            "打开", "关闭", "调大", "调小", "设置", "闹钟",
            "拍照", "录像", "发短信", "发消息", "医保码",
            "手电筒", "蓝牙", "WiFi", "天气", "新闻",
            "救命", "帮帮我", "紧急", "120", "110",
            "提醒我", "吃药", "位置", "搜索"
        )

        private val CHAT_KEYWORDS = listOf(
            "你好", "嗨", "早上好", "晚安", "谢谢",
            "侬好", "今朝好", "再会", "聊聊天",
            "几点了", "今天几号", "什么日子",
            "你叫什么", "你是谁", "你多大了",
            "讲个故事", "说说", "怎么样",
            "开心", "难过", "无聊", "想聊天",
            "最近怎么样", "身体好不好"
        )
    }

    fun detectMode(text: String): InteractionMode {
        val normalizedText = text.trim().lowercase()

        val commandScore = COMMAND_KEYWORDS.count { normalizedText.contains(it) }
        val chatScore = CHAT_KEYWORDS.count { normalizedText.contains(it) }

        if (normalizedText.contains("打") && (normalizedText.contains("给") || normalizedText.contains("电话") || normalizedText.contains("视频"))) {
            Log.d(TAG, "Strong command signal detected: call intent")
            return InteractionMode.COMMAND
        }

        if (normalizedText.length <= 4 && chatScore > 0) {
            Log.d(TAG, "Short chat-like input detected")
            return InteractionMode.CHAT
        }

        if (normalizedText.contains("聊") || normalizedText.contains("讲") || normalizedText.contains("说")) {
            val hasActionTarget = normalizedText.contains("给") && normalizedText.contains("打")
            if (!hasActionTarget) {
                Log.d(TAG, "Chat intent detected: conversational keywords")
                return InteractionMode.CHAT
            }
        }

        val mode = if (commandScore > chatScore) InteractionMode.COMMAND
        else if (chatScore > commandScore) InteractionMode.CHAT
        else if (normalizedText.length > 10) InteractionMode.COMMAND
        else InteractionMode.CHAT

        Log.d(TAG, "Mode detected: $mode (command=$commandScore, chat=$chatScore, text='$normalizedText')")
        return mode
    }

    fun detectDialectAndMode(text: String): Pair<String, InteractionMode> {
        val dialect = dialectManager.detectDialect(text)
        val mode = detectMode(text)
        return Pair(dialect, mode)
    }
}
