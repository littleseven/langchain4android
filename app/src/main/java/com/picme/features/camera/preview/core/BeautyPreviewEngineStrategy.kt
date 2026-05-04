package com.picme.features.camera.preview.core

import androidx.camera.core.Preview
import com.picme.domain.model.BeautyStrategy
import com.picme.beauty.api.BeautySettings

internal interface BeautyPreviewEngineStrategy {
    val strategy: BeautyStrategy

    fun bindPreview(previewUseCase: Preview, aspectRatio: Int): Boolean

    fun applyBeautySettings(settings: BeautySettings)

    fun applyFaceWarpParams(params: FaceWarpParams)

    fun release()
}
