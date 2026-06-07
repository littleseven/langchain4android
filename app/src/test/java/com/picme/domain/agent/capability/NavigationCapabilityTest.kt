package com.picme.domain.agent.capability

import androidx.navigation.NavController
import com.picme.agent.core.model.AgentAction
import com.picme.agent.core.model.AgentCommand
import com.picme.agent.core.model.AgentContext
import com.picme.agent.core.model.AgentScene
import io.mockk.mockk
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
import com.picme.agent.core.SceneManager

/**
 * NavigationCapability 场景化单元测试
 *
 * 验证：NavigationCapability 在所有场景均可用，
 * 页面导航和返回命令正确调用 NavController，目的地解析准确。
 *
 * **注意**：NavigationCapability.execute() 内部使用 withContext(Dispatchers.Main)
 * 确保导航在主线程执行。测试中使用 StandardTestDispatcher 模拟主线程。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationCapabilityTest {

    private val testDispatcher = StandardTestDispatcher()
    private val defaultContext = AgentContext(scene = AgentScene.CAMERA)
    private val capability = NavigationCapability.getInstance()
    private val mockNavController: NavController = mockk(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        capability.bindNavController(mockNavController)
    }

    @After
    fun tearDown() {
        capability.unbindNavController()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // 1. 场景绑定验证 — 所有场景可用
    // ------------------------------------------------------------------

    @Test
    fun `activeScenes returns all scenes`() {
        val scenes = capability.activeScenes()
        assertEquals(
            SceneManager.Scene.entries.toList(),
            scenes
        )
    }

    @Test
    fun `supportedCommands contains navigate and go_back`() {
        val commands = capability.supportedCommands()
        assertEquals(2, commands.size)
        assertTrue(commands.contains("navigate_to"))
        assertTrue(commands.contains("go_back"))
    }

    // ------------------------------------------------------------------
    // 2. 导航命令 — 目的地解析
    // ------------------------------------------------------------------

    @Test
    fun `execute NavigateTo camera returns Success`() = runTest(testDispatcher) {
        val result = capability.execute(
            AgentCommand.NavigateTo("camera"),
            defaultContext
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `execute NavigateTo gallery returns Success`() = runTest(testDispatcher) {
        val result = capability.execute(
            AgentCommand.NavigateTo("gallery"),
            defaultContext
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `execute NavigateTo settings returns Success`() = runTest(testDispatcher) {
        val result = capability.execute(
            AgentCommand.NavigateTo("settings"),
            defaultContext
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `execute NavigateTo debug returns Success`() = runTest(testDispatcher) {
        val result = capability.execute(
            AgentCommand.NavigateTo("debug"),
            defaultContext
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `execute NavigateTo model_center returns Success`() = runTest(testDispatcher) {
        val result = capability.execute(
            AgentCommand.NavigateTo("model_center"),
            defaultContext
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `execute NavigateTo llm_model_manager returns Success`() = runTest(testDispatcher) {
        val result = capability.execute(
            AgentCommand.NavigateTo("llm_model_manager"),
            defaultContext
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `execute NavigateTo asr_model_manager returns Success`() = runTest(testDispatcher) {
        val result = capability.execute(
            AgentCommand.NavigateTo("asr_model_manager"),
            defaultContext
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `execute NavigateTo Chinese alias for model center returns Success`() = runTest(testDispatcher) {
        val result = capability.execute(
            AgentCommand.NavigateTo("模型中心"),
            defaultContext
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `execute NavigateTo with Chinese alias returns Success`() = runTest(testDispatcher) {
        val result = capability.execute(
            AgentCommand.NavigateTo("相机"),
            defaultContext
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `execute NavigateTo unknown destination returns Error`() = runTest(testDispatcher) {
        val result = capability.execute(
            AgentCommand.NavigateTo("unknown_page"),
            defaultContext
        )

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertTrue((action as AgentAction.Error).message.contains("未知页面"))
        assertTrue((action as AgentAction.Error).message.contains("model_center"))
    }

    @Test
    fun `execute NavigateTo without bound NavController returns initialization error`() = runTest(testDispatcher) {
        capability.unbindNavController()

        val result = capability.execute(
            AgentCommand.NavigateTo("camera"),
            defaultContext
        )

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("导航系统未初始化", (action as AgentAction.Error).message)
    }

    // ------------------------------------------------------------------
    // 3. 返回命令
    // ------------------------------------------------------------------

    @Test
    fun `execute GoBack returns Success`() = runTest(testDispatcher) {
        val result = capability.execute(AgentCommand.GoBack, defaultContext)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `execute GoBack without bound NavController returns initialization error`() = runTest(testDispatcher) {
        capability.unbindNavController()

        val result = capability.execute(AgentCommand.GoBack, defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("导航系统未初始化", (action as AgentAction.Error).message)
    }

    // ------------------------------------------------------------------
    // 4. 跨场景可用性验证
    // ------------------------------------------------------------------

    @Test
    fun `navigation available in CAMERA scene`() {
        val cameraScene = SceneManager.Scene.CAMERA
        assertTrue(capability.activeScenes().contains(cameraScene))
    }

    @Test
    fun `navigation available in GALLERY scene`() {
        val galleryScene = SceneManager.Scene.GALLERY
        assertTrue(capability.activeScenes().contains(galleryScene))
    }

    @Test
    fun `navigation available in SETTINGS scene`() {
        val settingsScene = SceneManager.Scene.SETTINGS
        assertTrue(capability.activeScenes().contains(settingsScene))
    }

    @Test
    fun `navigation available in DEBUG scene`() {
        val debugScene = SceneManager.Scene.DEBUG
        assertTrue(capability.activeScenes().contains(debugScene))
    }

    // ------------------------------------------------------------------
    // 5. 不支持命令
    // ------------------------------------------------------------------

    @Test
    fun `execute unsupported command returns Error`() = runTest(testDispatcher) {
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
        val description = capability.buildCapabilityDescription()

        assertTrue(description.contains("navigation"))
        assertTrue(description.contains("navigate_to"))
        assertTrue(description.contains("go_back"))
    }
}
