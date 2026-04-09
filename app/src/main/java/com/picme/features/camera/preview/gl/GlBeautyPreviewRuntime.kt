package com.picme.features.camera.preview.gl

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.picme.core.image.gl.GlBeautyPreviewProvider
import com.picme.data.preferences.BeautyStrategy

@Composable
internal fun rememberGlBeautyPreviewProvider(
    context: Context,
    beautyStrategy: BeautyStrategy
): GlBeautyPreviewProvider {
    // PixelFree 实时预览借用 GL 美颜渲染管线以保证瘦脸/大眼即时生效。
    return remember(context) {
        GlBeautyPreviewProvider(context)
    }
}

