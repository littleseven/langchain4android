package com.example.picme.ui.model

import androidx.compose.ui.graphics.ColorMatrix

enum class FilterType(val displayName: String) {
    NONE("Original"),
    GRAYSCALE("B&W"),
    SEPIA("Sepia"),
    VINTAGE("Vintage"),
    COOL("Cool"),
    WARM("Warm");

    fun getColorMatrix(): ColorMatrix {
        return when (this) {
            NONE -> ColorMatrix()
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
                0f, 0f, 0.5f, 0f, 0f,
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
