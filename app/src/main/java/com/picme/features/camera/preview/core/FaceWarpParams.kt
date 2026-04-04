package com.picme.features.camera.preview.core

import androidx.compose.ui.geometry.Offset

internal data class FaceWarpParams(
    val faceCenterX: Float = 0.5f,
    val faceCenterY: Float = 0.5f,
    val leftEyeX: Float = 0.4f,
    val leftEyeY: Float = 0.45f,
    val rightEyeX: Float = 0.6f,
    val rightEyeY: Float = 0.45f,
    val faceRadius: Float = 0.18f,
    val hasFace: Boolean = false,
    val contourPoints: List<Offset> = emptyList(),
    val leftEyeContourPoints: List<Offset> = emptyList(),
    val rightEyeContourPoints: List<Offset> = emptyList()
)

