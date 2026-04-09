package com.picme.features.camera.preview.pixelfree

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.picme.core.common.Logger
import com.picme.core.image.pixelfree.PixelFreeGLSurfaceView
import com.picme.domain.model.BeautyStrategy

@Composable
internal fun rememberPixelFreePreviewView(
    context: Context,
    beautyStrategy: BeautyStrategy
): PixelFreeGLSurfaceView? {
    return remember(beautyStrategy) {
        if (beautyStrategy == BeautyStrategy.PIXEL_FREE) {
            PixelFreeGLSurfaceView(context).apply {
                Logger.d("Camera", "PixelFreeGLSurfaceView created for PixelFree strategy")
            }
        } else {
            null
        }
    }
}

