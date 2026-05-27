package com.picme.features.gallery.agent

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.picme.core.common.Logger
import com.picme.domain.agent.AgentOrchestratorV2
import com.picme.domain.agent.CapabilityRegistryV2
import com.picme.domain.agent.PromptBuilder
import com.picme.domain.agent.capability.GalleryCapability
import com.picme.domain.agent.capability.NavigationCapability
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.GalleryPageContext
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager
import com.picme.domain.model.MediaAsset
import com.picme.features.agent.GlobalAgentPanel
import com.picme.features.gallery.MediaViewModel
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
    private val TAG = "GalleryAgent"

    private val appContext = context.applicationContext

    // SceneManager 单例
    private val sceneManager = SceneManager.getInstance()

    // CapabilityRegistry 单例
    private val registry = CapabilityRegistryV2.getInstance()

    // Orchestrator 单例
    private val orchestrator = AgentOrchestratorV2.getInstance(appContext)

    // PromptBuilder
    private val promptBuilder = PromptBuilder(sceneManager)

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
    }

    /**
     * 注册 Gallery 相关的 Capability
     */
    fun registerCapabilities(
        viewModel: MediaViewModel,
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
            onViewMedia = onViewMedia,
            onDeleteMedia = onDeleteMedia,
            onShareMedia = onShareMedia,
            onSelectMedia = onSelectMedia,
            onSearch = onSearchMedia,
            onSwitchViewMode = { mode ->
                onSwitchViewMode(if (mode) "list" else "grid")
            },
            onFavoriteMedia = onFavoriteMedia
        )
        registry.register(galleryCapability)
        Logger.i(TAG, "GalleryCapability registered")

        // 注册 NavigationCapability
        val navigationCapability = NavigationCapability(
            onNavigate = { destination ->
                onNavigateTo(destination)
            },
            onBack = onNavigateBack
        )
        registry.register(navigationCapability)
        Logger.i(TAG, "NavigationCapability registered")
    }

    /**
     * 执行 Agent 命令
     */
    suspend fun executeCommand(command: AgentCommand, pageContext: PageContext): Result<Any> {
        val capabilities = registry.getCapabilitiesForScene(sceneManager.currentScene.value)

        return try {
            // 找到能处理该命令的 Capability
            for (capability in capabilities) {
                if (capability.canHandle(command)) {
                    val result = capability.execute(command, buildAgentContext(), pageContext)
                    return result.map { it as Any }
                }
            }
            Result.failure(IllegalStateException("No capability can handle command: $command"))
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to execute command", e)
            Result.failure(e)
        }
    }

    /**
     * 构建 AgentContext
     */
    private fun buildAgentContext(): com.picme.domain.agent.model.AgentContext {
        return com.picme.domain.agent.model.AgentContext(
            scene = sceneManager.currentScene.value
        )
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
        return PageContext.Gallery(
            currentMedia = currentMedia,
            selectedItems = selectedItems,
            isSelectionMode = isSelectionMode,
            allMedia = allMedia
        )
    }
}

/**
 * GalleryScreen 的 Agent Panel 组件
 */
@Composable
fun GalleryAgentPanel(
    integration: GalleryAgentIntegration,
    pageContext: PageContext,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd
    ) {
        GlobalAgentPanel(
            pageContext = pageContext,
            modifier = Modifier.padding(16.dp)
        )
    }
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
