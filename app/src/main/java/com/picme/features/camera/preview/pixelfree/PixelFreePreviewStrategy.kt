package com.picme.features.camera.preview.pixelfree

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import com.picme.core.common.Logger
import com.picme.core.image.pixelfree.PixelFreeGLSurfaceView
import com.picme.data.preferences.BeautyStrategy
import com.picme.domain.model.BeautySettings
import com.picme.features.camera.preview.core.BeautyPreviewEngineStrategy
import com.picme.features.camera.preview.core.FaceWarpParams

internal class PixelFreePreviewStrategy(
    private val previewView: PreviewView,
    private val pixelFreeView: PixelFreeGLSurfaceView
) : BeautyPreviewEngineStrategy {
    override val strategy: BeautyStrategy = BeautyStrategy.PIXEL_FREE

    override fun bindPreview(previewUseCase: Preview, aspectRatio: Int): Boolean {
        previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
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
    }

    override fun applyFaceWarpParams(params: FaceWarpParams) = Unit

    override fun release() = Unit
}

