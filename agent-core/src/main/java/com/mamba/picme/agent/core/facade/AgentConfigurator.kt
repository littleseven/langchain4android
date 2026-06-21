package com.mamba.picme.agent.core.facade

import android.content.Context
import android.view.WindowManager
import com.mamba.picme.agent.core.api.android.RemoteModelConfig
import com.mamba.picme.agent.core.api.policy.AiAgentMode
import com.mamba.picme.agent.core.api.policy.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import com.mamba.picme.agent.core.inference.local.pipeline.LocalInferencePipeline
import com.mamba.picme.agent.core.inference.local.prompt.LocalPromptBuilder
import com.mamba.picme.agent.core.inference.local.react.InAppAgentCallback
import com.mamba.picme.agent.core.inference.local.react.InAppAgentConfig
import com.mamba.picme.agent.core.inference.local.react.InAppAgentService
import com.mamba.picme.agent.core.inference.remote.llm.RemoteOrchestrator
import com.mamba.picme.agent.core.inference.remote.prompt.RemotePromptBuilder
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.MemoryManager
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.inference.IntentCache
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
     * 模式临时覆盖栈（用于飞书远程控制等场景强制使用特定推理模式）。
     *
     * - [pushModeOverride] 压入覆盖模式
     * - [popModeOverride] 弹出恢复
     * - [getAgentMode] 优先返回栈顶覆盖模式，栈空时返回持久化模式
     *
     * 使用场景：RemoteCommandDispatcher 在处理飞书消息时压入 REMOTE，
     * 处理完成后弹出，不影响用户设置的持久化模式。
     */
    private val modeOverrideStack = ArrayDeque<AiAgentMode>()

    // 配置状态
    private var agentMode: AiAgentMode = AiAgentMode.REMOTE
    private var currentModelId: String = "qwen3_5_2b"
    private var userRemoteConfig: RemoteModelConfig? = null
    private var pipelineRemoteConfig: RemoteModelConfig? = null
    private var localInferencePipeline: LocalInferencePipeline? = null
    private var cachedRemoteOrchestrator: RemoteOrchestrator? = null
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
            privacyGuard = privacyGuard,
            memoryManager = memoryManager
        )
        localInferencePipeline = pipeline
        return pipeline
    }

    /**
     * 获取或创建远程编排器（直接返回，不再经过 RemoteInferencePipeline 包装）
     */
    fun getRemoteOrchestrator(): RemoteOrchestrator {
        val currentConfig = userRemoteConfig ?: RemoteModelConfig.TENCENT_SCF_DEFAULT
        val existing = cachedRemoteOrchestrator
        if (existing != null && pipelineRemoteConfig == currentConfig) {
            return existing
        }
        val orchestrator = createRemoteOrchestrator(currentConfig)
        cachedRemoteOrchestrator = orchestrator
        pipelineRemoteConfig = currentConfig
        return orchestrator
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
            cachedRemoteOrchestrator = null
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
     *
     * 优先返回临时覆盖模式（[modeOverrideStack] 栈顶），
     * 栈空时返回持久化模式（[agentMode]）。
     */
    fun getAgentMode(): AiAgentMode = modeOverrideStack.lastOrNull() ?: agentMode

    /**
     * 压入模式临时覆盖。
     * 此后 [getAgentMode] 将返回 [mode]，直到 [popModeOverride] 被调用。
     *
     * 支持嵌套：多次压入需要对应次数弹出。
     */
    fun pushModeOverride(mode: AiAgentMode) {
        modeOverrideStack.addLast(mode)
        Logger.d(tag, "Mode override pushed: $mode (stack size=${modeOverrideStack.size})")
    }

    /**
     * 弹出模式临时覆盖。
     * 恢复栈为空时返回持久化模式。
     *
     * @throws NoSuchElementException 栈已空时调用
     */
    fun popModeOverride() {
        val popped = modeOverrideStack.removeLastOrNull()
        if (popped != null) {
            Logger.d(tag, "Mode override popped: $popped (stack size=${modeOverrideStack.size})")
        } else {
            Logger.w(tag, "popModeOverride called on empty stack")
        }
    }

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

    // ── 飞书 ReAct Agent（懒创建）────────────────────────────────────

    private var cachedFeishuAgent: InAppAgentService? = null

    /** 缓存的 Feishu Agent 对应的配置，用于检测配置变更 */
    private var cachedFeishuAgentConfig: RemoteModelConfig? = null

    /**
     * 获取或创建飞书 ReAct Agent。
     * 优先使用用户配置的远程模型，未配置时使用腾讯 SCF 默认兜底。
     *
     * 当用户配置发生变更时（cachedFeishuAgentConfig != userRemoteConfig），
     * 自动重建 Agent 以确保使用最新的 API Key / baseUrl / model。
     */
    fun getFeishuAgent(windowManager: WindowManager, callback: InAppAgentCallback): InAppAgentService? {
        val existing = cachedFeishuAgent
        val currentConfig = userRemoteConfig ?: RemoteModelConfig.TENCENT_SCF_DEFAULT

        // 配置变更检测：如果用户修改了远程模型配置，重建 Agent
        if (existing != null && cachedFeishuAgentConfig != null) {
            val configChanged = cachedFeishuAgentConfig?.modelId != currentConfig.modelId
                || cachedFeishuAgentConfig?.baseUrl != currentConfig.baseUrl
                || cachedFeishuAgentConfig?.apiKey != currentConfig.apiKey
                || cachedFeishuAgentConfig?.gatewayToken != currentConfig.gatewayToken
            if (configChanged) {
                Logger.i("AgentConfigurator", "Remote config changed (model=${currentConfig.modelId}), rebuilding Feishu Agent")
                existing.shutdown()
                cachedFeishuAgent = null
                cachedFeishuAgentConfig = null
            } else {
                return existing
            }
        } else if (existing != null) {
            return existing
        }

        val cfg = try {
            InAppAgentConfig.Builder()
                .apiKey(currentConfig.apiKey)
                .baseUrl(currentConfig.baseUrl)
                .modelName(currentConfig.modelId)
                .gatewayToken(currentConfig.gatewayToken)
                .build()
        } catch (e: Exception) {
            Logger.w("AgentConfigurator", "Failed to build FeishuAgent config", e)
            return null
        }

        val agent = InAppAgentService(cfg, windowManager, callback, context)
        agent.initialize()
        cachedFeishuAgent = agent
        cachedFeishuAgentConfig = currentConfig
        Logger.i("AgentConfigurator", "Feishu ReAct Agent created: model=${cfg.modelName}, baseUrl=${currentConfig.baseUrl.take(40)}")
        return agent
    }

    /**
     * 清除飞书 ReAct Agent 缓存（用于配置变更后重建）
     */
    fun clearFeishuAgent() {
        cachedFeishuAgent?.shutdown()
        cachedFeishuAgent = null
        cachedFeishuAgentConfig = null
        Logger.i("AgentConfigurator", "Feishu ReAct Agent cleared")
    }
}
