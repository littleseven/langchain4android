package com.picme.features.camera.preview.core

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.picme.beauty.egl.GlBeautyPreviewProvider
import com.picme.core.image.pixelfree.PixelFreeGLSurfaceView
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.model.BeautySettings
import com.picme.features.camera.preview.gl.GlBeautyPreviewStrategy
import com.picme.features.camera.preview.pixelfree.PixelFreePreviewLinkMode
import com.picme.features.camera.preview.pixelfree.PixelFreePreviewStrategy

internal interface BeautyPreviewEngineStrategy {
    val strategy: BeautyStrategy

    fun bindPreview(previewUseCase: Preview, aspectRatio: Int): Boolean

    fun applyBeautySettings(settings: BeautySettings)

    fun applyFaceWarpParams(params: FaceWarpParams)

    fun release()
}

internal data class PreviewStrategyBundle(
    val activeStrategy: BeautyPreviewEngineStrategy
)

@Composable
internal fun rememberPreviewStrategyBundle(
    beautyStrategy: BeautyStrategy,
    previewView: PreviewView,
    pixelFreeView: PixelFreeGLSurfaceView?,
    glPreviewProvider: GlBeautyPreviewProvider?,
    onGlWarmUpFallback: (String) -> Unit,
    onPixelFreePreviewLinkModeChanged: (PixelFreePreviewLinkMode) -> Unit,
    onPixelFreePreviewLinkReasonChanged: (String?) -> Unit
): PreviewStrategyBundle {
    val activeStrategy = remember(beautyStrategy, previewView, pixelFreeView, glPreviewProvider) {
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
            BeautyStrategy.PIXEL_FREE -> {
                PixelFreePreviewStrategy(
                    previewView = previewView,
                    pixelFreeView = requireNotNull(pixelFreeView) {
                        "PixelFree strategy requires PixelFreeGLSurfaceView"
                    },
                    glPreviewProvider = glPreviewProvider,
                    onPreviewLinkModeChanged = onPixelFreePreviewLinkModeChanged,
                    onPreviewLinkReasonChanged = onPixelFreePreviewLinkReasonChanged
                )
            }
        }
    }

    return PreviewStrategyBundle(activeStrategy = activeStrategy)
}

