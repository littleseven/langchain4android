package com.mamba.picme.features.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mamba.picme.R
import com.mamba.picme.agent.core.api.policy.AiAgentInferencePreference
import com.mamba.picme.agent.core.api.policy.AiAgentMode
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.designsystem.PicMeTheme
import com.mamba.picme.data.download.DownloadState
import com.mamba.picme.data.download.ModelConfig
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.model.DetectionModelType
import com.mamba.picme.domain.model.DetectionStage
import com.mamba.picme.domain.model.FaceDetectIntervalProfile
import com.mamba.picme.domain.model.FaceDetectionEngineMode
import com.mamba.picme.domain.model.InferenceDevicePreference
import com.mamba.picme.domain.model.LogModule
import com.mamba.picme.domain.model.LogModuleConfig
import com.mamba.picme.domain.model.StageConfig
import com.mamba.picme.domain.model.ThemeMode
import com.mamba.picme.domain.model.VoiceCommandMode
import com.mamba.picme.features.common.chat.rememberAgentChatConfig
import com.mamba.picme.features.settings.agent.SettingsAgentPanel
import com.mamba.picme.features.settings.agent.rememberSettingsAgentIntegration
import com.mamba.picme.features.settings.capability.SettingsCapability
import com.mamba.picme.service.accessibility.AccessibilityController
import com.mamba.picme.service.accessibility.PicMeAccessibilityService
import com.mamba.picme.service.chat.FloatingChatBubbleService
import com.mamba.picme.util.permission.BatteryOptimizationUtils
import com.mamba.picme.util.permission.MiuiPermissionUtils
import kotlinx.coroutines.delay

