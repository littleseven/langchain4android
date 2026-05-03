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
import com.picme.beauty.gpupixel.GpupixelBeautyPreviewProvider
import com.picme.core.common.Logger
import com.picme.domain.model.BeautySettings
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.domain.model.MediaType
import com.picme.features.camera.facedetect.FaceDetectorManager
import com.picme.features.camera.preview.core.FaceWarpParams
import com.pixpark.gpupixel.GPUPixel
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
    gpupixelProvider: GpupixelBeautyPreviewProvider?,
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

    // GPUPixel 模式：通过 ImageAnalysis → onRgbaFrame 路径传递帧，不需要 Preview usecase。
    // Preview usecase 的 SurfaceProvider 生命周期与 PreviewView 绑定；GPUPixel 模式下
    // PreviewView 不可见，其 Surface 随时可能销毁，导致 CameraX 反复触发 session 重建。
    // 解决：GPUPixel 模式跳过 Preview，只绑 imageCapture + imageAnalysis。
    val isGpuPixelMode = beautyStrategy == BeautyStrategy.GPUPIXEL

    val useCaseGroup = if (aspectRatio == AspectRatio.RATIO_FULL && !isGpuPixelMode) {
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

    // 非 useCaseGroup 模式（非全屏 aspectRatio，或 GPUPixel 模式）需要单独的 Preview
    // GPUPixel 模式下不创建 Preview，直接绑 imageCapture + imageAnalysis
    val preview = if (useCaseGroup == null && !isGpuPixelMode) {
        Preview.Builder()
            .setTargetAspectRatio(toCameraAspectRatio(aspectRatio))
            .build()
            .also { previewUseCase -> bindPreviewSurfaceProvider(previewUseCase) }
    } else {
        null
    }

    // GPUPixel 模式：初始化 provider（不依赖 Preview）
    if (isGpuPixelMode && gpupixelProvider != null) {
        gpupixelProvider.setScaleMode(isFillCenter = aspectRatio == AspectRatio.RATIO_FULL)
        gpupixelProvider.initialize()
        android.util.Log.d("PicMe:Camera", "GPUPixel mode: provider initialized, skipping Preview usecase")
    }

    // faceDetectorManager 由 AppContainer 统一管理，通过参数传入

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

        if (beautyStrategy == BeautyStrategy.GPUPIXEL && gpupixelProvider != null) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                try {
                    // 合并 Native 转换：一次提取 YUV，同时输出 I420（渲染）+ RGBA（人脸检测）
                    val buffers: Array<ByteBuffer>? = GPUPixel.YUV_420_888toI420AndRGBA(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees
                    )
                    if (buffers != null) {
                        val (rotatedWidth, rotatedHeight) = when (imageProxy.imageInfo.rotationDegrees) {
                            90, 270 -> Pair(mediaImage.height, mediaImage.width)
                            else -> Pair(mediaImage.width, mediaImage.height)
                        }
                        gpupixelProvider.onYuvFrame(
                            buffers[0], // Y
                            buffers[1], // U
                            buffers[2], // V
                            rotatedWidth,
                            rotatedHeight,
                            0,
                            buffers[3]  // RGBA for face detection
                        )
                    }
                } catch (e: Exception) {
                    Logger.e("Camera", "GPUPixel frame conversion error", e)
                }
            }
            // GPUPixel 预览链路仍通过 provider 产出调试点位；这里复用当前选择的人脸检测引擎，
            // 用于焦点跟踪与静态对照链路。
            handleImageAnalysisFrameMediaPipe(
                imageProxy = imageProxy,
                previewView = previewView,
                faceDetectorManager = faceDetectorManager,
                lensFacing = lensFacing,
                detectionEngineMode = detectionEngineMode,
                onFacePointChanged = onFacePointChanged,
                onFaceWarpParamsChanged = { mediaPipeParams ->
                    // 双模式：将 MediaPipe 结果合并到现有的 GPUPixel 参数中
                    // 注意：GPUPixel 点位由 onGpuPixelLandmarksDetected 回调单独更新
                    // 这里只更新 bigBeautyLandmarks 部分
                    onFaceWarpParamsChanged(mediaPipeParams)
                },
                onShowFocusIndicatorChanged = onShowFocusIndicatorChanged,
                isDualMode = true
            )
        } else {
            // 所有非 GPUPixel 预览模式：使用当前选定的人脸检测引擎。
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
    }

    try {
        cameraProvider.unbindAll()

        val camera = when {
            useCaseGroup != null -> {
                // RATIO_FULL + 非 GPUPixel：使用 UseCaseGroup（含 Preview + ViewPort）
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup)
            }
            isGpuPixelMode -> {
                // GPUPixel 模式：只绑 imageCapture + imageAnalysis，不需要 Preview
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageCapture,
                    imageAnalysis
                )
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

