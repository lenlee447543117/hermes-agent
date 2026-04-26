package com.ailaohu.domain.model

data class AutomationStep(
    val id: String,
    val name: String,
    val action: StepAction,
    val targetDescription: String,
    val delayAfterMs: Long = 1000L,
    val retryCount: Int = 2,
    val fallbackAction: StepAction? = null
)

enum class StepAction {
    LAUNCH_APP,
    VLM_CLICK,
    NODE_CLICK,
    INPUT_TEXT,
    WAIT,
    CHECK_STATE
}
