package com.picme.agent.core

import com.picme.agent.core.Logger
import com.picme.agent.core.model.AgentCommand
import com.picme.agent.core.model.AgentContext
import com.picme.agent.core.model.InferenceResult
import com.picme.agent.core.remote.AdaptiveStrategySelector
import com.picme.agent.core.remote.InferenceStrategy
import com.picme.agent.core.remote.RemoteOrchestrator
import com.picme.agent.core.CapabilityRegistry
import com.picme.agent.core.SceneManager

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

        // 3. 根据策略路由
        return when (strategy) {
            is InferenceStrategy.L1_Cached -> {
                Logger.d(tag, "L1 Cache hit, returning cached command")
                InferenceResult.Local(command = strategy.command)
            }
            is InferenceStrategy.L2_BatchFC -> {
                Logger.d(tag, "Routing to L2 Batch FC (remote)")
                remoteOrchestrator.processBatch(
                    userInput = strategy.userInput,
                    context = strategy.context
                )
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
     * 路由到本地引擎
     *
     * 构建 system prompt 并调用本地 LLM 生成单条命令。
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
