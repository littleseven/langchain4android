package com.picme.core.image.pixelfree

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import com.hapi.pixelfree.PFBeautyFilterType
import com.hapi.pixelfree.PFDetectFormat
import com.hapi.pixelfree.PFImageInput
import com.hapi.pixelfree.PFRotationMode
import com.hapi.pixelfree.PFSrcType
import com.hapi.pixelfree.PixelFree
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * PixelFree 美颜 GLSurfaceView
 * 
 * 功能：
 * 1. 集成 PixelFree SDK 到 GLSurfaceView
 * 2. 支持实时相机预览美颜处理
 * 3. 支持自定义美颜参数
 * 
 * @author RD Team
 * @version 1.0
 */
class PixelFreeGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {
    
    companion object {
        private const val TAG = "PicMe:PixelFreeGL"
    }
    
    /** PixelFree 引擎 */
    private val pixelFree = PixelFree()
    
    /** 是否已初始化 */
    private var isInitialized = false
    
    /** 相机纹理 ID */
    private var cameraTextureId = 0
    
    /** 当前纹理 ID */
    private var currentTextureId = 0
    
    /** CameraX 输入 Surface */
    private var cameraInputSurface: Surface? = null

    /** 图像宽度 */
    private var imageWidth = 0
    
    /** 图像高度 */
    private var imageHeight = 0
    
    /** 渲染器回调 */
    private var renderCallback: ((Int, Int, Int) -> Int)? = null
    
    init {
        // 设置 OpenGL ES 版本为 2.0
        setEGLContextClientVersion(2)
        
        // 设置渲染器
        setRenderer(this)
        
        // 设置渲染模式为手动渲染（按需渲染）
        renderMode = RENDERMODE_WHEN_DIRTY
        
        Log.d(TAG, "PixelFreeGLSurfaceView created")
    }
    
    /**
     * 初始化 PixelFree SDK
     */
    fun initPixelFree() {
        if (isInitialized) {
            Log.w(TAG, "PixelFree already initialized")
            return
        }
        
        try {
            // 在 GL 上下文中初始化
            pixelFree.create()
            
            // 加载授权文件
            val authData = pixelFree.readBundleFile(context, "pixelfreeAuth.lic")
            if (authData != null) {
                pixelFree.auth(context.applicationContext, authData, authData.size)
                Log.d(TAG, "Auth file loaded")
            }
            
            // 加载滤镜资源
            val filterData = pixelFree.readBundleFile(context, "filter_model.bundle")
            if (filterData != null) {
                pixelFree.createBeautyItemFormBundle(
                    filterData,
                    filterData.size,
                    PFSrcType.PFSrcTypeFilter
                )
                Log.d(TAG, "Filter bundle loaded")
            }
            
            // 加载美妆资源（如果有）
            val makeupData = pixelFree.readBundleFile(context, "makeup_name.bundle")
            if (makeupData != null) {
                pixelFree.createBeautyItemFormBundle(
                    makeupData,
                    makeupData.size,
                    PFSrcType.PFSrcTypeMakeup
                )
                Log.d(TAG, "Makeup bundle loaded")
            }
            
            isInitialized = true
            Log.d(TAG, "PixelFree initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PixelFree: ${e.message}", e)
        }
    }
    
    /**
     * 设置美颜参数
     * 
     * @param type 美颜类型（使用 PFBeautyFilterType 枚举值）
     * @param value 参数值 (0.0 - 1.0)
     */
    fun setBeautyParam(type: PFBeautyFilterType, value: Float) {
        if (!isInitialized) {
            Log.w(TAG, "PixelFree not initialized")
            return
        }
        
        try {
            pixelFree.pixelFreeSetBeautyFiterParam(type, value)
            Log.d(TAG, "Set beauty param: $type = $value")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set beauty param: ${e.message}", e)
        }
    }
    
    /**
     * 设置滤镜
     * 
     * @param filterName 滤镜名称
     * @param value 滤镜强度 (0.0 - 1.0)
     */
    fun setFilter(filterName: String, value: Float = 1.0f) {
        if (!isInitialized) {
            Log.w(TAG, "PixelFree not initialized")
            return
        }
        
        // TODO: 滤镜参数设置需要 SDK 支持，暂时注释
        // pixelFree?.pixelFreeSetBeautyFiterParam(filterName, value)
        Log.d(TAG, "Set filter: $filterName = $value (TODO: implement)")
    }
    
    /**
     * 设置渲染回调
     * 用于在渲染前处理纹理
     */
    fun setRenderCallback(callback: (Int, Int, Int) -> Int) {
        renderCallback = callback
    }
    
    /**
     * 获取当前显示的纹理 ID
     */
    fun getCurrentTextureId(): Int {
        return currentTextureId
    }
    
    /**
     * 请求渲染
     */
    override fun requestRender() {
        if (renderMode == RENDERMODE_WHEN_DIRTY) {
            super.requestRender()
        }
    }
    
    // ========== GLSurfaceView.Renderer ==========
    
    override fun onSurfaceCreated(gl: GL10?, eglConfig: EGLConfig?) {
        Log.d(TAG, "GLSurface created")
        // 在 GL 上下文中初始化 PixelFree
        initPixelFree()
    }
    
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.d(TAG, "GLSurface changed: ${width}x${height}")
        imageWidth = width
        imageHeight = height
        
