package com.ailaohu.domain.model

enum class UserIntent(val displayName: String) {
    VIDEO_CALL("视频通话"),
    VOICE_CALL("语音通话"),
    CALL_TAXI("打车"),
    UNKNOWN("未知")
}
