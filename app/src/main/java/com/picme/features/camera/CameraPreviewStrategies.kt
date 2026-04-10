package com.picme.features.camera

import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.picme.beauty.api.BeautyPreviewEngine
import com.picme.beauty.gpupixel.GpupixelBeautyPreviewProvider
import com.picme.domain.model.BeautyStrategy
import com.picme.features.camera.preview.core.PreviewStrategyBundle
import com.picme.features.camera.preview.gl.GlBeautyPreviewStrategy
import com.picme.features.camera.preview.gpupixel.GpupixelBeautyPreviewStrategy

@Composable
internal fun rememberPreviewStrategyBundle(
    beautyStrategy: BeautyStrategy,
    previewView: PreviewView,
    glPreviewProvider: BeautyPreviewEngine?,
    onGlWarmUpFallback: (String) -> Unit
): PreviewStrategyBundle {
    val activeStrategy = remember(beautyStrategy, previewView, glPreviewProvider) {
        when (beautyStrategy) {
            BeautyStrategy.BIG_BEAUTY -> {
                GlBeautyPreviewStrategy(
                    previewView = previewView,
                    glBeautyPreviewProvider = requireNotNull(glPreviewProvider) {
                        "GL beauty strategy requires GlBeautyPreviewProvider"
                    },
                    onWarmUpFallback = onGlWarmUpFallback
                )
            }
            BeautyStrategy.GPUPIXEL -> {
                GpupixelBeautyPreviewStrategy(
                    gpupixelProvider = requireNotNull(glPreviewProvider as? GpupixelBeautyPreviewProvider) {
                        "GPUPixel strategy requires GpupixelBeautyPreviewProvider"
                    },
                    onWarmUpFallback = onGlWarmUpFallback
                )
            }
        }
    }

    return PreviewStrategyBundle(activeStrategy = activeStrategy)
}

