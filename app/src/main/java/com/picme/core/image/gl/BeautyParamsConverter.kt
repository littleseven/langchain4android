package com.picme.core.image.gl

import com.picme.beauty.api.BeautyParams
import com.picme.domain.model.BeautySettings

/**
 * BeautySettings -> BeautyParams 转换扩展
 *
 * app 层负责将领域模型转换为新模块所需的参数格式。
 * 保留所有比例映射逻辑，确保美颜效果与重构前一致。
 */
fun BeautySettings.toBeautyParams(): BeautyParams {
    if (!enabled || !hasAnyEffect()) {
        return BeautyParams.EMPTY
    }
    return BeautyParams(
        enabled = true,
        smoothing = (smoothing / 100f).coerceIn(0f, 1f),
        whitening = (whitening / 100f).coerceIn(0f, 1f),
        bigEyes = (bigEyes / 100f).coerceIn(0f, 1f),
        slimFace = (slimFace / 50f * 1.35f).coerceIn(-1f, 1f),
        lipColor = (lipColor / 100f).coerceIn(0f, 1f),
        lipColorIndex = lipColorIndex.coerceIn(0, 11),
        blush = (blush / 100f).coerceIn(0f, 1f),
        blushColorFamily = blushColorFamily.coerceIn(0, 2)
    )
}

