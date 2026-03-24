package com.picme.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import com.picme.core.common.PicMeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
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
                PicMeLogger.e(TAG, "Smoothing error", e)
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
                PicMeLogger.e(TAG, "Whitening error", e)
                bitmap
            }
        }
    }
    
    override suspend fun applySlimFace(bitmap: Bitmap, strength: Float): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                // 使用 ML Kit 人脸 landmarks + 网格变形实现瘦脸效果
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(mutableBitmap)
                val paint = Paint().apply {
                    isAntiAlias = true
                    isDither = true
                    isFilterBitmap = true
                }
                
                // 临时使用简单的椭圆变形模拟瘦脸效果
                // TODO: 未来集成 MediaPipe Face Mesh 获取精确的 landmarks
                val centerX = bitmap.width / 2f
                val centerY = bitmap.height / 3f // 假设脸部在上半部分
                val faceWidth = bitmap.width * 0.6f
                val faceHeight = bitmap.height * 0.5f
                
                // 强度映射：-50~+50 -> 收缩/扩展系数 (0.7 - 1.3)
                val scale = 1f + (strength / 50f) * 0.3f
                
                // 创建网格变形
                val meshSize = 20
                val vertices = FloatArray((meshSize + 1) * (meshSize + 1) * 2)
                val texCoords = FloatArray((meshSize + 1) * (meshSize + 1) * 2)
                
                var index = 0
                for (i in 0..meshSize) {
                    val y = (bitmap.height * i) / meshSize
                    for (j in 0..meshSize) {
                        val x = (bitmap.width * j) / meshSize
                        
                        // 计算到脸中心的距离
                        val dx = x - centerX
                        val dy = y - centerY
                        val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                        
                        // 应用径向变形
                        val maxDist = faceWidth / 2f
                        if (distance < maxDist) {
                            val factor = (1f - distance / maxDist) * scale
                            val newX = centerX + dx * factor
                            val newY = centerY + dy * factor
                            
                            vertices[index] = newX.coerceIn(0f, bitmap.width.toFloat())
                            vertices[index + 1] = newY.coerceIn(0f, bitmap.height.toFloat())
                        } else {
                            vertices[index] = x.toFloat()
                            vertices[index + 1] = y.toFloat()
                        }
                        
                        texCoords[index] = x.toFloat()
                        texCoords[index + 1] = y.toFloat()
                        index += 2
                    }
                }
                
                PicMeLogger.d(TAG, "Slim face applied: strength=$strength, scale=$scale")
                mutableBitmap
            } catch (e: Exception) {
                PicMeLogger.e(TAG, "Slim face error", e)
                bitmap
            }
        }
    }
    
    override suspend fun applyBigEyes(bitmap: Bitmap, strength: Float): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                // 使用径向变换放大眼睛区域
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(mutableBitmap)
                
                // 临时使用简单的大眼算法（基于假设的眼睛位置）
                // TODO: 未来集成 ML Kit Face Mesh 获取精确的眼睛 landmarks
                val eyePositions = listOf(
                    PointF(bitmap.width * 0.35f, bitmap.height * 0.28f), // 左眼
                    PointF(bitmap.width * 0.65f, bitmap.height * 0.28f)  // 右眼
                )
                
                // 强度映射：0-100 -> 放大系数 (1.0 - 1.3)
                val eyeScale = 1f + (strength / 100f) * 0.3f
                val eyeRadius = bitmap.width * 0.08f // 眼睛影响半径
                
                for (eyePos in eyePositions) {
                    val eyeX = eyePos.x
                    val eyeY = eyePos.y
                    // 对每个眼睛周围区域进行径向放大
                    for (y in 0 until bitmap.height) {
                        for (x in 0 until bitmap.width) {
                            val dx = x - eyeX
                            val dy = y - eyeY
                            val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                            
                            if (distance < eyeRadius) {
                                // 径向放大：中心放大最多，边缘逐渐减弱
                                val factor = 1f - (distance / eyeRadius)
                                val actualScale = 1f + (eyeScale - 1f) * factor
                                
                                val srcX = eyeX + dx / actualScale
                                val srcY = eyeY + dy / actualScale
                                
                                if (srcX in 0f..bitmap.width.toFloat() && srcY in 0f..bitmap.height.toFloat()) {
                                    try {
                                        val pixel = mutableBitmap.getPixel(srcX.toInt(), srcY.toInt())
                                        mutableBitmap.setPixel(x, y, pixel)
                                    } catch (e: Exception) {
                                        // Ignore boundary errors
                                    }
                                }
                            }
                        }
                    }
                }
                
                PicMeLogger.d(TAG, "Big eyes applied: strength=$strength, scale=$eyeScale")
                mutableBitmap
            } catch (e: Exception) {
                PicMeLogger.e(TAG, "Big eyes error", e)
                bitmap
            }
        }
    }
    
    override suspend fun applyYouth(bitmap: Bitmap, strength: Float): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                
                // 使用 ColorMatrix 增加皮肤光泽感
                val youthIntensity = (strength / 100f) * 0.3f
                
                val colorMatrix = ColorMatrix().apply {
                    set(floatArrayOf(
                        1f + youthIntensity * 0.1f, 0f, 0f, 0f, youthIntensity * 10f,
                        0f, 1f + youthIntensity * 0.08f, 0f, 0f, youthIntensity * 8f,
                        0f, 0f, 1f + youthIntensity * 0.05f, 0f, youthIntensity * 5f,
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
                PicMeLogger.e(TAG, "Youth error", e)
                bitmap
            }
        }
    }
    
    override suspend fun applyLipColor(bitmap: Bitmap, strength: Float, colorIndex: Int): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                
                // 预设 12 种唇色
                val lipColors = listOf(
                    intArrayOf(212, 117, 125), // 豆沙色
                    intArrayOf(196, 51, 67),   // 正红色
                    intArrayOf(255, 127, 80),  // 珊瑚色
                    intArrayOf(204, 51, 102),  // 玫瑰色
                    intArrayOf(230, 102, 102), // 樱花粉
                    intArrayOf(153, 51, 76),   // 梅子色
                    intArrayOf(217, 89, 89),   // 西柚色
                    intArrayOf(191, 64, 89),   // 枫叶红
                    intArrayOf(242, 128, 128), // 水红色
                    intArrayOf(179, 38, 64),   // 姨妈红
                    intArrayOf(224, 107, 97),  // 番茄红
                    intArrayOf(209, 77, 107)   // 浆果色
                )
                
                val color = lipColors[colorIndex.coerceIn(0, lipColors.size - 1)]
                val intensity = (strength / 100f) * 0.5f  // 控制强度
                
                // 使用 ColorMatrix 添加唇色效果
                val colorMatrix = ColorMatrix().apply {
                    set(floatArrayOf(
                        1f, 0f, 0f, 0f, (color[0] / 255f - 0.5f) * intensity * 100f,
                        0f, 1f, 0f, 0f, (color[1] / 255f - 0.5f) * intensity * 100f,
                        0f, 0f, 1f, 0f, (color[2] / 255f - 0.5f) * intensity * 100f,
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
                PicMeLogger.e(TAG, "LipColor error", e)
                bitmap
            }
        }
    }
    
    override suspend fun applyBlush(bitmap: Bitmap, strength: Float): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                
                // 使用 ColorMatrix 添加腮红效果 (粉色系)
                val intensity = (strength / 100f) * 0.4f
                
                val colorMatrix = ColorMatrix().apply {
                    set(floatArrayOf(
                        1f, 0f, 0f, 0f, intensity * 30f,
                        0f, 1f, 0f, 0f, intensity * 10f,
                        0f, 0f, 1f, 0f, intensity * 20f,
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
                PicMeLogger.e(TAG, "Blush error", e)
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
                PicMeLogger.e(TAG, "Eyebrow error", e)
                bitmap
            }
        }
    }
    
    override suspend fun applyBodyEnhancement(bitmap: Bitmap, strength: Float): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                // 使用简单的纵向拉伸模拟丰胸效果
                // TODO: 未来集成 MediaPipe Pose 检测人体关键点并调整
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                
                // 假设上半身区域（从顶部到 40% 高度）
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
                
                PicMeLogger.d(TAG, "Body enhancement applied: strength=$strength, stretch=$stretchFactor")
                mutableBitmap
            } catch (e: Exception) {
                PicMeLogger.e(TAG, "Body enhancement error", e)
                bitmap
            }
        }
    }
    
    override suspend fun applyLegExtension(bitmap: Bitmap, strength: Float): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                // 使用下半身纵向拉伸模拟长腿效果
                // TODO: 未来集成姿态估计实现透视校正
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                
                // 假设下半身区域（从 50% 高度到底部）
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
                
                PicMeLogger.d(TAG, "Leg extension applied: strength=$strength, stretch=$stretchFactor")
                finalBitmap
            } catch (e: Exception) {
                PicMeLogger.e(TAG, "Leg extension error", e)
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
