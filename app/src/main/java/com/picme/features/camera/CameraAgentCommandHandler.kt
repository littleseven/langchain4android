package com.picme.features.camera

import android.content.Context
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.Recorder
import com.picme.beauty.api.BeautyPreviewEngine
import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.recorder.BeautyVideoRecorder
import com.picme.core.common.Logger
import com.picme.core.image.ImageProcessor
import com.picme.domain.model.AiAgentCommand
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.model.MediaType
import com.picme.features.camera.state.CameraStateMachine
import com.picme.features.camera.state.CameraStateManager
import com.picme.features.gallery.MediaViewModel
import kotlinx.coroutines.CoroutineScope

private const val TAG = "Camera"

internal class CameraAgentCommandHandler(
    private val context: Context,
    private val viewModel: MediaViewModel,
    private val imageProcessor: ImageProcessor,
    private val beautyVideoRecorder: BeautyVideoRecorder,
    private val glPreviewProvider: BeautyPreviewEngine?,
    private val videoCapture: VideoCapture<Recorder>,
    private val cameraStateManager: CameraStateManager,
    private val coroutineScope: CoroutineScope,
    private val onNavigateToSettings: () -> Unit,
    private val onNavigateToGallery: () -> Unit
) {
    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    var captureMode: MediaType = MediaType.PHOTO
    var isRecording: Boolean = false
    var recording: Recording? = null
    var imageCapture: ImageCapture? = null
    var selectedFilter: FilterType = FilterType.NONE
    var beautySettings: BeautySettings = BeautySettings(enabled = false)
    var beautyStrategy: BeautyStrategy = BeautyStrategy.BIG_BEAUTY
    var exposureCompensation: Int = 0
    var zoomRatio: Float = 1f
    var minZoomRatio: Float = 1f
    var maxZoomRatio: Float = 1f
    var cameraControl: CameraControl? = null
    var currentScene: ScenePreset = ScenePreset.NONE
    var aspectRatio: Int = AspectRatio.RATIO_FULL

    var onLensFacingChanged: ((Int) -> Unit)? = null
    var onCaptureModeChanged: ((MediaType) -> Unit)? = null
    var onIsRecordingChanged: ((Boolean) -> Unit)? = null
    var onRecordingChanged: ((Recording?) -> Unit)? = null
    var onSelectedFilterChanged: ((FilterType) -> Unit)? = null
    var onBeautySettingsChanged: ((BeautySettings) -> Unit)? = null
    var onExposureCompensationChanged: ((Int) -> Unit)? = null
    var onZoomRatioChanged: ((Float) -> Unit)? = null
    var onAspectRatioChanged: ((Int) -> Unit)? = null
    var onCurrentSceneChanged: ((ScenePreset) -> Unit)? = null

    fun handleCommand(cmd: AiAgentCommand) {
        Logger.i(TAG, "agentCommandHandler executing: ${cmd.javaClass.simpleName}")
        when (cmd) {
            is AiAgentCommand.AdjustBeauty -> {
                val newSettings = resolveNextBeautySettings(
                    currentSettings = beautySettings,
                    updatedSettings = cmd.settings
                )
                beautySettings = newSettings
                onBeautySettingsChanged?.invoke(newSettings)
            }
            is AiAgentCommand.SwitchFilter -> {
                selectedFilter = cmd.filterType
                val newSettings = beautySettings.copy(colorFilter = cmd.filterType)
                beautySettings = newSettings
                onBeautySettingsChanged?.invoke(newSettings)
                onSelectedFilterChanged?.invoke(cmd.filterType)
            }
            is AiAgentCommand.SwitchStyle -> {
                val newSettings = beautySettings.copy(styleFilter = cmd.styleFilter)
                beautySettings = newSettings
                onBeautySettingsChanged?.invoke(newSettings)
            }
            is AiAgentCommand.SwitchScene -> {
                val scene = when (cmd.sceneName.lowercase()) {
                    "night" -> ScenePreset.NIGHT
                    "moon" -> ScenePreset.MOON
                    else -> ScenePreset.NONE
                }
                currentScene = scene
                onCurrentSceneChanged?.invoke(scene)
            }
            is AiAgentCommand.SwitchRatio -> {
                if (!cameraStateManager.canRebind()) {
                    Logger.w(TAG, "Ratio switch rejected: state=${cameraStateManager.getState().name}")
                    return
                }
                val normalizedRatio = cmd.ratio.replace("_", ":")
                val ratio = when (normalizedRatio) {
                    "4:3" -> AspectRatio.RATIO_4_3
                    "16:9" -> AspectRatio.RATIO_16_9
                    else -> AspectRatio.RATIO_FULL
                }
                aspectRatio = ratio
                onAspectRatioChanged?.invoke(ratio)
            }
            is AiAgentCommand.AdjustExposure -> {
                val newExposure = cmd.exposure.coerceIn(-2, 2)
                exposureCompensation = newExposure
                cameraControl?.setExposureCompensationIndex(newExposure)
                onExposureCompensationChanged?.invoke(newExposure)
            }
            is AiAgentCommand.AdjustZoom -> {
                val clampedZoom = cmd.zoomRatio.coerceIn(minZoomRatio, maxZoomRatio)
                zoomRatio = clampedZoom
                cameraControl?.setZoomRatio(clampedZoom)
                onZoomRatioChanged?.invoke(clampedZoom)
            }
            is AiAgentCommand.FlipCamera -> {
                if (!cameraStateManager.canRebind()) {
                    Logger.w(TAG, "Flip rejected: state=${cameraStateManager.getState().name}")
                    return
                }
                runCatching {
                    cameraStateManager.transition(
                        CameraStateMachine.Rebinding(
                            CameraStateMachine.RebindReason.LENS_FACING_CHANGED
                        )
                    )
                }.onFailure {
                    Logger.w(TAG, "FlipCamera transition to Rebinding failed: ${it.message}")
                    return
                }
                val nextLens = nextLensFacing(lensFacing)
                lensFacing = nextLens
                onLensFacingChanged?.invoke(nextLens)
            }
            is AiAgentCommand.CapturePhoto -> {
                Logger.i(TAG, "Executing CapturePhoto command")
                if (!cameraStateManager.canCapture()) {
                    Logger.w(TAG, "Capture rejected: state=${cameraStateManager.getState().name}")
                    return
                }
                executeCapture()
            }
            is AiAgentCommand.ToggleRecording -> {
                executeCapture()
            }
            is AiAgentCommand.SwitchMode -> {
                captureMode = cmd.mode
                onCaptureModeChanged?.invoke(cmd.mode)
            }
            is AiAgentCommand.NavigateTo -> {
                when (cmd.destination.lowercase()) {
                    "settings" -> onNavigateToSettings()
                    "gallery" -> onNavigateToGallery()
                    "debug" -> { }
                    "model_center" -> onNavigateToSettings()
                    else -> Logger.w(TAG, "Unknown navigation destination: ${cmd.destination}")
                }
            }
            is AiAgentCommand.GoBack -> {
                Logger.d(TAG, "GoBack ignored on camera screen (root)")
            }
            is AiAgentCommand.TextReply -> {
            }
            is AiAgentCommand.Delay -> {
                Logger.i(TAG, "Delay command: ${cmd.delayMs}ms")
            }
            is AiAgentCommand.BatchExecute -> {
                Logger.w(TAG, "BatchExecute should be handled at top level, not in agentCommandHandler")
            }
        }
    }

    private fun executeCapture() {
        handleCaptureClick(
            context = context,
            captureMode = captureMode,
            isRecording = isRecording,
            recording = recording,
            videoCapture = videoCapture,
            viewModel = viewModel,
            imageCapture = imageCapture,
            imageProcessor = imageProcessor,
            selectedFilter = selectedFilter,
            beautySettings = beautySettings,
            lensFacing = lensFacing,
            cachedFaces = emptyList(),
            beautyStrategy = beautyStrategy,
            glPreviewProvider = glPreviewProvider,
            beautyVideoRecorder = beautyVideoRecorder,
            onRecordingChanged = { updated ->
                recording = updated
                onRecordingChanged?.invoke(updated)
            },
            onIsRecordingChanged = { recordingFlag ->
                isRecording = recordingFlag
                onIsRecordingChanged?.invoke(recordingFlag)
            },
            coroutineScope = coroutineScope,
            cameraStateManager = cameraStateManager
        )
    }
}
