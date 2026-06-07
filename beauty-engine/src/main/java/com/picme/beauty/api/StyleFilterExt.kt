package com.picme.beauty.api

import androidx.annotation.StringRes

/**
 * StyleFilter 扩展：Android 资源映射
 *
 * 将纯数据枚举 [StyleFilter] 与 Android 字符串资源关联。
 */
val StyleFilter.displayNameRes: Int
    @StringRes
    get() = when (this) {
        StyleFilter.NONE -> com.picme.beauty.R.string.style_filter_none
        StyleFilter.TOON -> com.picme.beauty.R.string.style_filter_toon
        StyleFilter.SKETCH -> com.picme.beauty.R.string.style_filter_sketch
        StyleFilter.POSTERIZE -> com.picme.beauty.R.string.style_filter_posterize
        StyleFilter.EMBOSS -> com.picme.beauty.R.string.style_filter_emboss
        StyleFilter.CROSSHATCH -> com.picme.beauty.R.string.style_filter_crosshatch
    }
