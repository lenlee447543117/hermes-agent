package com.ailaohu.ui.home.mvi

import com.ailaohu.service.voice.VoicePipelineState

data class HomeViewState(
    val voiceState: VoicePipelineState = VoicePipelineState.IDLE,
    val recognizedText: String = "",
    val responseText: String = "",
    val isLoading: Boolean = false,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isExecuting: Boolean = false,
    val isEmergency: Boolean = false,
    val hasNewNotification: Boolean = false,
    val notificationCount: Int = 0,
    val hintMessage: String = "长按下方按钮，对我说您的需求",
    val dialectMode: String = "shanghai",
    val interactionMode: String = "COMMAND",
    val lastActionIntent: String? = null,
    val proactiveCareMessage: String? = null
)
