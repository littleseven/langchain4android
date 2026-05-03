package com.picme.features.camera.model

import androidx.annotation.StringRes
import com.picme.R

/**
 * 风格特效滤镜枚举
 *
 * 通过 GPU Shader 实时渲染风格特效。
 * 与 [FilterType]（ColorMatrix 色调滤镜）互为补充：色调滤镜先生效，风格特效后叠加。
 *
 * [NONE] 表示不应用任何风格特效。
 */
enum class StyleFilter(
    @StringRes val displayNameRes: Int
) {
    NONE(R.string.style_filter_none),
    TOON(R.string.style_filter_toon),
    SKETCH(R.string.style_filter_sketch),
    POSTERIZE(R.string.style_filter_posterize),
    EMBOSS(R.string.style_filter_emboss),
    CROSSHATCH(R.string.style_filter_crosshatch)
}

