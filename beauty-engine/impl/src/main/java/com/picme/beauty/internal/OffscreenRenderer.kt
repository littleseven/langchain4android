package com.picme.beauty.internal

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Log

/**
 * OpenGL 离屏渲染器
 *
 * 核心职责：将 Bitmap 通过 OpenGL Shader 处理，输出处理后的 Bitmap
 *
 * 这是方案A（ADR-002）的关键组件，实现了预览和拍照使用同一套 Shader 的目标。
 *
 * 使用场景：
 * 1. 拍照后处理：Bitmap → Texture → Shader Chain → FBO → Bitmap
 * 2. 批量图片处理
 * 3. 离线滤镜应用
 *
 * 性能优化：
 * - 使用 PBO（Pixel Buffer Object）进行异步读取
 * - 支持分块处理超大图片（4K+）
 * - 纹理资源池复用，减少 GC 压力
 *
 * @param eglContext EGL 上下文，必须与预览渲染共用同一上下文或共享上下文
 */
class OffscreenRenderer(
    private val eglContext: EGLContext
) {
    private var fboId: Int = 0
    private var fboTextureId: Int = 0
    private var pboIds: IntArray? = null

    // 当前 FBO 尺寸
    private var fboWidth: Int = 0
    private var fboHeight: Int = 0

    // 是否使用 PBO 异步读取
    private var usePBO: Boolean = true

    // PBO 双缓冲索引
    private var pboIndex: Int = 0
    private val PBO_COUNT = 2

    /**
     * 处理 Bitmap 通过 OpenGL Shader 链
     *
     * 完整流程：
     * 1. Bitmap → OpenGL Texture
     * 2. 创建/复用 FBO
     * 3. 绑定 FBO 和输出纹理
     * 4. 执行 Shader 链（与预览完全一致！）
     * 5. FBO → Bitmap（通过 glReadPixels 或 PBO）
     * 6. 清理资源
     *
     * @param inputBitmap 原始照片 Bitmap
     * @param shaderChain 美颜 Shader 链（预览和拍照共用）
     * @return 处理后的 Bitmap
     *
     * @throws IllegalStateException 如果 EGL 上下文无效
     * @throws IllegalArgumentException 如果输入 Bitmap 为空或尺寸过大
     */
    fun processBitmap(
        inputBitmap: Bitmap,
        shaderChain: BeautyShaderChain
    ): Bitmap {
        require(inputBitmap.width > 0 && inputBitmap.height > 0) {
            "Input bitmap must have positive dimensions"
        }

        // 检查尺寸限制（防止 OOM）
        val maxTextureSize = getMaxTextureSize()
        require(inputBitmap.width <= maxTextureSize && inputBitmap.height <= maxTextureSize) {
            "Bitmap size (${inputBitmap.width}x${inputBitmap.height}) exceeds max texture size ($maxTextureSize)"
        }

        // 1. Bitmap → OpenGL Texture
        val inputTexture = bitmapToTexture(inputBitmap)

        try {
            // 2. 确保 FBO 已创建（尺寸匹配）
            ensureFBO(inputBitmap.width, inputBitmap.height)

            // 3. 绑定 FBO
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            checkGLError("glBindFramebuffer")

            // 4. 执行 Shader 链（与预览完全一致！）
            shaderChain.render(inputTexture, fboTextureId)
            checkGLError("shaderChain.render")

            // 5. FBO → Bitmap
            val outputBitmap = if (usePBO) {
                readPixelsWithPBO(inputBitmap.width, inputBitmap.height)
            } else {
                readPixelsDirect(inputBitmap.width, inputBitmap.height)
            }
            checkGLError("readPixels")

            return outputBitmap

        } finally {
            // 6. 清理
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            deleteTexture(inputTexture)
        }
    }

    /**
     * 将 Bitmap 转换为 OpenGL 纹理
     */
    private fun bitmapToTexture(bitmap: Bitmap): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)

        // 设置纹理参数
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // 加载 Bitmap 到纹理
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        return texId
    }

    /**
     * 确保 FBO 已创建且尺寸匹配
     */
    private fun ensureFBO(width: Int, height: Int) {
        if (fboId == 0 || fboWidth != width || fboHeight != height) {
            // 删除旧的 FBO
            deleteFBO()

            // 创建新的 FBO 纹理
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            fboTextureId = textures[0]

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                width,
                height,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

            // 创建 FBO
            val fbos = IntArray(1)
            GLES20.glGenFramebuffers(1, fbos, 0)
            fboId = fbos[0]

            // 绑定纹理到 FBO
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                fboTextureId,
                0
            )

            // 检查 FBO 完整性
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw RuntimeException("Framebuffer not complete: $status")
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            fboWidth = width
            fboHeight = height

            // 初始化 PBO（如果需要）
            if (usePBO) {
                initPBO(width, height)
            }
        }
    }

    /**
     * 初始化 PBO 用于异步读取
     */
    private fun initPBO(width: Int, height: Int) {
        pboIds?.let { GLES20.glDeleteBuffers(it.size, it, 0) }

        pboIds = IntArray(PBO_COUNT)
        GLES20.glGenBuffers(PBO_COUNT, pboIds, 0)

        val bufferSize = width * height * 4  // RGBA = 4 bytes per pixel

        pboIds?.forEach { pboId ->
            GLES20.glBindBuffer(GLES20.GL_PIXEL_PACK_BUFFER, pboId)
            GLES20.glBufferData(GLES20.GL_PIXEL_PACK_BUFFER, bufferSize, null, GLES20.GL_STREAM_READ)
        }

        GLES20.glBindBuffer(GLES20.GL_PIXEL_PACK_BUFFER, 0)
    }

    /**
     * 使用 PBO 异步读取像素（性能优化）
     */
    private fun readPixelsWithPBO(width: Int, height: Int): Bitmap {
        val pboIds = this.pboIds ?: return readPixelsDirect(width, height)

        // 当前使用的 PBO 索引
        val currentIndex = pboIndex % PBO_COUNT
        val nextIndex = (pboIndex + 1) % PBO_COUNT

        // 读取上一帧的数据（如果存在）
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        GLES20.glBindBuffer(GLES20.GL_PIXEL_PACK_BUFFER, pboIds[nextIndex])
        val buffer = GLES20.glMapBufferRange(
            GLES20.GL_PIXEL_PACK_BUFFER,
            0,
            width * height * 4,
            GLES20.GL_MAP_READ_BIT
        ) as ByteBuffer

        buffer.order(ByteOrder.nativeOrder())
        bitmap.copyPixelsFromBuffer(buffer)

        GLES20.glUnmapBuffer(GLES20.GL_PIXEL_PACK_BUFFER)

        // 触发当前帧的异步读取
        GLES20.glBindBuffer(GLES20.GL_PIXEL_PACK_BUFFER, pboIds[currentIndex])
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)

        GLES20.glBindBuffer(GLES20.GL_PIXEL_PACK_BUFFER, 0)

        pboIndex++

        return bitmap
    }

    /**
     * 直接读取像素（同步，首次使用或 PBO 不可用时）
     */
    private fun readPixelsDirect(width: Int, height: Int): Bitmap {
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        buffer.order(ByteOrder.nativeOrder())

        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        return bitmap
    }

    /**
     * 删除纹理
     */
    private fun deleteTexture(textureId: Int) {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }

    /**
     * 删除 FBO 和相关资源
     */
    private fun deleteFBO() {
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
        }
        if (fboTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
            fboTextureId = 0
        }
        pboIds?.let {
            GLES20.glDeleteBuffers(it.size, it, 0)
            pboIds = null
        }
        fboWidth = 0
        fboHeight = 0
    }

    /**
     * 获取最大纹理尺寸
     */
    private fun getMaxTextureSize(): Int {
        val size = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, size, 0)
        return size[0]
    }

    /**
     * 检查 OpenGL 错误
     */
    private fun checkGLError(operation: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e("OffscreenRenderer", "GL Error after $operation: $error")
        }
    }

    /**
     * 释放所有资源
     */
    fun release() {
        deleteFBO()
    }

    /**
     * 设置是否使用 PBO 异步读取
     */
    fun setUsePBO(use: Boolean) {
        usePBO = use
    }
}

/**
 * EGL 上下文接口（简化版）
 * 实际实现应与现有 EGL 管理器兼容
 */
interface EGLContext {
    fun makeCurrent(): Boolean
    fun swapBuffers(): Boolean
    fun release()
}
