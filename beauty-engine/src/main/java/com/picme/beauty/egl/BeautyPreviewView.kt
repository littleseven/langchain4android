package com.picme.beauty.egl

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.picme.beauty.api.BeautyPerfStats
import com.picme.beauty.api.IBeautyPipeline
import com.picme.beauty.engine.gpupixel.GPUPixelPipelineAdapter

/**
 * R Plan 预览视图：
 * - CameraX 输入 Surface 与显示 Surface 明确分离
 * - 显示层使用 SurfaceView，确保 Surface 创建可靠
 */
class BeautyPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        private const val TAG = "PicMe:BeautyPreviewView"
    }

    private val renderer: CameraPreviewRenderer = CameraPreviewRenderer(context)
    private var pipeline: IBeautyPipeline? = null // 统一管线接口
    private val surfaceView: SurfaceView = SurfaceView(context)

    private var cameraSurface: Surface? = null
    private var displaySurface: Surface? = null
    private var isRendererInitialized = false
    private var cameraInputWidth: Int = 1280
    private var cameraInputHeight: Int = 720
    private var isFillCenter: Boolean = true

    var smoothingStrength: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParamsInternal()
        }

    var whiteningStrength: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParamsInternal()
        }

    var bigEyesStrength: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParamsInternal()
        }

    var slimFaceStrength: Float = 0f
        set(value) {
            field = value.coerceIn(-1f, 1f)
            updateBeautyParamsInternal()
        }

    var lipColorStrength: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParamsInternal()
        }

    var lipColorIndex: Int = 0
        set(value) {
            field = value.coerceIn(0, 11)
            updateBeautyParamsInternal()
        }

    var blushStrength: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParamsInternal()
        }

    var blushColorFamily: Int = 0
        set(value) {
            field = value.coerceIn(0, 2)
            updateBeautyParamsInternal()
        }

    var colorMatrix: FloatArray? = null
        set(value) {
            field = value
            if (isRendererInitialized) renderer.updateColorMatrix(value)
        }

    var renderMode: Int = BeautyRenderer.MODE_BEAUTY
        set(value) {
            field = value
            renderer.setRenderMode(value)
        }

    /**
     * 切换底层渲染引擎
     * @param useGPUPixel true 使用 GPUPixel, false 使用自研 R Plan
     */
    fun switchEngine(useGPUPixel: Boolean) {
        renderer.switchEngine(useGPUPixel)
        if (useGPUPixel && pipeline !is GPUPixelPipelineAdapter) {
            pipeline?.release()
            pipeline = GPUPixelPipelineAdapter(context)
            Log.d(TAG, "Switched to GPUPixel Engine")
        } else if (!useGPUPixel && pipeline is GPUPixelPipelineAdapter) {
            pipeline?.release()
            pipeline = null
            Log.d(TAG, "Switched to R Plan Engine")
        }
    }

    /**
     * 设置 Shader 调试模式
     * @param mode 0=正常, 1=显示 Skin Mask, 2=显示 Warp 偏移
     */
    fun setDebugMode(mode: Int) {
        renderer.setDebugMode(mode)
    }

    init {
        surfaceView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        surfaceView.setZOrderMediaOverlay(true)
        addView(surfaceView)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val surface = holder.surface
                Log.d(TAG, "Display SurfaceView ready: ${surfaceView.width}x${surfaceView.height}")
                ensureRendererInitialized()
                bindDisplaySurface(surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "Display SurfaceView changed: ${width}x${height}")
                bindDisplaySurface(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "Display SurfaceView destroyed")
                displaySurface = null
            }
        })

        Log.d(TAG, "BeautyPreviewView created")
    }

    fun ensureOffscreenReady() {
        ensureRendererInitialized()
    }

    private fun ensureRendererInitialized() {
        if (isRendererInitialized) return
        renderer.init(surfaceView)
        renderer.setCameraInputBufferSize(cameraInputWidth, cameraInputHeight)
        renderer.setScaleMode(isFillCenter)
        isRendererInitialized = true
        updateBeautyParamsInternal()
        renderer.updateColorMatrix(colorMatrix)
        Log.d(TAG, "Renderer initialized")
    }

    private fun bindDisplaySurface(surface: Surface) {
        if (!isRendererInitialized || !surface.isValid) return
        if (displaySurface === surface) return
        displaySurface = surface
        renderer.setRenderSurface(surface)
    }

    fun getPerfStats(): BeautyPerfStats = renderer.getPerfStats()

    fun getSurfaceTexture(): SurfaceTexture? = renderer.getSurfaceTexture()

    fun getSurfaceForCamera(): Surface? {
        ensureRendererInitialized()
        renderer.setCameraInputBufferSize(cameraInputWidth, cameraInputHeight)
        renderer.getSurfaceTexture()?.setDefaultBufferSize(cameraInputWidth, cameraInputHeight)
        if (cameraSurface == null) {
            cameraSurface = renderer.getSurfaceForCamera()
            Log.d(TAG, "Created camera input surface: ${cameraSurface?.hashCode()}")
        }
        return cameraSurface
    }

    private fun updateBeautyParamsInternal() {
        if (!isRendererInitialized) return
        renderer.updateBeautyParams(
            smoothing = smoothingStrength,
            whitening = whiteningStrength,
            bigEyes = bigEyesStrength,
            slimFace = slimFaceStrength,
            lipColor = lipColorStrength,
            lipColorIndex = lipColorIndex,
            blush = blushStrength,
            blushColorFamily = blushColorFamily
        )
    }

    fun setCameraInputBufferSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        cameraInputWidth = width
        cameraInputHeight = height
        if (isRendererInitialized) {
            renderer.setCameraInputBufferSize(width, height)
            renderer.getSurfaceTexture()?.setDefaultBufferSize(width, height)
        }
    }

    fun setScaleMode(isFillCenter: Boolean) {
        this.isFillCenter = isFillCenter
        if (!isRendererInitialized) return
        renderer.setScaleMode(isFillCenter)
    }

    fun updateFaceWarpParams(
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
        if (!isRendererInitialized) return
        renderer.updateFaceWarpParams(
            faceCenterX = faceCenterX,
            faceCenterY = faceCenterY,
            leftEyeX = leftEyeX,
            leftEyeY = leftEyeY,
            rightEyeX = rightEyeX,
            rightEyeY = rightEyeY,
            mouthCenterX = mouthCenterX,
            mouthCenterY = mouthCenterY,
            mouthLeftX = mouthLeftX,
            mouthLeftY = mouthLeftY,
            mouthRightX = mouthRightX,
            mouthRightY = mouthRightY,
            upperLipCenterX = upperLipCenterX,
            upperLipCenterY = upperLipCenterY,
            lowerLipCenterX = lowerLipCenterX,
            lowerLipCenterY = lowerLipCenterY,
            faceRadius = faceRadius,
            hasFace = hasFace
        )
    }

    fun updateLipMaskPoints(
        outerPoints: List<Pair<Float, Float>>,
        innerPoints: List<Pair<Float, Float>>
    ) {
        if (!isRendererInitialized) return
        renderer.updateLipMaskPoints(outerPoints, innerPoints)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(TAG, "BeautyPreviewView attached to window")
        post {
            val holder = surfaceView.holder
            val surface = holder.surface
            if (surface != null && surface.isValid) {
                ensureRendererInitialized()
                bindDisplaySurface(surface)
            } else {
                Log.w(TAG, "Surface not yet valid, waiting for surfaceCreated callback")
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(TAG, "BeautyPreviewView detached from window")
        displaySurface = null
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        Log.d(TAG, "Visibility changed: $visibility")
    }
}

