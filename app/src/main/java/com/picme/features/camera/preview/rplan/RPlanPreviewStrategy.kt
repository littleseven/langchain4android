package com.picme.features.camera.preview.rplan

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.picme.core.common.Logger
import com.picme.core.image.rplan.RPlanBeautyPreviewProvider
import com.picme.data.preferences.BeautyStrategy
import com.picme.domain.model.BeautySettings
import com.picme.features.camera.AspectRatio
import com.picme.features.camera.preview.core.BeautyPreviewEngineStrategy
import com.picme.features.camera.preview.core.FaceWarpParams

internal class RPlanPreviewStrategy(
    private val previewView: PreviewView,
    private val rPlanPreviewProvider: RPlanBeautyPreviewProvider,
    private val onWarmUpFallback: (String) -> Unit
) : BeautyPreviewEngineStrategy {
    override val strategy: BeautyStrategy = BeautyStrategy.R_PLAN

    override fun bindPreview(previewUseCase: Preview, aspectRatio: Int): Boolean {
        return try {
            rPlanPreviewProvider.initialize()
            rPlanPreviewProvider.setScaleMode(isFillCenter = aspectRatio == AspectRatio.RATIO_FULL)

            val mainExecutor = ContextCompat.getMainExecutor(previewView.context)
            previewUseCase.setSurfaceProvider { request ->
                val resolution = request.resolution
                rPlanPreviewProvider.setCameraInputBufferSize(
                    width = resolution.width,
                    height = resolution.height
                )
                val previewSurface = rPlanPreviewProvider.createPreviewSurface()
                request.provideSurface(previewSurface, mainExecutor) { result ->
                    Logger.d("Camera", "R Plan surface request completed: $result")
                }
            }

            Logger.i("Camera", "Preview connected via R Plan provider surface, aspectRatio=$aspectRatio")
            true
        } catch (error: Throwable) {
            Logger.w("Camera", "R Plan warm-up failed, fallback to PreviewView", error)
            previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
            onWarmUpFallback(error.message ?: "warm-up error")
            false
        }
    }

    override fun applyBeautySettings(settings: BeautySettings) {
        try {
            rPlanPreviewProvider.initialize()
            rPlanPreviewProvider.updateFilters(settings)
        } catch (error: Throwable) {
            Logger.w("Camera", "R Plan update failed", error)
        }
    }

    override fun applyFaceWarpParams(params: FaceWarpParams) {
        runCatching {
            rPlanPreviewProvider.updateFaceWarpParams(
                faceCenterX = params.faceCenterX,
                faceCenterY = params.faceCenterY,
                leftEyeX = params.leftEyeX,
                leftEyeY = params.leftEyeY,
                rightEyeX = params.rightEyeX,
                rightEyeY = params.rightEyeY,
                faceRadius = params.faceRadius,
                hasFace = params.hasFace
            )
        }.onFailure { error ->
            Logger.w("Camera", "R Plan face params update failed", error)
        }
    }

    override fun release() {
        rPlanPreviewProvider.release()
    }
}

