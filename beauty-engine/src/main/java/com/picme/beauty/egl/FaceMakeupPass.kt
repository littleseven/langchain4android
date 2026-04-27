package com.picme.beauty.egl

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumMap

/**
 * 面部妆容 Pass（GPUPixel 风格）
 *
 * 使用三角网格 + 纹理贴图实现唇色/腮红渲染：
 * - 嘴唇与脸颊分别使用独立的索引网格和妆容纹理
 * - 纹理 alpha 负责确定精确的生效区域，颜色由业务色板共同决定
 * - 为兼容 GPUPixel 的 111 点妆容网格，内部会在 106 点基础上补齐 5 个辅助点
 *
 * 与 GPUPixel 原始实现保持一致：
 * 1. 先把输入帧完整复制到输出 FBO
 * 2. 再只在妆容三角网格区域覆盖渲染
 */
class FaceMakeupPass(private val context: Context) {

    companion object {
        private const val TAG = "PicMe:FaceMakeupPass"
        private const val TEXTURE_SIZE = 1280f
        private const val BASE_FACE_POINT_COUNT = 106

        const val VERTEX_COUNT = 111

        private const val HELPER_LIP_CENTER_INDEX = 106
        private const val HELPER_LEFT_BROW_INDEX = 107
        private const val HELPER_RIGHT_BROW_INDEX = 108
        private const val HELPER_LEFT_CHEEK_INDEX = 109
        private const val HELPER_RIGHT_CHEEK_INDEX = 110

        private val FULLSCREEN_VERTICES = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )

