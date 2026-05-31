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
 * 支持命令：
 * - change_theme: 切换主题（light/dark/system）
 * - change_language: 切换语言（zh/en）
 * - download_model: 下载模型
 * - switch_face_engine: 切换人脸检测引擎
 * - toggle_setting: 开关设置项
 */
class SettingsCapability(
    private val onChangeTheme: ((ThemeMode) -> Unit)? = null,
    private val onChangeLanguage: ((AppLanguage) -> Unit)? = null,
    private val onDownloadModel: ((String) -> Unit)? = null,
    private val onSwitchFaceEngine: ((FaceDetectionEngineMode) -> Unit)? = null,
    private val onToggleSetting: ((String, Boolean) -> Unit)? = null
) : BaseCapability() {

    companion object {
        private const val TAG = "SettingsCapability"
    }

    override val name = "settings"
    override val description = "应用设置控制：主题切换、语言切换、模型管理、调试选项"

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
        "switch_face_engine" -> "切换人脸检测引擎，参数：engine (mediapipe/insightface/mnn/ncnn)"
        "toggle_setting" -> "开关设置项，参数：key, enabled (true/false)"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        Logger.i(TAG, "Executing command: $command")

        return when (command) {
            is AgentCommand.ChangeTheme -> {
                if (onChangeTheme == null) {
                    return Result.success(AgentAction.Error("主题切换功能未初始化"))
                }
                handleChangeTheme(command)
            }
            is AgentCommand.ChangeLanguage -> {
                if (onChangeLanguage == null) {
                    return Result.success(AgentAction.Error("语言切换功能未初始化"))
                }
                handleChangeLanguage(command)
            }
            is AgentCommand.DownloadModel -> {
                if (onDownloadModel == null) {
                    return Result.success(AgentAction.Error("模型下载功能未初始化"))
                }
                handleDownloadModel(command)
            }
            is AgentCommand.SwitchFaceEngine -> {
                if (onSwitchFaceEngine == null) {
                    return Result.success(AgentAction.Error("人脸引擎切换未初始化"))
                }
                handleSwitchFaceEngine(command)
            }
            is AgentCommand.ToggleSetting -> {
                if (onToggleSetting == null) {
                    return Result.success(AgentAction.Error("设置项开关未初始化"))
                }
                handleToggleSetting(command)
            }
            else -> Result.success(AgentAction.Error("不支持的设置命令: $command"))
        }
    }

    private fun handleChangeTheme(command: AgentCommand.ChangeTheme): Result<AgentAction> {
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

        onChangeTheme?.invoke(themeMode)
        return Result.success(
            AgentAction.Success(command = command)
        )
    }

    private fun handleChangeLanguage(command: AgentCommand.ChangeLanguage): Result<AgentAction> {
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

        onChangeLanguage?.invoke(language)
        return Result.success(
            AgentAction.Success(command = command)
        )
    }

    private fun handleDownloadModel(command: AgentCommand.DownloadModel): Result<AgentAction> {
        if (command.modelId.isBlank()) {
            return Result.success(AgentAction.Error("请指定模型 ID"))
        }

        onDownloadModel?.invoke(command.modelId)
        return Result.success(
            AgentAction.Success(command = command)
        )
    }

    private fun handleSwitchFaceEngine(command: AgentCommand.SwitchFaceEngine): Result<AgentAction> {
        val engine = when (command.engine.lowercase()) {
            "mediapipe" -> FaceDetectionEngineMode.MEDIAPIPE
            "insightface" -> FaceDetectionEngineMode.INSIGHTFACE
            "mnn" -> FaceDetectionEngineMode.MNN
            "ncnn" -> FaceDetectionEngineMode.NCNN
            "custom" -> FaceDetectionEngineMode.CUSTOM
            else -> {
                return Result.success(
                    AgentAction.Error("未知的人脸检测引擎: ${command.engine}，支持 mediapipe/insightface/mnn/ncnn/custom")
                )
            }
        }

        onSwitchFaceEngine?.invoke(engine)
        return Result.success(
            AgentAction.Success(command = command)
        )
    }

    private fun handleToggleSetting(command: AgentCommand.ToggleSetting): Result<AgentAction> {
        if (command.settingKey.isBlank()) {
            return Result.success(AgentAction.Error("请指定设置项 key"))
        }

        onToggleSetting?.invoke(command.settingKey, command.enabled)
        return Result.success(
            AgentAction.Success(command = command)
        )
    }


}
