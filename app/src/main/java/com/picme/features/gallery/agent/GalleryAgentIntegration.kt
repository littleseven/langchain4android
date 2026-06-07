package com.picme.features.gallery.agent

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.picme.core.common.Logger
import com.picme.agent.core.AgentOrchestrator
import com.picme.features.gallery.capability.GalleryCapability
import com.picme.domain.agent.capability.NavigationCapability
import com.picme.agent.core.model.PageContext
import com.picme.agent.core.SceneManager
import com.picme.domain.model.AiAgentCommand
import com.picme.agent.core.model.MediaAsset
import com.picme.agent.core.model.AgentScene
import com.picme.features.common.chat.AgentChatPanel
import com.picme.features.camera.voice.VoiceCommandCoordinator
import com.picme.features.gallery.MediaViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
     * 注册 Gallery 相关的 Capability
     *
     * **已弃用**：Capability 现在由 PicMeApplication 统一注册，
     * 页面只需通过 GalleryCapability.getInstance().bindDelegate() 绑定 delegate。
     */
    @Deprecated("Capability 由 PicMeApplication 统一注册，页面只需绑定 delegate")
    fun registerCapabilities(
        viewModel: MediaViewModel,
        allMedia: List<MediaAsset>,
        onViewMedia: (MediaAsset) -> Unit,
        onDeleteMedia: (List<MediaAsset>) -> Unit,
        onShareMedia: (List<MediaAsset>) -> Unit,
        onSelectMedia: (MediaAsset, Boolean) -> Unit,
        onSearchMedia: (String) -> Unit,
        onSwitchViewMode: (String) -> Unit,
        onFavoriteMedia: (MediaAsset, Boolean) -> Unit
    ) {
        Logger.i(TAG, "registerCapabilities is deprecated, capabilities registered by PicMeApplication")
    }

    /**
     * 注销 Gallery 相关的 Capability
     *
     * **已弃用**：Capability 不再注销，由 PicMeApplication 统一管理。
     */
    @Deprecated("Capability 不再注销，由 PicMeApplication 统一管理")
    fun unregisterCapabilities() {
        Logger.i(TAG, "unregisterCapabilities is deprecated, capabilities managed by PicMeApplication")
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
