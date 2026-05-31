package com.picme.domain.agent

import com.picme.domain.agent.capability.CameraCapability
import com.picme.domain.agent.capability.GalleryCapability
import com.picme.domain.agent.capability.NavigationCapability
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.AgentScene
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager
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

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // 每个测试使用新的实例（通过反射重置单例）
        sceneManager = SceneManager.getInstance()
        registry = CapabilityRegistry.getInstance()
        // 清理注册表
        registry.getAll().forEach {
            registry.unregister(it.name)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // 1. 场景过滤验证
    // ------------------------------------------------------------------

    @Test
    fun `dispatch camera command in CAMERA scene succeeds`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA)

        var callbackInvoked = false
        val cameraCapability = CameraCapability(
            onCapturePhoto = { callbackInvoked = true }
        )
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
    fun `dispatch camera command in GALLERY scene returns error`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)

        val cameraCapability = CameraCapability(
            onCapturePhoto = { }
        )
        registry.register(cameraCapability)

        val result = registry.dispatch(
            AgentCommand.CapturePhoto,
            AgentContext(scene = AgentScene.GALLERY)
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue("跨场景命令应返回 Error", action is AgentAction.Error)
        assertTrue((action as AgentAction.Error).message.contains("无法执行"))
    }

    @Test
    fun `dispatch navigation command in any scene succeeds`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)

        var navigateInvoked = false
        val navCapability = NavigationCapability(
            onNavigate = { navigateInvoked = true },
            onBack = { }
        )
        registry.register(navCapability)

        val result = registry.dispatch(
            AgentCommand.NavigateTo("settings"),
            AgentContext(scene = AgentScene.GALLERY)
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Success)
        assertTrue(navigateInvoked)
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
    fun `dispatch command with null callback returns error`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA)

        // 注册一个所有回调都为 null 的 CameraCapability
        val cameraCapability = CameraCapability()
        registry.register(cameraCapability)

        val result = registry.dispatch(
            AgentCommand.CapturePhoto,
            AgentContext(scene = AgentScene.CAMERA)
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue("空回调必须返回 Error，不允许静默失败", action is AgentAction.Error)
        assertEquals("相机拍照未初始化", (action as AgentAction.Error).message)
    }

    @Test
    fun `dispatch gallery command with null callback returns error`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)

        val galleryCapability = GalleryCapability()
        registry.register(galleryCapability)

        val result = registry.dispatch(
            AgentCommand.ViewMedia("123"),
            AgentContext(scene = AgentScene.GALLERY)
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("相册查看功能未初始化", (action as AgentAction.Error).message)
    }

    // ------------------------------------------------------------------
    // 4. PageContext 传递
    // ------------------------------------------------------------------

    @Test
    fun `dispatch with GalleryContext uses page context`() = runTest(testDispatcher) {
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)

        var receivedId: String? = null
        val galleryCapability = GalleryCapability(
            onViewMedia = { id -> receivedId = id }
        )
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