package com.picme.features.common.chat

import android.content.Context
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.picme.core.common.Logger
import com.picme.data.preferences.UserPreferencesRepository
import com.picme.domain.agent.AgentOrchestrator
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.AgentScene
import com.picme.domain.agent.model.PageContext
import com.picme.domain.model.AiAgentCommand
import com.picme.domain.model.AiAgentMode
import com.picme.domain.usecase.AiAgentUseCase
import com.picme.features.camera.voice.AsrEngine
import com.picme.features.camera.voice.MnnAsrClient
import com.picme.features.camera.voice.SherpaMnnAsrEngine
import com.picme.features.camera.voice.SystemAsrEngine
import com.picme.features.camera.voice.VoiceCommandCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// 1. ASR 引擎初始化（公共逻辑）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 根据用户设置初始化 ASR 引擎，支持三级降级策略：
 * Sherpa-MNN ASR → MNN ASR → System ASR
 *
 * @param context Android Context
 * @param localAsrModel 本地 ASR 模型 ID（从 UserPreferencesRepository 读取）
 * @param logTag 日志标签前缀
 */
fun createAsrEngine(
    context: Context,
    localAsrModel: String,
    logTag: String
): AsrEngine {
    if (localAsrModel.isBlank()) {
        Logger.d(logTag, "No local ASR model configured, using system ASR")
        return SystemAsrEngine(context)
    }

    val modelDir = context.filesDir.resolve("llm_models/$localAsrModel")
    val modelDirPath = modelDir.absolutePath

    val isModelReady = if (localAsrModel.contains("zipformer", ignoreCase = true)) {
        modelDir.exists() && modelDir.isDirectory &&
            modelDir.walkTopDown().any { it.name.endsWith(".mnn") } &&
            java.io.File(modelDir, "tokens.txt").exists()
    } else {
        modelDir.exists() && modelDir.isDirectory
    }

    if (!isModelReady) {
        Logger.w(logTag, "ASR model not ready: $localAsrModel")
        return SystemAsrEngine(context)
    }

    return if (localAsrModel.contains("zipformer", ignoreCase = true)) {
        val sherpaAsr = SherpaMnnAsrEngine(context, modelDirPath)
        if (sherpaAsr.isAvailable()) {
            Logger.i(logTag, "Using Sherpa-MNN ASR engine")
            sherpaAsr
        } else {
            Logger.w(logTag, "Sherpa-MNN ASR init failed, fallback to system ASR")
            SystemAsrEngine(context)
        }
    } else {
        val mnnAsr = MnnAsrClient(context, localAsrModel)
        if (mnnAsr.isAvailable()) {
            Logger.i(logTag, "Using MNN ASR engine")
            mnnAsr
        } else {
            Logger.w(logTag, "MNN ASR not available, fallback to system ASR")
            SystemAsrEngine(context)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. VoiceCommandCoordinator 初始化（公共逻辑）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 创建并配置 VoiceCommandCoordinator
 *
 * @param asrEngine ASR 引擎实例
 * @param aiAgentUseCase AI Agent 用例
 * @param scope 协程作用域
 * @param onCommand 命令回调
 * @param onTranscript 识别文本回调（可选）
 * @param onAgentResponse Agent 响应回调（可选）
 */
fun createVoiceCommandCoordinator(
    asrEngine: AsrEngine,
    aiAgentUseCase: AiAgentUseCase,
    scope: CoroutineScope,
    onCommand: (AiAgentCommand) -> Unit,
    onTranscript: ((String) -> Unit)? = null,
    onAgentResponse: ((Result<AiAgentCommand>) -> Unit)? = null
): VoiceCommandCoordinator {
    return VoiceCommandCoordinator(
        asrEngine = asrEngine,
        aiAgentUseCase = aiAgentUseCase,
        onCommand = onCommand,
        scope = scope,
        onTranscript = onTranscript,
        onAgentResponse = onAgentResponse
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Agent Chat Panel（浮动按钮 + AiChatScreen + AgentOrchestrator）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 统一的 Agent Chat Panel 组件
 *
 * 封装以下重复逻辑：
 * - 右下角浮动按钮（KeyboardVoice 图标）
 * - AiChatScreen 对话框
 * - AgentOrchestrator 消息处理
 * - 消息状态管理
 *
 * @param pageContext 页面上下文
 * @param agentScene Agent 场景枚举
 * @param memorySessionId 记忆会话 ID
 * @param voiceCoordinator 语音协调器（可选）
 * @param modifier 修饰符（通常用于定位，如 Modifier.align(Alignment.BottomEnd)）
 */
@Composable
fun AgentChatPanel(
    pageContext: PageContext,
    agentScene: AgentScene,
    memorySessionId: String,
    voiceCoordinator: VoiceCommandCoordinator? = null,
    onCommand: ((com.picme.domain.model.AiAgentCommand) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isVisible by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    val messages = remember { mutableStateOf<List<AgentMessage>>(emptyList()) }

    // 浮动按钮触发 - 右下角
    if (!isVisible) {
        FloatingActionButton(
            onClick = { isVisible = true },
            modifier = modifier
                .padding(end = 16.dp, bottom = 16.dp)
                .navigationBarsPadding(),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardVoice,
                contentDescription = "AI Agent",
                tint = Color.White
            )
        }
    }

    // AiChatScreen + AgentOrchestrator 消息处理
    val context = LocalContext.current
    val orchestrator = remember { AgentOrchestrator.getInstance(context.applicationContext) }
    val scope = rememberCoroutineScope()

    AiChatScreen(
        visible = isVisible,
        messages = messages.value,
        isProcessing = isProcessing,
        onVisibleChange = { isVisible = it },
        voiceCoordinator = voiceCoordinator,
        onSendMessage = { input ->
            messages.value = messages.value + AgentMessage.UserText(content = input)
            isProcessing = true

            scope.launch {
                val agentContext = AgentContext(
                    scene = agentScene,
                    memorySessionId = memorySessionId
                )
                val result = orchestrator.processUserInput(
                    input = input,
                    agentContext = agentContext,
                    pageContext = pageContext
                )
                isProcessing = false
                result.fold(
                    onSuccess = { action ->
                        when (action) {
                            is AgentAction.Success -> {
                                // 如果调用者需要感知命令，通过 onCommand 回传
                                onCommand?.let { cmdCallback ->
                                    mapAgentActionToAiAgentCommand(action)?.let { cmdCallback(it) }
                                }
                                val executionMessages = agentActionToExecutionMessages(action)
                                messages.value = messages.value + executionMessages
                            }
                            is AgentAction.TextReply -> {
                                messages.value = messages.value + AgentMessage.AgentText(content = action.message)
                            }
                            is AgentAction.Error -> {
                                messages.value = messages.value + AgentMessage.AgentText(
                                    content = "抱歉，${action.message}"
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        messages.value = messages.value + AgentMessage.AgentText(
                            content = "处理失败：${error.message ?: "未知错误"}"
                        )
                    }
                )
            }
        },
        onCommand = { cmd ->
            onCommand?.invoke(cmd)
        },
        modifier = Modifier
    )
}

/**
 * 将 AgentAction 映射为 AiAgentCommand（用于向旧版 UI 回调回传命令）
 */
private fun mapAgentActionToAiAgentCommand(action: AgentAction.Success): com.picme.domain.model.AiAgentCommand? {
    return when (val cmd = action.command) {
        is com.picme.domain.agent.model.AgentCommand.AdjustBeauty ->
            com.picme.domain.model.AiAgentCommand.AdjustBeauty(cmd.settings)
        is com.picme.domain.agent.model.AgentCommand.SwitchFilter ->
            com.picme.domain.model.AiAgentCommand.SwitchFilter(cmd.filterType)
        is com.picme.domain.agent.model.AgentCommand.SwitchStyle ->
            com.picme.domain.model.AiAgentCommand.SwitchStyle(cmd.styleFilter)
        is com.picme.domain.agent.model.AgentCommand.SwitchScene ->
            com.picme.domain.model.AiAgentCommand.SwitchScene(cmd.sceneName)
        is com.picme.domain.agent.model.AgentCommand.SwitchRatio ->
            com.picme.domain.model.AiAgentCommand.SwitchRatio(cmd.ratio)
        is com.picme.domain.agent.model.AgentCommand.AdjustExposure ->
            com.picme.domain.model.AiAgentCommand.AdjustExposure(cmd.exposure)
        is com.picme.domain.agent.model.AgentCommand.AdjustZoom ->
            com.picme.domain.model.AiAgentCommand.AdjustZoom(cmd.zoomRatio)
        is com.picme.domain.agent.model.AgentCommand.FlipCamera ->
            com.picme.domain.model.AiAgentCommand.FlipCamera
        is com.picme.domain.agent.model.AgentCommand.CapturePhoto ->
            com.picme.domain.model.AiAgentCommand.CapturePhoto
        is com.picme.domain.agent.model.AgentCommand.ToggleRecording ->
            com.picme.domain.model.AiAgentCommand.ToggleRecording
        is com.picme.domain.agent.model.AgentCommand.SwitchMode ->
            com.picme.domain.model.AiAgentCommand.SwitchMode(cmd.mode)
        is com.picme.domain.agent.model.AgentCommand.NavigateTo ->
            com.picme.domain.model.AiAgentCommand.NavigateTo(cmd.destination)
        is com.picme.domain.agent.model.AgentCommand.GoBack ->
            com.picme.domain.model.AiAgentCommand.GoBack
        else -> null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AgentAction → CommandExecution 消息转换
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 将 AgentAction.Success 转换为 CommandExecution 消息列表
 *
 * 支持 BatchExecute 展开为多条消息，单命令转换为单条消息。
 */
private fun agentActionToExecutionMessages(action: AgentAction.Success): List<AgentMessage> {
    val cmd = action.command
    return when (cmd) {
        is com.picme.domain.agent.model.AgentCommand.BatchExecute -> {
            val total = cmd.commands.size
            cmd.commands.mapIndexed { index, subCmd ->
                AgentMessage.CommandExecution(
                    commandName = getAgentCommandDisplayName(subCmd),
                    status = AgentMessage.CommandExecution.Status.SUCCESS,
                    detail = getAgentCommandDetail(subCmd),
                    index = index + 1,
                    total = total
                )
            }
        }
        else -> listOf(
            AgentMessage.CommandExecution(
                commandName = getAgentCommandDisplayName(cmd),
                status = AgentMessage.CommandExecution.Status.SUCCESS,
                detail = getAgentCommandDetail(cmd),
                index = 0,
                total = 1
            )
        )
    }
}

private fun getAgentCommandDisplayName(command: com.picme.domain.agent.model.AgentCommand): String =
    when (command) {
        is com.picme.domain.agent.model.AgentCommand.AdjustBeauty -> "调整美颜"
        is com.picme.domain.agent.model.AgentCommand.SwitchFilter -> "切换滤镜"
        is com.picme.domain.agent.model.AgentCommand.SwitchStyle -> "切换风格"
        is com.picme.domain.agent.model.AgentCommand.SwitchScene -> "切换场景"
        is com.picme.domain.agent.model.AgentCommand.SwitchRatio -> "切换画幅"
        is com.picme.domain.agent.model.AgentCommand.AdjustExposure -> "调整曝光"
        is com.picme.domain.agent.model.AgentCommand.AdjustZoom -> "调整变焦"
        is com.picme.domain.agent.model.AgentCommand.FlipCamera -> "翻转摄像头"
        is com.picme.domain.agent.model.AgentCommand.CapturePhoto -> "拍照"
        is com.picme.domain.agent.model.AgentCommand.ToggleRecording -> "切换录像"
        is com.picme.domain.agent.model.AgentCommand.SwitchMode -> "切换模式"
        is com.picme.domain.agent.model.AgentCommand.NavigateTo -> "页面跳转"
        is com.picme.domain.agent.model.AgentCommand.GoBack -> "返回"
        is com.picme.domain.agent.model.AgentCommand.BatchExecute -> "批量执行"
        is com.picme.domain.agent.model.AgentCommand.TextReply -> "文本回复"
        is com.picme.domain.agent.model.AgentCommand.ExecutePlan -> "执行计划"
        is com.picme.domain.agent.model.AgentCommand.ChangeTheme -> "切换主题"
        is com.picme.domain.agent.model.AgentCommand.ChangeLanguage -> "切换语言"
        is com.picme.domain.agent.model.AgentCommand.DownloadModel -> "下载模型"
        is com.picme.domain.agent.model.AgentCommand.SwitchFaceEngine -> "切换引擎"
        is com.picme.domain.agent.model.AgentCommand.ToggleSetting -> "切换设置"
        is com.picme.domain.agent.model.AgentCommand.ViewMedia -> "查看照片"
        is com.picme.domain.agent.model.AgentCommand.DeleteMedia -> "删除照片"
        is com.picme.domain.agent.model.AgentCommand.ShareMedia -> "分享照片"
        is com.picme.domain.agent.model.AgentCommand.SelectMedia -> "选择照片"
        is com.picme.domain.agent.model.AgentCommand.SearchMedia -> "搜索照片"
        is com.picme.domain.agent.model.AgentCommand.SwitchViewMode -> "切换视图"
        is com.picme.domain.agent.model.AgentCommand.FavoriteMedia -> "收藏照片"
        is com.picme.domain.agent.model.AgentCommand.Unknown -> "未知命令"
        is com.picme.domain.agent.model.AgentCommand.Error -> "执行错误"
    }

private fun getAgentCommandDetail(command: com.picme.domain.agent.model.AgentCommand): String =
    when (command) {
        is com.picme.domain.agent.model.AgentCommand.AdjustBeauty -> buildString {
            val s = command.settings
            val parts = mutableListOf<String>()
            if (s.smoothing > 0) parts.add("磨皮 ${s.smoothing.toInt()}%")
            if (s.whitening > 0) parts.add("美白 ${s.whitening.toInt()}%")
            if (s.slimFace != 0f) parts.add("瘦脸 ${s.slimFace.toInt()}%")
            if (s.bigEyes > 0) parts.add("大眼 ${s.bigEyes.toInt()}%")
            if (parts.isEmpty()) append("默认参数") else append(parts.joinToString(", "))
        }
        is com.picme.domain.agent.model.AgentCommand.SwitchFilter -> "滤镜: ${command.filterType.name}"
        is com.picme.domain.agent.model.AgentCommand.SwitchStyle -> "风格: ${command.styleFilter.name}"
        is com.picme.domain.agent.model.AgentCommand.SwitchScene -> "场景: ${command.sceneName}"
        is com.picme.domain.agent.model.AgentCommand.SwitchRatio -> "比例: ${command.ratio}"
        is com.picme.domain.agent.model.AgentCommand.AdjustExposure -> "曝光: ${command.exposure}"
        is com.picme.domain.agent.model.AgentCommand.AdjustZoom -> "变焦: ${command.zoomRatio}x"
        is com.picme.domain.agent.model.AgentCommand.NavigateTo -> "目标: ${command.destination}"
        is com.picme.domain.agent.model.AgentCommand.ChangeTheme -> "主题: ${command.theme}"
        is com.picme.domain.agent.model.AgentCommand.ChangeLanguage -> "语言: ${command.language}"
        is com.picme.domain.agent.model.AgentCommand.DownloadModel -> "模型: ${command.modelId}"
        is com.picme.domain.agent.model.AgentCommand.SwitchFaceEngine -> "引擎: ${command.engine}"
        is com.picme.domain.agent.model.AgentCommand.ToggleSetting -> "${command.settingKey}: ${if (command.enabled) "开启" else "关闭"}"
        is com.picme.domain.agent.model.AgentCommand.SearchMedia -> "关键词: ${command.query}"
        is com.picme.domain.agent.model.AgentCommand.ExecutePlan -> "计划: ${command.plan.description}"
        else -> ""
    }

// ─────────────────────────────────────────────────────────────────────────────
// 4. 页面级初始化辅助（ASR + VoiceCoordinator 一站式配置）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 页面级 Agent Chat 初始化数据类
 *
 * 包含 ASR 引擎、VoiceCommandCoordinator 和 AiAgentUseCase 的完整配置
 */
data class AgentChatConfig(
    val asrEngine: AsrEngine,
    val aiAgentUseCase: AiAgentUseCase,
    val voiceCoordinator: VoiceCommandCoordinator
)

/**
 * 记住页面级 Agent Chat 配置（ASR + VoiceCoordinator + AiAgentUseCase）
 *
 * 使用示例：
 * ```
 * val config = rememberAgentChatConfig(
 *     context = context,
 *     logTag = "PicMe:Gallery",
 *     onCommand = { command -> /* 处理命令 */ },
 *     onTranscript = { transcript -> /* 处理识别文本 */ },
 *     onAgentResponse = { result -> /* 处理 Agent 响应 */ }
 * )
 * DisposableEffect(Unit) {
 *     onDispose { config.voiceCoordinator.release() }
 * }
 * ```
 */
@Composable
fun rememberAgentChatConfig(
    context: Context,
    logTag: String,
    onCommand: (AiAgentCommand) -> Unit,
    onTranscript: ((String) -> Unit)? = null,
    onAgentResponse: ((Result<AiAgentCommand>) -> Unit)? = null
): AgentChatConfig {
    val scope = rememberCoroutineScope()

    // 读取用户 ASR 模型设置
    val settingsRepository = remember { UserPreferencesRepository(context) }
    val localAsrModel by settingsRepository.localAsrModelFlow.collectAsState(initial = "")

    // ASR 引擎
    val asrEngine = remember(context, localAsrModel) {
        createAsrEngine(context, localAsrModel, logTag)
    }

    // 读取远程模型配置
    val aiAgentRemoteModelConfigs by settingsRepository.aiAgentRemoteModelConfigsFlow.collectAsState(initial = "")
    val aiAgentSelectedRemoteModel by settingsRepository.aiAgentSelectedRemoteModelFlow.collectAsState(initial = "deepseek-v4-flash")

    // 解析远程模型配置
    val remoteConfig = remember(aiAgentRemoteModelConfigs, aiAgentSelectedRemoteModel) {
        val configs = if (aiAgentRemoteModelConfigs.isNotBlank()) {
            com.picme.domain.model.RemoteModelConfigs.fromJson(aiAgentRemoteModelConfigs)
        } else {
            com.picme.domain.model.RemoteModelConfigs()
        }
        configs.getConfigByModelId(aiAgentSelectedRemoteModel)
            ?: com.picme.domain.model.RemoteModelConfig.defaultConfig(aiAgentSelectedRemoteModel)
    }

    // 读取腾讯云 SCF Gateway Token
    val cloudflareGatewayToken by settingsRepository.cloudflareGatewayTokenFlow.collectAsState(initial = "")

    // AiAgentUseCase
    val aiAgentUseCase = remember(remoteConfig, cloudflareGatewayToken) {
        AiAgentUseCase(
            context = context,
            agentMode = AiAgentMode.LOCAL,
            localModelId = "qwen3_1_7b",
            codingApiKey = remoteConfig.apiKey.takeIf { it.isNotBlank() },
            codingModel = remoteConfig.modelId,
            codingBaseUrl = remoteConfig.baseUrl.takeIf { it.isNotBlank() },
            gatewayToken = cloudflareGatewayToken.takeIf { it.isNotBlank() }
        )
    }

    // VoiceCommandCoordinator
    val voiceCoordinator = remember(asrEngine, aiAgentUseCase) {
        createVoiceCommandCoordinator(
            asrEngine = asrEngine,
            aiAgentUseCase = aiAgentUseCase,
            scope = scope,
            onCommand = onCommand,
            onTranscript = onTranscript,
            onAgentResponse = onAgentResponse
        )
    }

    return AgentChatConfig(
        asrEngine = asrEngine,
        aiAgentUseCase = aiAgentUseCase,
        voiceCoordinator = voiceCoordinator
    )
}
