package com.picme.features.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

@Composable
internal fun FaceDebugOverlay(
    faceWarpParams: FaceWarpParams,
    slimFaceValue: Float
) {
    if (!faceWarpParams.hasFace) {
        return
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerNorm = Offset(
            x = faceWarpParams.faceCenterX.coerceIn(0f, 1f),
            y = faceWarpParams.faceCenterY.coerceIn(0f, 1f)
        )
        val leftEyeNorm = Offset(
            x = faceWarpParams.leftEyeX.coerceIn(0f, 1f),
            y = faceWarpParams.leftEyeY.coerceIn(0f, 1f)
        )
        val rightEyeNorm = Offset(
            x = faceWarpParams.rightEyeX.coerceIn(0f, 1f),
            y = faceWarpParams.rightEyeY.coerceIn(0f, 1f)
        )
        val faceRadiusNorm = faceWarpParams.faceRadius.coerceIn(0f, 1f)
        val slimFaceIntensity = (slimFaceValue / 50f).coerceIn(-1f, 1f)

        fun toCanvasPoint(point: Offset): Offset {
            return Offset(
                x = point.x.coerceIn(0f, 1f) * size.width,
                y = point.y.coerceIn(0f, 1f) * size.height
            )
        }

        val center = toCanvasPoint(centerNorm)
        val leftEye = toCanvasPoint(leftEyeNorm)
        val rightEye = toCanvasPoint(rightEyeNorm)
        val radiusPx = faceRadiusNorm * size.width

        val contourPoints = faceWarpParams.contourPoints.map { contourPoint ->
            toCanvasPoint(contourPoint)
        }
        val leftEyeContourPoints = faceWarpParams.leftEyeContourPoints.map { contourPoint ->
            toCanvasPoint(contourPoint)
        }
        val rightEyeContourPoints = faceWarpParams.rightEyeContourPoints.map { contourPoint ->
            toCanvasPoint(contourPoint)
        }

        fun drawClosedContour(points: List<Offset>, color: Color, strokeWidth: Float) {
            if (points.size < 2) {
                return
            }
            points.zipWithNext().forEach { (start, end) ->
                drawLine(
                    color = color,
                    start = start,
                    end = end,
                    strokeWidth = strokeWidth
                )
            }
            drawLine(
                color = color,
                start = points.last(),
                end = points.first(),
                strokeWidth = strokeWidth
            )
        }

        fun applySlimFaceDebug(point: Offset): Offset {
            val dirX = point.x - centerNorm.x
            val dirY = point.y - centerNorm.y
            val distance = sqrt(dirX * dirX + dirY * dirY)
            if (distance >= faceRadiusNorm || faceRadiusNorm <= 0.0001f) {
                return point
            }

            val eyeAxisXRaw = rightEyeNorm.x - leftEyeNorm.x
            val eyeAxisYRaw = rightEyeNorm.y - leftEyeNorm.y
            val eyeAxisLen = sqrt(eyeAxisXRaw * eyeAxisXRaw + eyeAxisYRaw * eyeAxisYRaw)
            val eyeAxisX = if (eyeAxisLen > 0.0001f) eyeAxisXRaw / eyeAxisLen else 1f
            val eyeAxisY = if (eyeAxisLen > 0.0001f) eyeAxisYRaw / eyeAxisLen else 0f

            val percent = 1f - distance / faceRadiusNorm
            val strength = slimFaceIntensity * percent * percent * 0.45f
            val axisOffset = (dirX * eyeAxisX + dirY * eyeAxisY) / faceRadiusNorm
            val offsetX = eyeAxisX * axisOffset * strength * faceRadiusNorm
            val offsetY = eyeAxisY * axisOffset * strength * faceRadiusNorm

            return Offset(
                x = (point.x - offsetX).coerceIn(0f, 1f),
                y = (point.y - offsetY).coerceIn(0f, 1f)
            )
        }

        drawClosedContour(
            points = contourPoints,
            color = Color.Magenta.copy(alpha = 0.9f),
            strokeWidth = 2.dp.toPx()
        )
        drawClosedContour(
            points = leftEyeContourPoints,
            color = Color.Yellow.copy(alpha = 0.95f),
            strokeWidth = 2.dp.toPx()
        )
        drawClosedContour(
            points = rightEyeContourPoints,
            color = Color.Green.copy(alpha = 0.95f),
            strokeWidth = 2.dp.toPx()
        )

        drawCircle(
            color = Color(0xFFFF6D00).copy(alpha = 0.12f),
            radius = radiusPx,
            center = center,
            style = Fill
        )
        drawCircle(
            color = Color(0xFFFF6D00).copy(alpha = 0.18f),
            radius = radiusPx * 0.58f,
            center = center,
            style = Fill
        )
        drawCircle(
            color = Color.Cyan.copy(alpha = 0.75f),
            radius = radiusPx,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        val debugWarpAmplifier = 6f
        val yRatios = listOf(-0.45f, -0.2f, 0f, 0.2f, 0.45f)
        yRatios.forEach { ratio ->
            val sampleY = (centerNorm.y + faceRadiusNorm * ratio).coerceIn(0f, 1f)
            val leftSample = Offset(
                x = (centerNorm.x - faceRadiusNorm * 0.72f).coerceIn(0f, 1f),
                y = sampleY
            )
            val rightSample = Offset(
                x = (centerNorm.x + faceRadiusNorm * 0.72f).coerceIn(0f, 1f),
                y = sampleY
            )
            listOf(leftSample, rightSample).forEach { samplePoint ->
                val warpedPoint = applySlimFaceDebug(samplePoint)
                val sampleCanvasPoint = toCanvasPoint(samplePoint)
                val warpedCanvasPoint = toCanvasPoint(warpedPoint)
                val debugWarpedCanvasPoint = Offset(
                    x = (
                        sampleCanvasPoint.x +
                            (warpedCanvasPoint.x - sampleCanvasPoint.x) * debugWarpAmplifier
                        ).coerceIn(0f, size.width),
                    y = (
                        sampleCanvasPoint.y +
                            (warpedCanvasPoint.y - sampleCanvasPoint.y) * debugWarpAmplifier
                        ).coerceIn(0f, size.height)
                )

                drawLine(
                    color = Color(0xFFFF6D00),
                    start = sampleCanvasPoint,
                    end = debugWarpedCanvasPoint,
                    strokeWidth = 3.dp.toPx()
                )
                drawCircle(
                    color = Color(0xFFFF6D00),
                    radius = 3.dp.toPx(),
                    center = sampleCanvasPoint
                )
                drawCircle(
                    color = Color(0xFF00E5FF),
                    radius = 4.dp.toPx(),
                    center = debugWarpedCanvasPoint
                )
            }
        }

        drawLine(
            color = Color.White.copy(alpha = 0.8f),
            start = leftEye,
            end = rightEye,
            strokeWidth = 1.5.dp.toPx()
        )
    }
}

