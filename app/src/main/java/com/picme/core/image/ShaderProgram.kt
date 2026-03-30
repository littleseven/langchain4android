package com.picme.core.image

import android.opengl.GLES20
import android.util.Log

/**
 * R 计划 - Shader 程序管理类
 * 
 * 功能：
 * 1. 编译顶点和片段着色器
 * 2. 链接 Shader 程序
 * 3. 获取 Uniform 和 Attribute 位置
 * 4. 管理 Shader 程序生命周期
 * 
 * @author RD Team
 * @version 1.0 (R 计划)
 */
class ShaderProgram {
    companion object {
        private const val TAG = "PicMe:ShaderProgram"
    }
    
    /** Shader 程序 ID */
    private var programId: Int = -1
    
    /** Uniform 位置缓存 */
    private val uniformLocations = mutableMapOf<String, Int>()
    
    /** Attribute 位置缓存 */
    private val attribLocations = mutableMapOf<String, Int>()
    
    /** 是否已编译成功 */
    var isCompiled: Boolean = false
        private set
    
    /**
     * 编译 Shader 程序
     * 
     * @param vertexSource 顶点着色器源码
     * @param fragmentSource 片段着色器源码
     * @return 是否成功
     */
    fun compile(vertexSource: String, fragmentSource: String): Boolean {
        Log.d(TAG, "Compiling shader program")
        
        try {
            // 1. 编译顶点着色器
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            if (vertexShader == 0) {
                Log.e(TAG, "Failed to compile vertex shader")
                return false
            }
            
            // 2. 编译片段着色器
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            if (fragmentShader == 0) {
                Log.e(TAG, "Failed to compile fragment shader")
                GLES20.glDeleteShader(vertexShader)
                return false
            }
            
            // 3. 链接程序
            programId = GLES20.glCreateProgram()
            GLES20.glAttachShader(programId, vertexShader)
            GLES20.glAttachShader(programId, fragmentShader)
            GLES20.glLinkProgram(programId)
            
            // 4. 检查链接状态
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                val error = GLES20.glGetProgramInfoLog(programId)
                Log.e(TAG, "Failed to link program: $error")
                GLES20.glDeleteProgram(programId)
                GLES20.glDeleteShader(vertexShader)
                GLES20.glDeleteShader(fragmentShader)
                programId = -1
                return false
            }
            
            // 5. 清理着色器（链接后可以删除）
            GLES20.glDetachShader(programId, vertexShader)
            GLES20.glDetachShader(programId, fragmentShader)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            
            isCompiled = true
            Log.d(TAG, "Shader program compiled successfully: $programId")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Shader compile error: ${e.message}", e)
            if (programId != -1) {
                GLES20.glDeleteProgram(programId)
                programId = -1
            }
            isCompiled = false
            return false
        }
    }
    
    /**
     * 编译单个着色器
     * 
     * @param type 着色器类型（GL_VERTEX_SHADER 或 GL_FRAGMENT_SHADER）
     * @param source 源码
     * @return 着色器 ID，失败返回 0
     */
    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        
        // 检查编译状态
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetShaderInfoLog(shader)
            Log.e(TAG, "Shader compile error: $error")
            GLES20.glDeleteShader(shader)
            return 0
        }
        
        Log.v(TAG, "Shader compiled: type=$type, id=$shader")
        return shader
    }
    
    /**
     * 使用 Shader 程序
     */
    fun use() {
        if (isCompiled && programId != -1) {
            GLES20.glUseProgram(programId)
        }
    }
    
    /**
     * 获取 Uniform 位置
     * 
     * @param name Uniform 名称
     * @return 位置，-1 表示未找到
     */
    fun getUniformLocation(name: String): Int {
        // 检查缓存
        uniformLocations[name]?.let {
            return it
        }
        
        // 获取位置并缓存
        val location = GLES20.glGetUniformLocation(programId, name)
        uniformLocations[name] = location
        
        if (location == -1) {
            Log.w(TAG, "Uniform not found: $name")
        }
        
        return location
    }
    
    /**
     * 获取 Attribute 位置
     * 
     * @param name Attribute 名称
     * @return 位置，-1 表示未找到
     */
    fun getAttribLocation(name: String): Int {
        // 检查缓存
        attribLocations[name]?.let {
            return it
        }
        
        // 获取位置并缓存
        val location = GLES20.glGetAttribLocation(programId, name)
        attribLocations[name] = location
        
        if (location == -1) {
            Log.w(TAG, "Attribute not found: $name")
        }
        
        return location
    }
    
    /**
     * 设置 float Uniform
     */
    fun setFloat(name: String, value: Float) {
        val location = getUniformLocation(name)
        if (location != -1) {
            GLES20.glUniform1f(location, value)
        }
    }
    
    /**
     * 设置 int Uniform
     */
    fun setInt(name: String, value: Int) {
        val location = getUniformLocation(name)
        if (location != -1) {
            GLES20.glUniform1i(location, value)
        }
    }
    
    /**
     * 设置 vec2 Uniform
     */
    fun setVec2(name: String, x: Float, y: Float) {
        val location = getUniformLocation(name)
        if (location != -1) {
            GLES20.glUniform2f(location, x, y)
        }
    }
    
    /**
     * 设置 mat4 Uniform
     */
    fun setMat4(name: String, value: FloatArray) {
        val location = getUniformLocation(name)
        if (location != -1) {
            GLES20.glUniformMatrix4fv(location, 1, false, value, 0)
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        if (programId != -1) {
            GLES20.glDeleteProgram(programId)
            programId = -1
            uniformLocations.clear()
            attribLocations.clear()
            isCompiled = false
            Log.d(TAG, "Shader program released")
        }
    }
    
    /**
     * 获取程序 ID
     */
    fun getProgramId(): Int {
        return programId
    }
}