private const val TAG = "Settings"

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToModelCenter: (String) -> Unit = {},
) {
    val view = LocalView.current
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val insetsController = WindowCompat.getInsetsController(window, view)

        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
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
    val aiAgentInferencePreference by viewModel.aiAgentInferencePreference.collectAsState()
    val aiAgentL1CacheEnabled by viewModel.aiAgentL1CacheEnabled.collectAsState()
    val aiAgentLocalUseOpencl by viewModel.aiAgentLocalUseOpencl.collectAsState()
    val voiceCommandMode by viewModel.voiceCommandMode.collectAsState()
    val localAsrModel by viewModel.localAsrModel.collectAsState()
    val localKwsModel by viewModel.localKwsModel.collectAsState()
    val logModuleConfig by viewModel.logModuleConfig.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val allModels by viewModel.allModels.collectAsState()

    // 飞书远程控制
    val feishuAppId by viewModel.feishuAppId.collectAsState()
    val feishuAppSecret by viewModel.feishuAppSecret.collectAsState()

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
            // 修复 P0-1：不应该完全释放 voiceCoordinator，因为它在多个 Chat 屏幕间共享
            // 而应该只进行"软释放"（停止监听但保留引擎）
            Logger.i(TAG, "Settings screen disposed - performing soft release of voice coordinator")
            voiceCoordinator.stopWakeWordListening()
            voiceCoordinator.stopPushToTalk()
            // 注意：不调用 voiceCoordinator.release() 以避免破坏 ASR 引擎状态
        }
    }

    val agentIntegration = rememberSettingsAgentIntegration(
        context = context,
        onNavigateTo = { destination ->
            when (destination.lowercase()) {
                "camera", "gallery", "debug" -> onNavigateBack()
                "settings" -> { /* already on settings */ }
                "model_center" -> onNavigateToModelCenter("")
                "llm_model_manager" -> onNavigateToModelCenter("Chat")
                "asr_model_manager" -> onNavigateToModelCenter("Audio")
                "face_detection_model_manager" -> onNavigateToModelCenter("Vision")
                else -> Logger.w(TAG, "Unknown navigation destination: $destination")
            }
        },
        onNavigateBack = onNavigateBack
    )

    DisposableEffect(Unit) {
        Logger.i(TAG, "Binding SettingsCapability delegate")
        val settingsCapability = SettingsCapability.getInstance()
        settingsCapability.bindDelegate(object : SettingsCapability.Delegate {
            override fun onChangeTheme(theme: ThemeMode) {
                viewModel.setThemeMode(theme)
            }
            override fun onChangeLanguage(language: AppLanguage) {
                viewModel.setAppLanguage(language)
            }
            override fun onDownloadModel(modelId: String) {
                onNavigateToModelCenter("")
            }
            override fun onSwitchFaceEngine(engine: FaceDetectionEngineMode) {
                viewModel.setFaceDetectionEngineMode(engine)
            }
            override fun onToggleSetting(key: String, enabled: Boolean) {
                when (key) {
                    "debug_ui" -> viewModel.setDebugUiEnabled(enabled)
                    "camera_info" -> viewModel.setShowCameraInfoInPreview(enabled)
                    "voice_command" -> viewModel.setVoiceCommandMode(
                        if (enabled) VoiceCommandMode.WAKE_WORD else VoiceCommandMode.DISABLED
                    )
                    "agent_mode" -> viewModel.setAiAgentMode(
                        if (enabled) AiAgentMode.LOCAL else AiAgentMode.OFF
                    )
                    "agent_local_opencl" -> viewModel.setAiAgentLocalUseOpencl(enabled)
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

    val pageContext = agentIntegration.buildPageContext()

    Box(modifier = Modifier.fillMaxSize()) {
        SettingsContent(
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
            onThemeModeSelected = { viewModel.setThemeMode(it) },
            onAppLanguageSelected = { viewModel.setAppLanguage(it) },
            onDebugUiEnabledChange = { viewModel.setDebugUiEnabled(it) },
            onShowCameraInfoInPreviewChange = { viewModel.setShowCameraInfoInPreview(it) },
            onShowFaceDebugOverlayChange = { viewModel.setShowFaceDebugOverlay(it) },
            onShowLogOverlayChange = { viewModel.setShowLogOverlay(it) },
            onFaceDetectionLandmarkModeEnabledChange = { viewModel.setFaceDetectionLandmarkModeEnabled(it) },
            onAdaptiveFaceDetectionIntervalEnabledChange = { viewModel.setAdaptiveFaceDetectionIntervalEnabled(it) },
            onFaceDetectIntervalProfileSelected = { viewModel.setFaceDetectIntervalProfile(it) },
            onDebugShaderModeSelected = { viewModel.setDebugShaderMode(it) },
            onRoiModelTypeSelected = { viewModel.setRoiModelType(it) },
            onRoiDevicePreferenceSelected = { viewModel.setRoiDevicePreference(it) },
            onLandmarkModelTypeSelected = { viewModel.setLandmarkModelType(it) },
            onLandmarkDevicePreferenceSelected = { viewModel.setLandmarkDevicePreference(it) },
            aiAgentMode = aiAgentMode,
            onAiAgentModeChange = { viewModel.setAiAgentMode(it) },
            aiAgentLocalModel = aiAgentLocalModel,
            onAiAgentLocalModelChange = { viewModel.setAiAgentLocalModel(it) },
            aiAgentRemoteModelConfigs = aiAgentRemoteModelConfigs,
            onAiAgentRemoteModelConfigsChange = { viewModel.setAiAgentRemoteModelConfigs(it) },
            aiAgentSelectedRemoteModel = aiAgentSelectedRemoteModel,
            onAiAgentSelectedRemoteModelChange = { viewModel.setAiAgentSelectedRemoteModel(it) },
            aiAgentInferencePreference = aiAgentInferencePreference,
            onAiAgentInferencePreferenceChange = { viewModel.setAiAgentInferencePreference(it) },
            aiAgentL1CacheEnabled = aiAgentL1CacheEnabled,
            onAiAgentL1CacheEnabledChange = { viewModel.setAiAgentL1CacheEnabled(it) },
            aiAgentLocalUseOpencl = aiAgentLocalUseOpencl,
            onAiAgentLocalUseOpenclChange = { viewModel.setAiAgentLocalUseOpencl(it) },
            voiceCommandMode = voiceCommandMode,
            onVoiceCommandModeChange = { viewModel.setVoiceCommandMode(it) },
            localAsrModel = localAsrModel,
            onLocalAsrModelChange = { viewModel.setLocalAsrModel(it) },
            localKwsModel = localKwsModel,
            onLocalKwsModelChange = { viewModel.setLocalKwsModel(it) },
            onNavigateToModelCenter = onNavigateToModelCenter,
            isModelDownloaded = viewModel::isModelDownloaded,
            getModelId = viewModel::getModelId,
            downloadModel = viewModel::downloadModel,
            downloadStates = downloadStates,
            allModels = allModels,
            logModuleConfig = logModuleConfig,
            onLogModuleConfigChange = viewModel::setLogModuleConfig,
            feishuAppId = feishuAppId,
            feishuAppSecret = feishuAppSecret,
            onFeishuAppIdChange = viewModel::setFeishuAppId,
            onFeishuAppSecretChange = viewModel::setFeishuAppSecret,
            onNavigateBack = onNavigateBack
        )

        SettingsAgentPanel(
            pageContext = pageContext,
            voiceCoordinator = voiceCoordinator,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
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
    aiAgentInferencePreference: AiAgentInferencePreference,
    onAiAgentInferencePreferenceChange: (AiAgentInferencePreference) -> Unit,
    aiAgentL1CacheEnabled: Boolean,
    onAiAgentL1CacheEnabledChange: (Boolean) -> Unit,
    aiAgentLocalUseOpencl: Boolean,
    onAiAgentLocalUseOpenclChange: (Boolean) -> Unit,
    voiceCommandMode: VoiceCommandMode,
    onVoiceCommandModeChange: (VoiceCommandMode) -> Unit,
    localAsrModel: String,
    onLocalAsrModelChange: (String) -> Unit,
    localKwsModel: String,
    onLocalKwsModelChange: (String) -> Unit,
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
    onNavigateToModelCenter: (String) -> Unit,
    isModelDownloaded: (DetectionModelType) -> Boolean,
    getModelId: (DetectionModelType, DetectionStage) -> String?,
    downloadModel: (String, ModelConfig) -> Unit,
    downloadStates: Map<String, DownloadState>,
    allModels: List<ModelConfig>,
    logModuleConfig: LogModuleConfig,
    onLogModuleConfigChange: (LogModuleConfig) -> Unit,
    feishuAppId: String,
    feishuAppSecret: String,
    onFeishuAppIdChange: (String) -> Unit,
    onFeishuAppSecretChange: (String) -> Unit,
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
            // AI Agent 配置
            SettingsSection(
                title = stringResource(R.string.ai_agent),
                description = stringResource(R.string.ai_agent_desc)
            ) {
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

                AiAgentModeSelection(
                    currentMode = aiAgentMode,
                    onModeSelected = onAiAgentModeChange
                )

                // 推理偏好选择（仅 LOCAL 模式下显示）
                if (aiAgentMode == AiAgentMode.LOCAL) {
                    InferencePreferenceSelection(
                        currentPreference = aiAgentInferencePreference,
                        onPreferenceSelected = onAiAgentInferencePreferenceChange
                    )
                    OpenClBackendSelection(
                        useOpencl = aiAgentLocalUseOpencl,
                        onToggle = onAiAgentLocalUseOpenclChange
                    )
                }

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

                DebugOptionRow(
                    title = stringResource(R.string.ai_agent_l1_cache),
                    checked = aiAgentL1CacheEnabled,
                    onCheckedChange = onAiAgentL1CacheEnabledChange
                )
                Text(
                    text = stringResource(R.string.ai_agent_l1_cache_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // AI 跨应用控制（无障碍）
            val context = LocalContext.current
            var isAccessibilityEnabled by remember {
                mutableStateOf(AccessibilityController.isServiceConnected())
            }
            var showAccessibilityPrivacyDialog by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                while (true) {
                    isAccessibilityEnabled = AccessibilityController.isServiceConnected()
                    kotlinx.coroutines.delay(1000)
                }
            }

            SettingsSection(
                title = stringResource(R.string.settings_accessibility_title),
                description = stringResource(R.string.settings_accessibility_summary)
            ) {
                SettingsClickableRow(
                    title = stringResource(R.string.settings_accessibility_title),
                    subtitle = stringResource(R.string.settings_accessibility_summary),
                    valueText = stringResource(
                        if (isAccessibilityEnabled) R.string.settings_accessibility_enabled else R.string.settings_accessibility_disabled
                    ),
                    onClick = {
                        if (!isAccessibilityEnabled) {
                            showAccessibilityPrivacyDialog = true
                        } else {
                            context.startActivity(PicMeAccessibilityService.openSettingsIntent())
                        }
                    }
                )
            }

            if (showAccessibilityPrivacyDialog) {
                AlertDialog(
                    onDismissRequest = { showAccessibilityPrivacyDialog = false },
                    title = { Text(stringResource(R.string.dialog_accessibility_privacy_title)) },
                    text = { Text(stringResource(R.string.dialog_accessibility_privacy_message)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showAccessibilityPrivacyDialog = false
                                context.startActivity(PicMeAccessibilityService.openSettingsIntent())
                            }
                        ) {
                            Text(stringResource(R.string.ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAccessibilityPrivacyDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 全局悬浮聊天入口
            var isFloatingChatRunning by remember {
                mutableStateOf(FloatingChatBubbleService.isRunning(context))
            }
            var hasOverlayPermission by remember {
                mutableStateOf(FloatingChatBubbleService.canDrawOverlays(context))
            }
            val overlayPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) {
                hasOverlayPermission = FloatingChatBubbleService.canDrawOverlays(context)
                if (hasOverlayPermission && !isFloatingChatRunning) {
                    FloatingChatBubbleService.start(context)
                    isFloatingChatRunning = true
                }
            }

            LaunchedEffect(Unit) {
                while (true) {
                    isFloatingChatRunning = FloatingChatBubbleService.isRunning(context)
                    hasOverlayPermission = FloatingChatBubbleService.canDrawOverlays(context)
                    delay(1000)
                }
            }

            SettingsSection(
                title = stringResource(R.string.floating_chat_title),
                description = stringResource(R.string.floating_chat_summary)
            ) {
                SettingsClickableRow(
                    title = stringResource(R.string.floating_chat_title),
                    subtitle = stringResource(R.string.floating_chat_summary),
                    valueText = stringResource(
                        if (isFloatingChatRunning) R.string.floating_chat_enabled else R.string.floating_chat_disabled
                    ),
                    onClick = {
                        when {
                            !hasOverlayPermission -> {
                                overlayPermissionLauncher.launch(
                                    FloatingChatBubbleService.openOverlayPermissionSettingsIntent(context)
                                )
                            }
                            isFloatingChatRunning -> {
                                FloatingChatBubbleService.stop(context)
                                isFloatingChatRunning = false
                            }
                            else -> {
                                FloatingChatBubbleService.start(context)
                                isFloatingChatRunning = true
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 后台运行权限（国产 ROM 尤其是 MIUI 需要）
            var isIgnoringBatteryOptimizations by remember {
                mutableStateOf(BatteryOptimizationUtils.isIgnoringBatteryOptimizations(context))
            }
            val isMiui = remember { MiuiPermissionUtils.isMiui() }

            LaunchedEffect(Unit) {
                while (true) {
                    isIgnoringBatteryOptimizations =
                        BatteryOptimizationUtils.isIgnoringBatteryOptimizations(context)
                    delay(1000)
                }
            }

            SettingsSection(
                title = stringResource(R.string.settings_background_permission_title),
                description = stringResource(R.string.settings_background_permission_summary)
            ) {
                SettingsClickableRow(
                    title = stringResource(R.string.settings_battery_optimization_title),
                    subtitle = stringResource(R.string.settings_battery_optimization_summary),
                    valueText = stringResource(
                        if (isIgnoringBatteryOptimizations) {
                            R.string.settings_battery_optimization_enabled
                        } else {
                            R.string.settings_battery_optimization_disabled
                        }
                    ),
                    onClick = {
                        if (!isIgnoringBatteryOptimizations) {
                            BatteryOptimizationUtils.requestIgnoreBatteryOptimizations(context)
                        }
                    }
                )

                if (isMiui) {
                    SettingsClickableRow(
                        title = stringResource(R.string.settings_miui_auto_start_title),
                        subtitle = stringResource(R.string.settings_miui_auto_start_summary),
                        valueText = stringResource(R.string.settings_miui_action_open),
                        onClick = { MiuiPermissionUtils.openMiuiAutoStart(context) }
                    )

                    SettingsClickableRow(
                        title = stringResource(R.string.settings_miui_permission_editor_title),
                        subtitle = stringResource(R.string.settings_miui_permission_editor_summary),
                        valueText = stringResource(R.string.settings_miui_action_open),
                        onClick = { MiuiPermissionUtils.openMiuiPermissionEditor(context) }
                    )
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

                    LocalKwsModelSelection(
                        currentModel = localKwsModel,
                        onModelSelected = onLocalKwsModelChange,
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

            // 人脸检测高级设置
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

            // 调试工具
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
                    ShaderDebugModeSelection(
                        currentMode = debugShaderMode,
                        onModeSelected = onDebugShaderModeSelected
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 主题模式
            SettingsSection(
                title = stringResource(R.string.theme_mode),
                description = stringResource(R.string.settings_theme_mode_desc)
            ) {
                ThemeSelection(
                    currentMode = themeMode,
                    onModeSelected = onThemeModeSelected
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 语言设置
            SettingsSection(
                title = stringResource(R.string.language),
                description = stringResource(R.string.settings_language_desc)
            ) {
                LanguageSelection(
                    currentLanguage = appLanguage,
                    onLanguageSelected = onAppLanguageSelected
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 飞书远程控制
            SettingsSection(
                title = "飞书远程控制",
                description = "配置飞书应用凭证以启用 IM 远程控制"
            ) {
                var showSecret by remember { mutableStateOf(false) }

                OutlinedTextField(
                    value = feishuAppId,
                    onValueChange = onFeishuAppIdChange,
                    label = { Text("App ID") },
                    placeholder = { Text("cli_xxxxxxxxxxxx") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )

                OutlinedTextField(
                    value = feishuAppSecret,
                    onValueChange = onFeishuAppSecretChange,
                    label = { Text("App Secret") },
                    placeholder = { Text("xxxxxxxxxxxxxxxxxxxx") },
                    singleLine = true,
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )

                DebugOptionRow(
                    title = "显示 App Secret",
                    checked = showSecret,
                    onCheckedChange = { showSecret = it }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

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

            Spacer(modifier = Modifier.height(10.dp))

            // 飞书远程控制
            SettingsSection(
                title = "飞书远程控制",
                description = "配置飞书自建应用的 App ID 和 App Secret，启用 IM 远程控制"
            ) {
                SettingsTextInputRow(
                    title = "App ID",
                    value = feishuAppId,
                    onValueChange = onFeishuAppIdChange,
                    placeholder = "飞书应用的 App ID"
                )
                SettingsTextInputRow(
                    title = "App Secret",
                    value = feishuAppSecret,
                    onValueChange = onFeishuAppSecretChange,
                    placeholder = "飞书应用的 App Secret",
                    isPassword = true
                )
            }
        }
    }
}

@Composable
private fun LogModuleConfigSection(
    config: LogModuleConfig,
    onConfigChange: (LogModuleConfig) -> Unit
) {
    CompactMultiSelectChips(
        options = LogModule.entries.map { it to it.displayName },
        isSelected = { module -> config.isEnabled(module) },
        maxLines = 3,
        onToggle = { module ->
            onConfigChange(config.toggle(module, !config.isEnabled(module)))
        }
    )
}

@Composable
private fun InferencePreferenceSelection(
    currentPreference: AiAgentInferencePreference,
    onPreferenceSelected: (AiAgentInferencePreference) -> Unit
) {
    val options = listOf(
        AiAgentInferencePreference.AUTO to stringResource(R.string.ai_agent_inference_auto),
        AiAgentInferencePreference.FORCE_LOCAL to stringResource(R.string.ai_agent_inference_force_local),
        AiAgentInferencePreference.FORCE_REMOTE to stringResource(R.string.ai_agent_inference_force_remote)
    )

    Text(
        text = stringResource(R.string.ai_agent_inference_preference),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 0.dp)
    )

    CompactOptionChips(
        options = options,
        currentValue = currentPreference,
        maxLines = 1,
        onSelected = onPreferenceSelected
    )

    Text(
        text = when (currentPreference) {
            AiAgentInferencePreference.AUTO -> stringResource(R.string.ai_agent_inference_auto_desc)
            AiAgentInferencePreference.FORCE_LOCAL -> stringResource(R.string.ai_agent_inference_force_local_desc)
            AiAgentInferencePreference.FORCE_REMOTE -> stringResource(R.string.ai_agent_inference_force_remote_desc)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
    )
}

@Composable
private fun OpenClBackendSelection(
    useOpencl: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val options = listOf(
        false to stringResource(R.string.ai_agent_local_backend_cpu),
        true to stringResource(R.string.ai_agent_local_backend_opencl)
    )

    Text(
        text = stringResource(R.string.ai_agent_local_backend),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 0.dp)
    )

    CompactOptionChips(
        options = options,
        currentValue = useOpencl,
        maxLines = 1,
        onSelected = onToggle
    )

    Text(
        text = stringResource(R.string.ai_agent_local_backend_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
    )
}

@Composable
private fun ThemeSelection(
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
private fun LanguageSelection(
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

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    PicMeTheme {
        SettingsContent(
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
            aiAgentSelectedRemoteModel = "deepseek-v4-flash",
            onAiAgentSelectedRemoteModelChange = {},
            aiAgentInferencePreference = AiAgentInferencePreference.FORCE_LOCAL,
            onAiAgentInferencePreferenceChange = {},
            aiAgentL1CacheEnabled = true,
            onAiAgentL1CacheEnabledChange = {},
            aiAgentLocalUseOpencl = false,
            onAiAgentLocalUseOpenclChange = {},
            voiceCommandMode = VoiceCommandMode.DISABLED,
            onVoiceCommandModeChange = {},
            localAsrModel = "",
            onLocalAsrModelChange = {},
            localKwsModel = "",
            onLocalKwsModelChange = {},
            onNavigateToModelCenter = {},
            isModelDownloaded = { true },
            getModelId = { _, _ -> null },
            downloadModel = { _, _ -> },
            downloadStates = emptyMap(),
            allModels = emptyList(),
            logModuleConfig = LogModuleConfig.default(),
            onLogModuleConfigChange = {},
            feishuAppId = "",
            feishuAppSecret = "",
            onFeishuAppIdChange = {},
            onFeishuAppSecretChange = {},
            onNavigateBack = {}
        )
    }
}
