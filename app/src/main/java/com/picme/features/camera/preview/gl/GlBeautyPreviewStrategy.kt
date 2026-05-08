package com.picme.features.camera.preview.gl

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.picme.core.common.Logger
import com.picme.beauty.api.BeautyPreviewEngine
import com.picme.beauty.api.toBeautyParams
import com.picme.domain.model.BeautyStrategy
import com.picme.beauty.api.BeautySettings
import com.picme.features.camera.AspectRatio
import com.picme.features.camera.preview.core.BeautyPreviewEngineStrategy
import com.picme.beauty.api.facedetect.FaceWarpParams

internal class GlBeautyPreviewStrategy(
    private val previewView: PreviewView,
    private val glBeautyPreviewProvider: BeautyPreviewEngine,
    private val onWarmUpFallback: (String) -> Unit,
    private val lensFacing: Int = CameraSelector.LENS_FACING_BACK
) : BeautyPreviewEngineStrategy {
    override val strategy: BeautyStrategy = BeautyStrategy.BIG_BEAUTY

    override fun bindPreview(previewUseCase: Preview, aspectRatio: Int): Boolean {
        return try {
            glBeautyPreviewProvider.initialize()
            glBeautyPreviewProvider.setIsFrontCamera(lensFacing == CameraSelector.LENS_FACING_FRONT)
            glBeautyPreviewProvider.setScaleMode(isFillCenter = aspectRatio == AspectRatio.RATIO_FULL)

            val mainExecutor = ContextCompat.getMainExecutor(previewView.context)
            previewUseCase.setSurfaceProvider { request ->
                val resolution = request.resolution
                glBeautyPreviewProvider.setCameraInputBufferSize(
                    width = resolution.width,
                    height = resolution.height
                )
                val previewSurface = glBeautyPreviewProvider.createPreviewSurface()
                request.provideSurface(previewSurface, mainExecutor) { result ->
                    Logger.d("Camera", "GL beauty surface request completed: $result")
                    // [关键修复] SurfaceRequest 完成后必须释放 Surface，让 BufferQueue 生产者端断开，
                    // 否则下次 createPreviewSurface() 返回的 Surface 无法重新建立生产者连接，导致画面静止
                    previewSurface.release()
                }
            }

            Logger.i("Camera", "Preview connected via GL beauty provider surface, aspectRatio=$aspectRatio")
            true
        } catch (error: Throwable) {
            Logger.w("Camera", "GL beauty warm-up failed, fallback to PreviewView", error)
            previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
            onWarmUpFallback(error.message ?: "warm-up error")
            false
        }
    }

    override fun applyBeautySettings(settings: BeautySettings) {
        try {
            glBeautyPreviewProvider.initialize()
            glBeautyPreviewProvider.updateFilters(settings.toBeautyParams())
        } catch (error: Throwable) {
            Logger.w("Camera", "GL beauty update failed", error)
        }
    }

    override fun applyFaceWarpParams(params: FaceWarpParams) {
        runCatching {
            glBeautyPreviewProvider.updateFaceWarpParams(
                faceCenterX = params.faceCenterX,
                faceCenterY = params.faceCenterY,
                leftEyeX = params.leftEyeX,
                leftEyeY = params.leftEyeY,
                rightEyeX = params.rightEyeX,
                rightEyeY = params.rightEyeY,
                mouthCenterX = params.mouthCenterX,
                mouthCenterY = params.mouthCenterY,
                mouthLeftX = params.mouthLeftX,
                mouthLeftY = params.mouthLeftY,
                mouthRightX = params.mouthRightX,
                mouthRightY = params.mouthRightY,
                upperLipCenterX = params.upperLipCenterX,
                upperLipCenterY = params.upperLipCenterY,
                lowerLipCenterX = params.lowerLipCenterX,
                lowerLipCenterY = params.lowerLipCenterY,
                faceRadius = params.faceRadius,
                hasFace = params.hasFace
            )
            glBeautyPreviewProvider.updateLipMaskPoints(
                outerPoints = params.lipOuterContourPoints.map { contourPoint ->
                    Pair(contourPoint.x, contourPoint.y)
                },
                innerPoints = params.lipInnerContourPoints.map { contourPoint ->
                    Pair(contourPoint.x, contourPoint.y)
                }
            )
            glBeautyPreviewProvider.updateCheekContourPoints(
                leftCheekPoints = params.leftCheekContourPoints.map { contourPoint ->
                    Pair(contourPoint.x, contourPoint.y)
                },
                rightCheekPoints = params.rightCheekContourPoints.map { contourPoint ->
                    Pair(contourPoint.x, contourPoint.y)
                }
            )
            // 传递106点关键点给风格瘦脸/大眼
            val bigBeautyLandmarks = params.bigBeautyLandmarks
            if (bigBeautyLandmarks.hasFace && bigBeautyLandmarks.points.isNotEmpty()) {
                val landmarks106 = FloatArray(bigBeautyLandmarks.points.size * 2)
                for (i in bigBeautyLandmarks.points.indices) {
                    val point = bigBeautyLandmarks.points[i]
                    landmarks106[i * 2] = point.x
                    landmarks106[i * 2 + 1] = point.y
                }
                glBeautyPreviewProvider.updateFacePoints106(landmarks106)
            }
        }.onFailure { error ->
            Logger.w("Camera", "GL beauty face params update failed", error)
        }
    }

    override fun release() {
        glBeautyPreviewProvider.release()
    }
}

