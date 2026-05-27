package com.picme.features.settings.agent

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.picme.core.common.Logger
import com.picme.domain.agent.CapabilityRegistry
import com.picme.domain.agent.capability.NavigationCapability
import com.picme.domain.agent.capability.SettingsCapability
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager
import com.picme.domain.model.AppLanguage
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.domain.model.ThemeMode
import com.picme.features.agent.GlobalAgentPanel
import com.picme.features.settings.SettingsViewModel

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
    private val TAG = "SettingsAgent"

    private val appContext = context.applicationContext

    private val sceneManager = SceneManager.getInstance()
    private val registry = CapabilityRegistry.getInstance()

    fun onEnterSettings() {
        Logger.i(TAG, "Entering SETTINGS scene")
        sceneManager.transitionTo(SceneManager.Scene.SETTINGS)
    }

    fun onExitSettings() {
        Logger.i(TAG, "Exiting SETTINGS scene")
    }

    fun registerCapabilities(
        viewModel: SettingsViewModel,
        onNavigateToModelManager: () -> Unit
    ) {
        // 注册 SettingsCapability
        val settingsCapability = SettingsCapability(
            onChangeTheme = { theme ->
                viewModel.setThemeMode(theme)
            },
            onChangeLanguage = { language ->
                viewModel.setAppLanguage(language)
            },
            onDownloadModel = { modelId ->
                // 导航到模型管理页面
                onNavigateToModelManager()
            },
            onSwitchFaceEngine = { engine ->
                viewModel.setFaceDetectionEngineMode(engine)
            },
            onToggleSetting = { key, enabled ->
                when (key) {
                    "debug_ui" -> viewModel.setDebugUiEnabled(enabled)
                    "camera_info" -> viewModel.setShowCameraInfoInPreview(enabled)
                    "voice_command" -> viewModel.setVoiceCommandMode(
                        if (enabled) com.picme.domain.model.VoiceCommandMode.WAKE_WORD else com.picme.domain.model.VoiceCommandMode.DISABLED
                    )
                    "agent_mode" -> viewModel.setAiAgentMode(
                        if (enabled) com.picme.domain.model.AiAgentMode.LOCAL else com.picme.domain.model.AiAgentMode.OFF
                    )
                    else -> Logger.w(TAG, "Unknown setting key: $key")
                }
            }
        )
        registry.register(settingsCapability)
        Logger.i(TAG, "SettingsCapability registered")

        // 注册 NavigationCapability
        val navigationCapability = NavigationCapability(
            onNavigate = { destination ->
                onNavigateTo(destination.name.lowercase())
            },
            onBack = onNavigateBack
        )
        registry.register(navigationCapability)
        Logger.i(TAG, "NavigationCapability registered")
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
 */
@Composable
fun SettingsAgentPanel(
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
