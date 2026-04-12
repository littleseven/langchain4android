package com.picme.beauty.egl

import android.content.Context
import android.util.Log

/**
 * R 计划 - Shader 模块加载器
 *
 * 功能：
 * 1. 从 assets/shaders/ 目录加载独立的 .glsl 文件
 * 2. 按逻辑顺序拼接为完整的 Fragment Shader
 * 3. 支持模块化维护与独立测试
 */
object ShaderModuleLoader {
    private const val TAG = "PicMe:ShaderLoader"
    private const val SHADER_DIR = "shaders"

    private val MODULE_ORDER = listOf(
        "header.glsl",
        "uniforms.glsl",
        "warp.glsl",
        "skin.glsl",
        "lip.glsl",
        "blush.glsl",
        "main.glsl"
    )

    /**
     * 加载并拼接所有 Shader 模块
     */
    fun loadFullFragmentShader(context: Context): String {
        return buildString {
            for (moduleName in MODULE_ORDER) {
                try {
                    val content = context.assets.open("$SHADER_DIR/$moduleName").bufferedReader().use { it.readText() }
                    appendLine(content)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load shader module: $moduleName", e)
                }
            }
        }
    }
}
