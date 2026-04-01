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
        val engine = pixelFree ?: return
        if (!isInitialized) return

        try {
            engine.pixelFreeSetBeautyFiterParam(type, value.coerceIn(0f, 1f))
            Logger.d(TAG, "Set beauty param: $type = $value")
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
        // PixelFree SDK 可能需要美妆资源
        // 如果没有加载美妆资源，返回原图
        Logger.w(TAG, "Lip color not supported without makeup bundle")
        return bitmap
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

        // 一次性设置所有美颜参数，然后统一处理
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

        // 处理一次
        return processWithPixelFree(bitmap)
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

