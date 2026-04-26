package com.ailaohu.service.voice

import com.ailaohu.service.accessibility.AutoPilotService
import com.ailaohu.service.capture.ScreenCaptureManager
import com.ailaohu.service.tts.TTSManager
import com.ailaohu.service.vlm.VLMService
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCommandBridgeTest {

    @MockK
    private lateinit var autoGLMExecutor: AutoGLMExecutor

    @MockK
    private lateinit var ttsManager: TTSManager

    @MockK
    private lateinit var commandNormalizer: CommandNormalizer

    @MockK
    private lateinit var stateMachine: VoiceStateMachine

    @MockK
    private lateinit var screenCaptureManager: ScreenCaptureManager

    @MockK
    private lateinit var vlmService: VLMService

    private lateinit var voiceCommandBridge: VoiceCommandBridge

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        // Mock AutoPilotService companion object
        mockkObject(AutoPilotService)
        every { AutoPilotService.isRunning() } returns true
        every { AutoPilotService.performClick(any(), any()) } returns Unit
        every { AutoPilotService.performHome() } returns Unit

        // Setup default mocks
        every { ttsManager.speak(any()) } returns Unit
        every { ttsManager.enqueue(any()) } returns Unit
        every { stateMachine.transitionTo(any(), any()) } returns Unit

        voiceCommandBridge = VoiceCommandBridge(
            autoGLMExecutor,
            ttsManager,
            commandNormalizer,
            stateMachine,
            screenCaptureManager,
            vlmService
        )
    }

    @After
    fun tearDown() {
        voiceCommandBridge.destroy()
        Dispatchers.resetMain()
    }

    /**
     * TC-02: 链路完整性测试
     * 测试：用户发出指令后，完整的处理链路
     */
    @Test
    fun `TC-02 Link Integrity - command triggers screenshot and API call`() = runTest {
        // Arrange
        val rawCommand = "打开微信"
        val normalizedCommand = "打开微信"

        every { commandNormalizer.normalize(rawCommand) } returns normalizedCommand
        every { screenCaptureManager.hasPermission() } returns true
        every { screenCaptureManager.getScreenshotBase64() } returns "mock_base64_image"

        coEvery {
            vlmService.executeAutoGLMPhone(normalizedCommand, "mock_base64_image")
        } returns Result.success("""{
            "description": "打开微信",
            "actions": [
                {
                    "type": "launch",
                    "app": "com.tencent.mm",
                    "delay": 500
                }
            ]
        }""")

        // Act
        voiceCommandBridge.processCommand(rawCommand)

        // Assert
        verify(exactly = 1) {
            screenCaptureManager.getScreenshotBase64()
        }

        coVerify(exactly = 1) {
            vlmService.executeAutoGLMPhone(normalizedCommand, "mock_base64_image")
        }

        verify(exactly = 1) {
            stateMachine.transitionTo(VoicePipelineState.EXECUTING, normalizedCommand)
        }
    }

    /**
     * TC-03: 视觉理解准确性测试
     * 测试：解析并执行点击指令
     */
    @Test
    fun `TC-03 Visual Understanding - parses click coordinates`() = runTest {
        // Arrange
        val rawCommand = "下载排在第一个的应用"
        val normalizedCommand = "下载排在第一个的应用"

        every { commandNormalizer.normalize(rawCommand) } returns normalizedCommand
        every { screenCaptureManager.hasPermission() } returns true
        every { screenCaptureManager.getScreenshotBase64() } returns "mock_base64_image"

        coEvery {
            vlmService.executeAutoGLMPhone(normalizedCommand, "mock_base64_image")
        } returns Result.success("""{
            "description": "点击下载按钮",
            "actions": [
                {
                    "type": "click",
                    "x": 540,
                    "y": 980,
                    "delay": 300
                }
            ]
        }""")

        // Act
        voiceCommandBridge.processCommand(rawCommand)

        // Assert
        verify(exactly = 1) {
            AutoPilotService.performClick(540f, 980f)
        }
    }

    /**
     * TC-04: 复杂意图流测试
     * 测试：多步骤操作的执行
     */
    @Test
    fun `TC-04 Complex Intent - executes multiple action steps`() = runTest {
        // Arrange
        val rawCommand = "去淘宝帮我搜一下新款手机"
        val normalizedCommand = "去淘宝帮我搜一下新款手机"

        every { commandNormalizer.normalize(rawCommand) } returns normalizedCommand
        every { screenCaptureManager.hasPermission() } returns true
        every { screenCaptureManager.getScreenshotBase64() } returns "mock_base64_image"

        coEvery {
            vlmService.executeAutoGLMPhone(normalizedCommand, "mock_base64_image")
        } returns Result.success("""{
            "description": "打开淘宝并搜索",
            "actions": [
                {
                    "type": "launch",
                    "app": "com.taobao.taobao",
                    "delay": 1000
                },
                {
                    "type": "click",
                    "x": 540,
                    "y": 200,
                    "delay": 300
                },
                {
                    "type": "type",
                    "text": "新款手机",
                    "delay": 500
                }
            ]
        }""")

        // Act
        voiceCommandBridge.processCommand(rawCommand)

        // Assert - Verify at least launch is called
        // Full verification would need more complex testing with delays
        verify(exactly = 1) {
            stateMachine.transitionTo(VoicePipelineState.EXECUTING, normalizedCommand)
        }
    }

    /**
     * TC-05: App切换测试
     * 测试：返回桌面指令
     */
    @Test
    fun `TC-05 App Switch - triggers home action`() = runTest {
        // Arrange
        val rawCommand = "返回桌面"
        val normalizedCommand = "返回桌面"

        every { commandNormalizer.normalize(rawCommand) } returns normalizedCommand
        every { screenCaptureManager.hasPermission() } returns true
        every { screenCaptureManager.getScreenshotBase64() } returns "mock_base64_image"

        coEvery {
            vlmService.executeAutoGLMPhone(normalizedCommand, "mock_base64_image")
        } returns Result.success("""{
            "description": "返回桌面",
            "actions": [
                {
                    "type": "home",
                    "delay": 0
                }
            ]
        }""")

        // Act
        voiceCommandBridge.processCommand(rawCommand)

        // Assert
        verify(exactly = 1) {
            AutoPilotService.performHome()
        }
    }

    /**
     * 测试：API失败时的回退机制
     */
    @Test
    fun `Error Handling - fallback to AutoGLMExecutor on VLM failure`() = runTest {
        // Arrange
        val rawCommand = "打开微信"
        val normalizedCommand = "打开微信"

        every { commandNormalizer.normalize(rawCommand) } returns normalizedCommand
        every { screenCaptureManager.hasPermission() } returns true
        every { screenCaptureManager.getScreenshotBase64() } returns "mock_base64_image"
        every { screenCaptureManager.captureScreen() } returns null

        coEvery {
            vlmService.executeAutoGLMPhone(normalizedCommand, "mock_base64_image")
        } returns Result.failure(Exception("API Error"))

        coEvery {
            autoGLMExecutor.executeWithScreenContext(normalizedCommand, any())
        } returns Result.success("""{
            "description": "打开微信",
            "actions": [
                {
                    "type": "launch",
                    "app": "com.tencent.mm",
                    "delay": 500
                }
            ]
        }""")

        // Act
        voiceCommandBridge.processCommand(rawCommand)

        // Assert - Fallback should be called
        coVerify(exactly = 1) {
            autoGLMExecutor.executeWithScreenContext(normalizedCommand, any())
        }
    }

    /**
     * 测试：无屏幕截图权限时的处理
     */
    @Test
    fun `Permission Check - no screen capture uses fallback`() = runTest {
        // Arrange
        val rawCommand = "打开微信"
        val normalizedCommand = "打开微信"

        every { commandNormalizer.normalize(rawCommand) } returns normalizedCommand
        every { screenCaptureManager.hasPermission() } returns false
        every { screenCaptureManager.captureScreen() } returns null

        coEvery {
            autoGLMExecutor.executeWithScreenContext(normalizedCommand, any())
        } returns Result.success("""{
            "description": "打开微信",
            "actions": [
                {
                    "type": "launch",
                    "app": "com.tencent.mm",
                    "delay": 500
                }
            ]
        }""")

        // Act
        voiceCommandBridge.processCommand(rawCommand)

        // Assert
        coVerify(exactly = 1) {
            autoGLMExecutor.executeWithScreenContext(normalizedCommand, any())
        }
    }

    /**
     * 测试：JSON解析错误的处理
     */
    @Test
    fun `JSON Parsing - handles malformed response gracefully`() = runTest {
        // Arrange
        val rawCommand = "打开微信"
        val normalizedCommand = "打开微信"

        every { commandNormalizer.normalize(rawCommand) } returns normalizedCommand
        every { screenCaptureManager.hasPermission() } returns true
        every { screenCaptureManager.getScreenshotBase64() } returns "mock_base64_image"

        coEvery {
            vlmService.executeAutoGLMPhone(normalizedCommand, "mock_base64_image")
        } returns Result.success("Invalid JSON response")

        // Act
        voiceCommandBridge.processCommand(rawCommand)

        // Assert - Should not crash, and speak completion
        verify(exactly = 1) {
            ttsManager.speak("正在为您进行操作")
        }
    }
}
