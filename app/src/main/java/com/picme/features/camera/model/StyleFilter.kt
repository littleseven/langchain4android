package com.picme.features.camera.model

import androidx.annotation.StringRes
import com.picme.R

/**
 * 风格特效滤镜枚举
 *
 * 仅在 GPUPixel 引擎模式下生效，通过 GPU Shader 实时渲染风格特效。
 * 与 [FilterType]（ColorMatrix 色调滤镜）互为补充：色调滤镜先生效，风格特效后叠加。
 *
 * 枚举值到 GPUPixel 滤镜类名的映射由 [gpuFilterClassName] 提供。
 * [NONE] 表示不应用任何风格特效。
 */
enum class StyleFilter(
    @StringRes val displayNameRes: Int,
    val gpuFilterClassName: String?
) {
    NONE(R.string.style_filter_none, null),
    TOON(R.string.style_filter_toon, "ToonFilter"),
    SMOOTH_TOON(R.string.style_filter_smooth_toon, "SmoothToonFilter"),
    SKETCH(R.string.style_filter_sketch, "SketchFilter"),
    POSTERIZE(R.string.style_filter_posterize, "PosterizeFilter"),
    EMBOSS(R.string.style_filter_emboss, "EmbossFilter"),
    CROSSHATCH(R.string.style_filter_crosshatch, "CrosshatchFilter");

    /** 是否为有效特效（非 NONE） */
    val isActive: Boolean get() = gpuFilterClassName != null
}

