package com.picme.features.camera.model

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ColorMatrix
import com.picme.R

enum class FilterType(@StringRes val displayNameRes: Int) {
    NONE(R.string.filter_none),
    LEICA_CLASSIC(R.string.filter_leica_classic),
    LEICA_VIBRANT(R.string.filter_leica_vibrant),
    BEAUTY(R.string.filter_beauty),
    GRAYSCALE(R.string.filter_grayscale),
    SEPIA(R.string.filter_sepia),
    VINTAGE(R.string.filter_vintage),
    COOL(R.string.filter_cool),
    WARM(R.string.filter_warm);

    fun getColorMatrix(): ColorMatrix {
        return when (this) {
            NONE, BEAUTY -> ColorMatrix()
            LEICA_CLASSIC -> ColorMatrix(floatArrayOf(
                0.95f, 0f, 0f, 0f, 0f,
                0f, 0.9f, 0f, 0f, 0f,
                0f, 0f, 0.85f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            LEICA_VIBRANT -> ColorMatrix().apply {
                setToSaturation(1.3f)
            }
            GRAYSCALE -> ColorMatrix().apply { setToSaturation(0f) }
            SEPIA -> ColorMatrix(floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            VINTAGE -> ColorMatrix(floatArrayOf(
                0.9f, 0f, 0f, 0f, 0f,
                0f, 0.8f, 0f, 0f, 0f,
                0f, 0.5f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            COOL -> ColorMatrix(floatArrayOf(
                0.8f, 0f, 0f, 0f, 0f,
                0f, 0.9f, 0f, 0f, 0f,
                0f, 0f, 1.2f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            WARM -> ColorMatrix(floatArrayOf(
                1.2f, 0f, 0f, 0f, 0f,
                0f, 1.0f, 0f, 0f, 0f,
                0f, 0f, 0.8f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
    }
}
