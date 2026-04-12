package com.picme.beauty.egl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.Matrix
import android.util.Log
import android.view.View
import com.picme.beauty.api.BeautyPerfStats

/**
 * R 计划 - 相机预览渲染器
 *
 * 功能：
 * 1. 管理 EGL 上下文和渲染线程
 * 2. 绑定 SurfaceTexture（来自 CameraX）
 * 3. 使用 BeautyRenderer 渲染美颜效果
 * 4. 输出到 SurfaceView
 */
class CameraPreviewRenderer(private val context: Context) {
    companion object {
        private const val TAG = "PicMe:CameraPreview"

        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
    }

    // PerfStats 已迁移到 api 层，保留向后兼容：PerfStats = BeautyPerfStats（见包级别 typealias）

    private val eglCore = EGLCore()
    private var eglContext: android.opengl.EGLContext? = null
    private var windowSurface: WindowSurface? = null
    private lateinit var beautyRenderer: BeautyRenderer
    private var renderThread: Thread? = null

    var isRendering = false
        private set

    private var surfaceTexture: SurfaceTexture? = null
    private var textureId: Int = -1

    @Volatile
    private var cameraInputWidth: Int = DEFAULT_WIDTH

    @Volatile
    private var cameraInputHeight: Int = DEFAULT_HEIGHT

    @Volatile
    private var isFillCenter: Boolean = true

    private var renderView: View? = null

    @Volatile
    private var frameAvailable: Boolean = false

    @Volatile
    private var currentOutputWidth: Int = DEFAULT_WIDTH

    @Volatile
    private var currentOutputHeight: Int = DEFAULT_HEIGHT

    @Volatile
    private var currentViewportX: Int = 0

    @Volatile
    private var currentViewportY: Int = 0

    @Volatile
    private var currentViewportWidth: Int = DEFAULT_WIDTH

    @Volatile
    private var currentViewportHeight: Int = DEFAULT_HEIGHT

    private val textureMatrixLock = Any()
    private val latestTextureTransformMatrix = FloatArray(16).apply {
        Matrix.setIdentityM(this, 0)
    }

    @Volatile
    private var latestPerfStats: BeautyPerfStats = BeautyPerfStats()

