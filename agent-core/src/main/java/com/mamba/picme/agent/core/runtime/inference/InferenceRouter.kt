package com.mamba.picme.agent.core.runtime.inference

import com.mamba.picme.agent.core.api.command.AgentCommand
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.api.execution.ExecutionPlan
import com.mamba.picme.agent.core.api.execution.PlanStep
import com.mamba.picme.agent.core.platform.llm.local.LocalLlmEngine
import com.mamba.picme.agent.core.platform.llm.remote.RemoteOrchestrator
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.execution.InferenceResult
import com.mamba.picme.agent.core.runtime.parsing.AgentCommandParser
import com.mamba.picme.agent.core.runtime.parsing.PromptBuilder
import com.mamba.picme.agent.core.runtime.policy.PrivacyGuard
import com.mamba.picme.agent.core.runtime.policy.PrivacyLevel
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 推理路由器
 *
 * 根据隐私级别和自适应策略选择器的结果，将用户输入路由到本地或远程推理引擎：
 * - RESTRICTED 隐私级别：强制本地推理
 * - 其他级别：根据 [AdaptiveStrategySelector] 选择策略
 *   - L1_Cached → 本地单命令
 *   - L2_BatchFC → 远程批量命令
 *   - L3_PlanExecute → 远程计划执行
 *   - L4_ReAct → 远程聊天
 *
 * @property localEngine 本地 LLM 引擎
 * @property remoteOrchestrator 远程编排器
 * @property strategySelector 自适应策略选择器
 * @property privacyGuard 隐私守卫（默认实例）
 */
