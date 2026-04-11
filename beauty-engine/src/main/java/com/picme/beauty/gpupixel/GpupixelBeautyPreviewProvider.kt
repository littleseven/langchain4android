package com.picme.beauty.gpupixel

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.picme.beauty.api.BeautyParams
import com.picme.beauty.api.BeautyPerfStats
import com.picme.beauty.api.BeautyPreviewEngine
import com.pixpark.gpupixel.FaceDetector
import com.pixpark.gpupixel.GPUPixel
import com.pixpark.gpupixel.GPUPixelFilter
import com.pixpark.gpupixel.GPUPixelSinkSurface
import com.pixpark.gpupixel.GPUPixelSourceRawData

/**
 * GPUPixel 美颜预览提供者
 *
 * 数据流：CameraX ImageAnalysis (YUV) → RGBA 转换 → 上层手动旋转 → GPUPixel 滤镜链 → TextureView 显示
 *
 * ## 旋转与宽高比处理策略（参考大美丽方案）
 *
 * **核心问题**：`GPUPixelSourceRawData.SetRotation()` 只旋转纹理坐标（在 SourceRawData 内部），
 * 不会把旋转信息传递给下游 `SinkSurface`。因此 `SinkSurface.UpdateDisplayVertices()` 拿到的
 * framebuffer 尺寸始终是传感器原始横向尺寸（如 1280×720），导致宽高比计算错误，画面被拉伸。
 *
 * **解决方案（纯 Kotlin 层，不修改 C++ 代码）**：
 * 1. 不使用 `SetRotation()`，改为上层手动旋转 RGBA 像素数据，使传入 GPUPixel 的帧已是视觉
 *    正确方向（90°/270° 时交换宽高）。这样 SinkSurface 看到的 framebuffer 尺寸与显示方向一致。
 * 2. 使用 `STRETCH` FillMode，让 GPUPixel 直接填满 TextureView。
 * 3. 在上层（[updateTextureViewSize]）根据内容实际宽高比调整 TextureView 的 LayoutParams，
 *    控制 TextureView 在父容器中的显示比例，从而实现正确的 Fit/Fill 效果。
 *
 * 与大美丽方案的对比：大美丽在 `applyViewport()` 里用 `glViewport` 自己计算比例；
 * GPUPixel 路径等效地通过 TextureView LayoutParams 实现同样效果。
 *
 * ## 关键时序说明
 * - TextureView 加入 window hierarchy 后才会触发 [TextureView.SurfaceTextureListener]
 * - [initialize] 可能在 surface 可用之前或之后调用
 * - 通过 [pendingDisplaySurface] 缓存解决初始化竞态问题：无论哪个先就绪，都能正确绑定
 *
 * 当前为实验性集成，支持磨皮、美白、瘦脸、大眼、唇色、腮红。
 */
