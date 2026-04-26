package com.ailaohu.service.vlm

import com.ailaohu.data.remote.api.VlmApiService
import com.ailaohu.data.remote.dto.VlmContent
import com.ailaohu.data.remote.dto.VlmInput
import com.ailaohu.data.remote.dto.VlmMessage
import com.ailaohu.data.remote.dto.VlmOutput
import com.ailaohu.data.remote.dto.VlmRequest
import com.ailaohu.data.remote.dto.VlmResponse
import com.ailaohu.data.remote.dto.VlmChoice
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

class VLMServiceTest {

    @MockK
    private lateinit var vlmApiService: VlmApiService

    @MockK
    private lateinit var cacheManager: VLMCacheManager

    private lateinit var vlmService: VLMService

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        vlmService = VLMService(vlmApiService, cacheManager)
    }

    @Test
    fun `test findElement with valid response`() = runBlocking {
        // 模拟响应
        val mockResponse = VlmResponse(
            output = VlmOutput(
                choices = listOf(
                    VlmChoice(
                        message = VlmMessage(
                            content = listOf(
                                VlmContent(
                                    type = "text",
                                    text = "{\"x\": 0.5, \"y\": 0.5}"
                                )
                            )
                        )
                    )
                )
            )
        )

        coEvery { vlmApiService.analyzeScreen(any(), any()) } returns mockResponse
        coEvery { cacheManager.getCachedCoordinate(any(), any()) } returns null
        coEvery { cacheManager.cacheCoordinate(any(), any(), any()) } returns Unit

        val result = vlmService.findElement("base64Image", "test element")

        assertEquals(0.5f, result?.x)
        assertEquals(0.5f, result?.y)
    }

    @Test
    fun `test findElement with error response`() = runBlocking {
        // 模拟错误响应
        val mockResponse = VlmResponse(
            output = VlmOutput(
                choices = listOf(
                    VlmChoice(
                        message = VlmMessage(
                            content = listOf(
                                VlmContent(
                                    type = "text",
                                    text = "{\"error\": \"Element not found\"}"
                                )
                            )
                        )
                    )
                )
            )
        )

        coEvery { vlmApiService.analyzeScreen(any(), any()) } returns mockResponse
        coEvery { cacheManager.getCachedCoordinate(any(), any()) } returns null

        val result = vlmService.findElement("base64Image", "test element")

        assertNull(result)
    }

    @Test
    fun `test findElement with empty base64`() = runBlocking {
        val result = vlmService.findElement("", "test element")
        assertNull(result)
    }

    @Test
    fun `test detectPageState with valid response`() = runBlocking {
        // 模拟响应
        val mockResponse = VlmResponse(
            output = VlmOutput(
                choices = listOf(
                    VlmChoice(
                        message = VlmMessage(
                            content = listOf(
                                VlmContent(
                                    type = "text",
                                    text = "{\"page\": \"wechat_home\", \"confidence\": 0.9}"
                                )
                            )
                        )
                    )
                )
            )
        )

        coEvery { vlmApiService.analyzeScreen(any(), any()) } returns mockResponse

        val result = vlmService.detectPageState("base64Image", listOf("wechat_home", "wechat_search"))

        assertEquals("wechat_home", result)
    }

    @Test
    fun `test detectPageState with low confidence`() = runBlocking {
        // 模拟低置信度响应
        val mockResponse = VlmResponse(
            output = VlmOutput(
                choices = listOf(
                    VlmChoice(
                        message = VlmMessage(
                            content = listOf(
                                VlmContent(
                                    type = "text",
                                    text = "{\"page\": \"wechat_home\", \"confidence\": 0.5}"
                                )
                            )
                        )
                    )
                )
            )
        )

        coEvery { vlmApiService.analyzeScreen(any(), any()) } returns mockResponse

        val result = vlmService.detectPageState("base64Image", listOf("wechat_home", "wechat_search"))

        assertNull(result)
    }
}
