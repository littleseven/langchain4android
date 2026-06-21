package com.mamba.picme.features.gallery.agent

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.core.common.Logger
import com.mamba.picme.features.camera.voice.VoiceCommandCoordinator
import com.mamba.picme.features.common.chat.AgentChatPanel

/**
 * GalleryScreen 的 Agent 集成
 *
 * 职责：
 * 1. 初始化 Gallery 场景所需的 Capability
 * 2. 通知 SceneManager 当前场景为 GALLERY
 * 3. 构建 GalleryPageContext 供 Agent 使用
 * 4. 渲染 GlobalAgentPanel
 */
class GalleryAgentIntegration(
    context: Context,
    private val onNavigateTo: (String) -> Unit,
    private val onNavigateBack: () -> Unit
) {
    companion object { private const val TAG = "GalleryAgent" }

    val appContext = context.applicationContext

    // SceneManager 单例
    private val sceneManager = SceneManager.getInstance()

    // Orchestrator 单例
    private val orchestrator = AgentOrchestrator.getInstance(appContext)

    /**
     * 进入 Gallery 场景时调用
     *
     * 注意：Scene 切换由 MainActivity 统一管理，此处不再重复设置
     */
    fun onEnterGallery() {
        Logger.i(TAG, "Entering GALLERY scene (scene managed by MainActivity)")
        // Scene 切换由 MainActivity 的 DisposableEffect 统一管理
    }

    /**
     * 离开 Gallery 场景时调用
     *
     * 注意：Scene 切换由 MainActivity 统一管理，此处不再重复设置
     */
    fun onExitGallery() {
        Logger.i(TAG, "Exiting GALLERY scene (scene managed by MainActivity)")
        // Scene 切换由 MainActivity 的 DisposableEffect 统一管理
    }

    /**
     * 构建 GalleryPageContext
     */
    fun buildPageContext(
        currentMedia: MediaAsset?,
        selectedItems: List<MediaAsset>,
        isSelectionMode: Boolean,
        allMedia: List<MediaAsset>
    ): PageContext {
        return PageContext.GalleryContext(
            currentMedia = currentMedia,
            selectedItems = selectedItems,
            isSelectionMode = isSelectionMode,
            viewMode = PageContext.GalleryViewMode.GRID
        )
    }
}

/**
 * GalleryScreen 的 Agent Panel 组件
 *
 * 使用统一的 AgentChatPanel 组件，与其他页面保持一致
 */
@Composable
fun GalleryAgentPanel(
    integration: GalleryAgentIntegration,
    pageContext: PageContext,
    voiceCoordinator: VoiceCommandCoordinator? = null,
    modifier: Modifier = Modifier
) {
    AgentChatPanel(
        pageContext = pageContext,
        agentScene = AgentScene.GALLERY,
        memorySessionId = "gallery",
        voiceCoordinator = voiceCoordinator,
        modifier = modifier
    )
}

/**
 * 创建并记住 GalleryAgentIntegration 实例
 */
@Composable
fun rememberGalleryAgentIntegration(
    context: Context,
    onNavigateTo: (String) -> Unit,
    onNavigateBack: () -> Unit
): GalleryAgentIntegration {
    val integration = remember {
        GalleryAgentIntegration(context, onNavigateTo, onNavigateBack)
    }

    DisposableEffect(Unit) {
        integration.onEnterGallery()
        onDispose {
            integration.onExitGallery()
        }
    }

    return integration
}
