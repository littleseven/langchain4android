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
import com.picme.domain.model.AppLanguage
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.model.FaceDetectIntervalProfile
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.domain.model.InsightFaceLandmarkDetectorType
import com.picme.domain.model.InsightFaceRoiDetectorType
import com.picme.domain.model.ThemeMode
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
        val INSIGHTFACE_ROI_DETECTOR_TYPE = stringPreferencesKey("insightface_roi_detector_type")
        val INSIGHTFACE_LANDMARK_DETECTOR_TYPE = stringPreferencesKey("insightface_landmark_detector_type")
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
                ?: FaceDetectionEngineMode.INSIGHTFACE.name
            runCatching { FaceDetectionEngineMode.valueOf(modeName) }
                .getOrDefault(FaceDetectionEngineMode.INSIGHTFACE)
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

    // ── InsightFace 流水线配置 ─────────────────────────────
    // [迁移] NCNN 已移除，DET10G/INSIGHTFACE_2D106 降级为备选，MNN 成为默认
    private fun migrateRoiType(typeName: String?): InsightFaceRoiDetectorType {
        if (typeName == null) return InsightFaceRoiDetectorType.MNN
        return runCatching {
            when (val type = InsightFaceRoiDetectorType.valueOf(typeName)) {
                InsightFaceRoiDetectorType.MNN -> type
                // DET10G 和 MEDIAPIPE 仍可用，但默认推荐 MNN
                else -> type
            }
        }.getOrDefault(InsightFaceRoiDetectorType.MNN)
    }

    private fun migrateLandmarkType(typeName: String?): InsightFaceLandmarkDetectorType {
        if (typeName == null) return InsightFaceLandmarkDetectorType.MNN
        return runCatching {
            when (val type = InsightFaceLandmarkDetectorType.valueOf(typeName)) {
                InsightFaceLandmarkDetectorType.MNN -> type
                // INSIGHTFACE_2D106 和 MEDIAPIPE 仍可用
                else -> type
            }
        }.getOrDefault(InsightFaceLandmarkDetectorType.MNN)
    }

    override val insightFaceRoiDetectorTypeFlow: Flow<InsightFaceRoiDetectorType> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            migrateRoiType(preferences[PreferencesKeys.INSIGHTFACE_ROI_DETECTOR_TYPE])
        }

    override suspend fun updateInsightFaceRoiDetectorType(type: InsightFaceRoiDetectorType) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.INSIGHTFACE_ROI_DETECTOR_TYPE] = type.name
        }
    }

    override val insightFaceLandmarkDetectorTypeFlow: Flow<InsightFaceLandmarkDetectorType> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            migrateLandmarkType(preferences[PreferencesKeys.INSIGHTFACE_LANDMARK_DETECTOR_TYPE])
        }

    override suspend fun updateInsightFaceLandmarkDetectorType(type: InsightFaceLandmarkDetectorType) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.INSIGHTFACE_LANDMARK_DETECTOR_TYPE] = type.name
        }
    }

}
