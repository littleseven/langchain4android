package com.picme.beauty.egl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.picme.beauty.api.BeautyParams
import com.picme.beauty.api.FaceData
import com.picme.beauty.api.PhotoProcessException
import com.picme.beauty.api.PhotoProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 拍照 GPU 化处理实现
 *
 * 核心设计：
 * 1. 创建独立 EGL 上下文（Pbuffer Surface），与预览上下文隔离
 * 2. Bitmap → GL_TEXTURE_2D（通过 texImage2D）
 * 3. 复用 BeautyRenderer 多 Pass 管线，但输入从 OES 外部纹理改为 2D 纹理
 * 4. FBO 输出通过 glReadPixels 读取为 Bitmap
 * 5. 完整资源释放
 *
 * 线程安全：此类的所有方法应在同一线程调用（建议后台线程）
 */
class PhotoProcessorImpl(private val context: Context) : PhotoProcessor {

    companion object {
        private const val TAG = "PicMe:PhotoProcessor"
    }

    // EGL 资源
    private val eglCore = EGLCore()
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var isEglInitialized = false

    // 美颜渲染器（复用预览的 BeautyRenderer）
    private var beautyRenderer: BeautyRenderer? = null

    // FBO 资源（离屏渲染输出）
    private var fboId: Int = 0
    private var fboTextureId: Int = 0
    private var fboWidth: Int = 0
    private var fboHeight: Int = 0

    // PBO 资源（异步读取优化）- 注意：PBO 需要 OpenGL ES 3.0+
    // 当前使用 glReadPixels 直接读取，后续可升级到 PBO
    private var usePbo: Boolean = false

    // 输入纹理
    private var inputTextureId: Int = 0