        // 设置视口
        GLES20.glViewport(0, 0, width, height)
    }
    
    override fun onDrawFrame(gl: GL10?) {
        if (!isInitialized) {
            return
        }
        
        try {
            // 如果有相机纹理 ID，进行美颜处理
            if (cameraTextureId != 0) {
                // 使用 PixelFree SDK 处理纹理
                val processedTextureId = processTexture(cameraTextureId, imageWidth, imageHeight)
                currentTextureId = processedTextureId
                
                // TODO: 渲染到屏幕（需要自己实现渲染逻辑）
                // 简单的方式：直接显示处理后的纹理
                // 复杂的方式：自己绘制四边形并应用纹理
                
                Log.d(TAG, "Draw frame: cameraTexture=$cameraTextureId, processed=$processedTextureId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to draw frame: ${e.message}", e)
        }
    }
    
    /**
     * 设置磨皮强度
     */
    fun setSmoothingStrength(value: Float) {
        setBeautyParam(PFBeautyFilterType.PFBeautyFilterTypeFaceBlurStrength, value.coerceIn(0f, 1f))
    }
    
    /**
     * 设置美白强度
     */
    fun setWhiteningStrength(value: Float) {
        setBeautyParam(PFBeautyFilterType.PFBeautyFilterTypeFaceM_newWhitenStrength, value.coerceIn(0f, 1f))
    }
    
    /**
     * 设置瘦脸强度
     */
    fun setSlimFaceStrength(value: Float) {
        setBeautyParam(PFBeautyFilterType.PFBeautyFilterTypeFace_thinning, value.coerceIn(0f, 1f))
    }
    
    /**
     * 设置大眼强度
     */
    fun setBigEyesStrength(value: Float) {
        setBeautyParam(PFBeautyFilterType.PFBeautyFilterTypeFace_EyeStrength, value.coerceIn(0f, 1f))
    }
    
    // ========== 公开方法 ==========
    
    /**
     * 设置相机纹理 ID
     */
    fun setCameraTextureId(textureId: Int, width: Int, height: Int) {
        cameraTextureId = textureId
        imageWidth = width
        imageHeight = height
        
        // 请求渲染
        requestRender()
    }
    
    /**
     * 处理纹理（使用 PixelFree SDK）
     * 
     * @param textureId 输入纹理 ID
     * @param width 图像宽度
     * @param height 图像高度
     * @return 处理后的纹理 ID
     */
    fun processTexture(textureId: Int, width: Int, height: Int): Int {
        if (!isInitialized) {
            return textureId
        }
        
        try {
            val pxInput = PFImageInput()
            pxInput.textureID = textureId
            pxInput.wigth = width
            pxInput.height = height
            pxInput.p_data0 = null
            pxInput.p_data1 = null
            pxInput.p_data2 = null
            pxInput.stride_0 = 0
            pxInput.stride_1 = 0
            pxInput.stride_2 = 0
            pxInput.format = PFDetectFormat.PFFORMAT_IMAGE_TEXTURE
            pxInput.rotationMode = PFRotationMode.PFRotationMode0
            
            pixelFree.processWithBuffer(pxInput)
            
            val outputTextureId = pxInput.textureID
            Log.d(TAG, "Processed texture: input=$textureId, output=$outputTextureId, size=${width}x${height}")
            
            return outputTextureId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process texture: ${e.message}", e)
            return textureId
        }
    }
    
    /**
     * 获取 SurfaceTexture（用于 CameraX 绑定）
     * 注意：PixelFreeEffects 需要自己管理纹理输入
     */
    fun getSurfaceTexture(): android.graphics.SurfaceTexture? {
        // PixelFreeEffects SDK 不直接使用 SurfaceTexture
        // 而是通过 processTexture 方法处理纹理
        // 这里返回 null，需要在 CameraScreen 中使用其他方式传递纹理
        Log.w(TAG, "getSurfaceTexture() called - PixelFree uses texture processing instead")
        return null
    }
    
    /**
     * 获取 Surface（用于 CameraX 绑定）
     * 注意：PixelFreeEffects 不直接使用 Surface
     */
    fun getSurfaceForCamera(): Surface? {
        val holderSurface = holder.surface
        if (holderSurface == null || !holderSurface.isValid) {
            Log.w(TAG, "getSurfaceForCamera() called before holder surface is ready")
            return null
        }

        val cachedSurface = cameraInputSurface
        if (cachedSurface != null && cachedSurface.isValid) {
            return cachedSurface
        }

        cameraInputSurface = holderSurface
        Log.d(TAG, "Camera input surface ready for PixelFree preview")
        return cameraInputSurface
    }
    
    /**
     * 检查 SDK 是否已初始化
     */
    fun isCreate(): Boolean {
        return isInitialized && pixelFree.isCreate()
    }
    
    /**
     * 释放资源
     */
    fun release() {
        cameraInputSurface = null

        if (!isInitialized) {
            return
        }
        
        try {
            pixelFree.release()
            isInitialized = false
            cameraTextureId = 0
            currentTextureId = 0
            Log.d(TAG, "PixelFree released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release: ${e.message}", e)
        }
    }
}
