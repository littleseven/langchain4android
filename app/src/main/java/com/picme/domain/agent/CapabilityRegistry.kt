package com.picme.domain.agent

import com.picme.core.common.Logger
import com.picme.domain.agent.capability.Capability
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager

/**
 * 能力注册表
 *
 * 负责：
 * - 按场景过滤 Capability
 * - 页面上下文传递
 * - 命令分发到对应 Capability
 */
class CapabilityRegistry private constructor(
    private val sceneManager: SceneManager
) {

    companion object {
        @Volatile
        private var instance: CapabilityRegistry? = null

        fun getInstance(): CapabilityRegistry {
            return instance ?: synchronized(this) {
                instance ?: CapabilityRegistry(SceneManager.getInstance()).also { instance = it }
            }
        }
    }

    private val tag = "PicMe:CapabilityRegistry"
    private val registry = mutableMapOf<String, Capability>()

    /**
     * 注册 Capability
     */
    fun register(capability: Capability) {
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
     * 获取当前场景下可用的 Capability 列表
     */
    fun getCapabilitiesForCurrentScene(): List<Capability> {
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
        val commandType = command::class.simpleName ?: "Unknown"
        val currentScene = sceneManager.currentScene.value

        return when (command) {
            is AgentCommand.TextReply -> {
                Result.success(AgentAction.TextReply(command.message))
            }
            is AgentCommand.Unknown -> {
                Logger.w(tag, "[$commandType] Unknown command at $currentScene")
                Result.success(AgentAction.TextReply("收到你的消息了，但没理解具体意图，请再描述一下~"))
            }
            is AgentCommand.Error -> {
                Logger.e(tag, "[$commandType] Command error: ${command.reason}")
                Result.success(AgentAction.Error(command.reason))
            }
            else -> {
                val capability = findCapabilityForCommand(command)
                if (capability != null) {
                    // 检查 Capability 是否在当前场景可用
                    if (!capability.activeScenes().contains(currentScene)) {
                        Logger.w(tag, "[$commandType] Capability ${capability.name} not available in scene $currentScene")
                        return Result.success(
                            AgentAction.Error("在当前页面无法执行此操作，请先导航到对应页面")
                        )
                    }
                    Logger.i(tag, "[$commandType] Dispatching to ${capability.name} in scene $currentScene")
                    val result = capability.execute(command, context, pageContext)
                    result.fold(
                        onSuccess = { action ->
                            when (action) {
                                is AgentAction.Success -> {
                                    Logger.i(tag, "[$commandType] Executed successfully by ${capability.name}")
                                }
                                is AgentAction.Error -> {
                                    Logger.w(tag, "[$commandType] Executed but returned error: ${action.message}")
                                }
                                is AgentAction.TextReply -> {
                                    Logger.d(tag, "[$commandType] Text reply: ${action.message}")
                                }
                            }
                        },
                        onFailure = { error ->
                            Logger.e(tag, "[$commandType] Execution failed in ${capability.name}", error)
                        }
                    )
                    result
                } else {
                    Logger.w(tag, "[$commandType] No capability found for command in scene $currentScene")
                    Result.success(AgentAction.Error("暂不支持此操作"))
                }
            }
        }
    }

    /**
     * 根据命令查找对应的 Capability
     */
    private fun findCapabilityForCommand(command: AgentCommand): Capability? {
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
