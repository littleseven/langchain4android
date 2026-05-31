package com.picme.domain.agent

import androidx.navigation.NavController
import com.picme.domain.agent.capability.CameraCapability
import com.picme.domain.agent.capability.GalleryCapability
import com.picme.domain.agent.capability.NavigationCapability
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.AgentScene
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager
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
 * CapabilityRegistry 单元测试
 *
 * 验证命令分发的核心规则：
 * 1. 命令只能分发给当前场景可用的 Capability
 * 2. 找不到 Capability 时返回明确错误
 * 3. 回调未设置时返回错误（不静默失败）
 * 4. 导航命令在所有场景都可用
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityRegistryTest {

    private lateinit var registry: CapabilityRegistry
    private lateinit var sceneManager: SceneManager
    private val testDispatcher = StandardTestDispatcher()

    private val cameraCapability = CameraCapability.getInstance()
    private val galleryCapability = GalleryCapability.getInstance()
    private val navigationCapability = NavigationCapability.getInstance()

    private val mockNavController: NavController = mockk(relaxed = true)

    /**
     * Fake delegate for CameraCapability
     */
    private class FakeCameraDelegate(
        var capturePhotoAction: () -> Unit = {}
    ) : CameraCapability.Delegate {
        override fun onAdjustBeauty(settings: com.picme.beauty.api.BeautySettings) {}
        override fun onSwitchFilter(filterType: com.picme.beauty.api.FilterType) {}
        override fun onSwitchStyle(styleFilter: com.picme.beauty.api.StyleFilter) {}
        override fun onSwitchScene(sceneName: String) {}
        override fun onSwitchRatio(ratio: String) {}
        override fun onAdjustExposure(exposure: Int) {}
        override fun onAdjustZoom(zoomRatio: Float) {}
        override fun onFlipCamera() {}
        override fun onCapturePhoto() = capturePhotoAction()
        override fun onToggleRecording() {}
        override fun onSwitchMode(mode: com.picme.domain.model.MediaType) {}
    }

    /**
     * Fake delegate for GalleryCapability
     */
    private class FakeGalleryDelegate(
        var viewMediaAction: (String?) -> Unit = {}
    ) : GalleryCapability.Delegate {
        override fun onViewMedia(mediaId: String?) = viewMediaAction(mediaId)
        override fun onDeleteMedia(mediaIds: List<String>) {}
        override fun onShareMedia(mediaIds: List<String>) {}
        override fun onSelectMedia(mediaId: String, selected: Boolean) {}
        override fun onSearch(query: String) {}
        override fun onSwitchViewMode(mode: GalleryCapability.ViewMode) {}
        override fun onFavoriteMedia(mediaId: String, favorite: Boolean) {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sceneManager = SceneManager.getInstance()
        registry = CapabilityRegistry.getInstance()

        // Clear internal registry map via reflection
        clearRegistry()

        // Bind delegates for capabilities that need them
        navigationCapability.bindNavController(mockNavController)
    }

    @After
    fun tearDown() {
        // Unbind all delegates
        cameraCapability.unbindDelegate()
        galleryCapability.unbindDelegate()
        navigationCapability.unbindNavController()

        // Clear internal registry map via reflection
        clearRegistry()

        Dispatchers.resetMain()
    }

    /**
     * Clear the CapabilityRegistry internal map via reflection.
     * This is acceptable for testing since there is no public unregister() API.
     */
    private fun clearRegistry() {
        val registryField = CapabilityRegistry::class.java.getDeclaredField("registry")
        registryField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = registryField.get(registry) as MutableMap<String, com.picme.domain.agent.capability.Capability>
        map.clear()
    }

    // ------------------------------------------------------------------
    // 1. 场景过滤验证
    // ------------------------------------------------------------------

    @Test
    fun `dispatch camera command in CAMERA scene succeeds`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA)

        var callbackInvoked = false
        val fakeDelegate = FakeCameraDelegate(capturePhotoAction = { callbackInvoked = true })
        cameraCapability.bindDelegate(fakeDelegate)
        registry.register(cameraCapability)

        val result = registry.dispatch(
            AgentCommand.CapturePhoto,
            AgentContext(scene = AgentScene.CAMERA)
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Success)
        assertTrue(callbackInvoked)
    }

    @Test
    fun `dispatch camera command in GALLERY scene queues and returns text reply`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)

        val fakeDelegate = FakeCameraDelegate()
        cameraCapability.bindDelegate(fakeDelegate)
        registry.register(cameraCapability)

        val result = registry.dispatch(
            AgentCommand.CapturePhoto,
            AgentContext(scene = AgentScene.GALLERY)
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue("跨场景命令应返回 TextReply 并排队", action is AgentAction.TextReply)
        assertTrue((action as AgentAction.TextReply).message.contains("切换"))
    }

    @Test
    fun `dispatch navigation command in any scene succeeds`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)

        registry.register(navigationCapability)

        val result = registry.dispatch(
            AgentCommand.NavigateTo("settings"),
            AgentContext(scene = AgentScene.GALLERY)
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Success)
    }

    // ------------------------------------------------------------------
    // 2. 找不到 Capability
    // ------------------------------------------------------------------

    @Test
    fun `dispatch unregistered command returns error`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA)
        // 不注册任何 Capability

        val result = registry.dispatch(
            AgentCommand.CapturePhoto,
            AgentContext(scene = AgentScene.CAMERA)
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("暂不支持此操作", (action as AgentAction.Error).message)
    }

    // ------------------------------------------------------------------
    // 3. 空回调检测（关键修复验证）
    // ------------------------------------------------------------------

    @Test
    fun `dispatch command with unbound delegate queues and returns text reply`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA)

        // Register CameraCapability without binding delegate
        cameraCapability.unbindDelegate()
        registry.register(cameraCapability)

        val result = registry.dispatch(
            AgentCommand.CapturePhoto,
            AgentContext(scene = AgentScene.CAMERA)
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue("未绑定 delegate 时应排队并返回 TextReply", action is AgentAction.TextReply)
        assertTrue((action as AgentAction.TextReply).message.contains("切换"))
    }

    @Test
    fun `dispatch gallery command with unbound delegate queues and returns text reply`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)

        // Register GalleryCapability without binding delegate
        galleryCapability.unbindDelegate()
        registry.register(galleryCapability)

        val result = registry.dispatch(
            AgentCommand.ViewMedia("123"),
            AgentContext(scene = AgentScene.GALLERY)
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue("未绑定 delegate 时应排队并返回 TextReply", action is AgentAction.TextReply)
        assertTrue((action as AgentAction.TextReply).message.contains("切换"))
    }

    // ------------------------------------------------------------------
    // 4. PageContext 传递
    // ------------------------------------------------------------------

    @Test
    fun `dispatch with GalleryContext uses page context`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)

        var receivedId: String? = null
        val fakeDelegate = FakeGalleryDelegate(viewMediaAction = { id -> receivedId = id })
        galleryCapability.bindDelegate(fakeDelegate)
        registry.register(galleryCapability)

        val galleryContext = PageContext.GalleryContext(
            currentMedia = com.picme.domain.model.MediaAsset(
                id = 42L,
                uri = "content://fake/42",
                type = com.picme.domain.model.MediaType.PHOTO,
                captureDate = System.currentTimeMillis(),
                fileName = "IMG_42.jpg"
            ),
            selectedItems = emptyList()
        )

        val result = registry.dispatch(
            AgentCommand.ViewMedia(null), // 不指定 ID，应该使用 context 中的 currentMedia
            AgentContext(scene = AgentScene.GALLERY),
            galleryContext
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals("42", receivedId)
    }

    // ------------------------------------------------------------------
    // 5. 内置命令处理
    // ------------------------------------------------------------------

    @Test
    fun `dispatch TextReply returns text directly`() = runTest(testDispatcher) {
        val result = registry.dispatch(
            AgentCommand.TextReply("你好"),
            AgentContext(scene = AgentScene.CAMERA)
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.TextReply)
        assertEquals("你好", (action as AgentAction.TextReply).message)
    }

    @Test
    fun `dispatch Error command returns error directly`() = runTest(testDispatcher) {
        val result = registry.dispatch(
            AgentCommand.Error("模型未加载"),
            AgentContext(scene = AgentScene.CAMERA)
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("模型未加载", (action as AgentAction.Error).message)
    }
}
