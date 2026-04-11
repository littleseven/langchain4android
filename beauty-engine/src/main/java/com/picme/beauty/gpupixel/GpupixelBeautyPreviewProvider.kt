package com.picme.beauty.gpupixel

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
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
 * 数据流：CameraX ImageAnalysis (YUV) → RGBA 转换 → GPUPixel 滤镜链 → TextureView 显示
 *
 * 关键时序说明：
 * - TextureView 加入 window hierarchy 后才会触发 [TextureView.SurfaceTextureListener]
 * - [initialize] 可能在 surface 可用之前或之后调用
 * - 通过 [pendingDisplaySurface] 缓存解决初始化竞态问题：无论哪个先就绪，都能正确绑定
 *
 * 当前为实验性集成，支持磨皮、美白、瘦脸、大眼、唇色。
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
    private var faceReshapeFilter: GPUPixelFilter? = null
    private var faceDetector: FaceDetector? = null
    private var isInitialized = false
    private var lastParams: BeautyParams = BeautyParams.EMPTY
    private var isFillCenter: Boolean = true
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

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
        faceReshapeFilter = GPUPixelFilter.Create(GPUPixelFilter.FACE_RESHAPE_FILTER)
        sinkSurface = GPUPixelSinkSurface.Create()
        faceDetector = FaceDetector.Create()

        // 构建滤镜链：rawData → lipstick → beauty → faceReshape → sinkSurface
        sourceRawData?.AddSink(lipstickFilter)
        lipstickFilter?.AddSink(beautyFilter)
        beautyFilter?.AddSink(faceReshapeFilter)
        faceReshapeFilter?.AddSink(sinkSurface)

        applyParams(lastParams)

        // 应用 scaleMode（在 sinkSurface 创建后立即设置）
        val fillMode = if (isFillCenter) {
            GPUPixelSinkSurface.PRESERVE_ASPECT_RATIO_AND_FILL
        } else {
            GPUPixelSinkSurface.PRESERVE_ASPECT_RATIO
        }
        sinkSurface?.SetFillMode(fillMode)

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
     * 旋转由 [GPUPixelSourceRawData.SetRotation] 委托给 GPUPixel 内部处理，
     * 避免在上层分配额外 ByteArray 并执行像素级旋转（节省 CPU 和内存开销）。
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
            // 把旋转委托给 GPUPixel，无需上层手动旋转帧数据
            // 注意：GPUPixel SetRotation 接受的是 RotationMode 枚举序号，不是角度值
            // RotationMode: NoRotation=0, RotateLeft=1, RotateRight=2, FlipVertical=3,
            //               FlipHorizontal=4, RotateRightFlipVertical=5, RotateRightFlipHorizontal=6, Rotate180=7
            // CameraX rotationDegrees → RotationMode 映射：
            //   0   → NoRotation  (0)
            //   90  → RotateRight (2)  — 顺时针 90°
            //   180 → Rotate180   (7)
            //   270 → RotateLeft  (1)  — 逆时针 90°（即顺时针 270°）
            val rotationMode = when (rotationDegrees) {
                90 -> 2   // RotateRight
                180 -> 7  // Rotate180
                270 -> 1  // RotateLeft
                else -> 0 // NoRotation
            }
            sourceRawData?.SetRotation(rotationMode)

            val landmarks = faceDetector?.detect(
                data,
                width,
                height,
                width * 4,
                FaceDetector.GPUPIXEL_MODE_FMT_VIDEO,
                FaceDetector.GPUPIXEL_FRAME_TYPE_RGBA
            )

            if (landmarks != null && landmarks.isNotEmpty()) {
                faceReshapeFilter?.SetProperty("face_landmark", landmarks)
                lipstickFilter?.SetProperty("face_landmark", landmarks)
            }

            sourceRawData?.ProcessData(
                data,
                width,
                height,
                width * 4,
                GPUPixelSourceRawData.FRAME_TYPE_RGBA
            )
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        }
    }

    override fun updateFilters(params: BeautyParams) {
        lastParams = params
        applyParams(params)
    }

    private fun applyParams(params: BeautyParams) {
        if (!params.enabled) {
            beautyFilter?.SetProperty("skin_smoothing", 0.0f)
            beautyFilter?.SetProperty("whiteness", 0.0f)
            faceReshapeFilter?.SetProperty("thin_face", 0.0f)
            faceReshapeFilter?.SetProperty("big_eye", 0.0f)
            lipstickFilter?.SetProperty("blend_level", 0.0f)
            return
        }

        beautyFilter?.SetProperty("skin_smoothing", params.smoothing)
        beautyFilter?.SetProperty("whiteness", params.whitening)
        faceReshapeFilter?.SetProperty("thin_face", params.slimFace)
        faceReshapeFilter?.SetProperty("big_eye", params.bigEyes)
        lipstickFilter?.SetProperty("blend_level", params.lipColor)
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
        beautyFilter?.Destroy()
        faceReshapeFilter?.Destroy()
        sinkSurface?.Destroy()
        faceDetector?.destroy()
        pendingDisplaySurface?.release()
        sourceRawData = null
        lipstickFilter = null
        beautyFilter = null
        faceReshapeFilter = null
        sinkSurface = null
        faceDetector = null
        pendingDisplaySurface = null
        isInitialized = false
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
        val fillMode = if (isFillCenter) {
            GPUPixelSinkSurface.PRESERVE_ASPECT_RATIO_AND_FILL
        } else {
            GPUPixelSinkSurface.PRESERVE_ASPECT_RATIO
        }
        sinkSurface?.SetFillMode(fillMode)
    }

    override fun getPerfStats(): BeautyPerfStats = BeautyPerfStats.EMPTY
}
