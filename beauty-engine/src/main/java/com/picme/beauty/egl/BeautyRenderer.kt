package com.picme.beauty.egl

import android.opengl.GLES20
import android.util.Log

/**
 * R 计划 - 美颜渲染器
 *
 * 功能：
 * 1. 使用美颜 Shader 渲染相机预览帧
 * 2. 支持实时调整磨皮、美白、大眼、瘦脸、唇色、腮红
 */
class BeautyRenderer : GLRenderer() {
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
    private var bigEyesStrength: Float = 0f
    private var slimFaceStrength: Float = 0f
    private var lipColorStrength: Float = 0f
    private var lipColorIndex: Int = 0
    private var blushStrength: Float = 0f
    private var blushColorFamily: Int = 0

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

    private var warmthStrength: Float = 0.0f
    private var contrast: Float = 1.0f

    private var renderFrameCount: Long = 0

    private var uSmoothingLocation: Int = -1
    private var uWhiteningLocation: Int = -1
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
    private var uWarmthLocation: Int = -1
    private var uContrastLocation: Int = -1
    private var uLipColorLocation: Int = -1
    private var uLipColorIndexLocation: Int = -1
    private var uBlushLocation: Int = -1
    private var uBlushColorFamilyLocation: Int = -1

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
        bigEyes: Float = 0f,
        slimFace: Float = 0f,
        lipColor: Float = 0f,
        lipColorIndex: Int = 0,
        blush: Float = 0f,
        blushColorFamily: Int = 0
    ) {
        smoothingStrength = smoothing.coerceIn(0f, 1f)
        whiteningStrength = whitening.coerceIn(0f, 1f)
        bigEyesStrength = bigEyes.coerceIn(0f, 1f)
        slimFaceStrength = slimFace.coerceIn(-1f, 1f)
        lipColorStrength = lipColor.coerceIn(0f, 1f)
        this.lipColorIndex = lipColorIndex.coerceIn(0, 11)
        blushStrength = blush.coerceIn(0f, 1f)
        this.blushColorFamily = blushColorFamily.coerceIn(0, 2)
        Log.d(
            TAG,
            "Beauty params updated: smoothing=$smoothingStrength, whitening=$whiteningStrength, " +
                "bigEyes=$bigEyesStrength, slimFace=$slimFaceStrength"
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

    fun updateAdvancedParams(warmth: Float, contrast: Float) {
        warmthStrength = warmth.coerceIn(0f, 1f)
        this.contrast = contrast.coerceIn(0.5f, 1.5f)
    }

    override fun onCompileShader(): Boolean {
        val vertexShader = BeautyShaders.VERTEX_SHADER
        val fragmentShader = when (renderMode) {
            MODE_DEBUG_RED -> BeautyShaders.FRAGMENT_SHADER_DEBUG_RED
            MODE_DEBUG_TEXTURE_R -> BeautyShaders.FRAGMENT_SHADER_DEBUG_TEXTURE_R
            else -> BeautyShaders.FRAGMENT_SHADER_BEAUTY
        }
        Log.d(TAG, "Compiling shader: mode=$renderMode")
        return shaderProgram.compile(vertexShader, fragmentShader)
    }

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
                shaderProgram.setFloat("uSmoothing", smoothingStrength)
                shaderProgram.setFloat("uWhitening", whiteningStrength)
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

                if (renderMode == MODE_ADVANCED) {
                    shaderProgram.setFloat("uWarmth", warmthStrength)
                    shaderProgram.setFloat("uContrast", contrast)
                }
            }
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uTextureLocation, 0)
    }

    private fun initUniformLocations() {
        uSmoothingLocation = shaderProgram.getUniformLocation("uSmoothing")
        uWhiteningLocation = shaderProgram.getUniformLocation("uWhitening")
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
        uWarmthLocation = shaderProgram.getUniformLocation("uWarmth")
        uContrastLocation = shaderProgram.getUniformLocation("uContrast")
        uLipColorLocation = shaderProgram.getUniformLocation("uLipColor")
        uLipColorIndexLocation = shaderProgram.getUniformLocation("uLipColorIndex")
        uBlushLocation = shaderProgram.getUniformLocation("uBlush")
        uBlushColorFamilyLocation = shaderProgram.getUniformLocation("uBlushColorFamily")
    }

    override fun release() {
        Log.d(TAG, "Releasing BeautyRenderer")
        super.release()
    }
}

