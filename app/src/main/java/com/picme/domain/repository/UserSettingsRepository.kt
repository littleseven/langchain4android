package com.picme.domain.repository

import com.picme.domain.model.AiAgentMode
import com.picme.domain.model.AiAgentPrivacyLevel
import com.picme.domain.model.AppLanguage
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.model.CameraMemoryState
import com.picme.domain.model.FaceDetectIntervalProfile
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.domain.model.StageConfig
import com.picme.domain.model.ThemeMode
import com.picme.domain.model.VoiceCommandMode
import kotlinx.coroutines.flow.Flow

/**
 * 用户偏好设置仓储接口（Domain 层契约）
 *
 * Features 层应依赖此接口，而非直接依赖 data 层的 UserPreferencesRepository。
 * 实现类：data/preferences/UserPreferencesRepository（通过 DI 注入）。
 */
interface UserSettingsRepository {

    // ── 主题 ──────────────────────────────────────────────
    val themeModeFlow: Flow<ThemeMode>
    suspend fun updateThemeMode(mode: ThemeMode)

    // ── 语言 ──────────────────────────────────────────────
    val appLanguageFlow: Flow<AppLanguage>
    fun getAppLanguageBlocking(): AppLanguage
    suspend fun updateAppLanguage(language: AppLanguage)

    // ── 美颜引擎策略 ───────────────────────────────────────
    val beautyStrategyFlow: Flow<BeautyStrategy>
    fun getBeautyStrategyBlocking(): BeautyStrategy
    suspend fun updateBeautyStrategy(strategy: BeautyStrategy)

    // ── GL 引擎回退与恢复 ──────────────────────────────────
    val glEngineRecoveryAvailableAtFlow: Flow<Long>
    suspend fun persistGlEngineFallback(cooldownMs: Long)
    suspend fun triggerManualGlEngineRecovery()
    suspend fun clearGlEngineRecoveryCooldown()

    // ── 调试开关 ───────────────────────────────────────────
    val debugUiEnabledFlow: Flow<Boolean>
    suspend fun updateDebugUiEnabled(enabled: Boolean)

    val showCameraInfoInPreviewFlow: Flow<Boolean>
    suspend fun updateShowCameraInfoInPreview(show: Boolean)

    val showFaceDebugOverlayFlow: Flow<Boolean>
    suspend fun updateShowFaceDebugOverlay(show: Boolean)

    val showLogOverlayFlow: Flow<Boolean>
    suspend fun updateShowLogOverlay(show: Boolean)

    // ── Shader 调试模式 ────────────────────────────────────
    val debugShaderModeFlow: Flow<Int>
    suspend fun updateDebugShaderMode(mode: Int)

    // ── 人脸检测 ───────────────────────────────────────────
    val faceDetectionEngineModeFlow: Flow<FaceDetectionEngineMode>
    suspend fun updateFaceDetectionEngineMode(mode: FaceDetectionEngineMode)

    val faceDetectionLandmarkModeFlow: Flow<Boolean>
    suspend fun updateFaceDetectionLandmarkMode(enabled: Boolean)

    val adaptiveFaceDetectionIntervalEnabledFlow: Flow<Boolean>
    suspend fun updateAdaptiveFaceDetectionIntervalEnabled(enabled: Boolean)

    val faceDetectIntervalProfileFlow: Flow<FaceDetectIntervalProfile>
    suspend fun updateFaceDetectIntervalProfile(profile: FaceDetectIntervalProfile)

    // ── 阶段独立配置（ROI / Landmark）────────────────────────
    val roiStageConfigFlow: Flow<StageConfig>
    suspend fun updateRoiStageConfig(config: StageConfig)

    val landmarkStageConfigFlow: Flow<StageConfig>
    suspend fun updateLandmarkStageConfig(config: StageConfig)

    // ── AI Agent ────────────────────────────────────────────
    val aiAgentModeFlow: Flow<AiAgentMode>
    suspend fun updateAiAgentMode(mode: AiAgentMode)

    val aiAgentPrivacyLevelFlow: Flow<AiAgentPrivacyLevel>
    suspend fun updateAiAgentPrivacyLevel(level: AiAgentPrivacyLevel)

    val aiAgentLocalModelFlow: Flow<String>
    suspend fun updateAiAgentLocalModel(modelId: String)

    // ── 远程模型配置（供应商维度） ────────────────────────────────
    val aiAgentRemoteModelConfigsFlow: Flow<String>
    suspend fun updateAiAgentRemoteModelConfigs(configsJson: String)

    val aiAgentSelectedRemoteModelFlow: Flow<String>
    suspend fun updateAiAgentSelectedRemoteModel(modelId: String)

    // ── 强制使用远程模型 ─────────────────────────────────────
    val aiAgentForceRemoteFlow: Flow<Boolean>
    suspend fun updateAiAgentForceRemote(enabled: Boolean)

    // ── Cloudflare AI Gateway Token ─────────────────────────
    val cloudflareGatewayTokenFlow: Flow<String>
    suspend fun updateCloudflareGatewayToken(token: String)

    // ── 语音控制 ────────────────────────────────────────────
    val voiceCommandModeFlow: Flow<VoiceCommandMode>
    suspend fun updateVoiceCommandMode(mode: VoiceCommandMode)

    val localAsrModelFlow: Flow<String>
    suspend fun updateLocalAsrModel(modelId: String)

    // ── 相机参数记忆 ──────────────────────────────────────────
    val cameraMemoryStateFlow: Flow<CameraMemoryState>
    suspend fun updateCameraMemoryState(state: CameraMemoryState)
    suspend fun resetCameraMemoryState()

    // ── 日志模块配置 ──────────────────────────────────────────
    val logModuleConfigFlow: Flow<com.picme.domain.model.LogModuleConfig>
    suspend fun updateLogModuleConfig(config: com.picme.domain.model.LogModuleConfig)

    // ── Chat 输入模式记忆 ────────────────────────────────────
    val chatInputModeFlow: Flow<String>
    suspend fun updateChatInputMode(mode: String)
}


