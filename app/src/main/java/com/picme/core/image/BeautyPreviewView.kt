package com.picme.core.image

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout

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
    
    private val renderer: CameraPreviewRenderer = CameraPreviewRenderer()
    private val surfaceView: SurfaceView = SurfaceView(context)

    private var cameraSurface: Surface? = null
    private var displaySurface: Surface? = null
    private var isRendererInitialized = false
    private var cameraInputWidth: Int = 1280
    private var cameraInputHeight: Int = 720
    private var isFillCenter: Boolean = true

    /** 磨皮强度 */
    var smoothingStrength: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParams()
        }
    
    /** 美白强度 */
    var whiteningStrength: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParams()
        }
    
    /** 大眼强度 */
    var bigEyesStrength: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParams()
        }

    /** 瘦脸强度（-1.0 ~ 1.0） */
    var slimFaceStrength: Float = 0f
        set(value) {
            field = value.coerceIn(-1f, 1f)
            updateBeautyParams()
        }

    /** 唇色强度（0.0 ~ 1.0） */
    var lipColorStrength: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParams()
        }

    /** 唇色色号索引（0 ~ 11） */
    var lipColorIndex: Int = 0
        set(value) {
            field = value.coerceIn(0, 11)
            updateBeautyParams()
        }

    /** 腮红强度（0.0 ~ 1.0） */
    var blushStrength: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParams()
        }

    /** 腮红色系（0=粉色,1=橙色,2=梅子色） */
    var blushColorFamily: Int = 0
        set(value) {
            field = value.coerceIn(0, 2)
            updateBeautyParams()
        }

    /** 渲染模式 */
    var renderMode: Int = BeautyRenderer.MODE_BEAUTY
        set(value) {
            field = value
            renderer.setRenderMode(value)
        }
    
    init {
        surfaceView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        // 关键：设置 Z-order 确保 SurfaceView 正确参与 Surface 合成
        surfaceView.setZOrderMediaOverlay(true)
        addView(surfaceView)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val surface = holder.surface
                Log.d(TAG, "Display SurfaceView ready: ${surfaceView.width}x${surfaceView.height}, surface=$surface")
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

        Log.d(TAG, "BeautyPreviewView created with dedicated SurfaceView")
    }
    
    /** 预热链路：用于 CameraX 请求输入 Surface 前初始化 renderer */
    fun ensureOffscreenReady() {
        ensureRendererInitialized()
    }

    private fun ensureRendererInitialized() {
        if (isRendererInitialized) {
            return
        }

        renderer.init(surfaceView)
        renderer.setCameraInputBufferSize(cameraInputWidth, cameraInputHeight)
        renderer.setScaleMode(isFillCenter)
        isRendererInitialized = true
        updateBeautyParams()
        Log.d(TAG, "Renderer initialized")
    }

    private fun bindDisplaySurface(surface: Surface) {
        if (!isRendererInitialized || !surface.isValid) {
            return
        }
        
        if (displaySurface === surface) {
            return
        }

        displaySurface = surface
        renderer.setRenderSurface(surface)
    }
    
    fun getPerfStats(): CameraPreviewRenderer.PerfStats {
        return renderer.getPerfStats()
    }

    fun getSurfaceTexture(): SurfaceTexture? {
        return renderer.getSurfaceTexture()
    }
    
    fun getSurfaceForCamera(): Surface? {
        ensureRendererInitialized()

        renderer.setCameraInputBufferSize(cameraInputWidth, cameraInputHeight)
        renderer.getSurfaceTexture()?.setDefaultBufferSize(cameraInputWidth, cameraInputHeight)

        if (cameraSurface == null) {
            cameraSurface = renderer.getSurfaceForCamera()
            Log.d(
                TAG,
                "Created camera input surface: ${cameraSurface?.hashCode()}, buffer=${cameraInputWidth}x$cameraInputHeight"
            )
        }
        
        return cameraSurface
    }
    
    private fun updateBeautyParams() {
        if (!isRendererInitialized) {
            return
        }

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
        if (width <= 0 || height <= 0) {
            return
        }

        cameraInputWidth = width
        cameraInputHeight = height
        if (isRendererInitialized) {
            renderer.setCameraInputBufferSize(width, height)
            renderer.getSurfaceTexture()?.setDefaultBufferSize(width, height)
        }
    }

    fun setScaleMode(isFillCenter: Boolean) {
        this.isFillCenter = isFillCenter
        if (!isRendererInitialized) {
            return
        }
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
        if (!isRendererInitialized) {
            return
        }

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
        if (!isRendererInitialized) {
            return
        }
        renderer.updateLipMaskPoints(outerPoints, innerPoints)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(TAG, "BeautyPreviewView attached to window")

        // 关键：在视图附加到窗口后，强制检查 SurfaceView 的 Surface 状态
        // 某些设备上 SurfaceView 的 surfaceCreated 回调可能不会立即触发
        post {
            val holder = surfaceView.holder
            val surface = holder.surface
            Log.d(TAG, "Post check: surface=$surface, isValid=${surface?.isValid}")
            if (surface != null && surface.isValid) {
                Log.d(TAG, "Surface already valid, binding display surface")
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
        // 注意：不要在这里释放渲染器，因为视图可能会被重新附加
        // SurfaceView 的 Surface 会自动释放
        displaySurface = null
    }
    
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        Log.d(TAG, "Visibility changed: $visibility")
    }
}
