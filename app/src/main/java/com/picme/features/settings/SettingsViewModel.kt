package com.picme.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.picme.core.common.Logger
import com.picme.domain.model.AppLanguage
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.model.FaceDetectIntervalProfile
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.domain.model.InsightFaceLandmarkDetectorType
import com.picme.domain.model.InsightFaceRoiDetectorType
import com.picme.domain.model.ThemeMode
import com.picme.domain.repository.UserSettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: UserSettingsRepository) : ViewModel() {

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

    val insightFaceRoiDetectorType: StateFlow<InsightFaceRoiDetectorType> = repository.insightFaceRoiDetectorTypeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = InsightFaceRoiDetectorType.MNN
        )

    val insightFaceLandmarkDetectorType: StateFlow<InsightFaceLandmarkDetectorType> = repository.insightFaceLandmarkDetectorTypeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = InsightFaceLandmarkDetectorType.MNN
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

    fun setDebugShaderMode(mode: Int) {
        _debugShaderMode.value = mode
        // 通知 BeautyRenderer 更新 debug mode
        viewModelScope.launch {
            repository.updateDebugShaderMode(mode)
        }
    }

    fun setInsightFaceRoiDetectorType(type: InsightFaceRoiDetectorType) {
        viewModelScope.launch {
            repository.updateInsightFaceRoiDetectorType(type)
        }
    }

    fun setInsightFaceLandmarkDetectorType(type: InsightFaceLandmarkDetectorType) {
        viewModelScope.launch {
            repository.updateInsightFaceLandmarkDetectorType(type)
        }
    }

}

class SettingsViewModelFactory(
    private val repository: UserSettingsRepository
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
