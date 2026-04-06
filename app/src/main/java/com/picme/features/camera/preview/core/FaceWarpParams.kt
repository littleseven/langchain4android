package com.picme.features.camera.preview.core

import androidx.compose.ui.geometry.Offset

internal data class FaceWarpParams(
    val faceCenterX: Float = 0.5f,
    val faceCenterY: Float = 0.5f,
    val leftEyeX: Float = 0.4f,
    val leftEyeY: Float = 0.45f,
    val rightEyeX: Float = 0.6f,
    val rightEyeY: Float = 0.45f,
    val mouthCenterX: Float = 0.5f,
    val mouthCenterY: Float = 0.62f,
    val mouthLeftX: Float = 0.42f,
    val mouthLeftY: Float = 0.62f,
    val mouthRightX: Float = 0.58f,
    val mouthRightY: Float = 0.62f,
    val upperLipCenterX: Float = 0.5f,
    val upperLipCenterY: Float = 0.60f,
    val lowerLipCenterX: Float = 0.5f,
    val lowerLipCenterY: Float = 0.66f,
    val faceRadius: Float = 0.18f,
    val hasFace: Boolean = false,
    val contourPoints: List<Offset> = emptyList(),
    val leftEyeContourPoints: List<Offset> = emptyList(),
    val rightEyeContourPoints: List<Offset> = emptyList()
)

