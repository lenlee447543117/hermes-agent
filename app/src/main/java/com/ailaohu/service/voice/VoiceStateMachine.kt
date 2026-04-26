package com.ailaohu.service.voice

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ailaohu.service.audio.AudioManagerHelper
import com.ailaohu.service.tts.TTSManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

enum class VoicePipelineState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    EXECUTING
}

data class VoicePipelineEvent(
    val state: VoicePipelineState,
    val message: String = ""
)

@Singleton
class VoiceStateMachine @Inject constructor(
    private val ttsManager: TTSManager,
    private val audioManagerHelper: AudioManagerHelper
) {
    companion object {
        private const val TAG = "VoiceStateMachine"
        private const val CONTINUOUS_DIALOG_TIMEOUT_MS = 10000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _pipelineState = MutableStateFlow(VoicePipelineState.IDLE)
    val pipelineState: StateFlow<VoicePipelineState> = _pipelineState.asStateFlow()

    private val _pipelineEvent = MutableStateFlow(VoicePipelineEvent(VoicePipelineState.IDLE))
    val pipelineEvent: StateFlow<VoicePipelineEvent> = _pipelineEvent.asStateFlow()

    private var onReadyToListen: (() -> Unit)? = null
    private var pendingListenAfterSpeak = false
    private var continuousDialogEnabled = false
    private var continuousDialogCallback: (() -> Unit)? = null
    private var continuousDialogTimeoutRunnable: Runnable? = null

    fun currentState(): VoicePipelineState = _pipelineState.value

    fun setContinuousDialogEnabled(enabled: Boolean, listenCallback: (() -> Unit)? = null) {
        continuousDialogEnabled = enabled
        continuousDialogCallback = listenCallback
        Log.d(TAG, "连续对话模式: $enabled")
    }

    fun transitionTo(newState: VoicePipelineState, message: String = "") {
        val oldState = _pipelineState.value
        if (!isValidTransition(oldState, newState)) {
            Log.w(TAG, "Invalid transition: $oldState -> $newState")
            return
        }
        Log.d(TAG, "State transition: $oldState -> $newState${if (message.isNotEmpty()) " ($message)" else ""}")
        _pipelineState.value = newState
        _pipelineEvent.value = VoicePipelineEvent(newState, message)

        when (newState) {
            VoicePipelineState.LISTENING -> {
                audioManagerHelper.requestExclusiveFocus()
                cancelContinuousDialogTimeout()
            }
            VoicePipelineState.SPEAKING -> {
                audioManagerHelper.abandonFocus()
            }
            VoicePipelineState.IDLE -> {
                audioManagerHelper.abandonFocus()
                if (pendingListenAfterSpeak) {
                    pendingListenAfterSpeak = false
                    val callback = onReadyToListen
                    onReadyToListen = null
                    callback?.let { mainHandler.post(it) }
                } else if (continuousDialogEnabled && continuousDialogCallback != null) {
                    scheduleContinuousDialogTimeout()
                }
            }
            else -> {}
        }
    }

    fun speakAndWait(text: String, onComplete: (() -> Unit)? = null) {
        if (_pipelineState.value == VoicePipelineState.LISTENING) {
            Log.w(TAG, "Cannot speak while listening")
            return
        }
        transitionTo(VoicePipelineState.SPEAKING, text)
        ttsManager.speakWithCallback(text) {
            mainHandler.post {
                if (_pipelineState.value == VoicePipelineState.SPEAKING) {
                    transitionTo(VoicePipelineState.IDLE, "speak_done")
                }
                onComplete?.invoke()
            }
        }
    }

    suspend fun speakAndAwait(text: String) {
        suspendCancellableCoroutine<Unit> { cont ->
            speakAndWait(text) {
                cont.resume(Unit)
            }
        }
    }

    fun speakThenListen(text: String, listenCallback: () -> Unit) {
        if (_pipelineState.value == VoicePipelineState.LISTENING) {
            Log.w(TAG, "Already listening, skip speak")
            return
        }
        onReadyToListen = listenCallback
        pendingListenAfterSpeak = true
        transitionTo(VoicePipelineState.SPEAKING, text)
        ttsManager.speakWithCallback(text) {
            mainHandler.post {
                if (_pipelineState.value == VoicePipelineState.SPEAKING) {
                    transitionTo(VoicePipelineState.IDLE, "speak_done_then_listen")
                }
            }
        }
    }

    fun enqueueStepFeedback(text: String) {
        if (_pipelineState.value != VoicePipelineState.EXECUTING) return
        ttsManager.enqueue(text)
    }

    fun requestListen(callback: () -> Unit) {
        when (_pipelineState.value) {
            VoicePipelineState.IDLE -> {
                callback()
            }
            VoicePipelineState.SPEAKING -> {
                ttsManager.stop()
                onReadyToListen = callback
                pendingListenAfterSpeak = true
                transitionTo(VoicePipelineState.IDLE, "interrupted_for_listen")
            }
            VoicePipelineState.EXECUTING -> {
                onReadyToListen = callback
                pendingListenAfterSpeak = true
            }
            else -> {
                Log.w(TAG, "Cannot request listen in state: ${_pipelineState.value}")
            }
        }
    }

    fun cancelPendingListen() {
        pendingListenAfterSpeak = false
        onReadyToListen = null
    }

    private fun scheduleContinuousDialogTimeout() {
        cancelContinuousDialogTimeout()
        val callback = continuousDialogCallback ?: return
        continuousDialogTimeoutRunnable = Runnable {
            if (_pipelineState.value == VoicePipelineState.IDLE && continuousDialogEnabled) {
                Log.d(TAG, "连续对话: 自动重新开麦")
                mainHandler.post { callback.invoke() }
                scheduleContinuousDialogTimeout()
            }
        }
        mainHandler.postDelayed(continuousDialogTimeoutRunnable!!, CONTINUOUS_DIALOG_TIMEOUT_MS)
    }

    private fun cancelContinuousDialogTimeout() {
        continuousDialogTimeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        continuousDialogTimeoutRunnable = null
    }

    private fun isValidTransition(from: VoicePipelineState, to: VoicePipelineState): Boolean {
        return when (from) {
            VoicePipelineState.IDLE -> to == VoicePipelineState.LISTENING || to == VoicePipelineState.SPEAKING || to == VoicePipelineState.PROCESSING
            VoicePipelineState.LISTENING -> to == VoicePipelineState.PROCESSING || to == VoicePipelineState.IDLE || to == VoicePipelineState.SPEAKING
            VoicePipelineState.PROCESSING -> to == VoicePipelineState.SPEAKING || to == VoicePipelineState.EXECUTING || to == VoicePipelineState.IDLE
            VoicePipelineState.SPEAKING -> to == VoicePipelineState.IDLE || to == VoicePipelineState.LISTENING
            VoicePipelineState.EXECUTING -> to == VoicePipelineState.SPEAKING || to == VoicePipelineState.IDLE
        }
    }

    fun reset() {
        ttsManager.stop()
        audioManagerHelper.abandonFocus()
        cancelContinuousDialogTimeout()
        continuousDialogEnabled = false
        continuousDialogCallback = null
        pendingListenAfterSpeak = false
        onReadyToListen = null
        _pipelineState.value = VoicePipelineState.IDLE
    }
}
