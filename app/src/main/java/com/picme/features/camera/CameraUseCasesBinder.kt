package com.picme.features.camera

import android.content.Context
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Offset
import com.google.common.util.concurrent.ListenableFuture
import com.picme.core.common.Logger
import com.picme.beauty.api.BeautySettings
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.domain.model.MediaType
import com.picme.features.camera.facedetect.FaceDetectorManager
import com.picme.features.camera.preview.core.FaceWarpParams
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService

@ExperimentalGetImage
internal fun bindCameraUseCases(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    cameraProviderFuture: ListenableFuture<ProcessCameraProvider>,
    lensFacing: Int,
    captureMode: MediaType,
    aspectRatio: Int,
    previewView: PreviewView,
    bindPreviewSurfaceProvider: (Preview) -> Unit,
    cameraExecutor: ExecutorService,
    beautySettings: BeautySettings,
    beautyStrategy: BeautyStrategy,
    detectionEngineMode: FaceDetectionEngineMode,
    videoCapture: VideoCapture<Recorder>,
    faceDetectorManager: FaceDetectorManager,
    onImageCaptureChanged: (ImageCapture) -> Unit,
    onCameraControlChanged: (CameraControl) -> Unit,
    onZoomRatioChanged: (Float) -> Unit,
    onZoomRangeChanged: (minZoom: Float, maxZoom: Float) -> Unit,
    onActualLensFacingChanged: (Int) -> Unit,
    onFacePointChanged: (Offset) -> Unit,
    onFaceWarpParamsChanged: (FaceWarpParams) -> Unit,
    onShowFocusIndicatorChanged: (Boolean) -> Unit
) {
    android.util.Log.d("PicMe:Camera", "bindCameraUseCases START: aspectRatio=$aspectRatio, captureMode=$captureMode")
    val cameraProvider = cameraProviderFuture.get()
    Logger.d("Camera", "Binding camera with aspectRatio=$aspectRatio")

    val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

    val imageAnalysis = ImageAnalysis.Builder()
        .setTargetAspectRatio(toCameraAspectRatio(aspectRatio))
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        .build()

    val imageCapture = ImageCapture.Builder()
        .setTargetAspectRatio(toCameraAspectRatio(aspectRatio))
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()
    onImageCaptureChanged(imageCapture)

    val useCaseGroup = if (aspectRatio == AspectRatio.RATIO_FULL) {
        val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        val preview = Preview.Builder()
            .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
            .setTargetRotation(rotation)
            .build()
            .also { previewUseCase -> bindPreviewSurfaceProvider(previewUseCase) }

        imageCapture.targetRotation = rotation

        val displayMetrics = context.resources.displayMetrics
        val viewport = androidx.camera.core.ViewPort.Builder(
            android.util.Rational(displayMetrics.widthPixels, displayMetrics.heightPixels),
            rotation
        ).build()

        androidx.camera.core.UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(imageCapture)
            .addUseCase(imageAnalysis)
            .setViewPort(viewport)
            .build()
    } else {
        null
    }

    val preview = if (useCaseGroup == null) {
        Preview.Builder()
            .setTargetAspectRatio(toCameraAspectRatio(aspectRatio))
            .build()
            .also { previewUseCase -> bindPreviewSurfaceProvider(previewUseCase) }
    } else {
        null
    }

    var frameCount = 0
    var lastFrameLogMs = 0L
    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
        frameCount++
        // 限流：帧计数日志 1 秒最多打一次
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastFrameLogMs >= 1_000L) {
            Logger.dThrottled("Camera", "Camera:frameCount", "ImageAnalysis frame #$frameCount (~${frameCount}fps since bind) strategy=$beautyStrategy")
            lastFrameLogMs = nowMs
        }

        handleImageAnalysisFrameMediaPipe(
            imageProxy = imageProxy,
            previewView = previewView,
            faceDetectorManager = faceDetectorManager,
            lensFacing = lensFacing,
            detectionEngineMode = detectionEngineMode,
            onFacePointChanged = onFacePointChanged,
            onFaceWarpParamsChanged = onFaceWarpParamsChanged,
            onShowFocusIndicatorChanged = onShowFocusIndicatorChanged,
            isDualMode = false
        )
    }

    try {
        cameraProvider.unbindAll()

        val camera = when {
            useCaseGroup != null -> {
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup)
            }
            else -> {
                when (captureMode) {
                    MediaType.PHOTO, MediaType.PORTRAIT, MediaType.PRO, MediaType.DOCUMENT -> {
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview!!,
                            imageCapture,
                            imageAnalysis
                        )
                    }
                    MediaType.VIDEO -> {
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview!!,
                            videoCapture,
                            imageAnalysis
                        )
                    }
                }
            }
        }

        onCameraControlChanged(camera.cameraControl)
        camera.cameraInfo.zoomState.observe(lifecycleOwner) { state ->
            onZoomRatioChanged(state.zoomRatio)
            onZoomRangeChanged(state.minZoomRatio, state.maxZoomRatio)
        }
        onActualLensFacingChanged(camera.cameraInfo.lensFacing)
        Logger.d(
            "PicMe:Camera",
            "Camera bound: lensFacing=${camera.cameraInfo.lensFacing}, selector=$lensFacing, " +
                "useCaseGroup=${useCaseGroup != null}, aspectRatio=$aspectRatio"
        )
    } catch (error: Exception) {
        Logger.e("Camera", "Binding failed", error)
    }
}

