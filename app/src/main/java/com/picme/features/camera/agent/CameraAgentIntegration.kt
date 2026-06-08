package com.picme.features.camera.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.picme.agent.core.facade.AgentOrchestrator
import com.picme.agent.core.api.context.AgentContext
import com.picme.agent.core.api.policy.AiAgentMode
import com.picme.agent.core.api.policy.AiAgentPrivacyLevel
import com.picme.core.common.Logger
import com.picme.domain.usecase.AiAgentUseCase
import com.picme.features.agent.GlobalAgentPanel
import com.picme.features.agent.rememberGlobalAgentPanelState

private const val TAG = "CameraAgent"

/**
 * CameraScreen 的 Agent 集成
 *
 * 将 AgentOrchestrator 与现有 CameraScreen 集成
 * 提供向后兼容的桥梁
 */
class CameraAgentIntegration(
    val orchestrator: AgentOrchestrator,
    private val useCase: AiAgentUseCase
) {
    /**
     * 进入 Camera 场景
     *
     * 注意：Scene 切换由 MainActivity 统一管理，此处不再重复设置
     */
    fun enterCameraScene() {
        Logger.i(TAG, "Entering CAMERA scene (scene managed by MainActivity)")
        // Scene 切换由 MainActivity 的 DisposableEffect 统一管理
    }

    /**
     * 离开 Camera 场景
     *
     * 注意：Scene 切换由 MainActivity 统一管理，此处不再重复设置
     */
    fun leaveCameraScene() {
        Logger.i(TAG, "Exiting CAMERA scene (scene managed by MainActivity)")
        // Scene 切换由 MainActivity 的 DisposableEffect 统一管理
    }
}

/**
 * CameraScreen 的 Agent Panel 组件
 *
 * 使用新的 GlobalAgentPanel，但保持与现有 UI 的兼容性
 */
@Composable
fun CameraAgentPanelV2(
    integration: CameraAgentIntegration,
    agentContext: AgentContext,
    modifier: Modifier = Modifier
) {
    val panelState = rememberGlobalAgentPanelState()

    // 进入/离开场景的生命周期管理
    DisposableEffect(Unit) {
        integration.enterCameraScene()
        onDispose {
            integration.leaveCameraScene()
        }
    }

    GlobalAgentPanel(
        state = panelState,
        orchestrator = integration.orchestrator,
        agentContext = agentContext,
        pageContext = null, // Camera 页面暂无特定上下文
        modifier = modifier
    )
}

/**
 * 创建 CameraAgentIntegration 的 remember 函数
 */
@Composable
fun rememberCameraAgentIntegration(
    useCase: AiAgentUseCase
): CameraAgentIntegration {
    val context = LocalContext.current
    val orchestrator = remember {
        AgentOrchestrator.getInstance(context).apply {
            // 加载配置
            configure(
                mode = AiAgentMode.LOCAL,
                modelId = "qwen3_1_7b", // 下划线格式，与 ModelManager 注册表一致
                privacyLevel = AiAgentPrivacyLevel.STRICT
            )
        }
    }

    return remember {
        CameraAgentIntegration(orchestrator, useCase)
    }
}
