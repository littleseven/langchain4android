package com.picme.domain.agent

import androidx.navigation.NavController
import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.features.camera.capability.CameraCapability
import com.picme.domain.agent.capability.NavigationCapability
import com.picme.agent.core.model.AgentAction
import com.picme.agent.core.model.AgentCommand
import com.picme.agent.core.model.AgentContext
import com.picme.agent.core.model.AgentScene
import com.picme.agent.core.SceneManager
import com.picme.agent.core.model.MediaType
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * AgentOrchestrator 命令链路集成测试
 *
 * 验证完整的命令处理链路：
 * processUserInput → parse → route → dispatch → execute → callback
 *
 * 关键断言：不允许任何环节的失败被掩盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentOrchestratorCommandChainTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()

        // Unbind delegates after each test
        CameraCapability.getInstance().unbindDelegate()
        NavigationCapability.getInstance().unbindNavController()

        // Clear registry map via reflection since unregister() does not exist
        clearRegistry()
    }

    private fun clearRegistry() {
        val registry = CapabilityRegistry.getInstance()
        val field = CapabilityRegistry::class.java.getDeclaredField("registry")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(registry) as MutableMap<String, *>
        map.clear()

        // Also clear the singleton instance so next test gets a fresh registry
        val instanceField = CapabilityRegistry::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)
    }

    // ------------------------------------------------------------------
    // Fake delegate for CameraCapability
    // ------------------------------------------------------------------

    inner class FakeCameraDelegate : CameraCapability.Delegate {
        var captureCalled = false
        var lastBeautySettings: BeautySettings? = null

        override fun onAdjustBeauty(settings: BeautySettings) {
            lastBeautySettings = settings
        }

        override fun onSwitchFilter(filterType: FilterType) {}
        override fun onSwitchStyle(styleFilter: StyleFilter) {}
        override fun onSwitchScene(sceneName: String) {}
        override fun onSwitchRatio(ratio: String) {}
        override fun onAdjustExposure(exposure: Int) {}
        override fun onAdjustZoom(zoomRatio: Float) {}
        override fun onFlipCamera() {}

        override fun onCapturePhoto() {
            captureCalled = true
        }

        override fun onToggleRecording() {}
        override fun onSwitchMode(mode: MediaType) {}
    }

    // ------------------------------------------------------------------
    // 1. 命令解析链路
    // ------------------------------------------------------------------

    @Test
    fun `parse valid JSON command returns correct command`() {
        val context = AgentContext(scene = AgentScene.CAMERA)
        val response = """{"action":"capture"}"""

        val command = AgentCommandParser.parseLlmResponse(response, context)

        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parse adjust beauty command extracts all parameters`() {
        val context = AgentContext(scene = AgentScene.CAMERA)
        val response = """{"action":"adjust_beauty","smoothing":80,"whitening":60,"slim_face":20}"""

        val command = AgentCommandParser.parseLlmResponse(response, context)

        assertTrue(command is AgentCommand.AdjustBeauty)
        val beautyCmd = command as AgentCommand.AdjustBeauty
        assertEquals(80f, beautyCmd.settings.smoothing)
        assertEquals(60f, beautyCmd.settings.whitening)
        assertEquals(20f, beautyCmd.settings.slimFace)
    }

    @Test
    fun `parse navigate command returns NavigateTo`() {
        val context = AgentContext(scene = AgentScene.CAMERA)
        val response = """{"action":"navigate_to","destination":"gallery"}"""

        val command = AgentCommandParser.parseLlmResponse(response, context)

        assertTrue(command is AgentCommand.NavigateTo)
        assertEquals("gallery", (command as AgentCommand.NavigateTo).destination)
    }

    @Test
    fun `parse invalid JSON returns TextReply fallback`() {
        val context = AgentContext(scene = AgentScene.CAMERA)
        val response = "你好呀"

        val command = AgentCommandParser.parseLlmResponse(response, context)

        assertTrue(command is AgentCommand.TextReply)
        assertTrue((command as AgentCommand.TextReply).message.isNotBlank())
    }

    @Test
    fun `parse unknown action returns TextReply`() {
        val context = AgentContext(scene = AgentScene.CAMERA)
        val response = """{"action":"do_magic","param":"xyz"}"""

        val command = AgentCommandParser.parseLlmResponse(response, context)

        assertTrue(command is AgentCommand.TextReply)
    }

    // ------------------------------------------------------------------
    // 2. 关键词兜底解析
    // ------------------------------------------------------------------

    @Test
    fun `keyword parse for 拍照 returns CapturePhoto`() {
        val context = AgentContext(scene = AgentScene.CAMERA)
        val response = "好的，我来帮你拍照"

        val command = AgentCommandParser.parseLlmResponse(response, context)

        assertTrue("关键词'拍照'应解析为 CapturePhoto", command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `keyword parse for 去相册 returns NavigateTo gallery`() {
        val context = AgentContext(scene = AgentScene.CAMERA)
        val response = "去相册"

        val command = AgentCommandParser.parseLlmResponse(response, context)

        assertTrue(command is AgentCommand.NavigateTo)
        assertEquals("gallery", (command as AgentCommand.NavigateTo).destination)
    }

    @Test
    fun `keyword parse for 返回 returns GoBack`() {
        val context = AgentContext(scene = AgentScene.CAMERA)
        val response = "返回上一页"

        val command = AgentCommandParser.parseLlmResponse(response, context)

        assertTrue(command is AgentCommand.GoBack)
    }

    // ------------------------------------------------------------------
    // 3. CapabilityRegistry 分发 + 执行（使用 Fake Capability）
    // ------------------------------------------------------------------

    @Test
    fun `full chain parse dispatch execute with callback`() = runTest(testDispatcher) {
        val registry = CapabilityRegistry.getInstance()

        SceneManager.getInstance().transitionTo(SceneManager.Scene.CAMERA)

        val fakeDelegate = FakeCameraDelegate()
        val cameraCap = CameraCapability.getInstance()
        cameraCap.bindDelegate(fakeDelegate)
        registry.register(cameraCap)

        // 模拟 LLM 输出
        val context = AgentContext(scene = AgentScene.CAMERA)
        val parsedCommand = AgentCommandParser.parseLlmResponse("""{"action":"capture"}""", context)

        // 分发并执行
        val result = registry.dispatch(parsedCommand, context)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() is AgentAction.Success)
        assertTrue("回调必须被触发", fakeDelegate.captureCalled)
    }

    @Test
    fun `full chain with unbound delegate queues and returns text reply`() = runTest(testDispatcher) {
        val registry = CapabilityRegistry.getInstance()

        SceneManager.getInstance().transitionTo(SceneManager.Scene.CAMERA)

        // 注册 CameraCapability 但不绑定 delegate
        val cameraCap = CameraCapability.getInstance()
        registry.register(cameraCap)

        val context = AgentContext(scene = AgentScene.CAMERA)
        val parsedCommand = AgentCommandParser.parseLlmResponse("""{"action":"capture"}""", context)

        val result = registry.dispatch(parsedCommand, context)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue("未绑定 delegate 时应排队并返回 TextReply", action is AgentAction.TextReply)
        assertTrue((action as AgentAction.TextReply).message.contains("切换"))
    }

    @Test
    fun `navigation command works across scenes`() = runTest(testDispatcher) {
        val registry = CapabilityRegistry.getInstance()

        // 在 Gallery 场景也能执行导航
        SceneManager.getInstance().transitionTo(SceneManager.Scene.GALLERY)

        val navController = mockk<NavController>(relaxed = true)
        val navCap = NavigationCapability.getInstance()
        navCap.bindNavController(navController)
        registry.register(navCap)

        val context = AgentContext(scene = AgentScene.GALLERY)
        val parsedCommand = AgentCommandParser.parseLlmResponse(
            """{"action":"navigate_to","destination":"settings"}""",
            context
        )

        val result = registry.dispatch(parsedCommand, context)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() is AgentAction.Success)
    }

    // ------------------------------------------------------------------
    // 4. 边界：复杂 JSON 和 Think 标签
    // ------------------------------------------------------------------

    @Test
    fun `parse response with think tags strips them correctly`() {
        val context = AgentContext(scene = AgentScene.CAMERA)
        val response = """<think>让我想想</think>{"action":"flip_camera"}"""

        val command = AgentCommandParser.parseLlmResponse(response, context)

        assertTrue(command is AgentCommand.FlipCamera)
    }

    @Test
    fun `parse response with markdown code block strips fences`() {
        val context = AgentContext(scene = AgentScene.CAMERA)
        val response = """```json\n{"action":"capture"}\n```"""

        val command = AgentCommandParser.parseLlmResponse(response, context)

        assertTrue(command is AgentCommand.CapturePhoto)
    }
}
