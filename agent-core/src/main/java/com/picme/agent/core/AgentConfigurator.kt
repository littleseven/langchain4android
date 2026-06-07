package com.picme.agent.core

import android.content.Context
import com.picme.agent.core.Logger
import com.picme.agent.core.model.AiAgentMode
import com.picme.agent.core.model.AiAgentPrivacyLevel
import com.picme.agent.core.model.RemoteModelConfig
import com.picme.agent.core.SceneManager
import com.picme.agent.core.remote.AdaptiveStrategySelector
import com.picme.agent.core.remote.IntentCache
import com.picme.agent.core.remote.RemoteOrchestrator

/**
 * Agent 平台配置器
 *
 * 负责创建和配置 Agent 运行所需的平台特定组件：
 * - 本地 LLM 引擎（MNN-LLM）
 * - 远程编排器（RemoteOrchestrator）
 * - 推理路由器（InferenceRouter）
 * - 隐私守卫（PrivacyGuard）
 * - 场景管理器（SceneManager）
 * - 提示词构建器（PromptBuilder）
 *
 * 将平台特定的组件创建逻辑从 AgentOrchestrator 中剥离，
 * 使 Orchestrator 专注于纯编排逻辑，便于独立库提取。
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
    private var currentModelId: String = "qwen3_1_7b"
    private var userRemoteConfig: RemoteModelConfig? = null
    private var inferenceRouterConfig: RemoteModelConfig? = null
    private var inferenceRouter: InferenceRouter? = null

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
        remoteConfig: RemoteModelConfig? = null
    ) {
        this.agentMode = mode
        this.currentModelId = modelId
        if (remoteConfig != null && remoteConfig.baseUrl.isNotBlank() && remoteConfig.modelId.isNotBlank()) {
            this.userRemoteConfig = remoteConfig
            inferenceRouter = null
            inferenceRouterConfig = null
        }
        privacyGuard.updateConfig(privacyLevel, mode)
        Logger.i(tag, "Configured: mode=$mode, model=$modelId, privacy=$privacyLevel, " +
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
     * 用户远程配置
     */
    fun getUserRemoteConfig(): RemoteModelConfig? = userRemoteConfig

    /**
     * 模型是否已加载
     */
    val isModelLoaded: Boolean
        get() = localLlmEngine.isLoaded
}
