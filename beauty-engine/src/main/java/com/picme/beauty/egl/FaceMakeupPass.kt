package com.picme.beauty.egl

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * 面部妆容 Pass（GPUPixel 风格）
 *
 * 使用三角网格 + 纹理贴图实现唇色/腮红渲染：
 * - 111 个顶点构成人脸三角网格（基于 106 点关键点扩展）
 * - 226 个三角形覆盖全脸区域
 * - 预制妆容纹理（mouth.png / blusher.png）通过网格变形贴合人脸
 *
 * 渲染流程：
 * 1. 根据人脸关键点更新顶点位置
 * 2. 绑定妆容纹理和原始帧纹理
 * 3. 使用 glDrawElements 绘制三角网格
 */
class FaceMakeupPass(private val context: Context) {

    companion object {
        private const val TAG = "PicMe:FaceMakeupPass"

        // 顶点数：GPUPixel 标准脸网格 = 111 个顶点
        const val VERTEX_COUNT = 111

        // 三角形数：226 个三角形
        const val TRIANGLE_COUNT = 226
        const val INDEX_COUNT = TRIANGLE_COUNT * 3

        // 预定义纹理坐标（标准脸 UV，对应 mouth.png / blusher.png）
        // 这些数据来自 GPUPixel FaceMakeupFilter::FaceTextureCoordinates()
        private val FACE_TEXTURE_COORDS = floatArrayOf(
            0.302451f, 0.384169f, 0.302986f, 0.409377f, 0.304336f, 0.434977f,
            0.306984f, 0.460683f, 0.311010f, 0.486447f, 0.316537f, 0.511947f,
            0.323069f, 0.536942f, 0.331312f, 0.561627f, 0.342011f, 0.585088f,
            0.355477f, 0.607217f, 0.371142f, 0.627774f, 0.388459f, 0.646991f,
            0.407041f, 0.665229f, 0.426325f, 0.682694f, 0.447468f, 0.697492f,
            0.471782f, 0.707060f, 0.500000f, 0.709867f, 0.528218f, 0.707060f,
            0.552532f, 0.697492f, 0.573675f, 0.682694f, 0.592959f, 0.665229f,
            0.611541f, 0.646991f, 0.628858f, 0.627774f, 0.644523f, 0.607217f,
            0.657989f, 0.585088f, 0.668688f, 0.561627f, 0.676931f, 0.536942f,
            0.683463f, 0.511947f, 0.688990f, 0.486447f, 0.693016f, 0.460683f,
            0.695664f, 0.434977f, 0.697014f, 0.409377f, 0.697549f, 0.384169f,
            0.331655f, 0.354725f, 0.354609f, 0.331785f, 0.387080f, 0.325436f,
            0.420446f, 0.330125f, 0.452685f, 0.339996f, 0.547315f, 0.339996f,
            0.579554f, 0.330125f, 0.612920f, 0.325436f, 0.645391f, 0.331785f,
            0.668345f, 0.354725f, 0.500000f, 0.405156f, 0.500000f, 0.442322f,
            0.500000f, 0.480116f, 0.500000f, 0.517378f, 0.457729f, 0.542442f,
            0.476911f, 0.546376f, 0.500000f, 0.550557f, 0.523089f, 0.546376f,
            0.542271f, 0.542442f, 0.366597f, 0.404028f, 0.385132f, 0.392425f,
            0.428177f, 0.397495f, 0.442446f, 0.414082f, 0.422818f, 0.419177f,
            0.382917f, 0.415929f, 0.557554f, 0.414082f, 0.571823f, 0.397495f,
            0.614868f, 0.392425f, 0.633403f, 0.404028f, 0.617083f, 0.415929f,
            0.577182f, 0.419177f, 0.360880f, 0.349748f, 0.391440f, 0.348304f,
            0.421788f, 0.352051f, 0.451601f, 0.358026f, 0.548399f, 0.358026f,
            0.578212f, 0.352051f, 0.608560f, 0.348304f, 0.639120f, 0.349748f,
            0.407165f, 0.390906f, 0.402591f, 0.420584f, 0.406113f, 0.405280f,
            0.592835f, 0.390906f, 0.597409f, 0.420584f, 0.593887f, 0.405280f,
            0.471223f, 0.409619f, 0.528777f, 0.409619f, 0.455607f, 0.495169f,
            0.544393f, 0.495169f, 0.441855f, 0.523363f, 0.558145f, 0.523363f,
            0.426186f, 0.593516f, 0.453348f, 0.586128f, 0.481258f, 0.582594f,
            0.500000f, 0.584476f, 0.518742f, 0.582594f, 0.546652f, 0.586128f,
            0.573814f, 0.593516f, 0.556544f, 0.620391f, 0.531320f, 0.639672f,
            0.500000f, 0.644911f, 0.468680f, 0.639672f, 0.443456f, 0.620391f,
            0.433718f, 0.595595f, 0.466898f, 0.597025f, 0.500000f, 0.599883f,
            0.533102f, 0.597025f, 0.566282f, 0.595595f, 0.534634f, 0.610720f,
            0.500000f, 0.616173f, 0.465366f, 0.610720f, 0.406113f, 0.405280f,
            0.593887f, 0.405280f, 0.500000f, 0.608028f, 0.389259f, 0.336870f,
            0.610740f, 0.336870f, 0.386071f, 0.503558f, 0.613928f, 0.503558f
        )

        // 三角索引数据（226 个三角形 = 678 个索引）
        // 来自 GPUPixel FaceMakeupFilter::GetFaceIndexs()
        private val FACE_INDICES = intArrayOf(
            // Left eyebrow - 10 triangles
            33, 34, 64, 64, 34, 65, 65, 34, 107, 107, 34, 35, 35, 36, 107, 107, 36,
            66, 66, 107, 65, 66, 36, 67, 67, 36, 37, 37, 67, 43,
            // Right eyebrow - 10 triangles
            43, 38, 68, 68, 38, 39, 39, 68, 69, 39, 40, 108, 39, 108, 69, 69, 108, 70,
            70, 108, 41, 41, 108, 40, 41, 70, 71, 71, 41, 42,
            // Left eye - 21 triangles
            0, 33, 52, 33, 52, 64, 52, 64, 53, 64, 53, 65, 65, 53, 72, 65, 72, 66, 66,
            72, 54, 66, 54, 67, 54, 67, 55, 67, 55, 78, 67, 78, 43, 52, 53, 57, 53,
            72, 74, 53, 74, 57, 74, 57, 73, 72, 54, 104, 72, 104, 74, 74, 104, 73, 73,
            104, 56, 104, 56, 54, 54, 56, 55,
            // Right eye - 21 triangles
            68, 43, 79, 68, 79, 58, 68, 58, 59, 68, 59, 69, 69, 59, 75, 69, 75, 70,
            70, 75, 60, 70, 60, 71, 71, 60, 61, 71, 61, 42, 42, 61, 32, 61, 60, 62,
            60, 75, 77, 60, 77, 62, 77, 62, 76, 75, 77, 105, 77, 105, 76, 105, 76, 63,
            105, 63, 59, 105, 59, 75, 59, 63, 58,
            // Left cheek - 16 triangles
            0, 52, 1, 1, 52, 2, 2, 52, 57, 2, 57, 3, 3, 57, 4, 4, 57, 109, 57, 109,
            74, 74, 109, 56, 56, 109, 80, 80, 109, 82, 82, 109, 7, 7, 109, 6, 6, 109,
            5, 5, 109, 4, 56, 80, 55, 55, 80, 78,
            // Right cheek - 16 triangles
            32, 61, 31, 31, 61, 30, 30, 61, 62, 30, 62, 29, 29, 62, 28, 28, 62, 110,
            62, 110, 76, 76, 110, 63, 63, 110, 81, 81, 110, 83, 83, 110, 25, 25, 110,
            26, 26, 110, 27, 27, 110, 28, 63, 81, 58, 58, 81, 79,
            // Nose part - 16 triangles
            78, 43, 44, 43, 44, 79, 78, 44, 80, 79, 81, 44, 80, 44, 45, 44, 81, 45,
            80, 45, 46, 45, 81, 46, 80, 46, 82, 81, 46, 83, 82, 46, 47, 47, 46, 48,
            48, 46, 49, 49, 46, 50, 50, 46, 51, 51, 46, 83,
            // Triangles between nose and mouth - 14 triangles
            7, 82, 84, 82, 84, 47, 84, 47, 85, 85, 47, 48, 48, 85, 86, 86, 48, 49, 49,
            86, 87, 49, 87, 88, 88, 49, 50, 88, 50, 89, 89, 50, 51, 89, 51, 90, 51,
            90, 83, 83, 90, 25,
            // Upper lip part - 10 triangles
            84, 85, 96, 96, 85, 97, 97, 85, 86, 86, 97, 98, 86, 98, 87, 87, 98, 88,
            88, 98, 99, 88, 99, 89, 89, 99, 100, 89, 100, 90,
            // Lower lip part - 10 triangles
            90, 100, 91, 100, 91, 101, 101, 91, 92, 101, 92, 102, 102, 92, 93, 102,
            93, 94, 102, 94, 103, 103, 94, 95, 103, 95, 96, 96, 95, 84,
            // Between lips part - 8 triangles
            96, 97, 103, 97, 103, 106, 97, 106, 98, 106, 103, 102, 106, 102, 101, 106,
            101, 99, 106, 98, 99, 99, 101, 100,
            // Part between mouth and chin - 24 triangles
            7, 84, 8, 8, 84, 9, 9, 84, 10, 10, 84, 95, 10, 95, 11, 11, 95, 12, 12, 95,
            94, 12, 94, 13, 13, 94, 14, 14, 94, 93, 14, 93, 15, 15, 93, 16, 16, 93,
            17, 17, 93, 18, 18, 93, 92, 18, 92, 19, 19, 92, 20, 20, 92, 91, 20, 91,
            21, 21, 91, 22, 22, 91, 90, 22, 90, 23, 23, 90, 24, 24, 90, 25
        )

        // 混合模式常量（对应 GPUPixel blendMode）
        const val BLEND_MODE_REPLACE = 0
        const val BLEND_MODE_MULTIPLY = 15
        const val BLEND_MODE_OVERLAY = 17
        const val BLEND_MODE_HARD_LIGHT = 22
    }

