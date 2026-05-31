package com.picme.domain.agent

import com.picme.domain.agent.capability.CameraCapability
import com.picme.domain.agent.capability.NavigationCapability
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.AgentScene
import com.picme.domain.agent.model.SceneManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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
        val response = "好的，前往相册"

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
    fun `full chain parse dispatch execute with callback`() = runBlocking {
        val registry = CapabilityRegistry.getInstance()
        // 清理
        registry.getAll().forEach { registry.unregister(it.name) }

        SceneManager.getInstance().transitionTo(SceneManager.Scene.CAMERA)

        var captureCalled = false
        val cameraCap = CameraCapability(
            onCapturePhoto = { captureCalled = true }
        )
        registry.register(cameraCap)

        // 模拟 LLM 输出
        val context = AgentContext(scene = AgentScene.CAMERA)
        val parsedCommand = AgentCommandParser.parseLlmResponse("""{"action":"capture"}""", context)

        // 分发并执行
        val result = registry.dispatch(parsedCommand, context)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() is AgentAction.Success)
        assertTrue("回调必须被触发", captureCalled)
    }

    @Test
    fun `full chain with null callback returns error not success`() = runBlocking {
        val registry = CapabilityRegistry.getInstance()
        registry.getAll().forEach { registry.unregister(it.name) }

        SceneManager.getInstance().transitionTo(SceneManager.Scene.CAMERA)

        // 注册空回调 Capability
        val cameraCap = CameraCapability()
        registry.register(cameraCap)

        val context = AgentContext(scene = AgentScene.CAMERA)
        val parsedCommand = AgentCommandParser.parseLlmResponse("""{"action":"capture"}""", context)

        val result = registry.dispatch(parsedCommand, context)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue("空回调必须返回 Error，不允许静默失败", action is AgentAction.Error)
        assertEquals("相机拍照未初始化", (action as AgentAction.Error).message)
    }

    @Test
    fun `navigation command works across scenes`() = runBlocking {
        val registry = CapabilityRegistry.getInstance()
        registry.getAll().forEach { registry.unregister(it.name) }

        // 在 Gallery 场景也能执行导航
        SceneManager.getInstance().transitionTo(SceneManager.Scene.GALLERY)

        var navDestination: String? = null
        val navCap = NavigationCapability(
            onNavigate = { navDestination = it.name },
            onBack = { }
        )
        registry.register(navCap)

        val context = AgentContext(scene = AgentScene.GALLERY)
        val parsedCommand = AgentCommandParser.parseLlmResponse(
            """{"action":"navigate_to","destination":"settings"}""",
            context
        )

        val result = registry.dispatch(parsedCommand, context)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() is AgentAction.Success)
        assertEquals("SETTINGS", navDestination)
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