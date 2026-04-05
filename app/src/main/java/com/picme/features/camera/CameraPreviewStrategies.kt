package com.picme.features.camera

import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.picme.core.image.pixelfree.PixelFreeGLSurfaceView
import com.picme.core.image.rplan.RPlanBeautyPreviewProvider
import com.picme.data.preferences.BeautyStrategy
import com.picme.features.camera.preview.core.PreviewStrategyBundle
import com.picme.features.camera.preview.pixelfree.PixelFreePreviewLinkMode
import com.picme.features.camera.preview.pixelfree.PixelFreePreviewStrategy
import com.picme.features.camera.preview.rplan.RPlanPreviewStrategy

@Composable
internal fun rememberPreviewStrategyBundle(
    beautyStrategy: BeautyStrategy,
    previewView: PreviewView,
    pixelFreeView: PixelFreeGLSurfaceView?,
    rPlanPreviewProvider: RPlanBeautyPreviewProvider?,
    onRPlanWarmUpFallback: (String) -> Unit,
    onPixelFreePreviewLinkModeChanged: (PixelFreePreviewLinkMode) -> Unit,
    onPixelFreePreviewLinkReasonChanged: (String?) -> Unit
): PreviewStrategyBundle {
    val activeStrategy = remember(beautyStrategy, previewView, pixelFreeView, rPlanPreviewProvider) {
        when (beautyStrategy) {
            BeautyStrategy.R_PLAN -> {
                RPlanPreviewStrategy(
                    previewView = previewView,
                    rPlanPreviewProvider = requireNotNull(rPlanPreviewProvider) {
                        "R Plan strategy requires RPlanBeautyPreviewProvider"
                    },
                    onWarmUpFallback = onRPlanWarmUpFallback
                )
            }
            BeautyStrategy.PIXEL_FREE -> {
                PixelFreePreviewStrategy(
                    previewView = previewView,
                    pixelFreeView = requireNotNull(pixelFreeView) {
                        "PixelFree strategy requires PixelFreeGLSurfaceView"
                    },
                    rPlanPreviewProvider = rPlanPreviewProvider,
                    onPreviewLinkModeChanged = onPixelFreePreviewLinkModeChanged,
                    onPreviewLinkReasonChanged = onPixelFreePreviewLinkReasonChanged
                )
            }
        }
    }

    return PreviewStrategyBundle(activeStrategy = activeStrategy)
}

