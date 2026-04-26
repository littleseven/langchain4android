package com.picme.beauty.egl

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 大美丽 - 美颜渲染器
 *
 * 功能：
 * 1. 使用美颜 Shader 渲染相机预览帧
 * 2. 支持实时调整磨皮、美白、大眼、瘦脸、唇色、腮红
 * 3. Phase 2: 支持风格特效多 Pass 渲染（Toon/Sketch/Posterize/Emboss/Crosshatch）
 */
class BeautyRenderer(private val context: Context) : GLRenderer() {
    companion object {
        private const val TAG = "PicMe:BeautyRenderer"

        const val MODE_DEBUG_RED = 0
        const val MODE_DEBUG_TEXTURE_R = 1
        const val MODE_BEAUTY = 2
        const val MODE_ADVANCED = 3

        private const val MAX_LIP_CONTOUR_POINTS = 20
    }

    private var renderMode: Int = MODE_BEAUTY

    private var smoothingStrength: Float = 0.5f
    private var whiteningStrength: Float = 0.5f
    private var sharpenStrength: Float = 0.0f
    private var bigEyesStrength: Float = 0f
    private var slimFaceStrength: Float = 0f
    private var lipColorStrength: Float = 0f
    private var lipColorIndex: Int = 0
    private var blushStrength: Float = 0f
    private var blushColorFamily: Int = 0

    private var colorMatrix: FloatArray? = null

    private var faceCenterX: Float = 0.5f
    private var faceCenterY: Float = 0.5f
    private var leftEyeX: Float = 0.4f
    private var leftEyeY: Float = 0.45f
    private var rightEyeX: Float = 0.6f
    private var rightEyeY: Float = 0.45f
    private var mouthCenterX: Float = 0.5f
    private var mouthCenterY: Float = 0.62f
    private var mouthLeftX: Float = 0.42f
    private var mouthLeftY: Float = 0.62f
    private var mouthRightX: Float = 0.58f
    private var mouthRightY: Float = 0.62f
    private var upperLipCenterX: Float = 0.5f
    private var upperLipCenterY: Float = 0.60f
    private var lowerLipCenterX: Float = 0.5f
    private var lowerLipCenterY: Float = 0.66f
    private val lipOuterContourBuffer = FloatArray(MAX_LIP_CONTOUR_POINTS * 2)
    private var lipOuterContourCount: Int = 0
    private val lipInnerContourBuffer = FloatArray(MAX_LIP_CONTOUR_POINTS * 2)
    private var lipInnerContourCount: Int = 0
    private var faceRadius: Float = 0.18f
    private var hasFace: Float = 0f

    private val leftCheekContourBuffer = FloatArray(MAX_LIP_CONTOUR_POINTS * 2)
    private var leftCheekContourCount: Int = 0
    private val rightCheekContourBuffer = FloatArray(MAX_LIP_CONTOUR_POINTS * 2)
    private var rightCheekContourCount: Int = 0

    private var warmthStrength: Float = 0.0f
    private var contrast: Float = 1.0f
    private var exposureStrength: Float = 0.0f
    private var contrastStrength: Float = 1.0f
    private var saturationStrength: Float = 1.0f
    private var temperatureStrength: Float = 0.0f
    private var tintStrength: Float = 0.0f
    private var brightnessStrength: Float = 0.0f
    private var redAdjustment: Float = 1.0f
    private var greenAdjustment: Float = 1.0f
    private var blueAdjustment: Float = 1.0f
    private var contourThinFaceStrength: Float = 0.0f
    private var texelSizeX: Float = 0.0015f
    private var texelSizeY: Float = 0.0015f

    // GPUPixel 风格瘦脸/大眼：106点人脸关键点
    private val facePointsBuffer = FloatArray(106 * 2)
    private var useGpupixelWarp: Int = 1  // 默认启用GPUPixel风格warp
    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0

    private var renderFrameCount: Long = 0

    // Phase 2: 风格特效多 Pass 支持
    private val styleEffectShader = StyleEffectShader(context)
    private var intermediateFbo: Framebuffer? = null
    private var fboWidth: Int = 0
    private var fboHeight: Int = 0

    // Phase 3: GPUPixel 风格多 Pass 美颜支持
    private val fboPool = FramebufferPool()
    private val lutTextureLoader = LutTextureLoader(context)

    // Pass 0: OES外部纹理 -> 2D纹理
    private val copyPass = BeautyPass(context)
    private var copyPassCompiled = false

    // Pass 1: BoxBlur -> 均值图 (meanColor)
    private val boxBlurPass = BeautyPass(context)
    private var boxBlurPassCompiled = false

    // Pass 2: BoxHighPass -> 方差图 (varColor)
    private val boxHighPassPass = BeautyPass(context)
    private var boxHighPassPassCompiled = false

    // Pass 3: BeautyFaceUnit -> 磨皮+美白+LUT
    private val beautyUnitPass = BeautyPass(context)
    private var beautyUnitPassCompiled = false

    // FBO Copy Pass - 用于将FBO纹理直接渲染到屏幕（验证/调试）
    private val fboCopyPass = BeautyPass(context)
    private var fboCopyPassCompiled = false

    // Debug Pass - 用于验证Pass是否执行
    private val debugRedPass = BeautyPass(context)
    private var debugRedPassCompiled = false

    private var multiPassBeautyEnabled: Boolean = false

    private var uSmoothingLocation: Int = -1
    private var uWhiteningLocation: Int = -1
    private var uSharpenLocation: Int = -1
    private var uBigEyesLocation: Int = -1
    private var uSlimFaceLocation: Int = -1
    private var uFaceCenterLocation: Int = -1
    private var uLeftEyeLocation: Int = -1
    private var uRightEyeLocation: Int = -1
    private var uFaceRadiusLocation: Int = -1
    private var uHasFaceLocation: Int = -1
    private var uMouthCenterLocation: Int = -1
    private var uMouthLeftLocation: Int = -1
    private var uMouthRightLocation: Int = -1
    private var uUpperLipCenterLocation: Int = -1
    private var uLowerLipCenterLocation: Int = -1
    private var uLipOuterContourPointsLocation: Int = -1
    private var uLipOuterContourCountLocation: Int = -1
    private var uLipInnerContourPointsLocation: Int = -1
    private var uLipInnerContourCountLocation: Int = -1
    private var uLeftCheekContourPointsLocation: Int = -1
    private var uLeftCheekContourCountLocation: Int = -1
    private var uRightCheekContourPointsLocation: Int = -1
    private var uRightCheekContourCountLocation: Int = -1
    private var uWarmthLocation: Int = -1
    private var uContrastLocation: Int = -1
    private var uLipColorLocation: Int = -1
    private var uLipColorIndexLocation: Int = -1
    private var uBlushLocation: Int = -1
    private var uBlushColorFamilyLocation: Int = -1
    private var uCMRow0Location: Int = -1
    private var uCMRow1Location: Int = -1
    private var uCMRow2Location: Int = -1
    private var uCMRow3Location: Int = -1
    private var uCMOffsetLocation: Int = -1
    private var uHasColorMatrixLocation: Int = -1
    private var uExposureLocation: Int = -1
    private var uSaturationLocation: Int = -1
    private var uTemperatureLocation: Int = -1
    private var uTintLocation: Int = -1
    private var uBrightnessLocation: Int = -1
    private var uRedAdjLocation: Int = -1
    private var uGreenAdjLocation: Int = -1
    private var uBlueAdjLocation: Int = -1
    private var uContourThinFaceLocation: Int = -1
    private var uDebugModeLocation: Int = -1
    private var uFacePointsLocation: Int = -1
    private var uAspectRatioLocation: Int = -1
    private var uUseGpupixelWarpLocation: Int = -1