class GpupixelBeautyPreviewProvider(
    context: Context
) : BeautyPreviewEngine {

    companion object {
        private const val TAG = "PicMe:GpuPixelProvider"
    }

    private val appContext: Context = context.applicationContext
    private val textureView: TextureView = TextureView(appContext)
    private var sinkSurface: GPUPixelSinkSurface? = null
    private var sourceRawData: GPUPixelSourceRawData? = null
    private var beautyFilter: GPUPixelFilter? = null
    private var lipstickFilter: GPUPixelFilter? = null
    private var blusherFilter: GPUPixelFilter? = null
    private var faceReshapeFilter: GPUPixelFilter? = null
    private var exposureFilter: GPUPixelFilter? = null
    private var contrastFilter: GPUPixelFilter? = null
    private var saturationFilter: GPUPixelFilter? = null
    private var whiteBalanceFilter: GPUPixelFilter? = null
    private var faceDetector: FaceDetector? = null
    private var isInitialized = false
    private var lastParams: BeautyParams = BeautyParams.EMPTY
    private var isFillCenter: Boolean = true
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    /**
     * 上一次成功渲染帧的内容宽高（旋转后的视觉方向尺寸），用于驱动 TextureView 尺寸更新。
     * 首帧处理完成后才有意义。
     */
    @Volatile
    private var contentWidth: Int = 0

    @Volatile
    private var contentHeight: Int = 0

    /**
     * 上一次 TextureView 宽高比更新时使用的内容尺寸，用于去重（避免每帧都更新 LayoutParams）。
     */
    private var lastUpdatedContentWidth: Int = 0
    private var lastUpdatedContentHeight: Int = 0

    /**
     * TextureView surface 是否已经可用（已触发 onSurfaceTextureAvailable）
     */
    @Volatile
    private var surfaceAvailable = false

    /**
     * 缓存已就绪的显示 Surface，用于解决初始化竞态：
     * 当 [onSurfaceTextureAvailable] 触发时 [sinkSurface] 还未创建，
     * 将 surface 缓存在此；[initialize] 完成后立即绑定。
     */
    private var pendingDisplaySurface: Surface? = null

    init {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                surfaceWidth = width
                surfaceHeight = height
                surfaceAvailable = true
                val surface = Surface(surfaceTexture)
                val sink = sinkSurface
                if (sink != null) {
                    // initialize() 已经完成，直接绑定
                    sink.SetSurface(surface, width, height)
                    pendingDisplaySurface?.release()
                    pendingDisplaySurface = surface
                    Log.d(TAG, "Surface available and sink ready: ${width}x${height}, binding immediately")
                } else {
                    // initialize() 尚未完成，缓存 surface 等待绑定
                    pendingDisplaySurface?.release()
                    pendingDisplaySurface = surface
                    Log.d(TAG, "Surface available but sink not ready yet: ${width}x${height}, caching for later")
                }
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                surfaceWidth = width
                surfaceHeight = height
                // 尺寸变化时重新绑定，确保 sinkSurface 使用正确尺寸
                val surface = Surface(surfaceTexture)
                pendingDisplaySurface?.release()
                pendingDisplaySurface = surface
                sinkSurface?.SetSurface(surface, width, height)
                Log.d(TAG, "Surface size changed: ${width}x${height}")
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                surfaceAvailable = false
                sinkSurface?.ReleaseSurface()
                pendingDisplaySurface?.release()
                pendingDisplaySurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                // No-op
            }
        }
    }

    override fun initialize() {
        if (isInitialized) return

        GPUPixel.Init(appContext)

        sourceRawData = GPUPixelSourceRawData.Create()
        beautyFilter = GPUPixelFilter.Create(GPUPixelFilter.BEAUTY_FACE_FILTER)
        lipstickFilter = GPUPixelFilter.Create(GPUPixelFilter.LIPSTICK_FILTER)
        blusherFilter = GPUPixelFilter.Create(GPUPixelFilter.BLUSHER_FILTER)
        faceReshapeFilter = GPUPixelFilter.Create(GPUPixelFilter.FACE_RESHAPE_FILTER)
        exposureFilter = GPUPixelFilter.Create(GPUPixelFilter.EXPOSURE_FILTER)
        contrastFilter = GPUPixelFilter.Create(GPUPixelFilter.CONTRAST_FILTER)
        saturationFilter = GPUPixelFilter.Create(GPUPixelFilter.SATURATION_FILTER)
        whiteBalanceFilter = GPUPixelFilter.Create(GPUPixelFilter.WHITE_BALANCE_FILTER)
        sinkSurface = GPUPixelSinkSurface.Create()
        faceDetector = FaceDetector.Create()

        // 初始化调色参数为中性值（不影响画面）
        exposureFilter?.SetProperty("exposure", 0f)
        contrastFilter?.SetProperty("contrast", 1f)
        saturationFilter?.SetProperty("saturation", 1f)
        whiteBalanceFilter?.SetProperty("temperature", 5000f)
        whiteBalanceFilter?.SetProperty("tint", 0f)

        // 构建滤镜链：rawData → lipstick → blusher → beauty → faceReshape → exposure → contrast → saturation → whiteBalance → sinkSurface
        sourceRawData?.AddSink(lipstickFilter)
        lipstickFilter?.AddSink(blusherFilter)
        blusherFilter?.AddSink(beautyFilter)
        beautyFilter?.AddSink(faceReshapeFilter)
        faceReshapeFilter?.AddSink(exposureFilter)
        exposureFilter?.AddSink(contrastFilter)
        contrastFilter?.AddSink(saturationFilter)
        saturationFilter?.AddSink(whiteBalanceFilter)
        whiteBalanceFilter?.AddSink(sinkSurface)

        applyParams(lastParams)

        // 使用 STRETCH 模式：由上层通过 TextureView LayoutParams 控制宽高比，
        // 避免 SinkSurface 内部使用未旋转的 framebuffer 尺寸做错误的宽高比计算。
        sinkSurface?.SetFillMode(GPUPixelSinkSurface.STRETCH)

        // 绑定 surface：优先使用已缓存的 pendingDisplaySurface（surface 先于 initialize 就绪的情况）
        val cachedSurface = pendingDisplaySurface
        if (cachedSurface != null && cachedSurface.isValid) {
            sinkSurface?.SetSurface(cachedSurface, surfaceWidth, surfaceHeight)
            Log.d(TAG, "Bound cached pending surface: ${surfaceWidth}x${surfaceHeight}")
        } else if (surfaceAvailable && textureView.isAvailable) {
            // 兜底：surface 可用但未缓存，直接从 textureView 获取
            val st = textureView.surfaceTexture
            if (st != null) {
                val surface = Surface(st)
                pendingDisplaySurface?.release()
                pendingDisplaySurface = surface
                sinkSurface?.SetSurface(surface, surfaceWidth, surfaceHeight)
                Log.d(TAG, "Bound surface from textureView: ${surfaceWidth}x${surfaceHeight}")
            } else {
                Log.w(TAG, "surfaceAvailable=true but surfaceTexture is null, will bind when surface is ready")
            }
        } else {
            Log.d(TAG, "Surface not yet available, will bind in onSurfaceTextureAvailable callback")
        }

        isInitialized = true
        Log.i(TAG, "GpupixelBeautyPreviewProvider initialized, surfaceAvailable=$surfaceAvailable")
    }

    /**
     * GPUPixel 模式下此方法不应被 CameraX Preview use case 调用
     * （CameraX 帧通过 ImageAnalysis → [onRgbaFrame] 路径传递）。
     * 为防止上层误调用导致崩溃，返回一个临时 Surface；如 TextureView 不可用则抛出异常。
     */
    override fun createPreviewSurface(): Surface {
        if (!isInitialized) initialize()
        val st = textureView.surfaceTexture
            ?: throw IllegalStateException(
                "GPUPixel TextureView surface not available. " +
                    "Ensure TextureView is attached to window hierarchy before calling createPreviewSurface(). " +
                    "GPUPixel mode uses ImageAnalysis path, createPreviewSurface() should not be called."
            )
        return Surface(st)
    }

    /**
     * 处理原始 RGBA 帧数据（推荐用法），由外部（app 层）在 analyzer 线程调用。
     *
     * ## 旋转处理策略
     *
     * 不使用 `GPUPixelSourceRawData.SetRotation()`，原因：
     * - `SetRotation` 只旋转 SourceRawData 内部的纹理坐标，不传递旋转信息到下游 SinkSurface
     * - SinkSurface.UpdateDisplayVertices() 用原始横向 framebuffer 尺寸（如 1280×720）计算宽高比
     *   → 画面被错误拉伸
     *
     * 改为上层手动旋转 RGBA 像素数据（[rotateRgba90CW] / [rotateRgba90CCW]）：
     * - 旋转后 width/height 互换，SinkSurface 看到的 framebuffer 是视觉正确方向的竖向尺寸
     * - SinkSurface 内部宽高比计算正确
     * - TextureView 通过 LayoutParams 控制最终显示比例（见 [updateTextureViewSize]）
     *
     * 参考：大美丽方案在 CameraPreviewRenderer.applyViewport() 中自行计算 viewport，
     * 本方案等效地通过 TextureView LayoutParams 实现。
     *
     * @param data   原始（未旋转）RGBA 数据
     * @param width  传感器原始宽（未旋转）
     * @param height 传感器原始高（未旋转）
     * @param rotationDegrees [androidx.camera.core.ImageProxy.imageInfo.rotationDegrees]，
     *                        取值 0 / 90 / 180 / 270
     */
    fun onRgbaFrame(data: ByteArray, width: Int, height: Int, rotationDegrees: Int) {
        if (!isInitialized) return
        if (!surfaceAvailable) return

        try {
            // 上层手动旋转，使 GPUPixel 看到视觉正确方向的帧
            val (rotatedData, rotatedWidth, rotatedHeight) = when (rotationDegrees) {
                90 -> Triple(rotateRgba90CW(data, width, height), height, width)
                180 -> Triple(rotateRgba180(data, width, height), width, height)
                270 -> Triple(rotateRgba90CCW(data, width, height), height, width)
                else -> Triple(data, width, height)
            }

            // 记录内容尺寸（旋转后），首帧或尺寸变化时更新 TextureView 宽高比
            if (rotatedWidth != contentWidth || rotatedHeight != contentHeight) {
                contentWidth = rotatedWidth
                contentHeight = rotatedHeight
                // 切换到主线程更新 LayoutParams
                textureView.post { updateTextureViewSize(rotatedWidth, rotatedHeight) }
            }

            val landmarks = faceDetector?.detect(
                rotatedData,
                rotatedWidth,
                rotatedHeight,
                rotatedWidth * 4,
                FaceDetector.GPUPIXEL_MODE_FMT_VIDEO,
                FaceDetector.GPUPIXEL_FRAME_TYPE_RGBA
            )

            if (landmarks != null && landmarks.isNotEmpty()) {
                faceReshapeFilter?.SetProperty("face_landmark", landmarks)
                lipstickFilter?.SetProperty("face_landmark", landmarks)
                blusherFilter?.SetProperty("face_landmark", landmarks)
            }

            sourceRawData?.ProcessData(
                rotatedData,
                rotatedWidth,
                rotatedHeight,
                rotatedWidth * 4,
                GPUPixelSourceRawData.FRAME_TYPE_RGBA
            )
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        }
    }

    /**
     * 根据内容宽高比调整 TextureView 的 LayoutParams，实现 Fit/Fill + **居中** 效果。
     *
     * 对应大美丽方案 [CameraPreviewRenderer.applyViewport]：
     * - 大美丽：`x = (outputWidth - viewportWidth) / 2`，`y = (outputHeight - viewportHeight) / 2`
     *   → `glViewport(x, y, viewportWidth, viewportHeight)` 将内容居中绘制
     * - 本方案：等效地使用 `FrameLayout.LayoutParams(width, height, Gravity.CENTER)`
     *   → TextureView 在父 FrameLayout 内居中放置
     *
     * 必须在主线程调用（通过 [textureView.post] 调度）。
     */
    private fun updateTextureViewSize(contentW: Int, contentH: Int) {
        if (contentW <= 0 || contentH <= 0) return
        // 去重：宽高比未变则跳过，避免无效 requestLayout
        if (contentW == lastUpdatedContentWidth && contentH == lastUpdatedContentHeight) return

        val parent = textureView.parent as? ViewGroup ?: return
        val parentWidth = parent.width
        val parentHeight = parent.height
        if (parentWidth <= 0 || parentHeight <= 0) return

        lastUpdatedContentWidth = contentW
        lastUpdatedContentHeight = contentH

        val contentAspect = contentW.toFloat() / contentH.toFloat()
        val parentAspect = parentWidth.toFloat() / parentHeight.toFloat()

        val layoutWidth: Int
        val layoutHeight: Int

        if (isFillCenter) {
            // Fill 模式（全屏）：等价于大美丽 applyViewport isFillCenter 分支
            // → 让 TextureView 超出父容器，配合父容器 clipChildren=true 裁剪多余部分
            // → 不会拉伸，保持内容宽高比
            if (contentAspect > parentAspect) {
                // 内容比父容器更宽（如竖屏下 16:9 画面）：以高度填满，宽度超出（左右裁剪）
                layoutHeight = parentHeight
                layoutWidth = (parentHeight * contentAspect).toInt().coerceAtLeast(1)
            } else {
                // 内容比父容器更窄（如宽屏下 9:16 画面）：以宽度填满，高度超出（上下裁剪）
                layoutWidth = parentWidth
                layoutHeight = (parentWidth / contentAspect).toInt().coerceAtLeast(1)
            }
            // 确保父容器裁剪超出部分（等价于大美丽 glViewport 超出 output 时的硬件裁剪）
            parent.clipChildren = true
        } else {
            // Fit 模式：按内容比例缩放 TextureView，保证内容不被裁剪
            // 等价于大美丽 applyViewport 中的 Fit 分支计算
            if (contentAspect > parentAspect) {
                // 内容比父容器更宽（如宽屏内容在竖屏容器）：以宽度为基准，上下留黑边
                layoutWidth = parentWidth
                layoutHeight = (parentWidth / contentAspect).toInt().coerceAtLeast(1)
            } else {
                // 内容比父容器更窄（如竖向内容在宽屏容器）：以高度为基准，左右留黑边
                layoutHeight = parentHeight
                layoutWidth = (parentHeight * contentAspect).toInt().coerceAtLeast(1)
            }
        }

        // 使用 FrameLayout.LayoutParams + Gravity.CENTER，实现与大美丽
        // glViewport(x=(output-viewport)/2, y=(output-viewport)/2, ...) 等价的居中效果
        val params = FrameLayout.LayoutParams(layoutWidth, layoutHeight).apply {
            gravity = Gravity.CENTER
        }
        textureView.layoutParams = params

        Log.d(TAG, "TextureView size updated: content=${contentW}x${contentH}, " +
            "parent=${parentWidth}x${parentHeight}, layout=${layoutWidth}x${layoutHeight}, " +
            "isFillCenter=$isFillCenter, gravity=CENTER")
    }

    override fun updateFilters(params: BeautyParams) {
        lastParams = params
        applyParams(params)
    }

    private fun applyParams(params: BeautyParams) {
        // 美颜参数：受 enabled 开关控制，关闭时归零
        if (!params.enabled) {
            beautyFilter?.SetProperty("skin_smoothing", 0.0f)
            beautyFilter?.SetProperty("whiteness", 0.0f)
            faceReshapeFilter?.SetProperty("thin_face", 0.0f)
            faceReshapeFilter?.SetProperty("big_eye", 0.0f)
            lipstickFilter?.SetProperty("blend_level", 0.0f)
            blusherFilter?.SetProperty("blend_level", 0.0f)
        } else {
            beautyFilter?.SetProperty("skin_smoothing", params.smoothing)
            beautyFilter?.SetProperty("whiteness", params.whitening)
            faceReshapeFilter?.SetProperty("thin_face", params.slimFace)
            faceReshapeFilter?.SetProperty("big_eye", params.bigEyes)
            lipstickFilter?.SetProperty("blend_level", params.lipColor)
            blusherFilter?.SetProperty("blend_level", params.blush)
        }

        // 调色参数：不受美颜开关控制，始终实时生效（默认值为中性，不影响画面）
        exposureFilter?.SetProperty("exposure", params.gpuExposure.coerceIn(-10f, 10f))
        contrastFilter?.SetProperty("contrast", params.gpuContrast.coerceIn(0f, 4f))
        saturationFilter?.SetProperty("saturation", params.gpuSaturation.coerceIn(0f, 2f))
        whiteBalanceFilter?.SetProperty("temperature", params.gpuWhiteBalance.coerceIn(2000f, 10000f))
        Log.d(
            TAG,
            "applyParams: enabled=${params.enabled}, exposure=${params.gpuExposure}, " +
                "contrast=${params.gpuContrast}, saturation=${params.gpuSaturation}, " +
                "whiteBalance=${params.gpuWhiteBalance}, blush=${params.blush}"
        )
    }

    override fun updateFaceWarpParams(
        faceCenterX: Float,
        faceCenterY: Float,
        leftEyeX: Float,
        leftEyeY: Float,
        rightEyeX: Float,
        rightEyeY: Float,
        mouthCenterX: Float,
        mouthCenterY: Float,
        mouthLeftX: Float,
        mouthLeftY: Float,
        mouthRightX: Float,
        mouthRightY: Float,
        upperLipCenterX: Float,
        upperLipCenterY: Float,
        lowerLipCenterX: Float,
        lowerLipCenterY: Float,
        faceRadius: Float,
        hasFace: Boolean
    ) {
        // GPUPixel 使用自带 FaceDetector 的 landmarks，此处忽略 ML Kit 参数
    }

    override fun updateLipMaskPoints(
        outerPoints: List<Pair<Float, Float>>,
        innerPoints: List<Pair<Float, Float>>
    ) {
        // GPUPixel 使用自带 FaceDetector 的 landmarks，此处忽略 ML Kit 参数
    }

    override fun release() {
        sourceRawData?.Destroy()
        lipstickFilter?.Destroy()
        blusherFilter?.Destroy()
        beautyFilter?.Destroy()
        faceReshapeFilter?.Destroy()
        exposureFilter?.Destroy()
        contrastFilter?.Destroy()
        saturationFilter?.Destroy()
        whiteBalanceFilter?.Destroy()
        sinkSurface?.Destroy()
        faceDetector?.destroy()
        pendingDisplaySurface?.release()
        sourceRawData = null
        lipstickFilter = null
        blusherFilter = null
        beautyFilter = null
        faceReshapeFilter = null
        exposureFilter = null
        contrastFilter = null
        saturationFilter = null
        whiteBalanceFilter = null
        sinkSurface = null
        faceDetector = null
        pendingDisplaySurface = null
        isInitialized = false
        contentWidth = 0
        contentHeight = 0
        lastUpdatedContentWidth = 0
        lastUpdatedContentHeight = 0
        Log.i(TAG, "GpupixelBeautyPreviewProvider released")
    }

    /**
     * GPUPixel 引擎是否已就绪。
     *
     * 只要 [isInitialized] 为 true 即视为就绪，避免 [surfaceAvailable] 未及时触发
     * 导致上层超时后回退到 PreviewView。
     * [surfaceAvailable] 未就绪时 [onRgbaFrame] 会静默丢帧，不会崩溃。
     */
    override fun isReady(): Boolean {
        return isInitialized
    }

    override fun getView(): View = textureView

    override fun setCameraInputBufferSize(width: Int, height: Int) {
        // GPUPixel 以实际帧尺寸为准，此处仅作兼容
    }

    override fun setScaleMode(isFillCenter: Boolean) {
        this.isFillCenter = isFillCenter
        // FillMode 固定为 STRETCH，由 TextureView LayoutParams 控制实际的 Fit/Fill 效果
        sinkSurface?.SetFillMode(GPUPixelSinkSurface.STRETCH)
        // 若已有内容尺寸，触发一次 LayoutParams 更新
        val w = contentWidth
        val h = contentHeight
        if (w > 0 && h > 0) {
            lastUpdatedContentWidth = 0  // 强制重新计算
            lastUpdatedContentHeight = 0
            textureView.post { updateTextureViewSize(w, h) }
        }
    }

    override fun getPerfStats(): BeautyPerfStats = BeautyPerfStats.EMPTY

    // ─── 像素旋转工具方法 ───────────────────────────────────────────────────────

    /**
     * 顺时针旋转 90°（CameraX rotationDegrees=90 时使用）。
     * 输出尺寸：width=srcHeight, height=srcWidth
     */
    private fun rotateRgba90CW(src: ByteArray, srcWidth: Int, srcHeight: Int): ByteArray {
        val dst = ByteArray(src.size)
        val dstWidth = srcHeight
        for (row in 0 until srcHeight) {
            for (col in 0 until srcWidth) {
                val srcIdx = (row * srcWidth + col) * 4
                // 顺时针 90°：dst[col][dstWidth - 1 - row] = src[row][col]
                val dstCol = dstWidth - 1 - row
                val dstRow = col
                val dstIdx = (dstRow * dstWidth + dstCol) * 4
                dst[dstIdx] = src[srcIdx]
                dst[dstIdx + 1] = src[srcIdx + 1]
                dst[dstIdx + 2] = src[srcIdx + 2]
                dst[dstIdx + 3] = src[srcIdx + 3]
            }
        }
        return dst
    }

    /**
     * 逆时针旋转 90°（CameraX rotationDegrees=270 时使用）。
     * 输出尺寸：width=srcHeight, height=srcWidth
     */
    private fun rotateRgba90CCW(src: ByteArray, srcWidth: Int, srcHeight: Int): ByteArray {
        val dst = ByteArray(src.size)
        val dstWidth = srcHeight
        val dstHeight = srcWidth
        for (row in 0 until srcHeight) {
            for (col in 0 until srcWidth) {
                val srcIdx = (row * srcWidth + col) * 4
                // 逆时针 90°：dst[srcWidth - 1 - col][row] = src[row][col]
                val dstRow = dstHeight - 1 - col
                val dstCol = row
                val dstIdx = (dstRow * dstWidth + dstCol) * 4
                dst[dstIdx] = src[srcIdx]
                dst[dstIdx + 1] = src[srcIdx + 1]
                dst[dstIdx + 2] = src[srcIdx + 2]
                dst[dstIdx + 3] = src[srcIdx + 3]
            }
        }
        return dst
    }

    /**
     * 旋转 180°（CameraX rotationDegrees=180 时使用）。
     * 输出尺寸：与输入相同
     */
    private fun rotateRgba180(src: ByteArray, srcWidth: Int, srcHeight: Int): ByteArray {
        val dst = ByteArray(src.size)
        val totalPixels = srcWidth * srcHeight
        for (i in 0 until totalPixels) {
            val srcIdx = i * 4
            val dstIdx = (totalPixels - 1 - i) * 4
            dst[dstIdx] = src[srcIdx]
            dst[dstIdx + 1] = src[srcIdx + 1]
            dst[dstIdx + 2] = src[srcIdx + 2]
            dst[dstIdx + 3] = src[srcIdx + 3]
        }
        return dst
    }
}
