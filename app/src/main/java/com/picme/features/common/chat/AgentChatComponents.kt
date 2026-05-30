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
                        val responseText = when (action) {
                            is AgentAction.Success -> {
                                val commandName = action.command::class.simpleName ?: "操作"
                                "已执行: $commandName"
                            }
                            is AgentAction.TextReply -> action.message
                            is AgentAction.Error -> "抱歉，${action.message}"
                        }
                        messages.value = messages.value + AgentMessage.AgentText(content = responseText)
                    },
                    onFailure = { error ->
                        messages.value = messages.value + AgentMessage.AgentText(
                            content = "处理失败：${error.message ?: "未知错误"}"
                        )
                    }
                )
            }
        },
        onCommand = {
            // 命令已通过 AgentOrchestrator 执行
        },
        modifier = Modifier
    )
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

    // AiAgentUseCase
    val aiAgentUseCase = remember {
        AiAgentUseCase(
            context = context,
            agentMode = AiAgentMode.LOCAL,
            localModelId = "qwen3_0_6b"
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
