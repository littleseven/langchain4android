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
import android.graphics.Bitmap
import com.pixpark.gpupixel.GPUPixel
import com.pixpark.gpupixel.GPUPixelFilter
import com.pixpark.gpupixel.GPUPixelSinkRawData
import com.pixpark.gpupixel.GPUPixelSinkSurface
import com.pixpark.gpupixel.GPUPixelSourceImage
import com.pixpark.gpupixel.GPUPixelSourceRawData
import com.pixpark.gpupixel.GPUPixelSourceYUV
import java.nio.ByteBuffer

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
    context: Context,
    private val onFaceLandmarksDetected: ((FloatArray?) -> Unit)? = null
) : BeautyPreviewEngine {

    companion object {
        private const val TAG = "PicMe:GpuPixelProvider"
    }

    private val appContext: Context = context.applicationContext
    private val textureView: TextureView = TextureView(appContext)
    private var sinkSurface: GPUPixelSinkSurface? = null
    private var sourceRawData: GPUPixelSourceRawData? = null
    private var sourceYUV: GPUPixelSourceYUV? = null
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
    /** 缓存最近一次检测到的人脸 landmarks，供拍照处理时复用 */
    @Volatile
    private var lastDetectedLandmarks: FloatArray? = null
    /** 当前激活的风格特效滤镜（null 表示无特效 / STYLE_NONE） */
    private var activeStyleFilter: GPUPixelFilter? = null
    /** 当前激活的风格特效类名，用于判断是否需要切换 */
    private var activeStyleFilterClassName: String? = null
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
        sourceYUV = GPUPixelSourceYUV.Create()
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

        // 构建滤镜链：sourceYUV → lipstick → blusher → beauty → faceReshape → exposure → contrast → saturation → whiteBalance → [styleFilter] → sinkSurface
        sourceYUV?.AddSink(lipstickFilter)
        lipstickFilter?.AddSink(blusherFilter)
        blusherFilter?.AddSink(beautyFilter)
        beautyFilter?.AddSink(faceReshapeFilter)
        faceReshapeFilter?.AddSink(exposureFilter)
        exposureFilter?.AddSink(contrastFilter)
        contrastFilter?.AddSink(saturationFilter)
        saturationFilter?.AddSink(whiteBalanceFilter)
        // 初始时无风格特效，直连 sinkSurface
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
     * 处理 I420 帧数据（YUV 直通路径），由外部（app 层）在 analyzer 线程调用。
     *
     * 旋转已在 Native 层 YUV→I420 转换时完成，此处直接透传。
     * 使用 DirectByteBuffer 零拷贝传递给 GPUPixel 渲染链路和人脸检测。
     *
     * @param yBuffer Y 平面 DirectByteBuffer
     * @param uBuffer U 平面 DirectByteBuffer
     * @param vBuffer V 平面 DirectByteBuffer
     * @param width   帧宽（已旋转后）
     * @param height  帧高（已旋转后）
     * @param rotationDegrees 保留参数，当前始终为 0（旋转已前置完成）
     */
    fun onYuvFrame(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        rgbaBuffer: ByteBuffer? = null
    ) {
        if (!isInitialized) return
        if (!surfaceAvailable) return

        try {
            // 记录内容尺寸，首帧或尺寸变化时更新 TextureView 宽高比
            if (width != contentWidth || height != contentHeight) {
                contentWidth = width
                contentHeight = height
                // 切换到主线程更新 LayoutParams
                textureView.post { updateTextureViewSize(width, height) }
            }

            // 人脸检测：mars-face-kit 对 YUV_I420 支持不完善且会污染内部状态，必须使用 RGBA 路径
            val landmarks = rgbaBuffer?.let {
                faceDetector?.detect(
                    it,
                    width,
                    height,
                    width * 4,
                    FaceDetector.GPUPIXEL_MODE_FMT_VIDEO,
                    FaceDetector.GPUPIXEL_FRAME_TYPE_RGBA
                )
            }

            if (landmarks != null && landmarks.isNotEmpty()) {
                lastDetectedLandmarks = landmarks
                faceReshapeFilter?.SetProperty("face_landmark", landmarks)
                lipstickFilter?.SetProperty("face_landmark", landmarks)
                blusherFilter?.SetProperty("face_landmark", landmarks)
                // 回调 106 点数据用于调试 UI 显示
                onFaceLandmarksDetected?.invoke(landmarks)
            } else {
                onFaceLandmarksDetected?.invoke(null)
            }

            // 零拷贝 YUV 直通传递给 GPUPixel 渲染链路
            sourceYUV?.ProcessData(
                yBuffer,
                uBuffer,
                vBuffer,
                width,
                height,
                0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        }
    }

    /**
     * 处理原始 RGBA 帧数据（兼容/兜底路径）。
     *
     * @param buffer DirectByteBuffer 包含已旋转的 RGBA 数据
     * @param width  帧宽（已旋转后）
     * @param height 帧高（已旋转后）
     * @param rotationDegrees 保留参数
     */
    fun onRgbaFrame(buffer: ByteBuffer, width: Int, height: Int, rotationDegrees: Int) {
        if (!isInitialized) return
        if (!surfaceAvailable) return

        try {
            if (width != contentWidth || height != contentHeight) {
                contentWidth = width
                contentHeight = height
                textureView.post { updateTextureViewSize(width, height) }
            }

            val landmarks = faceDetector?.detect(
                buffer,
                width,
                height,
                width * 4,
                FaceDetector.GPUPIXEL_MODE_FMT_VIDEO,
                FaceDetector.GPUPIXEL_FRAME_TYPE_RGBA
            )

            if (landmarks != null && landmarks.isNotEmpty()) {
                faceReshapeFilter?.SetProperty("face_landmark", landmarks)
                lipstickFilter?.SetProperty("face_landmark", landmarks)
                blusherFilter?.SetProperty("face_landmark", landmarks)
                onFaceLandmarksDetected?.invoke(landmarks)
            } else {
                onFaceLandmarksDetected?.invoke(null)
            }

            sourceRawData?.ProcessData(
                buffer,
                width,
                height,
                width * 4,
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

    /**
     * 使用 GPUPixel 滤镜链处理拍照后的 Bitmap，确保预览与拍照效果一致。
     *
     * 实现方式：独立创建一套拍照专用滤镜对象（避免与预览链竞争），
     * 使用与预览完全相同的参数和 landmarks，通过 SourceImage → FilterChain → SinkRawData 渲染后返回 Bitmap。
     */
    fun processPhoto(bitmap: Bitmap): Bitmap {
        if (!isInitialized) {
            Log.w(TAG, "processPhoto called before initialization, returning original bitmap")
            return bitmap
        }

        try {
            // 创建独立的拍照滤镜链（避免与预览线程竞争）
            val photoSource = GPUPixelSourceImage.CreateFromBitmap(bitmap)
            val photoBeautyFilter = GPUPixelFilter.Create(GPUPixelFilter.BEAUTY_FACE_FILTER)
            val photoLipstickFilter = GPUPixelFilter.Create(GPUPixelFilter.LIPSTICK_FILTER)
            val photoBlusherFilter = GPUPixelFilter.Create(GPUPixelFilter.BLUSHER_FILTER)
            val photoFaceReshapeFilter = GPUPixelFilter.Create(GPUPixelFilter.FACE_RESHAPE_FILTER)
            val photoExposureFilter = GPUPixelFilter.Create(GPUPixelFilter.EXPOSURE_FILTER)
            val photoContrastFilter = GPUPixelFilter.Create(GPUPixelFilter.CONTRAST_FILTER)
            val photoSaturationFilter = GPUPixelFilter.Create(GPUPixelFilter.SATURATION_FILTER)
            val photoWhiteBalanceFilter = GPUPixelFilter.Create(GPUPixelFilter.WHITE_BALANCE_FILTER)
            val photoSinkRawData = GPUPixelSinkRawData.Create()

            // 初始化调色参数为中性值（后续会被覆盖）
            photoExposureFilter?.SetProperty("exposure", 0f)
            photoContrastFilter?.SetProperty("contrast", 1f)
            photoSaturationFilter?.SetProperty("saturation", 1f)
            photoWhiteBalanceFilter?.SetProperty("temperature", 5000f)
            photoWhiteBalanceFilter?.SetProperty("tint", 0f)

            // 构建滤镜链：sourceImage → lipstick → blusher → beauty → faceReshape → exposure → contrast → saturation → whiteBalance → [styleFilter] → sinkRawData
            photoSource?.AddSink(photoLipstickFilter)
            photoLipstickFilter?.AddSink(photoBlusherFilter)
            photoBlusherFilter?.AddSink(photoBeautyFilter)
            photoBeautyFilter?.AddSink(photoFaceReshapeFilter)
            photoFaceReshapeFilter?.AddSink(photoExposureFilter)
            photoExposureFilter?.AddSink(photoContrastFilter)
            photoContrastFilter?.AddSink(photoSaturationFilter)
            photoSaturationFilter?.AddSink(photoWhiteBalanceFilter)

            // 风格特效
            val styleClassName = lastParams.styleFilterClassName
            val photoStyleFilter: GPUPixelFilter? = if (styleClassName != null) {
                val sf = GPUPixelFilter.Create(styleClassName)
                if (sf != null && sf.getNativeClassID() != 0L) {
                    // 设置风格滤镜默认参数（与预览一致）
                    when (styleClassName) {
                        "ToonFilter" -> {
                            sf.SetProperty("threshold", 0.2f)
                            sf.SetProperty("quantizationLevels", 10.0f)
                        }
                        "SmoothToonFilter" -> {
                            sf.SetProperty("blurRadius", 2.0f)
                            sf.SetProperty("threshold", 0.1f)
                            sf.SetProperty("quantizationLevels", 10.0f)
                        }
                        "SketchFilter" -> sf.SetProperty("edgeStrength", 1.0f)
                        "PosterizeFilter" -> sf.SetProperty("colorLevels", 4f)
                        "EmbossFilter" -> sf.SetProperty("intensity", 1.0f)
                        "CrosshatchFilter" -> {
                            sf.SetProperty("crossHatchSpacing", 0.03f)
                            sf.SetProperty("lineWidth", 0.003f)
                        }
                    }
                    photoWhiteBalanceFilter?.AddSink(sf)
                    sf.AddSink(photoSinkRawData)
                    sf
                } else {
                    photoWhiteBalanceFilter?.AddSink(photoSinkRawData)
                    null
                }
            } else {
                photoWhiteBalanceFilter?.AddSink(photoSinkRawData)
                null
            }

            // 同步当前美颜参数
            if (!lastParams.enabled) {
                photoBeautyFilter?.SetProperty("skin_smoothing", 0.0f)
                photoBeautyFilter?.SetProperty("whiteness", 0.0f)
                photoFaceReshapeFilter?.SetProperty("thin_face", 0.0f)
                photoFaceReshapeFilter?.SetProperty("big_eye", 0.0f)
                photoLipstickFilter?.SetProperty("blend_level", 0.0f)
                photoBlusherFilter?.SetProperty("blend_level", 0.0f)
            } else {
                photoBeautyFilter?.SetProperty("skin_smoothing", lastParams.smoothing)
                photoBeautyFilter?.SetProperty("whiteness", lastParams.whitening)
                photoFaceReshapeFilter?.SetProperty("thin_face", lastParams.slimFace)
                photoFaceReshapeFilter?.SetProperty("big_eye", lastParams.bigEyes)
                photoLipstickFilter?.SetProperty("blend_level", lastParams.lipColor)
                photoBlusherFilter?.SetProperty("blend_level", lastParams.blush)
            }
            photoExposureFilter?.SetProperty("exposure", lastParams.gpuExposure.coerceIn(-10f, 10f))
            photoContrastFilter?.SetProperty("contrast", lastParams.gpuContrast.coerceIn(0f, 4f))
            photoSaturationFilter?.SetProperty("saturation", lastParams.gpuSaturation.coerceIn(0f, 2f))
            photoWhiteBalanceFilter?.SetProperty("temperature", lastParams.gpuWhiteBalance.coerceIn(2000f, 10000f))
            photoWhiteBalanceFilter?.SetProperty("tint", 0f)

            // 同步最新的人脸 landmarks（从预览链路的 faceReshapeFilter 中读取当前值）
            // GPUPixel 的 SetProperty 内部会保存 landmarks，但 Java 层没有 getter。
            // 替代方案：在 onYuvFrame 中把最新的 landmarks 缓存到一个成员变量中。
            val cachedLandmarks = lastDetectedLandmarks
            if (cachedLandmarks != null && cachedLandmarks.isNotEmpty()) {
                photoFaceReshapeFilter?.SetProperty("face_landmark", cachedLandmarks)
                photoLipstickFilter?.SetProperty("face_landmark", cachedLandmarks)
                photoBlusherFilter?.SetProperty("face_landmark", cachedLandmarks)
            }

            // 执行渲染
            photoSource?.Render()

            // 读取输出
            val rgbaBuffer = photoSinkRawData?.GetRgbaBuffer()
            val outWidth = photoSinkRawData?.GetWidth() ?: bitmap.width
            val outHeight = photoSinkRawData?.GetHeight() ?: bitmap.height

            val resultBitmap = if (rgbaBuffer != null && rgbaBuffer.isNotEmpty()) {
                val result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
                val byteBuffer = java.nio.ByteBuffer.wrap(rgbaBuffer)
                byteBuffer.rewind()
                result.copyPixelsFromBuffer(byteBuffer)
                result
            } else {
                bitmap
            }

            // 清理资源
            photoStyleFilter?.Destroy()
            photoSource?.Destroy()
            photoLipstickFilter?.Destroy()
            photoBlusherFilter?.Destroy()
            photoBeautyFilter?.Destroy()
            photoFaceReshapeFilter?.Destroy()
            photoExposureFilter?.Destroy()
            photoContrastFilter?.Destroy()
            photoSaturationFilter?.Destroy()
            photoWhiteBalanceFilter?.Destroy()
            photoSinkRawData?.Destroy()

            return resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "processPhoto error", e)
            return bitmap
        }
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

        // 风格特效参数：仅在类名变化时执行滤镜链切换（互斥切换，按需动态创建/销毁）
        if (params.styleFilterClassName != activeStyleFilterClassName) {
            applyStyleFilter(params.styleFilterClassName)
        }

        Log.d(
            TAG,
            "applyParams: enabled=${params.enabled}, exposure=${params.gpuExposure}, " +
                "contrast=${params.gpuContrast}, saturation=${params.gpuSaturation}, " +
                "whiteBalance=${params.gpuWhiteBalance}, blush=${params.blush}, " +
                "styleFilter=${params.styleFilterClassName}"
        )
    }

    /**
     * 动态切换风格特效滤镜。
     *
     * 切换策略：
     * 1. 从滤镜链中断开 whiteBalanceFilter → sinkSurface（或 whiteBalanceFilter → 旧风格滤镜 → sinkSurface）
     * 2. 销毁旧风格滤镜
     * 3. 若新 className 非 null，创建并接入新风格滤镜：whiteBalanceFilter → newStyleFilter → sinkSurface
     * 4. 若新 className 为 null（NONE），直接接线 whiteBalanceFilter → sinkSurface
     *
     * **注意**：GPUPixel C++ 层 AddSink/RemoveSink 操作需在 GPUPixel 渲染线程上执行，
     * 此处假设调用方（updateFilters → applyParams）在合适线程中调用。
     */
    private fun applyStyleFilter(newClassName: String?) {
        val wb = whiteBalanceFilter ?: return
        val sink = sinkSurface ?: return

        // Step 1: 断开旧链路
        val oldStyle = activeStyleFilter
        if (oldStyle != null) {
            // 断开 whiteBalance → oldStyle → sink
            wb.RemoveSink(oldStyle)
            oldStyle.RemoveSink(sink)
        } else {
            // 当前是直连 whiteBalance → sink，断开直连
            wb.RemoveSink(sink)
        }

        // Step 2: 销毁旧滤镜
        oldStyle?.Destroy()
        activeStyleFilter = null

        // Step 3: 创建并接入新滤镜（或直连 sink）
        if (newClassName != null) {
            val newFilter = GPUPixelFilter.Create(newClassName)
            if (newFilter != null && newFilter.getNativeClassID() != 0L) {
                // 设置风格滤镜默认参数
                applyStyleFilterDefaults(newFilter, newClassName)
                // 接线：whiteBalance → newFilter → sink
                wb.AddSink(newFilter)
                newFilter.AddSink(sink)
                activeStyleFilter = newFilter
                Log.i(TAG, "Style filter switched to: $newClassName")
            } else {
                // 创建失败，回退到直连
                newFilter?.Destroy()
                wb.AddSink(sink)
                Log.w(TAG, "Style filter create failed: $newClassName, fallback to NONE")
                activeStyleFilterClassName = null
                return
            }
        } else {
            // NONE：直连
            wb.AddSink(sink)
            Log.i(TAG, "Style filter cleared (NONE)")
        }
        activeStyleFilterClassName = newClassName
    }

    /**
     * 为风格特效滤镜设置推荐默认参数（参考 beauty-engine/AGENTS.md 规范）。
     */
    private fun applyStyleFilterDefaults(filter: GPUPixelFilter, className: String) {
        when (className) {
            "ToonFilter" -> {
                filter.SetProperty("threshold", 0.2f)
                filter.SetProperty("quantizationLevels", 10.0f)
            }
            "SmoothToonFilter" -> {
                filter.SetProperty("blurRadius", 2.0f)
                filter.SetProperty("threshold", 0.1f)
                filter.SetProperty("quantizationLevels", 10.0f)
            }
            "SketchFilter" -> {
                filter.SetProperty("edgeStrength", 1.0f)
            }
            "PosterizeFilter" -> {
                filter.SetProperty("colorLevels", 4f)
            }
            "EmbossFilter" -> {
                filter.SetProperty("intensity", 1.0f)
            }
            "CrosshatchFilter" -> {
                filter.SetProperty("crossHatchSpacing", 0.03f)
                filter.SetProperty("lineWidth", 0.003f)
            }
        }
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

    override fun updateCheekContourPoints(
        leftCheekPoints: List<Pair<Float, Float>>,
        rightCheekPoints: List<Pair<Float, Float>>
    ) {
        // GPUPixel 使用自带 FaceDetector 的 landmarks，此处忽略 ML Kit 参数
    }

    override fun release() {
        // 先断开并销毁当前风格滤镜（若有）
        activeStyleFilter?.let { style ->
            whiteBalanceFilter?.RemoveSink(style)
            style.RemoveSink(sinkSurface)
            style.Destroy()
        }
        activeStyleFilter = null
        activeStyleFilterClassName = null
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

    // 像素旋转已下放到 Native 层 YUV→RGBA 转换时完成，此处不再保留 Kotlin 层旋转工具方法
}
