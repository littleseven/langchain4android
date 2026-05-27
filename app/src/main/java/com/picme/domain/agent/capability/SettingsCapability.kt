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
 * - change_language: 切换语言（zh/en/ja/ko）
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
) : CapabilityV2 {

    private val TAG = "SettingsCapability"

    override val name = "settings"
    override val description = "应用设置控制：主题切换、语言切换、模型管理、调试选项"

    override fun activeScenes(): List<SceneManager.Scene> =
        listOf(SceneManager.Scene.SETTINGS)

    override fun supportedCommands(): List<String> = listOf(
        "change_theme",
        "change_language",
        "download_model",
        "switch_face_engine",
        "toggle_setting",
        "text_reply"
    )

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        Logger.i(TAG, "Executing command: $command")

        return when (command) {
            is AgentCommand.ChangeTheme -> handleChangeTheme(command)
            is AgentCommand.ChangeLanguage -> handleChangeLanguage(command)
            is AgentCommand.DownloadModel -> handleDownloadModel(command)
            is AgentCommand.SwitchFaceEngine -> handleSwitchFaceEngine(command)
            is AgentCommand.ToggleSetting -> handleToggleSetting(command)
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
            AgentAction.Success(
                command,
                message = "已切换到${getThemeName(themeMode)}模式"
            )
        )
    }

    private fun handleChangeLanguage(command: AgentCommand.ChangeLanguage): Result<AgentAction> {
        val language = when (command.language.lowercase()) {
            "zh", "中文", "简体中文", "cn" -> AppLanguage.CHINESE
            "en", "英文", "英语", "english" -> AppLanguage.ENGLISH
            "ja", "日文", "日语", "japanese" -> AppLanguage.JAPANESE
            "ko", "韩文", "韩语", "korean" -> AppLanguage.KOREAN
            else -> {
                return Result.success(
                    AgentAction.Error("不支持的语言: ${command.language}，支持 zh/en/ja/ko")
                )
            }
        }

        onChangeLanguage?.invoke(language)
        return Result.success(
            AgentAction.Success(
                command,
                message = "已切换到${getLanguageName(language)}"
            )
        )
    }

    private fun handleDownloadModel(command: AgentCommand.DownloadModel): Result<AgentAction> {
        if (command.modelId.isBlank()) {
            return Result.success(AgentAction.Error("请指定模型 ID"))
        }

        onDownloadModel?.invoke(command.modelId)
        return Result.success(
            AgentAction.Success(
                command,
                message = "开始下载模型: ${command.modelId}"
            )
        )
    }

    private fun handleSwitchFaceEngine(command: AgentCommand.SwitchFaceEngine): Result<AgentAction> {
        val engine = when (command.engine.lowercase()) {
            "mlkit", "ml_kit" -> FaceDetectionEngineMode.ML_KIT
            "effect", "effect_sdk" -> FaceDetectionEngineMode.EFFECT_SDK
            else -> {
                return Result.success(
                    AgentAction.Error("未知的人脸检测引擎: ${command.engine}，支持 mlkit/effect")
                )
            }
        }

        onSwitchFaceEngine?.invoke(engine)
        return Result.success(
            AgentAction.Success(
                command,
                message = "已切换到${getEngineName(engine)}"
            )
        )
    }

    private fun handleToggleSetting(command: AgentCommand.ToggleSetting): Result<AgentAction> {
        if (command.key.isBlank()) {
            return Result.success(AgentAction.Error("请指定设置项 key"))
        }

        onToggleSetting?.invoke(command.key, command.enabled)
        return Result.success(
            AgentAction.Success(
                command,
                message = "${command.key} 已${if (command.enabled) "开启" else "关闭"}"
            )
        )
    }

    private fun getThemeName(theme: ThemeMode): String = when (theme) {
        ThemeMode.LIGHT -> "浅色"
        ThemeMode.DARK -> "深色"
        ThemeMode.SYSTEM -> "跟随系统"
    }

    private fun getLanguageName(language: AppLanguage): String = when (language) {
        AppLanguage.CHINESE -> "中文"
        AppLanguage.ENGLISH -> "英文"
        AppLanguage.JAPANESE -> "日文"
        AppLanguage.KOREAN -> "韩文"
    }

    private fun getEngineName(engine: FaceDetectionEngineMode): String = when (engine) {
        FaceDetectionEngineMode.ML_KIT -> "ML Kit"
        FaceDetectionEngineMode.EFFECT_SDK -> "特效 SDK"
    }
}
