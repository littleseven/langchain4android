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
import com.picme.features.camera.preview.core.FaceWarpParams
import kotlin.math.sqrt
import androidx.compose.ui.graphics.drawscope.DrawScope

@Composable
internal fun FaceDebugOverlay(
    faceWarpParams: FaceWarpParams,
    slimFaceValue: Float,
    aspectRatio: Int = AspectRatio.RATIO_FULL
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

        // 计算 FIT_CENTER 模式下图像内容在 Canvas 中的实际显示区域
        // 4:3/16:9 比例时 PreviewView 使用 FIT_CENTER，有上下或左右黑边
        // normY 是基于全屏高度归一化的，需要映射到内容区域才能与视觉画面对齐
        val contentOffsetX: Float
        val contentOffsetY: Float
        val contentWidth: Float
        val contentHeight: Float

        if (aspectRatio == AspectRatio.RATIO_FULL) {
            // FILL_CENTER：内容填满 Canvas，无黑边，直接用全屏坐标
            contentOffsetX = 0f
            contentOffsetY = 0f
            contentWidth = size.width
            contentHeight = size.height
        } else {
            // FIT_CENTER：根据目标宽高比计算内容区域
            // 4:3 竖屏旋转后：图像内容宽高比 = 3/4 = 0.75（高 > 宽，高不是更大，而是比例上竖向更长）
            // 16:9 竖屏旋转后：图像内容宽高比 = 9/16 = 0.5625
            val imageContentAspect = when (aspectRatio) {
                AspectRatio.RATIO_4_3 -> 3f / 4f   // 竖屏时 width/height = 3/4
                AspectRatio.RATIO_16_9 -> 9f / 16f  // 竖屏时 width/height = 9/16
                else -> size.width / size.height
            }
            val canvasAspect = size.width / size.height
            if (imageContentAspect < canvasAspect) {
                // 内容比 Canvas 更窄：上下填满，左右有黑边
                contentHeight = size.height
                contentWidth = size.height * imageContentAspect
                contentOffsetX = (size.width - contentWidth) / 2f
                contentOffsetY = 0f
            } else {
                // 内容比 Canvas 更宽：左右填满，上下有黑边
                contentWidth = size.width
                contentHeight = size.width / imageContentAspect
                contentOffsetX = 0f
                contentOffsetY = (size.height - contentHeight) / 2f
            }
        }

        // 将归一化坐标（基于 previewView 全尺寸）映射到内容区域实际像素坐标
        fun toCanvasPoint(point: Offset): Offset {
            return Offset(
                x = contentOffsetX + point.x.coerceIn(0f, 1f) * contentWidth,
                y = contentOffsetY + point.y.coerceIn(0f, 1f) * contentHeight
            )
        }

        val center = toCanvasPoint(centerNorm)
        val leftEye = toCanvasPoint(leftEyeNorm)
        val rightEye = toCanvasPoint(rightEyeNorm)
        val radiusPx = faceRadiusNorm * contentWidth

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

        // 绘制所有 133 点 Contour（调试用）
        drawAllContours(faceWarpParams, ::toCanvasPoint)
    }
}

/**
 * 绘制所有 133 点 Contour（调试用）
 */
private fun DrawScope.drawAllContours(
    faceWarpParams: FaceWarpParams,
    toCanvasPoint: (Offset) -> Offset
) {
    val allContours = faceWarpParams.allContours
    if (allContours.totalPointCount() == 0) return

    // 绘制脸部轮廓（品红色）
    allContours.faceOval.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Magenta.copy(alpha = 0.7f), 3.dp.toPx())
    }

    // 绘制左眉毛（青色）
    allContours.leftEyebrowTop.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Cyan.copy(alpha = 0.8f), 2.dp.toPx())
    }
    allContours.leftEyebrowBottom.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Cyan.copy(alpha = 0.6f), 2.dp.toPx())
    }

    // 绘制右眉毛（蓝色）
    allContours.rightEyebrowTop.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Blue.copy(alpha = 0.8f), 2.dp.toPx())
    }
    allContours.rightEyebrowBottom.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Blue.copy(alpha = 0.6f), 2.dp.toPx())
    }

    // 绘制左眼（黄色）
    allContours.leftEye.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Yellow.copy(alpha = 0.9f), 2.dp.toPx())
    }

    // 绘制右眼（绿色）
    allContours.rightEye.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Green.copy(alpha = 0.9f), 2.dp.toPx())
    }

    // 绘制上嘴唇（红色）
    allContours.upperLipTop.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Red.copy(alpha = 0.8f), 2.dp.toPx())
    }
    allContours.upperLipBottom.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Red.copy(alpha = 0.6f), 2.dp.toPx())
    }

    // 绘制下嘴唇（橙色）
    allContours.lowerLipTop.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color(0xFFFF9800).copy(alpha = 0.6f), 2.dp.toPx())
    }
    allContours.lowerLipBottom.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color(0xFFFF9800).copy(alpha = 0.8f), 2.dp.toPx())
    }

    // 绘制鼻梁（紫色）
    allContours.noseBridge.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color(0xFF9C27B0).copy(alpha = 0.8f), 2.dp.toPx())
    }

    // 绘制鼻翼（深紫色）
    allContours.noseBottom.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color(0xFF673AB7).copy(alpha = 0.8f), 2.dp.toPx())
    }

    // 绘制左脸颊（浅绿色）
    allContours.leftCheek.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color(0xFF8BC34A).copy(alpha = 0.7f), 2.dp.toPx())
    }

    // 绘制右脸颊（深绿色）
    allContours.rightCheek.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color(0xFF4CAF50).copy(alpha = 0.7f), 2.dp.toPx())
    }

    // 显示点数统计
    val totalCount = allContours.totalPointCount()
    drawRect(
        color = Color.Black.copy(alpha = 0.5f),
        topLeft = Offset(10f, 10f),
        size = androidx.compose.ui.geometry.Size(200f, 120f)
    )
}

/**
 * 绘制 Contour 点（小圆点 + 连线）
 */
private fun DrawScope.drawContourPoints(
    points: List<Offset>,
    color: Color,
    strokeWidth: Float
) {
    if (points.isEmpty()) return

    // 绘制点
    points.forEach { point ->
        drawCircle(
            color = color,
            radius = 3.dp.toPx(),
            center = point,
            style = Fill
        )
    }

    // 绘制连线
    if (points.size >= 2) {
        points.zipWithNext().forEach { (start, end) ->
            drawLine(
                color = color.copy(alpha = 0.5f),
                start = start,
                end = end,
                strokeWidth = strokeWidth * 0.5f
            )
        }
    }
}

