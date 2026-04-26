package com.ailaohu.service.voice

import android.util.Log
import com.ailaohu.service.tts.TTSManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class TaskStep(
    val index: Int,
    val description: String,
    val isCompleted: Boolean = false
)

data class MultiStepTaskState(
    val isActive: Boolean = false,
    val taskDescription: String = "",
    val steps: List<TaskStep> = emptyList(),
    val currentStepIndex: Int = 0,
    val isWaitingForConfirmation: Boolean = false,
    val confirmationPrompt: String = ""
)

@Singleton
class MultiStepTaskHandler @Inject constructor(
    private val ttsManager: TTSManager,
    private val voiceStateMachine: VoiceStateMachine
) {
    companion object {
        private const val TAG = "MultiStepTask"
        private const val AUTO_RESUME_DELAY_MS = 5000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _taskState = MutableStateFlow(MultiStepTaskState())
    val taskState: StateFlow<MultiStepTaskState> = _taskState.asStateFlow()

    fun startMultiStepTask(description: String, steps: List<String>, onStepExecute: (Int, String) -> Unit) {
        val taskSteps = steps.mapIndexed { index, desc ->
            TaskStep(index = index, description = desc)
        }

        _taskState.value = MultiStepTaskState(
            isActive = true,
            taskDescription = description,
            steps = taskSteps,
            currentStepIndex = 0
        )

        voiceStateMachine.speakThenListen("好的，我来帮您$description，共${steps.size}步操作") {
            executeStep(0, onStepExecute)
        }
    }

    private fun executeStep(stepIndex: Int, onStepExecute: (Int, String) -> Unit) {
        val state = _taskState.value
        if (!state.isActive || stepIndex >= state.steps.size) {
            completeTask()
            return
        }

        val step = state.steps[stepIndex]
        _taskState.value = state.copy(currentStepIndex = stepIndex)

        scope.launch {
            voiceStateMachine.speakAndAwait("第${stepIndex + 1}步，${step.description}")
            onStepExecute(stepIndex, step.description)
            markStepCompleted(stepIndex)

            if (stepIndex + 1 < state.steps.size) {
                askForContinuation(stepIndex + 1)
            } else {
                completeTask()
            }
        }
    }

    private fun markStepCompleted(stepIndex: Int) {
        val state = _taskState.value
        val updatedSteps = state.steps.mapIndexed { index, step ->
            if (index == stepIndex) step.copy(isCompleted = true) else step
        }
        _taskState.value = state.copy(steps = updatedSteps)
    }

    private fun askForContinuation(nextStepIndex: Int) {
        val state = _taskState.value
        _taskState.value = state.copy(isWaitingForConfirmation = true)

        voiceStateMachine.speakThenListen("这一步完成了，要继续下一步吗？") {
            _taskState.value = _taskState.value.copy(isWaitingForConfirmation = false)
        }
    }

    fun onUserConfirm(onStepExecute: (Int, String) -> Unit) {
        val state = _taskState.value
        if (!state.isWaitingForConfirmation) return

        _taskState.value = state.copy(isWaitingForConfirmation = false)
        val nextIndex = state.currentStepIndex + 1
        executeStep(nextIndex, onStepExecute)
    }

    fun onUserCancel() {
        val state = _taskState.value
        if (!state.isWaitingForConfirmation) return

        _taskState.value = state.copy(isWaitingForConfirmation = false)
        scope.launch {
            voiceStateMachine.speakAndAwait("好的，已停止操作")
            cancelTask()
        }
    }

    private fun completeTask() {
        val state = _taskState.value
        scope.launch {
            voiceStateMachine.speakAndAwait("${state.taskDescription}已全部完成")
            _taskState.value = MultiStepTaskState()
        }
    }

    fun cancelTask() {
        _taskState.value = MultiStepTaskState()
    }

    fun isTaskActive(): Boolean = _taskState.value.isActive

    fun decomposeTask(command: String): List<String>? {
        val multiStepKeywords = mapOf(
            Regex("(给|跟|和)(.+?)(打视频|打微信|微信视频)") to listOf("打开微信", "搜索联系人", "发起视频通话"),
            Regex("(给|跟|和)(.+?)(打电话|语音通话)") to listOf("打开电话", "搜索联系人", "发起通话"),
            Regex("(打车|叫车|网约车)") to listOf("打开打车应用", "确认上车位置", "呼叫车辆"),
            Regex("(发短信|发消息)(给|跟)(.+)") to listOf("打开短信", "选择联系人", "输入内容并发送"),
            Regex("(设置|设个)(闹钟|提醒)") to listOf("打开时钟应用", "设置时间", "保存闹钟"),
            Regex("(买|订购)(.+?)(火车票|机票)") to listOf("打开购票应用", "搜索车次/航班", "选择座位并支付")
        )

        for ((pattern, steps) in multiStepKeywords) {
            if (pattern.containsMatchIn(command)) {
                return steps
            }
        }
        return null
    }
}
