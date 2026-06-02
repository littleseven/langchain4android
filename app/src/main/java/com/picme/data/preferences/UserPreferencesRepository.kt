package com.picme.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.picme.domain.model.AiAgentMode
import com.picme.domain.model.AiAgentPrivacyLevel
import com.picme.domain.model.AppLanguage
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.model.DetectionModelType
import com.picme.domain.model.DetectionStage
import com.picme.domain.model.FaceDetectIntervalProfile
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.domain.model.InferenceDevicePreference
import com.picme.domain.model.InferenceEngineType
import com.picme.domain.model.StageConfig
import com.picme.domain.model.ThemeMode
import com.picme.domain.model.VoiceCommandMode
import com.picme.domain.repository.UserSettingsRepository
import com.picme.core.common.Logger
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
        // Kimi Coding API 配置
        val AI_AGENT_CODING_API_KEY = stringPreferencesKey("ai_agent_coding_api_key")
        val AI_AGENT_CODING_MODEL = stringPreferencesKey("ai_agent_coding_model")
        val AI_AGENT_CODING_BASE_URL = stringPreferencesKey("ai_agent_coding_base_url")

        // 强制使用远程模型（绕过本地模型检查）
        val AI_AGENT_FORCE_REMOTE = booleanPreferencesKey("ai_agent_force_remote")

        // 语音控制
        val VOICE_COMMAND_MODE = stringPreferencesKey("voice_command_mode")
        val LOCAL_ASR_MODEL = stringPreferencesKey("local_asr_model")
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

    // ── Kimi Coding API 配置 ─────────────────────────────────

    override val aiAgentCodingApiKeyFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AI_AGENT_CODING_API_KEY] ?: ""
        }

    override suspend fun updateAiAgentCodingApiKey(apiKey: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AI_AGENT_CODING_API_KEY] = apiKey
        }
    }

    override val aiAgentCodingModelFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AI_AGENT_CODING_MODEL] ?: "kimi-for-coding"
        }

    override suspend fun updateAiAgentCodingModel(model: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AI_AGENT_CODING_MODEL] = model
        }
    }

    override val aiAgentCodingBaseUrlFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AI_AGENT_CODING_BASE_URL] ?: "https://api.kimi.com/coding/v1/"
        }

    override suspend fun updateAiAgentCodingBaseUrl(baseUrl: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AI_AGENT_CODING_BASE_URL] = baseUrl
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

}
