package com.picme.core.image

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.SurfaceHolder
import android.view.View

/**
 * R 计划 - 相机预览渲染器
 * 
 * 功能：
 * 1. 管理 EGL 上下文和渲染线程
 * 2. 绑定 SurfaceTexture（来自 CameraX）
 * 3. 使用 BeautyRenderer 渲染美颜效果
 * 4. 输出到 TextureView 或 SurfaceView
 * 
 * @author RD Team
 * @version 1.0 (R 计划)
 */
class CameraPreviewRenderer {
    companion object {
        private const val TAG = "PicMe:CameraPreview"
        
        /** 默认宽度 */
        const val DEFAULT_WIDTH = 1280
        
        /** 默认高度 */
        const val DEFAULT_HEIGHT = 720
    }
    
    /** EGL 核心管理类 */
    private val eglCore = EGLCore()
    
    /** EGL 上下文 */
    private var eglContext: android.opengl.EGLContext? = null
    
    /** Window Surface（用于显示） */
    private var windowSurface: WindowSurface? = null
    
    /** 美颜渲染器 */
    private val beautyRenderer = BeautyRenderer()
    
    /** 渲染线程 */
    private var renderThread: Thread? = null
    
    /** 是否正在渲染 */
    var isRendering = false
        private set
    
    /** SurfaceTexture（接收相机帧） */
    private var surfaceTexture: SurfaceTexture? = null
    
    /** OpenGL 纹理 ID */
    private var textureId: Int = -1
    
    /** 渲染视图（TextureView 或 SurfaceView） */
    private var renderView: View? = null
    
    /** 表面回调（SurfaceView 用） */
    private var surfaceCallback: SurfaceHolder.Callback? = null
    
