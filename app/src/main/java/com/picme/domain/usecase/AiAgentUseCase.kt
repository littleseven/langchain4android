package com.picme.domain.usecase


import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.beauty.api.llm.MnnLlmClient
import com.picme.core.common.Logger
import com.picme.data.remote.kimi.KimiApiClient
import com.picme.data.remote.kimi.KimiChatRequest
import com.picme.data.remote.kimi.KimiMessage
import com.picme.domain.model.AiAgentCommand
import com.picme.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI Agent 核心用例
 *
 * 负责：
 * 1. 构建系统 Prompt，将当前相机状态注入上下文
 * 2. 优先使用本地 MNN-LLM 模型推理，失败时回退到远程 Kimi API
 * 3. 将 LLM 的 JSON 响应映射为 [AiAgentCommand]
 *
 * @param apiKey Moonshot/Tencent API Key，为空时仅使用本地模型
 * @param model 模型 ID，默认 moonshot-v1-8k
 * @param baseUrl API Base URL，默认 Moonshot 官方地址
 * @param localLlmClient 本地 MNN-LLM 客户端，为 null 时禁用本地推理
 */
class AiAgentUseCase(
    apiKey: String?,
    private val model: String = DEFAULT_MODEL,
    baseUrl: String? = null,
    private val localLlmClient: MnnLlmClient? = null
) {

    private val client: KimiApiClient? = apiKey?.takeIf { it.isNotBlank() }?.let {
        KimiApiClient(
            apiKey = it,
            baseUrl = baseUrl ?: DEFAULT_BASE_URL,
            enableLogging = true
        )
    }

    /**
     * 本地模型是否已加载
     */
    val isLocalModelLoaded: Boolean
        get() = localLlmClient?.isLoaded == true

    /**
     * 发送用户指令到 LLM，返回解析后的命令
     *
     * 策略：优先本地模型 → 远程 API → 提示配置
     *
     * @param userInput 用户自然语言输入
     * @param currentState 当前相机状态快照，用于上下文感知
     */
    suspend fun processInput(
        userInput: String,
        currentState: CameraStateSnapshot
    ): Result<AiAgentCommand> = withContext(Dispatchers.IO) {
        val systemPrompt = buildSystemPrompt(currentState)

        // 1. 优先尝试本地模型
        if (localLlmClient != null && localLlmClient.isLoaded) {
            try {
                Logger.d("PicMe:AiAgent", "Using local MNN-LLM model")
                val content = localLlmClient.generateWithSystem(
                    systemPrompt = systemPrompt,
                    userPrompt = userInput,
                    maxNewTokens = 128
                )
                if (content.isNotBlank()) {
                    Logger.d("PicMe:AiAgent", "Local LLM response: $content")
                    val command = parseLlmResponse(content, currentState)
                    return@withContext Result.success(command)
                }
            } catch (exception: Exception) {
                Logger.w("PicMe:AiAgent", "Local LLM failed, fallback to remote", exception)
            }
        }

        // 2. 本地不可用，尝试远程 API
        if (client != null) {
            return@withContext callRemoteApi(userInput, systemPrompt, currentState)
        }

        // 3. 都不可用，提示配置
        Result.success(
            AiAgentCommand.TextReply(
                "Please configure your Moonshot API Key in Settings to enable AI Agent mode."
            )
        )
    }

    private suspend fun callRemoteApi(
        userInput: String,
        systemPrompt: String,
        currentState: CameraStateSnapshot
    ): Result<AiAgentCommand> {
        try {
            val temperature = if (model.contains("k2.6")) 1.0 else 0.2
            val request = KimiChatRequest(
                model = model,
                messages = listOf(
                    KimiMessage(role = "system", content = systemPrompt),
                    KimiMessage(role = "user", content = userInput)
                ),
                temperature = temperature,
                maxTokens = 256,
                stream = false
            )

            Logger.d("PicMe:AiAgent", "Sending remote request: model=$model, input=$userInput")

            val response = client!!.service.chatCompletions(request)
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                Logger.e("PicMe:AiAgent", "API error: ${response.code()}, $errorBody")
                return Result.failure(
                    RuntimeException("Moonshot API error: ${response.code()}")
                )
            }

            val body = response.body()
                ?: return Result.failure(RuntimeException("Empty response body"))

            val content = body.choices.firstOrNull()?.message?.content?.trim()
                ?: return Result.failure(RuntimeException("No choices in response"))

            Logger.d("PicMe:AiAgent", "Remote LLM response: $content")

            val command = parseLlmResponse(content, currentState)
            return Result.success(command)
        } catch (exception: Exception) {
            Logger.e("PicMe:AiAgent", "Remote API call failed", exception)
            return Result.failure(exception)
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
            appendLine("你是PicMe相机的AI助手，用中文回复用户。你可以自由聊天，也可以帮用户控制相机。")
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
            appendLine("规则: 如果用户只是聊天，直接友好地用中文回复。如果用户想控制相机，输出ONLY JSON，不要markdown。'自然妆'=磨皮20,美白15,瘦脸5,大眼5。'浓妆'=唇色80,腮红60,眉毛50。相对调整基于当前状态。")
        }
    }

    private fun parseLlmResponse(content: String, state: CameraStateSnapshot): AiAgentCommand {

        // 1. 移除 <think>...</think> 思考标签及其内容（支持多个标签）
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

        // 如果还有残留的 <think> 开头（没有闭合标签），移除从 <think> 开始到末尾的所有内容
        val orphanThinkStart = cleaned.indexOf("<think>")
        if (orphanThinkStart >= 0) {
            cleaned = cleaned.substring(0, orphanThinkStart).trim()
        }

        // 2. 移除 markdown 代码块标记
        cleaned = cleaned.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        Logger.d("PicMe:AiAgent", "Cleaned response: '$cleaned'")

        // 3. 检查是否包含 JSON action 字段
        val hasJsonAction = cleaned.contains("\"action\"")

        // 4. 如果不包含 JSON 指令，直接作为自由聊天文本返回
        if (!hasJsonAction) {
            Logger.d("PicMe:AiAgent", "No JSON action found, treating as free chat")
            return AiAgentCommand.TextReply(cleaned.ifBlank { "你好，我是 PicMe 相机的 AI 助手，有什么可以帮你的吗？" })
        }


        // 5. 提取 JSON 部分（可能混有自然语言，取第一个 { 到最后一个 }）
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
                "capture" -> AiAgentCommand.CapturePhoto
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
            Logger.w("PicMe:AiAgent", "Failed to parse LLM response, fallback to text: $json", exception)
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
