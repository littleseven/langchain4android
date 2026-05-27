package com.picme.features.camera.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.picme.domain.agent.AgentOrchestratorV2
import com.picme.domain.agent.capability.CameraCapability
import com.picme.domain.agent.capability.NavigationCapability
import com.picme.domain.agent.capability.toV2
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.SceneManager
import com.picme.domain.usecase.AiAgentUseCase
import com.picme.features.agent.GlobalAgentPanel
import com.picme.features.agent.rememberGlobalAgentPanelState

/**
 * CameraScreen 的 Agent 集成
 *
 * 将新的 AgentOrchestratorV2 与现有 CameraScreen 集成
 * 提供向后兼容的桥梁
 */
class CameraAgentIntegration(
    private val orchestrator: AgentOrchestratorV2,
    private val useCase: AiAgentUseCase
) {
    /**
     * 初始化 Camera 相关 Capability
     */
    fun initializeCameraCapabilities(
        onAdjustBeauty: (com.picme.beauty.api.BeautySettings) -> Unit,
        onSwitchFilter: (com.picme.beauty.api.FilterType) -> Unit,
        onSwitchStyle: (com.picme.beauty.api.StyleFilter) -> Unit,
        onSwitchScene: (String) -> Unit,
        onSwitchRatio: (String) -> Unit,
        onAdjustExposure: (Int) -> Unit,
        onAdjustZoom: (Float) -> Unit,
        onFlipCamera: () -> Unit,
        onCapturePhoto: () -> Unit,
        onToggleRecording: () -> Unit,
        onSwitchMode: (com.picme.domain.model.MediaType) -> Unit,
        onNavigateTo: (String) -> Unit,
        onBack: () -> Unit
    ) {
        // 注册 CameraCapability
        val cameraCapability = CameraCapability(
            onAdjustBeauty = onAdjustBeauty,
            onSwitchFilter = onSwitchFilter,
            onSwitchStyle = onSwitchStyle,
            onSwitchScene = onSwitchScene,
            onSwitchRatio = onSwitchRatio,
            onAdjustExposure = onAdjustExposure,
            onAdjustZoom = onAdjustZoom,
            onFlipCamera = onFlipCamera,
            onCapturePhoto = onCapturePhoto,
            onToggleRecording = onToggleRecording,
            onSwitchMode = onSwitchMode
        )
        orchestrator.registerCapability(cameraCapability.toV2())

        // 注册 NavigationCapability（使用 String 类型的回调）
        val navigationCapability = NavigationCapability(
            onNavigate = { destination ->
                // 将 Destination 转换为 String
                onNavigateTo(destination.name.lowercase())
            },
            onBack = onBack
        )
        orchestrator.registerCapability(navigationCapability.toV2())
    }

    /**
     * 进入 Camera 场景
     */
    fun enterCameraScene() {
        orchestrator.transitionToScene(SceneManager.Scene.CAMERA)
    }

    /**
     * 离开 Camera 场景
     */
    fun leaveCameraScene() {
        // 清理工作（如需要）
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
        AgentOrchestratorV2.getInstance(context).apply {
            // 加载配置
            configure(
                mode = com.picme.domain.model.AiAgentMode.LOCAL,
                modelId = "qwen3_0_6b",
                privacyLevel = com.picme.domain.model.AiAgentPrivacyLevel.STRICT
            )
        }
    }

    return remember {
        CameraAgentIntegration(orchestrator, useCase)
    }
}
