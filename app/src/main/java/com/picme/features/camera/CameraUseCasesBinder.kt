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
import com.google.mlkit.vision.face.FaceDetector
import com.picme.beauty.gpupixel.GpupixelBeautyPreviewProvider
import com.pixpark.gpupixel.GPUPixel
import com.picme.core.common.Logger
import com.picme.domain.model.BeautySettings
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.model.MediaType
import com.picme.features.camera.preview.core.FaceWarpParams
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
    faceDetector: FaceDetector,
    beautySettings: BeautySettings,
    beautyStrategy: BeautyStrategy,
    videoCapture: VideoCapture<Recorder>,
    gpupixelProvider: GpupixelBeautyPreviewProvider?,
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
    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
        frameCount++
        if (frameCount % 30 == 0) {
            android.util.Log.d("PicMe:Camera", "ImageAnalysis frame received: #${frameCount}")
            Logger.d("Camera", "ImageAnalysis frame received: #${frameCount}")
        }

        if (beautyStrategy == BeautyStrategy.GPUPIXEL && gpupixelProvider != null) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                try {
                    val rgba = GPUPixel.YUV_420_888toRGBA(mediaImage)
                    if (rgba != null) {
                        val width = mediaImage.width
                        val height = mediaImage.height
                        val rotation = imageProxy.imageInfo.rotationDegrees
                        val (outWidth, outHeight, rotatedData) = when (rotation) {
                            90, 270 -> {
                                val rotated = GPUPixel.rotateRgbaImage(rgba, width, height, rotation)
                                Triple(height, width, rotated)
                            }
                            180 -> {
                                val rotated = GPUPixel.rotateRgbaImage(rgba, width, height, rotation)
                                Triple(width, height, rotated)
                            }
                            else -> Triple(width, height, rgba)
                        }
                        gpupixelProvider.onRgbaFrame(rotatedData, outWidth, outHeight)
                    }
                } catch (e: Exception) {
                    Logger.e("Camera", "GPUPixel frame conversion error", e)
                }
            }
        }

        handleImageAnalysisFrame(
            imageProxy = imageProxy,
            previewView = previewView,
            faceDetector = faceDetector,
            lensFacing = lensFacing,
            beautySettings = beautySettings,
            onFacePointChanged = onFacePointChanged,
            onFaceWarpParamsChanged = onFaceWarpParamsChanged,
            onShowFocusIndicatorChanged = onShowFocusIndicatorChanged
        )
    }

    try {
        cameraProvider.unbindAll()

        val camera = if (useCaseGroup != null) {
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                useCaseGroup
            )
        } else {
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

