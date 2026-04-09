package com.picme.beauty.egl

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
 */
class WindowSurface(
    private val surface: Surface,
    private val eglCore: EGLCore
) {
    companion object {
        private const val TAG = "PicMe:WindowSurface"
    }

    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var isReleased = false

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

    fun getEglSurface(): EGLSurface = eglSurface

    fun isValid(): Boolean {
        return !isReleased &&
            eglSurface != EGL14.EGL_NO_SURFACE &&
            surface.isValid
    }

    fun release() {
        if (!isReleased) {
            Log.d(TAG, "Releasing WindowSurface")
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglCore.eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
                Log.d(TAG, "EGL surface destroyed")
            }
            isReleased = true
        }
    }
}

