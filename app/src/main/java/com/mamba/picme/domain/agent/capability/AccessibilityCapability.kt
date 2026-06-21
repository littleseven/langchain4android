package com.mamba.picme.domain.agent.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.service.accessibility.AccessibilityAction
import com.mamba.picme.service.accessibility.AccessibilityController

/**
 * 无障碍自动化 Capability
 *
 * 职责：
 * - 接收 `perform_accessibility_action` 命令
 * - 将命令转换为 [AccessibilityAction] 并投递到 [AccessibilityController]
 * - 返回执行结果（成功入队或服务未开启错误）
 *
 * 可用性取决于用户是否已在系统设置中开启 PicMe 无障碍服务。
 */
class AccessibilityCapability : BaseCapability() {

    override val name: String = "accessibility"
    override val description: String = "无障碍自动化：在其他应用中执行点击、输入、滚动、返回等操作"

    override fun supportedCommands(): List<String> = listOf("perform_accessibility_action")

    override fun getCommandDescription(command: String): String = when (command) {
        "perform_accessibility_action" ->
            "执行无障碍动作，参数: action (click|long_click|input|scroll_forward|scroll_backward|back|home|recent), target, params"
        else -> "未知命令"
    }

    /**
     * 全局 Capability 不需要页面级 delegate，因此始终声明为可用。
     * 实际执行时会在 [execute] 中检查服务是否连接，并返回结构化错误。
     */
    override fun isAvailable(): Boolean = true

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val actionCommand = command as? AgentCommand.PerformAccessibilityAction
            ?: return Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                    message = "AccessibilityCapability 不支持此命令"
                )
            )

        if (!AccessibilityController.isServiceConnected()) {
            return Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.CAPABILITY_UNAVAILABLE,
                    message = "无障碍服务未开启，请前往设置开启"
                )
            )
        }

        val action = AccessibilityAction(
            commandId = actionCommand.commandId,
            action = actionCommand.action,
            target = actionCommand.target,
            params = actionCommand.params
        )

        return AccessibilityController.enqueue(action).fold(
            onSuccess = {
                Result.success(
                    AgentAction.Success(
                        commandId = command.commandId,
                        command = command
                    )
                )
            },
            onFailure = { error ->
                Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.CAPABILITY_UNAVAILABLE,
                        message = error.message ?: "无障碍服务不可用"
                    )
                )
            }
        )
    }
}
