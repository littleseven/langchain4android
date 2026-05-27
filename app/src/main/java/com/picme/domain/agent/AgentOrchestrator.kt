package com.picme.domain.agent

import android.content.Context
import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.core.common.Logger
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.AgentScene
import com.picme.domain.agent.model.ChatMessage
import com.picme.domain.agent.model.ChatRole
import com.picme.domain.model.AiAgentMode
import com.picme.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Agent 编排器
 *
 * Agent Runtime 的统一入口。负责：
 * 1. 构建带记忆的 system prompt
 * 2. 调用 LocalLlmEngine 进行本地推理
 * 3. 解析 LLM 响应为 AgentCommand
 * 4. 通过 CapabilityRegistry 路由到对应 Capability 执行
 *
 * @param context Application Context
 */
class AgentOrchestrator(private val context: Context) {

    private val tag = "PicMe:AgentOrchestrator"
    private val localLlmEngine = LocalLlmEngine(context)
    private val memoryManager = MemoryManager(context)
    private val capabilityRegistry = CapabilityRegistry()
    private val privacyGuard = PrivacyGuard()

    /**
     * 当前 Agent 模式
     */
    var agentMode: AiAgentMode = AiAgentMode.LOCAL
        private set

    /**
     * 当前使用的模型 ID
     */
    var currentModelId: String = "qwen3_0_6b"
        private set

    /**
     * 注册 Capability
     */
    fun registerCapability(capability: com.picme.domain.agent.capability.Capability) {
        capabilityRegistry.register(capability)
    }

    /**
     * 初始化配置
     *
     * @param mode Agent 模式
     * @param modelId 模型 ID
     * @param privacyLevel 隐私级别
     */
    fun configure(
        mode: AiAgentMode,
        modelId: String,
        privacyLevel: com.picme.domain.model.AiAgentPrivacyLevel
    ) {
        this.agentMode = mode
        this.currentModelId = modelId
        privacyGuard.updateConfig(privacyLevel, mode)
        Logger.i(tag, "Configured: mode=$mode, model=$modelId, privacy=$privacyLevel")
    }

    /**
     * 加载本地模型
     *
     * @param modelId 模型 ID，为空时使用当前配置的模型
     * @return 加载结果，失败时包含具体原因和引导信息
     */
    suspend fun loadModel(modelId: String? = null): Result<Unit> {
        val targetModel = modelId ?: currentModelId

        // 先检查模型是否已下载
        if (!localLlmEngine.isModelAvailable(targetModel, context)) {
            Logger.w(tag, "Model not downloaded: $targetModel")
            return Result.failure(
                LlmModelNotFoundException(
                    "模型未下载，请前往设置 → AI 模型管理下载 $targetModel"
                )
            )
        }

        return localLlmEngine.loadModel(targetModel).onSuccess {
            currentModelId = targetModel
        }
    }

    /**
     * 卸载模型，释放内存
     */
    fun unloadModel() {
        localLlmEngine.unload()
    }

    /**
     * 模型是否已加载
     */
    val isModelLoaded: Boolean
        get() = localLlmEngine.isLoaded

    /**
     * 处理用户输入
     *
     * @param input 用户自然语言输入
     * @param context 当前 Agent 上下文
     * @return Agent 执行结果
     */
    suspend fun processUserInput(
        input: String,
        agentContext: AgentContext
    ): Result<AgentAction> = withContext(Dispatchers.IO) {
        Logger.d(tag, "Processing input: $input, scene=${agentContext.scene}")

        // 1. 检查本地模型状态
        if (!localLlmEngine.isLoaded) {
            if (currentModelId.isNotBlank()) {
                val loadResult = loadModel(currentModelId)
                if (loadResult.isFailure) {
                    val error = loadResult.exceptionOrNull()
                    val message = if (error is LlmModelNotFoundException) {
                        error.message ?: "模型未下载"
                    } else {
                        "模型加载失败：${error?.message ?: "未知错误"}"
                    }
                    return@withContext Result.success(
                        AgentAction.Error(message)
                    )
                }
            } else {
                return@withContext Result.success(
                    AgentAction.TextReply("请在设置中下载本地模型以启用 AI Agent。")
                )
            }
        }

        // 2. 构建 system prompt（清除历史避免干扰小模型）
        memoryManager.clearHistory(agentContext.memorySessionId)
        val systemPrompt = buildSystemPrompt(agentContext)
        val messages = memoryManager.buildContextMessages(
            sessionId = agentContext.memorySessionId,
            systemPrompt = systemPrompt,
            userInput = input
        )

        // 3. 本地推理
        // ChatMessages API 在 Qwen3 模型上返回空，改用单 prompt 模式
        val prompt = buildPrompt(systemPrompt, input)
        val responseResult = localLlmEngine.generate(prompt, maxTokens = 512)

        responseResult.fold(
            onSuccess = { rawResponse ->
                // 过滤 Qwen3 的 <think> 标签及其内容
                val response = filterThinkTags(rawResponse)
                Logger.i(tag, "LLM raw response: $response")
                val command = parseLlmResponse(response, agentContext)
                Logger.i(tag, "Parsed command: ${command.javaClass.simpleName}")

                // 4. 保存对话历史
                val assistantResponse = when (command) {
                    is AgentCommand.TextReply -> command.message
                    else -> response
                }
                memoryManager.appendConversation(
                    sessionId = agentContext.memorySessionId,
                    userInput = input,
                    assistantResponse = assistantResponse
                )

                // 5. 路由到 Capability 执行
                capabilityRegistry.dispatch(command, agentContext)
            },
            onFailure = { error ->
                Logger.e(tag, "Local LLM inference failed", error)
                Result.success(
                    AgentAction.Error("本地模型推理失败：${error.message ?: "未知错误"}")
                )
            }
        )
    }

