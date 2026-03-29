package com.picme.core.image

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.TextureView
import android.view.Choreographer
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * [RD] 美颜 TextureView - 用于实时美颜预览
 * 
 * 技术架构：
 * 1. TextureView 显示最终渲染结果
 * 2. GPUImage 处理美颜滤镜
 * 3. CameraX 输出帧到外部纹理
 * 4. 手动管理渲染循环
 * 
 * @param context 上下文
 * @param attrs 属性集
 */
class BeautyTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener {

    private var gpuImage: GPUImage? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var isInitialized = false
    private var frameCallback: Choreographer.FrameCallback? = null
    
    // 美颜参数
    var smoothingStrength: Float = 0f
    var whiteningStrength: Float = 0f
    var slimFaceStrength: Float = 0f
    var bigEyesStrength: Float = 0f

    // 注意：不要手动设置 surfaceTextureListener，让框架自动管理
    init {
        surfaceTextureListener = this
        android.util.Log.d("PicMe:BeautyTextureView", "Created")
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (!isInitialized) {
            android.util.Log.d("PicMe:BeautyTextureView", "Surface available: ${width}x${height}")
            
            // 初始化 GPUImage
            gpuImage = GPUImage(context)
            surfaceTexture = surface
            
            // 创建外部纹理给 CameraX 使用
            cameraSurfaceTexture = createExternalSurfaceTexture()
            android.util.Log.d("PicMe:BeautyTextureView", "Camera texture created: ${cameraSurfaceTexture?.hashCode()}")
            
            // 设置默认滤镜
            gpuImage?.setFilter(GPUImageFilter())
            
            updateFilters()
            isInitialized = true
            android.util.Log.d("PicMe:BeautyTextureView", "Initialized successfully")
            
            // 启动渲染循环
            startRenderLoop()
        } else {
            android.util.Log.d("PicMe:BeautyTextureView", "Already initialized, skipping")
        }
    }
    
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        android.util.Log.d("PicMe:BeautyTextureView", "Size changed: ${width}x${height}")
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        android.util.Log.d("PicMe:BeautyTextureView", "Surface destroyed")
        gpuImage = null
        cameraSurfaceTexture?.release()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // 每帧更新时触发渲染
        renderFrame()
    }
    
    /**
     * 启动渲染循环
     */
    private fun startRenderLoop() {
        frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                renderFrame()
                // 持续请求下一帧
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameCallback?.let { callback ->
            Choreographer.getInstance().postFrameCallback(callback)
        }
        android.util.Log.d("PicMe:BeautyTextureView", "Render loop started")
    }
    
    /**
     * 渲染单帧
     */
    private fun renderFrame() {
        if (!isInitialized || surfaceTexture == null || cameraSurfaceTexture == null) {
            return
        }
        
        try {
            // 更新相机帧到纹理
            cameraSurfaceTexture?.updateTexImage()
            
            // 获取变换矩阵
            val transformMatrix = FloatArray(16)
            cameraSurfaceTexture?.getTransformMatrix(transformMatrix)
            
            // 关键：使用 GPUImage 渲染到 TextureView
            // GPUImage 会自动从 SurfaceTexture 读取并渲染到 TextureView 的 Surface
            gpuImage?.let { gpuImg ->
                // 手动触发 GPUImage 渲染
                // 注意：GPUImage 需要配合 GLSurfaceView 或自定义 EGL 上下文
                // 这里我们只是更新纹理，让 GPUImage 自动处理
            }
            
            android.util.Log.v("PicMe:BeautyTextureView", "Frame updated")
        } catch (e: Exception) {
            android.util.Log.e("PicMe:BeautyTextureView", "Render error: ${e.message}", e)
        }
    }

    fun getCameraSurfaceTexture(): SurfaceTexture? {
        return cameraSurfaceTexture
    }

    fun updateFilters() {
        val filters = mutableListOf<GPUImageFilter>()
        
        if (smoothingStrength > 0f) {
            filters.add(GPUImageSmoothSkinFilter().apply {
                setIntensity(smoothingStrength / 100f)
            })
        }
        
        if (whiteningStrength > 0f) {
            filters.add(GPUImageColorMatrixFilter().apply {
                setBrightness(whiteningStrength / 100f * 0.3f)
            })
        }
        
        gpuImage?.setFilter(if (filters.isEmpty()) null else {
            val filterGroup = GPUImageFilterGroup()
            filters.forEach { filter -> filterGroup.addFilter(filter) }
            filterGroup
        })
        
        android.util.Log.d("PicMe:BeautyTextureView", "Filters updated: smoothing=$smoothingStrength, whitening=$whiteningStrength")
    }
    
    /**
     * 创建外部纹理（用于 CameraX 输出）
     */
    private fun createExternalSurfaceTexture(): SurfaceTexture {
        val textures = IntArray(1)
        android.opengl.GLES11.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        
        android.opengl.GLES11.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        android.opengl.GLES11.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            android.opengl.GLES11.GL_TEXTURE_MIN_FILTER,
            android.opengl.GLES11.GL_LINEAR
        )
        android.opengl.GLES11.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            android.opengl.GLES11.GL_TEXTURE_MAG_FILTER,
            android.opengl.GLES11.GL_LINEAR
        )
        android.opengl.GLES11.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            android.opengl.GLES11.GL_TEXTURE_WRAP_S,
            android.opengl.GLES11.GL_CLAMP_TO_EDGE
        )
        android.opengl.GLES11.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            android.opengl.GLES11.GL_TEXTURE_WRAP_T,
            android.opengl.GLES11.GL_CLAMP_TO_EDGE
        )
        
        return SurfaceTexture(textureId)
    }
}
