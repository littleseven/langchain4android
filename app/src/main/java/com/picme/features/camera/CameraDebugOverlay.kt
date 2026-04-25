package com.picme.features.camera

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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

    // 同时显示 GPUPixel 和大美丽两套点位用于对比
    val gpuPixelLandmarks = faceWarpParams.gpuPixelLandmarks
    val bigBeautyLandmarks = faceWarpParams.bigBeautyLandmarks
    val hasGpuPixel = gpuPixelLandmarks.hasFace && gpuPixelLandmarks.points.isNotEmpty()
    val hasBigBeauty = bigBeautyLandmarks.hasFace && bigBeautyLandmarks.points.isNotEmpty()

    // 大美丽模式：仅显示大美丽点位
    if (hasBigBeauty) {
        FaceDebugOverlayBigBeauty(
            bigBeautyLandmarks = bigBeautyLandmarks,
            aspectRatio = aspectRatio
        )
    } else if (hasGpuPixel) {
        // GPUPixel 模式：仅显示 GPUPixel 点位
        FaceDebugOverlayGpuPixel(
            gpuPixelLandmarks = gpuPixelLandmarks,
            aspectRatio = aspectRatio
        )
    }
}

@Composable
private fun FaceDebugOverlayDual(
    gpuPixelLandmarks: com.picme.features.camera.preview.core.GpuPixelLandmarks,
    bigBeautyLandmarks: com.picme.features.camera.preview.core.GpuPixelLandmarks,
    aspectRatio: Int = AspectRatio.RATIO_FULL
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val contentOffsetX: Float
        val contentOffsetY: Float
        val contentWidth: Float
        val contentHeight: Float

        if (aspectRatio == AspectRatio.RATIO_FULL) {
            contentOffsetX = 0f
            contentOffsetY = 0f
            contentWidth = size.width
            contentHeight = size.height
        } else {
            val imageContentAspect = when (aspectRatio) {
                AspectRatio.RATIO_4_3 -> 3f / 4f
                AspectRatio.RATIO_16_9 -> 9f / 16f
                else -> size.width / size.height
            }
            val canvasAspect = size.width / size.height
            if (imageContentAspect < canvasAspect) {
                contentHeight = size.height
                contentWidth = size.height * imageContentAspect
                contentOffsetX = (size.width - contentWidth) / 2f
                contentOffsetY = 0f
            } else {
                contentWidth = size.width
                contentHeight = size.width / imageContentAspect
                contentOffsetX = 0f
                contentOffsetY = (size.height - contentHeight) / 2f
            }
        }

        fun toCanvasPoint(point: Offset): Offset {
            return Offset(
                x = contentOffsetX + point.x.coerceIn(0f, 1f) * contentWidth,
                y = contentOffsetY + point.y.coerceIn(0f, 1f) * contentHeight
            )
        }

        val textPaint = Paint().apply {
            textSize = 8.dp.toPx()
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        // 绘制 GPUPixel 点位（绿色，带序号）
        val gpuPoints = gpuPixelLandmarks.points.map { pt -> toCanvasPoint(pt) }
        gpuPoints.forEachIndexed { index, point ->
            drawCircle(
                color = Color.Green.copy(alpha = 0.85f),
                radius = 2.5f,
                center = point,
                style = Fill
            )
            drawIntoCanvas { canvas ->
                textPaint.color = android.graphics.Color.GREEN
                canvas.nativeCanvas.drawText(
                    "G$index",
                    point.x,
                    point.y - 4.dp.toPx(),
                    textPaint
                )
            }
        }

        // 绘制大美丽点位（蓝色，带序号）
        val bbPoints = bigBeautyLandmarks.points.map { pt -> toCanvasPoint(pt) }
        bbPoints.forEachIndexed { index, point ->
            drawCircle(
                color = Color.Blue.copy(alpha = 0.85f),
                radius = 2.5f,
                center = point,
                style = Fill
            )
            drawIntoCanvas { canvas ->
                textPaint.color = android.graphics.Color.BLUE
                canvas.nativeCanvas.drawText(
                    "M$index",
                    point.x,
                    point.y + 10.dp.toPx(),
                    textPaint
                )
            }
        }

        // 每3秒输出一次完整坐标对比日志（用于映射优化）
        val frameCount = System.currentTimeMillis() / 1000
        if (frameCount % 3 == 0L && gpuPoints.size == 106 && bbPoints.size == 106) {
            val sbG = StringBuilder()
            val sbM = StringBuilder()
            sbG.append("GPU_COORDS:")
            sbM.append("MP_COORDS:")
            for (i in 0 until 106) {
                val g = gpuPixelLandmarks.points[i]
                val m = bigBeautyLandmarks.points[i]
                sbG.append(" [$i:${g.x.toString().take(5)},${g.y.toString().take(5)}]")
                sbM.append(" [$i:${m.x.toString().take(5)},${m.y.toString().take(5)}]")
            }
            com.picme.core.common.Logger.d("CameraDebug", sbG.toString())
            com.picme.core.common.Logger.d("CameraDebug", sbM.toString())
        }

        // 绘制图例说明
        drawIntoCanvas { canvas ->
            val legendPaint = Paint().apply {
                textSize = 10.dp.toPx()
                isAntiAlias = true
                textAlign = Paint.Align.LEFT
                color = android.graphics.Color.WHITE
            }
            canvas.nativeCanvas.drawText("G=GPUPixel  M=MediaPipe(大美丽)", 10f, 30f, legendPaint)
        }
    }
}