    interface OnTextureAvailableListener {
        fun onTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int)
        fun onTextureDestroyed()
    }

    private var textureListener: OnTextureAvailableListener? = null

    fun setOnTextureAvailableListener(listener: OnTextureAvailableListener?) {
        this.textureListener = listener
    }

    fun init(view: View) {
        Log.d(TAG, "Initializing CameraPreviewRenderer")
        this.renderView = view

        if (!eglCore.init()) {
            throw RuntimeException("Failed to initialize EGL")
        }

        eglContext = eglCore.createContext()

        val pbufferSurface = eglCore.createSurface(null, 1, 1)
        if (!eglCore.makeCurrent(pbufferSurface, eglContext!!)) {
            throw RuntimeException("Failed to make EGL context current before texture creation")
        }

        createExternalTexture()

        surfaceTexture = SurfaceTexture(textureId).apply {
            setOnFrameAvailableListener {
                frameAvailable = true
            }
        }

        beautyRenderer = BeautyRenderer(context)
        beautyRenderer.onInit()
        eglCore.clearCurrent()

        textureListener?.onTextureAvailable(surfaceTexture!!, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        Log.d(TAG, "CameraPreviewRenderer fully initialized")
    }

    private fun createExternalTexture() {
        val textures = intArrayOf(0)
        android.opengl.GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        if (textureId == 0) {
            throw RuntimeException("Failed to create external texture, textureId=0")
        }

        android.opengl.GLES20.glBindTexture(
            android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            textureId
        )
        android.opengl.GLES20.glTexParameteri(
            android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            android.opengl.GLES20.GL_TEXTURE_MIN_FILTER,
            android.opengl.GLES20.GL_LINEAR
        )
        android.opengl.GLES20.glTexParameteri(
            android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            android.opengl.GLES20.GL_TEXTURE_MAG_FILTER,
            android.opengl.GLES20.GL_LINEAR
        )
        android.opengl.GLES20.glTexParameteri(
            android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            android.opengl.GLES20.GL_TEXTURE_WRAP_S,
            android.opengl.GLES20.GL_CLAMP_TO_EDGE
        )
        android.opengl.GLES20.glTexParameteri(
            android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            android.opengl.GLES20.GL_TEXTURE_WRAP_T,
            android.opengl.GLES20.GL_CLAMP_TO_EDGE
        )
        Log.d(TAG, "External texture created: $textureId")
    }

    fun setRenderSurface(surface: android.view.Surface) {
        if (!surface.isValid) {
            Log.w(TAG, "Ignore invalid render surface")
            return
        }
        Log.d(TAG, "Setting render surface: hash=${surface.hashCode()}")
        windowSurface?.release()
        windowSurface = WindowSurface(surface, eglCore).apply { create() }
        startRendering()
    }

    private fun startRendering() {
        if (renderThread?.isAlive == true) {
            Log.d(TAG, "Render thread already running")
            return
        }

        isRendering = true

        renderThread = Thread {
            Log.d(TAG, "Render thread started")

            var frameCount = 0
            var framesReceived = 0
            var lastFrameTime = System.currentTimeMillis()
            var lastFpsLogMs = 0L
            var statsWindowStartMs = System.currentTimeMillis()
            var statsFrameCount = 0
            var statsProcessingTotalMs = 0L
            var statsNullFrames = 0

            try {
                while (isRendering && !Thread.interrupted()) {
                    if (!frameAvailable) {
                        if (!safeSleep(1)) break
                        continue
                    }

                    val frameStartNs = System.nanoTime()
                    try {
                        val ws = windowSurface
                        val context = eglContext
                        if (ws == null || context == null) {
                            if (!safeSleep(5)) break
                            continue
                        }

                        eglCore.makeCurrent(ws.getEglSurface(), context)

                        surfaceTexture?.updateTexImage()
                        frameAvailable = false
                        framesReceived++

                        val currentTime = System.currentTimeMillis()
                        // 限流：fps 日志 1 秒最多打一次
                        if (currentTime - lastFpsLogMs >= 1_000L) {
                            val elapsed = currentTime - lastFrameTime
                            val fps = if (elapsed > 0) framesReceived * 1000 / elapsed else 0
                            Log.d(TAG, "Received $framesReceived frames (~${fps}fps)")
                            lastFpsLogMs = currentTime
                            lastFrameTime = currentTime
                            framesReceived = 0
                        }

                        val transformMatrix = FloatArray(16)
                        surfaceTexture?.getTransformMatrix(transformMatrix)
                        synchronized(textureMatrixLock) {
                            System.arraycopy(transformMatrix, 0, latestTextureTransformMatrix, 0, 16)
                        }

                        android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0)
                        android.opengl.GLES20.glBindTexture(
                            android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                            textureId
                        )

                        beautyRenderer.setTextureTransform(transformMatrix)

                        val outputWidth =
                            renderView?.width?.takeIf { size -> size > 0 } ?: DEFAULT_WIDTH
                        val outputHeight =
                            renderView?.height?.takeIf { size -> size > 0 } ?: DEFAULT_HEIGHT
                        android.opengl.GLES20.glViewport(0, 0, outputWidth, outputHeight)
                        android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f)
                        android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT)

                        applyViewport(outputWidth, outputHeight)
                        beautyRenderer.setTexelSize(cameraInputWidth, cameraInputHeight)

                        beautyRenderer.onRender()
                        ws.swapBuffers()

                        frameCount++
                        statsFrameCount++
                        val frameProcessingMs =
                            ((System.nanoTime() - frameStartNs) / 1_000_000L).coerceAtLeast(0L)
                        statsProcessingTotalMs += frameProcessingMs

                        val statsNowMs = System.currentTimeMillis()
                        val statsElapsedMs = (statsNowMs - statsWindowStartMs).coerceAtLeast(1L)
                        if (statsElapsedMs >= 1000L) {
                            val fps = statsFrameCount * 1000f / statsElapsedMs.toFloat()
                            val avgProcessingMs =
                                if (statsFrameCount > 0) (statsProcessingTotalMs / statsFrameCount).toInt() else 0
                            val frameBudgetMs = if (fps > 0.1f) (1000f / fps).toInt() else 0
                            val delayMs = (frameBudgetMs - avgProcessingMs).coerceAtLeast(0)
                            val cpuUsage =
                                (statsProcessingTotalMs * 100f / statsElapsedMs.toFloat()).coerceIn(0f, 100f)
                            latestPerfStats = BeautyPerfStats(
                                fps = fps,
                                processingMs = avgProcessingMs,
                                delayMs = delayMs,
                                cpuUsage = cpuUsage,
                                nullFrames = statsNullFrames
                            )
                            statsWindowStartMs = statsNowMs
                            statsFrameCount = 0
                            statsProcessingTotalMs = 0L
                            statsNullFrames = 0
                        }
                    } catch (e: IllegalStateException) {
                        frameAvailable = false
                        statsNullFrames++
                        if (frameCount == 0 || frameCount % 60 == 0) {
                            Log.w(TAG, "SurfaceTexture not ready yet: ${e.message}")
                        }
                        if (!safeSleep(16)) break
                    } catch (e: Exception) {
                        statsNullFrames++
                        Log.e(TAG, "Render error at frame $frameCount: ${e.message}", e)
                        if (!safeSleep(16)) break
                    }
                }
            } finally {
                isRendering = false
                renderThread = null
                Log.d(TAG, "Render thread stopped after $frameCount frames")
            }
        }.apply {
            name = "CameraPreviewRender"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun safeSleep(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun setCameraInputBufferSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        cameraInputWidth = width
        cameraInputHeight = height
    }

    fun setScaleMode(isFillCenter: Boolean) {
        this.isFillCenter = isFillCenter
    }

    private fun applyViewport(outputWidth: Int, outputHeight: Int) {
        val safeOutputWidth = outputWidth.coerceAtLeast(1)
        val safeOutputHeight = outputHeight.coerceAtLeast(1)

        val rawSourceAspect = cameraInputWidth.toFloat() / cameraInputHeight.toFloat()
        val rotatedSourceAspect = cameraInputHeight.toFloat() / cameraInputWidth.toFloat()
        val outputAspect = safeOutputWidth.toFloat() / safeOutputHeight.toFloat()

        val sourceAspect = if (
            kotlin.math.abs(rotatedSourceAspect - outputAspect) <
            kotlin.math.abs(rawSourceAspect - outputAspect)
        ) rotatedSourceAspect else rawSourceAspect

        val viewportWidth: Int
        val viewportHeight: Int

        if (isFillCenter) {
            if (sourceAspect > outputAspect) {
                viewportHeight = safeOutputHeight
                viewportWidth = (safeOutputHeight * sourceAspect).toInt().coerceAtLeast(1)
            } else {
                viewportWidth = safeOutputWidth
                viewportHeight = (safeOutputWidth / sourceAspect).toInt().coerceAtLeast(1)
            }
        } else {
            if (sourceAspect > outputAspect) {
                viewportWidth = safeOutputWidth
                viewportHeight = (safeOutputWidth / sourceAspect).toInt().coerceAtLeast(1)
            } else {
                viewportHeight = safeOutputHeight
                viewportWidth = (safeOutputHeight * sourceAspect).toInt().coerceAtLeast(1)
            }
        }

        val x = (safeOutputWidth - viewportWidth) / 2
        val y = (safeOutputHeight - viewportHeight) / 2

        currentOutputWidth = safeOutputWidth
        currentOutputHeight = safeOutputHeight
        currentViewportX = x
        currentViewportY = y
        currentViewportWidth = viewportWidth
        currentViewportHeight = viewportHeight

        android.opengl.GLES20.glViewport(x, y, viewportWidth, viewportHeight)
    }

    private fun mapViewNormalizedToUv(x: Float, y: Float): Pair<Float, Float> {
        val outputW = currentOutputWidth.coerceAtLeast(1)
        val outputH = currentOutputHeight.coerceAtLeast(1)
        val viewportW = currentViewportWidth.coerceAtLeast(1)
        val viewportH = currentViewportHeight.coerceAtLeast(1)

        val pixelX = x.coerceIn(0f, 1f) * outputW
        val pixelY = y.coerceIn(0f, 1f) * outputH

        val preTransformUvX = ((pixelX - currentViewportX) / viewportW).coerceIn(0f, 1f)
        val preTransformUvY = (1f - ((pixelY - currentViewportY) / viewportH)).coerceIn(0f, 1f)

        val transformed = FloatArray(4)
        val matrixCopy = FloatArray(16)
        synchronized(textureMatrixLock) {
            System.arraycopy(latestTextureTransformMatrix, 0, matrixCopy, 0, 16)
        }
        Matrix.multiplyMV(
            transformed, 0,
            matrixCopy, 0,
            floatArrayOf(preTransformUvX, preTransformUvY, 0f, 1f), 0
        )

        return Pair(transformed[0].coerceIn(0f, 1f), transformed[1].coerceIn(0f, 1f))
    }

    fun updateBeautyParams(
        smoothing: Float,
        whitening: Float,
        bigEyes: Float = 0f,
        slimFace: Float = 0f,
        lipColor: Float = 0f,
        lipColorIndex: Int = 0,
        blush: Float = 0f,
        blushColorFamily: Int = 0
    ) {
        beautyRenderer.updateBeautyParams(
            smoothing = smoothing,
            whitening = whitening,
            bigEyes = bigEyes,
            slimFace = slimFace,
            lipColor = lipColor,
            lipColorIndex = lipColorIndex,
            blush = blush,
            blushColorFamily = blushColorFamily
        )
    }

    fun updateFaceWarpParams(
        faceCenterX: Float,
        faceCenterY: Float,
        leftEyeX: Float,
        leftEyeY: Float,
        rightEyeX: Float,
        rightEyeY: Float,
        mouthCenterX: Float,
        mouthCenterY: Float,
        mouthLeftX: Float,
        mouthLeftY: Float,
        mouthRightX: Float,
        mouthRightY: Float,
        upperLipCenterX: Float,
        upperLipCenterY: Float,
        lowerLipCenterX: Float,
        lowerLipCenterY: Float,
        faceRadius: Float,
        hasFace: Boolean
    ) {
        val mappedFaceCenter = mapViewNormalizedToUv(faceCenterX, faceCenterY)
        val mappedLeftEye = mapViewNormalizedToUv(leftEyeX, leftEyeY)
        val mappedRightEye = mapViewNormalizedToUv(rightEyeX, rightEyeY)
        val mappedMouthCenter = mapViewNormalizedToUv(mouthCenterX, mouthCenterY)
        val mappedMouthLeft = mapViewNormalizedToUv(mouthLeftX, mouthLeftY)
        val mappedMouthRight = mapViewNormalizedToUv(mouthRightX, mouthRightY)
        val mappedUpperLipCenter = mapViewNormalizedToUv(upperLipCenterX, upperLipCenterY)
        val mappedLowerLipCenter = mapViewNormalizedToUv(lowerLipCenterX, lowerLipCenterY)

        beautyRenderer.updateFaceWarpParams(
            faceCenterX = mappedFaceCenter.first,
            faceCenterY = mappedFaceCenter.second,
            leftEyeX = mappedLeftEye.first,
            leftEyeY = mappedLeftEye.second,
            rightEyeX = mappedRightEye.first,
            rightEyeY = mappedRightEye.second,
            mouthCenterX = mappedMouthCenter.first,
            mouthCenterY = mappedMouthCenter.second,
            mouthLeftX = mappedMouthLeft.first,
            mouthLeftY = mappedMouthLeft.second,
            mouthRightX = mappedMouthRight.first,
            mouthRightY = mappedMouthRight.second,
            upperLipCenterX = mappedUpperLipCenter.first,
            upperLipCenterY = mappedUpperLipCenter.second,
            lowerLipCenterX = mappedLowerLipCenter.first,
            lowerLipCenterY = mappedLowerLipCenter.second,
            faceRadius = faceRadius,
            hasFace = hasFace
        )
    }

    fun updateLipMaskPoints(
        outerPoints: List<Pair<Float, Float>>,
        innerPoints: List<Pair<Float, Float>>
    ) {
        val mappedOuterPoints = outerPoints.map { point ->
            mapViewNormalizedToUv(point.first, point.second)
        }
        val mappedInnerPoints = innerPoints.map { point ->
            mapViewNormalizedToUv(point.first, point.second)
        }
        val normalizedInnerPoints = normalizeInnerLipContour(mappedOuterPoints, mappedInnerPoints)
        beautyRenderer.updateLipMaskPoints(mappedOuterPoints, normalizedInnerPoints)
    }

    private fun normalizeInnerLipContour(
        outerPoints: List<Pair<Float, Float>>,
        innerPoints: List<Pair<Float, Float>>
    ): List<Pair<Float, Float>> {
        if (innerPoints.size < 6 || outerPoints.size < 6) return innerPoints
        val outerArea = polygonArea(outerPoints)
        val innerArea = polygonArea(innerPoints)
        if (outerArea <= 0.000001f) return innerPoints
        val areaRatio = innerArea / outerArea
        return if (areaRatio > 0.55f) {
            Log.w(TAG, "Lip inner contour area too large (ratio=$areaRatio), fallback")
            emptyList()
        } else {
            innerPoints
        }
    }

    private fun polygonArea(points: List<Pair<Float, Float>>): Float {
        if (points.size < 3) return 0f
        var sum = 0f
        for (index in points.indices) {
            val nextIndex = (index + 1) % points.size
            val currentPoint = points[index]
            val nextPoint = points[nextIndex]
            sum += currentPoint.first * nextPoint.second - nextPoint.first * currentPoint.second
        }
        return kotlin.math.abs(sum) * 0.5f
    }

    fun updateColorMatrix(matrix: FloatArray?) {
        beautyRenderer.updateColorMatrix(matrix)
    }

    fun setRenderMode(mode: Int) {
        beautyRenderer.setRenderMode(mode)
    }

    fun getPerfStats(): BeautyPerfStats = latestPerfStats

    fun setDebugMode(mode: Int) {
        beautyRenderer.setDebugMode(mode)
    }

    fun getSurfaceTexture(): SurfaceTexture? = surfaceTexture

    fun getSurfaceForCamera(): android.view.Surface? {
        return surfaceTexture?.let { android.view.Surface(it) }
    }

    fun release() {
        Log.d(TAG, "Releasing CameraPreviewRenderer")
        isRendering = false
        frameAvailable = false
        renderThread?.interrupt()
        renderThread = null
        windowSurface?.release()
        windowSurface = null
        if (textureId != -1) {
            val textures = intArrayOf(textureId)
            android.opengl.GLES20.glDeleteTextures(1, textures, 0)
            textureId = -1
        }
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
        beautyRenderer.release()
        eglCore.release()
        textureListener?.onTextureDestroyed()
        Log.d(TAG, "Released")
    }
}