class InferenceRouter(
    private val localEngine: LocalLlmEngine,
    private val remoteOrchestrator: RemoteOrchestrator,
    private val strategySelector: AdaptiveStrategySelector,
    private val privacyGuard: PrivacyGuard = PrivacyGuard()
) {

    private val tag = "InferenceRouter"

    companion object {
        /**
         * L2 本地快速通道超时阈值（毫秒）。
         * 超过此时间未返回则降级到远程推理，避免用户等待过久。
         */
        const val LOCAL_L2_TIMEOUT_MS = 10000L
    }

    /**
     * 处理用户输入
     *
     * 路由逻辑：
     * 1. 先进行隐私分级，RESTRICTED 强制走本地
     * 2. 否则使用策略选择器确定推理层级
     * 3. 根据策略路由到对应引擎
     *
     * @param userInput 用户自然语言输入
     * @param context 当前 Agent 上下文
     * @return 推理结果
     */
    suspend fun processInput(
        userInput: String,
        context: AgentContext
    ): InferenceResult {
        Logger.d(tag, "Processing input: '$userInput'")

        // 1. 隐私分级检查
        val privacyLevel = privacyGuard.classify(userInput)
        Logger.d(tag, "Privacy level: $privacyLevel")

        if (privacyLevel == PrivacyLevel.RESTRICTED) {
            Logger.i(tag, "RESTRICTED content detected, routing to local engine")
            return routeToLocal(userInput, context)
        }

        // 2. 选择策略
        val strategy = strategySelector.selectStrategy(userInput, context)
        Logger.d(tag, "Selected strategy: ${strategy::class.simpleName}")

        // 3. 根据策略路由（L2 优先本地快速通道，L3/L4 走远程）
        return when (strategy) {
            is InferenceStrategy.L1_Cached -> {
                Logger.d(tag, "L1 Cache hit, returning cached command")
                InferenceResult.Local(command = strategy.command)
            }
            is InferenceStrategy.L2_BatchFC -> {
                // P0: L2 本地快速通道 —— 先尝试本地模型处理简单批量指令
                Logger.i(tag, "[L2-LOCAL] Strategy is L2_BatchFC, trying local first")
                val localResult = tryLocalL2First(strategy.userInput, strategy.context)
                if (localResult != null) {
                    Logger.i(tag, "[L2-LOCAL] Fast path hit, returning local result")
                    localResult
                } else {
                    // [本地优先] L2 本地失败后，如果指令包含延迟/时间关键词，
                    // 尝试 L3 本地 Plan 模式（使用简化 Plan Prompt），而非直接回退远程
                    val hasDelay = AdaptiveStrategySelector.DELAY_KEYWORDS.any { strategy.userInput.contains(it) }
                    if (hasDelay) {
                        Logger.i(tag, "[L3-LOCAL] L2 failed but input has delay keywords, trying local L3 Plan")
                        val localPlanResult = tryLocalL3First(strategy.userInput, strategy.context)
                        if (localPlanResult != null) {
                            Logger.i(tag, "[L3-LOCAL] Local plan path hit, returning local plan result")
                            localPlanResult
                        } else {
                            Logger.i(tag, "[L3-LOCAL] Local plan failed, falling back to remote L2 Batch")
                            remoteOrchestrator.processBatch(
                                userInput = strategy.userInput,
                                context = strategy.context
                            )
                        }
                    } else {
                        Logger.i(tag, "[L2-LOCAL] Local fast path failed, routing to L2 Batch FC (remote)")
                        remoteOrchestrator.processBatch(
                            userInput = strategy.userInput,
                            context = strategy.context
                        )
                    }
                }
            }
            is InferenceStrategy.L3_PlanExecute -> {
                Logger.d(tag, "Routing to L3 Plan-Execute (remote)")
                remoteOrchestrator.processPlan(
                    userInput = strategy.userInput,
                    context = strategy.context
                )
            }
            is InferenceStrategy.L4_ReAct -> {
                Logger.d(tag, "Routing to L4 ReAct Chat (remote)")
                remoteOrchestrator.processChat(
                    userInput = strategy.userInput,
                    context = strategy.context
                )
            }
        }
    }

    /**
     * 尝试 L3 本地快速通道（简化版 Plan 模式）。
     *
     * 对包含延迟/时间依赖的指令，使用简化 Plan Prompt 尝试本地模型推理。
     * 成功则返回 Plan 结果，失败或超时返回 null 以触发远程降级。
     *
     * @param userInput 用户输入
     * @param context Agent 上下文
     * @return 本地推理结果，失败时返回 null
     */
    private suspend fun tryLocalL3First(
        userInput: String,
        context: AgentContext
    ): InferenceResult? = withTimeoutOrNull(LOCAL_L2_TIMEOUT_MS) {
        Logger.i(tag, "[L3-LOCAL] Entering local L3 fast path for input='$userInput', timeout=${LOCAL_L2_TIMEOUT_MS}ms")
        val startTime = System.currentTimeMillis()
        val result = routeToLocalL3(userInput, context)
        val latency = System.currentTimeMillis() - startTime

        Logger.i(tag, "[L3-LOCAL] routeToLocalL3 returned type=${result::class.simpleName}, latency=${latency}ms")
        if (result is InferenceResult.Plan) {
            Logger.i(tag, "[L3-LOCAL] plan steps=${result.plan.steps.size}")
        }

        when {
            result is InferenceResult.Plan && result.plan.steps.isNotEmpty() -> {
                Logger.i(tag, "[L3-LOCAL] Success, latency=${latency}ms")
                result
            }
            else -> {
                Logger.w(tag, "[L3-LOCAL] Failed or invalid result type, latency=${latency}ms, falling back")
                null
            }
        }
    }

    /**
     * 路由到本地引擎（L3 简化版）
     *
     * 使用 L2 的简化 prompt 但要求输出 Plan 格式（将 L2 的 Batch 结果转为 Plan）。
     * 端侧小模型对 Plan JSON 格式理解能力有限，所以先用 L2 Batch Prompt 生成命令数组，
     * 然后包装为 ExecutionPlan。
     *
     * @param userInput 用户输入
     * @param context Agent 上下文
     * @return 本地推理结果（Plan 类型）
     */
    private suspend fun routeToLocalL3(
        userInput: String,
        context: AgentContext
    ): InferenceResult {
        val promptBuilder = PromptBuilder(SceneManager.getInstance())
        val capabilities = CapabilityRegistry.getInstance()
            .getCapabilitiesForCurrentScene()

        // 使用 L2 的简化 system prompt（端侧模型更易理解）
        val systemPrompt = promptBuilder.buildL2SystemPrompt(capabilities, context)
        val userPrompt = buildString {
            appendLine("用户输入: $userInput")
            appendLine()
            appendLine("请输出 JSON 数组，不要其他内容:")
        }

        Logger.i(tag, "[L3-LOCAL] Calling localEngine.generateWithSystem, modelLoaded=${localEngine.isLoaded}")
        val result = localEngine.generateWithSystem(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            maxTokens = 256  // L3 需要更多 token 生成多步骤
        )

        return result.fold(
            onSuccess = { response ->
                Logger.i(tag, "[L3-LOCAL] Local engine raw response: ${response.replace("\n", "\\n")}")
                
                // 先尝试按 L2 Batch 解析命令数组
                val commands = try {
                    AgentCommandParser.parseL2BatchResponse(response, context)
                } catch (e: Exception) {
                    Logger.w(tag, "[L3-LOCAL] Failed to parse as batch, trying single command", e)
                    listOf(AgentCommandParser.parseLlmResponse(response, context))
                }
                
                Logger.i(tag, "[L3-LOCAL] Parsed ${commands.size} commands: ${commands.map { it::class.simpleName }}")
                
                // 将命令数组包装为 ExecutionPlan
                if (commands.isNotEmpty() && commands.all { it !is AgentCommand.Error }) {
                    val steps = commands.mapIndexed { index, command ->
                        PlanStep(
                            step = index + 1,
                            action = command,
                            description = AgentCommand.getMethodName(command)
                        )
                    }
                    val plan = ExecutionPlan(
                        planId = "local_plan_${System.currentTimeMillis()}",
                        description = userInput,
                        steps = steps
                    )
                    InferenceResult.Plan(plan = plan)
                } else {
                    Logger.w(tag, "[L3-LOCAL] Parsed commands empty or contains error")
                    InferenceResult.Plan(
                        plan = ExecutionPlan(
                            planId = "local_plan_error",
                            description = "解析失败",
                            steps = emptyList()
                        )
                    )
                }
            },
            onFailure = { error ->
                Logger.e(tag, "[L3-LOCAL] Local L3 engine failed", error)
                InferenceResult.Plan(
                    plan = ExecutionPlan(
                        planId = "local_plan_error",
                        description = "推理失败：${error.message ?: "未知错误"}",
                        steps = emptyList()
                    )
                )
            }
        )
    }

    /**
     * 尝试 L2 本地快速通道。
     *
     * 对简单批量指令（无条件、无复杂依赖）优先使用本地模型推理，
     * 成功则返回结果，失败或超时返回 null 以触发远程降级。
     *
     * @param userInput 用户输入
     * @param context Agent 上下文
     * @return 本地推理结果，失败时返回 null
     */
    private suspend fun tryLocalL2First(
        userInput: String,
        context: AgentContext
    ): InferenceResult? = withTimeoutOrNull(LOCAL_L2_TIMEOUT_MS) {
        Logger.i(tag, "[L2-LOCAL] Entering local L2 fast path for input='$userInput', timeout=${LOCAL_L2_TIMEOUT_MS}ms")
        val startTime = System.currentTimeMillis()
        val result = routeToLocalL2(userInput, context)
        val latency = System.currentTimeMillis() - startTime

        Logger.i(tag, "[L2-LOCAL] routeToLocalL2 returned type=${result::class.simpleName}, latency=${latency}ms")
        if (result is InferenceResult.Local) {
            Logger.i(tag, "[L2-LOCAL] command type=${result.command::class.simpleName}, isError=${result.command is AgentCommand.Error}")
        }

        when {
            result is InferenceResult.Local && result.command !is AgentCommand.Error -> {
                Logger.i(tag, "[L2-LOCAL] Success, latency=${latency}ms")
                result
            }
            result is InferenceResult.Local && result.command is AgentCommand.Error -> {
                Logger.w(tag, "[L2-LOCAL] Local engine returned error, latency=${latency}ms, falling back. reason=${(result.command as AgentCommand.Error).reason}")
                null
            }
            else -> {
                Logger.w(tag, "[L2-LOCAL] Failed or invalid result type, latency=${latency}ms, falling back")
                null
            }
        }
    }

    /**
     * 路由到本地引擎（L2 专用简化版）
     *
     * 使用简化 prompt 减少 token 数，提升本地模型推理速度。
     *
     * @param userInput 用户输入
     * @param context Agent 上下文
     * @return 本地推理结果
     */
    private suspend fun routeToLocalL2(
        userInput: String,
        context: AgentContext
    ): InferenceResult {
        val promptBuilder = PromptBuilder(SceneManager.getInstance())
        val capabilities = CapabilityRegistry.getInstance()
            .getCapabilitiesForCurrentScene()

        val systemPrompt = promptBuilder.buildL2SystemPrompt(capabilities, context)
        val userPrompt = buildString {
            appendLine("用户输入: $userInput")
            appendLine()
            appendLine("请输出 JSON 数组，不要其他内容:")
        }

        // 打印完整 prompt 用于调试
        val totalPromptLength = systemPrompt.length + userPrompt.length
        val estimatedTokens = totalPromptLength / 2
        Logger.d(tag, "===== L2 SYSTEM PROMPT ===== [len=${systemPrompt.length}, estTokens~${systemPrompt.length / 2}]")
        systemPrompt.lineSequence().forEach { line ->
            Logger.d(tag, line)
        }
        Logger.d(tag, "===== L2 USER PROMPT ===== [len=${userPrompt.length}, estTokens~${userPrompt.length / 2}]")
        Logger.d(tag, userPrompt)
        Logger.d(tag, "===== END L2 PROMPT ===== [totalLen=$totalPromptLength, totalEstTokens~$estimatedTokens, maxTokens=128]")

        Logger.i(tag, "[L2-LOCAL] Calling localEngine.generateWithSystem, modelLoaded=${localEngine.isLoaded}")
        val result = localEngine.generateWithSystem(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            maxTokens = 256  // 增加 token 上限，避免思考过程占用导致 JSON 截断
        )

        return result.fold(
            onSuccess = { response ->
                Logger.i(tag, "[L2-LOCAL] Local engine raw response: ${response.replace("\n", "\\n")}")
                val command = AgentCommandParser.parseLlmResponse(response, context)
                Logger.i(tag, "[L2-LOCAL] Parsed command: ${command::class.simpleName}")
                InferenceResult.Local(command = command)
            },
            onFailure = { error ->
                Logger.e(tag, "[L2-LOCAL] Local L2 engine failed", error)
                InferenceResult.Local(
                    command = AgentCommand.Error(reason = "本地推理失败：${error.message ?: "未知错误"}")
                )
            }
        )
    }

    /**
     * 路由到本地引擎（隐私强制/通用版）
     *
     * 构建完整 system prompt 并调用本地 LLM 生成单条命令。
     *
     * @param userInput 用户输入
     * @param context Agent 上下文
     * @return 本地推理结果
     */
    private suspend fun routeToLocal(
        userInput: String,
        context: AgentContext
    ): InferenceResult {
        val promptBuilder = PromptBuilder(SceneManager.getInstance())
        val capabilities = CapabilityRegistry.getInstance()
            .getCapabilitiesForCurrentScene()

        val systemPrompt = promptBuilder.buildSystemPrompt(capabilities, context)
        val userPrompt = buildString {
            appendLine("用户输入: $userInput")
            appendLine()
            appendLine("请只输出一行JSON，不要其他内容:")
        }

        // 打印完整 prompt 用于调试
        val totalPromptLength = systemPrompt.length + userPrompt.length
        val estimatedTokens = totalPromptLength / 2  // 中文字符约 1-2 token，取保守估计
        Logger.d(tag, "===== SYSTEM PROMPT ===== [len=${systemPrompt.length}, estTokens~${systemPrompt.length / 2}]")
        systemPrompt.lineSequence().forEach { line ->
            Logger.d(tag, line)
        }
        Logger.d(tag, "===== USER PROMPT ===== [len=${userPrompt.length}, estTokens~${userPrompt.length / 2}]")
        Logger.d(tag, userPrompt)
        Logger.d(tag, "===== END PROMPT ===== [totalLen=$totalPromptLength, totalEstTokens~$estimatedTokens, maxTokens=128]")

        val result = localEngine.generateWithSystem(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            maxTokens = 128
        )

        return result.fold(
            onSuccess = { response ->
                val command = AgentCommandParser.parseLlmResponse(response, context)
                InferenceResult.Local(command = command)
            },
            onFailure = { error ->
                Logger.e(tag, "Local engine failed", error)
                InferenceResult.Local(
                    command = AgentCommand.Error(reason = "本地推理失败：${error.message ?: "未知错误"}")
                )
            }
        )
    }
}
