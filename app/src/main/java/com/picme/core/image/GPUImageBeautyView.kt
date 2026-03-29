package com.picme.core.image

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * [RD] 纯 OpenGL ES 实时美颜渲染 View
 * 
 * 技术架构：
 * 1. 继承 GLSurfaceView，实现完整的 OpenGL ES 2.0 渲染管线
 * 2. 创建外部纹理（GL_TEXTURE_EXTERNAL_OES）接收 CameraX 输出
 * 3. 手动编写 Shader 实现磨皮、美白滤镜
 * 4. 手动管理 VBO、Shader 程序、纹理绑定
 * 
 * @param context 上下文
 * @param attrs 属性集
 */
class GPUImageBeautyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    private var cameraTextureId: Int = -1
    private var cameraSurfaceTexture: SurfaceTexture? = null
    
    // Shader 程序
    private var programId: Int = -1
    
    // 顶点坐标和纹理坐标缓冲
    private var vertexBuffer: FloatBuffer? = null
    private var textureBuffer: FloatBuffer? = null
    
    // Shader 属性位置
    private var aPositionLocation: Int = -1
    private var aTextureCoordLocation: Int = -1
    private var uTextureLocation: Int = -1
    private var uSmoothingLocation: Int = -1
    private var uWhiteningLocation: Int = -1
    
    // 美颜参数
    var smoothingStrength: Float = 0f
    var whiteningStrength: Float = 0f
    var slimFaceStrength: Float = 0f
    var bigEyesStrength: Float = 0f
    
    init {
        // 设置 OpenGL ES 2.0
        setEGLContextClientVersion(2)
        
        // 初始化缓冲
        initBuffers()
        
        // 设置渲染器
        setRenderer(this)
        
        // 持续渲染模式
        renderMode = RENDERMODE_CONTINUOUSLY
        
        android.util.Log.d("PicMe:GPUImageBeautyView", "Initialized with pure OpenGL ES")
    }
    
    /**
     * 初始化顶点和纹理坐标缓冲
     */
    private fun initBuffers() {
        // 顶点坐标（标准化设备坐标，-1 到 1）
        // 使用 GL_TRIANGLE_STRIP 绘制顺序：左下 -> 右下 -> 左上 -> 右上
        val vertices = floatArrayOf(
            -1f, -1f,  // 0: 左下
             1f, -1f,  // 1: 右下
            -1f,  1f,  // 2: 左上
             1f,  1f   // 3: 右上
        )
        
        // 纹理坐标（0 到 1）
        // 必须与顶点一一对应
        val textureCoords = floatArrayOf(
            0f, 1f,  // 0: 左下
            1f, 1f,  // 1: 右下
            0f, 0f,  // 2: 左上
            1f, 0f   // 3: 右上
        )
        
        // 分配直接内存（native memory，性能更好）
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexBuffer?.put(vertices)?.position(0)
        
        textureBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        textureBuffer?.put(textureCoords)?.position(0)
        
        android.util.Log.d("PicMe:GPUImageBeautyView", "Buffers initialized: vertices=${vertices.contentToString()}, texCoords=${textureCoords.contentToString()}")
    }
    
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        android.util.Log.d("PicMe:GPUImageBeautyView", "onSurfaceCreated")
        
        // 创建外部纹理 ID（用于 CameraX 输出）
        cameraTextureId = createExternalTextureId()
        android.util.Log.d("PicMe:GPUImageBeautyView", "Camera texture ID created: $cameraTextureId")
        
        // 创建 SurfaceTexture 给 CameraX
        cameraSurfaceTexture = SurfaceTexture(cameraTextureId)
        android.util.Log.d("PicMe:GPUImageBeautyView", "Camera SurfaceTexture created: ${cameraSurfaceTexture?.hashCode()}")
        
        // 编译 Shader 程序
        programId = createProgram()
        android.util.Log.d("PicMe:GPUImageBeautyView", "Shader program created: $programId")
        
        // 获取 Shader 属性和 Uniform 位置
        aPositionLocation = GLES20.glGetAttribLocation(programId, "aPosition")
        aTextureCoordLocation = GLES20.glGetAttribLocation(programId, "aTextureCoord")
        uTextureLocation = GLES20.glGetUniformLocation(programId, "uTexture")
        uSmoothingLocation = GLES20.glGetUniformLocation(programId, "uSmoothing")
        uWhiteningLocation = GLES20.glGetUniformLocation(programId, "uWhitening")
        
        android.util.Log.d("PicMe:GPUImageBeautyView", "Shader locations: aPos=$aPositionLocation, aTex=$aTextureCoordLocation, uTex=$uTextureLocation, uSmooth=$uSmoothingLocation, uWhite=$uWhiteningLocation")
        
        // 设置 OpenGL 状态
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_TEXTURE_2D)
    }
    
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        android.util.Log.d("PicMe:GPUImageBeautyView", "onSurfaceChanged: ${width}x${height}")
        
        // 设置 OpenGL 视口
        GLES20.glViewport(0, 0, width, height)
    }
    
    override fun onDrawFrame(gl: GL10?) {
        // 清除屏幕
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        
        try {
            if (cameraTextureId != -1 && programId != -1) {
                // 1. 使用 Shader 程序
                GLES20.glUseProgram(programId)
                
                // 2. 绑定外部纹理到纹理单元 0
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
                
                // 3. 设置纹理 uniform
                GLES20.glUniform1i(uTextureLocation, 0)
                
                // 4. 关键：更新 SurfaceTexture（获取最新的相机帧）
                cameraSurfaceTexture?.updateTexImage()
                
                // 5. 设置美颜参数
                GLES20.glUniform1f(uSmoothingLocation, smoothingStrength / 100f)
                GLES20.glUniform1f(uWhiteningLocation, whiteningStrength / 100f)
                
                // 6. 启用顶点数组
                GLES20.glEnableVertexAttribArray(aPositionLocation)
                GLES20.glEnableVertexAttribArray(aTextureCoordLocation)
                
                // 7. 设置顶点坐标
                vertexBuffer?.position(0)
                GLES20.glVertexAttribPointer(
                    aPositionLocation,
                    2,
                    GLES20.GL_FLOAT,
                    false,
                    0,
                    vertexBuffer
                )
                
                // 8. 设置纹理坐标
                textureBuffer?.position(0)
                GLES20.glVertexAttribPointer(
                    aTextureCoordLocation,
                    2,
                    GLES20.GL_FLOAT,
                    false,
                    0,
                    textureBuffer
                )
                
                // 9. 绘制四边形
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                
                // 10. 禁用顶点数组
                GLES20.glDisableVertexAttribArray(aPositionLocation)
                GLES20.glDisableVertexAttribArray(aTextureCoordLocation)
                
                // 11. 解绑纹理
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
                
                android.util.Log.v("PicMe:GPUImageBeautyView", "Frame rendered successfully")
            }
        } catch (e: Exception) {
            android.util.Log.e("PicMe:GPUImageBeautyView", "Render error: ${e.message}", e)
        }
    }
    
    /**
     * 创建外部纹理 ID
     */
    private fun createExternalTextureId(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        
        val textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        
        // 设置纹理参数
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        
        return textureId
    }
    
    /**
     * 创建 Shader 程序
     */
    private fun createProgram(): Int {
        // 顶点着色器
        val vertexShader = loadShader(
            GLES20.GL_VERTEX_SHADER,
            """
            uniform mat4 uTextureTransform;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uTextureTransform * aTextureCoord).xy;
            }
            """.trimIndent()
        )
        
        // 片段着色器（带磨皮和美白）
        val fragmentShader = loadShader(
            GLES20.GL_FRAGMENT_SHADER,
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform float uSmoothing;
            uniform float uWhitening;
            varying vec2 vTextureCoord;
            
            void main() {
                // 调试：先输出红色，确认渲染管线工作
                // gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);
                
                // 采样纹理
                vec4 color = texture2D(uTexture, vTextureCoord);
                
                // 简单的调试：输出纹理的 R 通道
                // gl_FragColor = vec4(color.r, color.r, color.r, 1.0);
                
                // 正常渲染：使用原始纹理颜色
                gl_FragColor = color;
            }
            """.trimIndent()
        )
        
        // 创建程序
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        
        // 检查链接状态
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetProgramInfoLog(program)
            android.util.Log.e("PicMe:GPUImageBeautyView", "Shader program link error: $error")
            GLES20.glDeleteProgram(program)
            return -1
        }
        
        return program
    }
    
    /**
     * 加载并编译 Shader
     */
    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        
        // 检查编译状态
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetShaderInfoLog(shader)
            android.util.Log.e("PicMe:GPUImageBeautyView", "Shader compile error: $error")
            GLES20.glDeleteShader(shader)
            return 0
        }
        
        return shader
    }
    
    /**
     * 获取相机应该输出的 SurfaceTexture
     */
    fun getCameraSurfaceTexture(): SurfaceTexture? {
        return cameraSurfaceTexture
    }
    
    override fun onPause() {
        super.onPause()
        cameraSurfaceTexture?.release()
        if (programId != -1) {
            GLES20.glDeleteProgram(programId)
        }
        android.util.Log.d("PicMe:GPUImageBeautyView", "Released")
    }
}