@Composable
private fun FaceDebugOverlayBigBeauty(
    bigBeautyLandmarks: com.picme.features.camera.preview.core.GpuPixelLandmarks,
    aspectRatio: Int = AspectRatio.RATIO_FULL
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val contentOffsetX: Float
        val contentOffsetY: Float
        val contentWidth: Float
        val contentHeight: Float

        if (aspectRatio == AspectRatio.RATIO_FULL) {
            contentOffsetX = 0f
            contentOffsetY = 0f
            contentWidth = size.width
            contentHeight = size.height
        } else {
            val imageContentAspect = when (aspectRatio) {
                AspectRatio.RATIO_4_3 -> 3f / 4f
                AspectRatio.RATIO_16_9 -> 9f / 16f
                else -> size.width / size.height
            }
            val canvasAspect = size.width / size.height
            if (imageContentAspect < canvasAspect) {
                contentHeight = size.height
                contentWidth = size.height * imageContentAspect
                contentOffsetX = (size.width - contentWidth) / 2f
                contentOffsetY = 0f
            } else {
                contentWidth = size.width
                contentHeight = size.width / imageContentAspect
                contentOffsetX = 0f
                contentOffsetY = (size.height - contentHeight) / 2f
            }
        }

        fun toCanvasPoint(point: Offset): Offset {
            return Offset(
                x = contentOffsetX + point.x.coerceIn(0f, 1f) * contentWidth,
                y = contentOffsetY + point.y.coerceIn(0f, 1f) * contentHeight
            )
        }

        // 绘制大美丽点位（蓝色，带序号）
        val bbPoints = bigBeautyLandmarks.points.map { pt -> toCanvasPoint(pt) }
        bbPoints.forEachIndexed { index, point ->
            drawCircle(
                color = Color.Blue.copy(alpha = 0.85f),
                radius = 2.5f,
                center = point,
                style = Fill
            )
        }

        // 绘制瘦脸控制点及方向（调试用）
        drawThinFaceControlPoints(bbPoints)

        // 绘制大眼控制点及方向（调试用）
        drawBigEyeControlPoints(bbPoints)
    }
}

@Composable
private fun FaceDebugOverlayGpuPixel(
    gpuPixelLandmarks: com.picme.features.camera.preview.core.GpuPixelLandmarks,
    aspectRatio: Int = AspectRatio.RATIO_FULL
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val contentOffsetX: Float
        val contentOffsetY: Float
        val contentWidth: Float
        val contentHeight: Float

        if (aspectRatio == AspectRatio.RATIO_FULL) {
            contentOffsetX = 0f
            contentOffsetY = 0f
            contentWidth = size.width
            contentHeight = size.height
        } else {
            val imageContentAspect = when (aspectRatio) {
                AspectRatio.RATIO_4_3 -> 3f / 4f
                AspectRatio.RATIO_16_9 -> 9f / 16f
                else -> size.width / size.height
            }
            val canvasAspect = size.width / size.height
            if (imageContentAspect < canvasAspect) {
                contentHeight = size.height
                contentWidth = size.height * imageContentAspect
                contentOffsetX = (size.width - contentWidth) / 2f
                contentOffsetY = 0f
            } else {
                contentWidth = size.width
                contentHeight = size.width / imageContentAspect
                contentOffsetX = 0f
                contentOffsetY = (size.height - contentHeight) / 2f
            }
        }

        fun toCanvasPoint(point: Offset): Offset {
            return Offset(
                x = contentOffsetX + point.x.coerceIn(0f, 1f) * contentWidth,
                y = contentOffsetY + point.y.coerceIn(0f, 1f) * contentHeight
            )
        }

        // 绘制 GPUPixel 点位（绿色，带序号）
        val gpuPoints = gpuPixelLandmarks.points.map { pt -> toCanvasPoint(pt) }
        gpuPoints.forEachIndexed { index, point ->
            drawCircle(
                color = Color.Green.copy(alpha = 0.85f),
                radius = 2.5f,
                center = point,
                style = Fill
            )
        }
    }
}

