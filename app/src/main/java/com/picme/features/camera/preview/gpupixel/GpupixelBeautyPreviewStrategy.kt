package com.picme.features.camera.preview.gpupixel

import androidx.camera.core.Preview
import com.picme.beauty.api.BeautyParams
import com.picme.beauty.gpupixel.GpupixelBeautyPreviewProvider
import com.picme.core.common.Logger
import com.picme.domain.model.BeautySettings
import com.picme.domain.model.BeautyStrategy
import com.picme.features.camera.AspectRatio
import com.picme.features.camera.preview.core.BeautyPreviewEngineStrategy
import com.picme.features.camera.preview.core.FaceWarpParams

internal class GpupixelBeautyPreviewStrategy(
    private val gpupixelProvider: GpupixelBeautyPreviewProvider,
    private val onWarmUpFallback: (String) -> Unit
) : BeautyPreviewEngineStrategy {
    override val strategy: BeautyStrategy = BeautyStrategy.GPUPIXEL

    override fun bindPreview(previewUseCase: Preview, aspectRatio: Int): Boolean {
        return try {
            gpupixelProvider.initialize()
            gpupixelProvider.setScaleMode(isFillCenter = aspectRatio == AspectRatio.RATIO_FULL)
            previewUseCase.setSurfaceProvider { request ->
                request.willNotProvideSurface()
            }
            Logger.i("Camera", "GPUPixel preview strategy active")
            true
        } catch (error: Throwable) {
            Logger.w("Camera", "GPUPixel warm-up failed", error)
            onWarmUpFallback(error.message ?: "GPUPixel warm-up error")
            false
        }
    }

    override fun applyBeautySettings(settings: BeautySettings) {
        val params = if (!settings.enabled || !settings.hasAnyEffect()) {
            BeautyParams.EMPTY
        } else {
            BeautyParams(
                enabled = true,
                smoothing = (settings.smoothing / 100f).coerceIn(0f, 1f),
                whitening = (settings.whitening / 100f).coerceIn(0f, 1f),
                bigEyes = (settings.bigEyes / 100f).coerceIn(0f, 1f),
                slimFace = (settings.slimFace / 50f * 1.35f).coerceIn(-1f, 1f),
                lipColor = (settings.lipColor / 100f).coerceIn(0f, 1f),
                lipColorIndex = settings.lipColorIndex.coerceIn(0, 11),
                blush = (settings.blush / 100f).coerceIn(0f, 1f),
                blushColorFamily = settings.blushColorFamily.coerceIn(0, 2)
            )
        }
        gpupixelProvider.updateFilters(params)
    }

    override fun applyFaceWarpParams(params: FaceWarpParams) {
        // GPUPixel 使用自带人脸检测器，忽略 ML Kit 传来的 FaceWarpParams
    }

    override fun release() {
        gpupixelProvider.release()
    }
}
