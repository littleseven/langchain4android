package com.mamba.picme.agent.core.facade

import android.content.Context
import com.mamba.picme.agent.core.api.android.RemoteModelConfig
import com.mamba.picme.agent.core.api.policy.AiAgentMode
import com.mamba.picme.agent.core.api.policy.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.api.ToolProvider
import com.mamba.picme.agent.core.local.pipeline.LocalInferencePipeline
import com.mamba.picme.agent.core.local.prompt.LocalPromptBuilder
import com.mamba.picme.agent.core.platform.llm.local.LocalLlmEngine
import com.mamba.picme.agent.core.platform.llm.remote.RemoteOrchestrator
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.MemoryManager
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.inference.IntentCache
import com.mamba.picme.agent.core.remote.pipeline.RemoteInferencePipeline
import com.mamba.picme.agent.core.remote.prompt.RemotePromptBuilder
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
    val localPromptBuilder = LocalPromptBuilder(sceneManager)
    val remotePromptBuilder = RemotePromptBuilder(sceneManager)
    val capabilityRegistry = CapabilityRegistry.getInstance()
    val intentCache = IntentCache()

    /**
     * Tool Provider（LangChain4j 风格 Tool Calling）。
     * 默认使用 [CapabilityRegistry]，未注册 Capability 时工具列表为空，自动回退到原有推理链路。
     */
    var toolProvider: ToolProvider? = capabilityRegistry
        private set

    /**
     * 设置 Tool Provider。传入 null 可关闭 Tool Calling。
     */
    fun setToolProvider(provider: ToolProvider?) {
        toolProvider = provider
    }

    // 配置状态
    private var agentMode: AiAgentMode = AiAgentMode.LOCAL
    private var currentModelId: String = "qwen3_5_2b"
    private var userRemoteConfig: RemoteModelConfig? = null
    private var pipelineRemoteConfig: RemoteModelConfig? = null
    private var localInferencePipeline: LocalInferencePipeline? = null
    private var remoteInferencePipeline: RemoteInferencePipeline? = null
    private var localUseOpencl: Boolean = false

    /**
     * 获取或创建本地推理管道
     */
    fun getLocalPipeline(): LocalInferencePipeline {
        val existing = localInferencePipeline
        if (existing != null) return existing
        val pipeline = LocalInferencePipeline(
            localEngine = localLlmEngine,
            sceneManager = sceneManager,
            capabilityRegistry = capabilityRegistry,
            intentCache = intentCache,
            privacyGuard = privacyGuard
        )
        localInferencePipeline = pipeline
        return pipeline
    }

    /**
     * 获取或创建远程推理管道
     */
    fun getRemotePipeline(): RemoteInferencePipeline {
        val currentConfig = userRemoteConfig ?: RemoteModelConfig.TENCENT_SCF_DEFAULT
        val existing = remoteInferencePipeline
        if (existing != null && pipelineRemoteConfig == currentConfig) {
            return existing
        }
        val remoteOrchestrator = createRemoteOrchestrator(currentConfig)
        val pipeline = RemoteInferencePipeline(
            remoteOrchestrator = remoteOrchestrator,
            localEngine = localLlmEngine,
            sceneManager = sceneManager,
            capabilityRegistry = capabilityRegistry,
            intentCache = intentCache,
            privacyGuard = privacyGuard
        )
        remoteInferencePipeline = pipeline
        pipelineRemoteConfig = currentConfig
        return pipeline
    }

    /**
     * 创建远程编排器
     */
    private fun createRemoteOrchestrator(config: RemoteModelConfig): RemoteOrchestrator {
        Logger.i(tag, "Creating RemoteOrchestrator with model=${config.modelId}, baseUrl=${config.baseUrl}")
        return RemoteOrchestrator(
            context = context,
            remoteConfig = config,
            promptBuilder = remotePromptBuilder
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
            localInferencePipeline = null
            remoteInferencePipeline = null
            pipelineRemoteConfig = null
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
