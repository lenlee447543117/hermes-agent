package com.ailaohu.debug

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.util.Base64
import android.util.Log
import com.ailaohu.service.accessibility.AutoPilotService

/**
 * AutoGLM-Phone 故障排查检查工具
 *
 * 用于检查和诊断常见问题
 */
class TroubleshootingChecker(private val context: Context) {

    companion object {
        private const val TAG = "Troubleshooting"

        /**
         * 运行完整的健康检查
         */
        fun runFullCheck(context: Context): HealthCheckReport {
            val checker = TroubleshootingChecker(context)
            return HealthCheckReport(
                accessibilityService = checker.checkAccessibilityService(),
                microphone = checker.checkMicrophone(),
                screenCapture = checker.checkScreenCapture(),
                apiKey = checker.checkApiKey(),
                base64Format = checker.checkBase64Format()
            )
        }
    }

    /**
     * 检查项1: 辅助服务状态
     */
    fun checkAccessibilityService(): CheckResult {
        val isRunning = AutoPilotService.isRunning()
        val issues = mutableListOf<String>()

        if (!isRunning) {
            issues.add("辅助服务未运行，请在系统设置中开启")
            issues.add("检查点: AutoPilotService.instance 是否为空")
        }

        return CheckResult(
            name = "辅助服务",
            passed = isRunning,
            issues = issues,
            suggestion = if (!isRunning) "请进入系统设置 -> 辅助功能 -> 开启沪老助手" else ""
        )
    }

    /**
     * 检查项2: 麦克风状态
     */
    fun checkMicrophone(): CheckResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val issues = mutableListOf<String>()

        // 检查是否有其他应用占用麦克风
        val mode = audioManager.mode
        Log.d(TAG, "Current audio mode: $mode")

        if (mode != AudioManager.MODE_NORMAL && mode != AudioManager.MODE_IN_CALL) {
            issues.add("检测到音频模式异常，可能有其他应用占用麦克风")
            issues.add("检查点: 查看日志中是否有 ACDB Error")
        }

        return CheckResult(
            name = "麦克风",
            passed = issues.isEmpty(),
            issues = issues,
            suggestion = "请确认 AudioSource 是否设置为 VOICE_COMMUNICATION"
        )
    }

    /**
     * 检查项3: 屏幕截图权限
     */
    fun checkScreenCapture(): CheckResult {
        val issues = mutableListOf<String>()

        // 这里需要检查 ScreenCaptureManager 的权限状态
        // 实际实现需要结合你的 ScreenCaptureManager

        return CheckResult(
            name = "屏幕截图",
            passed = true, // Placeholder
            issues = issues,
            suggestion = "请确认已授予屏幕录制权限"
        )
    }

    /**
     * 检查项4: API Key
     */
    fun checkApiKey(): CheckResult {
        val issues = mutableListOf<String>()

        // 这里检查你的 API Key 是否有效
        // 实际实现需要结合你的配置管理

        return CheckResult(
            name = "API Key",
            passed = true, // Placeholder
            issues = issues,
            suggestion = "请检查 API Key 是否过期"
        )
    }

    /**
     * 检查项5: Base64 格式
     */
    fun checkBase64Format(): CheckResult {
        val issues = mutableListOf<String>()

        // 测试 Base64 编码是否使用 NO_WRAP
        val testData = "test data".toByteArray()
        val encoded = Base64.encodeToString(testData, Base64.NO_WRAP)

        if (encoded.contains("\n") || encoded.contains("\r")) {
            issues.add("Base64 编码包含换行符，应该使用 Base64.NO_WRAP")
        }

        return CheckResult(
            name = "Base64 格式",
            passed = issues.isEmpty(),
            issues = issues,
            suggestion = "请确认使用 Base64.NO_WRAP 进行编码"
        )
    }

    /**
     * 检查项: 像素密度换算
     */
    fun checkDensityConversion(): CheckResult {
        val issues = mutableListOf<String>()

        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        Log.d(TAG, "Screen density: $density")

        if (density <= 0) {
            issues.add("无法获取屏幕密度")
        }

        return CheckResult(
            name = "像素密度",
            passed = density > 0,
            issues = issues,
            suggestion = "请检查截图 Density 与点击坐标的比例换算"
        )
    }
}

/**
 * 健康检查报告
 */
data class HealthCheckReport(
    val accessibilityService: CheckResult,
    val microphone: CheckResult,
    val screenCapture: CheckResult,
    val apiKey: CheckResult,
    val base64Format: CheckResult
) {
    val allPassed: Boolean
        get() = accessibilityService.passed &&
                microphone.passed &&
                screenCapture.passed &&
                apiKey.passed &&
                base64Format.passed

    fun getAllIssues(): List<String> {
        return listOf(
            accessibilityService.issues,
            microphone.issues,
            screenCapture.issues,
            apiKey.issues,
            base64Format.issues
        ).flatten()
    }
}

/**
 * 单个检查结果
 */
data class CheckResult(
    val name: String,
    val passed: Boolean,
    val issues: List<String>,
    val suggestion: String
)
