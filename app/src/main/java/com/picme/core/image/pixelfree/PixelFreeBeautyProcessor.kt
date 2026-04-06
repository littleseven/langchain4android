package com.picme.core.image.pixelfree

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.face.Face
import com.hapi.pixelfree.PFBeautyFilterType
import com.hapi.pixelfree.PFDetectFormat
import com.hapi.pixelfree.PFImageInput
import com.hapi.pixelfree.PFRotationMode
import com.hapi.pixelfree.PixelFree
import com.picme.core.common.Logger
import com.picme.core.image.BeautyProcessor
import com.picme.domain.model.BeautySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [RD] PixelFreeEffects SDK 实现的美颜处理器
 *
 * 实施策略：短期方案（1-2 周）
 * - 快速上线产品功能
 * - 验证产品需求
 * - 收集用户反馈
 *
 * 技术特点：
 * - 基于 PixelFree SDK
 * - 支持离线美颜处理（拍照后处理）
 * - GPU 加速，性能优化
 *
 * @param context Android Context
 * @see com.picme.core.image.BeautyProcessor
 * @see PixelFreeGLSurfaceView
 */
class PixelFreeBeautyProcessor(private val context: Context) : BeautyProcessor {

    companion object {
        private const val TAG = "PixelFreeProcessor"
    }

    /** PixelFree 引擎 */
    private var pixelFree: PixelFree? = null

    /** 是否已初始化 */
    private var isInitialized = false

    /**
     * 初始化 PixelFree 引擎
     * 必须在首次使用前调用
     */
    private fun initializeIfNeeded() {
        if (isInitialized) return

        try {
            pixelFree = PixelFree()
            pixelFree?.create()

            // 加载授权文件（可选）
            val authData = pixelFree?.readBundleFile(context, "pixelfreeAuth.lic")
            if (authData != null) {
                pixelFree?.auth(context.applicationContext, authData, authData.size)
                Logger.d(TAG, "Auth file loaded")
            } else {
                Logger.d(TAG, "No auth file, using SDK without authentication")
            }

            // 加载滤镜资源（可选）
            val filterData = pixelFree?.readBundleFile(context, "filter_model.bundle")
            if (filterData != null) {
                pixelFree?.createBeautyItemFormBundle(
                    filterData,
                    filterData.size,
                    com.hapi.pixelfree.PFSrcType.PFSrcTypeFilter
                )
                Logger.d(TAG, "Filter bundle loaded")
            } else {
                Logger.d(TAG, "No filter bundle, basic beauty effects only")
            }

            isInitialized = true
            Logger.d(TAG, "PixelFree engine initialized successfully")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to initialize PixelFree engine", e)
        }
    }

    /**
     * 将 Bitmap 转换为 RGBA 字节数组
     */
    private fun bitmapToRGBA(bitmap: Bitmap): ByteArray {
        // 确保 Bitmap 是 ARGB_8888 格式
        val argbBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }

        val buffer = ByteBuffer.allocateDirect(argbBitmap.width * argbBitmap.height * 4)
        buffer.order(ByteOrder.nativeOrder())
        argbBitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()

        val pixels = ByteArray(buffer.remaining())
        buffer.get(pixels)

