package com.picme.beauty.internal

import android.opengl.GLES20
import android.util.Log
import com.picme.beauty.egl.BeautyRenderer

/**
 * BeautyRenderer 到 BeautyShaderChain 的适配器
 *
 * 将现有的 BeautyRenderer 包装为 BeautyShaderChain 接口，
 * 使 OffscreenRenderer 能够复用预览渲染管线。
 *
 * 实现原理：
 * 1. 输入纹理绑定到 GL_TEXTURE0（替代相机 OES 纹理）
 * 2. 绑定输出 FBO 纹理作为渲染目标
 * 3. 调用 BeautyRenderer.renderMainShaderFromFbo2D() 执行渲染
 * 4. 解绑 FBO
 *
 * 注意：
 * - 必须确保 EGL 上下文与预览渲染共享
 * - 人脸关键点和美颜参数需提前同步
 */
class BeautyRendererAsShaderChain(
    private val beautyRenderer: BeautyRenderer
) : BeautyShaderChain {

    companion object {
        private const val TAG = "PicMe:BeautyRendererAdapter"
    }

    /**
     * 执行美颜渲染链
     *
     * 核心逻辑：
     * 1. 设置外部纹理 ID（输入纹理）
     * 2. 绑定输出 FBO 纹理
     * 3. 调用 renderMainShaderFromFbo2D() 执行渲染
     * 4. 解绑 FBO
     *
     * @param inputTextureId 输入纹理 ID（Bitmap 转换的 2D 纹理）
     * @param outputTextureId 输出纹理 ID（FBO 绑定的纹理）
     * @param width 渲染宽度
     * @param height 渲染高度
     * @return true 表示成功，false 表示失败
     */
    override fun render(
        inputTextureId: Int,
        outputTextureId: Int,
        width: Int,
        height: Int
    ): Boolean {
        Log.d(TAG, "render: inputTex=$inputTextureId, outputTex=$outputTextureId, size=${width}x${height}")

        try {
            // 1. 设置外部纹理 ID（让 BeautyRenderer 从该纹理采样）
            beautyRenderer.setExternalTextureId(inputTextureId)

            // 2. 绑定输出 FBO 纹理作为渲染目标
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputTextureId)

            // 3. 清屏
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // 4. 执行主 Shader 渲染（从输入纹理到输出 FBO）
            beautyRenderer.renderMainShaderFromFbo2D(
                inputTextureId = inputTextureId,
                width = width,
                height = height
            )

            // 5. 检查 GL 错误
            val glError = GLES20.glGetError()
            if (glError != GLES20.GL_NO_ERROR) {
                Log.e(TAG, "GL error after render: $glError")
                return false
            }

            // 6. 解绑 FBO
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            Log.d(TAG, "render: completed successfully")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "render: exception", e)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return false
        }
    }

    /**
     * 设置美颜参数（同步到 BeautyRenderer）
     */
    override fun setBeautyParams(
        smoothingStrength: Float,
        whiteningStrength: Float,
        slimFaceStrength: Float,
        bigEyeStrength: Float,
        lipColorStrength: Float,
        blushStrength: Float,
        eyebrowStrength: Float
    ) {
        beautyRenderer.updateBeautyParams(
            smoothing = smoothingStrength,
            whitening = whiteningStrength,
            sharpen = 0.0f,
            bigEyes = bigEyeStrength,
            slimFace = slimFaceStrength,
            lipColor = lipColorStrength,
            lipColorIndex = 0,
            blush = blushStrength,
            blushColorFamily = 0
        )

        Log.d(TAG, "setBeautyParams: smooth=$smoothingStrength, white=$whiteningStrength, slim=$slimFaceStrength, bigEye=$bigEyeStrength")
    }

    /**
     * 设置人脸关键点（106点）
     */
    override fun setFaceLandmarks(landmarks106: FloatArray?, hasFace: Boolean) {
        if (landmarks106 != null && landmarks106.isNotEmpty()) {
            require(landmarks106.size == 106 * 2) {
                "Landmarks must have 106 points (212 floats), got ${landmarks106.size}"
            }
            beautyRenderer.updateFacePoints106(landmarks106)
        }

        Log.d(TAG, "setFaceLandmarks: hasFace=$hasFace, points=${landmarks106?.size ?: 0}")
    }

    /**
     * 设置滤镜类型
     */
    override fun setFilterType(filterType: String) {
        // TODO: 根据 filterType 映射到 BeautyRenderer 的风格特效
        Log.d(TAG, "setFilterType: $filterType (TODO: implement style effect mapping)")
    }

    /**
     * 释放资源
     */
    override fun release() {
        Log.d(TAG, "release")
        // BeautyRenderer 由外部管理生命周期，这里不释放
    }
}