@Composable
private fun FaceDebugOverlaySingle(
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

        // 瘦脸控制点：仅在 ML Kit 模式（有 allContours 数据）下绘制，GPUPixel/MediaPipe 模式下跳过
        val isMlKitMode = faceWarpParams.allContours.totalPointCount() > 0
        if (isMlKitMode) {
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

        // 绘制 GPUPixel/MediaPipe 106 点（调试用，GPUPixel/大美丽模式）
        drawGpuPixelLandmarks(faceWarpParams, ::toCanvasPoint)

        // 绘制所有 133 点 Contour（调试用，ML Kit 模式）
        drawAllContours(faceWarpParams, ::toCanvasPoint)
    }
}

/**
 * 绘制 GPUPixel 106 点（mars-face-kit 格式）
 * 绘制点 + 轮廓/区域连线
 */
private fun DrawScope.drawGpuPixelLandmarks(
    faceWarpParams: FaceWarpParams,
    toCanvasPoint: (Offset) -> Offset
) {
    val gpuPixelLandmarks = faceWarpParams.gpuPixelLandmarks
    if (!gpuPixelLandmarks.hasFace || gpuPixelLandmarks.points.isEmpty()) return

    val points = gpuPixelLandmarks.points.map(toCanvasPoint)

    // 定义各区域的索引范围（mars-face-kit 106点格式，与GPUPixel一致）
    val contourRange = 0..32               // 33点 - 脸部轮廓
    val leftEyebrowRange = 33..37           // 5点 - 左眉
    val rightEyebrowRange = 38..42         // 5点 - 右眉
    val leftEyeRange = 52..74              // 左眼外6点(52-57) + 左眉下4点(64-67) + 左眼内3点(72-74)
    val rightEyeRange = 58..77             // 右眼外6点(58-63) + 右眉下4点(68-71) + 右眼内3点(75-77)
    val noseRange = 43..83                 // 眉心1点 + 鼻梁3点 + 鼻尖5点 + 鼻孔上2点 + 鼻孔4点
    val mouthRange = 84..105               // 嘴巴外12点 + 内8点 + 瞳孔重复2点

    // 绘制轮廓连线（品红色）
    val contourPoints = points.slice(contourRange)
    if (contourPoints.size >= 2) {
        // 绘制轮廓连线
        contourPoints.zipWithNext().forEach { (start, end) ->
            drawLine(
                color = Color.Magenta.copy(alpha = 0.5f),
                start = start,
                end = end,
                strokeWidth = 1.5f
            )
        }
        // 闭合轮廓（连接最后一个点到第一个点）
        drawLine(
            color = Color.Magenta.copy(alpha = 0.5f),
            start = contourPoints.last(),
            end = contourPoints.first(),
            strokeWidth = 1.5f
        )
    }

    // 绘制左眉毛连线（青色）
    val leftEyebrowPoints = points.slice(leftEyebrowRange)
    if (leftEyebrowPoints.size >= 2) {
        leftEyebrowPoints.zipWithNext().forEach { (start, end) ->
            drawLine(
                color = Color.Cyan.copy(alpha = 0.5f),
                start = start,
                end = end,
                strokeWidth = 1.5f
            )
        }
    }

    // 绘制右眉毛连线（蓝色）
    val rightEyebrowPoints = points.slice(rightEyebrowRange)
    if (rightEyebrowPoints.size >= 2) {
        rightEyebrowPoints.zipWithNext().forEach { (start, end) ->
            drawLine(
                color = Color.Blue.copy(alpha = 0.5f),
                start = start,
                end = end,
                strokeWidth = 1.5f
            )
        }
    }

    // 绘制左眼连线（黄色）
    val leftEyePoints = points.slice(leftEyeRange)
    if (leftEyePoints.size >= 2) {
        // 上眼睑 5 点 (53-57)
        leftEyePoints.take(5).zipWithNext().forEach { (start, end) ->
            drawLine(color = Color.Yellow.copy(alpha = 0.5f), start = start, end = end, strokeWidth = 1.5f)
        }
        // 下眼睑 5 点 (58-62)
        leftEyePoints.drop(5).take(5).zipWithNext().forEach { (start, end) ->
            drawLine(color = Color.Yellow.copy(alpha = 0.5f), start = start, end = end, strokeWidth = 1.5f)
        }
        // 连接眼睑两端，瞳孔(63)不连线
        if (leftEyePoints.size >= 10) {
            drawLine(color = Color.Yellow.copy(alpha = 0.5f), start = leftEyePoints[4], end = leftEyePoints[5], strokeWidth = 1.5f)
            drawLine(color = Color.Yellow.copy(alpha = 0.5f), start = leftEyePoints[9], end = leftEyePoints[0], strokeWidth = 1.5f)
        }
    }

    // 绘制右眼连线（绿色）
    val rightEyePoints = points.slice(rightEyeRange)
    if (rightEyePoints.size >= 2) {
        // 上眼睑 5 点 (64-68)
        rightEyePoints.take(5).zipWithNext().forEach { (start, end) ->
            drawLine(color = Color.Green.copy(alpha = 0.5f), start = start, end = end, strokeWidth = 1.5f)
        }
        // 下眼睑 5 点 (69-73)
        rightEyePoints.drop(5).take(5).zipWithNext().forEach { (start, end) ->
            drawLine(color = Color.Green.copy(alpha = 0.5f), start = start, end = end, strokeWidth = 1.5f)
        }
        // 连接眼睑两端，瞳孔(74)不连线
        if (rightEyePoints.size >= 10) {
            drawLine(color = Color.Green.copy(alpha = 0.5f), start = rightEyePoints[4], end = rightEyePoints[5], strokeWidth = 1.5f)
            drawLine(color = Color.Green.copy(alpha = 0.5f), start = rightEyePoints[9], end = rightEyePoints[0], strokeWidth = 1.5f)
        }
    }

    // 绘制鼻子连线（紫色）
    val nosePoints = points.slice(noseRange)
    if (nosePoints.size >= 2) {
        nosePoints.zipWithNext().forEach { (start, end) ->
            drawLine(
                color = Color(0xFF9C27B0).copy(alpha = 0.5f),
                start = start,
                end = end,
                strokeWidth = 1.5f
            )
        }
    }

    // 绘制嘴巴连线（红色）
    val mouthPoints = points.slice(mouthRange)
    if (mouthPoints.size >= 2) {
        mouthPoints.zipWithNext().forEach { (start, end) ->
            drawLine(
                color = Color.Red.copy(alpha = 0.5f),
                start = start,
                end = end,
                strokeWidth = 1.5f
            )
        }
        // 闭合嘴巴轮廓
        drawLine(
            color = Color.Red.copy(alpha = 0.5f),
            start = mouthPoints.last(),
            end = mouthPoints.first(),
            strokeWidth = 1.5f
        )
    }

    // 配置序号文本画笔
    val textPaint = Paint().apply {
        textSize = 9.dp.toPx()
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    // 绘制所有点（不同区域使用不同颜色）+ 序号
    points.forEachIndexed { index, point ->
        val color = when (index) {
            in contourRange -> Color.Magenta.copy(alpha = 0.9f)
            in leftEyebrowRange -> Color.Cyan.copy(alpha = 0.9f)
            in rightEyebrowRange -> Color.Blue.copy(alpha = 0.9f)
            in leftEyeRange -> Color.Yellow.copy(alpha = 0.95f)
            in rightEyeRange -> Color.Green.copy(alpha = 0.95f)
            in noseRange -> Color(0xFF9C27B0).copy(alpha = 0.9f)
            in mouthRange -> Color.Red.copy(alpha = 0.9f)
            else -> Color.White.copy(alpha = 0.8f)
        }

        drawCircle(
            color = color,
            radius = 3.dp.toPx(),
            center = point,
            style = Fill
        )

        // 绘制点位序号（使用对比色确保可读性）
        val textColor = Color.Black.copy(alpha = 0.85f)
        drawIntoCanvas { canvas ->
            textPaint.color = textColor.toArgb()
            canvas.nativeCanvas.drawText(
                index.toString(),
                point.x,
                point.y - 6.dp.toPx(),
                textPaint
            )
        }
    }

    // 绘制瘦脸控制点及方向（调试用）
    drawThinFaceControlPoints(points)

    // 绘制大眼控制点及方向（调试用）
    drawBigEyeControlPoints(points)
}

/**
 * 绘制大眼算法的2对控制点及方向
 * originIndex=瞳孔 -> targetIndex=眼角
 */
private fun DrawScope.drawBigEyeControlPoints(points: List<Offset>) {
    if (points.size < 78) return

    // 2对控制点映射（originIndex=瞳孔 -> targetIndex=眼角）
    val controlPairs = listOf(
        Pair(74, 72),   // 右瞳孔 -> 右眼内角
        Pair(77, 75)    // 左瞳孔 -> 左眼内角
    )

    controlPairs.forEachIndexed { idx, (originIdx, targetIdx) ->
        if (originIdx >= points.size || targetIdx >= points.size) return@forEachIndexed

        val origin = points[originIdx]
        val target = points[targetIdx]

        // 大眼使用径向放大，origin是瞳孔，target是眼角
        // 绘制从瞳孔到眼角的连线，表示放大中心方向
        val eyeColor = if (idx == 0) {
            Color(0xFFFFD700).copy(alpha = 0.9f)  // 金色 - 右眼
        } else {
            Color(0xFF00CED1).copy(alpha = 0.9f)  // 深青色 - 左眼
        }

        // 绘制瞳孔到眼角的实线
        drawLine(
            color = eyeColor.copy(alpha = 0.6f),
            start = origin,
            end = target,
            strokeWidth = 2.5f
        )

        // 绘制瞳孔点（大圆，表示放大中心）
        drawCircle(
            color = Color.Yellow.copy(alpha = 0.9f),
            radius = 8.dp.toPx(),
            center = origin,
            style = Stroke(width = 3f)
        )
        drawCircle(
            color = Color.Yellow.copy(alpha = 0.2f),
            radius = 8.dp.toPx(),
            center = origin,
            style = Fill
        )

        // 绘制眼角点（小圆，绿色）
        drawCircle(
            color = Color.Green.copy(alpha = 0.9f),
            radius = 4.dp.toPx(),
            center = target,
            style = Fill
        )

        // 绘制影响范围圆圈（radius = 瞳孔到眼角距离 * 5）
        val radiusPx = distance(
            vec2(target.x, target.y),
            vec2(origin.x, origin.y)
        ) * 5.0f
        drawCircle(
            color = eyeColor.copy(alpha = 0.3f),
            radius = radiusPx,
            center = origin,
            style = Stroke(width = 2.dp.toPx())
        )

        // 绘制放大方向箭头（从眼角指向瞳孔外侧，表示向外扩展）
        val direction = origin - target
        val angle = kotlin.math.atan2(direction.y, direction.x)
        val arrowLen = 20f
        val arrowAngle = 0.5f
        val tip = origin + direction * 0.5f
        val leftWing = Offset(
            tip.x - arrowLen * kotlin.math.cos(angle + arrowAngle),
            tip.y - arrowLen * kotlin.math.sin(angle + arrowAngle)
        )
        val rightWing = Offset(
            tip.x - arrowLen * kotlin.math.cos(angle - arrowAngle),
            tip.y - arrowLen * kotlin.math.sin(angle - arrowAngle)
        )
        drawLine(color = eyeColor, start = tip, end = leftWing, strokeWidth = 3f)
        drawLine(color = eyeColor, start = tip, end = rightWing, strokeWidth = 3f)

        // 标注序号和信息
        val textPaint = Paint().apply {
            textSize = 10.dp.toPx()
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val eyeLabel = if (idx == 0) "右眼" else "左眼"
        drawIntoCanvas { canvas ->
            textPaint.color = eyeColor.toArgb()
            canvas.nativeCanvas.drawText(
                "$eyeLabel: $originIdx(瞳孔)->$targetIdx(眼角)",
                origin.x,
                origin.y - 12.dp.toPx(),
                textPaint
            )
            canvas.nativeCanvas.drawText(
                "radius=${radiusPx.toInt()}px",
                origin.x,
                origin.y + 20.dp.toPx(),
                textPaint
            )
        }
    }
}

// GLSL风格辅助函数
private fun vec2(x: Float, y: Float): Offset = Offset(x, y)
private fun distance(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

/**
 * 绘制瘦脸算法的9对控制点及方向
 * originIndex -> targetIndex：从轮廓点指向鼻梁方向
 */
private fun DrawScope.drawThinFaceControlPoints(points: List<Offset>) {
    if (points.size < 50) return

    // 9对控制点映射（originIndex -> targetIndex）
    val controlPairs = listOf(
        Pair(3, 44),   // 右脸轮廓 -> 鼻梁右侧
        Pair(29, 44),  // 左脸轮廓 -> 鼻梁右侧
        Pair(7, 45),   // 右脸颊 -> 鼻梁中
        Pair(25, 45),  // 左脸颊 -> 鼻梁中
        Pair(10, 46),  // 右下颌 -> 鼻梁下
        Pair(22, 46),  // 左下颌 -> 鼻梁下
        Pair(14, 49),  // 右下巴 -> 下巴中心
        Pair(18, 49),  // 左下巴 -> 下巴中心
        Pair(16, 49)   // 下巴尖 -> 下巴中心
    )

    controlPairs.forEachIndexed { idx, (originIdx, targetIdx) ->
        if (originIdx >= points.size || targetIdx >= points.size) return@forEachIndexed

        val origin = points[originIdx]
        val target = points[targetIdx]

        // 绘制方向箭头（从 origin 指向 target，表示形变方向）
        val arrowColor = when (idx) {
            0, 1 -> Color(0xFFFF6D00).copy(alpha = 0.9f)  // 橙色 - 上部
            2, 3 -> Color(0xFF00E5FF).copy(alpha = 0.9f)  // 青色 - 中部
            4, 5 -> Color(0xFF76FF03).copy(alpha = 0.9f)  // 绿色 - 下部
            else -> Color(0xFFFF00FF).copy(alpha = 0.9f)  // 紫色 - 底部
        }

        // 绘制虚线连接 origin 和 target
        val direction = target - origin
        val segmentCount = 10
        for (i in 0 until segmentCount step 2) {
            val t1 = i.toFloat() / segmentCount
            val t2 = (i + 1).toFloat() / segmentCount
            drawLine(
                color = arrowColor.copy(alpha = 0.4f),
                start = origin + direction * t1,
                end = origin + direction * t2,
                strokeWidth = 2.5f
            )
        }

        // 绘制 origin 点（大圆，红色边框）
        drawCircle(
            color = Color.Red.copy(alpha = 0.9f),
            radius = 6.dp.toPx(),
            center = origin,
            style = Stroke(width = 2.5f)
        )
        drawCircle(
            color = Color.Red.copy(alpha = 0.3f),
            radius = 6.dp.toPx(),
            center = origin,
            style = Fill
        )

        // 绘制 target 点（小圆，绿色填充）
        drawCircle(
            color = Color.Green.copy(alpha = 0.9f),
            radius = 4.dp.toPx(),
            center = target,
            style = Fill
        )

        // 绘制箭头头部
        val arrowLen = 15f
        val arrowAngle = 0.5f  // 弧度
        val angle = kotlin.math.atan2(direction.y, direction.x)
        val tip = origin + direction * 0.85f
        val leftWing = Offset(
            tip.x - arrowLen * kotlin.math.cos(angle + arrowAngle),
            tip.y - arrowLen * kotlin.math.sin(angle + arrowAngle)
        )
        val rightWing = Offset(
            tip.x - arrowLen * kotlin.math.cos(angle - arrowAngle),
            tip.y - arrowLen * kotlin.math.sin(angle - arrowAngle)
        )
        drawLine(color = arrowColor, start = tip, end = leftWing, strokeWidth = 2.5f)
        drawLine(color = arrowColor, start = tip, end = rightWing, strokeWidth = 2.5f)

        // 标注序号
        val textPaint = Paint().apply {
            textSize = 10.dp.toPx()
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        drawIntoCanvas { canvas ->
            textPaint.color = arrowColor.toArgb()
            canvas.nativeCanvas.drawText(
                "$idx:$originIdx->$targetIdx",
                (origin.x + target.x) / 2,
                (origin.y + target.y) / 2,
                textPaint
            )
        }
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