    // 全屏四边形顶点缓冲
    private val vertexBuffer by lazy {
        val vertices = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(vertices).position(0) }
    }

    private val textureBuffer by lazy {
        val coords = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )
        ByteBuffer.allocateDirect(coords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(coords).position(0) }
    }

    override fun process(bitmap: Bitmap, params: BeautyParams, faceData: FaceData?): Bitmap {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "process START: bitmap=${bitmap.width}x${bitmap.height}, enabled=${params.enabled}")

        try {
            // 1. 初始化 EGL 环境（如果未初始化）并绑定到当前线程
            ensureEglInitialized()
            
            // 确保 EGL 上下文在当前线程激活（关键修复：线程池可能导致线程切换）
            if (!eglCore.makeCurrent(eglSurface, eglContext)) {
                throw PhotoProcessException("Failed to rebind EGL context in current thread")
            }

            // 2. 检查纹理尺寸限制（必须在 EGL 上下文绑定后调用）
            val maxTextureSize = getMaxTextureSize()
            Log.d(TAG, "Max texture size: $maxTextureSize (thread: ${Thread.currentThread().name})")
            if (maxTextureSize <= 0) {
                throw PhotoProcessException("Failed to query GL_MAX_TEXTURE_SIZE (returned $maxTextureSize)")
            }
            if (bitmap.width > maxTextureSize || bitmap.height > maxTextureSize) {
                throw PhotoProcessException(
                    "Bitmap size ${bitmap.width}x${bitmap.height} exceeds max texture size $maxTextureSize"
                )
            }

            // 3. 创建/复用 FBO
            ensureFbo(bitmap.width, bitmap.height)

            // 4. 上传 Bitmap 到纹理
            uploadBitmapToTexture(bitmap)

            // 5. 初始化 BeautyRenderer（如果未初始化）
            val renderer = ensureBeautyRenderer()

            // 6. 设置美颜参数
            applyBeautyParams(renderer, params, faceData)

            // 7. 执行渲染（多 Pass）
            val outputTexture = renderPhoto(renderer, bitmap.width, bitmap.height)

            // 8. 读取 FBO 到 Bitmap
            val result = readPixelsToBitmap(bitmap.width, bitmap.height, outputTexture)

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "process DONE: elapsed=${elapsed}ms")

            return result
        } catch (e: PhotoProcessException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GPU photo processing failed", e)
            throw PhotoProcessException(e.message ?: "Unknown error", e)
        }
    }

    /**
     * 初始化 EGL 环境（Pbuffer Surface）
     */
    private fun ensureEglInitialized() {
        if (isEglInitialized) {
            Log.d(TAG, "EGL already initialized, reusing context")
            return
        }

        Log.d(TAG, "Initializing EGL for photo processing")

        if (!eglCore.init()) {
            throw PhotoProcessException("EGL initialization failed")
        }

        eglContext = eglCore.createContext()
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw PhotoProcessException("EGL context creation failed")
        }

        // 创建 Pbuffer Surface（离屏渲染）
        eglSurface = eglCore.createSurface(null, 1, 1)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw PhotoProcessException("EGL Pbuffer surface creation failed")
        }

        val makeCurrentResult = eglCore.makeCurrent(eglSurface, eglContext)
        if (!makeCurrentResult) {
            throw PhotoProcessException("eglMakeCurrent failed")
        }

        // 验证上下文绑定成功
        val glError = GLES20.glGetError()
        if (glError != GLES20.GL_NO_ERROR) {
            throw PhotoProcessException("GL error after eglMakeCurrent: $glError")
        }

        isEglInitialized = true
        Log.d(TAG, "EGL initialized successfully for photo processing")
    }

    /**
     * 创建/复用 FBO
     */
    private fun ensureFbo(width: Int, height: Int) {
        if (fboId != 0 && fboWidth == width && fboHeight == height) {
            return
        }

        // 删除旧 FBO
        deleteFbo()

        // 创建 FBO 纹理
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        fboTextureId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        // 创建 FBO
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fboId = fbos[0]

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTextureId, 0
        )

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            deleteFbo()
            throw PhotoProcessException("Framebuffer incomplete: $status")
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        fboWidth = width
        fboHeight = height

        // PBO 初始化（当前禁用，需要 OpenGL ES 3.0）
        // if (usePbo) { initPbo(width, height) }

        Log.d(TAG, "FBO created: ${width}x${height}, texture=$fboTextureId")
    }

    // PBO 相关方法已移除（需要 OpenGL ES 3.0）
    // 使用 glReadPixels 直接读取

    /**
     * 上传 Bitmap 到 OpenGL 2D 纹理
     */
    private fun uploadBitmapToTexture(bitmap: Bitmap) {
        if (inputTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(inputTextureId), 0)
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        inputTextureId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        Log.d(TAG, "Bitmap uploaded to texture: $inputTextureId")
    }

    /**
     * 初始化 BeautyRenderer
     */
    private fun ensureBeautyRenderer(): BeautyRenderer {
        var renderer = beautyRenderer
        if (renderer == null) {
            renderer = BeautyRenderer(context)
            renderer.onInit()
            beautyRenderer = renderer
            Log.d(TAG, "BeautyRenderer initialized for photo processing")
        }
        return renderer
    }

    /**
     * 应用美颜参数到 BeautyRenderer
     */
    private fun applyBeautyParams(renderer: BeautyRenderer, params: BeautyParams, faceData: FaceData?) {
        renderer.setTexelSize(fboWidth, fboHeight)

        // 基础美颜参数
        renderer.updateBeautyParams(
            smoothing = params.smoothing,
            whitening = params.whitening,
            sharpen = 0f,
            bigEyes = params.bigEyes,
            slimFace = params.slimFace,
            lipColor = params.lipColor,
            lipColorIndex = params.lipColorIndex,
            blush = params.blush,
            blushColorFamily = params.blushColorFamily
        )

        // 专业调色参数
        renderer.setColorGradeParams(
            exposure = params.exposure,
            contrast = params.contrast,
            saturation = params.saturation,
            temperature = params.temperature,
            tint = params.tint,
            brightness = params.brightness,
            redAdj = params.redAdjustment,
            greenAdj = params.greenAdjustment,
            blueAdj = params.blueAdjustment
        )

        // 色调滤镜
        renderer.updateColorMatrix(params.colorMatrix)

        // 风格特效
        renderer.setStyleEffect(params.styleEffect)
        renderer.setStyleParams(
            intensity = params.styleIntensity,
            toonThreshold = params.toonThreshold,
            toonQuantizationLevels = params.toonQuantizationLevels,
            sketchEdgeStrength = params.sketchEdgeStrength,
            posterizeColorLevels = params.posterizeColorLevels,
            embossIntensity = params.embossIntensity,
            crosshatchSpacing = params.crosshatchSpacing,
            crosshatchLineWidth = params.crosshatchLineWidth
        )

        // 人脸数据
        if (faceData != null && faceData.hasFace) {
            renderer.updateFaceWarpParams(
                faceCenterX = faceData.faceCenterX,
                faceCenterY = faceData.faceCenterY,
                leftEyeX = faceData.leftEyeX,
                leftEyeY = faceData.leftEyeY,
                rightEyeX = faceData.rightEyeX,
                rightEyeY = faceData.rightEyeY,
                mouthCenterX = faceData.mouthCenterX,
                mouthCenterY = faceData.mouthCenterY,
                mouthLeftX = faceData.mouthLeftX,
                mouthLeftY = faceData.mouthLeftY,
                mouthRightX = faceData.mouthRightX,
                mouthRightY = faceData.mouthRightY,
                upperLipCenterX = faceData.upperLipCenterX,
                upperLipCenterY = faceData.upperLipCenterY,
                lowerLipCenterX = faceData.lowerLipCenterX,
                lowerLipCenterY = faceData.lowerLipCenterY,
                faceRadius = faceData.faceRadius,
                hasFace = true
            )

            if (faceData.lipOuterPoints.isNotEmpty() || faceData.lipInnerPoints.isNotEmpty()) {
                renderer.updateLipMaskPoints(faceData.lipOuterPoints, faceData.lipInnerPoints)
            }

            if (faceData.leftCheekPoints.isNotEmpty() || faceData.rightCheekPoints.isNotEmpty()) {
                renderer.updateCheekContourPoints(faceData.leftCheekPoints, faceData.rightCheekPoints)
            }

            if (faceData.landmarks106 != null) {
                renderer.updateFacePoints106(faceData.landmarks106)
            }
        } else {
            // 无人脸：重置人脸状态
            renderer.updateFaceWarpParams(
                faceCenterX = 0.5f, faceCenterY = 0.5f,
                leftEyeX = 0.4f, leftEyeY = 0.45f,
                rightEyeX = 0.6f, rightEyeY = 0.45f,
                mouthCenterX = 0.5f, mouthCenterY = 0.62f,
                mouthLeftX = 0.42f, mouthLeftY = 0.62f,
                mouthRightX = 0.58f, mouthRightY = 0.62f,
                upperLipCenterX = 0.5f, upperLipCenterY = 0.60f,
                lowerLipCenterX = 0.5f, lowerLipCenterY = 0.66f,
                faceRadius = 0.18f, hasFace = false
            )
        }
    }

    /**
     * 执行拍照渲染
     *
     * 拍照路径专用渲染流程：
     * - 输入是 2D 纹理（Bitmap 上传），跳过 CopyPass（无需 OES → 2D 转换）
     * - 直接调用 BeautyRenderer.renderMainShaderFromFbo() 渲染到目标 FBO
     * - 避免使用 onRender() 中的多 Pass 管线（该管线依赖 OES 外部纹理和屏幕输出）
     *
     * 返回输出纹理 ID
     */
    private fun renderPhoto(renderer: BeautyRenderer, width: Int, height: Int): Int {
        // 设置 viewport
        GLES20.glViewport(0, 0, width, height)

        // 绑定我们的 FBO 作为渲染目标
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)

        // 清屏
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 使用 BeautyRenderer 的 2D 主 Shader 直接渲染
        // 输入是 2D 纹理（inputTextureId），输出到 fboId
        Log.d(TAG, "Before renderMainShaderFromFbo2D: inputTex=$inputTextureId, fbo=$fboId")
        renderer.renderMainShaderFromFbo2D(inputTextureId, width, height)

        // 检查 GL 错误
        val glError = GLES20.glGetError()
        if (glError != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "GL error after renderMainShaderFromFbo2D: $glError")
        }

        // 解绑 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        return fboTextureId
    }

    /**
     * 读取 FBO 像素到 Bitmap
     */
    private fun readPixelsToBitmap(width: Int, height: Int, textureId: Int): Bitmap {
        return readPixelsDirect(width, height)
    }

    /**
     * 直接同步读取
     */
    private fun readPixelsDirect(width: Int, height: Int): Bitmap {
        // [关键修复] 必须绑定 FBO 才能读取其内容
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        buffer.order(ByteOrder.nativeOrder())

        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        
        // 检查 GL 错误
        val glError = GLES20.glGetError()
        if (glError != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "GL error in glReadPixels: $glError")
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        
        // 解绑 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        return bitmap
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
     * 删除 FBO 资源
     */
    private fun deleteFbo() {
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
        }
        if (fboTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
            fboTextureId = 0
        }
        // PBO 资源释放（当前未使用）
        fboWidth = 0
        fboHeight = 0
    }

    override fun release() {
        Log.d(TAG, "Releasing PhotoProcessorImpl")

        // 释放 BeautyRenderer
        beautyRenderer?.release()
        beautyRenderer = null

        // 删除输入纹理
        if (inputTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(inputTextureId), 0)
            inputTextureId = 0
        }

        // 删除 FBO
        deleteFbo()

        // 释放 EGL
        if (isEglInitialized) {
            eglCore.clearCurrent()
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglCore.eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglCore.eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            eglCore.release()
            isEglInitialized = false
        }

        Log.d(TAG, "PhotoProcessorImpl released")
    }
}
