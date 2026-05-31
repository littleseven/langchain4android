package com.picme.domain.agent.capability

import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.AgentScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * NavigationCapability 场景化单元测试
 *
 * 验证：NavigationCapability 在所有场景均可用，
 * 页面导航和返回命令正确触发回调，目的地解析准确。
 *
 * **注意**：NavigationCapability.execute() 内部使用 withContext(Dispatchers.Main)
 * 确保导航回调在主线程执行。测试中使用 StandardTestDispatcher 模拟主线程。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationCapabilityTest {

    private val testDispatcher = StandardTestDispatcher()
    private val defaultContext = AgentContext(scene = AgentScene.CAMERA)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // 1. 场景绑定验证 — 所有场景可用
    // ------------------------------------------------------------------

    @Test
    fun `activeScenes returns all scenes`() {
        val capability = NavigationCapability(
            onNavigate = {},
            onBack = {}
        )
        val scenes = capability.activeScenes()
        assertEquals(
            com.picme.domain.agent.model.SceneManager.Scene.entries.toList(),
            scenes
        )
    }

    @Test
    fun `supportedCommands contains navigate and go_back`() {
        val capability = NavigationCapability(
            onNavigate = {},
            onBack = {}
        )
        val commands = capability.supportedCommands()
        assertEquals(2, commands.size)
        assertTrue(commands.contains("navigate_to"))
        assertTrue(commands.contains("go_back"))
    }

    // ------------------------------------------------------------------
    // 2. 导航命令 — 目的地解析
    // ------------------------------------------------------------------

    @Test
    fun `execute NavigateTo camera triggers onNavigate with CAMERA`() = runTest(testDispatcher) {
        var receivedDest: NavigationCapability.Destination? = null
        val capability = NavigationCapability(
            onNavigate = { dest -> receivedDest = dest },
            onBack = {}
        )

        val result = capability.execute(
            AgentCommand.NavigateTo("camera"),
            defaultContext
        )

        assertTrue(result.isSuccess)
        assertEquals(NavigationCapability.Destination.CAMERA, receivedDest)
    }

    @Test
    fun `execute NavigateTo gallery triggers onNavigate with GALLERY`() = runTest(testDispatcher) {
        var receivedDest: NavigationCapability.Destination? = null
        val capability = NavigationCapability(
            onNavigate = { dest -> receivedDest = dest },
            onBack = {}
        )

        val result = capability.execute(
            AgentCommand.NavigateTo("gallery"),
            defaultContext
        )

        assertTrue(result.isSuccess)
        assertEquals(NavigationCapability.Destination.GALLERY, receivedDest)
    }

    @Test
    fun `execute NavigateTo settings triggers onNavigate with SETTINGS`() = runTest(testDispatcher) {
        var receivedDest: NavigationCapability.Destination? = null
        val capability = NavigationCapability(
            onNavigate = { dest -> receivedDest = dest },
            onBack = {}
        )

        val result = capability.execute(
            AgentCommand.NavigateTo("settings"),
            defaultContext
        )

        assertTrue(result.isSuccess)
        assertEquals(NavigationCapability.Destination.SETTINGS, receivedDest)
    }

    @Test
    fun `execute NavigateTo debug triggers onNavigate with DEBUG`() = runTest(testDispatcher) {
        var receivedDest: NavigationCapability.Destination? = null
        val capability = NavigationCapability(
            onNavigate = { dest -> receivedDest = dest },
            onBack = {}
        )

        val result = capability.execute(
            AgentCommand.NavigateTo("debug"),
            defaultContext
        )

        assertTrue(result.isSuccess)
        assertEquals(NavigationCapability.Destination.DEBUG, receivedDest)
    }

    @Test
    fun `execute NavigateTo llm_model_manager triggers onNavigate with LLM_MODEL_MANAGER`() = runTest(testDispatcher) {
        var receivedDest: NavigationCapability.Destination? = null
        val capability = NavigationCapability(
            onNavigate = { dest -> receivedDest = dest },
            onBack = {}
        )

        val result = capability.execute(
            AgentCommand.NavigateTo("llm_model_manager"),
            defaultContext
        )

        assertTrue(result.isSuccess)
        assertEquals(NavigationCapability.Destination.LLM_MODEL_MANAGER, receivedDest)
    }

    @Test
    fun `execute NavigateTo asr_model_manager triggers onNavigate with ASR_MODEL_MANAGER`() = runTest(testDispatcher) {
        var receivedDest: NavigationCapability.Destination? = null
        val capability = NavigationCapability(
            onNavigate = { dest -> receivedDest = dest },
            onBack = {}
        )

        val result = capability.execute(
            AgentCommand.NavigateTo("asr_model_manager"),
            defaultContext
        )

        assertTrue(result.isSuccess)
        assertEquals(NavigationCapability.Destination.ASR_MODEL_MANAGER, receivedDest)
    }

    @Test
    fun `execute NavigateTo Chinese alias for model manager triggers correctly`() = runTest(testDispatcher) {
        var receivedDest: NavigationCapability.Destination? = null
        val capability = NavigationCapability(
            onNavigate = { dest -> receivedDest = dest },
            onBack = {}
        )

        val result = capability.execute(
            AgentCommand.NavigateTo("模型管理"),
            defaultContext
        )

        assertTrue(result.isSuccess)
        assertEquals(NavigationCapability.Destination.LLM_MODEL_MANAGER, receivedDest)
    }

    @Test
    fun `execute NavigateTo with Chinese alias triggers correctly`() = runTest(testDispatcher) {
        var receivedDest: NavigationCapability.Destination? = null
        val capability = NavigationCapability(
            onNavigate = { dest -> receivedDest = dest },
            onBack = {}
        )

        val result = capability.execute(
            AgentCommand.NavigateTo("相机"),
            defaultContext
        )

        assertTrue(result.isSuccess)
        assertEquals(NavigationCapability.Destination.CAMERA, receivedDest)
    }

    @Test
    fun `execute NavigateTo unknown destination returns Error`() = runTest(testDispatcher) {
        val capability = NavigationCapability(
            onNavigate = {},
            onBack = {}
        )

        val result = capability.execute(
            AgentCommand.NavigateTo("unknown_page"),
            defaultContext
        )

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertTrue((action as AgentAction.Error).message.contains("未知页面"))
        assertTrue((action as AgentAction.Error).message.contains("llm_model_manager"))
        assertTrue((action as AgentAction.Error).message.contains("asr_model_manager"))
    }

    // ------------------------------------------------------------------
    // 3. 返回命令
    // ------------------------------------------------------------------

    @Test
    fun `execute GoBack triggers onBack callback`() = runTest(testDispatcher) {
        var callbackInvoked = false
        val capability = NavigationCapability(
            onNavigate = {},
            onBack = { callbackInvoked = true }
        )

        val result = capability.execute(AgentCommand.GoBack, defaultContext)

        assertTrue(result.isSuccess)
        assertTrue(callbackInvoked)
    }

    // ------------------------------------------------------------------
    // 4. 跨场景可用性验证
    // ------------------------------------------------------------------

    @Test
    fun `navigation available in CAMERA scene`() {
        val capability = NavigationCapability(
            onNavigate = {},
            onBack = {}
        )
        val cameraScene = com.picme.domain.agent.model.SceneManager.Scene.CAMERA
        assertTrue(capability.activeScenes().contains(cameraScene))
    }

    @Test
    fun `navigation available in GALLERY scene`() {
        val capability = NavigationCapability(
            onNavigate = {},
            onBack = {}
        )
        val galleryScene = com.picme.domain.agent.model.SceneManager.Scene.GALLERY
        assertTrue(capability.activeScenes().contains(galleryScene))
    }

    @Test
    fun `navigation available in SETTINGS scene`() {
        val capability = NavigationCapability(
            onNavigate = {},
            onBack = {}
        )
        val settingsScene = com.picme.domain.agent.model.SceneManager.Scene.SETTINGS
        assertTrue(capability.activeScenes().contains(settingsScene))
    }

    @Test
    fun `navigation available in DEBUG scene`() {
        val capability = NavigationCapability(
            onNavigate = {},
            onBack = {}
        )
        val debugScene = com.picme.domain.agent.model.SceneManager.Scene.DEBUG
        assertTrue(capability.activeScenes().contains(debugScene))
    }

    // ------------------------------------------------------------------
    // 5. 不支持命令
    // ------------------------------------------------------------------

    @Test
    fun `execute unsupported command returns Error`() = runTest(testDispatcher) {
        val capability = NavigationCapability(
            onNavigate = {},
            onBack = {}
        )

        val result = capability.execute(AgentCommand.CapturePhoto, defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("导航 Capability 不支持此命令", (action as AgentAction.Error).message)
    }

    // ------------------------------------------------------------------
    // 6. 自描述能力
    // ------------------------------------------------------------------

    @Test
    fun `buildCapabilityDescription contains navigation commands`() {
        val capability = NavigationCapability(
            onNavigate = {},
            onBack = {}
        )
        val description = capability.buildCapabilityDescription()

        assertTrue(description.contains("navigation"))
        assertTrue(description.contains("navigate_to"))
        assertTrue(description.contains("go_back"))
    }
}
