package com.picme.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.core.common.Logger
import com.picme.domain.model.AiAgentMode
import com.picme.domain.model.AiAgentPrivacyLevel
import com.picme.domain.model.AppLanguage
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.model.CameraAspectRatioMode
import com.picme.domain.model.CameraGridMode
import com.picme.domain.model.CameraMemoryState
import com.picme.domain.model.CameraSceneMode
import com.picme.domain.model.DetectionModelType
import com.picme.domain.model.DetectionStage
import com.picme.domain.model.FaceDetectIntervalProfile
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.domain.model.InferenceDevicePreference
import com.picme.domain.model.InferenceEngineType
import com.picme.domain.model.MediaType
import com.picme.domain.model.StageConfig
import com.picme.domain.model.ThemeMode
import com.picme.domain.model.VoiceCommandMode
import com.picme.domain.repository.UserSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.IOException

// 枚举已迁移至 domain.model.UserPreferences，调用方请从 com.picme.domain.model 导入

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferencesRepository(private val context: Context) : UserSettingsRepository {

    private object PreferencesKeys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val BEAUTY_STRATEGY = stringPreferencesKey("beauty_strategy")
        val DEBUG_UI_ENABLED = booleanPreferencesKey("debug_ui_enabled")
        val SHOW_CAMERA_INFO_IN_PREVIEW = booleanPreferencesKey("show_camera_info_in_preview")
        val SHOW_FACE_DEBUG_OVERLAY = booleanPreferencesKey("show_face_debug_overlay")
        val SHOW_LOG_OVERLAY = booleanPreferencesKey("show_log_overlay")
        val FACE_DETECTION_ENGINE_MODE = stringPreferencesKey("face_detection_engine_mode")
        val FACE_DETECTION_LANDMARK_MODE = booleanPreferencesKey("face_detection_landmark_mode")
        val ADAPTIVE_FACE_DETECTION_INTERVAL = booleanPreferencesKey("adaptive_face_detection_interval")
        val FACE_DETECT_INTERVAL_PROFILE = stringPreferencesKey("face_detect_interval_profile")
        val GL_ENGINE_RECOVERY_AVAILABLE_AT_MS = longPreferencesKey("gl_engine_recovery_available_at_ms")
        val DEBUG_SHADER_MODE = intPreferencesKey("debug_shader_mode")

        // 阶段独立配置（ROI / Landmark）
        val ROI_MODEL_TYPE = stringPreferencesKey("roi_model_type")
        val ROI_ENGINE_TYPE = stringPreferencesKey("roi_engine_type")
        val ROI_DEVICE_PREFERENCE = stringPreferencesKey("roi_device_preference")

        val LANDMARK_MODEL_TYPE = stringPreferencesKey("landmark_model_type")
        val LANDMARK_ENGINE_TYPE = stringPreferencesKey("landmark_engine_type")
        val LANDMARK_DEVICE_PREFERENCE = stringPreferencesKey("landmark_device_preference")

        // AI Agent
        val AI_AGENT_MODE = stringPreferencesKey("ai_agent_mode")
        val AI_AGENT_PRIVACY_LEVEL = stringPreferencesKey("ai_agent_privacy_level")
        val AI_AGENT_LOCAL_MODEL = stringPreferencesKey("ai_agent_local_model")

        // 远程模型配置（多模型 JSON + 当前选中模型）
        val AI_AGENT_REMOTE_MODEL_CONFIGS = stringPreferencesKey("ai_agent_remote_model_configs")
        val AI_AGENT_SELECTED_REMOTE_MODEL = stringPreferencesKey("ai_agent_selected_remote_model")

        // 强制使用远程模型（绕过本地模型检查）
        val AI_AGENT_FORCE_REMOTE = booleanPreferencesKey("ai_agent_force_remote")

        // Cloudflare AI Gateway Token
        val CLOUDFLARE_GATEWAY_TOKEN = stringPreferencesKey("cloudflare_gateway_token")

        // 语音控制
        val VOICE_COMMAND_MODE = stringPreferencesKey("voice_command_mode")
        val LOCAL_ASR_MODEL = stringPreferencesKey("local_asr_model")

        // 相机参数记忆
        val CAMERA_MEMORY_USE_FRONT_CAMERA = booleanPreferencesKey("camera_memory_use_front_camera")
        val CAMERA_MEMORY_CAPTURE_MODE = stringPreferencesKey("camera_memory_capture_mode")
        val CAMERA_MEMORY_SELECTED_FILTER = stringPreferencesKey("camera_memory_selected_filter")
        val CAMERA_MEMORY_SELECTED_STYLE_FILTER = stringPreferencesKey("camera_memory_selected_style_filter")

        val CAMERA_MEMORY_BEAUTY_ENABLED = booleanPreferencesKey("camera_memory_beauty_enabled")
        val CAMERA_MEMORY_BEAUTY_SMOOTHING = floatPreferencesKey("camera_memory_beauty_smoothing")
        val CAMERA_MEMORY_BEAUTY_WHITENING = floatPreferencesKey("camera_memory_beauty_whitening")
        val CAMERA_MEMORY_BEAUTY_SLIM_FACE = floatPreferencesKey("camera_memory_beauty_slim_face")
        val CAMERA_MEMORY_BEAUTY_BIG_EYES = floatPreferencesKey("camera_memory_beauty_big_eyes")
        val CAMERA_MEMORY_BEAUTY_LIP_COLOR = floatPreferencesKey("camera_memory_beauty_lip_color")
        val CAMERA_MEMORY_BEAUTY_LIP_COLOR_INDEX = intPreferencesKey("camera_memory_beauty_lip_color_index")
        val CAMERA_MEMORY_BEAUTY_BLUSH = floatPreferencesKey("camera_memory_beauty_blush")
        val CAMERA_MEMORY_BEAUTY_BLUSH_COLOR_FAMILY = intPreferencesKey("camera_memory_beauty_blush_color_family")
        val CAMERA_MEMORY_BEAUTY_EYEBROW = floatPreferencesKey("camera_memory_beauty_eyebrow")
        val CAMERA_MEMORY_BEAUTY_BODY_ENHANCEMENT = floatPreferencesKey("camera_memory_beauty_body_enhancement")
        val CAMERA_MEMORY_BEAUTY_LEG_EXTENSION = floatPreferencesKey("camera_memory_beauty_leg_extension")
        val CAMERA_MEMORY_BEAUTY_EXPOSURE = floatPreferencesKey("camera_memory_beauty_exposure")
        val CAMERA_MEMORY_BEAUTY_CONTRAST = floatPreferencesKey("camera_memory_beauty_contrast")
        val CAMERA_MEMORY_BEAUTY_SATURATION = floatPreferencesKey("camera_memory_beauty_saturation")
        val CAMERA_MEMORY_BEAUTY_TEMPERATURE = floatPreferencesKey("camera_memory_beauty_temperature")
        val CAMERA_MEMORY_BEAUTY_TINT = floatPreferencesKey("camera_memory_beauty_tint")
        val CAMERA_MEMORY_BEAUTY_BRIGHTNESS = floatPreferencesKey("camera_memory_beauty_brightness")
        val CAMERA_MEMORY_BEAUTY_RED_ADJUSTMENT = floatPreferencesKey("camera_memory_beauty_red_adjustment")
        val CAMERA_MEMORY_BEAUTY_GREEN_ADJUSTMENT = floatPreferencesKey("camera_memory_beauty_green_adjustment")
        val CAMERA_MEMORY_BEAUTY_BLUE_ADJUSTMENT = floatPreferencesKey("camera_memory_beauty_blue_adjustment")

        val CAMERA_MEMORY_ASPECT_RATIO = stringPreferencesKey("camera_memory_aspect_ratio")
        val CAMERA_MEMORY_ZOOM_RATIO = floatPreferencesKey("camera_memory_zoom_ratio")
        val CAMERA_MEMORY_EXPOSURE_COMPENSATION = intPreferencesKey("camera_memory_exposure_compensation")
        val CAMERA_MEMORY_WHITE_BALANCE_MODE = intPreferencesKey("camera_memory_white_balance_mode")
        val CAMERA_MEMORY_SCENE_MODE = stringPreferencesKey("camera_memory_scene_mode")
        val CAMERA_MEMORY_GRID_MODE = stringPreferencesKey("camera_memory_grid_mode")

        // 日志模块配置
        val LOG_MODULE_CONFIG = stringPreferencesKey("log_module_config")
    }

    private fun parseMediaType(value: String?): MediaType {
        return runCatching { value?.let { MediaType.valueOf(it) } }
            .getOrNull() ?: MediaType.PHOTO
    }

    private fun parseFilterType(value: String?): FilterType {
        return runCatching { value?.let { FilterType.valueOf(it) } }
            .getOrNull() ?: FilterType.NONE
    }

    private fun parseStyleFilter(value: String?): StyleFilter {
        return runCatching { value?.let { StyleFilter.valueOf(it) } }
            .getOrNull() ?: StyleFilter.NONE
    }

    private fun parseCameraAspectRatioMode(value: String?): CameraAspectRatioMode {
        return runCatching { value?.let { CameraAspectRatioMode.valueOf(it) } }
            .getOrNull() ?: CameraAspectRatioMode.FULL
    }

    private fun parseCameraSceneMode(value: String?): CameraSceneMode {
        return runCatching { value?.let { CameraSceneMode.valueOf(it) } }
            .getOrNull() ?: CameraSceneMode.NONE
    }

    private fun parseCameraGridMode(value: String?): CameraGridMode {
        return runCatching { value?.let { CameraGridMode.valueOf(it) } }
            .getOrNull() ?: CameraGridMode.NONE
    }

    override val themeModeFlow: Flow<ThemeMode> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val themeName = preferences[PreferencesKeys.THEME_MODE] ?: ThemeMode.SYSTEM.name
            ThemeMode.valueOf(themeName)
        }

    override val appLanguageFlow: Flow<AppLanguage> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val langName = preferences[PreferencesKeys.APP_LANGUAGE] ?: AppLanguage.SYSTEM.name
            AppLanguage.valueOf(langName)
        }

    override fun getAppLanguageBlocking(): AppLanguage = runBlocking {
        try {
            val preferences = context.dataStore.data.first()
            val langName = preferences[PreferencesKeys.APP_LANGUAGE] ?: AppLanguage.SYSTEM.name
            AppLanguage.valueOf(langName)
        } catch (_: Exception) {
            AppLanguage.SYSTEM
        }
    }

    override suspend fun updateThemeMode(mode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME_MODE] = mode.name
        }
    }

    override suspend fun updateAppLanguage(language: AppLanguage) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_LANGUAGE] = language.name
        }
    }

    override val beautyStrategyFlow: Flow<BeautyStrategy> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val strategyName = preferences[PreferencesKeys.BEAUTY_STRATEGY] ?: BeautyStrategy.BIG_BEAUTY.name
            BeautyStrategy.valueOf(strategyName)
        }

    override fun getBeautyStrategyBlocking(): BeautyStrategy = runBlocking {
        try {
            val preferences = context.dataStore.data.first()
            val strategyName = preferences[PreferencesKeys.BEAUTY_STRATEGY] ?: BeautyStrategy.BIG_BEAUTY.name
            BeautyStrategy.valueOf(strategyName)
        } catch (_: Exception) {
            BeautyStrategy.BIG_BEAUTY
        }
    }

    override suspend fun updateBeautyStrategy(strategy: BeautyStrategy) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BEAUTY_STRATEGY] = strategy.name
        }
    }

    override val glEngineRecoveryAvailableAtFlow: Flow<Long> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.GL_ENGINE_RECOVERY_AVAILABLE_AT_MS] ?: 0L
        }

    override suspend fun persistGlEngineFallback(cooldownMs: Long) {
        val nowMs = System.currentTimeMillis()
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GL_ENGINE_RECOVERY_AVAILABLE_AT_MS] = nowMs + cooldownMs
        }
    }

    override suspend fun triggerManualGlEngineRecovery() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BEAUTY_STRATEGY] = BeautyStrategy.BIG_BEAUTY.name
            preferences[PreferencesKeys.GL_ENGINE_RECOVERY_AVAILABLE_AT_MS] = 0L
        }
    }

    override suspend fun clearGlEngineRecoveryCooldown() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GL_ENGINE_RECOVERY_AVAILABLE_AT_MS] = 0L
        }
    }

    override val debugUiEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.DEBUG_UI_ENABLED] ?: true
        }

    override suspend fun updateDebugUiEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEBUG_UI_ENABLED] = enabled
        }
    }

    override val showCameraInfoInPreviewFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.SHOW_CAMERA_INFO_IN_PREVIEW] ?: false
        }

    override suspend fun updateShowCameraInfoInPreview(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_CAMERA_INFO_IN_PREVIEW] = show
        }
    }

    override val showFaceDebugOverlayFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.SHOW_FACE_DEBUG_OVERLAY] ?: false
        }

    override suspend fun updateShowFaceDebugOverlay(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_FACE_DEBUG_OVERLAY] = show
        }
    }

    override val showLogOverlayFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.SHOW_LOG_OVERLAY] ?: false
        }

    override suspend fun updateShowLogOverlay(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_LOG_OVERLAY] = show
        }
    }

    override val faceDetectionEngineModeFlow: Flow<FaceDetectionEngineMode> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val modeName = preferences[PreferencesKeys.FACE_DETECTION_ENGINE_MODE]
                ?: FaceDetectionEngineMode.MEDIAPIPE.name
            runCatching { FaceDetectionEngineMode.valueOf(modeName) }
                .getOrDefault(FaceDetectionEngineMode.MEDIAPIPE)
        }

    override suspend fun updateFaceDetectionEngineMode(mode: FaceDetectionEngineMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FACE_DETECTION_ENGINE_MODE] = mode.name
        }
    }

    override val debugShaderModeFlow: Flow<Int> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.DEBUG_SHADER_MODE] ?: 0
        }

    override suspend fun updateDebugShaderMode(mode: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEBUG_SHADER_MODE] = mode
        }
    }

    override val faceDetectionLandmarkModeFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.FACE_DETECTION_LANDMARK_MODE] ?: true
        }

    override suspend fun updateFaceDetectionLandmarkMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FACE_DETECTION_LANDMARK_MODE] = enabled
        }
    }

    override val adaptiveFaceDetectionIntervalEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.ADAPTIVE_FACE_DETECTION_INTERVAL] ?: true
        }

    override suspend fun updateAdaptiveFaceDetectionIntervalEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ADAPTIVE_FACE_DETECTION_INTERVAL] = enabled
        }
    }

    override val faceDetectIntervalProfileFlow: Flow<FaceDetectIntervalProfile> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val profileName = preferences[PreferencesKeys.FACE_DETECT_INTERVAL_PROFILE]
                ?: FaceDetectIntervalProfile.BALANCED.name
            FaceDetectIntervalProfile.valueOf(profileName)
        }

    override suspend fun updateFaceDetectIntervalProfile(profile: FaceDetectIntervalProfile) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FACE_DETECT_INTERVAL_PROFILE] = profile.name
        }
    }

    // ── 阶段独立配置（ROI / Landmark）────────────────────────

    private fun parseStageConfig(
        stage: DetectionStage,
        modelTypeName: String?,
        engineTypeName: String?,
        devicePreferenceName: String?
    ): StageConfig {
        val defaultConfig = when (stage) {
            DetectionStage.ROI -> StageConfig.defaultRoi()
            DetectionStage.LANDMARK -> StageConfig.defaultLandmark()
        }

        val modelType = runCatching {
            modelTypeName?.let { DetectionModelType.valueOf(it) }
        }.getOrNull() ?: defaultConfig.modelType

        val engineType = runCatching {
            engineTypeName?.let { InferenceEngineType.valueOf(it) }
        }.getOrNull() ?: defaultConfig.engineType

        val devicePreference = runCatching {
            devicePreferenceName?.let { InferenceDevicePreference.valueOf(it) }
        }.getOrNull() ?: defaultConfig.devicePreference

        return StageConfig(
            stage = stage,
            modelType = modelType,
            engineType = engineType,
            devicePreference = devicePreference
        )
    }

    override val roiStageConfigFlow: Flow<StageConfig> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Logger.e("DataStore", "Failed to read ROI stage config, using default")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            parseStageConfig(
                stage = DetectionStage.ROI,
                modelTypeName = preferences[PreferencesKeys.ROI_MODEL_TYPE],
                engineTypeName = preferences[PreferencesKeys.ROI_ENGINE_TYPE],
                devicePreferenceName = preferences[PreferencesKeys.ROI_DEVICE_PREFERENCE]
            )
        }

    override suspend fun updateRoiStageConfig(config: StageConfig) {
        Logger.d("DataStore", "Updating ROI stage config: $config")
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ROI_MODEL_TYPE] = config.modelType.name
            preferences[PreferencesKeys.ROI_ENGINE_TYPE] = config.engineType.name
            preferences[PreferencesKeys.ROI_DEVICE_PREFERENCE] = config.devicePreference.name
        }
    }

    override val landmarkStageConfigFlow: Flow<StageConfig> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Logger.e("DataStore", "Failed to read Landmark stage config, using default")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            parseStageConfig(
                stage = DetectionStage.LANDMARK,
                modelTypeName = preferences[PreferencesKeys.LANDMARK_MODEL_TYPE],
                engineTypeName = preferences[PreferencesKeys.LANDMARK_ENGINE_TYPE],
                devicePreferenceName = preferences[PreferencesKeys.LANDMARK_DEVICE_PREFERENCE]
            )
        }

    override suspend fun updateLandmarkStageConfig(config: StageConfig) {
        Logger.d("DataStore", "Updating Landmark stage config: $config")
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LANDMARK_MODEL_TYPE] = config.modelType.name
            preferences[PreferencesKeys.LANDMARK_ENGINE_TYPE] = config.engineType.name
            preferences[PreferencesKeys.LANDMARK_DEVICE_PREFERENCE] = config.devicePreference.name
        }
    }

    override val aiAgentModeFlow: Flow<AiAgentMode> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val modeName = preferences[PreferencesKeys.AI_AGENT_MODE] ?: AiAgentMode.LOCAL.name
            runCatching { AiAgentMode.valueOf(modeName) }
                .getOrDefault(AiAgentMode.LOCAL)
        }

    override suspend fun updateAiAgentMode(mode: AiAgentMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AI_AGENT_MODE] = mode.name
        }
    }

    override val aiAgentPrivacyLevelFlow: Flow<AiAgentPrivacyLevel> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val levelName = preferences[PreferencesKeys.AI_AGENT_PRIVACY_LEVEL]
                ?: AiAgentPrivacyLevel.STRICT.name
            runCatching { AiAgentPrivacyLevel.valueOf(levelName) }
                .getOrDefault(AiAgentPrivacyLevel.STRICT)
        }

    override suspend fun updateAiAgentPrivacyLevel(level: AiAgentPrivacyLevel) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AI_AGENT_PRIVACY_LEVEL] = level.name
        }
    }

    override val aiAgentLocalModelFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AI_AGENT_LOCAL_MODEL] ?: ""
        }

    override suspend fun updateAiAgentLocalModel(modelId: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AI_AGENT_LOCAL_MODEL] = modelId
        }
    }

    // ── 远程模型配置（多模型） ────────────────────────────────

    override val aiAgentRemoteModelConfigsFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AI_AGENT_REMOTE_MODEL_CONFIGS] ?: ""
        }

    override suspend fun updateAiAgentRemoteModelConfigs(configsJson: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AI_AGENT_REMOTE_MODEL_CONFIGS] = configsJson
        }
    }

    override val aiAgentSelectedRemoteModelFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AI_AGENT_SELECTED_REMOTE_MODEL] ?: "kimi-for-coding"
        }

    override suspend fun updateAiAgentSelectedRemoteModel(modelId: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AI_AGENT_SELECTED_REMOTE_MODEL] = modelId
        }
    }

    override val aiAgentForceRemoteFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AI_AGENT_FORCE_REMOTE] ?: false
        }

    override suspend fun updateAiAgentForceRemote(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AI_AGENT_FORCE_REMOTE] = enabled
        }
    }

    // ── Cloudflare AI Gateway Token ─────────────────────────

    override val cloudflareGatewayTokenFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.CLOUDFLARE_GATEWAY_TOKEN] ?: ""
        }

    override suspend fun updateCloudflareGatewayToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CLOUDFLARE_GATEWAY_TOKEN] = token
        }
    }

    override val voiceCommandModeFlow: Flow<VoiceCommandMode> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val modeName = preferences[PreferencesKeys.VOICE_COMMAND_MODE]
                ?: VoiceCommandMode.DISABLED.name
            runCatching { VoiceCommandMode.valueOf(modeName) }
                .getOrDefault(VoiceCommandMode.DISABLED)
        }

    override suspend fun updateVoiceCommandMode(mode: VoiceCommandMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.VOICE_COMMAND_MODE] = mode.name
        }
    }

    override val localAsrModelFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.LOCAL_ASR_MODEL] ?: ""
        }

    override suspend fun updateLocalAsrModel(modelId: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LOCAL_ASR_MODEL] = modelId
        }
    }

    override val cameraMemoryStateFlow: Flow<CameraMemoryState> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val selectedFilter = parseFilterType(preferences[PreferencesKeys.CAMERA_MEMORY_SELECTED_FILTER])
            val selectedStyleFilter = parseStyleFilter(preferences[PreferencesKeys.CAMERA_MEMORY_SELECTED_STYLE_FILTER])
            val beautySettings = BeautySettings(
                enabled = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_ENABLED] ?: false,
                smoothing = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_SMOOTHING] ?: 0f,
                whitening = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_WHITENING] ?: 0f,
                slimFace = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_SLIM_FACE] ?: 0f,
                bigEyes = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BIG_EYES] ?: 0f,
                lipColor = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_LIP_COLOR] ?: BeautySettings.DEFAULT_LIP_COLOR,
                lipColorIndex = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_LIP_COLOR_INDEX] ?: 0,
                blush = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BLUSH] ?: BeautySettings.DEFAULT_BLUSH,
                blushColorFamily = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BLUSH_COLOR_FAMILY] ?: 0,
                eyebrow = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_EYEBROW] ?: BeautySettings.DEFAULT_EYEBROW,
                bodyEnhancement = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BODY_ENHANCEMENT] ?: 0f,
                legExtension = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_LEG_EXTENSION] ?: 0f,
                exposure = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_EXPOSURE] ?: 0f,
                contrast = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_CONTRAST] ?: 50f,
                saturation = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_SATURATION] ?: 100f,
                temperature = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_TEMPERATURE] ?: 5000f,
                tint = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_TINT] ?: 0f,
                brightness = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BRIGHTNESS] ?: 0f,
                redAdjustment = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_RED_ADJUSTMENT] ?: 100f,
                greenAdjustment = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_GREEN_ADJUSTMENT] ?: 100f,
                blueAdjustment = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BLUE_ADJUSTMENT] ?: 100f,
                colorFilter = selectedFilter,
                styleFilter = selectedStyleFilter
            )

            CameraMemoryState(
                useFrontCamera = preferences[PreferencesKeys.CAMERA_MEMORY_USE_FRONT_CAMERA] ?: false,
                captureMode = parseMediaType(preferences[PreferencesKeys.CAMERA_MEMORY_CAPTURE_MODE]),
                selectedFilter = selectedFilter,
                selectedStyleFilter = selectedStyleFilter,
                beautySettings = beautySettings,
                aspectRatio = parseCameraAspectRatioMode(preferences[PreferencesKeys.CAMERA_MEMORY_ASPECT_RATIO]),
                zoomRatio = preferences[PreferencesKeys.CAMERA_MEMORY_ZOOM_RATIO] ?: 1f,
                exposureCompensation = preferences[PreferencesKeys.CAMERA_MEMORY_EXPOSURE_COMPENSATION] ?: 0,
                whiteBalanceMode = preferences[PreferencesKeys.CAMERA_MEMORY_WHITE_BALANCE_MODE] ?: 0,
                sceneMode = parseCameraSceneMode(preferences[PreferencesKeys.CAMERA_MEMORY_SCENE_MODE]),
                gridMode = parseCameraGridMode(preferences[PreferencesKeys.CAMERA_MEMORY_GRID_MODE])
            )
        }

    override suspend fun updateCameraMemoryState(state: CameraMemoryState) {
        val resolvedBeautySettings = state.beautySettings.copy(
            colorFilter = state.selectedFilter,
            styleFilter = state.selectedStyleFilter
        )

        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CAMERA_MEMORY_USE_FRONT_CAMERA] = state.useFrontCamera
            preferences[PreferencesKeys.CAMERA_MEMORY_CAPTURE_MODE] = state.captureMode.name
            preferences[PreferencesKeys.CAMERA_MEMORY_SELECTED_FILTER] = state.selectedFilter.name
            preferences[PreferencesKeys.CAMERA_MEMORY_SELECTED_STYLE_FILTER] = state.selectedStyleFilter.name

            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_ENABLED] = resolvedBeautySettings.enabled
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_SMOOTHING] = resolvedBeautySettings.smoothing
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_WHITENING] = resolvedBeautySettings.whitening
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_SLIM_FACE] = resolvedBeautySettings.slimFace
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BIG_EYES] = resolvedBeautySettings.bigEyes
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_LIP_COLOR] = resolvedBeautySettings.lipColor
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_LIP_COLOR_INDEX] = resolvedBeautySettings.lipColorIndex
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BLUSH] = resolvedBeautySettings.blush
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BLUSH_COLOR_FAMILY] = resolvedBeautySettings.blushColorFamily
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_EYEBROW] = resolvedBeautySettings.eyebrow
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BODY_ENHANCEMENT] = resolvedBeautySettings.bodyEnhancement
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_LEG_EXTENSION] = resolvedBeautySettings.legExtension
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_EXPOSURE] = resolvedBeautySettings.exposure
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_CONTRAST] = resolvedBeautySettings.contrast
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_SATURATION] = resolvedBeautySettings.saturation
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_TEMPERATURE] = resolvedBeautySettings.temperature
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_TINT] = resolvedBeautySettings.tint
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BRIGHTNESS] = resolvedBeautySettings.brightness
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_RED_ADJUSTMENT] = resolvedBeautySettings.redAdjustment
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_GREEN_ADJUSTMENT] = resolvedBeautySettings.greenAdjustment
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BLUE_ADJUSTMENT] = resolvedBeautySettings.blueAdjustment

            preferences[PreferencesKeys.CAMERA_MEMORY_ASPECT_RATIO] = state.aspectRatio.name
            preferences[PreferencesKeys.CAMERA_MEMORY_ZOOM_RATIO] = state.zoomRatio
            preferences[PreferencesKeys.CAMERA_MEMORY_EXPOSURE_COMPENSATION] = state.exposureCompensation
            preferences[PreferencesKeys.CAMERA_MEMORY_WHITE_BALANCE_MODE] = state.whiteBalanceMode
            preferences[PreferencesKeys.CAMERA_MEMORY_SCENE_MODE] = state.sceneMode.name
            preferences[PreferencesKeys.CAMERA_MEMORY_GRID_MODE] = state.gridMode.name
        }
    }

    override suspend fun resetCameraMemoryState() {
        updateCameraMemoryState(CameraMemoryState())
    }

    override val logModuleConfigFlow: Flow<com.picme.domain.model.LogModuleConfig> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val configJson = preferences[PreferencesKeys.LOG_MODULE_CONFIG]
            if (configJson != null) {
                com.picme.domain.model.LogModuleConfig.fromJson(configJson)
            } else {
                com.picme.domain.model.LogModuleConfig.default()
            }
        }

    override suspend fun updateLogModuleConfig(config: com.picme.domain.model.LogModuleConfig) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LOG_MODULE_CONFIG] = config.toJson()
        }
    }
}
