package com.picme.domain.agent.capability

import com.picme.core.common.Logger
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager

/**
 * 导航 Capability
 *
 * 负责页面导航：切换页面、返回上一页
 * 在所有场景都可用
 */
class NavigationCapability(
    private val onNavigate: (Destination) -> Unit,
    private val onBack: () -> Unit
) : BaseCapability() {

    private val tag = "PicMe:NavigationCapability"

    override val name: String = "navigation"
    override val description: String = "页面导航：切换页面、返回上一页"

    override fun activeScenes(): List<SceneManager.Scene> {
        // 导航能力在所有场景都可用
        return SceneManager.Scene.entries.toList()
    }

    override fun supportedCommands(): List<String> = listOf(
        "navigate_to",
        "go_back",
        "text_reply"
    )

    override fun getCommandDescription(command: String): String = when (command) {
        "navigate_to" -> "导航到指定页面，参数: destination (camera|gallery|settings|editor)"
        "go_back" -> "返回上一页，无参数"
        "text_reply" -> "文本回复"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        Logger.d(tag, "Executing command: ${command::class.simpleName}")

        return when (command) {
            is AgentCommand.NavigateTo -> {
                val destination = parseDestination(command.destination)
                if (destination != null) {
                    onNavigate(destination)
                    Result.success(AgentAction.Success(command))
                } else {
                    Result.success(
                        AgentAction.Error("未知页面: ${command.destination}，可用页面: camera, gallery, settings, editor")
                    )
                }
            }

            is AgentCommand.GoBack -> {
                onBack()
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.TextReply -> {
                Result.success(AgentAction.TextReply(command.message))
            }

            else -> {
                Logger.w(tag, "Unsupported command: ${command::class.simpleName}")
                Result.success(AgentAction.Error("导航 Capability 不支持此命令"))
            }
        }
    }

    private fun parseDestination(destination: String): Destination? {
        return when (destination.lowercase()) {
            "camera", "相机", "拍照", "拍摄" -> Destination.CAMERA
            "gallery", "相册", "照片", "图库" -> Destination.GALLERY
            "settings", "设置", "配置" -> Destination.SETTINGS
            "editor", "编辑", "编辑器" -> Destination.EDITOR
            "debug", "调试" -> Destination.DEBUG
            else -> null
        }
    }

    /**
     * 导航目标
     */
    enum class Destination {
        CAMERA,     // 相机页
        GALLERY,    // 相册页
        SETTINGS,   // 设置页
        EDITOR,     // 编辑页
        DEBUG       // 调试页
    }
}
