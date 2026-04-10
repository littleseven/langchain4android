package com.picme.features.camera.preview.gl

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.picme.beauty.api.BeautyPreviewEngine
import com.picme.beauty.egl.GlBeautyPreviewProvider
import com.picme.domain.model.BeautyStrategy

@Composable
internal fun rememberGlBeautyPreviewProvider(
    context: Context,
    beautyStrategy: BeautyStrategy
): BeautyPreviewEngine {
    // 实时预览通过 GL 美颜渲染管线保证瘦脸/大眼即时生效。
    return remember(context) {
        GlBeautyPreviewProvider(context)
    }
}

