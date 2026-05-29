package com.picme.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.picme.core.common.Logger
import com.picme.data.download.LlmModelDownloadManager
import com.picme.data.download.ModelConfig
import com.picme.domain.model.AiAgentMode
import com.picme.domain.model.AiAgentPrivacyLevel
import com.picme.domain.model.AppLanguage
import com.picme.domain.model.DetectionModelType
import com.picme.domain.model.DetectionStage
import com.picme.domain.model.FaceDetectIntervalProfile
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.domain.model.InferenceDevicePreference
import com.picme.domain.model.InferenceEngineType
import com.picme.domain.model.ModelCategory
import com.picme.domain.model.TagTranslations
import com.picme.domain.model.StageConfig
import com.picme.domain.model.ThemeMode
import com.picme.domain.model.VoiceCommandMode
import com.picme.domain.repository.UserSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: UserSettingsRepository,
    private val modelDownloadManager: LlmModelDownloadManager
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = repository.themeModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemeMode.SYSTEM
        )

    val appLanguage: StateFlow<AppLanguage> = repository.appLanguageFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppLanguage.SYSTEM
        )



    val debugUiEnabled: StateFlow<Boolean> = repository.debugUiEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val faceDetectionEngineMode: StateFlow<FaceDetectionEngineMode> = repository.faceDetectionEngineModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = FaceDetectionEngineMode.INSIGHTFACE
        )

    val faceDetectionLandmarkModeEnabled: StateFlow<Boolean> = repository.faceDetectionLandmarkModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val adaptiveFaceDetectionIntervalEnabled: StateFlow<Boolean> = repository.adaptiveFaceDetectionIntervalEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val faceDetectIntervalProfile: StateFlow<FaceDetectIntervalProfile> = repository.faceDetectIntervalProfileFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = FaceDetectIntervalProfile.BALANCED
        )

    val showCameraInfoInPreview: StateFlow<Boolean> = repository.showCameraInfoInPreviewFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val showFaceDebugOverlay: StateFlow<Boolean> = repository.showFaceDebugOverlayFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val showLogOverlay: StateFlow<Boolean> = repository.showLogOverlayFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    // Shader debug mode (not persisted, defaults to 0)
    private val _debugShaderMode = kotlinx.coroutines.flow.MutableStateFlow(0)
    val debugShaderMode: StateFlow<Int> = _debugShaderMode

    // ── 阶段独立配置（ROI / Landmark）────────────────────────
    val roiStageConfig: StateFlow<StageConfig> = repository.roiStageConfigFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StageConfig.defaultRoi()
        )

    val landmarkStageConfig: StateFlow<StageConfig> = repository.landmarkStageConfigFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StageConfig.defaultLandmark()
        )

    val aiAgentCodingApiKey: StateFlow<String> = repository.aiAgentCodingApiKeyFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    val aiAgentCodingModel: StateFlow<String> = repository.aiAgentCodingModelFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "kimi-for-coding"
        )

    val aiAgentCodingBaseUrl: StateFlow<String> = repository.aiAgentCodingBaseUrlFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "https://api.kimi.com/coding/v1/"
        )

    val aiAgentForceRemote: StateFlow<Boolean> = repository.aiAgentForceRemoteFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val aiAgentMode: StateFlow<AiAgentMode> = repository.aiAgentModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AiAgentMode.LOCAL
        )

    val aiAgentPrivacyLevel: StateFlow<AiAgentPrivacyLevel> = repository.aiAgentPrivacyLevelFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AiAgentPrivacyLevel.STRICT
        )

    val aiAgentLocalModel: StateFlow<String> = repository.aiAgentLocalModelFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    val voiceCommandMode: StateFlow<VoiceCommandMode> = repository.voiceCommandModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = VoiceCommandMode.DISABLED
        )

    val localAsrModel: StateFlow<String> = repository.localAsrModelFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    // 模型管理相关 Flow
    private val _allModels = MutableStateFlow<List<ModelConfig>>(emptyList())
    val allModels: StateFlow<List<ModelConfig>> = _allModels.asStateFlow()

    private val _downloadedModels = MutableStateFlow<List<ModelConfig>>(emptyList())
    val downloadedModels: StateFlow<List<ModelConfig>> = _downloadedModels.asStateFlow()

    private val _groupedModels = MutableStateFlow<Map<ModelCategory, List<ModelConfig>>>(emptyMap())
    val groupedModels: StateFlow<Map<ModelCategory, List<ModelConfig>>> = _groupedModels.asStateFlow()

    private val _tagTranslations = MutableStateFlow<TagTranslations>(emptyMap())
    val tagTranslations: StateFlow<TagTranslations> = _tagTranslations.asStateFlow()

    private val _categories = MutableStateFlow<List<ModelCategory>>(emptyList())
    val categories: StateFlow<List<ModelCategory>> = _categories.asStateFlow()

    private val _currentTab = MutableStateFlow(ModelCategory.ALL)
    val currentTab: StateFlow<ModelCategory> = _currentTab.asStateFlow()

    init {
        loadModels()
    }

    private fun loadModels() {
        viewModelScope.launch {
            try {
                val marketData = modelDownloadManager.loadMarketData()
                _allModels.value = marketData.models
                _tagTranslations.value = marketData.tagTranslations

                val grouped = marketData.groupByCategory()
                _groupedModels.value = grouped
                _categories.value = grouped.keys.toList()

                // 默认选中第一个分类
                if (_currentTab.value == ModelCategory.ALL && grouped.isNotEmpty()) {
                    _currentTab.value = grouped.keys.first()
                }

                val downloaded = modelDownloadManager.getDownloadedModels()
                _downloadedModels.value = downloaded

                Logger.i("PicMe:Settings", "Loaded ${marketData.models.size} models, " +
                    "categories: ${grouped.keys.map { it.tag }}")
            } catch (e: Exception) {
                Logger.e("PicMe:Settings", "Failed to load models", e)
            }
        }
    }

    /**
     * 切换 Tab
     */
    fun switchTab(tab: ModelCategory) {
        _currentTab.value = tab
    }

    /**
     * 获取当前 Tab 对应的模型列表
     */
    fun getCurrentTabModels(): List<ModelConfig> {
        return _groupedModels.value[_currentTab.value] ?: emptyList()
    }

    /**
     * 获取所有模型分类标签（用于 TabRow）
     * 返回 Map<分类标签, 中文翻译>
     */
    fun getModelTypeLabels(): Map<ModelCategory, String> {
        val translations = _tagTranslations.value
        return _categories.value.associateWith { category ->
            translations[category.tag] ?: category.tag
        }
    }

    /**
     * 刷新模型列表（强制从网络获取）
     */
    fun refreshModels() {
        viewModelScope.launch {
            try {
                val marketData = modelDownloadManager.refreshMarketData()
                _allModels.value = marketData.models
                _tagTranslations.value = marketData.tagTranslations

                val grouped = marketData.groupByCategory()
                _groupedModels.value = grouped
                _categories.value = grouped.keys.toList()

                if (_currentTab.value !in grouped.keys && grouped.isNotEmpty()) {
                    _currentTab.value = grouped.keys.first()
                }

                val downloaded = modelDownloadManager.getDownloadedModels()
                _downloadedModels.value = downloaded

                Logger.i("PicMe:Settings", "Refreshed ${marketData.models.size} models")
            } catch (e: Exception) {
                Logger.e("PicMe:Settings", "Failed to refresh models", e)
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            repository.updateThemeMode(mode)
        }
    }

    fun setAppLanguage(language: AppLanguage) {
        viewModelScope.launch {
            repository.updateAppLanguage(language)
        }
    }



    fun setDebugUiEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateDebugUiEnabled(enabled)
            // 关闭总开关时,同时关闭所有子选项
            if (!enabled) {
                repository.updateShowCameraInfoInPreview(false)
                repository.updateShowFaceDebugOverlay(false)
                repository.updateShowLogOverlay(false)
            }
        }
    }

    fun setFaceDetectionEngineMode(mode: FaceDetectionEngineMode) {
        viewModelScope.launch {
            Logger.d("UX", "Face detection engine mode changed: ${mode.name}")
            repository.updateFaceDetectionEngineMode(mode)

            if (mode != FaceDetectionEngineMode.CUSTOM) {
                val (roiConfig, landmarkConfig) = mode.toStageConfigs()
                repository.updateRoiStageConfig(roiConfig)
                repository.updateLandmarkStageConfig(landmarkConfig)
                Logger.d("UX", "Auto-updated StageConfig for $mode")
            }
        }
    }

    private fun FaceDetectionEngineMode.toStageConfigs(): Pair<StageConfig, StageConfig> = when (this) {
        FaceDetectionEngineMode.MEDIAPIPE -> Pair(
            StageConfig(DetectionStage.ROI, DetectionModelType.MEDIAPIPE, InferenceEngineType.TFLITE, InferenceDevicePreference.AUTO),
            StageConfig(DetectionStage.LANDMARK, DetectionModelType.MEDIAPIPE, InferenceEngineType.TFLITE, InferenceDevicePreference.AUTO)
        )
        FaceDetectionEngineMode.INSIGHTFACE -> Pair(
            StageConfig(DetectionStage.ROI, DetectionModelType.INSIGHTFACE_DET10G, InferenceEngineType.ONNX, InferenceDevicePreference.AUTO),
            StageConfig(DetectionStage.LANDMARK, DetectionModelType.INSIGHTFACE_2D106, InferenceEngineType.ONNX, InferenceDevicePreference.AUTO)
        )
        FaceDetectionEngineMode.MNN -> Pair(
            StageConfig(DetectionStage.ROI, DetectionModelType.INSIGHTFACE_DET10G, InferenceEngineType.MNN, InferenceDevicePreference.AUTO),
            StageConfig(DetectionStage.LANDMARK, DetectionModelType.INSIGHTFACE_2D106, InferenceEngineType.MNN, InferenceDevicePreference.AUTO)
        )
        FaceDetectionEngineMode.NCNN -> Pair(
            StageConfig(DetectionStage.ROI, DetectionModelType.INSIGHTFACE_DET10G, InferenceEngineType.NCNN, InferenceDevicePreference.AUTO),
            StageConfig(DetectionStage.LANDMARK, DetectionModelType.INSIGHTFACE_2D106, InferenceEngineType.NCNN, InferenceDevicePreference.AUTO)
        )
        FaceDetectionEngineMode.CUSTOM -> Pair(
            StageConfig.defaultRoi(),
            StageConfig.defaultLandmark()
        )
    }

    fun setFaceDetectionLandmarkModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateFaceDetectionLandmarkMode(enabled)
        }
    }

    fun setAdaptiveFaceDetectionIntervalEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateAdaptiveFaceDetectionIntervalEnabled(enabled)
        }
    }

    fun setFaceDetectIntervalProfile(profile: FaceDetectIntervalProfile) {
        viewModelScope.launch {
            repository.updateFaceDetectIntervalProfile(profile)
        }
    }

    fun setShowCameraInfoInPreview(show: Boolean) {
        viewModelScope.launch {
            repository.updateShowCameraInfoInPreview(show)
        }
    }

    fun setShowFaceDebugOverlay(show: Boolean) {
        viewModelScope.launch {
            repository.updateShowFaceDebugOverlay(show)
        }
    }

    fun setShowLogOverlay(show: Boolean) {
        viewModelScope.launch {
            repository.updateShowLogOverlay(show)
        }
    }

    fun setDebugShaderMode(mode: Int) {
        _debugShaderMode.value = mode
        // 通知 BeautyRenderer 更新 debug mode
        viewModelScope.launch {
            repository.updateDebugShaderMode(mode)
        }
    }

    // ── 阶段独立配置方法 ────────────────────────────────────
    fun setRoiModelType(modelType: DetectionModelType) {
        viewModelScope.launch {
            val current = roiStageConfig.value
            val updated = current.copy(modelType = modelType)
            Logger.d("UX", "ROI model type changed: ${modelType.name}")
            repository.updateRoiStageConfig(updated)
        }
    }

    fun setRoiEngineType(engineType: InferenceEngineType) {
        viewModelScope.launch {
            val current = roiStageConfig.value
            val updated = current.copy(engineType = engineType)
            Logger.d("UX", "ROI engine type changed: ${engineType.name}")
            repository.updateRoiStageConfig(updated)
        }
    }

    fun setRoiDevicePreference(preference: InferenceDevicePreference) {
        viewModelScope.launch {
            val current = roiStageConfig.value
            val updated = current.copy(devicePreference = preference)
            Logger.d("UX", "ROI device preference changed: ${preference.name}")
            repository.updateRoiStageConfig(updated)
        }
    }

    fun setLandmarkModelType(modelType: DetectionModelType) {
        viewModelScope.launch {
            val current = landmarkStageConfig.value
            val updated = current.copy(modelType = modelType)
            Logger.d("UX", "Landmark model type changed: ${modelType.name}")
            repository.updateLandmarkStageConfig(updated)
        }
    }

    fun setLandmarkEngineType(engineType: InferenceEngineType) {
        viewModelScope.launch {
            val current = landmarkStageConfig.value
            val updated = current.copy(engineType = engineType)
            Logger.d("UX", "Landmark engine type changed: ${engineType.name}")
            repository.updateLandmarkStageConfig(updated)
        }
    }

    fun setLandmarkDevicePreference(preference: InferenceDevicePreference) {
        viewModelScope.launch {
            val current = landmarkStageConfig.value
            val updated = current.copy(devicePreference = preference)
            Logger.d("UX", "Landmark device preference changed: ${preference.name}")
            repository.updateLandmarkStageConfig(updated)
        }
    }

    fun setAiAgentMode(mode: AiAgentMode) {
        viewModelScope.launch {
            repository.updateAiAgentMode(mode)
        }
    }

    fun setAiAgentPrivacyLevel(level: AiAgentPrivacyLevel) {
        viewModelScope.launch {
            Logger.d("UX", "AI Agent privacy level changed: ${level.name}")
            repository.updateAiAgentPrivacyLevel(level)
        }
    }

    fun setAiAgentLocalModel(modelId: String) {
        viewModelScope.launch {
            repository.updateAiAgentLocalModel(modelId)
        }
    }

    fun setAiAgentCodingApiKey(apiKey: String) {
        viewModelScope.launch {
            repository.updateAiAgentCodingApiKey(apiKey)
        }
    }

    fun setAiAgentCodingModel(model: String) {
        viewModelScope.launch {
            repository.updateAiAgentCodingModel(model)
        }
    }

    fun setAiAgentCodingBaseUrl(baseUrl: String) {
        viewModelScope.launch {
            repository.updateAiAgentCodingBaseUrl(baseUrl)
        }
    }

    fun setAiAgentForceRemote(enabled: Boolean) {
        viewModelScope.launch {
            Logger.d("UX", "AI Agent force remote changed: $enabled")
            repository.updateAiAgentForceRemote(enabled)
        }
    }

    fun setVoiceCommandMode(mode: VoiceCommandMode) {
        viewModelScope.launch {
            Logger.d("UX", "Voice command mode changed: ${mode.name}")
            repository.updateVoiceCommandMode(mode)
        }
    }

    fun setLocalAsrModel(modelId: String) {
        viewModelScope.launch {
            repository.updateLocalAsrModel(modelId)
        }
    }

}

class SettingsViewModelFactory(
    private val repository: UserSettingsRepository,
    private val modelDownloadManager: LlmModelDownloadManager
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(repository, modelDownloadManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
