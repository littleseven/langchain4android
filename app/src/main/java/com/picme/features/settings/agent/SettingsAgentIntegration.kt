package com.picme.features.settings.agent

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.picme.core.common.Logger
import com.picme.agent.core.CapabilityRegistry
import com.picme.domain.agent.capability.NavigationCapability
import com.picme.features.settings.capability.SettingsCapability
import com.picme.agent.core.model.PageContext
import com.picme.agent.core.SceneManager
import com.picme.agent.core.model.AgentScene
import com.picme.features.common.chat.AgentChatPanel
import com.picme.features.camera.voice.VoiceCommandCoordinator
import com.picme.features.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SettingsScreen 的 Agent 集成
 *
 * 职责：
 * 1. 初始化 Settings 场景所需的 Capability
 * 2. 通知 SceneManager 当前场景为 SETTINGS
 * 3. 构建 SettingsPageContext 供 Agent 使用
 * 4. 渲染 GlobalAgentPanel
 */
class SettingsAgentIntegration(
    context: Context,
    private val onNavigateTo: (String) -> Unit,
    private val onNavigateBack: () -> Unit
) {
    companion object { private const val TAG = "SettingsAgent" }

    private val appContext = context.applicationContext

    private val sceneManager = SceneManager.getInstance()
    private val registry = CapabilityRegistry.getInstance()

    /**
     * 进入 Settings 场景时调用
     *
     * 注意：Scene 切换由 MainActivity 统一管理，此处不再重复设置
     */
    fun onEnterSettings() {
        Logger.i(TAG, "Entering SETTINGS scene (scene managed by MainActivity)")
        // Scene 切换由 MainActivity 的 DisposableEffect 统一管理
    }

    /**
     * 离开 Settings 场景时调用
     *
     * 注意：Scene 切换由 MainActivity 统一管理，此处不再重复设置
     */
    fun onExitSettings() {
        Logger.i(TAG, "Exiting SETTINGS scene (scene managed by MainActivity)")
        // Scene 切换由 MainActivity 的 DisposableEffect 统一管理
    }

    /**
     * 注册 Settings 相关的 Capability
     *
     * **已弃用**：Capability 现在由 PicMeApplication 统一注册，
     * 页面只需通过 SettingsCapability.getInstance().bindDelegate() 绑定 delegate。
     */
    @Deprecated("Capability 由 PicMeApplication 统一注册，页面只需绑定 delegate")
    fun registerCapabilities(
        viewModel: SettingsViewModel,
        onNavigateToModelManager: () -> Unit
    ) {
        Logger.i(TAG, "registerCapabilities is deprecated, capabilities registered by PicMeApplication")
    }

    /**
     * 注销 Settings 相关的 Capability
     *
     * **已弃用**：Capability 不再注销，由 PicMeApplication 统一管理。
     */
    @Deprecated("Capability 不再注销，由 PicMeApplication 统一管理")
    fun unregisterCapabilities() {
        Logger.i(TAG, "unregisterCapabilities is deprecated, capabilities managed by PicMeApplication")
    }

    fun buildPageContext(
        currentCategory: String? = null
    ): PageContext {
        return PageContext.SettingsContext(
            currentCategory = currentCategory
        )
    }
}

/**
 * SettingsScreen 的 Agent Panel 组件
 *
 * 使用统一的 AgentChatPanel 组件，与其他页面保持一致
 */
@Composable
fun SettingsAgentPanel(
    pageContext: PageContext,
    voiceCoordinator: VoiceCommandCoordinator? = null,
    modifier: Modifier = Modifier
) {
    AgentChatPanel(
        pageContext = pageContext,
        agentScene = AgentScene.SETTINGS,
        memorySessionId = "settings",
        voiceCoordinator = voiceCoordinator,
        modifier = modifier
    )
}

/**
 * 创建并记住 SettingsAgentIntegration 实例
 */
@Composable
fun rememberSettingsAgentIntegration(
    context: Context,
    onNavigateTo: (String) -> Unit,
    onNavigateBack: () -> Unit
): SettingsAgentIntegration {
    val integration = remember {
        SettingsAgentIntegration(context, onNavigateTo, onNavigateBack)
    }

    DisposableEffect(Unit) {
        integration.onEnterSettings()
        onDispose {
            integration.onExitSettings()
        }
    }

    return integration
}