        private val FULLSCREEN_TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )

        private const val BASE_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTextureCoord;
            varying vec2 vTextureCoord;

            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTextureCoord = aTextureCoord;
            }
        """

        private const val BASE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uInputTexture;
            varying vec2 vTextureCoord;

            void main() {
                gl_FragColor = texture2D(uInputTexture, vTextureCoord);
            }
        """

        // GPUPixel 111 点基准纹理坐标
        private val FACE_TEXTURE_COORDS = floatArrayOf(
            0.302451f, 0.384169f, 0.302986f, 0.409377f, 0.304336f, 0.434977f, 0.306984f, 0.460683f, 0.311010f, 0.486447f,
            0.316537f, 0.511947f, 0.323069f, 0.536942f, 0.331312f, 0.561627f, 0.342011f, 0.585088f, 0.355477f, 0.607217f,
            0.371142f, 0.627774f, 0.388459f, 0.646991f, 0.407041f, 0.665229f, 0.426325f, 0.682694f, 0.447468f, 0.697492f,
            0.471782f, 0.707060f, 0.500000f, 0.709867f, 0.528218f, 0.707060f, 0.552532f, 0.697492f, 0.573675f, 0.682694f,
            0.592959f, 0.665229f, 0.611541f, 0.646991f, 0.628858f, 0.627774f, 0.644523f, 0.607217f, 0.657989f, 0.585088f,
            0.668688f, 0.561627f, 0.676931f, 0.536942f, 0.683463f, 0.511947f, 0.688990f, 0.486447f, 0.693016f, 0.460683f,
            0.695664f, 0.434977f, 0.697014f, 0.409377f, 0.697549f, 0.384169f,
            0.331655f, 0.354725f, 0.354609f, 0.331785f, 0.387080f, 0.325436f, 0.420446f, 0.330125f, 0.452685f, 0.339996f,
            0.547315f, 0.339996f, 0.579554f, 0.330125f, 0.612920f, 0.325436f, 0.645391f, 0.331785f, 0.668345f, 0.354725f,
            0.500000f, 0.405156f,
            0.500000f, 0.442322f, 0.500000f, 0.480116f, 0.500000f, 0.517378f, 0.457729f, 0.542442f, 0.476911f, 0.546376f,
            0.500000f, 0.550557f, 0.523089f, 0.546376f, 0.542271f, 0.542442f,
            0.366597f, 0.404028f, 0.385132f, 0.392425f, 0.428177f, 0.397495f, 0.442446f, 0.414082f, 0.422818f, 0.419177f,
            0.382917f, 0.415929f,
            0.557554f, 0.414082f, 0.571823f, 0.397495f, 0.614868f, 0.392425f, 0.633403f, 0.404028f, 0.617083f, 0.415929f,
            0.577182f, 0.419177f,
            0.360880f, 0.349748f, 0.391440f, 0.348304f, 0.421788f, 0.352051f, 0.451601f, 0.358026f, 0.548399f, 0.358026f,
            0.578212f, 0.352051f, 0.608560f, 0.348304f, 0.639120f, 0.349748f,
            0.407165f, 0.390906f, 0.402591f, 0.420584f, 0.406113f, 0.405280f,
            0.592835f, 0.390906f, 0.597409f, 0.420584f, 0.593887f, 0.405280f,
            0.471223f, 0.409619f, 0.528777f, 0.409619f, 0.455607f, 0.495169f, 0.544393f, 0.495169f, 0.441855f, 0.523363f,
            0.558145f, 0.523363f,
            0.426186f, 0.593516f, 0.453348f, 0.586128f, 0.481258f, 0.582594f, 0.500000f, 0.584476f, 0.518742f, 0.582594f,
            0.546652f, 0.586128f, 0.573814f, 0.593516f, 0.556544f, 0.620391f, 0.531320f, 0.639672f, 0.500000f, 0.644911f,
            0.468680f, 0.639672f, 0.443456f, 0.620391f,
            0.433718f, 0.595595f, 0.466898f, 0.597025f, 0.500000f, 0.599883f, 0.533102f, 0.597025f, 0.566282f, 0.595595f,
            0.534634f, 0.610720f, 0.500000f, 0.616173f, 0.465366f, 0.610720f,
            0.406113f, 0.405280f, 0.593887f, 0.405280f,
            0.500000f, 0.608028f,
            0.389259f, 0.336870f,
            0.610740f, 0.336870f,
            0.386071f, 0.503558f,
            0.613928f, 0.503558f
        )

        private val LIP_INDICES = intArrayOf(
            84, 85, 96,
            96, 85, 97,
            97, 85, 86,
            86, 97, 98,
            86, 98, 87,
            87, 98, 88,
            88, 98, 99,
            88, 99, 89,
            89, 99, 100,
            89, 100, 90,
            90, 100, 91,
            100, 91, 101,
            101, 91, 92,
            101, 92, 102,
            102, 92, 93,
            102, 93, 94,
            102, 94, 103,
            103, 94, 95,
            103, 95, 96,
            96, 95, 84,
            96, 97, 103,
            97, 103, 106,
            97, 106, 98,
            106, 103, 102,
            106, 102, 101,
            106, 101, 99,
            106, 98, 99,
            99, 101, 100
        )

        private val BLUSH_INDICES = intArrayOf(
            0, 52, 1,
            1, 52, 2,
            2, 52, 57,
            2, 57, 3,
            3, 57, 4,
            4, 57, 109,
            57, 109, 74,
            74, 109, 56,
            56, 109, 80,
            80, 109, 82,
            82, 109, 7,
            7, 109, 6,
            6, 109, 5,
            5, 109, 4,
            56, 80, 55,
            55, 80, 78,
            32, 61, 31,
            31, 61, 30,
            30, 61, 62,
            30, 62, 29,
            29, 62, 28,
            28, 62, 110,
            62, 110, 76,
            76, 110, 63,
            63, 110, 81,
            81, 110, 83,
            83, 110, 25,
            25, 110, 26,
            26, 110, 27,
            27, 110, 28,
            63, 81, 58,
            58, 81, 79
        )

        val LIP_INDEX_COUNT = LIP_INDICES.size
        val BLUSH_INDEX_COUNT = BLUSH_INDICES.size

        const val BLEND_MODE_MULTIPLY = 15
        const val BLEND_MODE_OVERLAY = 17
    }

    enum class MakeupType {
        LIP,
        BLUSH
    }

    private data class LoadedTexture(
        val assetPath: String,
        val textureId: Int,
        val bounds: FrameBounds
    )

    private val shaderProgram = ShaderProgram()
    private val baseCopyProgram = ShaderProgram()
    private var isCompiled = false

    private var aPositionLocation: Int = -1
    private var aTextureCoordLocation: Int = -1
    private var uInputTextureLocation: Int = -1
    private var uMakeupTextureLocation: Int = -1
    private var uIntensityLocation: Int = -1
    private var uBlendModeLocation: Int = -1
    private var uTintColorLocation: Int = -1
    private var uUseTintColorLocation: Int = -1

    private var basePositionLocation: Int = -1
    private var baseTextureCoordLocation: Int = -1
    private var baseInputTextureLocation: Int = -1

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(VERTEX_COUNT * 2 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val runtimeTexCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(FACE_TEXTURE_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val fullScreenVertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(FULLSCREEN_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULLSCREEN_VERTICES)
            position(0)
        }

    private val fullScreenTexCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(FULLSCREEN_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULLSCREEN_TEX_COORDS)
            position(0)
        }

    private val lipIndexByteBuffer: ByteBuffer = createIndexBuffer(LIP_INDICES)
    private val blushIndexByteBuffer: ByteBuffer = createIndexBuffer(BLUSH_INDICES)
    private val loadedTextures = EnumMap<MakeupType, LoadedTexture>(MakeupType::class.java)

    private var intensity: Float = 0.5f
    private var blendMode: Int = BLEND_MODE_MULTIPLY
    private var tintColor: FloatArray? = null

    fun compileFromAssets(vertexPath: String, fragmentPath: String): Boolean {
        if (isCompiled) return true

        val vertexSource = context.assets.open(vertexPath).bufferedReader().use { reader -> reader.readText() }
        val fragmentSource = context.assets.open(fragmentPath).bufferedReader().use { reader -> reader.readText() }

        val makeupCompiled = shaderProgram.compile(vertexSource, fragmentSource)
        val baseCompiled = baseCopyProgram.compile(BASE_VERTEX_SHADER.trimIndent(), BASE_FRAGMENT_SHADER.trimIndent())
        if (!makeupCompiled || !baseCompiled) {
            shaderProgram.release()
            baseCopyProgram.release()
            return false
        }

        aPositionLocation = shaderProgram.getAttribLocation("aPosition")
        aTextureCoordLocation = shaderProgram.getAttribLocation("aTextureCoord")
        uInputTextureLocation = shaderProgram.getUniformLocation("uInputTexture")
        uMakeupTextureLocation = shaderProgram.getUniformLocation("uMakeupTexture")
        uIntensityLocation = shaderProgram.getUniformLocation("uIntensity")
        uBlendModeLocation = shaderProgram.getUniformLocation("uBlendMode")
        uTintColorLocation = shaderProgram.getUniformLocation("uTintColor")
        uUseTintColorLocation = shaderProgram.getUniformLocation("uUseTintColor")

        basePositionLocation = baseCopyProgram.getAttribLocation("aPosition")
        baseTextureCoordLocation = baseCopyProgram.getAttribLocation("aTextureCoord")
        baseInputTextureLocation = baseCopyProgram.getUniformLocation("uInputTexture")

        isCompiled = true
        Log.d(TAG, "FaceMakeupPass compiled")
        return true
    }

    fun loadMakeupTexture(type: MakeupType, assetPath: String, bounds: FrameBounds) {
        val current = loadedTextures[type]
        if (current != null && current.assetPath == assetPath && current.bounds == bounds && current.textureId != 0) {
            return
        }

        current?.let { texture ->
            if (texture.textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(texture.textureId), 0)
            }
        }

        val textureId = loadTextureFromAssets(assetPath)
        loadedTextures[type] = LoadedTexture(assetPath = assetPath, textureId = textureId, bounds = bounds)
        Log.d(TAG, "Makeup texture loaded: type=$type, asset=$assetPath, id=$textureId, bounds=$bounds")
    }

    private fun loadTextureFromAssets(assetPath: String): Int {
        return try {
            val bitmap = context.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: run {
                Log.w(TAG, "Failed to decode bitmap: $assetPath")
                return 0
            }

            val width = bitmap.width
            val height = bitmap.height
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val textureId = textures[0]
            if (textureId == 0) {
                Log.e(TAG, "Failed to generate texture for $assetPath")
                bitmap.recycle()
                return 0
            }

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            bitmap.recycle()

            Log.d(TAG, "Texture loaded: $assetPath = $textureId (${width}x${height})")
            textureId
        } catch (error: Exception) {
            Log.w(TAG, "Failed to load texture: $assetPath - ${error.message}")
            0
        }
    }

    fun updateFaceLandmarks(landmarks: FloatArray) {
        if (landmarks.size < BASE_FACE_POINT_COUNT * 2) {
            Log.w(TAG, "Invalid landmarks size: ${landmarks.size}, expected >= ${BASE_FACE_POINT_COUNT * 2}")
            return
        }

        val expandedLandmarks = expandFaceLandmarks(landmarks)
        vertexBuffer.clear()
        for (index in 0 until VERTEX_COUNT) {
            val x = expandedLandmarks[index * 2]
            val y = expandedLandmarks[index * 2 + 1]
            vertexBuffer.put(x * 2f - 1f)
            vertexBuffer.put(y * 2f - 1f)
        }
        vertexBuffer.flip()
    }

    private fun expandFaceLandmarks(landmarks: FloatArray): FloatArray {
        val expanded = FloatArray(VERTEX_COUNT * 2)
        val baseCount = minOf(landmarks.size, BASE_FACE_POINT_COUNT * 2)
        System.arraycopy(landmarks, 0, expanded, 0, baseCount)

        putAveragePoint(expanded, HELPER_LIP_CENTER_INDEX, intArrayOf(96, 97, 98, 99, 100, 101, 102, 103))
        putAveragePoint(expanded, HELPER_LEFT_BROW_INDEX, intArrayOf(34, 35, 43, 64, 65))
        putAveragePoint(expanded, HELPER_RIGHT_BROW_INDEX, intArrayOf(39, 40, 43, 69, 70))
        putAveragePoint(expanded, HELPER_LEFT_CHEEK_INDEX, intArrayOf(4, 7, 56, 80, 82))
        putAveragePoint(expanded, HELPER_RIGHT_CHEEK_INDEX, intArrayOf(25, 28, 63, 81, 83))
        return expanded
    }

    private fun putAveragePoint(target: FloatArray, targetIndex: Int, sourceIndices: IntArray) {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        sourceIndices.forEach { sourceIndex ->
            if (sourceIndex in 0 until BASE_FACE_POINT_COUNT) {
                sumX += target[sourceIndex * 2]
                sumY += target[sourceIndex * 2 + 1]
                count += 1
            }
        }
        val outputBase = targetIndex * 2
        if (count == 0) {
            target[outputBase] = 0.5f
            target[outputBase + 1] = 0.5f
            return
        }
        target[outputBase] = (sumX / count).coerceIn(0f, 1f)
        target[outputBase + 1] = (sumY / count).coerceIn(0f, 1f)
    }

    fun setIntensity(value: Float) {
        intensity = value.coerceIn(0f, 1f)
    }

    fun setBlendMode(mode: Int) {
        blendMode = mode
    }

    fun setTintColor(color: FloatArray?) {
        tintColor = color?.takeIf { values -> values.size >= 3 }?.copyOf(3)
    }

    private fun updateTextureCoordinates(bounds: FrameBounds) {
        runtimeTexCoordBuffer.clear()
        for (index in 0 until VERTEX_COUNT) {
            val baseU = FACE_TEXTURE_COORDS[index * 2]
            val baseV = FACE_TEXTURE_COORDS[index * 2 + 1]
            val u = (baseU * TEXTURE_SIZE - bounds.x) / bounds.width
            val v = (baseV * TEXTURE_SIZE - bounds.y) / bounds.height
            runtimeTexCoordBuffer.put(u)
            runtimeTexCoordBuffer.put(v)
        }
        runtimeTexCoordBuffer.flip()
    }

    fun render(
        inputTextureId: Int,
        outputFbo: Framebuffer? = null,
        makeupType: MakeupType = MakeupType.LIP
    ): Boolean {
        val loadedTexture = loadedTextures[makeupType]
        if (!isCompiled || loadedTexture == null || loadedTexture.textureId == 0) {
            Log.w(
                TAG,
                "FaceMakeupPass not ready: compiled=$isCompiled, type=$makeupType, texture=${loadedTexture?.textureId ?: 0}"
            )
            return false
        }

        updateTextureCoordinates(loadedTexture.bounds)

        val previousViewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, previousViewport, 0)

        if (outputFbo != null) {
            outputFbo.bind()
            GLES20.glViewport(0, 0, outputFbo.getWidth(), outputFbo.getHeight())
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        renderBaseFrame(inputTextureId)
        renderMakeupTriangles(inputTextureId, loadedTexture.textureId, makeupType)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        if (outputFbo != null) {
            outputFbo.unbind()
        }
        GLES20.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
        return true
    }

    private fun renderBaseFrame(inputTextureId: Int) {
        baseCopyProgram.use()

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        if (baseInputTextureLocation >= 0) {
            GLES20.glUniform1i(baseInputTextureLocation, 0)
        }

        GLES20.glEnableVertexAttribArray(basePositionLocation)
        fullScreenVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(basePositionLocation, 2, GLES20.GL_FLOAT, false, 0, fullScreenVertexBuffer)

        GLES20.glEnableVertexAttribArray(baseTextureCoordLocation)
        fullScreenTexCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(baseTextureCoordLocation, 2, GLES20.GL_FLOAT, false, 0, fullScreenTexCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(basePositionLocation)
        GLES20.glDisableVertexAttribArray(baseTextureCoordLocation)
    }

    private fun renderMakeupTriangles(inputTextureId: Int, makeupTextureId: Int, makeupType: MakeupType) {
        shaderProgram.use()

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        if (uInputTextureLocation >= 0) {
            GLES20.glUniform1i(uInputTextureLocation, 0)
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, makeupTextureId)
        if (uMakeupTextureLocation >= 0) {
            GLES20.glUniform1i(uMakeupTextureLocation, 1)
        }

        if (uIntensityLocation >= 0) {
            GLES20.glUniform1f(uIntensityLocation, intensity)
        }
        if (uBlendModeLocation >= 0) {
            GLES20.glUniform1i(uBlendModeLocation, blendMode)
        }

        val activeTintColor = tintColor
        if (activeTintColor != null && uTintColorLocation >= 0 && uUseTintColorLocation >= 0) {
            GLES20.glUniform3f(
                uTintColorLocation,
                activeTintColor[0].coerceIn(0f, 1f),
                activeTintColor[1].coerceIn(0f, 1f),
                activeTintColor[2].coerceIn(0f, 1f)
            )
            GLES20.glUniform1f(uUseTintColorLocation, 1f)
        } else if (uUseTintColorLocation >= 0) {
            GLES20.glUniform1f(uUseTintColorLocation, 0f)
        }

        GLES20.glEnableVertexAttribArray(aPositionLocation)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(aTextureCoordLocation)
        runtimeTexCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(aTextureCoordLocation, 2, GLES20.GL_FLOAT, false, 0, runtimeTexCoordBuffer)

        val (indexBuffer, indexCount) = when (makeupType) {
            MakeupType.LIP -> lipIndexByteBuffer to LIP_INDEX_COUNT
            MakeupType.BLUSH -> blushIndexByteBuffer to BLUSH_INDEX_COUNT
        }

        indexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

        GLES20.glDisableVertexAttribArray(aPositionLocation)
        GLES20.glDisableVertexAttribArray(aTextureCoordLocation)
    }

    fun release() {
        loadedTextures.values.forEach { texture ->
            if (texture.textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(texture.textureId), 0)
            }
        }
        loadedTextures.clear()
        shaderProgram.release()
        baseCopyProgram.release()
        isCompiled = false
    }

    private fun createIndexBuffer(indices: IntArray): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
        indices.forEach { index ->
            byteBuffer.putShort(index.toShort())
        }
        byteBuffer.position(0)
        return byteBuffer
    }
}

/**
 * 纹理边界框（GPUPixel FrameBounds 兼容）
 */
data class FrameBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)