    /** 纹理可用监听器 */
    interface OnTextureAvailableListener {
        fun onTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int)
        fun onTextureDestroyed()
    }
    
    private var textureListener: OnTextureAvailableListener? = null
    
    /**
     * 设置纹理监听器
     */
    fun setOnTextureAvailableListener(listener: OnTextureAvailableListener?) {
        this.textureListener = listener
    }
    
    /**
     * 初始化渲染器
     * 
     * @param view 渲染视图（TextureView）
     */
    fun init(view: View) {
        Log.d(TAG, "Initializing CameraPreviewRenderer")
        
        this.renderView = view
        
        // 1. 初始化 EGL
        if (!eglCore.init()) {
            throw RuntimeException("Failed to initialize EGL")
        }
        Log.d(TAG, "EGL initialized")
        
        // 2. 创建渲染上下文
        eglContext = eglCore.createContext()
        Log.d(TAG, "EGL context created: ${eglContext?.hashCode()}")
        
        // 3. 使 EGL 上下文当前化（关键！必须在创建 GL 纹理之前）
        val pbufferSurface = eglCore.createSurface(null, 1, 1)
        if (!eglCore.makeCurrent(pbufferSurface, eglContext!!)) {
            throw RuntimeException("Failed to make EGL context current before texture creation")
        }
        Log.d(TAG, "EGL context made current before creating texture")

        // 4. 创建外部纹理
        createExternalTexture()

        // 5. 创建 SurfaceTexture 绑定到外部纹理（关键修复！）
        // 注意：不使用 TextureView 的 surfaceTexture，而是创建新的 SurfaceTexture
        // 这个 SurfaceTexture 会接收相机帧并更新到我们的外部纹理
        surfaceTexture = SurfaceTexture(textureId)
        Log.d(TAG, "Created SurfaceTexture bound to external texture: ${surfaceTexture.hashCode()}, textureId=$textureId")
        
        // 6. 初始化 BeautyRenderer（离屏渲染）
        beautyRenderer.onInit()
        Log.d(TAG, "BeautyRenderer initialized: ${beautyRenderer.isInitialized}")
        
        // 释放主线程 EGL 绑定，避免与渲染线程争用同一 Context
        eglCore.clearCurrent()

        // 通知监听器
        textureListener?.onTextureAvailable(surfaceTexture!!, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        
        Log.d(TAG, "CameraPreviewRenderer fully initialized")
    }
    
    /**
     * 创建外部纹理
     */
    private fun createExternalTexture() {
        val textures = intArrayOf(0)
        android.opengl.GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        
        if (textureId == 0) {
            throw RuntimeException("Failed to create external texture, textureId=0")
        }

        android.opengl.GLES20.glBindTexture(
            android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            textureId
        )
        
        // 设置纹理参数
        android.opengl.GLES20.glTexParameteri(
            android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            android.opengl.GLES20.GL_TEXTURE_MIN_FILTER,
            android.opengl.GLES20.GL_LINEAR
        )
        android.opengl.GLES20.glTexParameteri(
            android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            android.opengl.GLES20.GL_TEXTURE_MAG_FILTER,
            android.opengl.GLES20.GL_LINEAR
        )
        android.opengl.GLES20.glTexParameteri(
            android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            android.opengl.GLES20.GL_TEXTURE_WRAP_S,
            android.opengl.GLES20.GL_CLAMP_TO_EDGE
        )
        android.opengl.GLES20.glTexParameteri(
            android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            android.opengl.GLES20.GL_TEXTURE_WRAP_T,
            android.opengl.GLES20.GL_CLAMP_TO_EDGE
        )
        
        Log.d(TAG, "External texture created: $textureId")
    }
    
    /**
     * 设置渲染 Surface
     * 
     * @param surface 输出 Surface
     */
    fun setRenderSurface(surface: android.view.Surface) {
        Log.d(TAG, "Setting render surface")
            
        // 释放旧的 Surface
        windowSurface?.release()
            
        // 创建新的 WindowSurface
        windowSurface = WindowSurface(surface, eglCore).apply {
            create()
        }
        
        // 启动渲染线程（由渲染线程独占 EGL 上下文）
        startRendering()
    }
        
    /**
     * 启动渲染线程
     */
    private fun startRendering() {
        if (isRendering) {
            Log.d(TAG, "Render thread already running")
            return
        }
            
        isRendering = true
            
        renderThread = Thread {
            Log.d(TAG, "Render thread started")
                
            var frameCount = 0
            var consecutiveErrors = 0
            var framesReceived = 0
            var lastFrameTime = 0L
            
            while (isRendering && !Thread.interrupted()) {
                try {
                    // 关键：先使 WindowSurface 的 EGL 上下文当前化
                    windowSurface?.let { ws ->
                        eglCore.makeCurrent(ws.getEglSurface(), eglContext!!)
                    }
                    
                    // 1. 更新 SurfaceTexture（从相机获取新帧）
                    surfaceTexture?.updateTexImage()
                    framesReceived++
                    consecutiveErrors = 0  // 成功，重置错误计数
                    
                    val currentTime = System.currentTimeMillis()
                    if (framesReceived == 1) {
                        Log.d(TAG, "=========================================")
                        Log.d(TAG, "🎉 First frame received! Starting render loop.")
                        Log.d(TAG, "Total frames: $framesReceived, textureId=$textureId")
                        Log.d(TAG, "=========================================")
                    }
                    
                    // 统计帧率
                    if (framesReceived % 30 == 0) {
                        val elapsed = currentTime - lastFrameTime
                        val fps = if (elapsed > 0) 30000 / elapsed else 0
                        Log.d(TAG, "Received $framesReceived frames in ${elapsed}ms (~${fps}fps)")
                        lastFrameTime = currentTime
                    }
                    
                    // 2. 获取变换矩阵
                    val transformMatrix = FloatArray(16)
                    surfaceTexture?.getTransformMatrix(transformMatrix)
                    
                    // 3. 绑定外部纹理（注意：textureId 已经在 init 时绑定到 SurfaceTexture）
                    android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0)
                    android.opengl.GLES20.glBindTexture(
                        android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                        textureId
                    )
                    
                    // 4. 设置纹理变换矩阵
                    beautyRenderer.setTextureTransform(transformMatrix)
                    
                    // 关键：每帧刷新 viewport，避免部分设备默认 viewport 为 0 导致黑屏
                    val outputWidth = renderView?.width?.takeIf { size -> size > 0 } ?: DEFAULT_WIDTH
                    val outputHeight = renderView?.height?.takeIf { size -> size > 0 } ?: DEFAULT_HEIGHT
                    android.opengl.GLES20.glViewport(0, 0, outputWidth, outputHeight)

                    // 5. 渲染
                    beautyRenderer.onRender()
                    
                    // 6. 交换缓冲区
                    windowSurface?.swapBuffers()
                    
                    frameCount++
                    if (frameCount % 30 == 0) {
                        Log.d(TAG, "Rendered $frameCount frames, textureId=$textureId")
                    }
                    
                    if (!safeSleep(16)) {
                        break
                    } // ~60fps

                } catch (e: IllegalStateException) {
                    // SurfaceTexture 未就绪，等待相机帧
                    consecutiveErrors++
                    if (consecutiveErrors <= 10) {
                        Log.v(TAG, "Waiting for camera frame (attempt $consecutiveErrors)")
                    } else if (consecutiveErrors == 11) {
                        Log.w(TAG, "Still waiting for camera frame after 1 second...")
                    }
                    
                    if (consecutiveErrors > 60) {
                        Log.e(TAG, "No camera frames received after 6 seconds, stopping render")
                        break
                    }
                    
                    if (!safeSleep(100)) {
                        break
                    }  // 等待相机帧

                } catch (e: Exception) {
                    Log.e(TAG, "Render error at frame $frameCount: ${e.message}", e)
                    consecutiveErrors++
                }
            }
            
            Log.d(TAG, "Render thread stopped after $frameCount frames")
        }.apply {
            name = "CameraPreviewRender"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }
    
    private fun safeSleep(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.i(TAG, "Render thread interrupted while sleeping")
            false
        }
    }

    /**
     * 更新美颜参数
     * 
     * @param smoothing 磨皮强度 (0.0 - 1.0)
     * @param whitening 美白强度 (0.0 - 1.0)
     * @param bigEyes 大眼强度 (0.0 - 1.0)
     * @param slimFace 瘦脸强度 (-1.0 - 1.0)
     */
    fun updateBeautyParams(
        smoothing: Float,
        whitening: Float,
        bigEyes: Float = 0f,
        slimFace: Float = 0f
    ) {
        beautyRenderer.updateBeautyParams(
            smoothing = smoothing,
            whitening = whitening,
            bigEyes = bigEyes,
            slimFace = slimFace
        )
    }
    
    fun updateFaceWarpParams(
        faceCenterX: Float,
        faceCenterY: Float,
        leftEyeX: Float,
        leftEyeY: Float,
        rightEyeX: Float,
        rightEyeY: Float,
        faceRadius: Float,
        hasFace: Boolean
    ) {
        beautyRenderer.updateFaceWarpParams(
            faceCenterX = faceCenterX,
            faceCenterY = faceCenterY,
            leftEyeX = leftEyeX,
            leftEyeY = leftEyeY,
            rightEyeX = rightEyeX,
            rightEyeY = rightEyeY,
            faceRadius = faceRadius,
            hasFace = hasFace
        )
    }

    /**
     * 设置渲染模式
     * 
     * @param mode BeautyRenderer.MODE_*
     */
    fun setRenderMode(mode: Int) {
        beautyRenderer.setRenderMode(mode)
    }
    
    /**
     * 获取 SurfaceTexture（用于 CameraX 绑定）
     */
    fun getSurfaceTexture(): SurfaceTexture? {
        return surfaceTexture
    }
    
    /**
     * 获取用于 CameraX 的 Surface
     * 
     * @return Surface 供 CameraX 输出
     */
    fun getSurfaceForCamera(): android.view.Surface? {
        return surfaceTexture?.let { android.view.Surface(it) }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        Log.d(TAG, "Releasing CameraPreviewRenderer")
        
        // 停止渲染线程
        isRendering = false
        renderThread?.interrupt()
        renderThread = null
        
        // 释放 Surface
        windowSurface?.release()
        windowSurface = null
        
        // 删除纹理
        if (textureId != -1) {
            val textures = intArrayOf(textureId)
            android.opengl.GLES20.glDeleteTextures(1, textures, 0)
            textureId = -1
        }
        
        // 释放 SurfaceTexture
        surfaceTexture?.release()
        surfaceTexture = null
        
        // 释放渲染器
        beautyRenderer.release()
        
        // 释放 EGL
        eglCore.release()
        
        textureListener?.onTextureDestroyed()
        
        Log.d(TAG, "Released")
    }
}
