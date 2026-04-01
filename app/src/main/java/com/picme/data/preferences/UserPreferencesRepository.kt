package com.picme.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.IOException

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

enum class AppLanguage {
    SYSTEM, ENGLISH, CHINESE, TRADITIONAL_CHINESE
}

/**
 * 美颜策略选择
 *
 * PIXEL_FREE: PixelFreeEffects SDK（备用引擎）
 * R_PLAN: R 计划自主研发（主引擎，默认）
 */
enum class BeautyStrategy {
    PIXEL_FREE,
    R_PLAN
}

enum class FaceDetectIntervalProfile {
    CONSERVATIVE,
    BALANCED,
    AGGRESSIVE
}

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val BEAUTY_STRATEGY = stringPreferencesKey("beauty_strategy")
        val DEBUG_UI_ENABLED = booleanPreferencesKey("debug_ui_enabled")
        val FACE_DETECTION_LANDMARK_MODE = booleanPreferencesKey("face_detection_landmark_mode")
        val ADAPTIVE_FACE_DETECTION_INTERVAL = booleanPreferencesKey("adaptive_face_detection_interval")
        val FACE_DETECT_INTERVAL_PROFILE = stringPreferencesKey("face_detect_interval_profile")
        val R_PLAN_RECOVERY_AVAILABLE_AT_MS = longPreferencesKey("r_plan_recovery_available_at_ms")
    }

    val themeModeFlow: Flow<ThemeMode> = context.dataStore.data
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

    val appLanguageFlow: Flow<AppLanguage> = context.dataStore.data
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

    fun getAppLanguageBlocking(): AppLanguage = runBlocking {
        try {
            val preferences = context.dataStore.data.first()
            val langName = preferences[PreferencesKeys.APP_LANGUAGE] ?: AppLanguage.SYSTEM.name
            AppLanguage.valueOf(langName)
        } catch (_: Exception) {
            AppLanguage.SYSTEM
        }
    }

    suspend fun updateThemeMode(themeMode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME_MODE] = themeMode.name
        }
    }

    suspend fun updateAppLanguage(appLanguage: AppLanguage) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_LANGUAGE] = appLanguage.name
        }
    }

    val beautyStrategyFlow: Flow<BeautyStrategy> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val strategyName = preferences[PreferencesKeys.BEAUTY_STRATEGY] ?: BeautyStrategy.R_PLAN.name
            BeautyStrategy.valueOf(strategyName)
        }

    fun getBeautyStrategyBlocking(): BeautyStrategy = runBlocking {
        try {
            val preferences = context.dataStore.data.first()
            val strategyName = preferences[PreferencesKeys.BEAUTY_STRATEGY] ?: BeautyStrategy.R_PLAN.name
            BeautyStrategy.valueOf(strategyName)
        } catch (_: Exception) {
            BeautyStrategy.R_PLAN
        }
    }

    suspend fun updateBeautyStrategy(strategy: BeautyStrategy) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BEAUTY_STRATEGY] = strategy.name
        }
    }

    val rPlanRecoveryAvailableAtFlow: Flow<Long> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.R_PLAN_RECOVERY_AVAILABLE_AT_MS] ?: 0L
        }

    suspend fun persistRPlanFallback(cooldownMs: Long) {
        val nowMs = System.currentTimeMillis()
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BEAUTY_STRATEGY] = BeautyStrategy.PIXEL_FREE.name
            preferences[PreferencesKeys.R_PLAN_RECOVERY_AVAILABLE_AT_MS] = nowMs + cooldownMs
        }
    }

    suspend fun triggerManualRPlanRecovery() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BEAUTY_STRATEGY] = BeautyStrategy.R_PLAN.name
            preferences[PreferencesKeys.R_PLAN_RECOVERY_AVAILABLE_AT_MS] = 0L
        }
    }

    suspend fun clearRPlanRecoveryCooldown() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.R_PLAN_RECOVERY_AVAILABLE_AT_MS] = 0L
        }
    }

    val debugUiEnabledFlow: Flow<Boolean> = context.dataStore.data
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

    suspend fun updateDebugUiEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEBUG_UI_ENABLED] = enabled
        }
    }

    val faceDetectionLandmarkModeFlow: Flow<Boolean> = context.dataStore.data
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

    suspend fun updateFaceDetectionLandmarkMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FACE_DETECTION_LANDMARK_MODE] = enabled
        }
    }

    val adaptiveFaceDetectionIntervalEnabledFlow: Flow<Boolean> = context.dataStore.data
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

    suspend fun updateAdaptiveFaceDetectionIntervalEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ADAPTIVE_FACE_DETECTION_INTERVAL] = enabled
        }
    }

    val faceDetectIntervalProfileFlow: Flow<FaceDetectIntervalProfile> = context.dataStore.data
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

    suspend fun updateFaceDetectIntervalProfile(profile: FaceDetectIntervalProfile) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FACE_DETECT_INTERVAL_PROFILE] = profile.name
        }
    }
}
