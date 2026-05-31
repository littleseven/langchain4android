package com.picme.domain.agent.capability

import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.AgentScene
import com.picme.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CameraCapability 场景化单元测试
 *
 * 验证：在 CAMERA 场景下所有相机命令均可正确执行，回调被触发。
 */
class CameraCapabilityTest {

    private val defaultContext = AgentContext(
        scene = AgentScene.CAMERA,
        beautySettings = BeautySettings(),
        filterType = FilterType.NONE,
        styleFilter = StyleFilter.NONE,
        zoomRatio = 1f,
        exposureCompensation = 0,
        captureMode = MediaType.PHOTO,
        isRecording = false
    )

    // ------------------------------------------------------------------
    // 1. 场景绑定验证
    // ------------------------------------------------------------------

    @Test
    fun `activeScenes returns only CAMERA`() {
        val capability = CameraCapability()
        val scenes = capability.activeScenes()
        assertEquals(1, scenes.size)
        assertEquals(com.picme.domain.agent.model.SceneManager.Scene.CAMERA, scenes[0])
    }

    @Test
    fun `supportedCommands contains all camera commands`() {
        val capability = CameraCapability()
        val commands = capability.supportedCommands()
        assertEquals(11, commands.size)
        assertTrue(commands.contains("adjust_beauty"))
        assertTrue(commands.contains("switch_filter"))
        assertTrue(commands.contains("switch_style"))
        assertTrue(commands.contains("switch_scene"))
        assertTrue(commands.contains("switch_ratio"))
        assertTrue(commands.contains("adjust_exposure"))
        assertTrue(commands.contains("adjust_zoom"))
        assertTrue(commands.contains("flip_camera"))
        assertTrue(commands.contains("capture"))
        assertTrue(commands.contains("toggle_recording"))
        assertTrue(commands.contains("switch_mode"))
    }

    // ------------------------------------------------------------------
    // 2. 命令执行验证 — 回调触发
    // ------------------------------------------------------------------

    @Test
    fun `execute AdjustBeauty triggers onAdjustBeauty callback`() = runBlocking {
        var callbackInvoked = false
        var receivedSettings: BeautySettings? = null

        val capability = CameraCapability(
            onAdjustBeauty = { settings ->
                callbackInvoked = true
                receivedSettings = settings
            }
        )

        val settings = BeautySettings(smoothing = 0.8f)
        val command = AgentCommand.AdjustBeauty(settings)
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(callbackInvoked)
        assertEquals(0.8f, receivedSettings?.smoothing)
    }

    @Test
    fun `execute SwitchFilter triggers onSwitchFilter callback`() = runBlocking {
        var callbackInvoked = false
        var receivedFilter: FilterType? = null

        val capability = CameraCapability(
            onSwitchFilter = { filter ->
                callbackInvoked = true
                receivedFilter = filter
            }
        )

        val command = AgentCommand.SwitchFilter(FilterType.VINTAGE)
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(callbackInvoked)
        assertEquals(FilterType.VINTAGE, receivedFilter)
    }

    @Test
    fun `execute SwitchStyle triggers onSwitchStyle callback`() = runBlocking {
        var callbackInvoked = false
        var receivedStyle: StyleFilter? = null

        val capability = CameraCapability(
            onSwitchStyle = { style ->
                callbackInvoked = true
                receivedStyle = style
            }
        )

        val command = AgentCommand.SwitchStyle(StyleFilter.TOON)
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(callbackInvoked)
        assertEquals(StyleFilter.TOON, receivedStyle)
    }

    @Test
    fun `execute SwitchScene triggers onSwitchScene callback`() = runBlocking {
        var callbackInvoked = false
        var receivedScene: String? = null

        val capability = CameraCapability(
            onSwitchScene = { scene ->
                callbackInvoked = true
                receivedScene = scene
            }
        )

        val command = AgentCommand.SwitchScene("night")
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(callbackInvoked)
        assertEquals("night", receivedScene)
    }

    @Test
    fun `execute SwitchRatio triggers onSwitchRatio callback`() = runBlocking {
        var callbackInvoked = false
        var receivedRatio: String? = null

        val capability = CameraCapability(
            onSwitchRatio = { ratio ->
                callbackInvoked = true
                receivedRatio = ratio
            }
        )

        val command = AgentCommand.SwitchRatio("16:9")
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(callbackInvoked)
        assertEquals("16:9", receivedRatio)
    }