    private val shaderProgram = ShaderProgram()
    private var isCompiled = false

    private var aPositionLocation: Int = -1
    private var aTextureCoordLocation: Int = -1

    private var uInputTextureLocation: Int = -1
    private var uMakeupTextureLocation: Int = -1
    private var uIntensityLocation: Int = -1
    private var uBlendModeLocation: Int = -1

    // 顶点缓冲区（位置 = 人脸关键点，动态更新）
    private val vertexBuffer: FloatBuffer

    // 纹理坐标缓冲区（静态预定义）
    private val texCoordBuffer: FloatBuffer

    // 索引缓冲区（静态预定义）
    private val indexBuffer: IntBuffer

    // 妆容纹理
    private var makeupTextureId: Int = 0
    private var textureBounds = FrameBounds(0f, 0f, 1f, 1f)

    // 当前强度
    private var intensity: Float = 0.5f
    private var blendMode: Int = BLEND_MODE_MULTIPLY

    init {
        // 初始化顶点缓冲区（111 个顶点 * 2 坐标）
        val vb = ByteBuffer.allocateDirect(VERTEX_COUNT * 2 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vb.position(0)
        vertexBuffer = vb

        // 初始化纹理坐标缓冲区
        val tb = ByteBuffer.allocateDirect(FACE_TEXTURE_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        tb.put(FACE_TEXTURE_COORDS).position(0)
        texCoordBuffer = tb

        // 初始化索引缓冲区
        val ib = ByteBuffer.allocateDirect(FACE_INDICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
        ib.put(FACE_INDICES).position(0)
        indexBuffer = ib
    }

    /**
     * 编译 Shader
     */
    fun compileFromAssets(vertexPath: String, fragmentPath: String): Boolean {
        if (isCompiled) return true
        val vertexSource = context.assets.open(vertexPath).bufferedReader().use { it.readText() }
        val fragmentSource = context.assets.open(fragmentPath).bufferedReader().use { it.readText() }
        isCompiled = shaderProgram.compile(vertexSource, fragmentSource)
        if (isCompiled) {
            aPositionLocation = shaderProgram.getAttribLocation("aPosition")
            aTextureCoordLocation = shaderProgram.getAttribLocation("aTextureCoord")
            uInputTextureLocation = shaderProgram.getUniformLocation("uInputTexture")
            uMakeupTextureLocation = shaderProgram.getUniformLocation("uMakeupTexture")
            uIntensityLocation = shaderProgram.getUniformLocation("uIntensity")
            uBlendModeLocation = shaderProgram.getUniformLocation("uBlendMode")
            Log.d(TAG, "FaceMakeupPass compiled: pos=$aPositionLocation, tex=$aTextureCoordLocation")
        }
        return isCompiled
    }

    /**
     * 从 assets 加载妆容纹理
     * @param assetPath assets 中的图片路径，如 "makeup/mouth.png"
     * @param bounds 纹理中有效妆容区域的边界（GPUPixel FrameBounds 格式）
     */
    fun loadMakeupTexture(assetPath: String, bounds: FrameBounds) {
        if (makeupTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(makeupTextureId), 0)
        }
        makeupTextureId = loadTextureFromAssets(assetPath)
        textureBounds = bounds
        Log.d(TAG, "Makeup texture loaded: $assetPath, id=$makeupTextureId, bounds=$bounds")
    }

    /**
     * 从 assets 加载图片为 OpenGL 纹理
     */
    private fun loadTextureFromAssets(assetPath: String): Int {
        return try {
            val bitmap = context.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
            if (bitmap == null) {
                Log.w(TAG, "Failed to decode bitmap: $assetPath")
                return 0
            }
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val textureId = textures[0]
            if (textureId == 0) {
                Log.e(TAG, "Failed to generate texture")
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
            Log.d(TAG, "Texture loaded: $assetPath = $textureId (${bitmap.width}x${bitmap.height})")
            textureId
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load texture: $assetPath - ${e.message}")
            0
        }
    }

    /**
     * 更新人脸关键点（106 点 → 111 顶点）
     *
     * GPUPixel 将 106 点映射到 111 顶点：
     * - 前 106 个顶点 = 106 个关键点（landmarks[0..105]）
     * - 额外 5 个顶点 = 插值生成的辅助点（如眉毛中点等）
     *
     * @param landmarks 106 点人脸关键点，范围 [0,1] 的 UV 坐标
     *                 格式：[x0, y0, x1, y1, ..., x105, y105]
     */
    fun updateFaceLandmarks(landmarks: FloatArray) {
        if (landmarks.size < 106 * 2) {
            Log.w(TAG, "Invalid landmarks size: ${landmarks.size}, expected >= 212")
            return
        }

        vertexBuffer.position(0)

        // 1. 写入前 106 个关键点（转换为 OpenGL NDC [-1, 1]）
        for (i in 0 until 106) {
            val x = landmarks[i * 2]
            val y = landmarks[i * 2 + 1]
            // UV [0,1] → NDC [-1,1]
            vertexBuffer.put(x * 2f - 1f)
            vertexBuffer.put(y * 2f - 1f)
        }

        // 2. 生成额外 5 个辅助顶点（索引 106..110）
        // 这些对应 GPUPixel 的额外点，用于完善三角网格
        // 107 = 左眉中点 (34+36)/2, 108 = 右眉中点 (39+41)/2
        // 109 = 左脸颊辅助点, 110 = 右脸颊辅助点, 106 = 唇中辅助点
        val extraPoints = floatArrayOf(
            // 106: 上下唇中点（用于嘴唇间隙）
            (landmarks[84 * 2] + landmarks[90 * 2]) * 0.5f * 2f - 1f,
            (landmarks[84 * 2 + 1] + landmarks[90 * 2 + 1]) * 0.5f * 2f - 1f,
            // 107: 左眉中点
            (landmarks[34 * 2] + landmarks[36 * 2]) * 0.5f * 2f - 1f,
            (landmarks[34 * 2 + 1] + landmarks[36 * 2 + 1]) * 0.5f * 2f - 1f,
            // 108: 右眉中点
            (landmarks[39 * 2] + landmarks[41 * 2]) * 0.5f * 2f - 1f,
            (landmarks[39 * 2 + 1] + landmarks[41 * 2 + 1]) * 0.5f * 2f - 1f,
            // 109: 左脸颊辅助
            (landmarks[2 * 2] + landmarks[4 * 2]) * 0.5f * 2f - 1f,
            (landmarks[2 * 2 + 1] + landmarks[4 * 2 + 1]) * 0.5f * 2f - 1f,
            // 110: 右脸颊辅助
            (landmarks[29 * 2] + landmarks[27 * 2]) * 0.5f * 2f - 1f,
            (landmarks[29 * 2 + 1] + landmarks[27 * 2 + 1]) * 0.5f * 2f - 1f
        )
        vertexBuffer.put(extraPoints)
        vertexBuffer.position(0)
    }

    /**
     * 设置渲染参数
     */
    fun setIntensity(value: Float) {
        intensity = value.coerceIn(0f, 1f)
    }

    fun setBlendMode(mode: Int) {
        blendMode = mode
    }

    /**
     * 渲染妆容到 FBO
     *
     * @param inputTextureId 原始帧纹理 ID
     * @param outputFbo 输出 FBO（null 则输出到屏幕）
     */
    fun render(inputTextureId: Int, outputFbo: Framebuffer? = null) {
        if (!isCompiled || makeupTextureId == 0) {
            Log.w(TAG, "FaceMakeupPass not ready: compiled=$isCompiled, tex=$makeupTextureId")
            return
        }

        // 绑定输出
        if (outputFbo != null) {
            outputFbo.bind()
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        shaderProgram.use()

        // 绑定原始帧纹理（TEXTURE0）
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        if (uInputTextureLocation >= 0) {
            GLES20.glUniform1i(uInputTextureLocation, 0)
        }

        // 绑定妆容纹理（TEXTURE1）
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, makeupTextureId)
        if (uMakeupTextureLocation >= 0) {
            GLES20.glUniform1i(uMakeupTextureLocation, 1)
        }

        // 设置 uniform
        if (uIntensityLocation >= 0) {
            GLES20.glUniform1f(uIntensityLocation, intensity)
        }
        if (uBlendModeLocation >= 0) {
            GLES20.glUniform1i(uBlendModeLocation, blendMode)
        }

        // 设置顶点属性
        if (aPositionLocation >= 0) {
            GLES20.glEnableVertexAttribArray(aPositionLocation)
            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        }

        if (aTextureCoordLocation >= 0) {
            GLES20.glEnableVertexAttribArray(aTextureCoordLocation)
            texCoordBuffer.position(0)
            GLES20.glVertexAttribPointer(aTextureCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        }

        // 绘制三角网格
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, INDEX_COUNT, GLES20.GL_UNSIGNED_INT, indexBuffer)

        // 清理
        if (aPositionLocation >= 0) GLES20.glDisableVertexAttribArray(aPositionLocation)
        if (aTextureCoordLocation >= 0) GLES20.glDisableVertexAttribArray(aTextureCoordLocation)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        if (outputFbo != null) {
            outputFbo.unbind()
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        if (makeupTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(makeupTextureId), 0)
            makeupTextureId = 0
        }
        shaderProgram.release()
        isCompiled = false
    }
}

/**
 * 纹理边界框（GPUPixel FrameBounds 兼容）
 */
data class FrameBounds(
    val x: Float,      // 纹理中有效区域左上角 X
    val y: Float,      // 纹理中有效区域左上角 Y
    val width: Float,  // 有效区域宽度
    val height: Float  // 有效区域高度
)
