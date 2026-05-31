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

        SceneManager.Scene.DEBUG to """
当前在调试页面。
""".trimIndent(),

        SceneManager.Scene.UNKNOWN to """
当前页面未知，只能进行页面导航。
""".trimIndent()
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
            appendLine("【当前相机状态】")
            appendLine(
                "美颜=${if (context.beautySettings.enabled) "开" else "关"}, " +
                    "磨皮=${context.beautySettings.smoothing.toInt()}, " +
                    "美白=${context.beautySettings.whitening.toInt()}, " +
                    "瘦脸=${context.beautySettings.slimFace.toInt()}, " +
                    "大眼=${context.beautySettings.bigEyes.toInt()}, " +
                    "唇色=${context.beautySettings.lipColor.toInt()}, " +
                    "腮红=${context.beautySettings.blush.toInt()}, " +
                    "眉毛=${context.beautySettings.eyebrow.toInt()}"
            )
            appendLine(
                "滤镜=${context.filterType.name}, " +
                    "风格=${context.styleFilter.name}, " +
                    "变焦=${context.zoomRatio}x, " +
                    "曝光=${context.exposureCompensation}, " +
                    "模式=${context.captureMode.name}"
            )
        }
    }

    private fun buildCapabilitiesSection(): String {
        return buildString {
            appendLine("【可用命令】")
            appendLine("adjust_beauty: smoothing=0~100(磨皮), whitening=0~100(美白), slim_face=-50~50(瘦脸), big_eyes=0~100(大眼), lip_color=0~100(唇色), blush=0~100(腮红), eyebrow=0~100(眉毛)")
            appendLine("switch_filter: filter=NONE|LEICA_CLASSIC|LEICA_VIBRANT|LEICA_BW|FILM_GOLD|FILM_FUJI|VINTAGE|COOL|WARM")
            appendLine("switch_style: style=NONE|TOON|SKETCH|POSTERIZE|EMBOSS|CROSSHATCH")
            appendLine("switch_scene: scene=night|moon|none")
            appendLine("switch_ratio: ratio=4:3|16:9|full")
            appendLine("adjust_exposure: exposure=-2~2")
            appendLine("adjust_zoom: zoom=0.5~10.0")
            appendLine("flip_camera: 翻转前后摄像头")
            appendLine("capture: 拍照")
            appendLine("toggle_recording: 开始/停止录像")
            appendLine("switch_mode: mode=PHOTO|VIDEO|PORTRAIT|PRO|DOCUMENT")
            appendLine("text_reply: 普通聊天回复")
            appendLine()
            appendLine("【中文名称映射】")
            appendLine("滤镜: 无→NONE, 徕卡经典→LEICA_CLASSIC, 徕卡鲜艳→LEICA_VIBRANT, 徕卡黑白→LEICA_BW")
            appendLine("滤镜: 胶片金→FILM_GOLD, 胶片富士→FILM_FUJI, 复古→VINTAGE, 冷调→COOL, 暖调→WARM")
            appendLine("风格: 无→NONE, 卡通→TOON, 素描→SKETCH, 色调分离→POSTERIZE, 浮雕→EMBOSS, 交叉线→CROSSHATCH")
            appendLine("模式: 拍照→PHOTO, 录像→VIDEO, 人像→PORTRAIT, 专业→PRO, 文档→DOCUMENT")
            appendLine("场景: 夜景→night, 月亮→moon, 关闭→none")
            appendLine("比例: 4比3→4:3, 16比9→16:9, 全屏→full")
        }
    }
}
