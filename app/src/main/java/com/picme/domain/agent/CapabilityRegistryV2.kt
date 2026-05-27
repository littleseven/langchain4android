package com.picme.domain.agent

import com.picme.core.common.Logger
import com.picme.domain.agent.capability.CapabilityV2
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager

/**
 * 能力注册表 V2
 *
 * 增强版本，支持：
 * - 按场景过滤 Capability
 * - 支持 CapabilityV2 接口
 * - 页面上下文传递
 */
class CapabilityRegistryV2(
    private val sceneManager: SceneManager
) {

    private val tag = "PicMe:CapabilityRegistryV2"
    private val registry = mutableMapOf<String, CapabilityV2>()

    /**
     * 注册 Capability
     */
    fun register(capability: CapabilityV2) {
        registry[capability.name] = capability
        Logger.i(tag, "Registered capability: ${capability.name} " +
            "(scenes: ${capability.activeScenes().joinToString { it.name }})")
    }

    /**
     * 注销 Capability
     */
    fun unregister(name: String) {
        registry.remove(name)
        Logger.d(tag, "Unregistered capability: $name")
    }

    /**
     * 获取指定名称的 Capability
     */
    fun get(name: String): CapabilityV2? {
        return registry[name]
    }

    /**
     * 获取所有已注册的 Capability
     */
    fun getAll(): List<CapabilityV2> {
        return registry.values.toList()
    }

    /**
     * 获取当前场景下可用的 Capability 列表
     */
    fun getCapabilitiesForCurrentScene(): List<CapabilityV2> {
        val currentScene = sceneManager.currentScene.value
        return registry.values.filter { capability ->
            capability.activeScenes().contains(currentScene) ||
            capability.activeScenes().isEmpty() // 空列表表示在所有场景都可用
        }
    }

    /**
     * 分发命令到对应的 Capability
     *
     * @param command 解析后的命令
     * @param context Agent 上下文
     * @param pageContext 页面特定上下文（可选）
     * @return 执行结果
     */
    suspend fun dispatch(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext? = null
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
                    // 检查 Capability 是否在当前场景可用
                    val currentScene = sceneManager.currentScene.value
                    if (!capability.activeScenes().contains(currentScene)) {
                        Logger.w(tag, "Capability ${capability.name} not available in scene $currentScene")
                        return Result.success(
                            AgentAction.Error("在当前页面无法执行此操作，请先导航到对应页面")
                        )
                    }
                    capability.execute(command, context, pageContext)
                } else {
                    Logger.w(tag, "No capability found for command: ${command::class.simpleName}")
                    Result.success(AgentAction.Error("暂不支持此操作"))
                }
            }
        }
    }

    /**
     * 根据命令查找对应的 Capability
     */
    private fun findCapabilityForCommand(command: AgentCommand): CapabilityV2? {
        val commandName = AgentCommand.getActionName(command)

        // 首先在当前场景的 Capability 中查找
        val currentSceneCapabilities = getCapabilitiesForCurrentScene()

        return currentSceneCapabilities.find { capability ->
            capability.supportedCommands().contains(commandName)
        } ?: run {
            // 如果当前场景找不到，在所有 Capability 中查找（用于错误提示）
            registry.values.find { it.supportedCommands().contains(commandName) }
        }
    }

    /**
     * 构建 Capability 描述文本（用于 system prompt）
     */
    fun buildCapabilityDescription(): String {
        val capabilities = getCapabilitiesForCurrentScene()
        return capabilities.joinToString("\n") { capability ->
            capability.buildCapabilityDescription()
        }
    }

    /**
     * 获取指定命令在当前场景是否可用
     */
    fun isCommandAvailable(command: AgentCommand): Boolean {
        val capability = findCapabilityForCommand(command) ?: return false
        val currentScene = sceneManager.currentScene.value
        return capability.activeScenes().contains(currentScene)
    }
}
