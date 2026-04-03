package com.picme.core.image

import android.graphics.SurfaceTexture
import android.util.Log
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
    
    @Volatile
    private var cameraInputWidth: Int = DEFAULT_WIDTH

    @Volatile
    private var cameraInputHeight: Int = DEFAULT_HEIGHT

    @Volatile
    private var isFillCenter: Boolean = true

    /** 渲染视图（TextureView 或 SurfaceView） */
    private var renderView: View? = null
    
    @Volatile
    private var frameAvailable: Boolean = false

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
        surfaceTexture = SurfaceTexture(textureId).apply {
            setOnFrameAvailableListener {
                frameAvailable = true
            }
        }
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
        if (!surface.isValid) {
            Log.w(TAG, "Ignore invalid render surface")
            return
        }

        Log.d(TAG, "Setting render surface: hash=${surface.hashCode()}")

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
        if (renderThread?.isAlive == true) {
            Log.d(TAG, "Render thread already running")
            return
        }
            
        isRendering = true
            
        renderThread = Thread {
            Log.d(TAG, "Render thread started")
                
            var frameCount = 0
            var framesReceived = 0
            var lastFrameTime = System.currentTimeMillis()

            try {
                while (isRendering && !Thread.interrupted()) {
                    if (!frameAvailable) {
                        if (!safeSleep(1)) {
                            break
                        }
                        continue
                    }
                    
                    try {
                        val ws = windowSurface
                        val context = eglContext
                        if (ws == null || context == null) {
                            if (!safeSleep(5)) {
                                break
                            }
                            continue
                        }

                        // 关键：先使 WindowSurface 的 EGL 上下文当前化
                        eglCore.makeCurrent(ws.getEglSurface(), context)

                        // 1. 更新 SurfaceTexture（从相机获取新帧）
                        surfaceTexture?.updateTexImage()
                        frameAvailable = false
                        framesReceived++

                        val currentTime = System.currentTimeMillis()
                        if (framesReceived == 1) {
                            Log.d(TAG, "First frame received, rendering started. textureId=$textureId")
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

                        // 3. 绑定外部纹理
                        android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0)
                        android.opengl.GLES20.glBindTexture(
                            android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                            textureId
                        )

                        // 4. 设置纹理变换矩阵
                        beautyRenderer.setTextureTransform(transformMatrix)

                        // 5. 根据输入输出比例设置 viewport，避免拉伸
                        val outputWidth = renderView?.width?.takeIf { size -> size > 0 } ?: DEFAULT_WIDTH
                        val outputHeight = renderView?.height?.takeIf { size -> size > 0 } ?: DEFAULT_HEIGHT
                        applyViewport(outputWidth, outputHeight)

                        // 6. 渲染与交换缓冲
                        beautyRenderer.onRender()
                        ws.swapBuffers()

                        frameCount++
                        if (frameCount % 30 == 0) {
                            Log.d(TAG, "Rendered $frameCount frames, textureId=$textureId")
                        }
                    } catch (e: IllegalStateException) {
                        // SurfaceTexture 未就绪，等待下一帧
                        frameAvailable = false
                        if (frameCount == 0 || frameCount % 60 == 0) {
                            Log.w(TAG, "SurfaceTexture not ready yet: ${e.message}")
                        }
                        if (!safeSleep(16)) {
                            break
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Render error at frame $frameCount: ${e.message}", e)
                        if (!safeSleep(16)) {
                            break
                        }
                    }
                }
            } finally {
                isRendering = false
                renderThread = null
                Log.d(TAG, "Render thread stopped after $frameCount frames")
            }
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

    fun setCameraInputBufferSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        cameraInputWidth = width
        cameraInputHeight = height
    }

    fun setScaleMode(isFillCenter: Boolean) {
        this.isFillCenter = isFillCenter
    }

    private fun applyViewport(outputWidth: Int, outputHeight: Int) {
        val safeOutputWidth = outputWidth.coerceAtLeast(1)
        val safeOutputHeight = outputHeight.coerceAtLeast(1)

        val rawSourceAspect = cameraInputWidth.toFloat() / cameraInputHeight.toFloat()
        val rotatedSourceAspect = cameraInputHeight.toFloat() / cameraInputWidth.toFloat()
        val outputAspect = safeOutputWidth.toFloat() / safeOutputHeight.toFloat()

        // SurfaceRequest.resolution 在竖屏下可能仍按传感器横向返回，
        // 这里选择更接近当前输出容器的比例，避免人物横向拉伸。
        val sourceAspect = if (
            kotlin.math.abs(rotatedSourceAspect - outputAspect) < kotlin.math.abs(rawSourceAspect - outputAspect)
        ) {
            rotatedSourceAspect
        } else {
            rawSourceAspect
        }

        val viewportWidth: Int
        val viewportHeight: Int

        if (isFillCenter) {
            if (sourceAspect > outputAspect) {
                viewportHeight = safeOutputHeight
                viewportWidth = (safeOutputHeight * sourceAspect).toInt().coerceAtLeast(1)
            } else {
                viewportWidth = safeOutputWidth
                viewportHeight = (safeOutputWidth / sourceAspect).toInt().coerceAtLeast(1)
            }
        } else {
            if (sourceAspect > outputAspect) {
                viewportWidth = safeOutputWidth
                viewportHeight = (safeOutputWidth / sourceAspect).toInt().coerceAtLeast(1)
            } else {
                viewportHeight = safeOutputHeight
                viewportWidth = (safeOutputHeight * sourceAspect).toInt().coerceAtLeast(1)
            }
        }

        val x = (safeOutputWidth - viewportWidth) / 2
        val y = (safeOutputHeight - viewportHeight) / 2
        android.opengl.GLES20.glViewport(x, y, viewportWidth, viewportHeight)
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
        frameAvailable = false
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
        surfaceTexture?.setOnFrameAvailableListener(null)
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
