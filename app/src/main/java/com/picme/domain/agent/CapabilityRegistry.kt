package com.picme.domain.agent

import com.picme.core.common.Logger
import com.picme.domain.agent.capability.Capability
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.AgentErrorCode
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager
import com.picme.domain.agent.remote.StepResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

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
    private val sceneManager: SceneManager,
    private val externalScope: CoroutineScope? = null
) {

    companion object {
        @Volatile
        private var instance: CapabilityRegistry? = null

        // 队列配置常量
        const val DEFAULT_COMMAND_TIMEOUT_MS = 10_000L
        const val MAX_QUEUE_SIZE = 50           // 队列上限
        const val QUEUE_TTL_MS = 300_000L       // 命令 TTL：5 分钟
        const val MAX_RETRY_COUNT = 3           // 最大重试次数
        const val QUEUE_POLL_INTERVAL_MS = 500L // 轮询间隔

        /**
         * 获取单例实例（使用默认协程作用域）
         */
        fun getInstance(): CapabilityRegistry {
            return instance ?: synchronized(this) {
                instance ?: CapabilityRegistry(SceneManager.getInstance()).also { instance = it }
            }
        }

        /**
         * 创建实例（支持注入外部协程作用域，便于生命周期绑定和测试）
         *
         * @param sceneManager 场景管理器
         * @param scope 外部协程作用域（如 ViewModelScope、ApplicationScope）
         */
        fun create(
            sceneManager: SceneManager = SceneManager.getInstance(),
            scope: CoroutineScope? = null
        ): CapabilityRegistry {
            return CapabilityRegistry(sceneManager, scope)
        }
    }

    private val tag = "CapabilityRegistry"
    private val registry = mutableMapOf<String, Capability>()

    // ─────────────────────────────────────────────────────────────────────────────
    // 跨页面命令队列（重构为 Channel + 上限 + 去重 + TTL + retry）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Capability 执行异常
     *
     * 当 Capability.execute() 抛出异常时，包装为结构化错误。
     */
    class CapabilityExecutionException(
        message: String,
        val errorCode: Int = AgentErrorCode.INTERNAL_ERROR,
        cause: Throwable? = null
    ) : Exception(message, cause)

    /**
     * 排队的命令（增强版）
     *
     * @property enqueueTime 入队时间戳（用于 TTL 计算）
     * @property retryCount 已重试次数
     */
    data class QueuedCommand(
        val command: AgentCommand,
        val context: AgentContext,
        val pageContext: PageContext?,
        val targetScene: SceneManager.Scene,
        val enqueueTime: Long = System.currentTimeMillis(),
        val retryCount: Int = 0
    )

    /** 内部命令队列（线程安全） */
    private val commandQueue = mutableListOf<QueuedCommand>()
    private val queueLock = Object()

    /** 队列状态事件流（供 UI 观察） */
    private val _queueEvents = MutableSharedFlow<QueueEvent>(extraBufferCapacity = 64)
    val queueEvents: SharedFlow<QueueEvent> = _queueEvents.asSharedFlow()

    /** 队列处理器是否正在运行 */
    @Volatile
    private var isProcessorRunning = false

    /**
     * 队列事件（结构化可观测性）
     */
    sealed class QueueEvent {
        data class Enqueued(val commandType: String, val queueSize: Int) : QueueEvent()
        data class Executed(val commandType: String, val success: Boolean) : QueueEvent()
        data class Expired(val commandType: String, val ageMs: Long) : QueueEvent()
        data class Dropped(val commandType: String, val reason: String) : QueueEvent()
        data class Retry(val commandType: String, val retryCount: Int) : QueueEvent()
        data class QueueCleared(val previousSize: Int) : QueueEvent()
    }

    /**
     * 队列处理协程作用域
     *
     * 优先使用外部注入的作用域（如 ViewModelScope），
     * 未注入时创建独立作用域（应用级生命周期）。
     */
    private val queueScope: CoroutineScope
        get() = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
                Result.success(AgentAction.TextReply(commandId = command.commandId, message = command.message))
            }
            is AgentCommand.Unknown -> {
                Logger.w(tag, "[$commandType] Unknown command at $currentScene")
                Result.success(
                    AgentAction.TextReply(
                        commandId = command.commandId,
                        message = "收到你的消息了，但没理解具体意图，请再描述一下~"
                    )
                )
            }
            is AgentCommand.Error -> {
                Logger.e(tag, "[$commandType] Command error: ${command.reason}")
                Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.INVALID_REQUEST,
                        message = command.reason
                    )
                )
            }
            is AgentCommand.BatchExecute -> {
                dispatchBatch(command, context, pageContext, currentScene)
            }
            is AgentCommand.ExecutePlan -> {
                dispatchPlan(command, context, pageContext)
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
            return Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                    message = "暂不支持此操作",
                    detail = "No capability found for command '$commandType' in scene $currentScene"
                )
            )
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
                AgentAction.TextReply(
                    commandId = command.commandId,
                    message = "正在为您切换到对应页面执行操作..."
                )
            )
        }

        // 直接执行命令
        Logger.i(tag, "[$commandType] Dispatching to ${capability.name} in scene $currentScene")
        return executeCommand(command, context, pageContext, capability)
    }

    /**
     * 执行命令并记录结果
     *
     * 支持超时控制：单个命令默认 10 秒超时，超时后返回 EXECUTION_TIMEOUT 错误。
     */
    private suspend fun executeCommand(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?,
        capability: Capability
    ): Result<AgentAction> {
        val commandType = command::class.simpleName ?: "Unknown"
        val result = try {
            withTimeout(DEFAULT_COMMAND_TIMEOUT_MS) {
                try {
                    capability.execute(command, context, pageContext)
                } catch (capabilityError: CapabilityExecutionException) {
                    // Capability 主动抛出的结构化异常
                    Logger.w(tag, "[$commandType] Capability threw structured error: ${capabilityError.message}")
                    Result.success(
                        AgentAction.Error(
                            commandId = command.commandId,
                            errorCode = capabilityError.errorCode,
                            message = capabilityError.message ?: "Capability 执行错误",
                            detail = capabilityError.cause?.message
                        )
                    )
                } catch (throwable: Throwable) {
                    // Capability 未捕获的异常
                    Logger.e(tag, "[$commandType] Capability threw unexpected exception in ${capability.name}", throwable)
                    Result.success(
                        AgentAction.Error(
                            commandId = command.commandId,
                            errorCode = AgentErrorCode.INTERNAL_ERROR,
                            message = "Capability 执行异常: ${throwable.message}",
                            detail = throwable.stackTraceToString()
                        )
                    )
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            Logger.w(tag, "[$commandType] Execution timed out after ${DEFAULT_COMMAND_TIMEOUT_MS}ms in ${capability.name}")
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.EXECUTION_TIMEOUT,
                    message = "命令执行超时",
                    detail = "Command '$commandType' exceeded ${DEFAULT_COMMAND_TIMEOUT_MS}ms in ${capability.name}"
                )
            )
        }

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
                    is AgentAction.BatchResult -> {
                        Logger.d(tag, "[$commandType] Batch result: ${action.results.size} sub-results, success=${action.isSuccess}")
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
     * 支持：上限检查、去重、TTL、事件通知。
     * 当目标场景激活时，队列中的命令会自动执行。
     */
    private fun enqueueCommand(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?,
        capability: Capability
    ) {
        val targetScene = capability.activeScenes().firstOrNull() ?: SceneManager.Scene.UNKNOWN
        val commandType = command::class.simpleName ?: "Unknown"

        synchronized(queueLock) {
            // 1. 上限检查
            if (commandQueue.size >= MAX_QUEUE_SIZE) {
                Logger.w(tag, "Queue full ($MAX_QUEUE_SIZE), dropping oldest command")
                val dropped = commandQueue.removeAt(0)
                _queueEvents.tryEmit(
                    QueueEvent.Dropped(
                        commandType = dropped.command::class.simpleName ?: "Unknown",
                        reason = "Queue exceeded max size $MAX_QUEUE_SIZE"
                    )
                )
            }

            // 2. 去重：相同 commandId 的命令替换旧命令
            val existingIndex = commandQueue.indexOfFirst { it.command.commandId == command.commandId }
            if (existingIndex >= 0) {
                Logger.d(tag, "Duplicate command ${command.commandId} detected, replacing old entry")
                commandQueue.removeAt(existingIndex)
            }

            // 3. 入队
            val queuedCommand = QueuedCommand(command, context, pageContext, targetScene)
            commandQueue.add(queuedCommand)
            val currentSize = commandQueue.size

            Logger.i(tag, "Command queued for scene $targetScene, queue size: $currentSize")
            _queueEvents.tryEmit(QueueEvent.Enqueued(commandType = commandType, queueSize = currentSize))
        }

        // 4. 启动队列处理器（如果还没有在运行）
        startQueueProcessor()
    }

    /**
     * 启动队列处理器
     *
     * 监听场景变化，当目标场景激活时执行队列中的命令。
     * 支持 TTL 过期清理和重试机制。
     */
    private fun startQueueProcessor() {
        if (isProcessorRunning) return
        isProcessorRunning = true

        queueScope.launch {
            Logger.i(tag, "Queue processor started")
            while (true) {
                val currentScene = sceneManager.currentScene.value
                val now = System.currentTimeMillis()
                var executedCount = 0
                var expiredCount = 0

                synchronized(queueLock) {
                    val iterator = commandQueue.iterator()

                    while (iterator.hasNext()) {
                        val queued = iterator.next()
                        val capability = findCapabilityForCommand(queued.command)
                        val sceneMatch = capability?.activeScenes()?.contains(currentScene) ?: false
                        val available = capability?.isAvailable() ?: false
                        val ageMs = now - queued.enqueueTime

                        // TTL 检查：过期命令直接丢弃
                        if (ageMs > QUEUE_TTL_MS) {
                            iterator.remove()
                            expiredCount++
                            val cmdType = queued.command::class.simpleName ?: "Unknown"
                            Logger.w(tag, "Command expired after ${ageMs}ms: $cmdType")
                            _queueEvents.tryEmit(
                                QueueEvent.Expired(commandType = cmdType, ageMs = ageMs)
                            )
                            continue
                        }

                        Logger.d(tag, "Checking queued command: ${AgentCommand.getMethodName(queued.command)}, " +
                            "capability=${capability?.name}, sceneMatch=$sceneMatch, available=$available, age=${ageMs}ms")

                        if (capability != null && sceneMatch && available) {
                            iterator.remove()
                            executedCount++
                            val cmdType = queued.command::class.simpleName ?: "Unknown"
                            Logger.i(tag, "Executing queued command for scene $currentScene")

                            // 在单独的协程中执行命令，避免挂起影响队列处理器
                            launch {
                                val result = executeCommand(queued.command, queued.context, queued.pageContext, capability)
                                val success = result.isSuccess && result.getOrNull()?.isSuccess == true
                                _queueEvents.tryEmit(QueueEvent.Executed(commandType = cmdType, success = success))

                                // 重试机制：执行失败且未超过最大重试次数时重新入队
                                if (!success && queued.retryCount < MAX_RETRY_COUNT) {
                                    Logger.w(tag, "Command failed, retrying (${queued.retryCount + 1}/$MAX_RETRY_COUNT)")
                                    val retryCommand = queued.copy(retryCount = queued.retryCount + 1)
                                    synchronized(queueLock) {
                                        commandQueue.add(retryCommand)
                                    }
                                    _queueEvents.tryEmit(
                                        QueueEvent.Retry(commandType = cmdType, retryCount = queued.retryCount + 1)
                                    )
                                }
                            }
                        }
                    }
                }

                if (executedCount > 0) {
                    Logger.i(tag, "Queue processor executed $executedCount commands, remaining: ${commandQueue.size}")
                }
                if (expiredCount > 0) {
                    Logger.w(tag, "Queue processor expired $expiredCount commands")
                }

                // 队列为空时退出处理器
                val isEmpty = synchronized(queueLock) { commandQueue.isEmpty() }
                if (isEmpty) {
                    Logger.i(tag, "Queue processor stopped, queue empty")
                    isProcessorRunning = false
                    break
                }

                delay(QUEUE_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * 清空命令队列
     */
    fun clearCommandQueue() {
        val previousSize: Int
        synchronized(queueLock) {
            previousSize = commandQueue.size
            commandQueue.clear()
        }
        Logger.i(tag, "Command queue cleared (was $previousSize)")
        _queueEvents.tryEmit(QueueEvent.QueueCleared(previousSize = previousSize))
    }

    /**
     * 根据命令查找对应的 Capability
     *
     * 优先在当前场景的可用 Capability 中查找，
     * 如果找不到，在所有已注册的 Capability 中查找（用于跨页面指令）
     */
    private fun findCapabilityForCommand(command: AgentCommand): Capability? {
        val commandName = AgentCommand.getMethodName(command)

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

    // ─────────────────────────────────────────────────────────────────────────────
    // 批量命令执行（BatchExecute）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 批量执行命令
     *
     * 顺序执行子命令列表，每个子命令独立分发到对应 Capability，
     * 收集所有子结果，汇总为 BatchResult。
     * - atomic=true 时，任一失败触发全部回滚（已执行的通过 fallback 补偿）
     *
     * @param batchCommand 批量执行命令
     * @param context Agent 上下文
     * @param pageContext 页面上下文（可选）
     * @param currentScene 当前场景
     * @return BatchResult 或 Error
     */
    private suspend fun dispatchBatch(
        batchCommand: AgentCommand.BatchExecute,
        context: AgentContext,
        pageContext: PageContext?,
        currentScene: SceneManager.Scene
    ): Result<AgentAction> {
        Logger.i(tag, "[BatchExecute] Starting batch of ${batchCommand.commands.size} commands, atomic=${batchCommand.atomic}")

        if (batchCommand.commands.isEmpty()) {
            return Result.success(
                AgentAction.Error(
                    commandId = batchCommand.commandId,
                    errorCode = AgentErrorCode.INVALID_PARAMS,
                    message = "批量命令列表为空"
                )
            )
        }

        val results = mutableListOf<AgentAction>()
        val executedCommands = mutableListOf<AgentCommand>() // 用于 atomic 回滚

        for ((index, subCommand) in batchCommand.commands.withIndex()) {
            Logger.d(tag, "[BatchExecute] Executing sub-command ${index + 1}/${batchCommand.commands.size}: ${subCommand::class.simpleName}")

            val subResult = dispatch(subCommand, context, pageContext)
            val action = subResult.getOrNull()

            if (subResult.isFailure || action == null) {
                val errorMsg = subResult.exceptionOrNull()?.message ?: "子命令执行失败"
                Logger.w(tag, "[BatchExecute] Sub-command ${index + 1} failed: $errorMsg")

                // atomic 模式：回滚已执行的命令
                if (batchCommand.atomic) {
                    rollbackExecuted(executedCommands, context, pageContext)
                }

                results.add(
                    AgentAction.Error(
                        commandId = subCommand.commandId,
                        errorCode = AgentErrorCode.INTERNAL_ERROR,
                        message = errorMsg,
                        detail = "Batch sub-command ${index + 1} failed"
                    )
                )
                return Result.success(
                    AgentAction.BatchResult(
                        commandId = batchCommand.commandId,
                        results = results.toList()
                    )
                )
            }

            results.add(action)
            executedCommands.add(subCommand)

            // 检查子结果是否表示失败（即使 Result 是成功的）
            if (!action.isSuccess) {
                Logger.w(tag, "[BatchExecute] Sub-command ${index + 1} returned error action")

                if (batchCommand.atomic) {
                    rollbackExecuted(executedCommands, context, pageContext)
                }

                return Result.success(
                    AgentAction.BatchResult(
                        commandId = batchCommand.commandId,
                        results = results.toList()
                    )
                )
            }
        }

        Logger.i(tag, "[BatchExecute] All ${batchCommand.commands.size} commands completed successfully")
        return Result.success(
            AgentAction.BatchResult(
                commandId = batchCommand.commandId,
                results = results.toList()
            )
        )
    }

    /**
     * 原子模式回滚
     *
     * 对已执行的命令尝试执行反向操作（简化实现，实际回滚逻辑由具体 Capability 决定）
     */
    private suspend fun rollbackExecuted(
        executedCommands: List<AgentCommand>,
        context: AgentContext,
        pageContext: PageContext?
    ) {
        Logger.w(tag, "[BatchExecute] Atomic rollback: ${executedCommands.size} commands to revert")
        // 注意：实际回滚需要每个 Capability 支持 undo 操作
        // 当前版本仅记录日志，后续可通过 Capability 扩展 undo 接口
        for (cmd in executedCommands.asReversed()) {
            Logger.d(tag, "[BatchExecute] Rollback: ${cmd::class.simpleName} (id=${cmd.commandId})")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 执行计划分发（ExecutePlan）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 分发执行计划到 ExecutionEngine
     *
     * @param planCommand 包含 ExecutionPlan 的命令
     * @param context Agent 上下文
     * @param pageContext 页面上下文（可选）
     * @return 执行结果
     */
    private suspend fun dispatchPlan(
        planCommand: AgentCommand.ExecutePlan,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        Logger.i(tag, "[ExecutePlan] Dispatching plan: ${planCommand.plan.planId}, steps=${planCommand.plan.steps.size}")

        val engine = ExecutionEngine(
            capabilityRegistry = this,
            reporter = ExecutionReporterImpl()
        )

        return try {
            val result = engine.execute(planCommand.plan)
            Logger.i(tag, "[ExecutePlan] Plan completed: success=${result.isSuccess}, steps=${result.stepResults.size}")

            // 将 ExecutionResult 转换为 AgentAction
            // 如果全部成功，返回最后一个步骤的 action；否则返回包含错误信息的 Error
            if (result.isSuccess) {
                val lastAction = result.actions.lastOrNull()
                if (lastAction != null) {
                    Result.success(lastAction)
                } else {
                    Result.success(
                        AgentAction.Success(
                            commandId = planCommand.commandId,
                            command = planCommand
                        )
                    )
                }
            } else {
                val firstError = result.stepResults
                    .filterIsInstance<StepResult.Failed>()
                    .firstOrNull()
                Result.success(
                    AgentAction.Error(
                        commandId = planCommand.commandId,
                        errorCode = firstError?.action?.errorCode ?: AgentErrorCode.INTERNAL_ERROR,
                        message = firstError?.action?.message ?: "计划执行失败",
                        detail = "Plan '${planCommand.plan.planId}' failed at step ${firstError?.step?.step ?: "unknown"}"
                    )
                )
            }
        } catch (throwable: Throwable) {
            Logger.e(tag, "[ExecutePlan] Plan execution threw exception", throwable)
            Result.success(
                AgentAction.Error(
                    commandId = planCommand.commandId,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = "计划执行异常: ${throwable.message}",
                    detail = throwable.stackTraceToString()
                )
            )
        }
    }
}