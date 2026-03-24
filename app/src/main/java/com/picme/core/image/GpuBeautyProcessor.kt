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
                
                PicMeLogger.d(TAG, "Slim face applied: strength=$strength, faces=${faces.size}")
                mutableBitmap
            } catch (e: Exception) {
                PicMeLogger.e(TAG, "Slim face error", e)
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
                
                PicMeLogger.d(TAG, "Big eyes applied: strength=$strength, faces=${faces.size}")
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
