package com.picme.features.camera.preview.rplan

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.picme.core.image.rplan.RPlanBeautyPreviewProvider
import com.picme.data.preferences.BeautyStrategy

@Composable
internal fun rememberRPlanPreviewProvider(
    context: Context,
    beautyStrategy: BeautyStrategy
): RPlanBeautyPreviewProvider {
    // PixelFree 实时预览借用 R Plan 的渲染管线以保证瘦脸/大眼即时生效。
    return remember(context) {
        RPlanBeautyPreviewProvider(context)
    }
}