    @Test
    fun `execute AdjustExposure triggers onAdjustExposure callback`() = runBlocking {
        var callbackInvoked = false
        var receivedExposure: Int? = null

        val capability = CameraCapability(
            onAdjustExposure = { exposure ->
                callbackInvoked = true
                receivedExposure = exposure
            }
        )

        val command = AgentCommand.AdjustExposure(2)
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(callbackInvoked)
        assertEquals(2, receivedExposure)
    }

    @Test
    fun `execute AdjustZoom triggers onAdjustZoom callback`() = runBlocking {
        var callbackInvoked = false
        var receivedZoom: Float? = null

        val capability = CameraCapability(
            onAdjustZoom = { zoom ->
                callbackInvoked = true
                receivedZoom = zoom
            }
        )

        val command = AgentCommand.AdjustZoom(2.5f)
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(callbackInvoked)
        assertEquals(2.5f, receivedZoom)
    }

    @Test
    fun `execute FlipCamera triggers onFlipCamera callback`() = runBlocking {
        var callbackInvoked = false

        val capability = CameraCapability(
            onFlipCamera = { callbackInvoked = true }
        )

        val result = capability.execute(AgentCommand.FlipCamera, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(callbackInvoked)
    }

    @Test
    fun `execute CapturePhoto triggers onCapturePhoto callback`() = runBlocking {
        var callbackInvoked = false

        val capability = CameraCapability(
            onCapturePhoto = { callbackInvoked = true }
        )

        val result = capability.execute(AgentCommand.CapturePhoto, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(callbackInvoked)
    }

    @Test
    fun `execute ToggleRecording triggers onToggleRecording callback`() = runBlocking {
        var callbackInvoked = false

        val capability = CameraCapability(
            onToggleRecording = { callbackInvoked = true }
        )

        val result = capability.execute(AgentCommand.ToggleRecording, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(callbackInvoked)
    }

    @Test
    fun `execute SwitchMode triggers onSwitchMode callback`() = runBlocking {
        var callbackInvoked = false
        var receivedMode: MediaType? = null

        val capability = CameraCapability(
            onSwitchMode = { mode ->
                callbackInvoked = true
                receivedMode = mode
            }
        )

        val command = AgentCommand.SwitchMode(MediaType.VIDEO)
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(callbackInvoked)
        assertEquals(MediaType.VIDEO, receivedMode)
    }

    // ------------------------------------------------------------------
    // 3. 边界情况 — 回调未设置时必须返回 Error（不允许静默失败）
    // ------------------------------------------------------------------

    @Test
    fun `execute CapturePhoto without callback returns Error`() = runBlocking {
        val capability = CameraCapability() // 所有回调为 null

        val result = capability.execute(AgentCommand.CapturePhoto, defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue("回调为空时应返回 Error，而不是 Success", action is AgentAction.Error)
        assertEquals("相机拍照未初始化", (action as AgentAction.Error).message)
    }

    @Test
    fun `execute AdjustBeauty without callback returns Error`() = runBlocking {
        val capability = CameraCapability()

        val result = capability.execute(
            AgentCommand.AdjustBeauty(BeautySettings(smoothing = 0.8f)),
            defaultContext
        )

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("相机美颜调节未初始化", (action as AgentAction.Error).message)
    }

    @Test
    fun `execute FlipCamera without callback returns Error`() = runBlocking {
        val capability = CameraCapability()

        val result = capability.execute(AgentCommand.FlipCamera, defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("相机翻转未初始化", (action as AgentAction.Error).message)
    }

    // ------------------------------------------------------------------
    // 4. 不支持命令 — 返回 Error
    // ------------------------------------------------------------------

    @Test
    fun `execute unsupported command returns Error action`() = runBlocking {
        val capability = CameraCapability()

        val result = capability.execute(AgentCommand.GoBack, defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("相机页面不支持此命令", (action as AgentAction.Error).message)
    }

    // ------------------------------------------------------------------
    // 5. 自描述能力验证
    // ------------------------------------------------------------------

    @Test
    fun `buildCapabilityDescription contains all commands`() {
        val capability = CameraCapability()
        val description = capability.buildCapabilityDescription()

        assertTrue(description.contains("camera"))
        assertTrue(description.contains("adjust_beauty"))
        assertTrue(description.contains("capture"))
        assertTrue(description.contains("flip_camera"))
        assertTrue(description.contains("switch_filter"))
    }
}