    /**
     * 清空当前场景的对话历史
     */
    suspend fun clearMemory(sessionId: String) {
        memoryManager.clearHistory(sessionId)
    }

    /**
     * 构建 system prompt
     */
    private fun buildSystemPrompt(agentContext: AgentContext): String {
        return buildString {
            appendLine("你是相机AI助手。根据用户输入，严格按以下规则输出:")
            appendLine()
            appendLine("规则1: 用户说'拍照'、'拍一张'、'拍照片' → 只输出: {\"action\":\"capture\"}")
            appendLine("规则2: 用户说'翻转摄像头' → 只输出: {\"action\":\"flip_camera\"}")
            appendLine("规则3: 用户说'开始录像' → 只输出: {\"action\":\"toggle_recording\"}")
            appendLine("规则4: 用户说'切换滤镜' → 只输出: {\"action\":\"switch_filter\",\"filter\":\"NAME\"}")
            appendLine("规则5: 用户说'调整美颜' → 只输出: {\"action\":\"adjust_beauty\",\"smoothing\":50}")
            appendLine("规则6: 用户说'你好'、'在吗'等聊天 → 只输出: {\"action\":\"text_reply\",\"message\":\"你好!\"}")
            appendLine()
            appendLine("重要: 控制相机时只输出JSON，不要加任何其他文字、解释或标点。聊天时也用JSON格式输出text_reply。")
            appendLine("不要输出<think>标签。不要输出思考过程。")
            appendLine()
            appendLine("当前状态: 滤镜=${agentContext.filterType.name}, 模式=${agentContext.captureMode.name}")
        }
    }

    /**
     * 过滤 Qwen3 模型的 <think> 标签及其内容
     *
     * Qwen3 模型在某些输入下会输出思考过程，格式为：
     * <think>...思考内容...</think>实际回复
     *
     * 本方法移除 <think>...</think> 及其内部内容，只保留实际回复。
     * 如果只有开始标签没有结束标签，尝试从标签后提取内容（JSON 通常在 think 标签后）。
     */
    private fun filterThinkTags(response: String): String {
        val thinkStart = response.indexOf("<think>")
        if (thinkStart == -1) return response.trim()

        val thinkEnd = response.indexOf("</think>", thinkStart)
        return if (thinkEnd != -1) {
            // 移除 <think>...</think> 及其内容
            (response.substring(0, thinkStart) + response.substring(thinkEnd + 8))
                .trim()
        } else {
            // 只有开始标签没有结束标签，尝试从标签后提取内容
            val afterTag = response.substring(thinkStart + 7).trim()
            val beforeTag = response.substring(0, thinkStart).trim()
            // 优先使用标签后的内容（通常 JSON 在 think 标签后）
            if (afterTag.contains("{")) afterTag else beforeTag
        }
    }

    /**
     * 构建完整 prompt（纯文本，让 MNN-LLM 自动应用 chat template）
     *
     * MNN-LLM 内部会调用 apply_chat_template() 添加角色标记，
     * 外部不需要手动添加 <|im_start|> 等标记。
     */
    private fun buildPrompt(systemPrompt: String, userInput: String): String {
        return "system:\n$systemPrompt\n\nuser:\n$userInput\n\nassistant:"
    }

    /**
     * 解析 LLM 响应为 AgentCommand
     *
     * 委托给 [AgentCommandParser] 以便在纯 JVM 单元测试中直接测试解析逻辑。
     */
    fun parseLlmResponse(response: String, context: AgentContext): AgentCommand {
        return AgentCommandParser.parseLlmResponse(response, context)
    }

    /**
     * 根据 action 字段解析为具体命令
     *
     * 委托给 [AgentCommandParser] 以便在纯 JVM 单元测试中直接测试解析逻辑。
     */
    fun parseCommandByAction(
        action: String,
        json: String,
        context: AgentContext,
        fallbackText: String
    ): AgentCommand {
        return AgentCommandParser.parseCommandByAction(action, json, context, fallbackText)
    }
}
