package com.picme.core.image.gl

import com.picme.beauty.api.BeautyParams
import com.picme.domain.model.BeautySettings
import com.picme.features.camera.model.FilterType

/**
 * BeautySettings -> BeautyParams 转换扩展
 *
 * app 层负责将领域模型转换为新模块所需的参数格式。
 * 保留所有比例映射逻辑，确保美颜效果与重构前一致。
 *
 * colorFilter 字段会被转换为 4x5 colorMatrix（FloatArray，行主序），
 * 传入大美丽引擎的 OpenGL Shader 进行实时色调变换。
 * FilterType.NONE 时 colorMatrix 为 null，Shader 直通输出。
 */
fun BeautySettings.toBeautyParams(): BeautyParams {
    // 色调滤镜矩阵：取 Android ColorMatrix 的底层 float 数组（20个元素，4行×5列）
    val matrix: FloatArray? = colorFilter
        .takeIf { it != FilterType.NONE }
        ?.getColorMatrix()
        ?.values

    if (!enabled || !hasAnyEffect()) {
        // 美颜关闭时仍需携带色调矩阵（滤镜独立于美颜开关）
        return BeautyParams.EMPTY.copy(colorMatrix = matrix)
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
        blushColorFamily = blushColorFamily.coerceIn(0, 2),
        colorMatrix = matrix
    )
}

