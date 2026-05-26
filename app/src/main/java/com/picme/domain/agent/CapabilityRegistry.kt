package com.picme.domain.agent

import com.picme.core.common.Logger
import com.picme.domain.agent.capability.Capability
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext

/**
 * 能力注册表
 *
 * 统一管理所有 Capability 的注册和命令分发。
 * 支持动态扩展，后续可通过插件机制注册新的 Capability。
 */
class CapabilityRegistry {

    private val tag = "PicMe:CapabilityRegistry"
    private val registry = mutableMapOf<String, Capability>()

    /**
     * 注册 Capability
     */
    fun register(capability: Capability) {
        registry[capability.name] = capability
        Logger.i(tag, "Registered capability: ${capability.name}")
    }

    /**
     * 注销 Capability
     */
    fun unregister(name: String) {
        registry.remove(name)
        Logger.d(tag, "Unregistered capability: $name")
    }

    /**
     * 获取已注册的 Capability
     */
    fun get(name: String): Capability? {
        return registry[name]
    }

    /**
     * 获取所有已注册的 Capability
     */
    fun getAll(): List<Capability> {
        return registry.values.toList()
    }

    /**
     * 分发命令到对应的 Capability
     *
     * 根据命令类型自动路由到合适的 Capability。
     * 如果命令是 TextReply/Unknown/Error，直接返回对应 Action 不路由。
     */
    suspend fun dispatch(
        command: AgentCommand,
        context: AgentContext
    ): Result<AgentAction> {
        return when (command) {
            is AgentCommand.TextReply -> {
                Result.success(AgentAction.TextReply(command.message))
            }
            is AgentCommand.Unknown -> {
                Result.success(AgentAction.TextReply("收到你的消息了，但没理解具体意图，请再描述一下~"))
            }
            is AgentCommand.Error -> {
                Result.success(AgentAction.Error(command.reason))
            }
            else -> {
                val capability = findCapabilityForCommand(command)
                if (capability != null) {
                    capability.execute(command, context)
                } else {
                    Logger.w(tag, "No capability found for command: ${command.javaClass.simpleName}, returning Success for UI handling")
                    Result.success(AgentAction.Success(command))
                }
            }
        }
    }

    /**
     * 根据命令查找对应的 Capability
     */
    private fun findCapabilityForCommand(command: AgentCommand): Capability? {
        val commandType = when (command) {
            is AgentCommand.AdjustBeauty,
            is AgentCommand.SwitchFilter,
            is AgentCommand.SwitchStyle,
            is AgentCommand.SwitchScene,
            is AgentCommand.SwitchRatio,
            is AgentCommand.AdjustExposure,
            is AgentCommand.AdjustZoom,
            is AgentCommand.FlipCamera,
            is AgentCommand.CapturePhoto,
            is AgentCommand.ToggleRecording,
            is AgentCommand.SwitchMode -> "camera"
            else -> null
        }
        return commandType?.let { registry[it] }
    }

    /**
     * 构建 Capability 描述文本（用于 system prompt）
     */
    fun buildCapabilityDescription(): String {
        return registry.values.joinToString("\n") { capability ->
            "- ${capability.name}: ${capability.description}"
        }
    }
}
