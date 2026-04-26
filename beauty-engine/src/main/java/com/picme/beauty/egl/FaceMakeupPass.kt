package com.picme.beauty.egl

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * 面部妆容 Pass（GPUPixel 风格）
 *
 * 使用三角网格 + 纹理贴图实现唇色/腮红渲染：
 * - 106 个顶点构成人脸三角网格（纯 106 点关键点，不扩展）
 * - 36 个三角形覆盖嘴唇区域
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

        // 顶点数：纯 106 点方案
        const val VERTEX_COUNT = 106

        // GPUPixel 111 点基准纹理坐标的前 106 点
        // 来源：GPUPixel FaceTextureCoordinates()，忽略辅助点 106-110
        private val FACE_TEXTURE_COORDS = floatArrayOf(
            // 0-32: 轮廓点（33点）
            0.302451f, 0.384169f, 0.302986f, 0.409377f, 0.304336f, 0.434977f, 0.306984f, 0.460683f, 0.311010f, 0.486447f,
            0.316537f, 0.511947f, 0.323069f, 0.536942f, 0.331312f, 0.561627f, 0.342011f, 0.585088f, 0.355477f, 0.607217f,
            0.371142f, 0.627774f, 0.388459f, 0.646991f, 0.407041f, 0.665229f, 0.426325f, 0.682694f, 0.447468f, 0.697492f,
            0.471782f, 0.707060f, 0.500000f, 0.709867f, 0.528218f, 0.707060f, 0.552532f, 0.697492f, 0.573675f, 0.682694f,
            0.592959f, 0.665229f, 0.611541f, 0.646991f, 0.628858f, 0.627774f, 0.644523f, 0.607217f, 0.657989f, 0.585088f,
            0.668688f, 0.561627f, 0.676931f, 0.536942f, 0.683463f, 0.511947f, 0.688990f, 0.486447f, 0.693016f, 0.460683f,
            0.695664f, 0.434977f, 0.697014f, 0.409377f, 0.697549f, 0.384169f,
            // 33-42: 眉毛上部（10点）
            0.331655f, 0.354725f, 0.354609f, 0.331785f, 0.387080f, 0.325436f, 0.420446f, 0.330125f, 0.452685f, 0.339996f,
            0.547315f, 0.339996f, 0.579554f, 0.330125f, 0.612920f, 0.325436f, 0.645391f, 0.331785f, 0.668345f, 0.354725f,
            // 43: 眉心（1点）
            0.500000f, 0.405156f,
            // 44-51: 鼻子（8点）
            0.500000f, 0.442322f, 0.500000f, 0.480116f, 0.500000f, 0.517378f, 0.457729f, 0.542442f, 0.476911f, 0.546376f,
            0.500000f, 0.550557f, 0.523089f, 0.546376f, 0.542271f, 0.542442f,
            // 52-57: 右眼（6点）
            0.366597f, 0.404028f, 0.385132f, 0.392425f, 0.428177f, 0.397495f, 0.442446f, 0.414082f, 0.422818f, 0.419177f,
            0.382917f, 0.415929f,
            // 58-63: 左眼（6点）
            0.557554f, 0.414082f, 0.571823f, 0.397495f, 0.614868f, 0.392425f, 0.633403f, 0.404028f, 0.617083f, 0.415929f,
            0.577182f, 0.419177f,
            // 64-71: 眉毛下部（8点）
            0.360880f, 0.349748f, 0.391440f, 0.348304f, 0.421788f, 0.352051f, 0.451601f, 0.358026f, 0.548399f, 0.358026f,
            0.578212f, 0.352051f, 0.608560f, 0.348304f, 0.639120f, 0.349748f,
            // 72-74: 右眼补充（3点）
            0.407165f, 0.390906f, 0.402591f, 0.420584f, 0.406113f, 0.405280f,
            // 75-77: 左眼补充（3点）
            0.592835f, 0.390906f, 0.597409f, 0.420584f, 0.593887f, 0.405280f,
            // 78-83: 鼻子补充（6点）
            0.471223f, 0.409619f, 0.528777f, 0.409619f, 0.455607f, 0.495169f, 0.544393f, 0.495169f, 0.441855f, 0.523363f,
            0.558145f, 0.523363f,
            // 84-95: 嘴巴外轮廓（12点）
            0.426186f, 0.593516f, 0.453348f, 0.586128f, 0.481258f, 0.582594f, 0.500000f, 0.584476f, 0.518742f, 0.582594f,
            0.546652f, 0.586128f, 0.573814f, 0.593516f, 0.556544f, 0.620391f, 0.531320f, 0.639672f, 0.500000f, 0.644911f,
            0.468680f, 0.639672f, 0.443456f, 0.620391f,
            // 96-103: 嘴巴内轮廓（8点）
            0.433718f, 0.595595f, 0.466898f, 0.597025f, 0.500000f, 0.599883f, 0.533102f, 0.597025f, 0.566282f, 0.595595f,
            0.534634f, 0.610720f, 0.500000f, 0.616173f, 0.465366f, 0.610720f,
            // 104-105: 瞳孔（2点）
            0.406113f, 0.405280f, 0.593887f, 0.405280f
        )

        // 嘴唇区域三角索引（只使用 84-103 共20个点）
        // 所有索引必须在 [0, 105] 范围内（因为 VERTEX_COUNT = 106）
        private val LIP_INDICES = intArrayOf(
            // === 上唇区域 ===
            84, 85, 96,
            85, 96, 97,
            85, 86, 97,
            86, 97, 98,
            86, 87, 98,
            87, 98, 99,
            87, 88, 99,
            88, 99, 100,
            88, 89, 100,
            89, 90, 100,
            // === 下唇区域 ===
            90, 91, 100,
            91, 100, 101,
            91, 92, 101,
            92, 101, 102,
            92, 93, 102,
            93, 102, 103,
            93, 94, 103,
            94, 103, 96,
            94, 95, 96,
            95, 84, 96,
            // === 嘴唇中央闭合区域 ===
            96, 97, 103,
            97, 98, 102,
            97, 102, 103,
            98, 99, 102,
            99, 100, 101,
            99, 101, 102
        )

        // 腮红区域三角索引（使用脸颊轮廓点）
        // 右脸颊（画面左侧=实际右脸）：轮廓点 2-6
        // 左脸颊（画面右侧=实际左脸）：轮廓点 27-31
        // 使用鼻子点（44-46, 78-79）作为内侧边界
        private val BLUSH_INDICES = intArrayOf(
            // === 右脸颊（画面左侧）===
            // 使用轮廓点 2,3,4,5,6 和鼻子点 44,45,78 构建三角网格
            2, 3, 78,
            3, 78, 44,
            3, 4, 44,
            4, 44, 45,
            4, 5, 45,
            5, 45, 46,
            5, 6, 46,
            // === 左脸颊（画面右侧）===
            // 使用轮廓点 27,28,29,30,31 和鼻子点 45,46,79 构建三角网格
            27, 28, 79,
            28, 79, 46,
            28, 29, 46,
            29, 46, 45,
            29, 30, 45,
            30, 45, 44,
            30, 31, 44
        )

        // 索引数
        val LIP_INDEX_COUNT = LIP_INDICES.size
        val BLUSH_INDEX_COUNT = BLUSH_INDICES.size

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

    // 嘴唇索引缓冲区
    private val lipIndexBuffer: ShortBuffer
    private val lipIndexByteBuffer: ByteBuffer

    // 腮红索引缓冲区
    private val blushIndexBuffer: ShortBuffer
    private val blushIndexByteBuffer: ByteBuffer

    // 妆容纹理
    private var makeupTextureId: Int = 0
    private var textureBounds = FrameBounds(0f, 0f, 1f, 1f)

    // 当前强度
    private var intensity: Float = 0.5f
    private var blendMode: Int = BLEND_MODE_MULTIPLY

    init {
        // 初始化顶点缓冲区（106 个顶点 × 2 坐标 × 4 bytes/float）
        val vb = ByteBuffer.allocateDirect(VERTEX_COUNT * 2 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexBuffer = vb

        // 初始化纹理坐标缓冲区
        val tb = ByteBuffer.allocateDirect(FACE_TEXTURE_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        tb.put(FACE_TEXTURE_COORDS)
        tb.flip()
        texCoordBuffer = tb

        // 初始化嘴唇索引缓冲区
        val lipIbb = ByteBuffer.allocateDirect(LIP_INDICES.size * 2)
            .order(ByteOrder.nativeOrder())
        val lipIb = lipIbb.asShortBuffer()
        for (idx in LIP_INDICES) {
            lipIb.put(idx.toShort())
        }
        lipIb.flip()
        lipIbb.limit(lipIb.limit() * 2)
        lipIndexBuffer = lipIb
        lipIndexByteBuffer = lipIbb

        // 初始化腮红索引缓冲区
        val blushIbb = ByteBuffer.allocateDirect(BLUSH_INDICES.size * 2)
            .order(ByteOrder.nativeOrder())
        val blushIb = blushIbb.asShortBuffer()
        for (idx in BLUSH_INDICES) {
            blushIb.put(idx.toShort())
        }
        blushIb.flip()
        blushIbb.limit(blushIb.limit() * 2)
        blushIndexBuffer = blushIb
        blushIndexByteBuffer = blushIbb

        Log.d(TAG, "Index buffers initialized: lip=${LIP_INDICES.size}, blush=${BLUSH_INDICES.size}")
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
     * 更新人脸关键点（纯 106 点方案）
     *
     * 所有 106 个顶点直接使用关键点，不添加额外顶点
     *
     * @param landmarks 106 点人脸关键点，范围 [0,1] 的 UV 坐标
     *                 格式：[x0, y0, x1, y1, ..., x105, y105]
     */
    fun updateFaceLandmarks(landmarks: FloatArray) {
        if (landmarks.size < 106 * 2) {
            Log.w(TAG, "Invalid landmarks size: ${landmarks.size}, expected >= 212")
            return
        }

        vertexBuffer.clear()

        // 写入 106 个关键点（转换为 OpenGL NDC [-1, 1]）
        for (i in 0 until 106) {
            val x = landmarks[i * 2]
            val y = landmarks[i * 2 + 1]
            // UV [0,1] → NDC [-1,1]
            vertexBuffer.put(x * 2f - 1f)
            vertexBuffer.put(y * 2f - 1f)
        }

        vertexBuffer.flip()
        
        // 调试：打印嘴唇区域顶点的 NDC 坐标
        val lipIndices = intArrayOf(84, 87, 90, 93, 96, 100)
        val sb = StringBuilder("Lip vertices NDC: ")
        for (idx in lipIndices) {
            if (idx < VERTEX_COUNT) {
                val x = vertexBuffer.get(idx * 2)
                val y = vertexBuffer.get(idx * 2 + 1)
                sb.append("[$idx](${String.format("%.2f", x)},${String.format("%.2f", y)}) ")
            }
        }
        Log.d(TAG, sb.toString())
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
     * 设置纹理边界（用于切换不同妆容的 bounds）
     */
    fun setTextureBounds(bounds: FrameBounds) {
        textureBounds = bounds
        Log.d(TAG, "Texture bounds updated: $bounds")
    }

    // 运行时纹理坐标缓冲区（应用 textureBounds 后的坐标）
    private val runtimeTexCoordBuffer: FloatBuffer

    init {
        // ... 前面的初始化代码保持不变 ...
        
        // 初始化运行时纹理坐标缓冲区
        val rtb = ByteBuffer.allocateDirect(FACE_TEXTURE_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        runtimeTexCoordBuffer = rtb
    }

    // 当前妆容类型
    enum class MakeupType {
        LIP,    // 唇色
        BLUSH   // 腮红
    }
    private var currentMakeupType = MakeupType.LIP

    /**
     * 动态计算纹理坐标
     *
     * 根据妆容类型选择不同的映射策略：
     * - LIP: 使用嘴唇关键点（84-103）计算外接矩形，映射到 mouth.png
     * - BLUSH: 使用脸颊关键点计算外接矩形，映射到 blusher.png
     */
    // 纹理尺寸（mouth.png / blusher.png 都是 1280x1280）
    private val TEXTURE_SIZE = 1280f

    /**
     * 计算纹理坐标：GPUPixel 方式
     *
     * 使用基准纹理坐标（FACE_TEXTURE_COORDS）+ textureBounds 缩放
     * 公式：finalU = (baseU * textureSize - bounds.x) / bounds.width
     */
    private fun updateTextureCoordinates() {
        val bounds = textureBounds
        val texSize = TEXTURE_SIZE

        runtimeTexCoordBuffer.clear()
        for (i in 0 until VERTEX_COUNT) {
            val baseU = FACE_TEXTURE_COORDS[i * 2]
            val baseV = FACE_TEXTURE_COORDS[i * 2 + 1]

            // GPUPixel 公式：(coord * 1280 - bounds.x) / bounds.width
            val u = (baseU * texSize - bounds.x) / bounds.width
            val v = (baseV * texSize - bounds.y) / bounds.height

            runtimeTexCoordBuffer.put(u)
            runtimeTexCoordBuffer.put(v)
        }
        runtimeTexCoordBuffer.flip()
    }

    /**
     * 渲染妆容到 FBO
     *
     * @param inputTextureId 原始帧纹理 ID
     * @param outputFbo 输出 FBO（null 则输出到屏幕）
     * @param makeupType 妆容类型（LIP 或 BLUSH）
     */
    fun render(inputTextureId: Int, outputFbo: Framebuffer? = null, makeupType: MakeupType = MakeupType.LIP) {
        if (!isCompiled || makeupTextureId == 0) {
            Log.w(TAG, "FaceMakeupPass not ready: compiled=$isCompiled, tex=$makeupTextureId")
            return
        }

        // 设置当前妆容类型
        currentMakeupType = makeupType

        // 更新纹理坐标（根据妆容类型）
        updateTextureCoordinates()

        // 保存当前视口
        val prevViewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0)

        // 绑定输出并设置视口
        if (outputFbo != null) {
            outputFbo.bind()
            GLES20.glViewport(0, 0, outputFbo.getWidth(), outputFbo.getHeight())
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

        // 设置顶点属性（使用真实人脸关键点）
        if (aPositionLocation >= 0) {
            GLES20.glEnableVertexAttribArray(aPositionLocation)
            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        }

        if (aTextureCoordLocation >= 0) {
            GLES20.glEnableVertexAttribArray(aTextureCoordLocation)
            runtimeTexCoordBuffer.position(0)
            GLES20.glVertexAttribPointer(aTextureCoordLocation, 2, GLES20.GL_FLOAT, false, 0, runtimeTexCoordBuffer)
        }

        // 根据妆容类型选择索引缓冲区和绘制
        val (indexBuffer, indexCount) = when (makeupType) {
            MakeupType.LIP -> Pair(lipIndexByteBuffer, LIP_INDEX_COUNT)
            MakeupType.BLUSH -> Pair(blushIndexByteBuffer, BLUSH_INDEX_COUNT)
        }

        Log.d(TAG, "FaceMakeupPass render: type=$makeupType, drawing $indexCount indices")
        indexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

        // 清理
        if (aPositionLocation >= 0) GLES20.glDisableVertexAttribArray(aPositionLocation)
        if (aTextureCoordLocation >= 0) GLES20.glDisableVertexAttribArray(aTextureCoordLocation)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        if (outputFbo != null) {
            outputFbo.unbind()
            // 恢复之前的视口
            GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
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