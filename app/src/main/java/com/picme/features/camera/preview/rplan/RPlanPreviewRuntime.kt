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
): RPlanBeautyPreviewProvider? {
    return remember(beautyStrategy) {
        if (beautyStrategy == BeautyStrategy.R_PLAN) {
            RPlanBeautyPreviewProvider(context)
        } else {
            null
        }
    }
}

