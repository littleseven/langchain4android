package com.picme.features.camera

import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.picme.beauty.api.BeautyPreviewEngine
import com.picme.beauty.gpupixel.GpupixelBeautyPreviewProvider
import com.picme.core.common.Logger
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
                // 防御性检查：provider 可能在策略切换的过渡帧中类型不匹配（如 recomposition 竞态）
                // 此时降级到 BIG_BEAUTY 路径，下一帧 provider 刷新后会自动重建正确策略
                val gpupixelProvider = glPreviewProvider as? GpupixelBeautyPreviewProvider
                if (gpupixelProvider == null) {
                    Logger.w(
                        "Camera",
                        "GPUPixel strategy requested but provider type mismatch: " +
                            "${glPreviewProvider?.javaClass?.simpleName}, fallback to GL strategy temporarily"
                    )
                    GlBeautyPreviewStrategy(
                        previewView = previewView,
                        glBeautyPreviewProvider = requireNotNull(glPreviewProvider) {
                            "No beauty provider available during strategy transition"
                        },
                        onWarmUpFallback = onGlWarmUpFallback
                    )
                } else {
                    GpupixelBeautyPreviewStrategy(
                        gpupixelProvider = gpupixelProvider,
                        previewView = previewView,
                        onWarmUpFallback = onGlWarmUpFallback
                    )
                }
            }
        }
    }

    return PreviewStrategyBundle(activeStrategy = activeStrategy)
}

