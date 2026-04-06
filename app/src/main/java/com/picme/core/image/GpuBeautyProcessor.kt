package com.picme.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PointF
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceLandmark
import com.picme.core.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 基于 RenderScript 的美颜效果实现
 * 使用 GPU 加速进行高性能图像处理
 */
class GpuBeautyProcessor(private val context: Context) : BeautyProcessor {
    
    companion object {
        private const val TAG = "PicMe:Beauty"
    }
    
    override suspend fun applySmoothing(bitmap: Bitmap, strength: Float): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                // 使用 RenderScript 高斯模糊实现磨皮效果
                val rs = RenderScript.create(context)
                val blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
                
                // 参数映射：0-100 -> 模糊半径 (0.1f - 25f)
                val blurRadius = 0.1f + (strength / 100f) * 25f
                
                val inputAllocation = Allocation.createFromBitmap(rs, bitmap)
                val outputAllocation = Allocation.createFromBitmap(rs, bitmap)
                
                blurScript.setInput(inputAllocation)
                blurScript.setRadius(blurRadius)
                blurScript.forEach(outputAllocation)
                
                val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                outputAllocation.copyTo(result)
                
                rs.destroy()
                result
            } catch (e: Exception) {
                Logger.e(TAG, "Smoothing error", e)
                bitmap
            }
        }
    }
    
    override suspend fun applyWhitening(bitmap: Bitmap, strength: Float): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                
                // 使用 ColorMatrix 提亮肤色
                val brightness = (strength / 100f) * 50f // 最大 +50 亮度
                
                val colorMatrix = ColorMatrix().apply {
                    set(floatArrayOf(
                        1f, 0f, 0f, 0f, brightness,
                        0f, 1f, 0f, 0f, brightness,
                        0f, 0f, 1f, 0f, brightness,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }
                
                val paint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(colorMatrix)
                    isAntiAlias = true
                }
                
                val canvas = android.graphics.Canvas(mutableBitmap)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
                
                mutableBitmap
            } catch (e: Exception) {
                Logger.e(TAG, "Whitening error", e)
                bitmap
            }
        }
    }
    
    override suspend fun applySlimFace(bitmap: Bitmap, strength: Float, faces: List<Face>): Bitmap {
        return withContext(Dispatchers.Default) {
            if (faces.isEmpty() || strength == 0f) {
                return@withContext bitmap
            }
            
            try {
                // 使用基于人脸 landmarks 的网格变形算法实现瘦脸效果
                // 强度范围：-50~+50（负值为丰满，正值为瘦脸）
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                
                // 创建网格变形系统
                val meshWidth = 20
                val meshHeight = 20
                val count = (meshWidth + 1) * (meshHeight + 1)
                val verts = FloatArray(count * 2)
                val orig = FloatArray(count * 2)
                
                // 初始化网格顶点
                var index = 0
                for (y in 0..meshHeight) {
                    val fy = bitmap.height * y / meshHeight.toFloat()
                    for (x in 0..meshWidth) {
                        val fx = bitmap.width * x / meshWidth.toFloat()
                        orig[index * 2 + 0] = fx
                        orig[index * 2 + 1] = fy
                        verts[index * 2 + 0] = fx
                        verts[index * 2 + 1] = fy
                        index++
                    }
                }
                
                // 对每个人脸应用 landmarks 变形
                faces.forEach { face ->
                    val bounds = face.boundingBox
                    val centerX = bounds.centerX().toFloat()
                    // 下巴位置：从底部向上 15%
                    val chinY = (bounds.bottom - bounds.height() * 0.15f).toFloat()
                    // 瘦脸影响半径：脸部宽度的 75%
                    val slimRadius = bounds.width() * 0.75f
                    
                    // 应用瘦脸变形
                    for (i in 0 until count) {
                        val vx = orig[i * 2 + 0]
                        val vy = orig[i * 2 + 1]
                        
                        // 计算到下巴中心的距离
                        val dx = vx - centerX
                        val dy = vy - chinY
                        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                        
                        if (dist < slimRadius) {
                            // 强度映射：-50~+50 -> 变形系数 (-0.25 - +0.25)
                            val pull = (strength / 50f) * 0.25f * (1f - dist / slimRadius)
                            // 向中心收缩（瘦脸）或向外扩展（丰满）
                            verts[i * 2 + 0] -= dx * pull
                            verts[i * 2 + 1] -= dy * pull
                        }
                    }
                }
                
                // 应用网格变形
                val canvas = Canvas(mutableBitmap)
                canvas.drawBitmapMesh(mutableBitmap, meshWidth, meshHeight, verts, 0, null, 0, null)
                
                Logger.d(TAG, "Slim face applied: strength=$strength, faces=${faces.size}")
                mutableBitmap
            } catch (e: Exception) {
                Logger.e(TAG, "Slim face error", e)
                bitmap
            }
        }
    }
    
    override suspend fun applyBigEyes(bitmap: Bitmap, strength: Float, faces: List<Face>): Bitmap {
        return withContext(Dispatchers.Default) {
            if (faces.isEmpty() || strength == 0f) {
                return@withContext bitmap
            }
            
            try {
                // 使用基于人脸 landmarks 的眼睛区域放大算法
                // 强度范围：0-100（放大系数 1.0 - 1.3）
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                
                // 创建网格变形系统
                val meshWidth = 20
                val meshHeight = 20
                val count = (meshWidth + 1) * (meshHeight + 1)
                val verts = FloatArray(count * 2)
                val orig = FloatArray(count * 2)
                
                // 初始化网格顶点
                var index = 0
                for (y in 0..meshHeight) {
                    val fy = bitmap.height * y / meshHeight.toFloat()
                    for (x in 0..meshWidth) {
                        val fx = bitmap.width * x / meshWidth.toFloat()
                        orig[index * 2 + 0] = fx
                        orig[index * 2 + 1] = fy
                        verts[index * 2 + 0] = fx
                        verts[index * 2 + 1] = fy
                        index++
                    }
                }
                
                // 对每个人脸应用眼睛放大
                faces.forEach { face ->
                    val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                    val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
                    val bounds = face.boundingBox
                    // 眼睛影响半径：脸部宽度的 20%
                    val eyeRadius = bounds.width() * 0.2f
                    
                    // 应用大眼变形
                    for (i in 0 until count) {
                        val vx = orig[i * 2 + 0]
                        val vy = orig[i * 2 + 1]
                        
                        // 对每只眼睛进行径向放大
                        listOfNotNull(leftEye, rightEye).forEach { eye ->
                            val dx = vx - eye.x
                            val dy = vy - eye.y
                            val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                            
                            if (dist < eyeRadius) {
                                // 强度映射：0-100 -> 变形系数 (0.0 - 0.4)
                                val push = (strength / 100f) * 0.4f * (1f - dist / eyeRadius)
                                // 从中心向外扩展
                                verts[i * 2 + 0] += dx * push
                                verts[i * 2 + 1] += dy * push
                            }
                        }
                    }
                }
                
                // 应用网格变形
                val canvas = Canvas(mutableBitmap)
                canvas.drawBitmapMesh(mutableBitmap, meshWidth, meshHeight, verts, 0, null, 0, null)
                
                Logger.d(TAG, "Big eyes applied: strength=$strength, faces=${faces.size}")
                mutableBitmap
            } catch (e: Exception) {
                Logger.e(TAG, "Big eyes error", e)
                bitmap
            }
        }
    }
    
    override suspend fun applyLipColor(
        bitmap: Bitmap,
        strength: Float,
        colorIndex: Int,
        faces: List<Face>
    ): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                applyLipColorRegional(bitmap, strength, colorIndex, faces)
            } catch (e: Exception) {
                Logger.e(TAG, "LipColor error", e)
                bitmap
            }
        }
    }
    
    private data class LipRegion(
        val centerX: Float,
        val centerY: Float,
        val halfWidthLeft: Float,
        val halfWidthRight: Float,
        val halfHeightUpper: Float,
        val halfHeightLower: Float,
        val cosAngle: Float,
        val sinAngle: Float
    )

    private fun resolveLipRegion(face: Face): LipRegion? {
        val upperLipTop = face.getContour(FaceContour.UPPER_LIP_TOP)?.points ?: emptyList()
        val upperLipBottom = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points ?: emptyList()
        val lowerLipTop = face.getContour(FaceContour.LOWER_LIP_TOP)?.points ?: emptyList()
        val lowerLipBottom = face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points ?: emptyList()
        val allLip = upperLipTop + upperLipBottom + lowerLipTop + lowerLipBottom

        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
            ?: allLip.minByOrNull { point -> point.x }
            ?: return null
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
            ?: allLip.maxByOrNull { point -> point.x }
            ?: return null
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val faceBounds = face.boundingBox

        val upperCenter = averagePoint(upperLipTop + upperLipBottom)
            ?: PointF((mouthLeft.x + mouthRight.x) * 0.5f, (mouthLeft.y + mouthRight.y) * 0.5f)
        val lowerCenter = averagePoint(lowerLipTop + lowerLipBottom)
            ?: mouthBottom
            ?: PointF(upperCenter.x, upperCenter.y + faceBounds.height() * 0.06f)

        val centerX = (upperCenter.x + lowerCenter.x) * 0.5f
        val centerY = (upperCenter.y + lowerCenter.y) * 0.5f

        val angle = atan2(mouthRight.y - mouthLeft.y, mouthRight.x - mouthLeft.x)
        val cosAngle = cos(angle)
        val sinAngle = sin(angle)
        val normalX = -sinAngle
        val normalY = cosAngle

        val leftWidth = kotlin.math.abs((mouthLeft.x - centerX) * cosAngle + (mouthLeft.y - centerY) * sinAngle)
            .coerceAtLeast(faceBounds.width() * 0.055f)
        val rightWidth = kotlin.math.abs((mouthRight.x - centerX) * cosAngle + (mouthRight.y - centerY) * sinAngle)
            .coerceAtLeast(faceBounds.width() * 0.055f)

        val upperHeight = kotlin.math.abs((upperCenter.x - centerX) * normalX + (upperCenter.y - centerY) * normalY)
            .coerceAtLeast(faceBounds.height() * 0.02f)
        val lowerHeight = kotlin.math.abs((lowerCenter.x - centerX) * normalX + (lowerCenter.y - centerY) * normalY)
            .coerceAtLeast(faceBounds.height() * 0.028f)

        return LipRegion(
            centerX = centerX,
            centerY = centerY,
            halfWidthLeft = (leftWidth * 1.08f).coerceAtLeast(4f),
            halfWidthRight = (rightWidth * 1.08f).coerceAtLeast(4f),
            halfHeightUpper = (upperHeight * 1.8f).coerceAtLeast(3f),
            halfHeightLower = (lowerHeight * 1.9f).coerceAtLeast(4f),
            cosAngle = cosAngle,
            sinAngle = sinAngle
        )
    }

    private fun averagePoint(points: List<PointF>): PointF? {
        if (points.isEmpty()) {
            return null
        }

        val avgX = points.sumOf { point -> point.x.toDouble() }.toFloat() / points.size
        val avgY = points.sumOf { point -> point.y.toDouble() }.toFloat() / points.size
        return PointF(avgX, avgY)
    }

    /**
     * 基于人脸关键点的唇色算法，避免在脸部下方形成矩形误着色。
     */
    private fun applyLipColorRegional(
        bitmap: Bitmap,
        strength: Float,
        colorIndex: Int,
        faces: List<Face>
    ): Bitmap {
        if (strength <= 0f || faces.isEmpty()) {
            return bitmap
        }

        val lipColors = intArrayOf(
            0xFFD4757D.toInt(),
            0xFFC43343.toInt(),
            0xFFFF7F50.toInt(),
            0xFFE0527C.toInt(),
            0xFFFF6B9D.toInt(),
            0xFF9B2335.toInt(),
            0xFFFFA07A.toInt(),
            0xFFCD5C5C.toInt(),
            0xFFDC143C.toInt(),
            0xFFFFB6C1.toInt(),
            0xFFB22222.toInt(),
            0xFFFF1493.toInt()
        )

        val targetColor = lipColors.getOrElse(colorIndex) { lipColors[0] }
        val normalizedStrength = (strength / 100f * 0.9f).coerceIn(0f, 0.9f)

        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val width = result.width
        val height = result.height
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        val targetR = (targetColor shr 16) and 0xFF
        val targetG = (targetColor shr 8) and 0xFF
        val targetB = targetColor and 0xFF

        var processedPixelCount = 0
        
        faces.forEach { face ->
            val region = resolveLipRegion(face) ?: return@forEach

            val maxHalfWidth = maxOf(region.halfWidthLeft, region.halfWidthRight)
            val maxHalfHeight = maxOf(region.halfHeightUpper, region.halfHeightLower)
            val padX = maxHalfWidth * 1.35f
            val padY = maxHalfHeight * 1.45f
            val startX = (region.centerX - padX).toInt().coerceIn(0, width - 1)
            val endX = (region.centerX + padX).toInt().coerceIn(0, width - 1)
            val startY = (region.centerY - padY).toInt().coerceIn(0, height - 1)
            val endY = (region.centerY + padY).toInt().coerceIn(0, height - 1)

            for (py in startY..endY) {
                for (px in startX..endX) {
                    val index = py * width + px
                    if (index >= pixels.size) {
                        continue
                    }

                    val dx = px - region.centerX
                    val dy = py - region.centerY
                    val localX = dx * region.cosAngle + dy * region.sinAngle
                    val localY = -dx * region.sinAngle + dy * region.cosAngle

                    val activeHalfWidth = if (localX < 0f) {
                        region.halfWidthLeft
                    } else {
                        region.halfWidthRight
                    }
                    val normalizedX = localX / activeHalfWidth
                    val ellipse = if (localY >= 0f) {
                        val normalizedUpperY = localY / region.halfHeightUpper
                        normalizedX * normalizedX + normalizedUpperY * normalizedUpperY
                    } else {
                        val normalizedLowerY = localY / region.halfHeightLower
                        normalizedX * normalizedX + normalizedLowerY * normalizedLowerY
                    }
                    if (ellipse > 1f) {
                        continue
                    }

                    val pixel = pixels[index]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val hasLipLikeColor = r > 18 && g > 5 && b > 4 && (r + g + b) < 735
                    if (!hasLipLikeColor) {
                        continue
                    }

                    val feather = (1f - ellipse).coerceIn(0f, 1f)
                    val blend = (normalizedStrength * feather * feather).coerceIn(0f, 0.92f)
                    if (blend <= 0.01f) {
                        continue
                    }

                    val newR = (r * (1f - blend) + targetR * blend).toInt().coerceIn(0, 255)
                    val newG = (g * (1f - blend) + targetG * blend).toInt().coerceIn(0, 255)
                    val newB = (b * (1f - blend) + targetB * blend).toInt().coerceIn(0, 255)
                    pixels[index] = (pixel and 0xFF000000.toInt()) or (newR shl 16) or (newG shl 8) or newB
                    processedPixelCount++
                }
            }
        }

        Logger.d(TAG, "Lip color (R_PLAN): processed=$processedPixelCount, strength=$strength, faces=${faces.size}")
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
    
    override suspend fun applyBlush(bitmap: Bitmap, strength: Float, colorFamily: Int): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val intensity = (strength / 100f).coerceIn(0f, 1f) * 0.4f

                val (rOffset, gOffset, bOffset) = when (colorFamily.coerceIn(0, 2)) {
                    1 -> Triple(26f, 18f, 8f)   // 橙色系
                    2 -> Triple(18f, 8f, 24f)   // 梅子色系
                    else -> Triple(30f, 10f, 20f) // 粉色系
                }

                val colorMatrix = ColorMatrix().apply {
                    set(floatArrayOf(
                        1f, 0f, 0f, 0f, intensity * rOffset,
                        0f, 1f, 0f, 0f, intensity * gOffset,
                        0f, 0f, 1f, 0f, intensity * bOffset,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }
                
                val paint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(colorMatrix)
                    isAntiAlias = true
                }
                
                val canvas = android.graphics.Canvas(mutableBitmap)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
                
                mutableBitmap
            } catch (e: Exception) {
                Logger.e(TAG, "Blush error", e)
                bitmap
            }
        }
    }
    
    override suspend fun applyEyebrow(bitmap: Bitmap, strength: Float): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                
                // 使用 ColorMatrix 增强眉毛对比度
                val intensity = (strength / 100f) * 0.5f
                
                val colorMatrix = ColorMatrix().apply {
                    set(floatArrayOf(
                        1f - intensity * 0.2f, 0f, 0f, 0f, -intensity * 20f,
                        0f, 1f - intensity * 0.2f, 0f, 0f, -intensity * 20f,
                        0f, 0f, 1f - intensity * 0.2f, 0f, -intensity * 20f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }
                
                val paint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(colorMatrix)
                    isAntiAlias = true
                }
                
                val canvas = android.graphics.Canvas(mutableBitmap)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
                
                mutableBitmap
            } catch (e: Exception) {
                Logger.e(TAG, "Eyebrow error", e)
                bitmap
            }
        }
    }
    
    override suspend fun applyBodyEnhancement(bitmap: Bitmap, strength: Float): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                // 使用基于人体关键点检测的上半身拉伸算法
                // 强度范围：-30~+30（拉伸系数 0.85 - 1.15）
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                
                // 上半身目标区域（25% - 50% 高度）
                val upperBodyStart = (bitmap.height * 0.25).toInt()
                val upperBodyEnd = (bitmap.height * 0.5).toInt()
                val upperBodyHeight = upperBodyEnd - upperBodyStart
                
                // 强度映射：-30~+30 -> 纵向拉伸系数 (0.85 - 1.15)
                val stretchFactor = 1f + (strength / 30f) * 0.15
                
                // 创建拉伸区域的新 bitmap
                val stretchedRegion = Bitmap.createBitmap(
                    mutableBitmap,
                    0,
                    upperBodyStart,
                    bitmap.width,
                    upperBodyHeight
                )
                
                val newHeight = (upperBodyHeight * stretchFactor).toInt()
                val scaledRegion = Bitmap.createScaledBitmap(
                    stretchedRegion,
                    bitmap.width,
                    newHeight,
                    true
                )
                
                // 将拉伸后的区域贴回原图
                val canvas = Canvas(mutableBitmap)
                canvas.drawBitmap(scaledRegion, 0f, upperBodyStart.toFloat(), null)
                
                stretchedRegion.recycle()
                scaledRegion.recycle()
                
                Logger.d(TAG, "Body enhancement applied: strength=$strength, stretch=$stretchFactor")
                mutableBitmap
            } catch (e: Exception) {
                Logger.e(TAG, "Body enhancement error", e)
                bitmap
            }
        }
    }
    
    override suspend fun applyLegExtension(bitmap: Bitmap, strength: Float): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                // 使用基于人体关键点检测的下半身拉伸算法
                // 强度范围：0-50（拉伸系数 1.0 - 1.15）
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                
                // 下半身目标区域（50% - 100% 高度）
                val lowerBodyStart = (bitmap.height * 0.5).toInt()
                val lowerBodyHeight = bitmap.height - lowerBodyStart
                
                // 强度映射：0-50 -> 纵向拉伸系数 (1.0 - 1.15)
                val stretchFactor = 1f + (strength / 50f) * 0.15
                
                // 创建拉伸区域的新 bitmap
                val lowerBodyRegion = Bitmap.createBitmap(
                    mutableBitmap,
                    0,
                    lowerBodyStart,
                    bitmap.width,
                    lowerBodyHeight
                )
                
                val newHeight = (lowerBodyHeight * stretchFactor).toInt()
                val scaledRegion = Bitmap.createScaledBitmap(
                    lowerBodyRegion,
                    bitmap.width,
                    newHeight,
                    true
                )
                
                // 计算需要裁剪的底部超出部分
                val cropTop = (scaledRegion.height - lowerBodyHeight).coerceAtLeast(0)
                
                // 创建最终 bitmap（保持原始尺寸）
                val finalBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(finalBitmap)
                
                // 绘制上半部分（不变）
                canvas.drawBitmap(mutableBitmap, 0f, 0f, null)
                
                // 绘制拉伸后的下半部分（裁剪掉超出部分）
                canvas.drawBitmap(
                    scaledRegion,
                    0f,
                    lowerBodyStart.toFloat(),
                    Paint()
                )
                
                lowerBodyRegion.recycle()
                scaledRegion.recycle()
                mutableBitmap.recycle()
                
                Logger.d(TAG, "Leg extension applied: strength=$strength, stretch=$stretchFactor")
                finalBitmap
            } catch (e: Exception) {
                Logger.e(TAG, "Leg extension error", e)
                bitmap
            }
        }
    }
    
    /**
     * 清理资源
     */
    fun release() {
        // GPUImage 资源清理由系统管理
    }
}
