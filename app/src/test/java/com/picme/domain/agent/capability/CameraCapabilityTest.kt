package com.picme.domain.agent.capability

import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.agent.core.api.context.AgentAction
import com.picme.agent.core.api.command.AgentCommand
import com.picme.agent.core.api.context.AgentContext
import com.picme.agent.core.api.context.AgentScene
import com.picme.agent.core.api.context.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.picme.agent.core.runtime.state.SceneManager

/**
 * CameraCapability 场景化单元测试
 *
 * 验证：在 CAMERA 场景下所有相机命令均可正确执行，回调被触发。
 */
class CameraCapabilityTest {

    private lateinit var capability: CameraCapability

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

    @Before
    fun setUp() {
        capability = CameraCapability.getInstance()
    }

    @After
    fun tearDown() {
        capability.unbindDelegate()
    }

    // ------------------------------------------------------------------
    // FakeDelegate: 捕获所有回调参数
    // ------------------------------------------------------------------

    inner class FakeDelegate : CameraCapability.Delegate {
        var onAdjustBeautyCalled = false
        var receivedBeautySettings: BeautySettings? = null

        var onSwitchFilterCalled = false
        var receivedFilterType: FilterType? = null

        var onSwitchStyleCalled = false
        var receivedStyleFilter: StyleFilter? = null

        var onSwitchSceneCalled = false
        var receivedSceneName: String? = null

        var onSwitchRatioCalled = false
        var receivedRatio: String? = null

        var onAdjustExposureCalled = false
        var receivedExposure: Int? = null

        var onAdjustZoomCalled = false
        var receivedZoomRatio: Float? = null

        var onFlipCameraCalled = false
        var onCapturePhotoCalled = false
        var onToggleRecordingCalled = false

        var onSwitchModeCalled = false
        var receivedMode: MediaType? = null

        override fun onAdjustBeauty(settings: BeautySettings) {
            onAdjustBeautyCalled = true
            receivedBeautySettings = settings
        }

        override fun onSwitchFilter(filterType: FilterType) {
            onSwitchFilterCalled = true
            receivedFilterType = filterType
        }

        override fun onSwitchStyle(styleFilter: StyleFilter) {
            onSwitchStyleCalled = true
            receivedStyleFilter = styleFilter
        }

        override fun onSwitchScene(sceneName: String) {
            onSwitchSceneCalled = true
            receivedSceneName = sceneName
        }

        override fun onSwitchRatio(ratio: String) {
            onSwitchRatioCalled = true
            receivedRatio = ratio
        }

        override fun onAdjustExposure(exposure: Int) {
            onAdjustExposureCalled = true
            receivedExposure = exposure
        }

        override fun onAdjustZoom(zoomRatio: Float) {
            onAdjustZoomCalled = true
            receivedZoomRatio = zoomRatio
        }

        override fun onFlipCamera() {
            onFlipCameraCalled = true
        }

        override fun onCapturePhoto() {
            onCapturePhotoCalled = true
        }

        override fun onToggleRecording() {
            onToggleRecordingCalled = true
        }

        override fun onSwitchMode(mode: MediaType) {
            onSwitchModeCalled = true
            receivedMode = mode
        }
    }

    // ------------------------------------------------------------------
    // 1. 场景绑定验证
    // ------------------------------------------------------------------

    @Test
    fun `activeScenes returns only CAMERA`() {
        val scenes = capability.activeScenes()
        assertEquals(1, scenes.size)
        assertEquals(SceneManager.Scene.CAMERA, scenes[0])
    }

    @Test
    fun `supportedCommands contains all camera commands`() {
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
        val fakeDelegate = FakeDelegate()
        capability.bindDelegate(fakeDelegate)

        val settings = BeautySettings(smoothing = 0.8f)
        val command = AgentCommand.AdjustBeauty(settings)
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(fakeDelegate.onAdjustBeautyCalled)
        assertEquals(0.8f, fakeDelegate.receivedBeautySettings?.smoothing)
    }

    @Test
    fun `execute SwitchFilter triggers onSwitchFilter callback`() = runBlocking {
        val fakeDelegate = FakeDelegate()
        capability.bindDelegate(fakeDelegate)

        val command = AgentCommand.SwitchFilter(FilterType.VINTAGE)
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(fakeDelegate.onSwitchFilterCalled)
        assertEquals(FilterType.VINTAGE, fakeDelegate.receivedFilterType)
    }

    @Test
    fun `execute SwitchStyle triggers onSwitchStyle callback`() = runBlocking {
        val fakeDelegate = FakeDelegate()
        capability.bindDelegate(fakeDelegate)

        val command = AgentCommand.SwitchStyle(StyleFilter.TOON)
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(fakeDelegate.onSwitchStyleCalled)
        assertEquals(StyleFilter.TOON, fakeDelegate.receivedStyleFilter)
    }

