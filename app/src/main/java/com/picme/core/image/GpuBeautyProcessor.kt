package com.picme.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import com.picme.core.common.PicMeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                // TODO: 使用 ML Kit 人脸 landmarks + 网格变形
                // 当前返回原图作为占位符
                PicMeLogger.d(TAG, "Slim face applied: $strength")
                bitmap
            } catch (e: Exception) {
                PicMeLogger.e(TAG, "Slim face error", e)
                bitmap
            }
        }
    }
    
    override suspend fun applyBigEyes(bitmap: Bitmap, strength: Float): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                // TODO: 使用径向变换放大眼睛区域
                PicMeLogger.d(TAG, "Big eyes applied: $strength")
                bitmap
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
                // TODO: 使用 MediaPipe Pose 检测人体关键点并调整
                PicMeLogger.d(TAG, "Body enhancement applied: $strength")
                bitmap
            } catch (e: Exception) {
                PicMeLogger.e(TAG, "Body enhancement error", e)
                bitmap
            }
        }
    }
    
    override suspend fun applyLegExtension(bitmap: Bitmap, strength: Float): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                // TODO: 下半身纵向拉伸 + 透视校正
                PicMeLogger.d(TAG, "Leg extension applied: $strength")
                bitmap
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
