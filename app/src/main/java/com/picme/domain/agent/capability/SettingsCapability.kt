package com.picme.domain.agent.capability

import com.picme.core.common.Logger
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager
import com.picme.domain.model.AppLanguage
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.domain.model.ThemeMode

/**
 * 设置控制 Capability
 *
 * 应用级单例，通过 delegate 模式与页面解耦：
 * - 在 Application.onCreate() 中注册一次，永不注销
 * - SettingsScreen 通过绑定 delegate 提供实际执行逻辑
 * - 页面离开时解绑 delegate，Capability 仍然注册但返回不可用状态
 *
 * 支持命令：
 * - change_theme: 切换主题（light/dark/system）
 * - change_language: 切换语言（zh/en）
 * - download_model: 下载模型
 * - switch_face_engine: 切换人脸检测引擎
 * - toggle_setting: 开关设置项
 */
class SettingsCapability : BaseCapability() {

    companion object {
        private const val TAG = "SettingsCapability"

        @Volatile
        private var instance: SettingsCapability? = null

        fun getInstance(): SettingsCapability {
            return instance ?: synchronized(this) {
                instance ?: SettingsCapability().also { instance = it }
            }
        }
    }

    override val name = "settings"
    override val description = "应用设置控制：主题切换、语言切换、模型管理、调试选项"

    /**
     * 设置操作委托接口
     */
    interface Delegate {
        fun onChangeTheme(theme: ThemeMode)
        fun onChangeLanguage(language: AppLanguage)
        fun onDownloadModel(modelId: String)
        fun onSwitchFaceEngine(engine: FaceDetectionEngineMode)
        fun onToggleSetting(key: String, enabled: Boolean)
    }

    @Volatile
    var delegate: Delegate? = null
        private set

    fun bindDelegate(delegate: Delegate) {
        this.delegate = delegate
        Logger.i(TAG, "Delegate bound")
    }

    fun unbindDelegate() {
        this.delegate = null
        Logger.i(TAG, "Delegate unbound")
    }

    override fun isAvailable(): Boolean {
        return delegate != null
    }

    override fun activeScenes(): List<SceneManager.Scene> =
        listOf(SceneManager.Scene.SETTINGS)

    override fun supportedCommands(): List<String> = listOf(
        "change_theme",
        "change_language",
        "download_model",
        "switch_face_engine",
        "toggle_setting"
    )

    override fun getCommandDescription(command: String): String = when (command) {
        "change_theme" -> "切换主题模式，参数：theme (light/dark/system)"
        "change_language" -> "切换应用语言，参数：language (zh/en)"
        "download_model" -> "下载AI模型，参数：model_id"
        "switch_face_engine" -> "切换人脸检测引擎，参数：engine (mediapipe/mnn/ncnn/custom)"
        "toggle_setting" -> "开关设置项，参数：key, enabled (true/false)"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        Logger.i(TAG, "Executing command: $command")

        val d = delegate
            ?: return Result.success(
                AgentAction.Error("设置页面未激活，请先切换到设置页面")
            )

        return when (command) {
            is AgentCommand.ChangeTheme -> {
                handleChangeTheme(command, d)
            }
            is AgentCommand.ChangeLanguage -> {
                handleChangeLanguage(command, d)
            }
            is AgentCommand.DownloadModel -> {
                handleDownloadModel(command, d)
            }
            is AgentCommand.SwitchFaceEngine -> {
                handleSwitchFaceEngine(command, d)
            }
            is AgentCommand.ToggleSetting -> {
                handleToggleSetting(command, d)
            }
            else -> Result.success(AgentAction.Error("不支持的设置命令: $command"))
        }
    }

    private fun handleChangeTheme(
        command: AgentCommand.ChangeTheme,
        d: Delegate
    ): Result<AgentAction> {
        val themeMode = when (command.theme.lowercase()) {
            "light", "浅色", "亮色" -> ThemeMode.LIGHT
            "dark", "深色", "暗色" -> ThemeMode.DARK
            "system", "系统", "跟随系统" -> ThemeMode.SYSTEM
            else -> {
                return Result.success(
                    AgentAction.Error("未知的主题模式: ${command.theme}，支持 light/dark/system")
                )
            }
        }

        d.onChangeTheme(themeMode)
        return Result.success(AgentAction.Success(command = command))
    }

    private fun handleChangeLanguage(
        command: AgentCommand.ChangeLanguage,
        d: Delegate
    ): Result<AgentAction> {
        val language = when (command.language.lowercase()) {
            "zh", "中文", "简体中文", "cn" -> AppLanguage.CHINESE
            "en", "英文", "英语", "english" -> AppLanguage.ENGLISH
            "system", "系统默认" -> AppLanguage.SYSTEM
            else -> {
                return Result.success(
                    AgentAction.Error("不支持的语言: ${command.language}，支持 zh/en/system")
                )
            }
        }

        d.onChangeLanguage(language)
        return Result.success(AgentAction.Success(command = command))
    }

    private fun handleDownloadModel(
        command: AgentCommand.DownloadModel,
        d: Delegate
    ): Result<AgentAction> {
        if (command.modelId.isBlank()) {
            return Result.success(AgentAction.Error("请指定模型 ID"))
        }

        d.onDownloadModel(command.modelId)
        return Result.success(AgentAction.Success(command = command))
    }

    private fun handleSwitchFaceEngine(
        command: AgentCommand.SwitchFaceEngine,
        d: Delegate
    ): Result<AgentAction> {
        val engine = when (command.engine.lowercase()) {
            "mediapipe" -> FaceDetectionEngineMode.MEDIAPIPE
            "mnn" -> FaceDetectionEngineMode.MNN
            "ncnn" -> FaceDetectionEngineMode.NCNN
            "custom" -> FaceDetectionEngineMode.CUSTOM
            else -> {
                return Result.success(
                    AgentAction.Error("未知的人脸检测引擎: ${command.engine}，支持 mediapipe/mnn/ncnn/custom")
                )
            }
        }

        d.onSwitchFaceEngine(engine)
        return Result.success(AgentAction.Success(command = command))
    }

    private fun handleToggleSetting(
        command: AgentCommand.ToggleSetting,
        d: Delegate
    ): Result<AgentAction> {
        if (command.settingKey.isBlank()) {
            return Result.success(AgentAction.Error("请指定设置项 key"))
        }

        d.onToggleSetting(command.settingKey, command.enabled)
        return Result.success(AgentAction.Success(command = command))
    }


}
