package com.mamba.picme.agent.core.facade

import android.content.Context
import com.mamba.picme.agent.core.api.android.RemoteModelConfig
import com.mamba.picme.agent.core.api.capability.Capability
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.api.policy.AiAgentMode
import com.mamba.picme.agent.core.api.policy.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.platform.llm.local.LocalLlmEngine
import com.mamba.picme.agent.core.platform.llm.remote.RemoteOrchestrator
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.MemoryManager
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.inference.AdaptiveStrategySelector
import com.mamba.picme.agent.core.runtime.inference.InferenceRouter
import com.mamba.picme.agent.core.runtime.inference.IntentCache
import com.mamba.picme.agent.core.runtime.parsing.PromptBuilder
import com.mamba.picme.agent.core.runtime.policy.PrivacyGuard
import com.mamba.picme.agent.core.runtime.state.SceneManager

/**
 * Agent 配置器
 *
 * 负责初始化和配置 Agent 运行时所需的所有核心组件。
 * 作为 [AgentOrchestrator] 的依赖工厂，集中管理组件生命周期。
 */
class AgentConfigurator(private val context: Context) {

    private val tag = "AgentConfigurator"

    /**
     * 获取 Application Context
     */
    fun getContext(): Context = context

    // 核心组件（延迟初始化）
    val localLlmEngine = LocalLlmEngine(context)
    val memoryManager = MemoryManager(context)
    val privacyGuard = PrivacyGuard()
    val sceneManager = SceneManager.getInstance()
    val promptBuilder = PromptBuilder(sceneManager)
    val capabilityRegistry = CapabilityRegistry.getInstance()
    val strategySelector = AdaptiveStrategySelector()

    // L1 意图缓存
    val intentCache = IntentCache()

    // 配置状态
    private var agentMode: AiAgentMode = AiAgentMode.LOCAL
    private var currentModelId: String = "qwen3_5_2b"
    private var userRemoteConfig: RemoteModelConfig? = null
    private var inferenceRouterConfig: RemoteModelConfig? = null
    private var inferenceRouter: InferenceRouter? = null
    private var localUseOpencl: Boolean = false

    /**
     * 获取或创建 InferenceRouter
     * 如果配置变化，会重新创建以确保使用最新远程配置
     */
    fun getInferenceRouter(): InferenceRouter {
        val currentConfig = userRemoteConfig ?: RemoteModelConfig.TENCENT_SCF_DEFAULT
        val existing = inferenceRouter
        if (existing != null && inferenceRouterConfig == currentConfig) {
            return existing
        }
        val remoteOrchestrator = createRemoteOrchestrator(currentConfig)
        val newRouter = InferenceRouter(
            localEngine = localLlmEngine,
            remoteOrchestrator = remoteOrchestrator,
            strategySelector = strategySelector,
            privacyGuard = privacyGuard
        )
        inferenceRouter = newRouter
        inferenceRouterConfig = currentConfig
        return newRouter
    }

    /**
     * 创建远程编排器
     */
    private fun createRemoteOrchestrator(config: RemoteModelConfig): RemoteOrchestrator {
        Logger.i(tag, "Creating RemoteOrchestrator with model=${config.modelId}, baseUrl=${config.baseUrl}")
        return RemoteOrchestrator(
            context = context,
            remoteConfig = config,
            promptBuilder = promptBuilder
        )
    }

    /**
     * 配置 Agent 运行参数
     */
    fun configure(
        mode: AiAgentMode,
        modelId: String,
        privacyLevel: AiAgentPrivacyLevel,
        remoteConfig: RemoteModelConfig? = null,
        localUseOpencl: Boolean = false
    ) {
        this.agentMode = mode
        this.currentModelId = modelId
        this.localUseOpencl = localUseOpencl
        if (remoteConfig != null && remoteConfig.baseUrl.isNotBlank() && remoteConfig.modelId.isNotBlank()) {
            this.userRemoteConfig = remoteConfig
            inferenceRouter = null
            inferenceRouterConfig = null
        }
        privacyGuard.updateConfig(privacyLevel, mode)
        Logger.i(tag, "Configured: mode=$mode, model=$modelId, privacy=$privacyLevel, " +
            "localUseOpencl=$localUseOpencl, " +
            "remoteModel=${remoteConfig?.modelId ?: "default"}, " +
            "effectiveRemoteModel=${userRemoteConfig?.modelId ?: "fallback"}")
    }

    /**
     * 当前 Agent 运行模式
     */
    fun getAgentMode(): AiAgentMode = agentMode

    /**
     * 当前模型 ID
     */
    fun getCurrentModelId(): String = currentModelId

    /**
     * 当前本地 LLM 后端是否使用 OpenCL
     */
    fun getLocalUseOpencl(): Boolean = localUseOpencl

    /**
     * 用户远程配置
     */
    fun getUserRemoteConfig(): RemoteModelConfig? = userRemoteConfig

    /**
     * 模型是否已加载
     */
    val isModelLoaded: Boolean
        get() = localLlmEngine.isLoaded
}
