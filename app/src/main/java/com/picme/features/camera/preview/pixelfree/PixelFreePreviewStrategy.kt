package com.picme.features.camera.preview.pixelfree

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.picme.core.common.Logger
import com.picme.core.image.gl.GlBeautyPreviewProvider
import com.picme.core.image.gl.toBeautyParams
import com.picme.core.image.pixelfree.PixelFreeGLSurfaceView
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.model.BeautySettings
import com.picme.features.camera.AspectRatio
import com.picme.features.camera.preview.core.BeautyPreviewEngineStrategy
import com.picme.features.camera.preview.core.FaceWarpParams

internal enum class PixelFreePreviewLinkMode {
    PROVIDER,
    RAW,
    PREVIEW_FALLBACK
}

internal class PixelFreePreviewStrategy(
    private val previewView: PreviewView,
    private val pixelFreeView: PixelFreeGLSurfaceView,
    private val glPreviewProvider: GlBeautyPreviewProvider?,
    private val onPreviewLinkModeChanged: (PixelFreePreviewLinkMode) -> Unit,
    private val onPreviewLinkReasonChanged: (String?) -> Unit
) : BeautyPreviewEngineStrategy {
    override val strategy: BeautyStrategy = BeautyStrategy.PIXEL_FREE

    override fun bindPreview(previewUseCase: Preview, aspectRatio: Int): Boolean {
        val provider = glPreviewProvider
        if (provider == null) {
            return bindPreviewFallback(previewUseCase, "provider unavailable")
        }

        return try {
            provider.initialize()
            provider.setScaleMode(isFillCenter = aspectRatio == AspectRatio.RATIO_FULL)

            val mainExecutor = ContextCompat.getMainExecutor(previewView.context)
            previewUseCase.setSurfaceProvider { request ->
                val resolution = request.resolution
                provider.setCameraInputBufferSize(
                    width = resolution.width,
                    height = resolution.height
                )
                val previewSurface = provider.createPreviewSurface()
                request.provideSurface(previewSurface, mainExecutor) { result ->
                    Logger.d("Camera", "PixelFree compat surface request completed: $result")
                }
            }

            onPreviewLinkModeChanged(PixelFreePreviewLinkMode.PROVIDER)
            onPreviewLinkReasonChanged(null)
            Logger.i("Camera", "PixelFree preview bound with realtime provider path, aspectRatio=$aspectRatio")
            true
        } catch (error: Throwable) {
            Logger.w("Camera", "PixelFree provider path failed, fallback to PreviewView", error)
            bindPreviewFallback(previewUseCase, error.message ?: "provider path failure")
        }
    }

    private fun bindPreviewFallback(previewUseCase: Preview, reason: String): Boolean {
        onPreviewLinkModeChanged(PixelFreePreviewLinkMode.PREVIEW_FALLBACK)
        onPreviewLinkReasonChanged(reason)
        previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
        Logger.w("Camera", "PixelFree preview fallback to PreviewView: $reason")
        return false
    }

    override fun applyBeautySettings(settings: BeautySettings) {
        runCatching {
            glPreviewProvider?.updateFilters(settings.toBeautyParams())
        }.onFailure { error ->
            Logger.w("Camera", "PixelFree provider beauty update failed", error)
        }

        runCatching {
            pixelFreeView.queueEvent {
                pixelFreeView.setSmoothingStrength(settings.smoothing / 100f)
                pixelFreeView.setWhiteningStrength(settings.whitening / 100f)
                pixelFreeView.setBigEyesStrength((settings.bigEyes / 100f * 1.35f).coerceIn(0f, 1f))
                pixelFreeView.setSlimFaceStrength(((settings.slimFace + 50f) / 100f).coerceIn(0f, 1f))

                // 设置唇色（妆容调节）
                pixelFreeView.setLipColorStrength((settings.lipColor / 100f).coerceIn(0f, 1f))
            }
        }.onFailure { error ->
            Logger.w("Camera", "PixelFree sdk beauty update failed", error)
        }
    }

    override fun applyFaceWarpParams(params: FaceWarpParams) {
        runCatching {
            glPreviewProvider?.updateFaceWarpParams(
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
            glPreviewProvider?.updateLipMaskPoints(
                outerPoints = params.lipOuterContourPoints.map { contourPoint ->
                    Pair(contourPoint.x, contourPoint.y)
                },
                innerPoints = params.lipInnerContourPoints.map { contourPoint ->
                    Pair(contourPoint.x, contourPoint.y)
                }
            )
        }.onFailure { error ->
            Logger.w("Camera", "PixelFree provider face params update failed", error)
        }
    }

    override fun release() {
        pixelFreeView.release()
        glPreviewProvider?.release()
    }
}

