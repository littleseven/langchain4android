package com.picme.features.camera.preview.gl

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.picme.beauty.api.BeautyPreviewEngine
import com.picme.beauty.egl.GlBeautyPreviewProvider
import com.picme.beauty.gpupixel.GpupixelBeautyPreviewProvider
import com.picme.domain.model.BeautyStrategy

@Composable
internal fun rememberGlBeautyPreviewProvider(
    context: Context,
    beautyStrategy: BeautyStrategy
): BeautyPreviewEngine {
    return remember(context, beautyStrategy) {
        when (beautyStrategy) {
            BeautyStrategy.BIG_BEAUTY -> GlBeautyPreviewProvider(context)
            BeautyStrategy.GPUPIXEL -> GpupixelBeautyPreviewProvider(context)
        }
    }
}

