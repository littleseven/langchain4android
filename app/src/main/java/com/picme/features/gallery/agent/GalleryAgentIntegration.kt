package com.picme.features.gallery.agent

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.picme.core.common.Logger
import com.picme.domain.agent.AgentOrchestrator
import com.picme.domain.agent.capability.GalleryCapability
import com.picme.domain.agent.capability.NavigationCapability
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager
import com.picme.domain.model.AiAgentCommand
import com.picme.domain.model.MediaAsset
import com.picme.domain.agent.model.AgentScene
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
     */
    fun onEnterGallery() {
        Logger.i(TAG, "Entering GALLERY scene")
        sceneManager.transitionTo(SceneManager.Scene.GALLERY)
    }

    /**
     * 离开 Gallery 场景时调用
     */
    fun onExitGallery() {
        Logger.i(TAG, "Exiting GALLERY scene")
        sceneManager.transitionTo(SceneManager.Scene.UNKNOWN)
    }

    /**
     * 注册 Gallery 相关的 Capability
     */
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
        // 注册 GalleryCapability
        val galleryCapability = GalleryCapability(
            onViewMedia = { mediaId ->
                mediaId?.let { id ->
                    val asset = allMedia.find { it.id.toString() == id }
                    asset?.let { onViewMedia(it) }
                }
            },
            onDeleteMedia = { mediaIds ->
                val assets = allMedia.filter { it.id.toString() in mediaIds }
                onDeleteMedia(assets)
            },
            onShareMedia = { mediaIds ->
                val assets = allMedia.filter { it.id.toString() in mediaIds }
                onShareMedia(assets)
            },
            onSelectMedia = { mediaId, selected ->
                val asset = allMedia.find { it.id.toString() == mediaId }
                asset?.let { onSelectMedia(it, selected) }
            },
            onSearch = onSearchMedia,
            onSwitchViewMode = { mode ->
                onSwitchViewMode(mode.name.lowercase())
            },
            onFavoriteMedia = { mediaId, favorite ->
                val asset = allMedia.find { it.id.toString() == mediaId }
                asset?.let { onFavoriteMedia(it, favorite) }
            }
        )
        orchestrator.registerCapability(galleryCapability)
        Logger.i(TAG, "GalleryCapability registered")

        // 注册 NavigationCapability
        val navigationCapability = NavigationCapability(
            onNavigate = { destination ->
                onNavigateTo(destination.name.lowercase())
            },
            onBack = onNavigateBack
        )
        orchestrator.registerCapability(navigationCapability)
        Logger.i(TAG, "NavigationCapability registered")
    }

    /**
     * 注销 Gallery 相关的 Capability
     */
    fun unregisterCapabilities() {
        orchestrator.unregisterCapability("gallery")
        orchestrator.unregisterCapability("navigation")
        Logger.i(TAG, "Gallery capabilities unregistered")
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
