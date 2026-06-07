package com.picme.domain.agent.capability

import com.picme.agent.core.model.AgentAction
import com.picme.agent.core.model.AgentCommand
import com.picme.agent.core.model.AgentContext
import com.picme.agent.core.model.AgentScene
import com.picme.domain.model.AppLanguage
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.domain.model.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.picme.agent.core.SceneManager

/**
 * SettingsCapability 场景化单元测试
 *
 * 验证：在 SETTINGS 场景下所有设置命令均可正确执行，
 * 参数映射（如语言、主题、引擎枚举）准确无误。
 */
class SettingsCapabilityTest {

    private val defaultContext = AgentContext(scene = AgentScene.SETTINGS)

    private lateinit var capability: SettingsCapability
    private val fakeDelegate = FakeDelegate()

    @Before
    fun setUp() {
        capability = SettingsCapability.getInstance()
        capability.bindDelegate(fakeDelegate)
    }

    @After
    fun tearDown() {
        capability.unbindDelegate()
    }

    // ------------------------------------------------------------------
    // 1. 场景绑定验证
    // ------------------------------------------------------------------

    @Test
    fun `activeScenes returns only SETTINGS`() {
        val scenes = capability.activeScenes()
        assertEquals(1, scenes.size)
        assertEquals(SceneManager.Scene.SETTINGS, scenes[0])
    }

    @Test
    fun `supportedCommands contains all settings commands`() {
        val commands = capability.supportedCommands()
        assertEquals(5, commands.size)
        assertTrue(commands.contains("change_theme"))
        assertTrue(commands.contains("change_language"))
        assertTrue(commands.contains("download_model"))
        assertTrue(commands.contains("switch_face_engine"))
        assertTrue(commands.contains("toggle_setting"))
    }

    // ------------------------------------------------------------------
    // 2. 主题切换 — 参数映射验证
    // ------------------------------------------------------------------

    @Test
    fun `execute ChangeTheme light maps to LIGHT`() = runBlocking {
        val result = capability.execute(AgentCommand.ChangeTheme("light"), defaultContext)

        assertTrue(result.isSuccess)
        assertEquals(ThemeMode.LIGHT, fakeDelegate.lastTheme)
    }

    @Test
    fun `execute ChangeTheme dark maps to DARK`() = runBlocking {
        val result = capability.execute(AgentCommand.ChangeTheme("dark"), defaultContext)

        assertTrue(result.isSuccess)
        assertEquals(ThemeMode.DARK, fakeDelegate.lastTheme)
    }

    @Test
    fun `execute ChangeTheme system maps to SYSTEM`() = runBlocking {
        val result = capability.execute(AgentCommand.ChangeTheme("system"), defaultContext)

        assertTrue(result.isSuccess)
        assertEquals(ThemeMode.SYSTEM, fakeDelegate.lastTheme)
    }

    @Test
    fun `execute ChangeTheme with Chinese alias maps correctly`() = runBlocking {
        val result = capability.execute(AgentCommand.ChangeTheme("深色"), defaultContext)

        assertTrue(result.isSuccess)
        assertEquals(ThemeMode.DARK, fakeDelegate.lastTheme)
    }

