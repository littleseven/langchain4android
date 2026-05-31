package com.picme.domain.agent

import com.picme.domain.agent.capability.GalleryCapability
import com.picme.domain.agent.capability.NavigationCapability
import com.picme.domain.agent.capability.SettingsCapability
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.AgentScene
import com.picme.domain.agent.model.SceneManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 页面级 Agent 集成测试
 *
 * 验证：页面进入/离开时场景切换和 Capability 注册/注销的正确性。
 * 这些测试如果之前存在，就能发现 Gallery 页面场景为 UNKNOWN 导致 Capability 不可用的 bug。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageAgentIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sceneManager: SceneManager
    private lateinit var registry: CapabilityRegistry

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sceneManager = SceneManager.getInstance()
        sceneManager.clearHistory()
        sceneManager.transitionTo(SceneManager.Scene.UNKNOWN, saveToHistory = false)

        registry = CapabilityRegistry.getInstance()
        resetRegistry()
    }

    @After
    fun tearDown() {
        resetRegistry()
        sceneManager.clearHistory()
        Dispatchers.resetMain()
    }

    private fun resetRegistry() {
        val field = CapabilityRegistry::class.java.getDeclaredField("registry")
        field.isAccessible = true
        val map = field.get(registry) as MutableMap<*, *>
        map.clear()
    }

    // ------------------------------------------------------------------
    // 1. 场景切换验证
    // ------------------------------------------------------------------

    @Test
    fun `transitionTo GALLERY sets current scene to GALLERY`() {
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)

        assertEquals(SceneManager.Scene.GALLERY, sceneManager.currentScene.value)
    }

    @Test
    fun `transitionTo SETTINGS sets current scene to SETTINGS`() {
        sceneManager.transitionTo(SceneManager.Scene.SETTINGS)

        assertEquals(SceneManager.Scene.SETTINGS, sceneManager.currentScene.value)
    }

    @Test
    fun `transitionTo DEBUG sets current scene to DEBUG`() {
        sceneManager.transitionTo(SceneManager.Scene.DEBUG)

        assertEquals(SceneManager.Scene.DEBUG, sceneManager.currentScene.value)
    }

    @Test
    fun `transitionTo CAMERA sets current scene to CAMERA`() {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA)

        assertEquals(SceneManager.Scene.CAMERA, sceneManager.currentScene.value)
    }

    @Test
    fun `multiple transitions keep correct scene`() {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA)
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)
        sceneManager.transitionTo(SceneManager.Scene.SETTINGS)

        assertEquals(SceneManager.Scene.SETTINGS, sceneManager.currentScene.value)
    }

    // ------------------------------------------------------------------
    // 2. Capability 注册后在对应场景可用
    // ------------------------------------------------------------------

    @Test
    fun `GalleryCapability registered and available in GALLERY scene`() {
        val galleryCap = GalleryCapability()
        registry.register(galleryCap)
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)

        val capabilities = registry.getCapabilitiesForCurrentScene()
        val names = capabilities.map { it.name }

        assertTrue("GalleryCapability 应在 GALLERY 场景可用", names.contains("gallery"))
    }

    @Test
    fun `GalleryCapability not available in CAMERA scene`() {
        val galleryCap = GalleryCapability()
        registry.register(galleryCap)
        sceneManager.transitionTo(SceneManager.Scene.CAMERA)

        val capabilities = registry.getCapabilitiesForCurrentScene()
        val names = capabilities.map { it.name }

        assertFalse("GalleryCapability 不应在 CAMERA 场景可用", names.contains("gallery"))
    }

    @Test
    fun `SettingsCapability registered and available in SETTINGS scene`() {
        val settingsCap = SettingsCapability()
        registry.register(settingsCap)
        sceneManager.transitionTo(SceneManager.Scene.SETTINGS)

        val capabilities = registry.getCapabilitiesForCurrentScene()
        val names = capabilities.map { it.name }

        assertTrue("SettingsCapability 应在 SETTINGS 场景可用", names.contains("settings"))
    }

    @Test
    fun `NavigationCapability available in all scenes including UNKNOWN`() {
        val navCap = NavigationCapability(onNavigate = {}, onBack = {})
        registry.register(navCap)

        // 测试所有场景
        SceneManager.Scene.entries.forEach { scene ->
            sceneManager.transitionTo(scene, saveToHistory = false)
            val capabilities = registry.getCapabilitiesForCurrentScene()
            val names = capabilities.map { it.name }
            assertTrue(
                "NavigationCapability 应在 $scene 场景可用",
                names.contains("navigation")
            )
        }
    }

    // ------------------------------------------------------------------
    // 3. Capability 注册/注销生命周期
    // ------------------------------------------------------------------

    @Test
    fun `unregister removes capability from registry`() {
        val galleryCap = GalleryCapability()
        registry.register(galleryCap)
        assertNotNull(registry.get("gallery"))

        registry.unregister("gallery")
        assertNull(registry.get("gallery"))
    }

    @Test
    fun `unregistered capability not available in any scene`() {
        val galleryCap = GalleryCapability()
        registry.register(galleryCap)
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)

        // 先确认已注册
        var capabilities = registry.getCapabilitiesForCurrentScene()
        assertTrue(capabilities.any { it.name == "gallery" })

        // 注销后确认不可用
        registry.unregister("gallery")
        capabilities = registry.getCapabilitiesForCurrentScene()
        assertFalse(capabilities.any { it.name == "gallery" })
    }

    @Test
    fun `multiple capabilities can be registered and unregistered independently`() {
        val galleryCap = GalleryCapability()
        val navCap = NavigationCapability(onNavigate = {}, onBack = {})

        registry.register(galleryCap)
        registry.register(navCap)

        sceneManager.transitionTo(SceneManager.Scene.GALLERY)
        var capabilities = registry.getCapabilitiesForCurrentScene()
        assertEquals(2, capabilities.size)

        // 只注销 gallery
        registry.unregister("gallery")
        capabilities = registry.getCapabilitiesForCurrentScene()
        assertEquals(1, capabilities.size)
        assertEquals("navigation", capabilities[0].name)
    }

    // ------------------------------------------------------------------
    // 4. UNKNOWN 场景下的行为
    // ------------------------------------------------------------------

    @Test
    fun `UNKNOWN scene with no registered capabilities returns empty list`() {
        sceneManager.transitionTo(SceneManager.Scene.UNKNOWN)

        val capabilities = registry.getCapabilitiesForCurrentScene()

        assertTrue("UNKNOWN 场景无注册 Capability 时应返回空列表", capabilities.isEmpty())
    }

    @Test
    fun `UNKNOWN scene with NavigationCapability returns navigation`() {
        val navCap = NavigationCapability(onNavigate = {}, onBack = {})
        registry.register(navCap)
        sceneManager.transitionTo(SceneManager.Scene.UNKNOWN)

        val capabilities = registry.getCapabilitiesForCurrentScene()
        val names = capabilities.map { it.name }

        assertTrue("NavigationCapability 应在 UNKNOWN 场景可用", names.contains("navigation"))
    }

    @Test
    fun `dispatch NavigateTo in UNKNOWN scene with NavigationCapability succeeds`() = runTest(testDispatcher) {
        val navCap = NavigationCapability(
            onNavigate = {},
            onBack = {}
        )
        registry.register(navCap)
        sceneManager.transitionTo(SceneManager.Scene.UNKNOWN)

        val context = AgentContext(scene = AgentScene.CAMERA)
        val result = registry.dispatch(AgentCommand.NavigateTo("camera"), context)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue("导航命令在 UNKNOWN 场景也应执行", action is AgentAction.Success)
    }

    @Test
    fun `dispatch non-navigation command in UNKNOWN scene returns error`() = runTest(testDispatcher) {
        val galleryCap = GalleryCapability()
        registry.register(galleryCap)
        sceneManager.transitionTo(SceneManager.Scene.UNKNOWN)

        val context = AgentContext(scene = AgentScene.CAMERA)
        val result = registry.dispatch(AgentCommand.ViewMedia("1"), context)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue("非导航命令在 UNKNOWN 场景应返回错误", action is AgentAction.Error)
    }

    // ------------------------------------------------------------------
    // 5. 跨页面导航场景切换
    // ------------------------------------------------------------------

    @Test
    fun `simulate camera to gallery transition updates scene`() {
        // 模拟从 Camera 页面导航到 Gallery 页面
        sceneManager.transitionTo(SceneManager.Scene.CAMERA)
        assertEquals(SceneManager.Scene.CAMERA, sceneManager.currentScene.value)

        // Camera 页面离开，Gallery 页面进入
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)
        assertEquals(SceneManager.Scene.GALLERY, sceneManager.currentScene.value)
    }

    @Test
    fun `simulate gallery to settings transition updates scene`() {
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)
        sceneManager.transitionTo(SceneManager.Scene.SETTINGS)

        assertEquals(SceneManager.Scene.SETTINGS, sceneManager.currentScene.value)
    }

    @Test
    fun `scene history tracks navigation`() {
        sceneManager.transitionTo(SceneManager.Scene.CAMERA)
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)
        sceneManager.transitionTo(SceneManager.Scene.SETTINGS)

        // 返回上一页
        val success = sceneManager.navigateBack()
        assertTrue(success)
        assertEquals(SceneManager.Scene.GALLERY, sceneManager.currentScene.value)
    }

    // ------------------------------------------------------------------
    // 6. 端到端：注册 + 场景切换 + 命令分发
    // ------------------------------------------------------------------

    @Test
    fun `end to end gallery page navigation command`() = runTest(testDispatcher) {
        // 模拟 Gallery 页面进入时的完整流程
        val galleryCap = GalleryCapability()
        val navCap = NavigationCapability(
            onNavigate = {},
            onBack = {}
        )
        registry.register(galleryCap)
        registry.register(navCap)
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)

        // 验证 Gallery 场景下可用的 Capability
        val capabilities = registry.getCapabilitiesForCurrentScene()
        val names = capabilities.map { it.name }
        assertTrue(names.contains("gallery"))
        assertTrue(names.contains("navigation"))

        // 验证导航命令可以执行
        val context = AgentContext(scene = AgentScene.GALLERY)
        val result = registry.dispatch(AgentCommand.NavigateTo("camera"), context)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() is AgentAction.Success)
    }

    @Test
    fun `end to end settings page navigation command`() = runTest(testDispatcher) {
        val settingsCap = SettingsCapability()
        val navCap = NavigationCapability(
            onNavigate = {},
            onBack = {}
        )
        registry.register(settingsCap)
        registry.register(navCap)
        sceneManager.transitionTo(SceneManager.Scene.SETTINGS)

        val capabilities = registry.getCapabilitiesForCurrentScene()
        val names = capabilities.map { it.name }
        assertTrue(names.contains("settings"))
        assertTrue(names.contains("navigation"))

        val context = AgentContext(scene = AgentScene.SETTINGS)
        val result = registry.dispatch(AgentCommand.NavigateTo("camera"), context)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() is AgentAction.Success)
    }
}
