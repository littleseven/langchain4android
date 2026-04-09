package com.picme.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.picme.data.preferences.AppLanguage
import com.picme.data.preferences.BeautyStrategy
import com.picme.data.preferences.FaceDetectIntervalProfile
import com.picme.data.preferences.ThemeMode
import com.picme.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: UserPreferencesRepository) : ViewModel() {

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

    val beautyStrategy: StateFlow<BeautyStrategy> = repository.beautyStrategyFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = BeautyStrategy.R_PLAN
        )

    val debugUiEnabled: StateFlow<Boolean> = repository.debugUiEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
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

    fun setBeautyStrategy(strategy: BeautyStrategy) {
        viewModelScope.launch {
            repository.updateBeautyStrategy(strategy)
            if (strategy == BeautyStrategy.R_PLAN) {
                repository.triggerManualGlEngineRecovery()
            }
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
}

class SettingsViewModelFactory(
    private val repository: UserPreferencesRepository
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
