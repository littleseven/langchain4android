package com.picme.beauty.egl

import android.content.Context
import android.opengl.GLES20
import android.util.Log

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

    private var renderFrameCount: Long = 0

    // Phase 2: 风格特效多 Pass 支持
    private val styleEffectShader = StyleEffectShader(context)
    private var intermediateFbo: Framebuffer? = null
    private var fboWidth: Int = 0
    private var fboHeight: Int = 0

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

    private var debugMode: Int = 0

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
        }
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
        if (activeStyle == StyleEffect.NONE) {
            super.onRender()
            return
        }
        renderMultiPass(activeStyle)
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
    }

    /**
     * 设置调试模式
     * @param mode 0=正常, 1=显示 Skin Mask, 2=显示 Warp 偏移, 3=显示唇部区域
     */
    fun setDebugMode(mode: Int) {
        debugMode = mode
    }

    override fun release() {
        Log.d(TAG, "Releasing BeautyRenderer")
        intermediateFbo?.release()
        intermediateFbo = null
        styleEffectShader.release()
        super.release()
    }
}
