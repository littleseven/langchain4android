package com.picme.features.settings


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.picme.R
import com.picme.core.common.Logger
import com.picme.core.designsystem.PicMeTheme
import com.picme.data.download.LlmModelDownloadManager
import com.picme.data.download.ModelConfig
import com.picme.domain.model.AiAgentMode
import com.picme.domain.model.AppLanguage
import com.picme.domain.model.DetectionModelType
import com.picme.domain.model.DetectionStage
import com.picme.domain.model.FaceDetectIntervalProfile
import com.picme.domain.model.InferenceDevicePreference
import com.picme.domain.model.StageConfig
import com.picme.domain.model.ThemeMode
import com.picme.domain.model.VoiceCommandMode
import com.picme.features.common.chat.rememberAgentChatConfig
import com.picme.features.settings.agent.SettingsAgentPanel
import com.picme.features.settings.agent.rememberSettingsAgentIntegration
import java.util.Locale

private const val TAG = "Settings"

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToModelCenter: () -> Unit = {},
) {
    // 沉浸式模式
    val view = LocalView.current
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window ?: return@DisposableEffect onDispose {}
        val insetsController = WindowCompat.getInsetsController(window, view)

        // 隐藏状态栏和导航栏
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        // 设置沉浸式模式，滑动边缘时显示系统栏
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            // 恢复系统栏显示
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val themeMode by viewModel.themeMode.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()

    val debugUiEnabled by viewModel.debugUiEnabled.collectAsState()
    val showCameraInfoInPreview by viewModel.showCameraInfoInPreview.collectAsState()
    val showFaceDebugOverlay by viewModel.showFaceDebugOverlay.collectAsState()
    val showLogOverlay by viewModel.showLogOverlay.collectAsState()
    val faceDetectionLandmarkModeEnabled by viewModel.faceDetectionLandmarkModeEnabled.collectAsState()
    val adaptiveFaceDetectionIntervalEnabled by viewModel.adaptiveFaceDetectionIntervalEnabled.collectAsState()
    val faceDetectIntervalProfile by viewModel.faceDetectIntervalProfile.collectAsState()
    val debugShaderMode by viewModel.debugShaderMode.collectAsState()
    val roiStageConfig by viewModel.roiStageConfig.collectAsState()
    val landmarkStageConfig by viewModel.landmarkStageConfig.collectAsState()
    val aiAgentMode by viewModel.aiAgentMode.collectAsState()
    val aiAgentLocalModel by viewModel.aiAgentLocalModel.collectAsState()
    val aiAgentRemoteModelConfigs by viewModel.aiAgentRemoteModelConfigs.collectAsState()
    val aiAgentSelectedRemoteModel by viewModel.aiAgentSelectedRemoteModel.collectAsState()
    val aiAgentForceRemote by viewModel.aiAgentForceRemote.collectAsState()
    val voiceCommandMode by viewModel.voiceCommandMode.collectAsState()
    val localAsrModel by viewModel.localAsrModel.collectAsState()
    val logModuleConfig by viewModel.logModuleConfig.collectAsState()

    // 模型下载状态（从 ViewModel 获取，确保共享）
    val downloadStates by viewModel.downloadStates.collectAsState()

    // 模型配置列表（用于设置页自动下载）
    val allModels by viewModel.allModels.collectAsState()

    // ===== Agent Chat 配置（使用公共组件）=====
    val agentChatConfig = rememberAgentChatConfig(
        context = context,
        logTag = TAG,
        onCommand = { command ->
            Logger.i(TAG, "Voice command: ${command.javaClass.simpleName}")
        },
        onTranscript = { transcript ->
            Logger.d(TAG, "Voice transcript: $transcript")
        }
    )
    val voiceCoordinator = agentChatConfig.voiceCoordinator
    DisposableEffect(Unit) {
        onDispose {
            voiceCoordinator.release()
        }
    }

    // ===== Agent 集成 =====
    val agentIntegration = rememberSettingsAgentIntegration(
        context = context,
        onNavigateTo = { destination ->
            when (destination.lowercase()) {
                "camera" -> onNavigateBack()
                "gallery" -> onNavigateBack()
                "settings" -> { /* 已在设置页，无需导航 */ }
                "debug" -> onNavigateBack()
                "model_center" -> onNavigateToModelCenter()
                "llm_model_manager" -> onNavigateToModelCenter()
                "asr_model_manager" -> onNavigateToModelCenter()
                "face_detection_model_manager" -> onNavigateToModelCenter()

                else -> Logger.w(TAG, "Unknown navigation destination: $destination")
            }
        },
        onNavigateBack = onNavigateBack
    )

    // 绑定 SettingsCapability 的 delegate，确保生命周期绑定
    DisposableEffect(Unit) {
        Logger.i(TAG, "Binding SettingsCapability delegate")

        val settingsCapability = com.picme.domain.agent.capability.SettingsCapability.getInstance()
        settingsCapability.bindDelegate(object : com.picme.domain.agent.capability.SettingsCapability.Delegate {
            override fun onChangeTheme(theme: com.picme.domain.model.ThemeMode) {
                viewModel.setThemeMode(theme)
            }
            override fun onChangeLanguage(language: com.picme.domain.model.AppLanguage) {
                viewModel.setAppLanguage(language)
            }
            override fun onDownloadModel(modelId: String) {
                onNavigateToModelCenter()
            }
            override fun onSwitchFaceEngine(engine: com.picme.domain.model.FaceDetectionEngineMode) {
                viewModel.setFaceDetectionEngineMode(engine)
            }
            override fun onToggleSetting(key: String, enabled: Boolean) {
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
        })
        Logger.i(TAG, "SettingsCapability delegate bound")

        onDispose {
            Logger.i(TAG, "Unbinding SettingsCapability delegate")
            settingsCapability.unbindDelegate()
        }
    }

    // 构建 PageContext
    val pageContext = agentIntegration.buildPageContext()

    Box(modifier = Modifier.fillMaxSize()) {
        settingsContent(
        themeMode = themeMode,
        appLanguage = appLanguage,
        debugUiEnabled = debugUiEnabled,
        showCameraInfoInPreview = showCameraInfoInPreview,
        showFaceDebugOverlay = showFaceDebugOverlay,
        showLogOverlay = showLogOverlay,
        faceDetectionLandmarkModeEnabled = faceDetectionLandmarkModeEnabled,
        adaptiveFaceDetectionIntervalEnabled = adaptiveFaceDetectionIntervalEnabled,
        faceDetectIntervalProfile = faceDetectIntervalProfile,
        debugShaderMode = debugShaderMode,
        roiStageConfig = roiStageConfig,
        landmarkStageConfig = landmarkStageConfig,
        onThemeModeSelected = { mode -> viewModel.setThemeMode(mode) },
        onAppLanguageSelected = { language -> viewModel.setAppLanguage(language) },
        onDebugUiEnabledChange = { enabled -> viewModel.setDebugUiEnabled(enabled) },
        onShowCameraInfoInPreviewChange = { show -> viewModel.setShowCameraInfoInPreview(show) },
        onShowFaceDebugOverlayChange = { show -> viewModel.setShowFaceDebugOverlay(show) },
        onShowLogOverlayChange = { show -> viewModel.setShowLogOverlay(show) },
        onFaceDetectionLandmarkModeEnabledChange = { enabled ->
            viewModel.setFaceDetectionLandmarkModeEnabled(enabled)
        },
        onAdaptiveFaceDetectionIntervalEnabledChange = { enabled ->
            viewModel.setAdaptiveFaceDetectionIntervalEnabled(enabled)
        },
        onFaceDetectIntervalProfileSelected = { profile ->
            viewModel.setFaceDetectIntervalProfile(profile)
        },
        onDebugShaderModeSelected = { mode -> viewModel.setDebugShaderMode(mode) },
        onRoiModelTypeSelected = { type -> viewModel.setRoiModelType(type) },
        onRoiDevicePreferenceSelected = { preference -> viewModel.setRoiDevicePreference(preference) },
        onLandmarkModelTypeSelected = { type -> viewModel.setLandmarkModelType(type) },
        onLandmarkDevicePreferenceSelected = { preference -> viewModel.setLandmarkDevicePreference(preference) },
        aiAgentMode = aiAgentMode,
        onAiAgentModeChange = { mode -> viewModel.setAiAgentMode(mode) },
        aiAgentLocalModel = aiAgentLocalModel,
        onAiAgentLocalModelChange = { modelId -> viewModel.setAiAgentLocalModel(modelId) },
        aiAgentRemoteModelConfigs = aiAgentRemoteModelConfigs,
        onAiAgentRemoteModelConfigsChange = { configs -> viewModel.setAiAgentRemoteModelConfigs(configs) },
        aiAgentSelectedRemoteModel = aiAgentSelectedRemoteModel,
        onAiAgentSelectedRemoteModelChange = { modelId -> viewModel.setAiAgentSelectedRemoteModel(modelId) },
        aiAgentForceRemote = aiAgentForceRemote,
        onAiAgentForceRemoteChange = { enabled -> viewModel.setAiAgentForceRemote(enabled) },
        voiceCommandMode = voiceCommandMode,
        onVoiceCommandModeChange = { mode -> viewModel.setVoiceCommandMode(mode) },
        localAsrModel = localAsrModel,
        onLocalAsrModelChange = { modelId -> viewModel.setLocalAsrModel(modelId) },
        onNavigateToModelCenter = onNavigateToModelCenter,
        isModelDownloaded = viewModel::isModelDownloaded,
        getModelId = viewModel::getModelId,
        downloadModel = viewModel::downloadModel,
        downloadStates = downloadStates,
        allModels = allModels,
        logModuleConfig = logModuleConfig,
        onLogModuleConfigChange = viewModel::setLogModuleConfig,
        onNavigateBack = onNavigateBack
    )

        // Agent Panel（浮动在内容之上）
        SettingsAgentPanel(
            pageContext = pageContext,
            voiceCoordinator = voiceCoordinator,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun settingsContent(
    themeMode: ThemeMode,
    appLanguage: AppLanguage,
    debugUiEnabled: Boolean,
    showCameraInfoInPreview: Boolean,
    showFaceDebugOverlay: Boolean,
    showLogOverlay: Boolean,
    faceDetectionLandmarkModeEnabled: Boolean,
    adaptiveFaceDetectionIntervalEnabled: Boolean,
    faceDetectIntervalProfile: FaceDetectIntervalProfile,
    debugShaderMode: Int,
    roiStageConfig: StageConfig,
    landmarkStageConfig: StageConfig,
    aiAgentMode: AiAgentMode,
    onAiAgentModeChange: (AiAgentMode) -> Unit,
    aiAgentLocalModel: String,
    onAiAgentLocalModelChange: (String) -> Unit,
    aiAgentRemoteModelConfigs: String,
    onAiAgentRemoteModelConfigsChange: (String) -> Unit,
    aiAgentSelectedRemoteModel: String,
    onAiAgentSelectedRemoteModelChange: (String) -> Unit,
    aiAgentForceRemote: Boolean,
    onAiAgentForceRemoteChange: (Boolean) -> Unit,
    voiceCommandMode: VoiceCommandMode,
    onVoiceCommandModeChange: (VoiceCommandMode) -> Unit,
    localAsrModel: String,
    onLocalAsrModelChange: (String) -> Unit,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onAppLanguageSelected: (AppLanguage) -> Unit,
    onDebugUiEnabledChange: (Boolean) -> Unit,
    onShowCameraInfoInPreviewChange: (Boolean) -> Unit,
    onShowFaceDebugOverlayChange: (Boolean) -> Unit,
    onShowLogOverlayChange: (Boolean) -> Unit,
    onFaceDetectionLandmarkModeEnabledChange: (Boolean) -> Unit,
    onAdaptiveFaceDetectionIntervalEnabledChange: (Boolean) -> Unit,
    onFaceDetectIntervalProfileSelected: (FaceDetectIntervalProfile) -> Unit,
    onDebugShaderModeSelected: (Int) -> Unit,
    onRoiModelTypeSelected: (DetectionModelType) -> Unit,
    onRoiDevicePreferenceSelected: (InferenceDevicePreference) -> Unit,
    onLandmarkModelTypeSelected: (DetectionModelType) -> Unit,
    onLandmarkDevicePreferenceSelected: (InferenceDevicePreference) -> Unit,
    onNavigateToModelCenter: () -> Unit,
    isModelDownloaded: (DetectionModelType) -> Boolean,
    getModelId: (DetectionModelType, DetectionStage) -> String?,
    downloadModel: (String, ModelConfig) -> Unit,
    downloadStates: Map<String, com.picme.data.download.DownloadState>,
    allModels: List<ModelConfig>,
    logModuleConfig: com.picme.domain.model.LogModuleConfig,
    onLogModuleConfigChange: (com.picme.domain.model.LogModuleConfig) -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            // AI Agent 配置 - 放到最顶部
            SettingsSection(
                title = stringResource(R.string.ai_agent),
                description = stringResource(R.string.ai_agent_desc)
            ) {
                // 第一行：模式选择 + 强制远程开关
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.ai_agent_mode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.ai_agent_force_remote),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(
                            checked = aiAgentForceRemote,
                            onCheckedChange = onAiAgentForceRemoteChange
                        )
                    }
                }

                AiAgentModeSelection(
                    currentMode = aiAgentMode,
                    onModeSelected = onAiAgentModeChange
                )

                // 自动执行多步骤计划开关
                var autoExecutePlans by remember { mutableStateOf(true) }
                DebugOptionRow(
                    title = stringResource(R.string.ai_agent_auto_execute_plans),
                    checked = autoExecutePlans,
                    onCheckedChange = { autoExecutePlans = it }
                )
                Text(
                    text = stringResource(R.string.ai_agent_auto_execute_plans_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )

                when (aiAgentMode) {
                    AiAgentMode.OFF -> {
                        Text(
                            text = stringResource(R.string.ai_agent_mode_off),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    AiAgentMode.LOCAL -> {
                        AiAgentLocalModelSection(
                            currentLocalModel = aiAgentLocalModel,
                            onLocalModelSelected = onAiAgentLocalModelChange,
                            onNavigateToModelManager = onNavigateToModelCenter
                        )
                    }
                    AiAgentMode.REMOTE -> {
                        AiAgentRemoteModelsSection(
                            configsJson = aiAgentRemoteModelConfigs,
                            onConfigsChange = onAiAgentRemoteModelConfigsChange,
                            selectedModelId = aiAgentSelectedRemoteModel,
                            onSelectedModelChange = onAiAgentSelectedRemoteModelChange
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 语音控制配置
            SettingsSection(
                title = stringResource(R.string.voice_control),
                description = stringResource(R.string.voice_control_desc)
            ) {
                VoiceCommandModeSelection(
                    currentMode = voiceCommandMode,
                    onModeSelected = onVoiceCommandModeChange
                )

                if (voiceCommandMode != VoiceCommandMode.DISABLED) {
                    LocalAsrModelSelection(
                        currentModel = localAsrModel,
                        onModelSelected = onLocalAsrModelChange,
                        onNavigateToModelCenter = onNavigateToModelCenter
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ROI 阶段配置
            StageConfigSection(
                stage = DetectionStage.ROI,
                config = roiStageConfig,
                onModelTypeSelected = onRoiModelTypeSelected,
                onDevicePreferenceSelected = onRoiDevicePreferenceSelected,
                onNavigateToModelManager = onNavigateToModelCenter,
                isModelDownloaded = isModelDownloaded,
                getModelId = getModelId,
                downloadModel = downloadModel,
                downloadStates = downloadStates,
                allModels = allModels
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Landmark 阶段配置
            StageConfigSection(
                stage = DetectionStage.LANDMARK,
                config = landmarkStageConfig,
                onModelTypeSelected = onLandmarkModelTypeSelected,
                onDevicePreferenceSelected = onLandmarkDevicePreferenceSelected,
                onNavigateToModelManager = onNavigateToModelCenter,
                isModelDownloaded = isModelDownloaded,
                getModelId = getModelId,
                downloadModel = downloadModel,
                downloadStates = downloadStates,
                allModels = allModels
            )

            Spacer(modifier = Modifier.height(10.dp))

            SettingsSection(
                title = stringResource(R.string.face_detection_advanced),
                description = stringResource(R.string.settings_face_detection_advanced_desc)
            ) {
                DebugOptionRow(
                    title = stringResource(R.string.face_landmark_mode),
                    checked = faceDetectionLandmarkModeEnabled,
                    onCheckedChange = onFaceDetectionLandmarkModeEnabledChange
                )
                DebugOptionRow(
                    title = stringResource(R.string.adaptive_face_detect_interval),
                    checked = adaptiveFaceDetectionIntervalEnabled,
                    onCheckedChange = onAdaptiveFaceDetectionIntervalEnabledChange
                )
                if (adaptiveFaceDetectionIntervalEnabled) {
                    FaceDetectProfileSelection(
                        currentProfile = faceDetectIntervalProfile,
                        onProfileSelected = onFaceDetectIntervalProfileSelected
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            SettingsSection(
                title = stringResource(R.string.debug_tools),
                description = stringResource(R.string.settings_debug_tools_desc)
            ) {
                DebugOptionRow(
                    title = stringResource(R.string.debug),
                    checked = debugUiEnabled,
                    onCheckedChange = onDebugUiEnabledChange
                )
                if (debugUiEnabled) {
                    DebugOptionRow(
                        title = stringResource(R.string.show_camera_info),
                        checked = showCameraInfoInPreview,
                        onCheckedChange = onShowCameraInfoInPreviewChange
                    )
                    DebugOptionRow(
                        title = stringResource(R.string.show_face_debug),
                        checked = showFaceDebugOverlay,
                        onCheckedChange = onShowFaceDebugOverlayChange
                    )
                    DebugOptionRow(
                        title = stringResource(R.string.show_log_overlay),
                        checked = showLogOverlay,
                        onCheckedChange = onShowLogOverlayChange
                    )
                    // Shader debug mode is always available in the current BIG_BEAUTY architecture.
                    ShaderDebugModeSelection(
                        currentMode = debugShaderMode,
                        onModeSelected = onDebugShaderModeSelected
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            SettingsSection(
                title = stringResource(R.string.theme_mode),
                description = stringResource(R.string.settings_theme_mode_desc)
            ) {
                themeSelection(
                    currentMode = themeMode,
                    onModeSelected = onThemeModeSelected
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            SettingsSection(
                title = stringResource(R.string.language),
                description = stringResource(R.string.settings_language_desc)
            ) {
                languageSelection(
                    currentLanguage = appLanguage,
                    onLanguageSelected = onAppLanguageSelected
                )
            }


            // 日志模块管理
            SettingsSection(
                title = stringResource(R.string.log_management),
                description = stringResource(R.string.log_management_desc)
            ) {
                LogModuleConfigSection(
                    config = logModuleConfig,
                    onConfigChange = onLogModuleConfigChange
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogModuleConfigSection(
    config: com.picme.domain.model.LogModuleConfig,
    onConfigChange: (com.picme.domain.model.LogModuleConfig) -> Unit
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        com.picme.domain.model.LogModule.entries.forEach { module ->
            val enabled = config.isEnabled(module)
            FilterChip(
                selected = enabled,
                onClick = { onConfigChange(config.toggle(module, !enabled)) },
                label = {
                    Text(
                        text = module.displayName,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

@Composable
private fun VoiceCommandModeSelection(
    currentMode: VoiceCommandMode,
    onModeSelected: (VoiceCommandMode) -> Unit
) {
    val options = listOf(
        VoiceCommandMode.DISABLED to stringResource(R.string.voice_command_mode_disabled),
        VoiceCommandMode.PUSH_TO_TALK to stringResource(R.string.voice_command_mode_push_to_talk),
        VoiceCommandMode.WAKE_WORD to stringResource(R.string.voice_command_mode_wake_word)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.voice_command_mode),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CompactOptionChips(
            options = options,
            currentValue = currentMode,
            maxLines = 1,
            onSelected = onModeSelected
        )
    }
}

@Composable
private fun LocalAsrModelSelection(
    currentModel: String,
    onModelSelected: (String) -> Unit,
    onNavigateToModelCenter: () -> Unit
) {
    val context = LocalContext.current
    val downloadManager = remember { LlmModelDownloadManager(context) }
    var downloadedModels by remember { mutableStateOf<List<com.picme.data.download.ModelConfig>>(emptyList()) }

    LaunchedEffect(Unit) {
        downloadedModels = downloadManager.getDownloadedModels()
            .filter { model ->
                model.tags.any { tag -> tag.equals("ASR", ignoreCase = true) } ||
                    model.id.contains("asr", ignoreCase = true)
            }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.local_asr_model),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // ASR 模型管理入口（紧凑图标按钮）
            Row(
                modifier = Modifier
                    .clickable(onClick = onNavigateToModelCenter)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.model_center),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = Icons.Outlined.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (downloadedModels.isEmpty()) {
            Text(
                text = stringResource(R.string.local_asr_model_fallback),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            val options = downloadedModels.map { it.id to it.name }
            CompactOptionChips(
                options = options,
                currentValue = currentModel,
                maxLines = 2,
                onSelected = onModelSelected
            )
        }
    }
}



@Composable
private fun AiAgentModeSelection(
    currentMode: AiAgentMode,
    onModeSelected: (AiAgentMode) -> Unit
) {
    val options = listOf(
        AiAgentMode.LOCAL to stringResource(R.string.ai_agent_mode_local),
        AiAgentMode.REMOTE to stringResource(R.string.ai_agent_mode_remote)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.ai_agent_mode),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CompactOptionChips(
            options = options,
            currentValue = currentMode,
            maxLines = 1,
            onSelected = onModeSelected
        )
    }
}

private fun ModelConfig.isAiAgentLlmCandidate(): Boolean {
    val normalizedTags = tags.map { tag -> tag.lowercase(Locale.ROOT) }
    val normalizedId = id.lowercase(Locale.ROOT)
    val normalizedName = name.lowercase(Locale.ROOT)

    val hasExcludedSignal = normalizedTags.any { tag ->
        tag == "asr" ||
            tag == "tts" ||
            tag == "audio" ||
            tag == "audiogen" ||
            tag == "imagegen" ||
            tag.contains("face")
    } || normalizedId.contains("face") || normalizedId.contains("asr") || normalizedName.contains("face")

    if (hasExcludedSignal) return false

    val hasLlmTag = normalizedTags.any { tag ->
        tag == "chat" ||
            tag == "think" ||
            tag == "reasoning" ||
            tag == "llm" ||
            tag == "language"
    }
    val hasLlmFile = files.any { file ->
        val normalizedFile = file.lowercase(Locale.ROOT)
        normalizedFile.contains("tokenizer") || normalizedFile.contains("llm")
    }
    val hasLlmId = normalizedId.contains("qwen") ||
        normalizedId.contains("llm") ||
        normalizedId.contains("chat") ||
        normalizedId.contains("deepseek") ||
        normalizedId.contains("mistral") ||
        normalizedId.contains("gemma")

    return hasLlmTag || hasLlmFile || hasLlmId
}

@Composable
private fun AiAgentLocalModelSection(
    currentLocalModel: String,
    onLocalModelSelected: (String) -> Unit,
    onNavigateToModelManager: () -> Unit
) {
    val context = LocalContext.current
    val downloadManager = remember { LlmModelDownloadManager(context) }

    var downloadedModels by remember { mutableStateOf<List<ModelConfig>>(emptyList()) }

    LaunchedEffect(Unit) {
        downloadedModels = downloadManager.getDownloadedModels()
            .filter { model -> model.isAiAgentLlmCandidate() }
    }

    // 当界面重新获得焦点时刷新已下载模型列表（从模型管理器返回后）
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            downloadedModels = downloadManager.getDownloadedModels()
                .filter { model -> model.isAiAgentLlmCandidate() }
        }
    }

    LaunchedEffect(downloadedModels, currentLocalModel) {
        val hasCurrentSelection = downloadedModels.any { model -> model.id == currentLocalModel }
        if (!hasCurrentSelection && downloadedModels.isNotEmpty()) {
            onLocalModelSelected(downloadedModels.first().id)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // 已下载模型选择
        Text(
            text = stringResource(R.string.ai_agent_local_model),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (downloadedModels.isEmpty()) {
            Text(
                text = stringResource(R.string.ai_agent_no_local_model),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            val options = downloadedModels.map { it.id to it.name }
            CompactOptionChips(
                options = options,
                currentValue = currentLocalModel,
                maxLines = 2,
                onSelected = onLocalModelSelected
            )
        }

        // 跳转到模型管理器
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNavigateToModelManager)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.model_center),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.model_center_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Outlined.CloudDownload,
                contentDescription = null,
                modifier = Modifier
                    .size(22.dp)
                    .padding(start = 4.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun AiAgentModelManagerRow(
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = stringResource(R.string.model_center),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.model_center_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            modifier = Modifier
                .size(20.dp)
                .padding(start = 4.dp)
        )
    }
}

@Composable
private fun AiAgentRemoteModelsSection(
    configsJson: String,
    onConfigsChange: (String) -> Unit,
    selectedModelId: String,
    onSelectedModelChange: (String) -> Unit
) {
    val configs = remember(configsJson) {
        if (configsJson.isNotBlank()) {
            com.picme.domain.model.RemoteModelConfigs.fromJson(configsJson)
        } else {
            com.picme.domain.model.RemoteModelConfigs()
        }
    }
    var showAddDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.remote_models),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { showAddDialog = true }) {
                Text("+ ${stringResource(R.string.add_model)}")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        configs.configs.forEach { config ->
            RemoteModelConfigCard(
                config = config,
                isSelected = config.modelId == selectedModelId,
                onSelect = { onSelectedModelChange(config.modelId) },
                onConfigChange = { originalId, updatedConfig ->
                    val updated = configs.updateConfig(originalId, updatedConfig)
                    onConfigsChange(com.picme.domain.model.RemoteModelConfigs.toJson(updated))
                    // 如果修改了当前选中模型的ID，同步更新选中状态
                    if (originalId == selectedModelId && originalId != updatedConfig.modelId) {
                        onSelectedModelChange(updatedConfig.modelId)
                    }
                },
                onDelete = { modelId ->
                    val updated = configs.removeConfig(modelId)
                    onConfigsChange(com.picme.domain.model.RemoteModelConfigs.toJson(updated))
                    // 如果删除的是当前选中的模型，重置为第一个预置模型
                    if (modelId == selectedModelId) {
                        val firstPredefined = com.picme.domain.model.RemoteModelConfig.PREDEFINED_MODELS.firstOrNull()?.modelId
                        if (firstPredefined != null) {
                            onSelectedModelChange(firstPredefined)
                        }
                    }
                },
                isPredefined = com.picme.domain.model.RemoteModelConfig.PREDEFINED_MODELS.any { it.modelId == config.modelId }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // 添加自定义模型对话框
    if (showAddDialog) {
        AddCustomModelDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { newConfig ->
                val updated = configs.addConfig(newConfig)
                onConfigsChange(com.picme.domain.model.RemoteModelConfigs.toJson(updated))
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun RemoteModelConfigCard(
    config: com.picme.domain.model.RemoteModelConfig,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onConfigChange: (String, com.picme.domain.model.RemoteModelConfig) -> Unit,
    onDelete: (String) -> Unit,
    isPredefined: Boolean
) {
    var isEditing by remember { mutableStateOf(false) }
    var editModelId by remember { mutableStateOf(config.modelId) }
    var editApiKey by remember { mutableStateOf(config.apiKey) }
    var editBaseUrl by remember { mutableStateOf(config.baseUrl) }
    var editProtocol by remember { mutableStateOf(config.protocol) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        onClick = onSelect
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = onSelect
                    )
                    Text(
                        text = config.modelId,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (config.isConfigured) {
                        Text(
                            text = stringResource(R.string.configured),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.not_configured),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!isPredefined) {
                        TextButton(
                            onClick = { onDelete(config.modelId) },
                            modifier = Modifier.padding(0.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.delete),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isEditing) {
                // Model ID (editable for custom models)
                if (!isPredefined) {
                    androidx.compose.material3.OutlinedTextField(
                        value = editModelId,
                        onValueChange = { editModelId = it },
                        label = { Text(stringResource(R.string.model_id)) },
                        placeholder = { Text("e.g. gpt-4o") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                // Protocol selection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.protocol),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    com.picme.domain.model.RemoteProtocol.entries.forEach { protocol ->
                        FilterChip(
                            selected = editProtocol == protocol,
                            onClick = { editProtocol = protocol },
                            label = { Text(protocol.name) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = editBaseUrl,
                    onValueChange = { editBaseUrl = it },
                    label = { Text(stringResource(R.string.base_url)) },
                    placeholder = { Text(config.baseUrl) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = editApiKey,
                    onValueChange = { editApiKey = it },
                    label = { Text(stringResource(R.string.api_key)) },
                    placeholder = { Text(stringResource(R.string.api_key_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        onConfigChange(
                            config.modelId,
                            config.copy(
                                modelId = editModelId.trim(),
                                apiKey = editApiKey.trim(),
                                baseUrl = editBaseUrl.trim(),
                                protocol = editProtocol
                            )
                        )
                        isEditing = false
                    }) {
                        Text(stringResource(R.string.save))
                    }
                    TextButton(onClick = {
                        editModelId = config.modelId
                        editApiKey = config.apiKey
                        editBaseUrl = config.baseUrl
                        editProtocol = config.protocol
                        isEditing = false
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isEditing = true },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "${config.protocol.name} · ${config.baseUrl.ifBlank { stringResource(R.string.base_url_not_set) }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (config.apiKey.isNotBlank()) {
                                stringResource(R.string.api_key_set)
                            } else {
                                stringResource(R.string.api_key_not_set)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (config.apiKey.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Text(
                        text = stringResource(R.string.edit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun AddCustomModelDialog(
    onDismiss: () -> Unit,
    onConfirm: (com.picme.domain.model.RemoteModelConfig) -> Unit
) {
    var modelId by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(com.picme.domain.model.RemoteProtocol.OPENAI) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_custom_model)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text(stringResource(R.string.model_id)) },
                    placeholder = { Text("e.g. gpt-4o, claude-3-5-sonnet") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.protocol),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    com.picme.domain.model.RemoteProtocol.entries.forEach { p ->
                        FilterChip(
                            selected = protocol == p,
                            onClick = { protocol = p },
                            label = { Text(p.name) }
                        )
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.base_url)) },
                    placeholder = { Text("https://api.example.com/v1/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                androidx.compose.material3.OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.api_key)) },
                    placeholder = { Text(stringResource(R.string.api_key_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (modelId.isNotBlank()) {
                        onConfirm(
                            com.picme.domain.model.RemoteModelConfig(
                                modelId = modelId.trim(),
                                protocol = protocol,
                                baseUrl = baseUrl.trim(),
                                apiKey = apiKey.trim()
                            )
                        )
                    }
                },
                enabled = modelId.isNotBlank()
            ) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun SettingsSection(
    title: String,
    description: String? = null,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 2.dp)
        )
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        content()
        HorizontalDivider(
            modifier = Modifier.padding(top = 2.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun themeSelection(
    currentMode: ThemeMode,
    onModeSelected: (ThemeMode) -> Unit
) {
    val options = listOf(
        ThemeMode.SYSTEM to stringResource(R.string.system_default),
        ThemeMode.LIGHT to stringResource(R.string.light),
        ThemeMode.DARK to stringResource(R.string.dark)
    )

    CompactOptionChips(
        options = options,
        currentValue = currentMode,
        maxLines = 1,
        onSelected = onModeSelected
    )
}

@Composable
private fun languageSelection(
    currentLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit
) {
    val options = listOf(
        AppLanguage.SYSTEM to stringResource(R.string.system_default),
        AppLanguage.ENGLISH to stringResource(R.string.english),
        AppLanguage.CHINESE to stringResource(R.string.chinese),
        AppLanguage.TRADITIONAL_CHINESE to stringResource(R.string.traditional_chinese)
    )

    CompactOptionChips(
        options = options,
        currentValue = currentLanguage,
        maxLines = 2,
        onSelected = onLanguageSelected
    )
}



@Composable
private fun FaceDetectProfileSelection(
    currentProfile: FaceDetectIntervalProfile,
    onProfileSelected: (FaceDetectIntervalProfile) -> Unit
) {
    val options = listOf(
        FaceDetectIntervalProfile.CONSERVATIVE to stringResource(R.string.face_detect_profile_conservative),
        FaceDetectIntervalProfile.BALANCED to stringResource(R.string.face_detect_profile_balanced),
        FaceDetectIntervalProfile.AGGRESSIVE to stringResource(R.string.face_detect_profile_aggressive)
    )

    Text(
        text = stringResource(R.string.face_detect_profile_title),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 0.dp)
    )

    CompactOptionChips(
        options = options,
        currentValue = currentProfile,
        maxLines = 1,
        onSelected = onProfileSelected
    )
}

@Composable
private fun ShaderDebugModeSelection(
    currentMode: Int,
    onModeSelected: (Int) -> Unit
) {
    val options = listOf(
        0 to "Normal",
        1 to "Skin Mask",
        2 to "Warp Offset",
        3 to "BigEye Radius",
        4 to "ThinFace Radius",
        5 to "All Warp"
    )

    Text(
        text = "Shader Debug Mode",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 0.dp)
    )

    CompactOptionChips(
        options = options,
        currentValue = currentMode,
        maxLines = 2,
        onSelected = onModeSelected
    )
}

@Composable
private fun StageConfigSection(
    stage: DetectionStage,
    config: StageConfig,
    onModelTypeSelected: (DetectionModelType) -> Unit,
    onDevicePreferenceSelected: (InferenceDevicePreference) -> Unit,
    onNavigateToModelManager: () -> Unit,
    isModelDownloaded: (DetectionModelType) -> Boolean,
    getModelId: (DetectionModelType, DetectionStage) -> String?,
    downloadModel: (String, ModelConfig) -> Unit,
    downloadStates: Map<String, com.picme.data.download.DownloadState>,
    allModels: List<ModelConfig>
) {
    val context = LocalContext.current
    val title = when (stage) {
        DetectionStage.ROI -> stringResource(R.string.stage_roi_title)
        DetectionStage.LANDMARK -> stringResource(R.string.stage_landmark_title)
    }
    val description = when (stage) {
        DetectionStage.ROI -> stringResource(R.string.stage_roi_desc)
        DetectionStage.LANDMARK -> stringResource(R.string.stage_landmark_desc)
    }

    SettingsSection(
        title = title,
        description = description
    ) {
        // 模型选择
        Text(
            text = stringResource(R.string.model_type),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 2.dp)
        )
        modelTypeSelection(
            currentType = config.modelType,
            stage = stage,
            onTypeSelected = onModelTypeSelected,
            isModelDownloaded = isModelDownloaded,
            getModelId = getModelId,
            downloadModel = downloadModel,
            downloadStates = downloadStates,
            allModels = allModels
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 设备偏好选择
        Text(
            text = stringResource(R.string.inference_device_preference),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 2.dp)
        )
        inferenceDevicePreferenceSelection(
            currentPreference = config.devicePreference,
            onPreferenceSelected = onDevicePreferenceSelected
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 模型管理入口
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNavigateToModelManager)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.model_center),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.model_center_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Outlined.CloudDownload,
                contentDescription = null,
                modifier = Modifier
                    .size(22.dp)
                    .padding(start = 4.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun modelTypeSelection(
    currentType: DetectionModelType,
    stage: DetectionStage,
    onTypeSelected: (DetectionModelType) -> Unit,
    isModelDownloaded: (DetectionModelType) -> Boolean,
    getModelId: (DetectionModelType, DetectionStage) -> String?,
    downloadModel: (String, ModelConfig) -> Unit,
    downloadStates: Map<String, com.picme.data.download.DownloadState>,
    allModels: List<ModelConfig>
) {
    val context = LocalContext.current

    val options = when (stage) {
        DetectionStage.ROI -> listOf(
            DetectionModelType.MEDIAPIPE to stringResource(R.string.model_mediapipe),
            DetectionModelType.DET_500M_MNN to stringResource(R.string.model_det10g_mnn),
            DetectionModelType.DET_500M_NCNN to stringResource(R.string.model_det10g_ncnn)
        )
        DetectionStage.LANDMARK -> listOf(
            DetectionModelType.MEDIAPIPE to stringResource(R.string.model_mediapipe),
            DetectionModelType.FACE_2D106_MNN to stringResource(R.string.model_2d106_mnn),
            DetectionModelType.FACE_2D106_NCNN to stringResource(R.string.model_2d106_ncnn)
        )
    }

    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        maxLines = 2
    ) {
        options.forEach { (modelType, label) ->
            val downloaded = isModelDownloaded(modelType)
            val isMediaPipe = modelType == DetectionModelType.MEDIAPIPE
            val modelId = getModelId(modelType, stage)
            val downloadState = modelId?.let { downloadStates[it] }
            val isDownloading = downloadState?.status == com.picme.data.download.DownloadStatus.DOWNLOADING
            val downloadProgress = if (isDownloading && downloadState.totalBytes > 0) {
                (downloadState.downloadedBytes.toFloat() / downloadState.totalBytes * 100).toInt()
            } else 0

            FilterChip(
                selected = modelType == currentType,
                onClick = {
                    if (downloaded || isMediaPipe) {
                        onTypeSelected(modelType)
                    } else if (!isDownloading) {
                        val mId = modelId
                        val modelConfig = mId?.let { id -> allModels.find { it.id == id } }
                        if (modelConfig != null && mId != null) {
                            downloadModel(mId, modelConfig)
                            android.widget.Toast.makeText(
                                context,
                                "开始下载 ${modelConfig.name}，下载完成后自动生效",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                "模型配置未找到，请先进入模型管理页面下载",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall
                        )
                        when {
                            isDownloading -> {
                                Text(
                                    text = "${downloadProgress}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            !downloaded && !isMediaPipe -> {
                                Icon(
                                    imageVector = Icons.Outlined.CloudDownload,
                                    contentDescription = "未下载",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                ),
                enabled = !isDownloading
            )
        }
    }
}

@Composable
private fun inferenceDevicePreferenceSelection(
    currentPreference: InferenceDevicePreference,
    onPreferenceSelected: (InferenceDevicePreference) -> Unit
) {
    val options = listOf(
        InferenceDevicePreference.AUTO to stringResource(R.string.device_preference_auto),
        InferenceDevicePreference.FORCE_CPU to stringResource(R.string.device_preference_force_cpu),
        InferenceDevicePreference.FORCE_GPU to stringResource(R.string.device_preference_force_gpu)
    )

    CompactOptionChips(
        options = options,
        currentValue = currentPreference,
        maxLines = 1,
        onSelected = onPreferenceSelected
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> CompactOptionChips(
    options: List<Pair<T, String>>,
    currentValue: T,
    maxLines: Int,
    onSelected: (T) -> Unit
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        maxLines = maxLines
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == currentValue,
                onClick = { onSelected(value) },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}

@Composable
private fun DebugOptionRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = { enabled -> onCheckedChange(enabled) }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    PicMeTheme {
        settingsContent(
            themeMode = ThemeMode.SYSTEM,
            appLanguage = AppLanguage.ENGLISH,
            debugUiEnabled = true,
            showCameraInfoInPreview = false,
            showFaceDebugOverlay = false,
        showLogOverlay = false,
        faceDetectionLandmarkModeEnabled = true,

            adaptiveFaceDetectionIntervalEnabled = true,
            faceDetectIntervalProfile = FaceDetectIntervalProfile.BALANCED,
            debugShaderMode = 0,
            roiStageConfig = StageConfig.defaultRoi(),
            landmarkStageConfig = StageConfig.defaultLandmark(),
            onThemeModeSelected = {},
            onAppLanguageSelected = {},
            onDebugUiEnabledChange = {},
            onShowCameraInfoInPreviewChange = {},
            onShowFaceDebugOverlayChange = {},
            onShowLogOverlayChange = {},
            onFaceDetectionLandmarkModeEnabledChange = {},
            onAdaptiveFaceDetectionIntervalEnabledChange = {},
            onFaceDetectIntervalProfileSelected = {},
            onDebugShaderModeSelected = {},
            onRoiModelTypeSelected = {},
            onRoiDevicePreferenceSelected = {},
            onLandmarkModelTypeSelected = {},
            onLandmarkDevicePreferenceSelected = {},
            aiAgentMode = AiAgentMode.LOCAL,
            onAiAgentModeChange = {},
            aiAgentLocalModel = "",
            onAiAgentLocalModelChange = {},
            aiAgentRemoteModelConfigs = "",
            onAiAgentRemoteModelConfigsChange = {},
            aiAgentSelectedRemoteModel = "kimi-for-coding",
            onAiAgentSelectedRemoteModelChange = {},
            aiAgentForceRemote = false,
            onAiAgentForceRemoteChange = {},
            voiceCommandMode = VoiceCommandMode.DISABLED,
            onVoiceCommandModeChange = {},
            localAsrModel = "",
            onLocalAsrModelChange = {},
            onNavigateToModelCenter = {},
            isModelDownloaded = { true },
            getModelId = { _, _ -> null },
            downloadModel = { _, _ -> },
            downloadStates = emptyMap(),
            allModels = emptyList(),
            logModuleConfig = com.picme.domain.model.LogModuleConfig.default(),
            onLogModuleConfigChange = {},
            onNavigateBack = {}
        )
    }
}
