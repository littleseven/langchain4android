package com.picme.core.image.pixelfree

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceLandmark
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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

    override suspend fun applyLipColor(
        bitmap: Bitmap,
        strength: Float,
        colorIndex: Int,
        faces: List<Face>
    ): Bitmap {
        // PixelFree 离线链路优先使用关键点定位，避免出现固定矩形着色。
        return applyLipColorSimulation(bitmap, strength, colorIndex, faces)
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
     * 模拟唇色效果 - 基于关键点定位，避免全局矩形误着色
     */
    private fun applyLipColorSimulation(
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

        Logger.d(TAG, "Lip color: processed=$processedPixelCount, strength=$strength, faces=${faces.size}")

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

                val colorMatrix = android.graphics.ColorMatrix().apply {
                    set(floatArrayOf(
                        1f, 0f, 0f, 0f, intensity * rOffset,
                        0f, 1f, 0f, 0f, intensity * gOffset,
                        0f, 0f, 1f, 0f, intensity * bOffset,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }

                val paint = android.graphics.Paint().apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
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
                val intensity = (strength / 100f).coerceIn(0f, 1f) * 0.5f

                val colorMatrix = android.graphics.ColorMatrix().apply {
                    set(floatArrayOf(
                        1f - intensity * 0.2f, 0f, 0f, 0f, -intensity * 20f,
                        0f, 1f - intensity * 0.2f, 0f, 0f, -intensity * 20f,
                        0f, 0f, 1f - intensity * 0.2f, 0f, -intensity * 20f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }

                val paint = android.graphics.Paint().apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
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
                result = applyLipColor(result, settings.lipColor, settings.lipColorIndex, faces)
            }
            if (settings.blush > 0) {
                result = applyBlush(result, settings.blush, settings.blushColorFamily)
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

