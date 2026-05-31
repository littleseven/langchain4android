package com.picme.domain.agent

import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.domain.agent.capability.CameraCapability
import com.picme.domain.agent.capability.GalleryCapability
import com.picme.domain.agent.capability.NavigationCapability
import com.picme.domain.agent.capability.SettingsCapability
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.AgentScene
import com.picme.domain.agent.model.SceneManager
import com.picme.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CapabilityRegistry 场景过滤集成测试
 *
 * 验证：
 * 1. 注册后的 Capability 按场景正确过滤
 * 2. dispatch 命令时场景不匹配返回友好错误
 * 3. TextReply / Unknown / Error 命令在 CapabilityRegistry 顶层处理
 * 4. buildCapabilityDescription 仅包含当前场景的 Capability
 *
 * **注意**：NavigationCapability.execute() 使用 withContext(Dispatchers.Main)，
 * 测试中使用 StandardTestDispatcher 模拟主线程。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityRegistrySceneTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sceneManager: SceneManager
    private lateinit var registry: CapabilityRegistry

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // 每个测试前重置 SceneManager 和 CapabilityRegistry
        sceneManager = SceneManager.getInstance()
        sceneManager.clearHistory()
        sceneManager.transitionTo(SceneManager.Scene.UNKNOWN, saveToHistory = false)

        registry = CapabilityRegistry.getInstance()

        // 清理可能残留的注册（通过反射重置 registry map）
        resetRegistry()

        // 注册所有 Capability
        registry.register(NavigationCapability(onNavigate = {}, onBack = {}))
        registry.register(CameraCapability(onCapturePhoto = {}))
        registry.register(GalleryCapability())
        registry.register(SettingsCapability())
    }

    @After
    fun tearDown() {
        resetRegistry()
        sceneManager.clearHistory()
        Dispatchers.resetMain()
    }

    private fun resetRegistry() {
        // 通过反射清空 registry
        val field = CapabilityRegistry::class.java.getDeclaredField("registry")
        field.isAccessible = true
        val map = field.get(registry) as MutableMap<*, *>
        map.clear()
    }

    private fun cameraContext() = AgentContext(
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
    // 1. 场景过滤 — getCapabilitiesForCurrentScene
    // ------------------------------------------------------------------

    @Test
    fun `CAMERA scene returns camera and navigation capabilities`() {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA, saveToHistory = false)

        val capabilities = registry.getCapabilitiesForCurrentScene()
        val names = capabilities.map { it.name }

        assertTrue("CAMERA 场景应包含 camera", names.contains("camera"))
        assertTrue("CAMERA 场景应包含 navigation", names.contains("navigation"))
        assertFalse("CAMERA 场景不应包含 gallery", names.contains("gallery"))
        assertFalse("CAMERA 场景不应包含 settings", names.contains("settings"))
    }

    @Test
    fun `GALLERY scene returns gallery and navigation capabilities`() {
        sceneManager.transitionTo(SceneManager.Scene.GALLERY, saveToHistory = false)

        val capabilities = registry.getCapabilitiesForCurrentScene()
        val names = capabilities.map { it.name }

        assertTrue("GALLERY 场景应包含 gallery", names.contains("gallery"))
        assertTrue("GALLERY 场景应包含 navigation", names.contains("navigation"))
        assertFalse("GALLERY 场景不应包含 camera", names.contains("camera"))
        assertFalse("GALLERY 场景不应包含 settings", names.contains("settings"))
    }

    @Test
    fun `SETTINGS scene returns settings and navigation capabilities`() {
        sceneManager.transitionTo(SceneManager.Scene.SETTINGS, saveToHistory = false)

        val capabilities = registry.getCapabilitiesForCurrentScene()
        val names = capabilities.map { it.name }

        assertTrue("SETTINGS 场景应包含 settings", names.contains("settings"))
        assertTrue("SETTINGS 场景应包含 navigation", names.contains("navigation"))
        assertFalse("SETTINGS 场景不应包含 camera", names.contains("camera"))
        assertFalse("SETTINGS 场景不应包含 gallery", names.contains("gallery"))
    }

    @Test
    fun `DEBUG scene returns only navigation capability`() {
        sceneManager.transitionTo(SceneManager.Scene.DEBUG, saveToHistory = false)

        val capabilities = registry.getCapabilitiesForCurrentScene()
        val names = capabilities.map { it.name }

        assertTrue("DEBUG 场景应包含 navigation", names.contains("navigation"))
        assertFalse("DEBUG 场景不应包含 camera", names.contains("camera"))
        assertFalse("DEBUG 场景不应包含 gallery", names.contains("gallery"))
        assertFalse("DEBUG 场景不应包含 settings", names.contains("settings"))
    }

    // ------------------------------------------------------------------
    // 2. dispatch 场景过滤
    // ------------------------------------------------------------------

    @Test
    fun `dispatch CapturePhoto in CAMERA scene returns Success`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA, saveToHistory = false)

        val result = registry.dispatch(AgentCommand.CapturePhoto, cameraContext())

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Success)
    }

    @Test
    fun `dispatch CapturePhoto in GALLERY scene returns scene error`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.GALLERY, saveToHistory = false)

        val result = registry.dispatch(AgentCommand.CapturePhoto, cameraContext())

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals(
            "在当前页面无法执行此操作，请先导航到对应页面",
            (action as AgentAction.Error).message
        )
    }

    @Test
    fun `dispatch Gallery command in CAMERA scene returns scene error`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA, saveToHistory = false)

        val result = registry.dispatch(
            AgentCommand.DeleteMedia(listOf("1")),
            cameraContext()
        )

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals(
            "在当前页面无法执行此操作，请先导航到对应页面",
            (action as AgentAction.Error).message
        )
    }

    @Test
    fun `dispatch Settings command in CAMERA scene returns scene error`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA, saveToHistory = false)

        val result = registry.dispatch(
            AgentCommand.ChangeTheme("dark"),
            cameraContext()
        )

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals(
            "在当前页面无法执行此操作，请先导航到对应页面",
            (action as AgentAction.Error).message
        )
    }

    @Test
    fun `dispatch NavigateTo in any scene is allowed`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA, saveToHistory = false)

        val result = registry.dispatch(
            AgentCommand.NavigateTo("gallery"),
            cameraContext()
        )

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Success)
    }

    // ------------------------------------------------------------------
    // 3. 顶层命令处理（不进入 Capability）
    // ------------------------------------------------------------------

    @Test
    fun `dispatch TextReply returns TextReply action`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA, saveToHistory = false)

        val result = registry.dispatch(
            AgentCommand.TextReply("你好"),
            cameraContext()
        )

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.TextReply)
        assertEquals("你好", (action as AgentAction.TextReply).message)
    }

    @Test
    fun `dispatch Unknown returns fallback TextReply`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA, saveToHistory = false)

        val result = registry.dispatch(
            AgentCommand.Unknown("some raw text"),
            cameraContext()
        )

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.TextReply)
        assertEquals(
            "收到你的消息了，但没理解具体意图，请再描述一下~",
            (action as AgentAction.TextReply).message
        )
    }

    @Test
    fun `dispatch Error returns Error action`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA, saveToHistory = false)

        val result = registry.dispatch(
            AgentCommand.Error("解析失败"),
            cameraContext()
        )

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("解析失败", (action as AgentAction.Error).message)
    }

    // ------------------------------------------------------------------
    // 4. buildCapabilityDescription 场景过滤
    // ------------------------------------------------------------------

    @Test
    fun `buildCapabilityDescription in CAMERA scene contains only camera and navigation`() {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA, saveToHistory = false)

        val description = registry.buildCapabilityDescription()

        // 检查 Capability 名称前缀（buildCapabilityDescription 格式为 "- name: description"）
        assertTrue(description.contains("- camera:"))
        assertTrue(description.contains("- navigation:"))
        assertFalse("GALLERY 场景 Capability 不应出现在 CAMERA 场景描述中",
            description.contains("- gallery:"))
        assertFalse("SETTINGS 场景 Capability 不应出现在 CAMERA 场景描述中",
            description.contains("- settings:"))
    }

    @Test
    fun `buildCapabilityDescription in GALLERY scene contains only gallery and navigation`() {
        sceneManager.transitionTo(SceneManager.Scene.GALLERY, saveToHistory = false)

        val description = registry.buildCapabilityDescription()

        assertTrue(description.contains("- gallery:"))
        assertTrue(description.contains("- navigation:"))
        assertFalse("CAMERA 场景 Capability 不应出现在 GALLERY 场景描述中",
            description.contains("- camera:"))
        assertFalse("SETTINGS 场景 Capability 不应出现在 GALLERY 场景描述中",
            description.contains("- settings:"))
    }

    // ------------------------------------------------------------------
    // 5. isCommandAvailable
    // ------------------------------------------------------------------

    @Test
    fun `isCommandAvailable returns true for camera command in CAMERA scene`() {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA, saveToHistory = false)

        assertTrue(registry.isCommandAvailable(AgentCommand.CapturePhoto))
    }

    @Test
    fun `isCommandAvailable returns false for camera command in GALLERY scene`() {
        sceneManager.transitionTo(SceneManager.Scene.GALLERY, saveToHistory = false)

        assertFalse(registry.isCommandAvailable(AgentCommand.CapturePhoto))
    }

    @Test
    fun `isCommandAvailable returns true for navigation command in any scene`() {
        sceneManager.transitionTo(SceneManager.Scene.SETTINGS, saveToHistory = false)

        assertTrue(registry.isCommandAvailable(AgentCommand.NavigateTo("camera")))
    }

    // ------------------------------------------------------------------
    // 6. 注册/注销
    // ------------------------------------------------------------------

    @Test
    fun `register adds capability to registry`() {
        val testCapability = CameraCapability()
        registry.register(testCapability)

        assertNotNull(registry.get("camera"))
    }

    @Test
    fun `unregister removes capability from registry`() {
        registry.unregister("camera")

        assertEquals(null, registry.get("camera"))
    }
}
