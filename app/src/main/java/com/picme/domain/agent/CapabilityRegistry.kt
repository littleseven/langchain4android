package com.picme.domain.agent

import com.picme.core.common.Logger
import com.picme.domain.agent.capability.Capability
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 能力注册表
 *
 * 应用级单例，负责：
 * - 按场景过滤 Capability
 * - 页面上下文传递
 * - 命令分发到对应 Capability
 * - 跨页面命令队列管理（支持后台排队执行）
 *
 * **架构原则**：
 * - Capability 在 Application.onCreate() 中注册一次，永不注销
 * - 通过 isAvailable() 检查 Capability 是否可用（delegate 是否绑定）
 * - 支持跨页面指令排队执行
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

    // 跨页面命令队列
    private val commandQueue = mutableListOf<QueuedCommand>()
    private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 排队的命令
     */
    data class QueuedCommand(
        val command: AgentCommand,
        val context: AgentContext,
        val pageContext: PageContext?,
        val targetScene: SceneManager.Scene,
        val retryCount: Int = 0
    )

    /**
     * 注册 Capability（应用级，只注册一次，永不注销）
     */
    fun register(capability: Capability) {
        if (registry.containsKey(capability.name)) {
            Logger.w(tag, "Capability ${capability.name} already registered, skipping")
            return
        }
        registry[capability.name] = capability
        Logger.i(tag, "Registered capability: ${capability.name} " +
            "(scenes: ${capability.activeScenes().joinToString { it.name }})")
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
     * 获取当前场景下活跃的 Capability 列表
     *
     * 只返回在当前场景活跃的 Capability（不检查 isAvailable）
     * 用于构建 system prompt，让 LLM 知道当前页面"应该"支持哪些命令
     */
    fun getCapabilitiesForCurrentScene(): List<Capability> {
        val currentScene = sceneManager.currentScene.value
        return registry.values.filter { capability ->
            capability.activeScenes().contains(currentScene) ||
                    capability.activeScenes().isEmpty()
        }
    }

    /**
     * 分发命令到对应的 Capability
     *
     * **跨页面指令支持**：
     * - 如果目标 Capability 在当前场景不可用（场景不匹配或 delegate 未绑定），命令会自动入队
     * - 当目标页面激活时，队列中的命令会自动执行
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
                dispatchWithQueueSupport(command, context, pageContext, currentScene, commandType)
            }
        }
    }

    /**
     * 分发命令，支持跨页面排队
     */
    private suspend fun dispatchWithQueueSupport(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?,
        currentScene: SceneManager.Scene,
        commandType: String
    ): Result<AgentAction> {
        val capability = findCapabilityForCommand(command)

        if (capability == null) {
            Logger.w(tag, "[$commandType] No capability found for command in scene $currentScene")
            return Result.success(AgentAction.Error("暂不支持此操作"))
        }

        // 检查场景是否匹配
        val sceneMatch = capability.activeScenes().contains(currentScene) ||
                capability.activeScenes().isEmpty()

        // 检查 Capability 是否可用（delegate 是否绑定）
        val isAvailable = capability.isAvailable()

        // 如果场景不匹配或 Capability 不可用，将命令入队
        if (!sceneMatch || !isAvailable) {
            val reason = when {
                !sceneMatch -> "scene mismatch (current=$currentScene, required=${capability.activeScenes()})"
                else -> "delegate not bound"
            }
            Logger.i(tag, "[$commandType] Capability ${capability.name} unavailable ($reason), queuing command")
            enqueueCommand(command, context, pageContext, capability)
            return Result.success(
                AgentAction.TextReply("正在为您切换到对应页面执行操作...")
            )
        }

        // 直接执行命令
        Logger.i(tag, "[$commandType] Dispatching to ${capability.name} in scene $currentScene")
        return executeCommand(command, context, pageContext, capability)
    }

    /**
     * 执行命令并记录结果
     */
    private suspend fun executeCommand(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?,
        capability: Capability
    ): Result<AgentAction> {
        val commandType = command::class.simpleName ?: "Unknown"
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
        return result
    }

    /**
     * 将命令加入跨页面队列
     *
     * 当目标场景激活时，队列中的命令会自动执行
     */
    private fun enqueueCommand(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?,
        capability: Capability
    ) {
        val targetScene = capability.activeScenes().firstOrNull() ?: SceneManager.Scene.UNKNOWN
        val queuedCommand = QueuedCommand(command, context, pageContext, targetScene)
        commandQueue.add(queuedCommand)
        Logger.i(tag, "Command queued for scene $targetScene, queue size: ${commandQueue.size}")

        // 启动队列处理器（如果还没有在运行）
        startQueueProcessor()
    }

    /**
     * 启动队列处理器
     *
     * 监听场景变化，当目标场景激活时执行队列中的命令
     */
    private fun startQueueProcessor() {
        queueScope.launch {
            Logger.i(tag, "Queue processor started, queue size: ${commandQueue.size}")
            while (commandQueue.isNotEmpty()) {
                val currentScene = sceneManager.currentScene.value
                val iterator = commandQueue.iterator()
                var executedCount = 0

                while (iterator.hasNext()) {
                    val queued = iterator.next()
                    val capability = findCapabilityForCommand(queued.command)
                    val sceneMatch = capability?.activeScenes()?.contains(currentScene) ?: false
                    val available = capability?.isAvailable() ?: false

                    Logger.d(tag, "Checking queued command: ${AgentCommand.getActionName(queued.command)}, " +
                        "capability=${capability?.name}, sceneMatch=$sceneMatch, available=$available")

                    if (capability != null && sceneMatch && available) {
                        iterator.remove()
                        executedCount++
                        Logger.i(tag, "Executing queued command for scene $currentScene")
                        // 在单独的协程中执行命令，避免挂起影响队列处理器
                        launch {
                            executeCommand(queued.command, queued.context, queued.pageContext, capability)
                        }
                    }
                }

                if (executedCount > 0) {
                    Logger.i(tag, "Queue processor executed $executedCount commands, remaining: ${commandQueue.size}")
                }

                delay(500) // 每 500ms 检查一次
            }
            Logger.i(tag, "Queue processor stopped, queue empty")
        }
    }

    /**
     * 清空命令队列
     */
    fun clearCommandQueue() {
        commandQueue.clear()
        Logger.i(tag, "Command queue cleared")
    }

    /**
     * 根据命令查找对应的 Capability
     *
     * 优先在当前场景的可用 Capability 中查找，
     * 如果找不到，在所有已注册的 Capability 中查找（用于跨页面指令）
     */
    private fun findCapabilityForCommand(command: AgentCommand): Capability? {
        val commandName = AgentCommand.getActionName(command)

        // 首先在当前场景的可用 Capability 中查找
        val currentSceneCapabilities = getCapabilitiesForCurrentScene()
        val availableMatch = currentSceneCapabilities.find { capability ->
            capability.supportedCommands().contains(commandName)
        }
        if (availableMatch != null) return availableMatch

        // 如果当前场景找不到，在所有已注册的 Capability 中查找
        // 这支持跨页面指令：即使目标 Capability 当前不可用，也能找到它并排队
        return registry.values.find { it.supportedCommands().contains(commandName) }
    }

    /**
     * 构建 Capability 描述文本（用于 system prompt）
     *
     * 只包含当前场景可用且 isAvailable 的 Capability
     */
    fun buildCapabilityDescription(): String {
        val capabilities = getCapabilitiesForCurrentScene()
        return capabilities.joinToString("\n") { capability ->
            capability.buildCapabilityDescription()
        }
    }

    /**
     * 获取所有已注册 Capability 的描述（用于调试）
     */
    fun buildAllCapabilitiesDescription(): String {
        return registry.values.joinToString("\n") { capability ->
            val available = if (capability.isAvailable()) "✓" else "✗"
            "$available ${capability.buildCapabilityDescription()}"
        }
    }

    /**
     * 获取指定命令在当前场景是否可用
     *
     * 同时检查场景匹配和 Capability 可用性（delegate 是否绑定）
     */
    fun isCommandAvailable(command: AgentCommand): Boolean {
        val capability = findCapabilityForCommand(command) ?: return false
        val currentScene = sceneManager.currentScene.value
        return capability.activeScenes().contains(currentScene) && capability.isAvailable()
    }
}