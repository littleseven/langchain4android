package com.picme.features.camera

import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.picme.beauty.api.BeautyPreviewEngine
import com.picme.core.common.Logger
import com.picme.domain.model.BeautyStrategy
import com.picme.features.camera.preview.core.PreviewStrategyBundle
import com.picme.features.camera.preview.gl.GlBeautyPreviewStrategy

@Composable
internal fun rememberPreviewStrategyBundle(
    beautyStrategy: BeautyStrategy,
    previewView: PreviewView,
    glPreviewProvider: BeautyPreviewEngine?,
    onGlWarmUpFallback: (String) -> Unit
): PreviewStrategyBundle {
    val activeStrategy = remember(beautyStrategy, previewView, glPreviewProvider) {
        GlBeautyPreviewStrategy(
            previewView = previewView,
            glBeautyPreviewProvider = requireNotNull(glPreviewProvider) {
                "GL beauty strategy requires GlBeautyPreviewProvider"
            },
            onWarmUpFallback = onGlWarmUpFallback
        )
    }

    return PreviewStrategyBundle(activeStrategy = activeStrategy)
}
