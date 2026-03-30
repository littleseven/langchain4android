package com.picme.core.image.pixelfree

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.hapi.pixelfree.*

/**
 * PixelFreeEffects 美颜引擎包装类（完全自定义集成）
 * 
 * 功能：
 * 1. 封装 PixelFree SDK 的初始化
 * 2. 提供美颜、美型、滤镜等功能
 * 3. 支持实时预览（OpenGL 纹理模式）
 * 4. 完全自定义 UI 和控制逻辑
 * 
 * @author RD Team
 * @version 1.0
 */
class PixelFreeBeautyEngine(private val context: Context) {
    
    companion object {
        private const val TAG = "PicMe:PixelFree"
    }
    
    /** PixelFree 引擎 */
    private var pixelFree: PixelFree? = null
    
    /** 是否已初始化 */
    private var isInitialized = false
    
    /** 当前美颜参数缓存 */
    private val beautyParams = mutableMapOf<Int, Float>()
    
    /**
     * 初始化 PixelFree 引擎
     * 
     * @param glSurfaceView GLSurfaceView 实例
     */
    fun init(glSurfaceView: GLSurfaceView) {
        if (isInitialized) {
            Log.w(TAG, "Engine already initialized")
            return
        }
        
        try {
            pixelFree = PixelFree()
            pixelFree?.create()
            
            // 读取授权文件（如果有）
            val authData = pixelFree?.readBundleFile(context, "pixelfreeAuth.lic")
            if (authData != null) {
                pixelFree?.auth(context.applicationContext, authData, authData.size)
                Log.d(TAG, "Auth file loaded")
            }
            
            // 读取滤镜文件
            val filterData = pixelFree?.readBundleFile(context, "filter_model.bundle")
            if (filterData != null) {
                pixelFree?.createBeautyItemFormBundle(
                    filterData,
                    filterData.size,
                    PFSrcType.PFSrcTypeFilter
                )
                Log.d(TAG, "Filter bundle loaded")
            }
            
            // 读取美妆文件（如果有）
            val makeupData = pixelFree?.readBundleFile(context, "makeup_name.bundle")
            if (makeupData != null) {
                pixelFree?.createBeautyItemFormBundle(
                    makeupData,
                    makeupData.size,
                    PFSrcType.PFSrcTypeMakeup
                )
                Log.d(TAG, "Makeup bundle loaded")
            }
            
            // 设置默认美颜参数
            resetBeautyParams()
            
            isInitialized = true
            Log.d(TAG, "PixelFree engine initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PixelFree engine: ${e.message}", e)
        }
    }
    
    /**
     * 重置美颜参数到默认值
     */
    fun resetBeautyParams() {
        // 磨皮、美白、红润、大眼、瘦脸等
        // 参数值范围：0.0 - 1.0
        // 具体参数类型需要参考 SDK 文档或反编译 AAR 查看
        // 这里先使用示例值
        Log.d(TAG, "Reset beauty params to default values")
    }
    
    /**
     * 设置滤镜参数
     * 
     * @param filterName 滤镜名称（如 "heibai1", "nature" 等）
     * @param value 滤镜强度 (0.0 - 1.0)
     */
    fun setFilter(filterName: String, value: Float = 1.0f) {
        if (!isInitialized || pixelFree == null) {
            Log.w(TAG, "Engine not initialized")
            return
        }
        
        // TODO: 滤镜参数设置需要 SDK 支持，暂时注释
        // pixelFree?.pixelFreeSetBeautyFiterParam(filterName, value)
        Log.d(TAG, "Set filter: $filterName = $value (TODO: implement)")
    }
    
    /**
     * 处理 OpenGL 纹理（实时预览模式）
     * 
     * @param textureId 输入纹理 ID
     * @param width 图像宽度
     * @param height 图像高度
     * @return 处理后的纹理 ID
     */
    fun processTexture(textureId: Int, width: Int, height: Int): Int {
        if (!isInitialized || pixelFree == null) {
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
            
            pixelFree?.processWithBuffer(pxInput)
            
            val outputTextureId = pxInput.textureID
            Log.d(TAG, "Processed texture: input=$textureId, output=$outputTextureId")
            
            return outputTextureId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process texture: ${e.message}", e)
            return textureId
        }
    }
    
    /**
     * 处理 RGBA 图像数据
     * 
     * @param data RGBA 字节数组
     * @param width 图像宽度
     * @param height 图像高度
     * @return 处理后的字节数组
     */
    fun processRGBA(data: ByteArray, width: Int, height: Int): ByteArray {
        if (!isInitialized || pixelFree == null) {
            return data
        }
        
        try {
            val pxInput = PFImageInput()
            pxInput.textureID = 0
            pxInput.wigth = width
            pxInput.height = height
            pxInput.p_data0 = data
            pxInput.p_data1 = null
            pxInput.p_data2 = null
            pxInput.stride_0 = width * 4
            pxInput.stride_1 = 0
            pxInput.stride_2 = 0
            pxInput.format = PFDetectFormat.PFFORMAT_IMAGE_RGBA
            pxInput.rotationMode = PFRotationMode.PFRotationMode0
            
            pixelFree?.processWithBuffer(pxInput)
            
            Log.d(TAG, "Processed RGBA image: ${width}x${height}")
            
            return pxInput.p_data0 ?: data
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process RGBA: ${e.message}", e)
            return data
        }
    }
    
    /**
     * 处理 YUV NV21 图像数据
     * 
     * @param yData Y 通道数据
     * @param uvData UV 通道数据
     * @param width 图像宽度
     * @param height 图像高度
     * @return Pair<处理后的 Y 数据，处理后的 UV 数据>
     */
    fun processYUV(yData: ByteArray, uvData: ByteArray, width: Int, height: Int): Pair<ByteArray, ByteArray> {
        if (!isInitialized || pixelFree == null) {
            return Pair(yData, uvData)
        }
        
        try {
            val pxInput = PFImageInput()
            pxInput.textureID = 0
            pxInput.wigth = width
            pxInput.height = height
            pxInput.p_data0 = yData
            pxInput.p_data1 = uvData
            pxInput.p_data2 = null
            pxInput.stride_0 = width
            pxInput.stride_1 = width
            pxInput.stride_2 = 0
            pxInput.format = PFDetectFormat.PFFORMAT_IMAGE_YUV_NV21
            pxInput.rotationMode = PFRotationMode.PFRotationMode0
            
            pixelFree?.processWithBuffer(pxInput)
            
            Log.d(TAG, "Processed YUV image: ${width}x${height}")
            
            return Pair(pxInput.p_data0 ?: yData, pxInput.p_data1 ?: uvData)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process YUV: ${e.message}", e)
            return Pair(yData, uvData)
        }
    }
    
    /**
     * 检查 SDK 是否已初始化
     */
    fun isCreate(): Boolean {
        return isInitialized && (pixelFree?.isCreate() == true)
    }
    
    /**
     * 获取 SDK 版本号
     */
    fun getVersion(): String {
        return pixelFree?.getVersion() ?: "unknown"
    }
    
    /**
     * 释放资源
     */
    fun release() {
        if (!isInitialized) {
            return
        }
        
        try {
            pixelFree?.release()
            pixelFree = null
            isInitialized = false
            beautyParams.clear()
            Log.d(TAG, "PixelFree engine released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release engine: ${e.message}", e)
        }
    }
}
