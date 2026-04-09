package com.picme.features.camera.preview.gl

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.picme.core.common.Logger
import com.picme.core.image.gl.GlBeautyPreviewProvider
import com.picme.core.image.gl.toBeautyParams
import com.picme.data.preferences.BeautyStrategy
import com.picme.domain.model.BeautySettings
import com.picme.features.camera.AspectRatio
import com.picme.features.camera.preview.core.BeautyPreviewEngineStrategy
import com.picme.features.camera.preview.core.FaceWarpParams

internal class GlBeautyPreviewStrategy(
    private val previewView: PreviewView,
    private val glBeautyPreviewProvider: GlBeautyPreviewProvider,
    private val onWarmUpFallback: (String) -> Unit
) : BeautyPreviewEngineStrategy {
    override val strategy: BeautyStrategy = BeautyStrategy.R_PLAN

    override fun bindPreview(previewUseCase: Preview, aspectRatio: Int): Boolean {
        return try {
            glBeautyPreviewProvider.initialize()
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
        }.onFailure { error ->
            Logger.w("Camera", "GL beauty face params update failed", error)
        }
    }

    override fun release() {
        glBeautyPreviewProvider.release()
    }
}

