package com.ailaohu.service.capture

import android.graphics.Bitmap
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull

class ScreenCaptureManagerTest {

    @MockK
    private lateinit var mockBitmap: Bitmap

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
    }

    @Test
    fun `test bitmapToBase64 with valid bitmap`() {
        // 模拟Bitmap的compress方法
        every { mockBitmap.compress(any(), any(), any()) } returns true

        val result = ScreenCaptureManager.bitmapToBase64(mockBitmap)
        assertNotNull(result)
    }

    @Test
    fun `test getScreenshotBase64 when service not running`() {
        // 模拟服务未运行
        ScreenCaptureService.isRunning = false

        val result = ScreenCaptureManager.getScreenshotBase64()
        assertNull(result)
    }

    @Test
    fun `test getScreenshotBase64 when service instance is null`() {
        // 模拟服务运行但实例为null
        ScreenCaptureService.isRunning = true
        // ScreenCaptureService.getRunningInstance() 默认为null

        val result = ScreenCaptureManager.getScreenshotBase64()
        assertNull(result)
    }
}
