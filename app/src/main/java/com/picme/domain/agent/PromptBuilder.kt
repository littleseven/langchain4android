package com.picme.domain.agent

import com.picme.domain.agent.capability.Capability
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.SceneManager

/**
 * Prompt 构建器
 *
 * 分层构建 system prompt：
 * - Base: 通用规则（JSON 格式、回复风格等）
 * - Scene: 场景特定能力和约束
 * - Capability: 各 Capability 的自描述
 */
class PromptBuilder(
    private val sceneManager: SceneManager
) {

    /**
     * 基础 Prompt 模板
     *
     * 针对 Qwen3-0.6B 小模型优化：
     * - 统一 JSON 输出（消除聊天/控制输出的矛盾）
     * - Few-shot 示例
     * - 参数范围明确
     */
    private val basePrompt = """
你是 PicMe 的 AI 助手小觅，帮助用户控制相机和管理照片。

【绝对规则 - 必须遵守】
1. 无论用户要求什么，回复永远只输出一行 JSON，不要任何其他文字、解释、标点或换行
2. 控制设备格式: {"action": "action_name", 参数...}
3. 聊天回复格式: {"action": "text_reply", "message": "用中文友好回复"}
4. 绝对不要输出 <think> 标签或思考过程
5. 绝对不要输出 markdown 代码块 ```
6. 如果无法理解意图，输出 {"action": "text_reply", "message": "抱歉我没理解，请换一种说法"}

【可用命令】
""".trimIndent()

    /**
     * 场景特定提示
     */
    private val scenePrompts = mapOf(
        SceneManager.Scene.CAMERA to """
当前在相机页面，可用操作：
- 拍照、录像、翻转摄像头
- 调节美颜（磨皮、美白、瘦脸、大眼、唇色、腮红）
- 切换滤镜、风格、场景模式
- 调节变焦、曝光
- 切换画幅比例（4:3, 16:9, full）
- 切换拍摄模式（照片/视频）
""".trimIndent(),

        SceneManager.Scene.GALLERY to """
当前在相册页面，可用操作：
- 查看照片/视频
- 删除照片/视频
- 分享照片/视频
- 搜索照片
- 切换视图模式（网格/列表/时间线）
- 选择/取消选择（多选模式）
""".trimIndent(),

        SceneManager.Scene.SETTINGS to """
当前在设置页面，可用操作：
- 切换主题（浅色/深色/跟随系统）
- 切换语言（中文/英文/繁体）
- 下载 AI 模型
- 切换人脸检测引擎
- 开启/关闭各种设置项
""".trimIndent(),

        SceneManager.Scene.EDITOR to """
当前在照片编辑页面，可用操作：
- 应用美颜效果
- 撤销/重做编辑
- 保存编辑结果
""".trimIndent(),

        SceneManager.Scene.DEBUG to """
当前在调试页面。
""".trimIndent(),

        SceneManager.Scene.UNKNOWN to """
当前页面未知，只能进行页面导航。
""".trimIndent()
    )

    /**
     * 构建完整的 system prompt
     *
     * @param capabilities 当前可用的 Capability 列表
     * @param context Agent 上下文
     * @return 完整的 system prompt
     */
    fun buildSystemPrompt(
        capabilities: List<Capability>,
        context: AgentContext
    ): String {
        val currentScene = sceneManager.currentScene.value

        return buildString {
            // 1. 基础 Prompt
            appendLine(basePrompt)

            // 2. 场景特定提示
            appendLine()
            appendLine("【当前页面】")
            appendLine(scenePrompts[currentScene] ?: scenePrompts[SceneManager.Scene.UNKNOWN])

            // 3. Capability 详细说明
            appendLine()
            appendLine("【详细功能说明】")
            capabilities.forEach { capability ->
                appendLine(capability.buildCapabilityDescription())
            }

            // 4. 当前状态
            appendLine()
            appendLine("【当前状态】")
            appendLine("场景: ${currentScene.name}")
            appendLine("美颜: 磨皮=${context.beautySettings.smoothing}, 美白=${context.beautySettings.whitening}")
            appendLine("滤镜: ${context.filterType.name}, 风格: ${context.styleFilter.name}")
            appendLine("变焦: ${context.zoomRatio}x, 曝光: ${context.exposureCompensation}")
            appendLine("模式: ${context.captureMode.name}")
        }
    }

    /**
     * 构建单轮对话的完整 prompt（兼容 MNN-LLM）
     */
    fun buildPrompt(
        systemPrompt: String,
        userInput: String,
        history: List<Pair<String, String>> = emptyList()
    ): String {
        return buildString {
            appendLine("system:")
            appendLine(systemPrompt)
            appendLine()

            // 添加历史对话（简化版，避免超长）
            history.takeLast(3).forEach { (user, assistant) ->
                appendLine("user:")
                appendLine(user)
                appendLine()
                appendLine("assistant:")
                appendLine(assistant)
                appendLine()
            }

            appendLine("user:")
            appendLine(userInput)
            appendLine()
            append("assistant:")
        }
    }
}
