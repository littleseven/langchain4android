package com.mamba.picme.domain.agent

import androidx.navigation.NavController
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.features.camera.capability.CameraCapability
import com.mamba.picme.features.gallery.capability.GalleryCapability
import com.mamba.picme.domain.agent.capability.NavigationCapability
import com.mamba.picme.features.settings.capability.SettingsCapability
import com.mamba.picme.agent.core.api.context.AgentAction
import com.mamba.picme.agent.core.api.command.AgentCommand
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.api.context.AgentScene
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.model.FaceDetectionEngineMode
import com.mamba.picme.agent.core.api.context.MediaType
import com.mamba.picme.domain.model.ThemeMode
import io.mockk.mockk
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

    private val cameraCapability = CameraCapability.getInstance()
    private val galleryCapability = GalleryCapability.getInstance()
    private val settingsCapability = SettingsCapability.getInstance()
    private val navigationCapability = NavigationCapability.getInstance()

    private val mockNavController: NavController = mockk(relaxed = true)

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

        // 绑定各 Capability 的 delegate / navController
        cameraCapability.bindDelegate(FakeCameraDelegate())
        galleryCapability.bindDelegate(FakeGalleryDelegate())
        settingsCapability.bindDelegate(FakeSettingsDelegate())
        navigationCapability.bindNavController(mockNavController)

        // 注册所有 Capability
        registry.register(navigationCapability)
        registry.register(cameraCapability)
        registry.register(galleryCapability)
        registry.register(settingsCapability)
    }

    @After
    fun tearDown() {
        // 解绑各 Capability 的 delegate / navController
        cameraCapability.unbindDelegate()
        galleryCapability.unbindDelegate()
        settingsCapability.unbindDelegate()
        navigationCapability.unbindNavController()

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
    // Fake Delegates
    // ------------------------------------------------------------------

    private class FakeCameraDelegate : CameraCapability.Delegate {
        override fun onAdjustBeauty(settings: BeautySettings) {}
        override fun onSwitchFilter(filterType: FilterType) {}
        override fun onSwitchStyle(styleFilter: StyleFilter) {}
        override fun onSwitchScene(sceneName: String) {}
        override fun onSwitchRatio(ratio: String) {}
        override fun onAdjustExposure(exposure: Int) {}
        override fun onAdjustZoom(zoomRatio: Float) {}
        override fun onFlipCamera() {}
        override fun onCapturePhoto() {}
        override fun onToggleRecording() {}
        override fun onSwitchMode(mode: MediaType) {}
    }

    private class FakeGalleryDelegate : GalleryCapability.Delegate {
        override fun onViewMedia(mediaId: String?) {}
        override fun onDeleteMedia(mediaIds: List<String>) {}
        override fun onShareMedia(mediaIds: List<String>) {}
        override fun onSelectMedia(mediaId: String, selected: Boolean) {}
        override fun onSearch(query: String) {}
        override fun onSwitchViewMode(mode: GalleryCapability.ViewMode) {}
        override fun onFavoriteMedia(mediaId: String, favorite: Boolean) {}
    }

    private class FakeSettingsDelegate : SettingsCapability.Delegate {
        override fun onChangeTheme(theme: ThemeMode) {}
        override fun onChangeLanguage(language: AppLanguage) {}
        override fun onDownloadModel(modelId: String) {}
        override fun onSwitchFaceEngine(engine: FaceDetectionEngineMode) {}
        override fun onToggleSetting(key: String, enabled: Boolean) {}
    }

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
    fun `dispatch CapturePhoto in GALLERY scene queues and returns text reply`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.GALLERY, saveToHistory = false)

        val result = registry.dispatch(AgentCommand.CapturePhoto, cameraContext())

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.TextReply)
        assertTrue((action as AgentAction.TextReply).message.contains("切换"))
    }

    @Test
    fun `dispatch Gallery command in CAMERA scene queues and returns text reply`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA, saveToHistory = false)

        val result = registry.dispatch(
            AgentCommand.DeleteMedia(listOf("1")),
            cameraContext()
        )

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.TextReply)
        assertTrue((action as AgentAction.TextReply).message.contains("切换"))
    }

    @Test
    fun `dispatch Settings command in CAMERA scene queues and returns text reply`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA, saveToHistory = false)

        val result = registry.dispatch(
            AgentCommand.ChangeTheme("dark"),
            cameraContext()
        )

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.TextReply)
        assertTrue((action as AgentAction.TextReply).message.contains("切换"))
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
    // 6. 注册
    // ------------------------------------------------------------------

    @Test
    fun `register adds capability to registry`() {
        // 先清空 registry，确保测试能力独立
        resetRegistry()

        val testCapability = CameraCapability.getInstance()
        testCapability.bindDelegate(FakeCameraDelegate())
        registry.register(testCapability)

        assertNotNull(registry.get("camera"))
    }
}
