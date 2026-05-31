package com.picme.domain.agent

import com.picme.domain.agent.capability.Capability
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.SceneManager

/**
 * Prompt 构建器
 *
 * 分层构建 system prompt：
 * - Base: 通用规则（JSON 格式、回复风格等）
 * - Scene: 场景特定能力和约束
 * - Capability: 各 Capability 的自描述
 *
 * 同时支持远程 LLM 编排所需的三种 Prompt 模板：
 * - Batch: L2 批量命令解析（JSON 数组）
 * - Plan: L3 计划执行（ExecutionPlan JSON）
 * - Chat: L4 纯文本对话
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
你是PicMe助手小觅。只输出一行JSON。
规则:1.控制格式{"action":"命令",参数...} 2.聊天{"action":"text_reply","message":"回复"} 3.禁止think标签和markdown 4.导航必须用navigate_to/go_back 5.无法理解{"action":"text_reply","message":"抱歉我没理解"}

命令:
""".trimIndent()

    /**
     * 场景特定提示
     */
    private val scenePrompts = mapOf(
        SceneManager.Scene.CAMERA to "当前相机页:拍照/录像/翻转/美颜/滤镜/风格/变焦/曝光/比例/模式/导航",
        SceneManager.Scene.GALLERY to "当前相册页:查看/删除/分享/搜索/视图切换/选择/导航",
        SceneManager.Scene.SETTINGS to "当前设置页:主题/语言/模型下载/人脸引擎/开关设置",
        SceneManager.Scene.DEBUG to "当前调试页",
        SceneManager.Scene.UNKNOWN to "当前页面未知，只能导航"
    )

    /**
     * 构建完整的 system prompt（本地 LLM 使用）
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

            // 3. Capability 详细说明（使用压缩版）
            appendLine()
            appendLine("【命令】")
            appendLine(buildCapabilitiesSection())

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

    // ── 远程 LLM Prompt 模板（L2/L3/L4）────────────────────────────

    /**
     * 构建 L2 Batch 模式 Prompt
     *
     * 输出格式为 JSON 数组，每个元素是一个命令对象。
     *
     * @param userInput 用户输入
     * @param context 当前 Agent 上下文
     * @return Batch 模式 system prompt
     */
    fun buildBatchPrompt(userInput: String, context: AgentContext): String {
        return buildString {
            appendLine("你是 PicMe 相机的 AI 助手小觅。用户通过语音或文字与你交互。")
            appendLine()
            appendLine("【绝对规则 - 必须遵守】")
            appendLine("1. 无论用户要求什么，你的回复永远只输出一个 JSON 数组，不要任何其他文字、解释、标点或换行")
            appendLine("2. 数组中每个元素是一个命令对象: {\"action\":\"命令名\", 参数...}")
            appendLine("3. 命令按数组顺序依次执行")
            appendLine("4. 如果用户只是聊天，输出: [{\"action\":\"text_reply\",\"message\":\"用中文友好回复\"}]")
            appendLine("5. 绝对不要输出 <think> 标签或思考过程")
            appendLine("6. 绝对不要输出 markdown 代码块 ```")
            appendLine()
            appendLine(buildStateSection(context))
            appendLine()
            appendLine(buildCapabilitiesSection())
            appendLine()
            appendLine("【示例 - 严格模仿】")
            appendLine("用户: 磨皮开到60，美白30，然后拍一张")
            appendLine("→ [{\"action\":\"adjust_beauty\",\"smoothing\":60},{\"action\":\"adjust_beauty\",\"whitening\":30},{\"action\":\"capture\"}]")
            appendLine("用户: 你好")
            appendLine("→ [{\"action\":\"text_reply\",\"message\":\"你好呀，我是小觅！\"}]")
            appendLine("用户: 切徕卡黑白再拍照")
            appendLine("→ [{\"action\":\"switch_filter\",\"filter\":\"LEICA_BW\"},{\"action\":\"capture\"}]")
        }
    }

    /**
     * 构建 L3 Plan 模式 Prompt
     *
     * 输出格式为 ExecutionPlan JSON。
     *
     * @param userInput 用户输入
     * @param context 当前 Agent 上下文
     * @return Plan 模式 system prompt
     */
    fun buildPlanPrompt(userInput: String, context: AgentContext): String {
        return buildString {
            appendLine("你是 PicMe 相机的 AI 助手小觅。用户可能提出包含条件或复杂步骤的请求。")
            appendLine()
            appendLine("【输出格式 - 严格 JSON】")
            appendLine("{")
            appendLine("  \"plan_id\": \"plan_1\",")
            appendLine("  \"description\": \"计划描述\",")
            appendLine("  \"steps\": [")
            appendLine("    {\"step\":1, \"condition\":\"条件表达式或null\", \"wait_condition\":null, \"repeat_count\":1, \"action\":{\"action\":\"命令名\",...}, \"description\":\"步骤描述\", \"delayMs\":500},")
            appendLine("    {\"step\":2, \"condition\":null, \"wait_condition\":null, \"repeat_count\":1, \"action\":{\"action\":\"命令名\",...}, \"description\":\"步骤描述\", \"delayMs\":0}")
            appendLine("  ]")
            appendLine("}")
            appendLine()
            appendLine("【规则】")
            appendLine("1. condition 字段：需要条件判断时填写描述性条件，无条件时填 null")
            appendLine("2. wait_condition 字段：需要等待条件时填写， null 表示不等待。当前支持的等待类型：")
            appendLine("   - {\"type\":\"duration\",\"delay_ms\":1000} 等待固定时长（毫秒）")
            appendLine("   - {\"type\":\"face_detected\",\"timeout_ms\":10000} 等待检测到人脸")
            appendLine("   - {\"type\":\"user_confirm\",\"prompt\":\"请确认\"} 等待用户确认（预留）")
            appendLine("3. repeat_count 字段：同一动作重复执行次数，默认 1。如\"连拍3张\"则 repeat_count=3")
            appendLine("4. delayMs 字段：给 UI 反应时间的延迟（毫秒），拍照建议 500ms，其他 0ms")
            appendLine("5. 绝对不要输出任何其他文字")
            appendLine()
            appendLine(buildStateSection(context))
            appendLine()
            appendLine(buildCapabilitiesSection())
            appendLine()
            appendLine("【示例1：条件+连拍】")
            appendLine("用户: 如果是后置摄像头就切前置，然后设置磨皮80美白60，最后拍一张")
            appendLine("→ {\"plan_id\":\"plan_1\",\"description\":\"切换前置并拍摄人像\",\"steps\":[{\"step\":1,\"condition\":\"当前是后置摄像头\",\"wait_condition\":null,\"repeat_count\":1,\"action\":{\"action\":\"flip_camera\"},\"description\":\"切换到前置摄像头\",\"delayMs\":300},{\"step\":2,\"condition\":null,\"wait_condition\":null,\"repeat_count\":1,\"action\":{\"action\":\"adjust_beauty\",\"smoothing\":80,\"whitening\":60},\"description\":\"设置人像美颜参数\",\"delayMs\":0},{\"step\":3,\"condition\":null,\"wait_condition\":null,\"repeat_count\":1,\"action\":{\"action\":\"capture\"},\"description\":\"拍照\",\"delayMs\":500}]}")
            appendLine()
            appendLine("【示例2：等待+连拍】")
            appendLine("用户: 打开美颜，然后脸再瘦一点，等1秒后连拍三张")
            appendLine("→ {\"plan_id\":\"plan_2\",\"description\":\"开启美颜瘦脸后连拍\",\"steps\":[{\"step\":1,\"condition\":null,\"wait_condition\":null,\"repeat_count\":1,\"action\":{\"action\":\"adjust_beauty\",\"smoothing\":50,\"whitening\":50,\"slim_face\":30,\"enabled\":true},\"description\":\"打开美颜\",\"delayMs\":0},{\"step\":2,\"condition\":null,\"wait_condition\":null,\"repeat_count\":1,\"action\":{\"action\":\"adjust_beauty\",\"slim_face\":50},\"description\":\"瘦脸再加强\",\"delayMs\":0},{\"step\":3,\"condition\":null,\"wait_condition\":{\"type\":\"duration\",\"delay_ms\":1000},\"repeat_count\":1,\"action\":{\"action\":\"text_reply\",\"message\":\"准备连拍\"},\"description\":\"等待1秒\",\"delayMs\":0},{\"step\":4,\"condition\":null,\"wait_condition\":null,\"repeat_count\":3,\"action\":{\"action\":\"capture\"},\"description\":\"连拍3张\",\"delayMs\":500}]}")
        }
    }

    /**
     * 构建 L4 Chat 模式 Prompt
     *
     * 纯文本对话，不输出 JSON。
     *
     * @param userInput 用户输入
     * @param context 当前 Agent 上下文
     * @param history 历史对话记录（可选）
     * @return Chat 模式 system prompt
     */
    fun buildChatPrompt(
        userInput: String,
        context: AgentContext,
        history: List<String> = emptyList()
    ): String {
        return buildString {
            appendLine("你是 PicMe 相机的 AI 助手小觅。用户的问题可能无法直接映射到相机命令，请友好回复。")
            appendLine()
            appendLine("【规则】")
            appendLine("1. 用中文友好、简洁地回复")
            appendLine("2. 如果用户问你能做什么，列出你可以控制的相机功能")
            appendLine("3. 如果用户的问题与相机无关，礼貌地引导回相机功能")
            appendLine("4. 绝对不要输出 JSON 格式")
            appendLine()
            appendLine(buildStateSection(context))

            if (history.isNotEmpty()) {
                appendLine()
                appendLine("【历史对话】")
                history.forEachIndexed { index, message ->
                    appendLine("${index + 1}. $message")
                }
            }
        }
    }

    // ── 内部辅助方法 ────────────────────────────────────────────

    private fun buildStateSection(context: AgentContext): String {
        return buildString {
            append("状态:")
            append("美颜${if (context.beautySettings.enabled) "开" else "关"}")
            append("磨${context.beautySettings.smoothing.toInt()}")
            append("白${context.beautySettings.whitening.toInt()}")
            append("瘦${context.beautySettings.slimFace.toInt()}")
            append("眼${context.beautySettings.bigEyes.toInt()}")
            append("唇${context.beautySettings.lipColor.toInt()}")
            append("腮${context.beautySettings.blush.toInt()}")
            append("眉${context.beautySettings.eyebrow.toInt()}")
            append("滤${context.filterType.name}")
            append("风${context.styleFilter.name}")
            append("焦${context.zoomRatio}x")
            append("曝${context.exposureCompensation}")
            appendLine("模${context.captureMode.name}")
        }
    }

    private fun buildCapabilitiesSection(): String {
        return buildString {
            appendLine("命令:adjust_beauty(smoothing/whitening/slim_face/big_eyes/lip_color/blush/eyebrow)")
            appendLine("switch_filter(NONE/LEICA_CLASSIC/LEICA_VIBRANT/LEICA_BW/FILM_GOLD/FILM_FUJI/VINTAGE/COOL/WARM)")
            appendLine("switch_style(NONE/TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH)")
            appendLine("switch_scene(night/moon/none) switch_ratio(4:3/16:9/full) adjust_exposure(-2~2) adjust_zoom(0.5~10)")
            appendLine("flip_camera capture toggle_recording switch_mode(PHOTO/VIDEO/PORTRAIT/PRO/DOCUMENT)")
            appendLine("navigate_to(camera/gallery/settings/debug) go_back text_reply")
            appendLine("映射:无/NONE 徕卡经典/CLASSIC 鲜艳/VIBRANT 黑白/BW 胶片金/GOLD 富士/FUJI 复古/VINTAGE")
            appendLine("卡通/TOON 素描/SKETCH 拍照/PHOTO 录像/VIDEO 人像/PORTRAIT 专业/PRO 文档/DOCUMENT")
            appendLine("夜景/night 月亮/moon 4比3/4:3 16比9/16:9 全屏/full 相机/camera 相册/gallery 设置/settings")
            appendLine("导航:去相册→{\"action\":\"navigate_to\",\"destination\":\"gallery\"} 去相机→camera 返回→go_back")
        }
    }
}
