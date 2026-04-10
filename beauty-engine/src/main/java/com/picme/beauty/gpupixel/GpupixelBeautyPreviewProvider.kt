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
 * 经外部转换后的 RGBA 帧喂给 GPUPixel 滤镜链，渲染到内部 TextureView。
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
    private var surfaceAvailable = false

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
                sinkSurface?.SetSurface(Surface(surfaceTexture), width, height)
                Log.d(TAG, "Surface available: ${width}x${height}")
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                surfaceWidth = width
                surfaceHeight = height
                sinkSurface?.SetSurface(Surface(surfaceTexture), width, height)
                Log.d(TAG, "Surface size changed: ${width}x${height}")
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                surfaceAvailable = false
                sinkSurface?.ReleaseSurface()
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

        sourceRawData?.AddSink(lipstickFilter)
        lipstickFilter?.AddSink(beautyFilter)
        beautyFilter?.AddSink(faceReshapeFilter)
        faceReshapeFilter?.AddSink(sinkSurface)

        applyParams(lastParams)

        if (surfaceAvailable && textureView.isAvailable) {
            val st = textureView.surfaceTexture
            if (st != null) {
                sinkSurface?.SetSurface(Surface(st), surfaceWidth, surfaceHeight)
            }
        }

        isInitialized = true
        Log.i(TAG, "GpupixelBeautyPreviewProvider initialized")
    }

    override fun createPreviewSurface(): Surface {
        if (!isInitialized) initialize()
        val st = textureView.surfaceTexture
            ?: throw IllegalStateException("TextureView surface texture not available yet")
        return Surface(st)
    }

    /**
     * 处理 RGBA 帧数据，由外部（app 层）在 analyzer 线程调用。
     */
    fun onRgbaFrame(data: ByteArray, width: Int, height: Int) {
        if (!isInitialized) return

        try {
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
        sourceRawData = null
        lipstickFilter = null
        beautyFilter = null
        faceReshapeFilter = null
        sinkSurface = null
        faceDetector = null
        isInitialized = false
        Log.i(TAG, "GpupixelBeautyPreviewProvider released")
    }

    override fun isReady(): Boolean {
        return isInitialized && surfaceAvailable
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
