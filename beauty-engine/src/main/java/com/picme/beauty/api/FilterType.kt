package com.picme.beauty.api

import androidx.annotation.StringRes
import com.picme.beauty.R
import android.graphics.ColorMatrix

/**
 * 色调滤镜枚举
 *
 * 通过 ColorMatrix 实现实时色调变换。
 * 与 [StyleFilter]（GPU Shader 风格特效）互为补充：色调滤镜先生效，风格特效后叠加。
 *
 * [NONE] 表示不应用任何色调滤镜。
 */
enum class FilterType(@StringRes val displayNameRes: Int) {
    NONE(R.string.filter_none),
    LEICA_CLASSIC(R.string.filter_leica_classic),
    LEICA_VIBRANT(R.string.filter_leica_vibrant),
    LEICA_BW(R.string.filter_leica_bw),
    FILM_GOLD(R.string.filter_film_gold),
    FILM_FUJI(R.string.filter_film_fuji),
    VINTAGE(R.string.filter_vintage),
    COOL(R.string.filter_cool),
    WARM(R.string.filter_warm);

    /**
     * 返回 Android 原生的 ColorMatrix（用于预览和拍照后处理）
     */
    fun toAndroidColorMatrix(): ColorMatrix {
        return when (this) {
            NONE -> ColorMatrix()
            LEICA_CLASSIC -> ColorMatrix(
                floatArrayOf(
                    0.95f, 0f, 0f, 0f, 0f,
                    0f, 0.9f, 0f, 0f, 0f,
                    0f, 0f, 0.85f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )

            LEICA_VIBRANT -> ColorMatrix().apply {
                setSaturation(1.3f)
            }

            LEICA_BW -> ColorMatrix().apply {
                setSaturation(0f)
            }

            FILM_GOLD -> ColorMatrix(
                floatArrayOf(
                    1.1f, 0.1f, 0f, 0f, 0f,
                    0.1f, 1.0f, 0f, 0f, 0f,
                    0f, 0f, 0.8f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )

            FILM_FUJI -> ColorMatrix(
                floatArrayOf(
                    0.9f, 0f, 0.1f, 0f, 0f,
                    0f, 1.1f, 0f, 0f, 0f,
                    0.1f, 0f, 1.0f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )

            VINTAGE -> ColorMatrix(
                floatArrayOf(
                    0.9f, 0f, 0f, 0f, 0f,
                    0f, 0.8f, 0f, 0f, 0f,
                    0f, 0.5f, 0f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )

            COOL -> ColorMatrix(
                floatArrayOf(
                    0.8f, 0f, 0f, 0f, 0f,
                    0f, 0.9f, 0f, 0f, 0f,
                    0f, 0f, 1.2f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )

            WARM -> ColorMatrix(
                floatArrayOf(
                    1.2f, 0f, 0f, 0f, 0f,
                    0f, 1.0f, 0f, 0f, 0f,
                    0f, 0.5f, 0f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
    }
}