    private var debugMode: Int = 0  // 0=正常渲染

    // FBO纹理采样时使用翻转Y轴的UV（因为FBO渲染的图像Y轴是反的）
    private val fboTextureBuffer: FloatBuffer by lazy {
        val coords = floatArrayOf(
            0f, 1f,  // 左下 -> 左上（V翻转）
            1f, 1f,  // 右下 -> 右上
            0f, 0f,  // 左上 -> 左下
            1f, 0f   // 右上 -> 右下
        )
        ByteBuffer.allocateDirect(coords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(coords).position(0) }
    }

    fun setRenderMode(mode: Int) {
        if (renderMode != mode) {
            Log.d(TAG, "Render mode changed: $renderMode -> $mode")
            renderMode = mode
            isInitialized = false
            shaderProgram.release()

            if (onCompileShader()) {
                aPositionLocation = shaderProgram.getAttribLocation("aPosition")
                aTextureCoordLocation = shaderProgram.getAttribLocation("aTextureCoord")
                uTextureLocation = shaderProgram.getUniformLocation("uTexture")
                uTextureTransformLocation = shaderProgram.getUniformLocation("uTextureTransform")
                initUniformLocations()
                isInitialized = true
            }
        }
    }

    fun updateBeautyParams(
        smoothing: Float,
        whitening: Float,
        sharpen: Float = 0.0f,
        bigEyes: Float = 0f,
        slimFace: Float = 0f,
        lipColor: Float = 0f,
        lipColorIndex: Int = 0,
        blush: Float = 0f,
        blushColorFamily: Int = 0
    ) {
        smoothingStrength = smoothing.coerceIn(0f, 1f)
        whiteningStrength = whitening.coerceIn(0f, 1f)
        sharpenStrength = sharpen.coerceIn(0f, 1f)
        bigEyesStrength = bigEyes.coerceIn(0f, 1f)
        slimFaceStrength = slimFace.coerceIn(-1f, 1f)
        lipColorStrength = lipColor.coerceIn(0f, 1f)
        this.lipColorIndex = lipColorIndex.coerceIn(0, 11)
        blushStrength = blush.coerceIn(0f, 1f)
        this.blushColorFamily = blushColorFamily.coerceIn(0, 2)
        Log.d(
            TAG,
            "Beauty params updated: smoothing=$smoothingStrength, whitening=$whiteningStrength, " +
                "sharpen=$sharpenStrength, bigEyes=$bigEyesStrength, slimFace=$slimFaceStrength"
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
        this.faceCenterX = faceCenterX.coerceIn(0f, 1f)
        this.faceCenterY = faceCenterY.coerceIn(0f, 1f)
        this.leftEyeX = leftEyeX.coerceIn(0f, 1f)
        this.leftEyeY = leftEyeY.coerceIn(0f, 1f)
        this.rightEyeX = rightEyeX.coerceIn(0f, 1f)
        this.rightEyeY = rightEyeY.coerceIn(0f, 1f)
        this.mouthCenterX = mouthCenterX.coerceIn(0f, 1f)
        this.mouthCenterY = mouthCenterY.coerceIn(0f, 1f)
        this.mouthLeftX = mouthLeftX.coerceIn(0f, 1f)
        this.mouthLeftY = mouthLeftY.coerceIn(0f, 1f)
        this.mouthRightX = mouthRightX.coerceIn(0f, 1f)
        this.mouthRightY = mouthRightY.coerceIn(0f, 1f)
        this.upperLipCenterX = upperLipCenterX.coerceIn(0f, 1f)
        this.upperLipCenterY = upperLipCenterY.coerceIn(0f, 1f)
        this.lowerLipCenterX = lowerLipCenterX.coerceIn(0f, 1f)
        this.lowerLipCenterY = lowerLipCenterY.coerceIn(0f, 1f)
        this.faceRadius = faceRadius.coerceIn(0.08f, 0.45f)
        this.hasFace = if (hasFace) 1f else 0f
    }

    fun setColorGradeParams(
        exposure: Float = 0.0f,
        contrast: Float = 1.0f,
        saturation: Float = 1.0f,
        temperature: Float = 0.0f,
        tint: Float = 0.0f,
        brightness: Float = 0.0f,
        redAdj: Float = 1.0f,
        greenAdj: Float = 1.0f,
        blueAdj: Float = 1.0f
    ) {
        exposureStrength = exposure.coerceIn(-10f, 10f)
        contrastStrength = contrast.coerceIn(0f, 4f)
        saturationStrength = saturation.coerceIn(0f, 2f)
        temperatureStrength = temperature.coerceIn(-1f, 1f)
        tintStrength = tint.coerceIn(-1f, 1f)
        brightnessStrength = brightness.coerceIn(-1f, 1f)
        redAdjustment = redAdj.coerceIn(0f, 2f)
        greenAdjustment = greenAdj.coerceIn(0f, 2f)
        blueAdjustment = blueAdj.coerceIn(0f, 2f)
    }

    /**
     * GPUPixel 风格轮廓瘦脸强度设置。
     * @param strength 0.0~1.0，0.0 为关闭
     */
    fun setContourThinFace(strength: Float) {
        contourThinFaceStrength = strength.coerceIn(0f, 1f)
    }

    fun updateLipMaskPoints(
        outerPoints: List<Pair<Float, Float>>,
        innerPoints: List<Pair<Float, Float>>
    ) {
        lipOuterContourCount = outerPoints.size.coerceIn(0, MAX_LIP_CONTOUR_POINTS)
        lipInnerContourCount = innerPoints.size.coerceIn(0, MAX_LIP_CONTOUR_POINTS)

        for (index in 0 until MAX_LIP_CONTOUR_POINTS) {
            val base = index * 2
            if (index < lipOuterContourCount) {
                val point = outerPoints[index]
                lipOuterContourBuffer[base] = point.first.coerceIn(0f, 1f)
                lipOuterContourBuffer[base + 1] = point.second.coerceIn(0f, 1f)
            } else {
                lipOuterContourBuffer[base] = 0f
                lipOuterContourBuffer[base + 1] = 0f
            }

            if (index < lipInnerContourCount) {
                val point = innerPoints[index]
                lipInnerContourBuffer[base] = point.first.coerceIn(0f, 1f)
                lipInnerContourBuffer[base + 1] = point.second.coerceIn(0f, 1f)
            } else {
                lipInnerContourBuffer[base] = 0f
                lipInnerContourBuffer[base + 1] = 0f
            }
        }
    }

    fun updateCheekContourPoints(
        leftCheekPoints: List<Pair<Float, Float>>,
        rightCheekPoints: List<Pair<Float, Float>>
    ) {
        leftCheekContourCount = leftCheekPoints.size.coerceIn(0, MAX_LIP_CONTOUR_POINTS)
        rightCheekContourCount = rightCheekPoints.size.coerceIn(0, MAX_LIP_CONTOUR_POINTS)

        for (index in 0 until MAX_LIP_CONTOUR_POINTS) {
            val base = index * 2
            if (index < leftCheekContourCount) {
                val point = leftCheekPoints[index]
                leftCheekContourBuffer[base] = point.first.coerceIn(0f, 1f)
                leftCheekContourBuffer[base + 1] = point.second.coerceIn(0f, 1f)
            } else {
                leftCheekContourBuffer[base] = 0f
                leftCheekContourBuffer[base + 1] = 0f
            }

            if (index < rightCheekContourCount) {
                val point = rightCheekPoints[index]
                rightCheekContourBuffer[base] = point.first.coerceIn(0f, 1f)
                rightCheekContourBuffer[base + 1] = point.second.coerceIn(0f, 1f)
            } else {
                rightCheekContourBuffer[base] = 0f
                rightCheekContourBuffer[base + 1] = 0f
            }
        }
    }

    fun setTexelSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            texelSizeX = 1.0f / width
            texelSizeY = 1.0f / height
            viewportWidth = width
            viewportHeight = height
        }
    }

    /**
     * 计算 textureMatrix 的逆矩阵，用于将变换后的坐标还原到标准UV
     */
    private fun getInverseTextureMatrix(): FloatArray {
        val inverse = FloatArray(16)
        val result = Matrix.invertM(inverse, 0, textureMatrix, 0)
        if (!result) {
            Log.w(TAG, "Failed to invert textureMatrix, using identity")
            Matrix.setIdentityM(inverse, 0)
        }
        return inverse
    }

    /**
     * 使用逆矩阵将变换后的坐标还原到标准UV
     */
    private fun inverseTransformVec2(x: Float, y: Float): Pair<Float, Float> {
        val inverseMatrix = getInverseTextureMatrix()
        val vec = floatArrayOf(x, y, 0f, 1f)
        val result = FloatArray(4)
        Matrix.multiplyMV(result, 0, inverseMatrix, 0, vec, 0)
        return Pair(result[0].coerceIn(0f, 1f), result[1].coerceIn(0f, 1f))
    }

    /**
     * 将轮廓点数组通过逆矩阵还原到标准UV坐标系
     */
    private fun inverseTransformContour(buffer: FloatArray, count: Int) {
        val inverseMatrix = getInverseTextureMatrix()
        for (i in 0 until count) {
            val base = i * 2
            val vec = floatArrayOf(buffer[base], buffer[base + 1], 0f, 1f)
            val result = FloatArray(4)
            Matrix.multiplyMV(result, 0, inverseMatrix, 0, vec, 0)
            buffer[base] = result[0].coerceIn(0f, 1f)
            buffer[base + 1] = result[1].coerceIn(0f, 1f)
        }
    }

    /**
     * 将106点人脸关键点通过逆矩阵还原到标准UV坐标系
     */
    private fun inverseTransformFacePoints() {
        val inverseMatrix = getInverseTextureMatrix()
        for (i in 0 until 106) {
            val base = i * 2
            val vec = floatArrayOf(facePointsBuffer[base], facePointsBuffer[base + 1], 0f, 1f)
            val result = FloatArray(4)
            Matrix.multiplyMV(result, 0, inverseMatrix, 0, vec, 0)
            facePointsBuffer[base] = result[0].coerceIn(0f, 1f)
            facePointsBuffer[base + 1] = result[1].coerceIn(0f, 1f)
        }
    }

    /**
     * 更新106点人脸关键点（GPUPixel风格瘦脸/大眼使用）
     * @param landmarks106 FloatArray(212) = [x0,y0, x1,y1, ..., x105,y105]
     */
    fun updateFacePoints106(landmarks106: FloatArray?) {
        if (landmarks106 == null || landmarks106.isEmpty()) {
            hasFace = 0f
            return
        }
        val count = minOf(landmarks106.size, facePointsBuffer.size)
        System.arraycopy(landmarks106, 0, facePointsBuffer, 0, count)
        hasFace = 1f
    }

    /**
     * 设置是否使用GPUPixel风格warp（基于106点关键点）
     * @param enabled true=使用GPUPixel风格, false=使用原有简单warp
     */
    fun setUseGpupixelWarp(enabled: Boolean) {
        useGpupixelWarp = if (enabled) 1 else 0
    }

    fun updateAdvancedParams(warmth: Float, contrast: Float) {
        warmthStrength = warmth.coerceIn(0f, 1f)
        this.contrast = contrast.coerceIn(0.5f, 1.5f)
    }

    /**
     * 更新色调滤镜矩阵。
     * @param matrix Android ColorMatrix.values（20个float，4行×5列，行主序）；null 表示无滤镜
     */
    fun updateColorMatrix(matrix: FloatArray?) {
        colorMatrix = matrix
    }

    override fun onCompileShader(): Boolean {
        val vertexShader = BeautyShaders.VERTEX_SHADER
        val fragmentShader = ShaderModuleLoader.loadFullFragmentShader(context)
        Log.d(TAG, "Compiling shader (modular): mode=$renderMode")
        return shaderProgram.compile(vertexShader, fragmentShader)
    }

    // ========== Phase 2: 风格特效多 Pass 支持 ==========

    /**
     * 设置风格特效类型
     */
    fun setStyleEffect(effect: StyleEffect) {
        styleEffectShader.setStyleEffect(effect)
    }

    /**
     * 设置风格特效参数
     */
    fun setStyleParams(
        intensity: Float = 1f,
        toonThreshold: Float = 0.2f,
        toonQuantizationLevels: Float = 10f,
        sketchEdgeStrength: Float = 1f,
        posterizeColorLevels: Float = 10f,
        embossIntensity: Float = 1f,
        crosshatchSpacing: Float = 0.03f,
        crosshatchLineWidth: Float = 0.003f
    ) {
        styleEffectShader.setToonParams(toonThreshold, toonQuantizationLevels)
        styleEffectShader.setSketchParams(sketchEdgeStrength)
        styleEffectShader.setPosterizeParams(posterizeColorLevels)
        styleEffectShader.setEmbossParams(embossIntensity)
        styleEffectShader.setCrosshatchParams(crosshatchSpacing, crosshatchLineWidth)
    }

    private fun updateFramebufferSize(width: Int, height: Int) {
        if (width != fboWidth || height != fboHeight || intermediateFbo?.isInitialized != true) {
            intermediateFbo?.release()
            val newFbo = Framebuffer(width, height)
            if (!newFbo.initialize()) {
                Log.e(TAG, "Failed to initialize framebuffer: ${width}x${height}")
                intermediateFbo = null
                fboWidth = 0
                fboHeight = 0
                return
            }
            intermediateFbo = newFbo
            fboWidth = width
            fboHeight = height
            styleEffectShader.setRenderSize(width, height)
            Log.d(TAG, "Framebuffer updated: ${width}x${height}")
        }
    }

    override fun onRender() {
        val activeStyle = styleEffectShader.getActiveStyle()

        // 如果启用了多Pass美颜且需要磨皮/美白，使用多Pass管线
        if (multiPassBeautyEnabled && (smoothingStrength > 0.001 || whiteningStrength > 0.001)) {
            renderBeautyMultiPass(activeStyle)
            return
        }

        // 原有逻辑：单Pass美颜
        if (activeStyle == StyleEffect.NONE) {
            super.onRender()
            return
        }
        renderMultiPass(activeStyle)
    }

    /**
     * 多Pass美颜 + 风格特效渲染
     */
    private fun renderBeautyMultiPass(activeStyle: StyleEffect) {
        val viewportArray = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewportArray, 0)
        val outputWidth = viewportArray[2]
        val outputHeight = viewportArray[3]

        // 步骤1: 执行磨皮/美白 Pass
        Log.d(TAG, "renderBeautyMultiPass: calling executeBeautyPasses")
        val beautyPassSuccess = executeBeautyPasses()
        Log.d(TAG, "renderBeautyMultiPass: executeBeautyPasses returned $beautyPassSuccess, beautyPassOutputTextureId=$beautyPassOutputTextureId")

        if (!beautyPassSuccess) {
            // 多Pass失败，回退到单Pass
            if (activeStyle == StyleEffect.NONE) {
                super.onRender()
            } else {
                renderMultiPass(activeStyle)
            }
            return
        }

        // 步骤2: 使用主Shader渲染美型+美妆+调色
        // 主Shader从 beautyPassOutputTextureId（FBO纹理）采样
        if (activeStyle != StyleEffect.NONE) {
            // 有风格特效：先渲染到 intermediateFbo
            updateFramebufferSize(outputWidth, outputHeight)
            val fbo = intermediateFbo
            if (fbo != null && fbo.isInitialized) {
                renderMainShaderFromFbo(beautyPassOutputTextureId, fbo, outputWidth, outputHeight)
                fbo.unbind()

                // 风格特效 -> 屏幕
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(viewportArray[0], viewportArray[1], outputWidth, outputHeight)
                GLES20.glDisableVertexAttribArray(aPositionLocation)
                GLES20.glDisableVertexAttribArray(aTextureCoordLocation)
                styleEffectShader.render(fbo.getTextureId(), outputWidth, outputHeight)
            } else {
                renderMainShaderFromFbo(beautyPassOutputTextureId, null, outputWidth, outputHeight)
            }
        } else {
            // 无风格特效：直接渲染到屏幕
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(viewportArray[0], viewportArray[1], outputWidth, outputHeight)
            renderMainShaderFromFbo(beautyPassOutputTextureId, null, outputWidth, outputHeight)
        }
    }

    /**
     * 使用主Shader渲染，但输入纹理来自 FBO（而非外部纹理）
     */
    // 2D 纹理版本的主 Shader（用于多Pass后从FBO采样）
    private val shaderProgram2D by lazy { ShaderProgram() }
    private var shaderProgram2DCompiled = false

    private fun compileShaderProgram2D(): Boolean {
        // 强制重新编译（确保使用最新的 uniforms_2d.glsl）
        if (shaderProgram2DCompiled) {
            shaderProgram2D.release()
            shaderProgram2DCompiled = false
        }
        val vertexSource = context.assets.open(ShaderModuleLoader.VERTEX_SHADER_2D_PATH).bufferedReader().use { it.readText() }
        val fragmentSource = ShaderModuleLoader.loadFullFragmentShader2D(context)
        shaderProgram2DCompiled = shaderProgram2D.compile(vertexSource, fragmentSource)
        Log.d(TAG, "ShaderProgram2D compiled: $shaderProgram2DCompiled")
        return shaderProgram2DCompiled
    }

    private fun renderMainShaderFromFbo(
        inputTextureId: Int,
        outputFbo: Framebuffer? = null,
        viewportWidth: Int = 0,
        viewportHeight: Int = 0
    ) {
        // 编译 2D Shader（如果未编译）
        if (!compileShaderProgram2D()) {
            Log.e(TAG, "Failed to compile ShaderProgram2D")
            return
        }

        // 绑定输出 FBO（如果有）
        if (outputFbo != null) {
            outputFbo.bind()
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        // 使用 2D 主 Shader 渲染
        shaderProgram2D.use()

        // 绑定 FBO 纹理到 TEXTURE0（替代外部纹理）
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        val uTexLoc = shaderProgram2D.getUniformLocation("uTexture")
        if (uTexLoc >= 0) GLES20.glUniform1i(uTexLoc, 0)

        // 设置所有 uniform（复制 onBeforeRender 的逻辑）
        shaderProgram2D.setVec2("uTexelSize", texelSizeX, texelSizeY)
        // 多Pass已执行磨皮/美白，主Shader不再重复处理
        shaderProgram2D.setFloat("uSmoothing", 0.0f)
        shaderProgram2D.setFloat("uWhitening", 0.0f)
        shaderProgram2D.setFloat("uSharpen", 0.0f)
        shaderProgram2D.setFloat("uBigEyes", bigEyesStrength)
        shaderProgram2D.setFloat("uSlimFace", slimFaceStrength)
        shaderProgram2D.setFloat("uFaceRadius", faceRadius)
        shaderProgram2D.setFloat("uHasFace", hasFace)
        shaderProgram2D.setFloat("uLipColor", lipColorStrength)
        shaderProgram2D.setInt("uLipColorIndex", lipColorIndex)
        shaderProgram2D.setFloat("uBlush", blushStrength)
        shaderProgram2D.setInt("uBlushColorFamily", blushColorFamily)
        // 多Pass模式下：vWarpCoord和vTextureCoord都是标准UV
        // 人脸关键点需要通过逆矩阵还原到标准UV坐标系
        val invFaceCenter = inverseTransformVec2(faceCenterX, faceCenterY)
        val invMouthCenter = inverseTransformVec2(mouthCenterX, mouthCenterY)
        val invMouthLeft = inverseTransformVec2(mouthLeftX, mouthLeftY)
        val invMouthRight = inverseTransformVec2(mouthRightX, mouthRightY)
        val invUpperLip = inverseTransformVec2(upperLipCenterX, upperLipCenterY)
        val invLowerLip = inverseTransformVec2(lowerLipCenterX, lowerLipCenterY)
        val invLeftEye = inverseTransformVec2(leftEyeX, leftEyeY)
        val invRightEye = inverseTransformVec2(rightEyeX, rightEyeY)
        shaderProgram2D.setVec2("uFaceCenter", invFaceCenter.first, invFaceCenter.second)
        shaderProgram2D.setVec2("uMouthCenter", invMouthCenter.first, invMouthCenter.second)
        shaderProgram2D.setVec2("uMouthLeft", invMouthLeft.first, invMouthLeft.second)
        shaderProgram2D.setVec2("uMouthRight", invMouthRight.first, invMouthRight.second)
        shaderProgram2D.setVec2("uUpperLipCenter", invUpperLip.first, invUpperLip.second)
        shaderProgram2D.setVec2("uLowerLipCenter", invLowerLip.first, invLowerLip.second)
        // 轮廓点也需要逆变换
        val invLipOuter = lipOuterContourBuffer.clone()
        val invLipInner = lipInnerContourBuffer.clone()
        val invLeftCheek = leftCheekContourBuffer.clone()
        val invRightCheek = rightCheekContourBuffer.clone()
        inverseTransformContour(invLipOuter, lipOuterContourCount)
        inverseTransformContour(invLipInner, lipInnerContourCount)
        inverseTransformContour(invLeftCheek, leftCheekContourCount)
        inverseTransformContour(invRightCheek, rightCheekContourCount)
        shaderProgram2D.setFloat("uLipOuterContourCount", lipOuterContourCount.toFloat())
        shaderProgram2D.setVec2Array("uLipOuterContourPoints", invLipOuter, MAX_LIP_CONTOUR_POINTS)
        shaderProgram2D.setFloat("uLipInnerContourCount", lipInnerContourCount.toFloat())
        shaderProgram2D.setVec2Array("uLipInnerContourPoints", invLipInner, MAX_LIP_CONTOUR_POINTS)
        shaderProgram2D.setVec2("uLeftEye", invLeftEye.first, invLeftEye.second)
        shaderProgram2D.setVec2("uRightEye", invRightEye.first, invRightEye.second)
        shaderProgram2D.setFloat("uLeftCheekContourCount", leftCheekContourCount.toFloat())
        shaderProgram2D.setVec2Array("uLeftCheekContourPoints", invLeftCheek, MAX_LIP_CONTOUR_POINTS)
        shaderProgram2D.setFloat("uRightCheekContourCount", rightCheekContourCount.toFloat())
        shaderProgram2D.setVec2Array("uRightCheekContourPoints", invRightCheek, MAX_LIP_CONTOUR_POINTS)

        if (renderMode == MODE_ADVANCED) {
            shaderProgram2D.setFloat("uWarmth", warmthStrength)
            shaderProgram2D.setFloat("uContrast", contrast)
        }

        val cm = colorMatrix
        if (cm != null && cm.size >= 20) {
            shaderProgram2D.setFloat("uHasColorMatrix", 1f)
            shaderProgram2D.setVec4("uCMRow0", cm[0], cm[1], cm[2], cm[3])
            shaderProgram2D.setVec4("uCMRow1", cm[5], cm[6], cm[7], cm[8])
            shaderProgram2D.setVec4("uCMRow2", cm[10], cm[11], cm[12], cm[13])
            shaderProgram2D.setVec4("uCMRow3", cm[15], cm[16], cm[17], cm[18])
            shaderProgram2D.setVec4("uCMOffset", cm[4] / 255f, cm[9] / 255f, cm[14] / 255f, cm[19] / 255f)
        } else {
            shaderProgram2D.setFloat("uHasColorMatrix", 0f)
        }

        shaderProgram2D.setInt("uDebugMode", debugMode)

        val uExpLoc = shaderProgram2D.getUniformLocation("uExposure")
        if (uExpLoc >= 0) GLES20.glUniform1f(uExpLoc, exposureStrength)
        val uContLoc = shaderProgram2D.getUniformLocation("uContrast")
        if (uContLoc >= 0) GLES20.glUniform1f(uContLoc, contrastStrength)
        val uSatLoc = shaderProgram2D.getUniformLocation("uSaturation")
        if (uSatLoc >= 0) GLES20.glUniform1f(uSatLoc, saturationStrength)
        val uTempLoc = shaderProgram2D.getUniformLocation("uTemperature")
        if (uTempLoc >= 0) GLES20.glUniform1f(uTempLoc, temperatureStrength)
        val uTintLoc = shaderProgram2D.getUniformLocation("uTint")
        if (uTintLoc >= 0) GLES20.glUniform1f(uTintLoc, tintStrength)
        val uBrightLoc = shaderProgram2D.getUniformLocation("uBrightness")
        if (uBrightLoc >= 0) GLES20.glUniform1f(uBrightLoc, brightnessStrength)
        val uRedLoc = shaderProgram2D.getUniformLocation("uRedAdj")
        if (uRedLoc >= 0) GLES20.glUniform1f(uRedLoc, redAdjustment)
        val uGreenLoc = shaderProgram2D.getUniformLocation("uGreenAdj")
        if (uGreenLoc >= 0) GLES20.glUniform1f(uGreenLoc, greenAdjustment)
        val uBlueLoc = shaderProgram2D.getUniformLocation("uBlueAdj")
        if (uBlueLoc >= 0) GLES20.glUniform1f(uBlueLoc, blueAdjustment)
        val uContourLoc = shaderProgram2D.getUniformLocation("uContourThinFace")
        if (uContourLoc >= 0) GLES20.glUniform1f(uContourLoc, contourThinFaceStrength)

        // 多Pass模式下：vWarpCoord是标准UV，人脸关键点也需要逆变换到标准UV
        val uFacePtsLoc = shaderProgram2D.getUniformLocation("uFacePoints")
        if (uFacePtsLoc >= 0 && hasFace > 0.5f) {
            // 复制并逆变换106点
            val invFacePoints = facePointsBuffer.clone()
            val inverseMatrix = getInverseTextureMatrix()
            for (i in 0 until 106) {
                val base = i * 2
                val vec = floatArrayOf(invFacePoints[base], invFacePoints[base + 1], 0f, 1f)
                val result = FloatArray(4)
                Matrix.multiplyMV(result, 0, inverseMatrix, 0, vec, 0)
                invFacePoints[base] = result[0].coerceIn(0f, 1f)
                invFacePoints[base + 1] = result[1].coerceIn(0f, 1f)
            }
            GLES20.glUniform1fv(uFacePtsLoc, invFacePoints.size, invFacePoints, 0)
        }
        val uAspectLoc = shaderProgram2D.getUniformLocation("uAspectRatio")
        if (uAspectLoc >= 0) {
            // 使用传入的 viewport 尺寸计算 aspect ratio（避免 FBO bind 后 viewport 被修改）
            val aspectW = if (viewportWidth > 0) viewportWidth else {
                val viewportArray = IntArray(4)
                GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewportArray, 0)
                viewportArray[2]
            }
            val aspectH = if (viewportHeight > 0) viewportHeight else {
                val viewportArray = IntArray(4)
                GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewportArray, 0)
                viewportArray[3]
            }
            if (aspectH > 0) {
                val aspect = aspectW.toFloat() / aspectH.toFloat()
                GLES20.glUniform1f(uAspectLoc, aspect)
                Log.d(TAG, "MainShader2D uAspectRatio: $aspect (${aspectW}x${aspectH})")
            }
        }
        val uUseWarpLoc = shaderProgram2D.getUniformLocation("uUseGpupixelWarp")
        if (uUseWarpLoc >= 0) GLES20.glUniform1i(uUseWarpLoc, useGpupixelWarp)

        // 设置顶点数据（CopyPass已应用uTextureTransform，FBO图像方向正确，使用标准UV）
        val vb = vertexBuffer
        val tb = textureBuffer
        if (vb != null && tb != null) {
            val aPosLoc = shaderProgram2D.getAttribLocation("aPosition")
            val aTexLoc = shaderProgram2D.getAttribLocation("aTextureCoord")
            if (aPosLoc >= 0) {
                GLES20.glEnableVertexAttribArray(aPosLoc)
                vb.position(0)
                GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vb)
            }
            if (aTexLoc >= 0) {
                GLES20.glEnableVertexAttribArray(aTexLoc)
                tb.position(0)
                GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, tb)
            }
        }

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 清理
        if (vb != null) {
            val aPosLoc = shaderProgram2D.getAttribLocation("aPosition")
            if (aPosLoc >= 0) GLES20.glDisableVertexAttribArray(aPosLoc)
        }
        if (tb != null) {
            val aTexLoc = shaderProgram2D.getAttribLocation("aTextureCoord")
            if (aTexLoc >= 0) GLES20.glDisableVertexAttribArray(aTexLoc)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        if (outputFbo != null) {
            outputFbo.unbind()
        }

        Log.d(TAG, "MainShader2D rendered from FBO texture: $inputTextureId")
    }

    /**
     * 多Pass美颜渲染管线
     *
     * 在 onBeforeRender 中执行磨皮/美白 Pass，将相机纹理处理后的结果
     * 保存到 ping-pong FBO。然后主 Shader 从 FBO 纹理采样（而非外部纹理），
     * 执行美型+美妆+调色。
     *
     * Pass 0: CopyPass (OES外部纹理 -> FBO 2D纹理)
     * Pass 1: BoxBlur (原图 -> 均值图)
     * Pass 2: BoxHighPass (原图+均值图 -> 方差图)
     * Pass 3: BeautyFaceUnit (原图+均值图+方差图+LUT -> 磨皮+美白)
     * Pass 4: 主Shader (美型+美妆+调色 -> 屏幕/FBO)
     */
    private var beautyPassOutputTextureId: Int = 0
    // 标记多Pass是否真正执行了磨皮/美白（影响主Shader是否回退到单Pass逻辑）
    private var beautyPassExecutedSmoothing: Boolean = false
    private var beautyPassExecutedWhitening: Boolean = false

    /**
     * 在 onBeforeRender 中执行磨皮/美白多Pass
     * 返回 true 表示需要修改主Shader的纹理绑定
     *
     * 管线（简化版）：
     * Pass 0: CopyPass (OES外部纹理 -> FBO_ping 2D纹理)
     * Pass 1: BeautyPass (磨皮+美白+LUT 合并，FBO_ping -> FBO_pong)
     */
    private fun executeBeautyPasses(): Boolean {
        Log.d(TAG, "executeBeautyPasses: START, multiPass=$multiPassBeautyEnabled, smooth=$smoothingStrength, white=$whiteningStrength")
        if (!multiPassBeautyEnabled) {
            Log.d(TAG, "Multi-pass disabled")
            return false
        }
        if (smoothingStrength < 0.001 && whiteningStrength < 0.001) {
            Log.d(TAG, "Multi-pass: smoothing and whitening both near zero")
            return false
        }

        val viewportArray = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewportArray, 0)
        val outputWidth = viewportArray[2]
        val outputHeight = viewportArray[3]
        Log.d(TAG, "executeBeautyPasses: viewport=${outputWidth}x${outputHeight}")

        if (outputWidth <= 0 || outputHeight <= 0) {
            Log.w(TAG, "executeBeautyPasses: invalid viewport size")
            return false
        }

        Log.d(TAG, "executeBeautyPasses: viewport check passed")

        // 编译独立 Pass Shader（延迟编译）
        Log.d(TAG, "executeBeautyPasses: compiling shaders...")
        if (!copyPassCompiled) {
            try {
                copyPassCompiled = copyPass.compileFromAssets(
                    "shaders/pass_vertex.glsl", "shaders/pass_copy.glsl"
                )
                Log.d(TAG, "CopyPass compiled: $copyPassCompiled")
                if (!copyPassCompiled) {
                    Log.e(TAG, "CopyPass compilation failed, aborting multi-pass")
                    return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "CopyPass compilation exception: ${e.message}", e)
                return false
            }
        }
        if (!beautyUnitPassCompiled) {
            beautyUnitPassCompiled = beautyUnitPass.compileFromAssets(
                "shaders/pass_vertex.glsl", "shaders/pass_smoothing.glsl"
            )
            Log.d(TAG, "BeautyUnitPass compiled: $beautyUnitPassCompiled")
        }

        // 加载 LUT 纹理
        if (!lutTextureLoader.isAllLoaded()) {
            val lutLoaded = lutTextureLoader.loadAllFallback()
            Log.d(TAG, "LUT textures loaded: $lutLoaded")
        }

        // 确保 FBO 池已初始化（需要2个FBO：ping/pong）
        val fboPing = fboPool.acquire("ping", outputWidth, outputHeight)
        val fboPong = fboPool.acquire("pong", outputWidth, outputHeight)
        Log.d(TAG, "executeBeautyPasses: FBOs initialized: ping=${fboPing.isInitialized}, pong=${fboPong.isInitialized}")
        if (!fboPing.isInitialized || !fboPong.isInitialized) {
            Log.w(TAG, "FBO pool not ready")
            return false
        }

        // 获取外部纹理 ID
        val cameraTextureId = getBoundExternalTextureId()
        Log.d(TAG, "executeBeautyPasses: cameraTextureId=$cameraTextureId")
        if (cameraTextureId == 0) {
            Log.w(TAG, "Camera texture ID is 0, multi-pass will use fallback")
            return false
        }
        Log.d(TAG, "Multi-pass: cameraTex=$cameraTextureId, smooth=$smoothingStrength, white=$whiteningStrength")

        var currentInputTexture = cameraTextureId
        var currentOutputFbo = fboPing

        // Pass 0: 将 OES 外部纹理复制到 2D FBO 纹理
        if (copyPassCompiled) {
            val copyProgram = copyPass.getShaderProgram()
            Log.d(TAG, "Pass0 CopyPass: input=$currentInputTexture, outputFbo=${currentOutputFbo.getTextureId()}")
            copyPass.render(
                inputTextureId = currentInputTexture,
                outputFbo = currentOutputFbo,
                textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                setupUniforms = {
                    val loc = copyProgram.getUniformLocation("uCameraTexture")
                    if (loc >= 0) GLES20.glUniform1i(loc, 0)
                    val transformLoc = copyProgram.getUniformLocation("uTextureTransform")
                    if (transformLoc >= 0) {
                        GLES20.glUniformMatrix4fv(transformLoc, 1, false, textureMatrix, 0)
                    }
                }
            )
            currentInputTexture = currentOutputFbo.getTextureId()
            currentOutputFbo = if (currentOutputFbo === fboPing) fboPong else fboPing
            Log.d(TAG, "Pass0 done: outputTex=$currentInputTexture")
        }

        val originalTexture = currentInputTexture  // 保存原图纹理ID

        // 确保 Pass 的输入和输出不使用同一个 FBO
        if (currentOutputFbo.getTextureId() == originalTexture) {
            currentOutputFbo = if (currentOutputFbo === fboPing) fboPong else fboPing
            Log.d(TAG, "Switched outputFbo to avoid read-write conflict: ${currentOutputFbo.getTextureId()}")
        }

        // Pass 1: BeautyPass -> 磨皮+美白+LUT（合并Pass，无需BoxBlur/BoxHighPass）
        if (beautyUnitPassCompiled && lutTextureLoader.isAllLoaded()) {
            val unitProgram = beautyUnitPass.getShaderProgram()
            beautyUnitPass.render(
                inputTextureId = originalTexture,
                outputFbo = currentOutputFbo,
                setupUniforms = {
                    // 绑定 LUT 纹理
                    val grayLoc = unitProgram.getUniformLocation("uLookUpGray")
                    if (grayLoc >= 0) {
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureLoader.getGrayTextureId())
                        GLES20.glUniform1i(grayLoc, 1)
                    }
                    val originLoc = unitProgram.getUniformLocation("uLookUpOrigin")
                    if (originLoc >= 0) {
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureLoader.getOriginTextureId())
                        GLES20.glUniform1i(originLoc, 2)
                    }
                    val skinLoc = unitProgram.getUniformLocation("uLookUpSkin")
                    if (skinLoc >= 0) {
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureLoader.getSkinTextureId())
                        GLES20.glUniform1i(skinLoc, 3)
                    }
                    val lightLoc = unitProgram.getUniformLocation("uLookUpLight")
                    if (lightLoc >= 0) {
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureLoader.getLightTextureId())
                        GLES20.glUniform1i(lightLoc, 4)
                    }
                    // 设置参数（磨皮和美白同时生效）
                    setFloat("uBlurAlpha", smoothingStrength)
                    setFloat("uSharpen", 0.0f)
                    setFloat("uWhiten", whiteningStrength)
                    setFloat("uWidthOffset", texelSizeX)
                    setFloat("uHeightOffset", texelSizeY)
                }
            )
            currentInputTexture = currentOutputFbo.getTextureId()
            currentOutputFbo = if (currentOutputFbo === fboPing) fboPong else fboPing
            // 标记多Pass执行状态
            beautyPassExecutedSmoothing = smoothingStrength > 0.001f
            beautyPassExecutedWhitening = whiteningStrength > 0.001f
            Log.d(TAG, "BeautyUnitPass executed: smoothing=$beautyPassExecutedSmoothing, whitening=$beautyPassExecutedWhitening")
        } else {
            Log.w(TAG, "BeautyUnitPass skipped: compiled=$beautyUnitPassCompiled, lutLoaded=${lutTextureLoader.isAllLoaded()}")
        }

        // 保存最终输出纹理 ID
        beautyPassOutputTextureId = currentInputTexture
        Log.d(TAG, "executeBeautyPasses DONE: outputTextureId=$beautyPassOutputTextureId, fboPing=${fboPing.getTextureId()}, fboPong=${fboPong.getTextureId()}")
        return true
    }

    /**
     * 外部纹理 ID（由 CameraPreviewRenderer 在渲染前绑定到 GL_TEXTURE0 + GL_TEXTURE_EXTERNAL_OES）
     * 不再查询 GL 状态，而是直接使用已知的外部纹理 ID
     */
    private var externalTextureId: Int = 0

    fun setExternalTextureId(id: Int) {
        externalTextureId = id
    }

    private fun getBoundExternalTextureId(): Int {
        return externalTextureId
    }

    /**
     * 设置是否启用多Pass美颜
     */
    fun setMultiPassBeautyEnabled(enabled: Boolean) {
        multiPassBeautyEnabled = enabled
        Log.d(TAG, "Multi-pass beauty enabled: $enabled")
    }

    private fun renderMultiPass(activeStyle: StyleEffect) {
        // 获取当前 viewport 尺寸
        val viewportArray = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewportArray, 0)
        val outputWidth = viewportArray[2]
        val outputHeight = viewportArray[3]

        updateFramebufferSize(outputWidth, outputHeight)

        val fbo = intermediateFbo
        if (fbo == null || !fbo.isInitialized) {
            Log.w(TAG, "FBO not ready, falling back to single pass")
            super.onRender()
            return
        }

        // Pass 1: 美颜 + 调色 -> FBO
        fbo.bind()
        super.onRender()
        fbo.unbind()

        // 恢复 viewport 和默认 framebuffer
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(viewportArray[0], viewportArray[1], outputWidth, outputHeight)

        // Pass 2: 风格特效 -> 屏幕
        // 禁用 Pass 1 可能遗留的 vertex attrib array，避免干扰 Pass 2
        GLES20.glDisableVertexAttribArray(aPositionLocation)
        GLES20.glDisableVertexAttribArray(aTextureCoordLocation)

        styleEffectShader.render(fbo.getTextureId(), outputWidth, outputHeight)
    }

    // ========== Phase 2 结束 ==========

    override fun onBeforeRender() {
        super.onBeforeRender()
        initUniformLocations()

        if (renderFrameCount % 60 == 0L) {
            Log.d(
                TAG,
                "onBeforeRender: hasFace=$hasFace, slimFace=$slimFaceStrength, " +
                    "bigEyes=$bigEyesStrength, center=($faceCenterX, $faceCenterY)"
            )
        }
        renderFrameCount++

        when (renderMode) {
            MODE_BEAUTY, MODE_ADVANCED -> {
                shaderProgram.setVec2("uTexelSize", texelSizeX, texelSizeY)
                shaderProgram.setFloat("uSmoothing", smoothingStrength)
                shaderProgram.setFloat("uWhitening", whiteningStrength)
                shaderProgram.setFloat("uSharpen", sharpenStrength)
                shaderProgram.setFloat("uBigEyes", bigEyesStrength)
                shaderProgram.setFloat("uSlimFace", slimFaceStrength)
                shaderProgram.setFloat("uFaceRadius", faceRadius)
                shaderProgram.setFloat("uHasFace", hasFace)
                shaderProgram.setFloat("uLipColor", lipColorStrength)
                shaderProgram.setInt("uLipColorIndex", lipColorIndex)
                shaderProgram.setFloat("uBlush", blushStrength)
                shaderProgram.setInt("uBlushColorFamily", blushColorFamily)
                shaderProgram.setVec2("uFaceCenter", faceCenterX, faceCenterY)
                shaderProgram.setVec2("uMouthCenter", mouthCenterX, mouthCenterY)
                shaderProgram.setVec2("uMouthLeft", mouthLeftX, mouthLeftY)
                shaderProgram.setVec2("uMouthRight", mouthRightX, mouthRightY)
                shaderProgram.setVec2("uUpperLipCenter", upperLipCenterX, upperLipCenterY)
                shaderProgram.setVec2("uLowerLipCenter", lowerLipCenterX, lowerLipCenterY)
                shaderProgram.setFloat("uLipOuterContourCount", lipOuterContourCount.toFloat())
                shaderProgram.setVec2Array(
                    "uLipOuterContourPoints",
                    lipOuterContourBuffer,
                    MAX_LIP_CONTOUR_POINTS
                )
                shaderProgram.setFloat("uLipInnerContourCount", lipInnerContourCount.toFloat())
                shaderProgram.setVec2Array(
                    "uLipInnerContourPoints",
                    lipInnerContourBuffer,
                    MAX_LIP_CONTOUR_POINTS
                )
                shaderProgram.setVec2("uLeftEye", leftEyeX, leftEyeY)
                shaderProgram.setVec2("uRightEye", rightEyeX, rightEyeY)
                shaderProgram.setFloat("uLeftCheekContourCount", leftCheekContourCount.toFloat())
                shaderProgram.setVec2Array(
                    "uLeftCheekContourPoints",
                    leftCheekContourBuffer,
                    MAX_LIP_CONTOUR_POINTS
                )
                shaderProgram.setFloat("uRightCheekContourCount", rightCheekContourCount.toFloat())
                shaderProgram.setVec2Array(
                    "uRightCheekContourPoints",
                    rightCheekContourBuffer,
                    MAX_LIP_CONTOUR_POINTS
                )

                if (renderMode == MODE_ADVANCED) {
                    shaderProgram.setFloat("uWarmth", warmthStrength)
                    shaderProgram.setFloat("uContrast", contrast)
                }

                val cm = colorMatrix
                if (cm != null && cm.size >= 20) {
                    shaderProgram.setFloat("uHasColorMatrix", 1f)
                    shaderProgram.setVec4("uCMRow0", cm[0], cm[1], cm[2], cm[3])
                    shaderProgram.setVec4("uCMRow1", cm[5], cm[6], cm[7], cm[8])
                    shaderProgram.setVec4("uCMRow2", cm[10], cm[11], cm[12], cm[13])
                    shaderProgram.setVec4("uCMRow3", cm[15], cm[16], cm[17], cm[18])
                    shaderProgram.setVec4(
                        "uCMOffset",
                        cm[4] / 255f, cm[9] / 255f, cm[14] / 255f, cm[19] / 255f
                    )
                } else {
                    shaderProgram.setFloat("uHasColorMatrix", 0f)
                }

                shaderProgram.setInt("uDebugMode", debugMode)

                GLES20.glUniform1f(uExposureLocation, exposureStrength)
                GLES20.glUniform1f(uContrastLocation, contrastStrength)
                GLES20.glUniform1f(uSaturationLocation, saturationStrength)
                GLES20.glUniform1f(uTemperatureLocation, temperatureStrength)
                GLES20.glUniform1f(uTintLocation, tintStrength)
                GLES20.glUniform1f(uBrightnessLocation, brightnessStrength)
                GLES20.glUniform1f(uRedAdjLocation, redAdjustment)
                GLES20.glUniform1f(uGreenAdjLocation, greenAdjustment)
                GLES20.glUniform1f(uBlueAdjLocation, blueAdjustment)
                GLES20.glUniform1f(uContourThinFaceLocation, contourThinFaceStrength)

                // GPUPixel 风格瘦脸/大眼：传递106点关键点和宽高比
                if (uFacePointsLocation >= 0 && hasFace > 0.5f) {
                    GLES20.glUniform1fv(uFacePointsLocation, facePointsBuffer.size, facePointsBuffer, 0)
                }
                if (uAspectRatioLocation >= 0) {
                    // 使用当前实际 viewport 尺寸计算 aspect ratio（与 GPUPixel 原始实现一致）
                    val viewportArray = IntArray(4)
                    GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewportArray, 0)
                    val vpW = viewportArray[2]
                    val vpH = viewportArray[3]
                    if (vpH > 0) {
                        val aspect = vpW.toFloat() / vpH.toFloat()
                        GLES20.glUniform1f(uAspectRatioLocation, aspect)
                    }
                }
                if (uUseGpupixelWarpLocation >= 0) {
                    GLES20.glUniform1i(uUseGpupixelWarpLocation, useGpupixelWarp)
                }
            }
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uTextureLocation, 0)
    }

    private fun initUniformLocations() {
        uSmoothingLocation = shaderProgram.getUniformLocation("uSmoothing")
        uWhiteningLocation = shaderProgram.getUniformLocation("uWhitening")
        uSharpenLocation = shaderProgram.getUniformLocation("uSharpen")
        uBigEyesLocation = shaderProgram.getUniformLocation("uBigEyes")
        uSlimFaceLocation = shaderProgram.getUniformLocation("uSlimFace")
        uFaceCenterLocation = shaderProgram.getUniformLocation("uFaceCenter")
        uLeftEyeLocation = shaderProgram.getUniformLocation("uLeftEye")
        uRightEyeLocation = shaderProgram.getUniformLocation("uRightEye")
        uFaceRadiusLocation = shaderProgram.getUniformLocation("uFaceRadius")
        uHasFaceLocation = shaderProgram.getUniformLocation("uHasFace")
        uMouthCenterLocation = shaderProgram.getUniformLocation("uMouthCenter")
        uMouthLeftLocation = shaderProgram.getUniformLocation("uMouthLeft")
        uMouthRightLocation = shaderProgram.getUniformLocation("uMouthRight")
        uUpperLipCenterLocation = shaderProgram.getUniformLocation("uUpperLipCenter")
        uLowerLipCenterLocation = shaderProgram.getUniformLocation("uLowerLipCenter")
        uLipOuterContourPointsLocation =
            shaderProgram.getUniformLocation("uLipOuterContourPoints")
        uLipOuterContourCountLocation =
            shaderProgram.getUniformLocation("uLipOuterContourCount")
        uLipInnerContourPointsLocation =
            shaderProgram.getUniformLocation("uLipInnerContourPoints")
        uLipInnerContourCountLocation =
            shaderProgram.getUniformLocation("uLipInnerContourCount")
        uLeftCheekContourPointsLocation =
            shaderProgram.getUniformLocation("uLeftCheekContourPoints")
        uLeftCheekContourCountLocation =
            shaderProgram.getUniformLocation("uLeftCheekContourCount")
        uRightCheekContourPointsLocation =
            shaderProgram.getUniformLocation("uRightCheekContourPoints")
        uRightCheekContourCountLocation =
            shaderProgram.getUniformLocation("uRightCheekContourCount")
        uWarmthLocation = shaderProgram.getUniformLocation("uWarmth")
        uContrastLocation = shaderProgram.getUniformLocation("uContrast")
        uLipColorLocation = shaderProgram.getUniformLocation("uLipColor")
        uLipColorIndexLocation = shaderProgram.getUniformLocation("uLipColorIndex")
        uBlushLocation = shaderProgram.getUniformLocation("uBlush")
        uBlushColorFamilyLocation = shaderProgram.getUniformLocation("uBlushColorFamily")
        uCMRow0Location = shaderProgram.getUniformLocation("uCMRow0")
        uCMRow1Location = shaderProgram.getUniformLocation("uCMRow1")
        uCMRow2Location = shaderProgram.getUniformLocation("uCMRow2")
        uCMRow3Location = shaderProgram.getUniformLocation("uCMRow3")
        uCMOffsetLocation = shaderProgram.getUniformLocation("uCMOffset")
        uHasColorMatrixLocation = shaderProgram.getUniformLocation("uHasColorMatrix")
        uExposureLocation = shaderProgram.getUniformLocation("uExposure")
        uSaturationLocation = shaderProgram.getUniformLocation("uSaturation")
        uTemperatureLocation = shaderProgram.getUniformLocation("uTemperature")
        uTintLocation = shaderProgram.getUniformLocation("uTint")
        uBrightnessLocation = shaderProgram.getUniformLocation("uBrightness")
        uRedAdjLocation = shaderProgram.getUniformLocation("uRedAdj")
        uGreenAdjLocation = shaderProgram.getUniformLocation("uGreenAdj")
        uBlueAdjLocation = shaderProgram.getUniformLocation("uBlueAdj")
        uContourThinFaceLocation = shaderProgram.getUniformLocation("uContourThinFace")
        uDebugModeLocation = shaderProgram.getUniformLocation("uDebugMode")
        uFacePointsLocation = shaderProgram.getUniformLocation("uFacePoints")
        uAspectRatioLocation = shaderProgram.getUniformLocation("uAspectRatio")
        uUseGpupixelWarpLocation = shaderProgram.getUniformLocation("uUseGpupixelWarp")
    }

    /**
     * 设置调试模式
     * @param mode 0=正常, 1=Skin Mask, 2=Warp Offset, 
     *             3=BigEye Radius, 4=ThinFace Radius, 5=All Warp
     */
    fun setDebugMode(mode: Int) {
        debugMode = mode
    }

    override fun release() {
        Log.d(TAG, "Releasing BeautyRenderer")
        intermediateFbo?.release()
        intermediateFbo = null
        styleEffectShader.release()
        copyPass.release()
        beautyUnitPass.release()
        lutTextureLoader.release()
        fboPool.releaseAll()
        super.release()
    }
}
