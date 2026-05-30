package com.picme.features.gallery.agent

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.picme.core.common.Logger
import com.picme.domain.agent.AgentOrchestrator
import com.picme.domain.agent.capability.GalleryCapability
import com.picme.domain.agent.capability.NavigationCapability
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager
import com.picme.domain.model.AiAgentCommand
import com.picme.domain.model.MediaAsset
import com.picme.features.common.chat.AgentMessage
import com.picme.features.common.chat.AiChatScreen
import com.picme.features.gallery.MediaViewModel

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

    private val appContext = context.applicationContext

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
 * 使用统一的 AiChatScreen 组件，与其他页面保持一致
 */
@Composable
fun GalleryAgentPanel(
    integration: GalleryAgentIntegration,
    pageContext: PageContext,
    modifier: Modifier = Modifier
) {
    var isVisible by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    val messages = remember { mutableStateOf<List<AgentMessage>>(emptyList()) }

    // 浮动按钮触发 - 右下角，方便拇指点击
    if (!isVisible) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomEnd
        ) {
            FloatingActionButton(
                onClick = { isVisible = true },
                modifier = Modifier
                    .padding(end = 16.dp, bottom = 100.dp)
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
    }

    // 统一的 AiChatScreen
    AiChatScreen(
        visible = isVisible,
        messages = messages.value,
        isProcessing = isProcessing,
        onVisibleChange = { isVisible = it },
        onSendMessage = { input ->
            messages.value = messages.value + AgentMessage.UserText(content = input)
            isProcessing = true
            // TODO: 集成 AgentOrchestrator 处理消息
            // 模拟响应
            isProcessing = false
            messages.value = messages.value + AgentMessage.AgentText(
                content = "收到：$input"
            )
        },
        onCommand = { command ->
            // TODO: 处理命令
        },
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