        Logger.d(TAG, "Converted bitmap to RGBA: ${argbBitmap.width}x${argbBitmap.height}, bytes=${pixels.size}")
        return pixels
    }

    /**
     * 将 RGBA 字节数组转换为 Bitmap
     */
    private fun rgbaToBitmap(data: ByteArray, width: Int, height: Int): Bitmap {
        val buffer = ByteBuffer.allocateDirect(data.size)
        buffer.order(ByteOrder.nativeOrder())
        buffer.put(data)
        buffer.rewind()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        Logger.d(TAG, "Converted RGBA to bitmap: ${width}x${height}, bytes=${data.size}")
        return bitmap
    }

    /**
     * 使用 PixelFree SDK 处理 RGBA 图像
     */
    private suspend fun processWithPixelFree(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        initializeIfNeeded()

        val engine = pixelFree
        if (engine == null || !isInitialized) {
            Logger.w(TAG, "Engine not initialized, returning original bitmap")
            return@withContext bitmap
        }

        try {
            Logger.d(TAG, "Starting PixelFree processing: ${bitmap.width}x${bitmap.height}, config=${bitmap.config}")

            // 转换 Bitmap 到 RGBA 字节数组
            val rgbaData = bitmapToRGBA(bitmap)

            // 验证数据完整性
            if (rgbaData.isEmpty()) {
                Logger.e(TAG, "RGBA data is empty, returning original bitmap")
                return@withContext bitmap
            }

            // 创建 PFImageInput
            val pxInput = PFImageInput()
            pxInput.textureID = 0
            pxInput.wigth = bitmap.width
            pxInput.height = bitmap.height
            pxInput.p_data0 = rgbaData
            pxInput.p_data1 = null
            pxInput.p_data2 = null
            pxInput.stride_0 = bitmap.width * 4
            pxInput.stride_1 = 0
            pxInput.stride_2 = 0
            pxInput.format = PFDetectFormat.PFFORMAT_IMAGE_RGBA
            pxInput.rotationMode = PFRotationMode.PFRotationMode0

            Logger.d(TAG, "Calling PixelFree processWithBuffer...")
            // 处理图像
            engine.processWithBuffer(pxInput)
            Logger.d(TAG, "PixelFree processWithBuffer completed")

            // 将处理后的数据转换回 Bitmap
            val processedData = pxInput.p_data0
            if (processedData == null || processedData.isEmpty()) {
                Logger.w(TAG, "Processed data is null or empty, returning original bitmap")
                return@withContext bitmap
            }

            val processedBitmap = rgbaToBitmap(processedData, bitmap.width, bitmap.height)

            // 验证处理后的 Bitmap 不是空白的
            if (processedBitmap.width == 0 || processedBitmap.height == 0) {
                Logger.e(TAG, "Processed bitmap has invalid dimensions, returning original")
                return@withContext bitmap
            }

            Logger.d(TAG, "Successfully processed bitmap: ${bitmap.width}x${bitmap.height}")
            return@withContext processedBitmap

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to process bitmap, returning original", e)
            return@withContext bitmap
        }
    }

    /**
     * 设置美颜参数
     */
    private fun setBeautyParam(type: PFBeautyFilterType, value: Float) {
        initializeIfNeeded()
        val engine = pixelFree ?: return
        if (!isInitialized) {
            Logger.w(TAG, "Skip beauty param update because PixelFree is not initialized")
            return
        }

        try {
            val normalizedValue = value.coerceIn(0f, 1f)
            engine.pixelFreeSetBeautyFiterParam(type, normalizedValue)
            Logger.d(TAG, "Set beauty param: $type = $normalizedValue")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to set beauty param", e)
        }
    }

    override suspend fun applySmoothing(bitmap: Bitmap, strength: Float): Bitmap {
        // 归一化：0-100 → 0.0-1.0
        val normalizedStrength = strength / 100f
        setBeautyParam(PFBeautyFilterType.PFBeautyFilterTypeFaceBlurStrength, normalizedStrength)
        return processWithPixelFree(bitmap)
    }

    override suspend fun applyWhitening(bitmap: Bitmap, strength: Float): Bitmap {
        // 归一化：0-100 → 0.0-1.0
        val normalizedStrength = strength / 100f
        setBeautyParam(PFBeautyFilterType.PFBeautyFilterTypeFaceM_newWhitenStrength, normalizedStrength)
        return processWithPixelFree(bitmap)
    }

    override suspend fun applySlimFace(bitmap: Bitmap, strength: Float, faces: List<Face>): Bitmap {
        // 归一化：-50~+50 → 0.0-1.0
        // 映射规则：-50 → 0.0, 0 → 0.5, +50 → 1.0
        val normalizedStrength = (strength + 50f) / 100f
        setBeautyParam(PFBeautyFilterType.PFBeautyFilterTypeFace_thinning, normalizedStrength)
        return processWithPixelFree(bitmap)
    }

    override suspend fun applyBigEyes(bitmap: Bitmap, strength: Float, faces: List<Face>): Bitmap {
        // 归一化：0-100 → 0.0-1.0，并做适度增益提升效果可见性
        val normalizedStrength = (strength / 100f * 1.35f).coerceIn(0f, 1f)
        setBeautyParam(PFBeautyFilterType.PFBeautyFilterTypeFace_EyeStrength, normalizedStrength)
        return processWithPixelFree(bitmap)
    }

    override suspend fun applyLipColor(bitmap: Bitmap, strength: Float, colorIndex: Int): Bitmap {
        // 由于 PixelFree SDK 需要美妆 bundle 资源，当前使用色彩叠加模拟唇色效果
        // 使用超宽松的检测条件确保效果可见
        return applyLipColorSimulation(bitmap, strength, colorIndex)
    }

    /**
     * 模拟唇色效果 - 基于色彩叠加
     * 使用超宽松的条件确保效果可见，后续可优化为基于人脸关键点
     */
    private fun applyLipColorSimulation(bitmap: Bitmap, strength: Float, colorIndex: Int): Bitmap {
        if (strength <= 0) return bitmap

        // 12 种预设唇色 (ARGB)
        val lipColors = intArrayOf(
            0xFFD4757D.toInt(), // 0: 豆沙色
            0xFFC43343.toInt(), // 1: 正红色
            0xFFFF7F50.toInt(), // 2: 珊瑚色
            0xFFE0527C.toInt(), // 3: 玫瑰色
            0xFFFF6B9D.toInt(), // 4: 粉色
            0xFF9B2335.toInt(), // 5: 酒红色
            0xFFFFA07A.toInt(), // 6: 浅粉色
            0xFFCD5C5C.toInt(), // 7: 印度红
            0xFFDC143C.toInt(), // 8: 深红色
            0xFFFFB6C1.toInt(), // 9: 浅玫瑰色
            0xFFB22222.toInt(), // 10: 火砖色
            0xFFFF1493.toInt()  // 11: 深粉色
        )

        val targetColor = lipColors.getOrElse(colorIndex) { lipColors[0] }
        // 增加强度确保效果可见
        val normalizedStrength = (strength / 100f * 0.85f).coerceIn(0f, 0.85f)

        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val width = result.width
        val height = result.height
        val pixels = IntArray(width * height)

        result.getPixels(pixels, 0, width, 0, 0, width, height)

        val targetR = (targetColor shr 16) and 0xFF
        val targetG = (targetColor shr 8) and 0xFF
        val targetB = targetColor and 0xFF

        var lipPixelCount = 0
        var processedPixelCount = 0
        
        // 处理区域：图像下半部分的中间区域（嘴部区域）
        val startY = (height * 0.6).toInt()
        val endY = (height * 0.8).toInt()
        val centerX = width / 2
        val maxRadius = width * 0.3

        for (py in startY until endY) {
            for (px in 0 until width) {
                val i = py * width + px
                if (i >= pixels.size) continue
                
                val distFromCenter = kotlin.math.abs(px - centerX)
                if (distFromCenter > maxRadius) continue
                
                val pixel = pixels[i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // 超宽松检测：只要有一定颜色就处理
                val hasColor = r > 40 && g > 20 && b > 15
                val notTooBright = (r + g + b) < 600
                
                if (hasColor && notTooBright) {
                    lipPixelCount++
                    
                    val positionFactor = 1.0f - (distFromCenter / maxRadius) * 0.4f
                    val effectiveStrength = normalizedStrength * positionFactor

                    if (effectiveStrength > 0.08f) {
                        processedPixelCount++
                        
                        val newR = (r * (1 - effectiveStrength) + targetR * effectiveStrength).toInt().coerceIn(0, 255)
                        val newG = (g * (1 - effectiveStrength) + targetG * effectiveStrength).toInt().coerceIn(0, 255)
                        val newB = (b * (1 - effectiveStrength) + targetB * effectiveStrength).toInt().coerceIn(0, 255)

                        pixels[i] = (pixel and 0xFF000000.toInt()) or (newR shl 16) or (newG shl 8) or newB
                    }
                }
            }
        }

        Logger.d(TAG, "Lip color: detected=$lipPixelCount, processed=$processedPixelCount, strength=$strength")

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    override suspend fun applyBlush(bitmap: Bitmap, strength: Float): Bitmap {
        // PixelFree SDK 可能需要美妆资源
        Logger.w(TAG, "Blush not supported without makeup bundle")
        return bitmap
    }

    override suspend fun applyEyebrow(bitmap: Bitmap, strength: Float): Bitmap {
        // PixelFree SDK 可能需要美妆资源
        Logger.w(TAG, "Eyebrow not supported without makeup bundle")
        return bitmap
    }

    override suspend fun applyBodyEnhancement(bitmap: Bitmap, strength: Float): Bitmap {
        // PixelFree SDK 可能没有直接的身材调整参数
        Logger.w(TAG, "Body enhancement not supported by PixelFree SDK")
        return bitmap
    }

    override suspend fun applyLegExtension(bitmap: Bitmap, strength: Float): Bitmap {
        // PixelFree SDK 可能没有直接的长腿参数
        Logger.w(TAG, "Leg extension not supported by PixelFree SDK")
        return bitmap
    }

    override suspend fun applyAllEffects(
        bitmap: Bitmap,
        settings: BeautySettings,
        faces: List<Face>
    ): Bitmap {
        initializeIfNeeded()

        var result = bitmap

        // ========== 第一步：妆容调节（唇色、腮红、眉毛）==========
        // 注意：必须在 PixelFree 处理之前应用，因为 PixelFree 的美白/磨皮
        // 会改变肤色特征，导致唇色检测失效
        if (faces.isNotEmpty()) {
            if (settings.lipColor > 0) {
                Logger.d(TAG, "Applying lip color BEFORE PixelFree processing: ${settings.lipColor}")
                result = applyLipColor(result, settings.lipColor, settings.lipColorIndex)
            }
            if (settings.blush > 0) {
                result = applyBlush(result, settings.blush)
            }
            if (settings.eyebrow > 0) {
                result = applyEyebrow(result, settings.eyebrow)
            }
        }

        // ========== 第二步：PixelFree SDK 基础美颜（磨皮、美白、瘦脸、大眼）==========
        if (settings.smoothing > 0) {
            setBeautyParam(PFBeautyFilterType.PFBeautyFilterTypeFaceBlurStrength, settings.smoothing / 100f)
        }
        if (settings.whitening > 0) {
            setBeautyParam(PFBeautyFilterType.PFBeautyFilterTypeFaceM_newWhitenStrength, settings.whitening / 100f)
        }
        if (faces.isNotEmpty()) {
            if (settings.slimFace != 0f) {
                val normalizedSlimFace = (settings.slimFace + 50f) / 100f
                setBeautyParam(PFBeautyFilterType.PFBeautyFilterTypeFace_thinning, normalizedSlimFace)
            }
            if (settings.bigEyes > 0) {
                setBeautyParam(PFBeautyFilterType.PFBeautyFilterTypeFace_EyeStrength, settings.bigEyes / 100f)
            }
        }

        // 处理基础美颜
        result = processWithPixelFree(result)

        // ========== 第三步：身材管理 ==========
        if (faces.isNotEmpty() && (settings.bodyEnhancement != 0f || settings.legExtension > 0f)) {
            if (settings.bodyEnhancement != 0f) {
                result = applyBodyEnhancement(result, settings.bodyEnhancement)
            }
            if (settings.legExtension > 0) {
                result = applyLegExtension(result, settings.legExtension)
            }
        }

        return result
    }

    /**
     * 释放资源
     */
    fun release() {
        if (!isInitialized) return

        try {
            pixelFree?.release()
            pixelFree = null
            isInitialized = false
            Logger.d(TAG, "PixelFree engine released")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to release engine", e)
        }
    }
}