    @Test
    fun `execute ChangeTheme unknown returns Error`() = runBlocking {
        fakeDelegate.reset()

        val result = capability.execute(AgentCommand.ChangeTheme("purple"), defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("未知的主题模式: purple，支持 light/dark/system", (action as AgentAction.Error).message)
    }

    // ------------------------------------------------------------------
    // 3. 语言切换 — 参数映射验证
    // ------------------------------------------------------------------

    @Test
    fun `execute ChangeLanguage zh maps to CHINESE`() = runBlocking {
        val result = capability.execute(AgentCommand.ChangeLanguage("zh"), defaultContext)

        assertTrue(result.isSuccess)
        assertEquals(AppLanguage.CHINESE, fakeDelegate.lastLanguage)
    }

    @Test
    fun `execute ChangeLanguage en maps to ENGLISH`() = runBlocking {
        val result = capability.execute(AgentCommand.ChangeLanguage("en"), defaultContext)

        assertTrue(result.isSuccess)
        assertEquals(AppLanguage.ENGLISH, fakeDelegate.lastLanguage)
    }

    @Test
    fun `execute ChangeLanguage system maps to SYSTEM`() = runBlocking {
        val result = capability.execute(AgentCommand.ChangeLanguage("system"), defaultContext)

        assertTrue(result.isSuccess)
        assertEquals(AppLanguage.SYSTEM, fakeDelegate.lastLanguage)
    }

    @Test
    fun `execute ChangeLanguage unknown returns Error`() = runBlocking {
        fakeDelegate.reset()

        val result = capability.execute(AgentCommand.ChangeLanguage("fr"), defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("不支持的语言: fr，支持 zh/en/system", (action as AgentAction.Error).message)
    }

    // ------------------------------------------------------------------
    // 4. 模型下载
    // ------------------------------------------------------------------

    @Test
    fun `execute DownloadModel with modelId triggers onDownloadModel`() = runBlocking {
        val result = capability.execute(AgentCommand.DownloadModel("qwen3-4b"), defaultContext)

        assertTrue(result.isSuccess)
        assertEquals("qwen3-4b", fakeDelegate.lastModelId)
    }

    @Test
    fun `execute DownloadModel with blank modelId returns Error`() = runBlocking {
        fakeDelegate.reset()

        val result = capability.execute(AgentCommand.DownloadModel(""), defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("请指定模型 ID", (action as AgentAction.Error).message)
    }

    // ------------------------------------------------------------------
    // 5. 人脸检测引擎切换
    // ------------------------------------------------------------------

    @Test
    fun `execute SwitchFaceEngine mediapipe maps correctly`() = runBlocking {
        val result = capability.execute(AgentCommand.SwitchFaceEngine("mediapipe"), defaultContext)

        assertTrue(result.isSuccess)
        assertEquals(FaceDetectionEngineMode.MEDIAPIPE, fakeDelegate.lastEngine)
    }

    @Test
    fun `execute SwitchFaceEngine ncnn maps correctly`() = runBlocking {
        val result = capability.execute(AgentCommand.SwitchFaceEngine("ncnn"), defaultContext)

        assertTrue(result.isSuccess)
        assertEquals(FaceDetectionEngineMode.NCNN, fakeDelegate.lastEngine)
    }

    @Test
    fun `execute SwitchFaceEngine unknown returns Error`() = runBlocking {
        fakeDelegate.reset()

        val result = capability.execute(AgentCommand.SwitchFaceEngine("unknown"), defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals(
            "未知的人脸检测引擎: unknown，支持 mediapipe/mnn/ncnn/custom",
            (action as AgentAction.Error).message
        )
    }

    // ------------------------------------------------------------------
    // 6. 设置项开关
    // ------------------------------------------------------------------

    @Test
    fun `execute ToggleSetting triggers onToggleSetting`() = runBlocking {
        val result = capability.execute(
            AgentCommand.ToggleSetting("debug_mode", true),
            defaultContext
        )

        assertTrue(result.isSuccess)
        assertEquals("debug_mode", fakeDelegate.lastToggleKey)
        assertEquals(true, fakeDelegate.lastToggleEnabled)
    }

    @Test
    fun `execute ToggleSetting with blank key returns Error`() = runBlocking {
        fakeDelegate.reset()

        val result = capability.execute(
            AgentCommand.ToggleSetting("", false),
            defaultContext
        )

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("请指定设置项 key", (action as AgentAction.Error).message)
    }

    // ------------------------------------------------------------------
    // 7. Delegate 未绑定时必须返回 Error（不允许静默失败）
    // ------------------------------------------------------------------

    @Test
    fun `execute ChangeTheme without delegate returns Error`() = runBlocking {
        capability.unbindDelegate()

        val result = capability.execute(AgentCommand.ChangeTheme("dark"), defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue("Delegate 为空时应返回 Error", action is AgentAction.Error)
        assertEquals("设置页面未激活，请先切换到设置页面", (action as AgentAction.Error).message)
    }

    @Test
    fun `execute ToggleSetting without delegate returns Error`() = runBlocking {
        capability.unbindDelegate()

        val result = capability.execute(
            AgentCommand.ToggleSetting("debug_mode", true),
            defaultContext
        )

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("设置页面未激活，请先切换到设置页面", (action as AgentAction.Error).message)
    }

    // ------------------------------------------------------------------
    // 8. 不支持命令
    // ------------------------------------------------------------------

    @Test
    fun `execute unsupported command returns Error`() = runBlocking {
        val result = capability.execute(AgentCommand.CapturePhoto, defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertTrue((action as AgentAction.Error).message.contains("不支持"))
    }

    // ------------------------------------------------------------------
    // 9. 自描述能力
    // ------------------------------------------------------------------

    @Test
    fun `buildCapabilityDescription contains all commands`() {
        val description = capability.buildCapabilityDescription()

        assertTrue(description.contains("settings"))
        assertTrue(description.contains("change_theme"))
        assertTrue(description.contains("change_language"))
        assertTrue(description.contains("switch_face_engine"))
    }

    // ------------------------------------------------------------------
    // Fake Delegate
    // ------------------------------------------------------------------

    private class FakeDelegate : SettingsCapability.Delegate {
        var lastTheme: ThemeMode? = null
        var lastLanguage: AppLanguage? = null
        var lastModelId: String? = null
        var lastEngine: FaceDetectionEngineMode? = null
        var lastToggleKey: String? = null
        var lastToggleEnabled: Boolean? = null

        override fun onChangeTheme(theme: ThemeMode) {
            lastTheme = theme
        }

        override fun onChangeLanguage(language: AppLanguage) {
            lastLanguage = language
        }

        override fun onDownloadModel(modelId: String) {
            lastModelId = modelId
        }

        override fun onSwitchFaceEngine(engine: FaceDetectionEngineMode) {
            lastEngine = engine
        }

        override fun onToggleSetting(key: String, enabled: Boolean) {
            lastToggleKey = key
            lastToggleEnabled = enabled
        }

        fun reset() {
            lastTheme = null
            lastLanguage = null
            lastModelId = null
            lastEngine = null
            lastToggleKey = null
            lastToggleEnabled = null
        }
    }
}
