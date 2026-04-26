package com.ailaohu.service.vla

import android.graphics.Bitmap
import android.util.Log
import com.ailaohu.service.accessibility.AutoPilotService
import com.ailaohu.service.capture.ScreenCaptureManager
import com.ailaohu.service.privacy.PrivacyFilterService
import com.ailaohu.service.vlm.ScreenCoordinate
import com.ailaohu.service.vlm.VLMService
import com.ailaohu.util.BitmapUtils
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

enum class VlaExecutionState {
    IDLE,
    PARSING_INTENT,
    CAPTURING_SCREEN,
    FILTERING_PRIVACY,
    LOCATING_ELEMENT,
    EXECUTING_ACTION,
    VERIFYING_RESULT,
    COMPLETED,
    FAILED
}

data class VlaExecutionResult(
    val success: Boolean,
    val state: VlaExecutionState,
    val message: String,
    val retryCount: Int = 0
)

@Singleton
class VlaExecutionEngine @Inject constructor(
    private val vlmService: VLMService,
    private val screenCaptureManager: ScreenCaptureManager,
    private val privacyFilterService: PrivacyFilterService
) {
    companion object {
        private const val TAG = "VlaEngine"
        private const val MAX_RETRIES = 3
        private const val ACTION_DELAY_MIN = 300L
        private const val ACTION_DELAY_MAX = 600L
        private const val VERIFICATION_DELAY = 1500L
    }

    private var currentState = VlaExecutionState.IDLE

    suspend fun executeWithVla(
        intent: String,
        targetElement: String,
        action: VlaAction,
        context: String = ""
    ): VlaExecutionResult {
        Log.i(TAG, "VLA execution started: intent=$intent, target=$targetElement")

        try {
            currentState = VlaExecutionState.PARSING_INTENT
            val prompt = com.ailaohu.service.vlm.PromptBuilder.buildFindElementPrompt(targetElement)

            currentState = VlaExecutionState.CAPTURING_SCREEN
            val rawBitmap = screenCaptureManager.captureScreen()
                ?: return VlaExecutionResult(false, currentState, "截屏失败")

            currentState = VlaExecutionState.FILTERING_PRIVACY
            val filteredBitmap = privacyFilterService.filterScreenshot(rawBitmap)

            currentState = VlaExecutionState.LOCATING_ELEMENT
            val base64Image = BitmapUtils.bitmapToBase64(filteredBitmap, 720, 70)
            val coordinate = vlmService.findElement(base64Image, prompt)
                ?: return VlaExecutionResult(false, currentState, "VLM未找到目标元素: $targetElement")

            val calibratedCoord = calibrateCoordinate(coordinate)
            Log.d(TAG, "Element located at (${calibratedCoord.x}, ${calibratedCoord.y})")

            currentState = VlaExecutionState.EXECUTING_ACTION
            val randomDelay = (ACTION_DELAY_MIN..ACTION_DELAY_MAX).random()
            delay(randomDelay)
            executeAction(action, calibratedCoord)

            currentState = VlaExecutionState.VERIFYING_RESULT
            delay(VERIFICATION_DELAY)
            val verified = verifyExecution(intent, targetElement)

            currentState = if (verified) VlaExecutionState.COMPLETED else VlaExecutionState.FAILED
            return VlaExecutionResult(
                success = verified,
                state = currentState,
                message = if (verified) "执行成功" else "执行结果验证失败"
            )

        } catch (e: Exception) {
            Log.e(TAG, "VLA execution error: ${e.message}", e)
            currentState = VlaExecutionState.FAILED
            return VlaExecutionResult(false, currentState, "执行异常: ${e.message}")
        } finally {
            currentState = VlaExecutionState.IDLE
        }
    }

    suspend fun executeWithRetry(
        intent: String,
        targetElement: String,
        action: VlaAction,
        maxRetries: Int = MAX_RETRIES
    ): VlaExecutionResult {
        var lastResult: VlaExecutionResult = VlaExecutionResult(false, VlaExecutionState.IDLE, "未执行")

        repeat(maxRetries) { attempt ->
            lastResult = executeWithVla(intent, targetElement, action)
            if (lastResult.success) {
                Log.i(TAG, "VLA execution succeeded on attempt ${attempt + 1}")
                return lastResult.copy(retryCount = attempt)
            }

            Log.w(TAG, "VLA execution failed on attempt ${attempt + 1}: ${lastResult.message}")
            if (attempt < maxRetries - 1) {
                delay(1000L * (attempt + 1))
            }
        }

        return lastResult.copy(retryCount = maxRetries)
    }

    private fun calibrateCoordinate(coord: ScreenCoordinate): ScreenCoordinate {
        val displayMetrics = android.content.res.Resources.getSystem().displayMetrics
        val deviceWidth = displayMetrics.widthPixels
        val deviceHeight = displayMetrics.heightPixels
        val standardWidth = 1080

        val scaleX = deviceWidth.toFloat() / standardWidth
        val calibratedX = (coord.x * scaleX).coerceIn(0f, 1f)
        val calibratedY = coord.y.coerceIn(0f, 1f)

        return ScreenCoordinate(calibratedX, calibratedY)
    }

    private fun executeAction(action: VlaAction, coord: ScreenCoordinate) {
        when (action) {
            is VlaAction.Click -> {
                AutoPilotService.performClick(coord.x, coord.y)
                Log.d(TAG, "Click at (${coord.x}, ${coord.y})")
            }
            is VlaAction.LongClick -> {
                AutoPilotService.performLongClick(coord.x, coord.y)
                Log.d(TAG, "Long click at (${coord.x}, ${coord.y})")
            }
            is VlaAction.Swipe -> {
                AutoPilotService.performSwipe(
                    action.startX, action.startY,
                    action.endX, action.endY,
                    action.duration
                )
                Log.d(TAG, "Swipe from (${action.startX}, ${action.startY}) to (${action.endX}, ${action.endY})")
            }
            is VlaAction.TypeText -> {
                AutoPilotService.performClick(coord.x, coord.y)
                Thread.sleep(300)
                AutoPilotService.performTypeText(action.text)
                Log.d(TAG, "Type text: ${action.text}")
            }
        }
    }

    private suspend fun verifyExecution(intent: String, targetElement: String): Boolean {
        val bitmap = screenCaptureManager.captureScreen() ?: return false
        val base64Image = BitmapUtils.bitmapToBase64(bitmap, 720, 70)

        val verifyPrompt = "验证当前屏幕是否显示了与「$targetElement」相关的操作结果。返回JSON: {\"verified\": true/false}"
        val result = vlmService.findElement(base64Image, verifyPrompt)

        val screenInfo = AutoPilotService.getScreenInfo() ?: ""
        val hasExpectedContent = screenInfo.contains(targetElement) ||
                screenInfo.contains("呼叫中") ||
                screenInfo.contains("已接通") ||
                screenInfo.contains("搜索")

        return hasExpectedContent || result != null
    }

    fun getCurrentState(): VlaExecutionState = currentState
}

sealed class VlaAction {
    data class Click(val x: Float = 0f, val y: Float = 0f) : VlaAction()
    data class LongClick(val duration: Long = 500) : VlaAction()
    data class Swipe(
        val startX: Float, val startY: Float,
        val endX: Float, val endY: Float,
        val duration: Long = 300
    ) : VlaAction()
    data class TypeText(val text: String) : VlaAction()
}