    @Test
    fun `execute SwitchScene triggers onSwitchScene callback`() = runBlocking {
        val fakeDelegate = FakeDelegate()
        capability.bindDelegate(fakeDelegate)

        val command = AgentCommand.SwitchScene("night")
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(fakeDelegate.onSwitchSceneCalled)
        assertEquals("night", fakeDelegate.receivedSceneName)
    }

    @Test
    fun `execute SwitchRatio triggers onSwitchRatio callback`() = runBlocking {
        val fakeDelegate = FakeDelegate()
        capability.bindDelegate(fakeDelegate)

        val command = AgentCommand.SwitchRatio("16:9")
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(fakeDelegate.onSwitchRatioCalled)
        assertEquals("16:9", fakeDelegate.receivedRatio)
    }

    @Test
    fun `execute AdjustExposure triggers onAdjustExposure callback`() = runBlocking {
        val fakeDelegate = FakeDelegate()
        capability.bindDelegate(fakeDelegate)

        val command = AgentCommand.AdjustExposure(2)
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(fakeDelegate.onAdjustExposureCalled)
        assertEquals(2, fakeDelegate.receivedExposure)
    }

    @Test
    fun `execute AdjustZoom triggers onAdjustZoom callback`() = runBlocking {
        val fakeDelegate = FakeDelegate()
        capability.bindDelegate(fakeDelegate)

        val command = AgentCommand.AdjustZoom(2.5f)
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(fakeDelegate.onAdjustZoomCalled)
        assertEquals(2.5f, fakeDelegate.receivedZoomRatio)
    }

    @Test
    fun `execute FlipCamera triggers onFlipCamera callback`() = runBlocking {
        val fakeDelegate = FakeDelegate()
        capability.bindDelegate(fakeDelegate)

        val result = capability.execute(AgentCommand.FlipCamera, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(fakeDelegate.onFlipCameraCalled)
    }

    @Test
    fun `execute CapturePhoto triggers onCapturePhoto callback`() = runBlocking {
        val fakeDelegate = FakeDelegate()
        capability.bindDelegate(fakeDelegate)

        val result = capability.execute(AgentCommand.CapturePhoto, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(fakeDelegate.onCapturePhotoCalled)
    }

    @Test
    fun `execute ToggleRecording triggers onToggleRecording callback`() = runBlocking {
        val fakeDelegate = FakeDelegate()
        capability.bindDelegate(fakeDelegate)

        val result = capability.execute(AgentCommand.ToggleRecording, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(fakeDelegate.onToggleRecordingCalled)
    }

    @Test
    fun `execute SwitchMode triggers onSwitchMode callback`() = runBlocking {
        val fakeDelegate = FakeDelegate()
        capability.bindDelegate(fakeDelegate)

        val command = AgentCommand.SwitchMode(MediaType.VIDEO)
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(fakeDelegate.onSwitchModeCalled)
        assertEquals(MediaType.VIDEO, fakeDelegate.receivedMode)
    }

    // ------------------------------------------------------------------
    // 3. 边界情况 — delegate 未绑定时必须返回 Error（不允许静默失败）
    // ------------------------------------------------------------------

    @Test
    fun `execute CapturePhoto without delegate returns Error`() = runBlocking {
        val result = capability.execute(AgentCommand.CapturePhoto, defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue("delegate 为空时应返回 Error，而不是 Success", action is AgentAction.Error)
        assertEquals("相机页面未激活，请先切换到相机页面", (action as AgentAction.Error).message)
    }

    @Test
    fun `execute AdjustBeauty without delegate returns Error`() = runBlocking {
        val result = capability.execute(
            AgentCommand.AdjustBeauty(BeautySettings(smoothing = 0.8f)),
            defaultContext
        )

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("相机页面未激活，请先切换到相机页面", (action as AgentAction.Error).message)
    }

    @Test
    fun `execute FlipCamera without delegate returns Error`() = runBlocking {
        val result = capability.execute(AgentCommand.FlipCamera, defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("相机页面未激活，请先切换到相机页面", (action as AgentAction.Error).message)
    }

    // ------------------------------------------------------------------
    // 4. 不支持命令 — 返回 Error
    // ------------------------------------------------------------------

    @Test
    fun `execute unsupported command returns Error action`() = runBlocking {
        val fakeDelegate = FakeDelegate()
        capability.bindDelegate(fakeDelegate)

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
        val description = capability.buildCapabilityDescription()

        assertTrue(description.contains("camera"))
        assertTrue(description.contains("adjust_beauty"))
        assertTrue(description.contains("capture"))
        assertTrue(description.contains("flip_camera"))
        assertTrue(description.contains("switch_filter"))
    }
}
