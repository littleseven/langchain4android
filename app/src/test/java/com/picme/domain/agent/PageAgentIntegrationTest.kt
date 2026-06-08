package com.picme.domain.agent

import com.picme.features.gallery.capability.GalleryCapability
import com.picme.domain.agent.capability.NavigationCapability
import com.picme.features.settings.capability.SettingsCapability
import com.picme.agent.core.api.context.AgentAction
import com.picme.agent.core.api.command.AgentCommand
import com.picme.agent.core.api.context.AgentContext
import com.picme.agent.core.api.context.AgentScene
import com.picme.agent.core.runtime.state.SceneManager
import io.mockk.mockk
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
import androidx.navigation.NavController
import com.picme.domain.model.AppLanguage
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.domain.model.ThemeMode

/**
 * Page-level Agent integration tests
 *
 * Verifies: scene transitions on page enter/leave and Capability register/unregister correctness.
 * These tests would have caught the bug where Gallery page scene was UNKNOWN causing Capability unavailability.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageAgentIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sceneManager: SceneManager
    private lateinit var registry: CapabilityRegistry

    private lateinit var galleryCapability: GalleryCapability
    private lateinit var navigationCapability: NavigationCapability
    private lateinit var settingsCapability: SettingsCapability

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sceneManager = SceneManager.getInstance()
        sceneManager.clearHistory()
        sceneManager.transitionTo(SceneManager.Scene.UNKNOWN, saveToHistory = false)

        registry = CapabilityRegistry.getInstance()
        resetRegistry()

        galleryCapability = GalleryCapability.getInstance()
        navigationCapability = NavigationCapability.getInstance()
        settingsCapability = SettingsCapability.getInstance()

        bindCapabilities()
    }

    @After
    fun tearDown() {
        unbindCapabilities()
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

    private fun bindCapabilities() {
        galleryCapability.bindDelegate(FakeGalleryDelegate())
        navigationCapability.bindNavController(mockk<NavController>(relaxed = true))
        settingsCapability.bindDelegate(FakeSettingsDelegate())
    }

    private fun unbindCapabilities() {
        galleryCapability.unbindDelegate()
        navigationCapability.unbindNavController()
        settingsCapability.unbindDelegate()
    }

    // ------------------------------------------------------------------
    // Fake delegates
    // ------------------------------------------------------------------

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
    // 1. Scene transition verification
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
    // 2. Capability registered and available in corresponding scene
    // ------------------------------------------------------------------

    @Test
    fun `GalleryCapability registered and available in GALLERY scene`() {
        registry.register(galleryCapability)
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)

        val capabilities = registry.getCapabilitiesForCurrentScene()
        val names = capabilities.map { it.name }

        assertTrue("GalleryCapability should be available in GALLERY scene", names.contains("gallery"))
    }

    @Test
    fun `GalleryCapability not available in CAMERA scene`() {
        registry.register(galleryCapability)
        sceneManager.transitionTo(SceneManager.Scene.CAMERA)

        val capabilities = registry.getCapabilitiesForCurrentScene()
        val names = capabilities.map { it.name }

        assertFalse("GalleryCapability should not be available in CAMERA scene", names.contains("gallery"))
    }

    @Test
    fun `SettingsCapability registered and available in SETTINGS scene`() {
        registry.register(settingsCapability)
        sceneManager.transitionTo(SceneManager.Scene.SETTINGS)

        val capabilities = registry.getCapabilitiesForCurrentScene()
        val names = capabilities.map { it.name }

        assertTrue("SettingsCapability should be available in SETTINGS scene", names.contains("settings"))
    }

    @Test
    fun `NavigationCapability available in all scenes including UNKNOWN`() {
        registry.register(navigationCapability)

        // Test all scenes
        SceneManager.Scene.entries.forEach { scene ->
            sceneManager.transitionTo(scene, saveToHistory = false)
            val capabilities = registry.getCapabilitiesForCurrentScene()
            val names = capabilities.map { it.name }
            assertTrue(
                "NavigationCapability should be available in $scene scene",
                names.contains("navigation")
            )
        }
    }

    // ------------------------------------------------------------------
    // 3. Capability register/unregister lifecycle
    // ------------------------------------------------------------------

    @Test
    fun `resetRegistry removes capability from registry`() {
        registry.register(galleryCapability)
        assertNotNull(registry.get("gallery"))

        resetRegistry()
        assertNull(registry.get("gallery"))
    }

    @Test
    fun `removed capability not available in any scene`() {
        registry.register(galleryCapability)
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)

        // Confirm registered
        var capabilities = registry.getCapabilitiesForCurrentScene()
        assertTrue(capabilities.any { it.name == "gallery" })

        // After reset, confirm unavailable
        resetRegistry()
        capabilities = registry.getCapabilitiesForCurrentScene()
        assertFalse(capabilities.any { it.name == "gallery" })
    }

    @Test
    fun `multiple capabilities can be registered and cleared independently`() {
        registry.register(galleryCapability)
        registry.register(navigationCapability)

        sceneManager.transitionTo(SceneManager.Scene.GALLERY)
        var capabilities = registry.getCapabilitiesForCurrentScene()
        assertEquals(2, capabilities.size)

        // Clear only gallery by resetting registry (simulates full clear since unregister does not exist)
        resetRegistry()
        // Re-register only navigation
        registry.register(navigationCapability)

        sceneManager.transitionTo(SceneManager.Scene.GALLERY)
        capabilities = registry.getCapabilitiesForCurrentScene()
        assertEquals(1, capabilities.size)
        assertEquals("navigation", capabilities[0].name)
    }

    // ------------------------------------------------------------------
    // 4. Behavior in UNKNOWN scene
    // ------------------------------------------------------------------

    @Test
    fun `UNKNOWN scene with no registered capabilities returns empty list`() {
        sceneManager.transitionTo(SceneManager.Scene.UNKNOWN)

        val capabilities = registry.getCapabilitiesForCurrentScene()

        assertTrue("UNKNOWN scene with no registered capabilities should return empty list", capabilities.isEmpty())
    }

    @Test
    fun `UNKNOWN scene with NavigationCapability returns navigation`() {
        registry.register(navigationCapability)
        sceneManager.transitionTo(SceneManager.Scene.UNKNOWN)

        val capabilities = registry.getCapabilitiesForCurrentScene()
        val names = capabilities.map { it.name }

        assertTrue("NavigationCapability should be available in UNKNOWN scene", names.contains("navigation"))
    }

    @Test
    fun `dispatch NavigateTo in UNKNOWN scene with NavigationCapability succeeds`() = runTest(testDispatcher) {
        registry.register(navigationCapability)
        sceneManager.transitionTo(SceneManager.Scene.UNKNOWN)

        val context = AgentContext(scene = AgentScene.CAMERA)
        val result = registry.dispatch(AgentCommand.NavigateTo("camera"), context)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue("Navigation command should execute in UNKNOWN scene", action is AgentAction.Success)
    }

    @Test
    fun `dispatch non-navigation command in UNKNOWN scene queues and returns text reply`() = runTest(testDispatcher) {
        registry.register(galleryCapability)
        sceneManager.transitionTo(SceneManager.Scene.UNKNOWN)

        val context = AgentContext(scene = AgentScene.CAMERA)
        val result = registry.dispatch(AgentCommand.ViewMedia("1"), context)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue("Non-navigation command in UNKNOWN scene should queue and return TextReply", action is AgentAction.TextReply)
        assertTrue((action as AgentAction.TextReply).message.contains("切换"))
    }

    // ------------------------------------------------------------------
    // 5. Cross-page navigation scene transitions
    // ------------------------------------------------------------------

    @Test
    fun `simulate camera to gallery transition updates scene`() {
        // Simulate navigating from Camera page to Gallery page
        sceneManager.transitionTo(SceneManager.Scene.CAMERA)
        assertEquals(SceneManager.Scene.CAMERA, sceneManager.currentScene.value)

        // Camera page leaves, Gallery page enters
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

        // Navigate back
        val success = sceneManager.navigateBack()
        assertTrue(success)
        assertEquals(SceneManager.Scene.GALLERY, sceneManager.currentScene.value)
    }

    // ------------------------------------------------------------------
    // 6. End-to-end: register + scene switch + command dispatch
    // ------------------------------------------------------------------

    @Test
    fun `end to end gallery page navigation command`() = runTest(testDispatcher) {
        // Simulate full flow when Gallery page enters
        registry.register(galleryCapability)
        registry.register(navigationCapability)
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)

        // Verify available capabilities in Gallery scene
        val capabilities = registry.getCapabilitiesForCurrentScene()
        val names = capabilities.map { it.name }
        assertTrue(names.contains("gallery"))
        assertTrue(names.contains("navigation"))

        // Verify navigation command can execute
        val context = AgentContext(scene = AgentScene.GALLERY)
        val result = registry.dispatch(AgentCommand.NavigateTo("camera"), context)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() is AgentAction.Success)
    }

    @Test
    fun `end to end settings page navigation command`() = runTest(testDispatcher) {
        registry.register(settingsCapability)
        registry.register(navigationCapability)
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
