package com.picme.domain.usecase

import android.content.Context
import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.core.common.Logger
import com.picme.data.remote.kimi.KimiApiClient
import com.picme.data.remote.kimi.KimiChatRequest
import com.picme.data.remote.kimi.KimiMessage
import com.picme.domain.agent.AgentOrchestrator
import com.picme.domain.agent.capability.CameraCapability
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.AgentScene
import com.picme.domain.model.AiAgentCommand
import com.picme.domain.model.AiAgentMode
import com.picme.domain.model.AiAgentPrivacyLevel
import com.picme.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI Agent 核心用例（Facade）
 *
 * 向后兼容的入口类。内部委托给 [AgentOrchestrator]，保留原有接口不变。
 * 核心变更：删除自动远程 API 回退逻辑，默认 100% 本地执行。
 *
 * @param context Application Context
 * @param apiKey Moonshot/Tencent API Key，为空时禁用远程推理
 * @param model 远程模型 ID，默认 moonshot-v1-8k
 * @param baseUrl API Base URL，默认 Moonshot 官方地址
 * @param agentMode Agent 运行模式，默认 LOCAL
 * @param privacyLevel 隐私级别，默认 STRICT
 * @param localModelId 本地模型 ID，默认 qwen3_0_6b
 */
class AiAgentUseCase(
    context: Context,
    apiKey: String?,
    private val model: String = DEFAULT_MODEL,
    baseUrl: String? = null,
    agentMode: AiAgentMode = AiAgentMode.LOCAL,
    privacyLevel: AiAgentPrivacyLevel = AiAgentPrivacyLevel.STRICT,
    localModelId: String = "qwen3_0_6b"
) {

    private val tag = "PicMe:AiAgent"

    /**
     * 新的 Agent Runtime 编排器
     */
    private val orchestrator = AgentOrchestrator(context)

    /**
     * 远程 API 客户端（仅在 REMOTE 模式下使用）
     */
    private val remoteClient: KimiApiClient? = apiKey?.takeIf { it.isNotBlank() }?.let {
        KimiApiClient(
            apiKey = it,
            baseUrl = baseUrl ?: DEFAULT_BASE_URL,
            enableLogging = true
        )
    }

    /**
     * 当前 Agent 模式
     */
    val currentMode: AiAgentMode = agentMode

    /**
     * 当前配置的本地模型 ID
     */
    private var currentLocalModelId: String = localModelId

    init {
        orchestrator.configure(
            mode = agentMode,
            modelId = localModelId,
            privacyLevel = privacyLevel
        )
    }

    /**
     * 注册相机 Capability
     */
    fun registerCameraCapability(capability: CameraCapability) {
        orchestrator.registerCapability(capability)
    }

    /**
     * 本地模型是否已加载
     */
    val isLocalModelLoaded: Boolean
        get() = orchestrator.isModelLoaded

    /**
     * 加载本地模型
     *
     * @param modelId 模型 ID，为空时使用当前配置的模型。如果模型 ID 与当前加载的不同，会先卸载旧模型。
     */
    suspend fun loadLocalModel(modelId: String? = null): Result<Unit> {
        val targetModel = modelId ?: currentLocalModelId
        if (targetModel != currentLocalModelId && modelId != null) {
            currentLocalModelId = targetModel
            orchestrator.configure(
                mode = currentMode,
                modelId = targetModel,
                privacyLevel = AiAgentPrivacyLevel.STRICT
            )
        }
        return orchestrator.loadModel(targetModel)
    }

    /**
     * 发送用户指令到 LLM，返回解析后的命令
     *
     * 新策略：
     * 1. 本地模型可用 → 本地推理（默认）
     * 2. 本地模型不可用 → 提示下载模型
     * 3. REMOTE 显式模式 + API Key 配置 → 远程推理
     *
     * @param userInput 用户自然语言输入
     * @param currentState 当前相机状态快照，用于上下文感知
     */
    suspend fun processInput(
        userInput: String,
        currentState: CameraStateSnapshot
    ): Result<AiAgentCommand> = withContext(Dispatchers.IO) {
        // 构建 AgentContext
        val agentContext = AgentContext(
            scene = AgentScene.CAMERA,
            beautySettings = currentState.beautySettings,
            filterType = currentState.filterType,
            styleFilter = currentState.styleFilter,
            zoomRatio = currentState.zoomRatio,
            exposureCompensation = currentState.exposureCompensation,
            captureMode = currentState.captureMode,
            isRecording = currentState.isRecording,
            memorySessionId = "camera"
        )

        // 优先本地推理
        if (currentMode == AiAgentMode.LOCAL || currentMode == AiAgentMode.OFF) {
            val result = orchestrator.processUserInput(userInput, agentContext)
            return@withContext result.map { action ->
                mapAgentActionToLegacyCommand(action)
            }
        }

        // REMOTE 模式：尝试远程 API
        if (currentMode == AiAgentMode.REMOTE && remoteClient != null) {
            return@withContext callRemoteApi(userInput, currentState)
        }

        // 都不可用
        Result.success(
            AiAgentCommand.TextReply(
                "请在设置中下载本地模型或配置 API Key 以启用 AI Agent。"
            )
        )
    }

    /**
     * 清空对话记忆
     */
    suspend fun clearMemory() {
        orchestrator.clearMemory("camera")
    }

    /**
     * 远程 API 调用（仅在显式 REMOTE 模式下使用）
     */
    private suspend fun callRemoteApi(
        userInput: String,
        currentState: CameraStateSnapshot
    ): Result<AiAgentCommand> {
        try {
            val systemPrompt = buildSystemPrompt(currentState)
            val temperature = if (model.contains("k2.6")) 1.0 else 0.2
            val request = KimiChatRequest(
                model = model,
                messages = listOf(
                    KimiMessage(role = "system", content = systemPrompt),
                    KimiMessage(role = "user", content = userInput)
                ),
                temperature = temperature,
                maxTokens = 512,
                stream = false
            )

            Logger.d(tag, "Sending remote request: model=$model, input=$userInput")

            val response = remoteClient!!.service.chatCompletions(request)
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                Logger.e(tag, "API error: ${response.code()}, $errorBody")
                return Result.failure(
                    RuntimeException("Moonshot API error: ${response.code()}")
                )
            }

            val body = response.body()
                ?: return Result.failure(RuntimeException("Empty response body"))

            val content = body.choices.firstOrNull()?.message?.content?.trim()
                ?: return Result.failure(RuntimeException("No choices in response"))

            Logger.d(tag, "Remote LLM response: $content")

            val command = parseLlmResponse(content, currentState)
            return Result.success(command)
        } catch (exception: Exception) {
            Logger.e(tag, "Remote API call failed", exception)
            return Result.failure(exception)
        }
    }

    /**
     * 将新的 AgentAction 映射为旧的 AiAgentCommand（向后兼容）
     */
    private fun mapAgentActionToLegacyCommand(action: AgentAction): AiAgentCommand {
        return when (action) {
            is AgentAction.Success -> {
                when (val cmd = action.command) {
                    is AgentCommand.AdjustBeauty -> AiAgentCommand.AdjustBeauty(cmd.settings)
                    is AgentCommand.SwitchFilter -> AiAgentCommand.SwitchFilter(cmd.filterType)
                    is AgentCommand.SwitchStyle -> AiAgentCommand.SwitchStyle(cmd.styleFilter)
                    is AgentCommand.SwitchScene -> AiAgentCommand.SwitchScene(cmd.sceneName)
                    is AgentCommand.SwitchRatio -> AiAgentCommand.SwitchRatio(cmd.ratio)
                    is AgentCommand.AdjustExposure -> AiAgentCommand.AdjustExposure(cmd.exposure)
                    is AgentCommand.AdjustZoom -> AiAgentCommand.AdjustZoom(cmd.zoomRatio)
                    is AgentCommand.FlipCamera -> AiAgentCommand.FlipCamera
                    is AgentCommand.CapturePhoto -> AiAgentCommand.CapturePhoto
                    is AgentCommand.ToggleRecording -> AiAgentCommand.ToggleRecording
                    is AgentCommand.SwitchMode -> AiAgentCommand.SwitchMode(cmd.mode)
                    is AgentCommand.TextReply -> AiAgentCommand.TextReply(cmd.message)
                    else -> AiAgentCommand.TextReply("操作已执行")
                }
            }
            is AgentAction.TextReply -> AiAgentCommand.TextReply(action.message)
            is AgentAction.Error -> AiAgentCommand.TextReply("处理出错了：${action.message}")
        }
    }

    /**
     * 当前相机状态快照
     */
    data class CameraStateSnapshot(
        val beautySettings: BeautySettings = BeautySettings(),
        val filterType: FilterType = FilterType.NONE,
        val styleFilter: StyleFilter = StyleFilter.NONE,
        val zoomRatio: Float = 1f,
        val exposureCompensation: Int = 0,
        val captureMode: MediaType = MediaType.PHOTO,
        val isRecording: Boolean = false
    )

    private fun buildSystemPrompt(state: CameraStateSnapshot): String {
        return buildString {
            appendLine("你是PicMe相机的AI助手小觅。你必须用中文回复用户。")
            appendLine()
            appendLine("当前相机状态: 美颜=${state.beautySettings.enabled}, 磨皮=${state.beautySettings.smoothing.toInt()}, 美白=${state.beautySettings.whitening.toInt()}, 瘦脸=${state.beautySettings.slimFace.toInt()}, 大眼=${state.beautySettings.bigEyes.toInt()}, 唇色=${state.beautySettings.lipColor.toInt()}, 腮红=${state.beautySettings.blush.toInt()}, 眉毛=${state.beautySettings.eyebrow.toInt()}, 滤镜=${state.filterType.name}, 风格=${state.styleFilter.name}, 变焦=${state.zoomRatio}x, 曝光=${state.exposureCompensation}, 模式=${state.captureMode.name}")
            appendLine()
            appendLine("可用滤镜: 无, 徕卡经典, 徕卡鲜艳, 徕卡黑白, 胶片金, 胶片富士, 复古, 冷调, 暖调")
            appendLine("可用风格: 无, 卡通, 素描, 色调分离, 浮雕, 交叉线")
            appendLine("可用模式: 拍照, 录像, 人像, 专业, 文档")
            appendLine()
            appendLine("如果用户想控制相机，输出JSON指令:")
            appendLine("1. 调整美颜: {\"action\":\"adjust_beauty\",\"smoothing\":0-100,\"whitening\":0-100,\"slim_face\":-50~50,\"big_eyes\":0-100,\"lip_color\":0-100,\"blush\":0-100,\"eyebrow\":0-100}")
            appendLine("2. 切换滤镜: {\"action\":\"switch_filter\",\"filter\":\"NAME\"}")
            appendLine("3. 切换风格: {\"action\":\"switch_style\",\"style\":\"NAME\"}")
            appendLine("4. 切换场景: {\"action\":\"switch_scene\",\"scene\":\"night|moon|none\"}")
            appendLine("5. 切换比例: {\"action\":\"switch_ratio\",\"ratio\":\"4:3|16:9|full\"}")
            appendLine("6. 调整曝光: {\"action\":\"adjust_exposure\",\"exposure\":-2~2}")
            appendLine("7. 调整变焦: {\"action\":\"adjust_zoom\",\"zoom\":0.5~10.0}")
            appendLine("8. 翻转摄像头: {\"action\":\"flip_camera\"}")
            appendLine("9. 拍照: {\"action\":\"capture\"}")
            appendLine("10. 切换录像: {\"action\":\"toggle_recording\"}")
            appendLine("11. 切换模式: {\"action\":\"switch_mode\",\"mode\":\"PHOTO|VIDEO|PORTRAIT|PRO|DOCUMENT\"}")
            appendLine("12. 文本回复: {\"action\":\"text_reply\",\"message\":\"回复内容\"}")
            appendLine()
            appendLine("重要规则:")
            appendLine("- 如果用户只是聊天，直接友好地用中文回复，不要输出JSON")
            appendLine("- 如果用户想控制相机，只输出JSON，不要输出其他文字")
            appendLine("- 绝对不要输出<think>标签或思考过程")
            appendLine("- 所有回复必须使用中文")
            appendLine("- '自然妆'=磨皮20,美白15,瘦脸5,大眼5。'浓妆'=唇色80,腮红60,眉毛50。相对调整基于当前状态。")
            appendLine()
            appendLine("语音控制规则:")
            appendLine("- 用户可能通过语音与你交互，如果用户说'小觅'或'小觅助手'开头，表示要控制相机")
            appendLine("- 如果用户只是聊天（没有唤醒词），友好地用中文回复，不要输出JSON")
            appendLine("- 如果用户说了唤醒词+控制意图，只输出JSON指令")
            appendLine("- 支持相对调整：'美颜高一点' = 在当前基础上增加适量值")
            appendLine("- 支持组合指令：'小觅，把滤镜换成复古然后拍照' = 先 switch_filter 再 capture")
        }
    }

    private fun parseLlmResponse(content: String, state: CameraStateSnapshot): AiAgentCommand {
        var cleaned = content.trim()

        while (true) {
            val thinkStart = cleaned.indexOf("<think>")
            val thinkEnd = cleaned.indexOf("</think>")
            if (thinkStart >= 0 && thinkEnd > thinkStart) {
                cleaned = cleaned.removeRange(thinkStart, thinkEnd + "</think>".length).trim()
            } else {
                break
            }
        }

        val orphanThinkStart = cleaned.indexOf("<think>")
        if (orphanThinkStart >= 0) {
            cleaned = cleaned.substring(0, orphanThinkStart).trim()
        }

        cleaned = cleaned.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        Logger.d(tag, "Cleaned response: '$cleaned'")

        val hasJsonAction = cleaned.contains("\"action\"")
        if (!hasJsonAction) {
            Logger.d(tag, "No JSON action found, treating as free chat")
            return AiAgentCommand.TextReply(cleaned.ifBlank { "你好，我是小觅，有什么可以帮你的吗？" })
        }

        val jsonStart = cleaned.indexOf('{')
        val jsonEnd = cleaned.lastIndexOf('}')
        val json = if (jsonStart >= 0 && jsonEnd > jsonStart) {
            cleaned.substring(jsonStart, jsonEnd + 1)
        } else {
            cleaned
        }

        return try {
            val action = extractJsonField(json, "action") ?: "text_reply"
            when (action) {
                "adjust_beauty" -> {
                    val smoothing = extractJsonFloat(json, "smoothing") ?: state.beautySettings.smoothing
                    val whitening = extractJsonFloat(json, "whitening") ?: state.beautySettings.whitening
                    val slimFace = extractJsonFloat(json, "slim_face") ?: state.beautySettings.slimFace
                    val bigEyes = extractJsonFloat(json, "big_eyes") ?: state.beautySettings.bigEyes
                    val lipColor = extractJsonFloat(json, "lip_color") ?: state.beautySettings.lipColor
                    val blush = extractJsonFloat(json, "blush") ?: state.beautySettings.blush
                    val eyebrow = extractJsonFloat(json, "eyebrow") ?: state.beautySettings.eyebrow
                    AiAgentCommand.AdjustBeauty(
                        state.beautySettings.copy(
                            enabled = true,
                            smoothing = smoothing,
                            whitening = whitening,
                            slimFace = slimFace,
                            bigEyes = bigEyes,
                            lipColor = lipColor,
                            blush = blush,
                            eyebrow = eyebrow
                        )
                    )
                }
                "switch_filter" -> {
                    val filterName = extractJsonField(json, "filter") ?: "NONE"
                    val filterType = runCatching { FilterType.valueOf(filterName) }.getOrDefault(FilterType.NONE)
                    AiAgentCommand.SwitchFilter(filterType)
                }
                "switch_style" -> {
                    val styleName = extractJsonField(json, "style") ?: "NONE"
                    val styleFilter = runCatching { StyleFilter.valueOf(styleName) }.getOrDefault(StyleFilter.NONE)
                    AiAgentCommand.SwitchStyle(styleFilter)
                }
                "switch_scene" -> {
                    val scene = extractJsonField(json, "scene") ?: "none"
                    AiAgentCommand.SwitchScene(scene)
                }
                "switch_ratio" -> {
                    val ratio = extractJsonField(json, "ratio") ?: "full"
                    AiAgentCommand.SwitchRatio(ratio)
                }
                "adjust_exposure" -> {
                    val exposure = extractJsonInt(json, "exposure") ?: 0
                    AiAgentCommand.AdjustExposure(exposure.coerceIn(-2, 2))
                }
                "adjust_zoom" -> {
                    val zoom = extractJsonFloat(json, "zoom") ?: 1f
                    val minZoom = 0.5f
                    AiAgentCommand.AdjustZoom(zoom.coerceAtLeast(minZoom))
                }
                "flip_camera" -> AiAgentCommand.FlipCamera
                "capture", "photo" -> AiAgentCommand.CapturePhoto
                "toggle_recording" -> AiAgentCommand.ToggleRecording
                "switch_mode" -> {
                    val modeName = extractJsonField(json, "mode") ?: "PHOTO"
                    val mode = runCatching { MediaType.valueOf(modeName) }.getOrDefault(MediaType.PHOTO)
                    AiAgentCommand.SwitchMode(mode)
                }
                else -> {
                    val message = extractJsonField(json, "message")
                        ?: cleaned.ifBlank { "收到，有什么其他需要帮忙的吗？" }
                    AiAgentCommand.TextReply(message)
                }
            }
        } catch (exception: Exception) {
            Logger.w(tag, "Failed to parse LLM response, fallback to text: $json", exception)
            AiAgentCommand.TextReply(cleaned.ifBlank { "收到你的消息了，但没理解具体意图，请再描述一下~" })
        }
    }

    private fun extractJsonField(json: String, key: String): String? {
        val regex = """"$key"\s*:\s*"([^"]*)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonFloat(json: String, key: String): Float? {
        val regex = """"$key"\s*:\s*(-?\d+\.?\d*)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val regex = """"$key"\s*:\s*(-?\d+)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    companion object {
        private const val DEFAULT_MODEL = "moonshot-v1-8k"
        private const val DEFAULT_BASE_URL = "https://api.moonshot.cn/v1/"
    }
}
