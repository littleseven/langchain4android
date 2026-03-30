package com.picme.core.image

import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

/**
 * R 计划 - Window Surface 封装类
 * 
 * 功能：
 * 1. 封装 EGLSurface，用于渲染到 Android Surface
 * 2. 提供双缓冲交换机制
 * 3. 支持绑定为当前渲染目标
 * 
 * 使用场景：
 * - TextureView 的 Surface（推荐，支持变换和动画）
 * - SurfaceView 的 Surface（性能更好，但灵活性较低）
 * 
 * @param surface Android Surface 对象
 * @param eglCore EGL 核心管理类
 * @author RD Team
 * @version 1.0 (R 计划)
 */
class WindowSurface(
    private val surface: Surface,
    private val eglCore: EGLCore
) {
    companion object {
        private const val TAG = "PicMe:WindowSurface"
    }
    
    /** EGL Surface 对象 */
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    
    /** 是否已释放 */
    private var isReleased = false
    
    /**
     * 创建 EGL Surface
     * 
     * 必须在创建对应的 Android Surface 之后调用
     */
    fun create() {
        if (isReleased) {
            throw IllegalStateException("WindowSurface already released")
        }
        
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "EGL surface already created, releasing old one")
            release()
        }
        
        try {
            eglSurface = eglCore.createSurface(surface)
            Log.d(TAG, "WindowSurface created: ${eglSurface.hashCode()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WindowSurface: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * 将此 Surface 设置为当前渲染目标
     * 
     * @param context OpenGL ES 上下文
     * @return 是否成功
     */
    fun makeCurrent(context: EGLContext): Boolean {
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "EGL surface not created")
            return false
        }
        
        if (isReleased) {
            Log.e(TAG, "WindowSurface already released")
            return false
        }
        
        return eglCore.makeCurrent(eglSurface, context)
    }
    
    /**
     * 交换前后缓冲区（双缓冲）
     * 
     * 在每帧渲染完成后调用，将渲染结果显示到屏幕上
     * 
     * @return 是否成功
     */
    fun swapBuffers(): Boolean {
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "EGL surface not created")
            return false
        }
        
        if (isReleased) {
            Log.e(TAG, "WindowSurface already released")
            return false
        }
        
        return eglCore.swapBuffers(eglSurface)
    }
    
    /**
     * 获取 EGL Surface 对象
     * 
     * @return EGLSurface
     */
    fun getEglSurface(): EGLSurface {
        return eglSurface
    }
    
    /**
     * 检查 Surface 是否有效
     * 
     * @return true 表示 Surface 有效且已创建
     */
    fun isValid(): Boolean {
        return !isReleased && 
               eglSurface != EGL14.EGL_NO_SURFACE && 
               surface.isValid
    }
    
    /**
     * 释放资源
     * 
     * 注意：不会销毁 Android Surface（由调用者管理）
     */
    fun release() {
        if (!isReleased) {
            Log.d(TAG, "Releasing WindowSurface")
            
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                // 销毁 EGL Surface
                EGL14.eglDestroySurface(eglCore.eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
                Log.d(TAG, "EGL surface destroyed")
            }
            
            isReleased = true
        }
    }
}
