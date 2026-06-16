package com.mamba.picme.agent.core.remote.pipeline

import com.mamba.picme.agent.core.api.ChatRequest
import com.mamba.picme.agent.core.api.SystemMessage
import com.mamba.picme.agent.core.api.UserMessage
import com.mamba.picme.agent.core.api.command.AgentCommand
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.api.context.AgentIdGenerator
import com.mamba.picme.agent.core.local.parser.LocalCommandParser
import com.mamba.picme.agent.core.platform.llm.local.LocalLlmEngine
import com.mamba.picme.agent.core.platform.llm.remote.RemoteOrchestrator
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.remote.parser.ToolCallParser
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.execution.InferenceResult
import com.mamba.picme.agent.core.runtime.inference.IntentCache
import com.mamba.picme.agent.core.runtime.policy.PrivacyGuard
import com.mamba.picme.agent.core.runtime.state.SceneManager
import org.json.JSONObject

/**
 * 远程推理管道
 *
 * 负责使用远程 LLM（DeepSeek/Kimi via cloud API）进行推理，
 * 使用标准 OpenAI tool_calls 协议。
 * 提供三个层级：
 * - L2: 批量命令（Batch Function Calling）
 * - L3: 计划执行（Plan-and-Execute）
 * - L4: 纯文本对话
 *
 * 与本地管道完全独立，无共享路由逻辑。
 */
class RemoteInferencePipeline(
    private val remoteOrchestrator: RemoteOrchestrator,
    private val localEngine: LocalLlmEngine,
    private val sceneManager: SceneManager,
    private val capabilityRegistry: CapabilityRegistry,
    private val intentCache: IntentCache,
    private val privacyGuard: PrivacyGuard
) {

    private val tag = "RemoteInferencePipeline"

    /**
     * 处理用户输入（主入口）
     *
     * 路由逻辑：
     * 1. L1 缓存查询
     * 2. 隐私分级检查
     * 3. 按策略路由到 L2/L3/L4
     *
     * @param userInput 用户自然语言输入
     * @param context 当前 Agent 上下文
     * @return 推理结果
     */
    suspend fun processInput(
        userInput: String,
        context: AgentContext
    ): InferenceResult {
        Logger.d(tag, "Processing input via remote pipeline: '$userInput'")

        // 1. L1 缓存查询
        val cachedCommand = intentCache.match(userInput)
        if (cachedCommand != null) {
            Logger.i(tag, "L1 cache hit for input='$userInput' -> ${cachedCommand::class.simpleName}")
            return InferenceResult.Local(command = cachedCommand)
        }

        // 2. 隐私分级检查
        val privacyLevel = privacyGuard.classify(userInput)
        Logger.d(tag, "Privacy level: $privacyLevel")

        // 3. L2 Batch（默认远程入口）
        Logger.i(tag, "[L2-BATCH] Remote L2 Batch FC")
        return remoteOrchestrator.processBatch(userInput, context)
    }

    /**
     * 处理用户输入为 L3 Plan
     */
    suspend fun processPlan(
        userInput: String,
        context: AgentContext
    ): InferenceResult {
        return remoteOrchestrator.processPlan(userInput, context)
    }

    /**
     * 处理用户输入为 L4 Chat
     */
    suspend fun processChat(
        userInput: String,
        context: AgentContext
    ): InferenceResult {
        return remoteOrchestrator.processChat(userInput, context)
    }
}
