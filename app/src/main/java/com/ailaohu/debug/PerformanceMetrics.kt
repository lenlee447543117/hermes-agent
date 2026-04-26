package com.ailaohu.debug

import android.os.SystemClock
import android.util.Log

/**
 * AutoGLM-Phone 性能测量工具
 *
 * 用于测量响应时延：
 * - 端到端响应：≤ 3.5s
 * - 本地处理：≤ 800ms
 */
class PerformanceMetrics {

    companion object {
        private const val TAG = "Performance"

        // 性能阈值
        private const val MAX_END_TO_END_MS = 3500L
        private const val MAX_LOCAL_PROCESSING_MS = 800L
        private const val MAX_SCREENSHOT_MS = 500L
    }

    private val timestamps = mutableMapOf<String, Long>()
    private val measurements = mutableListOf<Measurement>()

    /**
     * 开始测量
     */
    fun startMeasurement(name: String): String {
        val id = "${name}_${System.currentTimeMillis()}"
        timestamps[id] = SystemClock.elapsedRealtime()
        Log.d(TAG, "Started measurement: $name (id: $id)")
        return id
    }

    /**
     * 结束测量并记录
     */
    fun endMeasurement(id: String): Long {
        val startTime = timestamps.remove(id) ?: return -1
        val endTime = SystemClock.elapsedRealtime()
        val duration = endTime - startTime

        val name = id.substringBeforeLast("_")
        measurements.add(Measurement(name, duration))

        Log.d(TAG, "Completed measurement: $name - ${duration}ms")

        // 检查是否超过阈值
        checkThresholds(name, duration)

        return duration
    }

    /**
     * 记录中间时间点
     */
    fun recordMilestone(milestoneId: String, description: String) {
        val time = SystemClock.elapsedRealtime()
        Log.d(TAG, "Milestone: $description at ${time}ms (id: $milestoneId)")
    }

    /**
     * 检查阈值
     */
    private fun checkThresholds(name: String, duration: Long) {
        when {
            name.contains("screenshot", ignoreCase = true) && duration > MAX_SCREENSHOT_MS -> {
                Log.w(TAG, "⚠️ 截图耗时过长: ${duration}ms (阈值: ${MAX_SCREENSHOT_MS}ms)")
            }
            name.contains("local", ignoreCase = true) && duration > MAX_LOCAL_PROCESSING_MS -> {
                Log.w(TAG, "⚠️ 本地处理耗时过长: ${duration}ms (阈值: ${MAX_LOCAL_PROCESSING_MS}ms)")
            }
            name.contains("end_to_end", ignoreCase = true) && duration > MAX_END_TO_END_MS -> {
                Log.w(TAG, "⚠️ 端到端响应耗时过长: ${duration}ms (阈值: ${MAX_END_TO_END_MS}ms)")
            }
        }
    }

    /**
     * 获取所有测量结果
     */
    fun getAllMeasurements(): List<Measurement> = measurements.toList()

    /**
     * 获取性能摘要
     */
    fun getSummary(): PerformanceSummary {
        val endToEndMeasurements = measurements.filter { it.name.contains("end_to_end", ignoreCase = true) }
        val localMeasurements = measurements.filter { it.name.contains("local", ignoreCase = true) }
        val screenshotMeasurements = measurements.filter { it.name.contains("screenshot", ignoreCase = true) }

        return PerformanceSummary(
            totalMeasurements = measurements.size,
            avgEndToEnd = if (endToEndMeasurements.isNotEmpty()) endToEndMeasurements.map { it.duration }.average() else 0.0,
            avgLocalProcessing = if (localMeasurements.isNotEmpty()) localMeasurements.map { it.duration }.average() else 0.0,
            avgScreenshot = if (screenshotMeasurements.isNotEmpty()) screenshotMeasurements.map { it.duration }.average() else 0.0,
            allMeasurements = measurements.toList()
        )
    }

    /**
     * 清除所有测量数据
     */
    fun clear() {
        timestamps.clear()
        measurements.clear()
    }
}

/**
 * 单次测量记录
 */
data class Measurement(
    val name: String,
    val duration: Long
)

/**
 * 性能摘要
 */
data class PerformanceSummary(
    val totalMeasurements: Int,
    val avgEndToEnd: Double,
    val avgLocalProcessing: Double,
    val avgScreenshot: Double,
    val allMeasurements: List<Measurement>
) {
    val isAcceptable: Boolean
        get() = avgEndToEnd <= 3500 &&
                avgLocalProcessing <= 800 &&
                avgScreenshot <= 500
}
