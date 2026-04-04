package com.picme.features.camera

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.picme.core.common.Logger
import com.picme.core.image.pixelfree.PixelFreeGLSurfaceView
import com.picme.core.image.rplan.RPlanBeautyPreviewProvider
import com.picme.data.preferences.BeautyStrategy
import com.picme.domain.model.BeautySettings

internal interface BeautyPreviewEngineStrategy {
    val strategy: BeautyStrategy

    fun bindPreview(previewUseCase: Preview, aspectRatio: Int): Boolean

    fun applyBeautySettings(settings: BeautySettings)

    fun applyFaceWarpParams(params: FaceWarpParams)

    fun release()
}

internal class PixelFreePreviewStrategy(
    private val previewView: PreviewView,
    private val pixelFreeView: PixelFreeGLSurfaceView,
    private val rPlanPreviewProvider: RPlanBeautyPreviewProvider
) : BeautyPreviewEngineStrategy {
    override val strategy: BeautyStrategy = BeautyStrategy.PIXEL_FREE

    override fun bindPreview(previewUseCase: Preview, aspectRatio: Int): Boolean {
        previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
        runCatching {
            rPlanPreviewProvider.initialize()
        }.onFailure { error ->
            Logger.w("Camera", "PixelFree bridge warm-up failed", error)
        }
        Logger.i("Camera", "Preview connected via PreviewView for PixelFree strategy")
        return false
    }

    override fun applyBeautySettings(settings: BeautySettings) {
        pixelFreeView.queueEvent {
            pixelFreeView.setSmoothingStrength(settings.smoothing / 100f)
            pixelFreeView.setWhiteningStrength(settings.whitening / 100f)
            pixelFreeView.setBigEyesStrength((settings.bigEyes / 100f * 1.35f).coerceIn(0f, 1f))
            pixelFreeView.setSlimFaceStrength(((settings.slimFace + 50f) / 100f).coerceIn(0f, 1f))
        }

        runCatching {
            rPlanPreviewProvider.initialize()
            rPlanPreviewProvider.updateFilters(settings)
        }.onFailure { error ->
            Logger.w("Camera", "PixelFree bridge update failed", error)
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
            Logger.w("Camera", "PixelFree bridge face params update failed", error)
        }
    }

    override fun release() = Unit
}

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

internal data class PreviewStrategyBundle(
    val rPlanStrategy: BeautyPreviewEngineStrategy,
    val pixelFreeStrategy: BeautyPreviewEngineStrategy,
    val activeStrategy: BeautyPreviewEngineStrategy
)

@Composable
internal fun rememberPreviewStrategyBundle(
    beautyStrategy: BeautyStrategy,
    previewView: PreviewView,
    pixelFreeView: PixelFreeGLSurfaceView,
    rPlanPreviewProvider: RPlanBeautyPreviewProvider,
    onRPlanWarmUpFallback: (String) -> Unit
): PreviewStrategyBundle {
    val rPlanStrategy = remember(previewView, rPlanPreviewProvider) {
        RPlanPreviewStrategy(
            previewView = previewView,
            rPlanPreviewProvider = rPlanPreviewProvider,
            onWarmUpFallback = onRPlanWarmUpFallback
        )
    }
    val pixelFreeStrategy = remember(previewView, pixelFreeView, rPlanPreviewProvider) {
        PixelFreePreviewStrategy(
            previewView = previewView,
            pixelFreeView = pixelFreeView,
            rPlanPreviewProvider = rPlanPreviewProvider
        )
    }

    val activeStrategy = when (beautyStrategy) {
        BeautyStrategy.R_PLAN -> rPlanStrategy
        BeautyStrategy.PIXEL_FREE -> pixelFreeStrategy
    }

    return PreviewStrategyBundle(
        rPlanStrategy = rPlanStrategy,
        pixelFreeStrategy = pixelFreeStrategy,
        activeStrategy = activeStrategy
    )
}

