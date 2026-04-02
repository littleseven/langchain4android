package com.picme.core.image

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import java.nio.FloatBuffer

/**
 * R 计划 - OpenGL ES 渲染器基类
 * 
 * 功能：
 * 1. 管理顶点缓冲和纹理缓冲
 * 2. 管理 Shader 程序
 * 3. 提供渲染接口
 * 4. 处理 OpenGL ES 状态
 * 
 * @author RD Team
 * @version 1.0 (R 计划)
 */
abstract class GLRenderer {
    companion object {
        private const val TAG = "PicMe:GLRenderer"
    }
    
    /** 顶点坐标缓冲 */
    protected var vertexBuffer: FloatBuffer? = null
    
    /** 纹理坐标缓冲 */
    protected var textureBuffer: FloatBuffer? = null
    
    /** Shader 程序管理器 */
    protected val shaderProgram = ShaderProgram()
    
    /** 纹理变换矩阵 */
    protected val textureMatrix = FloatArray(16)
    
    /** Shader 属性位置 */
    protected var aPositionLocation: Int = -1
        protected set
    
    protected var aTextureCoordLocation: Int = -1
        protected set
    
    protected var uTextureLocation: Int = -1
        protected set
    
    protected var uTextureTransformLocation: Int = -1
        protected set
    
    /** 是否已初始化 */
    var isInitialized: Boolean = false
        protected set
    
    /**
     * 初始化渲染器
     * 
     * 在 onSurfaceCreated 中调用
     */
    open fun onInit() {
        Log.d(TAG, "Initializing GLRenderer")
        
        // 1. 初始化缓冲
        initBuffers()
        
        // 2. 初始化矩阵
        Matrix.setIdentityM(textureMatrix, 0)
        
        // 3. 编译 Shader
        if (!onCompileShader()) {
            Log.e(TAG, "Failed to compile shader")
            isInitialized = false
            return
        }
        
        // 4. 获取 Shader 位置
        aPositionLocation = shaderProgram.getAttribLocation("aPosition")
        aTextureCoordLocation = shaderProgram.getAttribLocation("aTextureCoord")
        uTextureLocation = shaderProgram.getUniformLocation("uTexture")
        uTextureTransformLocation = shaderProgram.getUniformLocation("uTextureTransform")
        
        isInitialized = true
        Log.d(TAG, "GLRenderer initialized")
    }
    
    /**
     * 初始化顶点和纹理缓冲
     * 
     * 子类可以重写此方法自定义缓冲
     */
    protected open fun initBuffers() {
        // 顶点坐标（标准化设备坐标，-1 到 1）
        // 使用 GL_TRIANGLE_STRIP 绘制顺序：左下 -> 右下 -> 左上 -> 右上
        val vertices = floatArrayOf(
            -1f, -1f,  // 0: 左下
             1f, -1f,  // 1: 右下
            -1f,  1f,  // 2: 左上
             1f,  1f   // 3: 右上
        )
        
        // 纹理坐标（0 到 1）
        val textureCoords = floatArrayOf(
            0f, 0f,  // 0: 左下
            1f, 0f,  // 1: 右下
            0f, 1f,  // 2: 左上
            1f, 1f   // 3: 右上
        )
        
        // 分配直接内存
        vertexBuffer = java.nio.ByteBuffer.allocateDirect(vertices.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexBuffer?.put(vertices)?.position(0)
        
        textureBuffer = java.nio.ByteBuffer.allocateDirect(textureCoords.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
        textureBuffer?.put(textureCoords)?.position(0)
        
        Log.d(TAG, "Buffers initialized")
    }
    
    /**
     * 编译 Shader
     * 
     * 子类必须实现此方法以提供 Shader 源码
     * 
     * @return 是否成功
     */
    protected abstract fun onCompileShader(): Boolean
    
    /**
     * 渲染前准备
     * 
     * 子类可以重写此方法设置 Uniform 等
     */
    protected open fun onBeforeRender() {
        // 使用 Shader 程序
        shaderProgram.use()
        
        // 设置纹理变换矩阵
        shaderProgram.setMat4("uTextureTransform", textureMatrix)
    }
    
    /**
     * 渲染
     * 
     * 在 onDrawFrame 中调用
     */
    open fun onRender() {
        if (!isInitialized) {
            Log.w(TAG, "Renderer not initialized")
            return
        }
        
        // 1. 渲染前准备
        onBeforeRender()
        
        // 2. 启用顶点数组
        GLES20.glEnableVertexAttribArray(aPositionLocation)
        GLES20.glEnableVertexAttribArray(aTextureCoordLocation)
        
        // 3. 设置顶点坐标
        vertexBuffer?.let { vb ->
            vb.position(0)
            GLES20.glVertexAttribPointer(
                aPositionLocation,
                2,  // 每个顶点 2 个分量 (x, y)
                GLES20.GL_FLOAT,
                false,
                0,
                vb
            )
        }
        
        // 4. 设置纹理坐标
        textureBuffer?.let { tb ->
            tb.position(0)
            GLES20.glVertexAttribPointer(
                aTextureCoordLocation,
                2,  // 每个纹理坐标 2 个分量 (u, v)
                GLES20.GL_FLOAT,
                false,
                0,
                tb
            )
        }
        
        // 5. 绘制四边形
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        // 6. 禁用顶点数组
        GLES20.glDisableVertexAttribArray(aPositionLocation)
        GLES20.glDisableVertexAttribArray(aTextureCoordLocation)
        
        // 7. 渲染后处理
        onAfterRender()
    }
    
    /**
     * 渲染后处理
     * 
     * 子类可以重写此方法清理状态
     */
    protected open fun onAfterRender() {
        // 默认空实现
    }
    
    /**
     * 释放资源
     */
    open fun release() {
        Log.d(TAG, "Releasing GLRenderer")
        
        shaderProgram.release()
        
        vertexBuffer = null
        textureBuffer = null
        
        isInitialized = false
    }
    
    /**
     * 设置纹理变换矩阵
     * 
     * @param matrix 4x4 矩阵
     */
    fun setTextureTransform(matrix: FloatArray) {
        if (matrix.size == 16) {
            System.arraycopy(matrix, 0, textureMatrix, 0, 16)
        }
    }
}
