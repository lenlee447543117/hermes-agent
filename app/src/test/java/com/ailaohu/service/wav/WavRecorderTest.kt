package com.ailaohu.service.wav

import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * 截图性能测试
 * 要求：从用户松手到获取 Bitmap 必须在 500ms 内完成
 */
class WavRecorderTest {

    private lateinit var wavRecorder: WavRecorder

    @Before
    fun setUp() {
        wavRecorder = WavRecorder()
    }

    /**
     * 测试WAV文件格式是否正确
     */
    @Test
    fun `WAV Header - creates valid WAV header`() {
        // Arrange - Mock audio data
        val audioData = ByteArray(48000) // 3 seconds of 16kHz audio

        // Act
        val wavData = WavRecorder.createWavFile(audioData, 16000, 1, 16)

        // Assert
        assertTrue(wavData.size > 44) // Header should be at least 44 bytes
        assertTrue(wavData[0] == 'R'.code.toByte())
        assertTrue(wavData[1] == 'I'.code.toByte())
        assertTrue(wavData[2] == 'F'.code.toByte())
        assertTrue(wavData[3] == 'F'.code.toByte())
    }

    /**
     * 性能测试：录音开始的延迟
     */
    @Test
    fun `Performance - recording initialization latency`() {
        val startTime = System.currentTimeMillis()

        // Act
        wavRecorder.start()

        val endTime = System.currentTimeMillis()
        val latency = endTime - startTime

        println("Recording initialization latency: ${latency}ms")

        // Assert - Should initialize quickly
        assertTrue(latency < 100, "Recording initialization should complete in under 100ms")
    }

    /**
     * 测试停止录音并获取WAV的延迟
     */
    @Test
    fun `Performance - stop and get WAV latency`() {
        // Arrange
        wavRecorder.start()
        Thread.sleep(100) // Record for short time

        val startTime = System.currentTimeMillis()

        // Act
        val wavFile = wavRecorder.stopAndGetWav()

        val endTime = System.currentTimeMillis()
        val latency = endTime - startTime

        println("Stop and get WAV latency: ${latency}ms")

        // Assert - Should complete quickly
        assertTrue(latency < 200, "Stop and get WAV should complete in under 200ms")
    }

    /**
     * 清理测试文件
     */
    @org.junit.After
    fun tearDown() {
        try {
            wavRecorder.stopAndGetWav()?.delete()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
}
