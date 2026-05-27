package com.picme.features.settings

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
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.picme.R
import com.picme.core.designsystem.PicMeTheme
import com.picme.data.download.LlmModelDownloadManager
import com.picme.domain.model.AiAgentMode
import com.picme.domain.model.AppLanguage
import com.picme.domain.model.DetectionModelType
import com.picme.domain.model.DetectionStage
import com.picme.domain.model.FaceDetectIntervalProfile
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.domain.model.InferenceDevicePreference
import com.picme.domain.model.InferenceEngineType
import com.picme.domain.model.StageConfig
import com.picme.domain.model.ThemeMode
import com.picme.domain.model.VoiceCommandMode
import com.picme.features.settings.agent.SettingsAgentPanel
import com.picme.features.settings.agent.rememberSettingsAgentIntegration

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToLlmModelManager: () -> Unit = {}
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
    val faceDetectionEngineMode by viewModel.faceDetectionEngineMode.collectAsState()
    val faceDetectionLandmarkModeEnabled by viewModel.faceDetectionLandmarkModeEnabled.collectAsState()
    val adaptiveFaceDetectionIntervalEnabled by viewModel.adaptiveFaceDetectionIntervalEnabled.collectAsState()
    val faceDetectIntervalProfile by viewModel.faceDetectIntervalProfile.collectAsState()
    val debugShaderMode by viewModel.debugShaderMode.collectAsState()
    val roiStageConfig by viewModel.roiStageConfig.collectAsState()
    val landmarkStageConfig by viewModel.landmarkStageConfig.collectAsState()
    val aiAgentMode by viewModel.aiAgentMode.collectAsState()
    val aiAgentLocalModel by viewModel.aiAgentLocalModel.collectAsState()
    val aiAgentApiKey by viewModel.aiAgentApiKey.collectAsState()
    val aiAgentModel by viewModel.aiAgentModel.collectAsState()
    val aiAgentBaseUrl by viewModel.aiAgentBaseUrl.collectAsState()
    val voiceCommandMode by viewModel.voiceCommandMode.collectAsState()
    val localAsrModel by viewModel.localAsrModel.collectAsState()

    // ===== Agent 集成 =====
    val agentIntegration = rememberSettingsAgentIntegration(
        context = context,
        onNavigateTo = { destination ->
            when (destination) {
                "camera" -> onNavigateBack()
                "gallery" -> onNavigateBack()
            }
        },
        onNavigateBack = onNavigateBack
    )

    // 注册 Settings Capability
    agentIntegration.registerCapabilities(
        viewModel = viewModel,
        onNavigateToModelManager = onNavigateToLlmModelManager
    )

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
        faceDetectionEngineMode = faceDetectionEngineMode,
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
        onFaceDetectionEngineModeSelected = { mode ->
            viewModel.setFaceDetectionEngineMode(mode)
        },
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
        onRoiEngineTypeSelected = { type -> viewModel.setRoiEngineType(type) },
        onRoiDevicePreferenceSelected = { preference -> viewModel.setRoiDevicePreference(preference) },
        onLandmarkModelTypeSelected = { type -> viewModel.setLandmarkModelType(type) },
        onLandmarkEngineTypeSelected = { type -> viewModel.setLandmarkEngineType(type) },
        onLandmarkDevicePreferenceSelected = { preference -> viewModel.setLandmarkDevicePreference(preference) },
        aiAgentMode = aiAgentMode,
        onAiAgentModeChange = { mode -> viewModel.setAiAgentMode(mode) },
        aiAgentLocalModel = aiAgentLocalModel,
        onAiAgentLocalModelChange = { modelId -> viewModel.setAiAgentLocalModel(modelId) },
        aiAgentApiKey = aiAgentApiKey,
        onAiAgentApiKeyChange = { key -> viewModel.setAiAgentApiKey(key) },
        aiAgentModel = aiAgentModel,
        onAiAgentModelChange = { model -> viewModel.setAiAgentModel(model) },
        aiAgentBaseUrl = aiAgentBaseUrl,
        onAiAgentBaseUrlChange = { url -> viewModel.setAiAgentBaseUrl(url) },
        voiceCommandMode = voiceCommandMode,
        onVoiceCommandModeChange = { mode -> viewModel.setVoiceCommandMode(mode) },
        localAsrModel = localAsrModel,
        onLocalAsrModelChange = { modelId -> viewModel.setLocalAsrModel(modelId) },
        onNavigateToLlmModelManager = onNavigateToLlmModelManager,
        onNavigateBack = onNavigateBack
    )

        // Agent Panel（浮动在内容之上）
        SettingsAgentPanel(
            pageContext = pageContext,
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
    faceDetectionEngineMode: FaceDetectionEngineMode,
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
    aiAgentApiKey: String,
    onAiAgentApiKeyChange: (String) -> Unit,
    aiAgentModel: String,
    onAiAgentModelChange: (String) -> Unit,
    aiAgentBaseUrl: String,
    onAiAgentBaseUrlChange: (String) -> Unit,
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
    onFaceDetectionEngineModeSelected: (FaceDetectionEngineMode) -> Unit,
    onFaceDetectionLandmarkModeEnabledChange: (Boolean) -> Unit,
    onAdaptiveFaceDetectionIntervalEnabledChange: (Boolean) -> Unit,
    onFaceDetectIntervalProfileSelected: (FaceDetectIntervalProfile) -> Unit,
    onDebugShaderModeSelected: (Int) -> Unit,
    onRoiModelTypeSelected: (DetectionModelType) -> Unit,
    onRoiEngineTypeSelected: (InferenceEngineType) -> Unit,
    onRoiDevicePreferenceSelected: (InferenceDevicePreference) -> Unit,
    onLandmarkModelTypeSelected: (DetectionModelType) -> Unit,
    onLandmarkEngineTypeSelected: (InferenceEngineType) -> Unit,
    onLandmarkDevicePreferenceSelected: (InferenceDevicePreference) -> Unit,
    onNavigateToLlmModelManager: () -> Unit,
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
                // 模式选择：本地模型 / 远程模型
                AiAgentModeSelection(
                    currentMode = aiAgentMode,
                    onModeSelected = onAiAgentModeChange
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
                            onNavigateToModelManager = onNavigateToLlmModelManager
                        )
                    }
                    AiAgentMode.REMOTE -> {
                        AiAgentBaseUrlSelection(
                            currentBaseUrl = aiAgentBaseUrl,
                            onBaseUrlSelected = onAiAgentBaseUrlChange
                        )
                        AiAgentModelSelection(
                            currentModel = aiAgentModel,
                            onModelSelected = onAiAgentModelChange
                        )
                        AiAgentApiKeyRow(
                            apiKey = aiAgentApiKey,
                            onApiKeyChange = onAiAgentApiKeyChange
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
                        onModelSelected = onLocalAsrModelChange
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 人脸检测算法模式选择
            SettingsSection(
                title = stringResource(R.string.face_detection),
                description = stringResource(R.string.face_detection_desc)
            ) {
                FaceDetectionEngineSelection(
                    currentMode = faceDetectionEngineMode,
                    onModeSelected = onFaceDetectionEngineModeSelected
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ROI 阶段配置
            StageConfigSection(
                stage = DetectionStage.ROI,
                config = roiStageConfig,
                onModelTypeSelected = onRoiModelTypeSelected,
                onEngineTypeSelected = onRoiEngineTypeSelected,
                onDevicePreferenceSelected = onRoiDevicePreferenceSelected
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Landmark 阶段配置
            StageConfigSection(
                stage = DetectionStage.LANDMARK,
                config = landmarkStageConfig,
                onModelTypeSelected = onLandmarkModelTypeSelected,
                onEngineTypeSelected = onLandmarkEngineTypeSelected,
                onDevicePreferenceSelected = onLandmarkDevicePreferenceSelected
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
    onModelSelected: (String) -> Unit
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
        Text(
            text = stringResource(R.string.local_asr_model),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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

@Composable
private fun AiAgentLocalModelSection(
    currentLocalModel: String,
    onLocalModelSelected: (String) -> Unit,
    onNavigateToModelManager: () -> Unit
) {
    val context = LocalContext.current
    val downloadManager = remember { LlmModelDownloadManager(context) }

    var downloadedModels by remember { mutableStateOf<List<com.picme.data.download.ModelConfig>>(emptyList()) }

    LaunchedEffect(Unit) {
        downloadedModels = downloadManager.getDownloadedModels()
    }

    // 当界面重新获得焦点时刷新已下载模型列表（从模型管理器返回后）
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            downloadedModels = downloadManager.getDownloadedModels()
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
                    text = stringResource(R.string.ai_model_manager),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.ai_model_manager_desc),
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
private fun AiAgentApiKeyRow(
    apiKey: String,
    onApiKeyChange: (String) -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(apiKey) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.ai_agent_api_key),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (isEditing) {
            androidx.compose.material3.OutlinedTextField(
                value = editText,
                onValueChange = { editText = it },
                placeholder = { Text(stringResource(R.string.ai_agent_api_key_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    Row {
                        TextButton(onClick = {
                            onApiKeyChange(editText.trim())
                            isEditing = false
                        }) {
                            Text(stringResource(R.string.save))
                        }
                        TextButton(onClick = {
                            editText = apiKey
                            isEditing = false
                        }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isEditing = true },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (apiKey.isNotBlank()) {
                        stringResource(R.string.ai_agent_api_key_set)
                    } else {
                        stringResource(R.string.ai_agent_api_key_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (apiKey.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    text = stringResource(R.string.edit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
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
                text = stringResource(R.string.ai_model_manager),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.ai_model_manager_desc),
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
private fun AiAgentBaseUrlSelection(
    currentBaseUrl: String,
    onBaseUrlSelected: (String) -> Unit
) {
    val presets = listOf(
        "https://tokenhub.tencentmaas.com/v1/" to stringResource(R.string.ai_agent_base_url_tencent)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.ai_agent_base_url),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CompactOptionChips(
            options = presets,
            currentValue = currentBaseUrl,
            maxLines = 2,
            onSelected = onBaseUrlSelected
        )
    }
}

@Composable
private fun AiAgentModelSelection(
    currentModel: String,
    onModelSelected: (String) -> Unit
) {
    val models = listOf(
        "kimi-k2.6" to stringResource(R.string.ai_agent_model_k2_6)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.ai_agent_model),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CompactOptionChips(
            options = models,
            currentValue = currentModel,
            maxLines = 2,
            onSelected = onModelSelected
        )
    }
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
private fun FaceDetectionEngineSelection(
    currentMode: FaceDetectionEngineMode,
    onModeSelected: (FaceDetectionEngineMode) -> Unit
) {
    val options = listOf(
        FaceDetectionEngineMode.MEDIAPIPE to stringResource(R.string.face_detection_engine_mode_mediapipe),
        FaceDetectionEngineMode.INSIGHTFACE to stringResource(R.string.face_detection_engine_mode_insightface),
        FaceDetectionEngineMode.CUSTOM to stringResource(R.string.face_detection_engine_mode_custom)
    )

    Text(
        text = stringResource(R.string.face_detection_engine_title),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 0.dp)
    )

    CompactOptionChips(
        options = options,
        currentValue = currentMode,
        maxLines = 2,
        onSelected = onModeSelected
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
    onEngineTypeSelected: (InferenceEngineType) -> Unit,
    onDevicePreferenceSelected: (InferenceDevicePreference) -> Unit
) {
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
            onTypeSelected = onModelTypeSelected
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 推理引擎选择
        Text(
            text = stringResource(R.string.inference_engine),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 2.dp)
        )
        inferenceEngineSelection(
            currentType = config.engineType,
            onTypeSelected = onEngineTypeSelected
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
    }
}

@Composable
private fun modelTypeSelection(
    currentType: DetectionModelType,
    stage: DetectionStage,
    onTypeSelected: (DetectionModelType) -> Unit
) {
    val options = when (stage) {
        DetectionStage.ROI -> listOf(
            DetectionModelType.MEDIAPIPE to stringResource(R.string.model_mediapipe),
            DetectionModelType.INSIGHTFACE_DET10G to stringResource(R.string.model_insightface_det10g)
        )
        DetectionStage.LANDMARK -> listOf(
            DetectionModelType.MEDIAPIPE to stringResource(R.string.model_mediapipe),
            DetectionModelType.INSIGHTFACE_2D106 to stringResource(R.string.model_insightface_2d106)
        )
    }

    CompactOptionChips(
        options = options,
        currentValue = currentType,
        maxLines = 1,
        onSelected = onTypeSelected
    )
}

@Composable
private fun inferenceEngineSelection(
    currentType: InferenceEngineType,
    onTypeSelected: (InferenceEngineType) -> Unit
) {
    val options = listOf(
        InferenceEngineType.ONNX to stringResource(R.string.inference_engine_onnx),
        InferenceEngineType.MNN to stringResource(R.string.inference_engine_mnn),
        InferenceEngineType.NCNN to stringResource(R.string.inference_engine_ncnn),
        InferenceEngineType.TFLITE to stringResource(R.string.inference_engine_tflite)
    )

    CompactOptionChips(
        options = options,
        currentValue = currentType,
        maxLines = 2,
        onSelected = onTypeSelected
    )
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
        faceDetectionEngineMode = FaceDetectionEngineMode.INSIGHTFACE,
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
            onFaceDetectionEngineModeSelected = {},
            onFaceDetectionLandmarkModeEnabledChange = {},
            onAdaptiveFaceDetectionIntervalEnabledChange = {},
            onFaceDetectIntervalProfileSelected = {},
            onDebugShaderModeSelected = {},
            onRoiModelTypeSelected = {},
            onRoiEngineTypeSelected = {},
            onRoiDevicePreferenceSelected = {},
            onLandmarkModelTypeSelected = {},
            onLandmarkEngineTypeSelected = {},
            onLandmarkDevicePreferenceSelected = {},
            aiAgentMode = AiAgentMode.LOCAL,
            onAiAgentModeChange = {},
            aiAgentLocalModel = "",
            onAiAgentLocalModelChange = {},
            aiAgentApiKey = "",
            onAiAgentApiKeyChange = {},
            aiAgentModel = "moonshot-v1-8k",
            onAiAgentModelChange = {},
            aiAgentBaseUrl = "",
            onAiAgentBaseUrlChange = {},
            voiceCommandMode = VoiceCommandMode.DISABLED,
            onVoiceCommandModeChange = {},
            localAsrModel = "",
            onLocalAsrModelChange = {},
            onNavigateToLlmModelManager = {},
            onNavigateBack = {}
        )
    }
}
