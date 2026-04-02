package com.picme.core.image

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

/**
 * R 计划 - EGL 核心管理类
 *
 * 功能：
 * 1. 初始化 EGL 显示连接
 * 2. 创建和管理 EGL 上下文
 * 3. 创建 EGL Surface（Pbuffer 和 WindowSurface）
 * 4. 管理上下文切换
 *
 * @author RD Team
 * @version 1.0 (R 计划)
 */
class EGLCore {
    companion object {
        private const val TAG = "PicMe:EGLCore"

        /** EGL 版本 */
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    /** EGL 显示连接 */
    var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private set

    /** EGL 配置 */
    private var eglConfig: EGLConfig? = null

    /** 共享上下文（用于上下文共享） */
    private var sharedContext: EGLContext = EGL14.EGL_NO_CONTEXT

    /**
     * 初始化 EGL
     *
     * @return 是否成功
     */
    fun init(): Boolean {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            Log.w(TAG, "EGL already initialized")
            return true
        }

        // 1. 获取默认显示设备
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "Unable to get EGL14 display")
            return false
        }

        // 2. 初始化 EGL
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "Unable to initialize EGL14")
            eglDisplay = EGL14.EGL_NO_DISPLAY
            return false
        }

        Log.d(TAG, "EGL version: ${version[0]}.${version[1]}")

        // 3. 选择 EGL 配置
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)

        if (!EGL14.eglChooseConfig(
                eglDisplay, configAttribs, 0,
                configs, 0, configs.size,
                numConfigs, 0
            )
        ) {
            Log.e(TAG, "Unable to find RGB888+recordable ES2 EGL config")
            return false
        }

        eglConfig = configs[0]
        Log.d(TAG, "EGL initialized successfully")

        return true
    }

    /**
     * 创建 EGL 上下文
     *
     * @return EGL 上下文
     */
    fun createContext(): EGLContext {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw IllegalStateException("EGL not initialized")
        }

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )

        val context = EGL14.eglCreateContext(
            eglDisplay, eglConfig, sharedContext, contextAttribs, 0
        )

        if (context == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Failed to create EGL context")
        }

        // 保存第一个上下文作为共享上下文
        if (sharedContext == EGL14.EGL_NO_CONTEXT) {
            sharedContext = context
        }

        Log.d(TAG, "EGL context created: ${context.hashCode()}")

        return context
    }

    /**
     * 创建 Pbuffer Surface（离屏渲染）
     *
     * @param width 宽度
     * @param height 高度
     * @return EGL Surface
     */
    fun createSurface(surface: Surface?, width: Int = 1, height: Int = 1): EGLSurface {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw IllegalStateException("EGL not initialized")
        }

        val eglSurface = if (surface == null) {
            // 创建 Pbuffer Surface（离屏渲染）
            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
            )

            EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
        } else {
            // 创建 Window Surface（显示）
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)

            EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        }

        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("Failed to create EGL surface")
        }

        Log.d(TAG, "EGL surface created: ${eglSurface.hashCode()}")

        return eglSurface
    }

    /**
     * 使上下文和 Surface 成为当前渲染目标
     *
     * @param surface EGL Surface
     * @param context EGL 上下文
     * @return 是否成功
     */
    fun makeCurrent(surface: EGLSurface, context: EGLContext): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "EGL not initialized")
            return false
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, surface, surface, context)) {
            Log.e(TAG, "eglMakeCurrent failed")
            return false
        }

        return true
    }

    /**
     * 清除当前线程绑定的 EGL 上下文
     */
    fun clearCurrent(): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "EGL not initialized")
            return false
        }

        return EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )
    }

    /**
     * 交换缓冲区
     *
     * @param surface EGL Surface
     * @return 是否成功
     */
    fun swapBuffers(surface: EGLSurface): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "EGL not initialized")
            return false
        }

        return EGL14.eglSwapBuffers(eglDisplay, surface)
    }

    /**
     * 释放资源
     */
    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )

            if (sharedContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, sharedContext)
                sharedContext = EGL14.EGL_NO_CONTEXT
            }

            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY

            Log.d(TAG, "EGL released")
        }
    }
}

