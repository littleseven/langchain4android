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
     * 面向端侧小模型（Qwen3-1.7B）优化：
     * - 输出约束显式化（只允许一行 JSON）
     * - 导航语义强约束（避免 camera/gallery 混淆）
     * - 字段名白名单（降低模型发明字段概率）
     */
    private val basePrompt = """
你是 PicMe 的本地 AI 助手小觅（端侧小模型）。
任务：把用户输入转成一个 JSON 对象，只输出一行。

硬性规则：
1) 只能输出 JSON 对象，不要解释、不要 markdown、不要 <think>、不要前后缀文本。
2) 控制命令格式：{"action":"命令名", 参数...}
3) 聊天或不确定：{"action":"text_reply","message":"中文简短回复"}
4) 导航只能使用 navigate_to / go_back。
5) destination 只能是：camera / gallery / settings / debug。
6) 只允许这些参数键：smoothing, whitening, slim_face, big_eyes, lip_color, blush, eyebrow, filter, style, scene, ratio, exposure, zoom, mode, destination, message。
7) 不要输出未定义字段；不需要的参数不要输出。
8) 用户说"去相机/回相机/打开相机/去拍照"时，必须输出 destination="camera"。
9) 用户说"调高美颜/增强美颜/美颜"时，磨皮(smoothing)和美白(whitening)都提升到 60-70。
10) 用户说"打开前置/切前置/前置"时，必须输出 flip_camera。
11) 用户说"冷调/冷色/冷滤镜"时，必须输出 filter="COOL"。

场景映射示例（严格遵循）：
「拍张照」→ {"action":"capture"}
「调高美颜」→ {"action":"adjust_beauty","smoothing":65,"whitening":65}
「换个冷调滤镜」→ {"action":"switch_filter","filter":"COOL"}
「打开前置」→ {"action":"flip_camera"}
""".trimIndent()

    /**
     * 场景特定提示
     */
    private val scenePrompts = mapOf(
        SceneManager.Scene.CAMERA to "当前相机页：优先相机控制。仅当用户明确说去相册/去设置/返回时再导航。",
        SceneManager.Scene.GALLERY to "当前相册页：优先相册操作。用户说去相机/去拍照时必须导航到 camera。",
        SceneManager.Scene.SETTINGS to "当前设置页：优先设置操作。用户说去相机/回相机/打开相机时必须导航到 camera，不可导航到 gallery。",
        SceneManager.Scene.DEBUG to "当前调试页：优先调试相关；普通控制建议导航回 camera 或 settings。",
        SceneManager.Scene.UNKNOWN to "当前页面未知：优先使用导航或 text_reply。"
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
            appendLine(basePrompt)
            appendLine()
            appendLine("【当前页面】")
            appendLine(scenePrompts[currentScene] ?: scenePrompts[SceneManager.Scene.UNKNOWN])
            appendLine()
            appendLine("【可用命令】")
            appendLine(buildCapabilitiesSection(scene = currentScene))
            appendLine()
            appendLine("【当前状态】")
            appendLine(buildStateSection(context, currentScene))

            if (capabilities.isNotEmpty()) {
                appendLine()
                appendLine("【已激活能力】")
                appendLine(capabilities.joinToString(separator = ", ") { it.name })
            }
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

            // 历史压缩为最近 3 轮，避免本地模型上下文污染
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
     */
    fun buildBatchPrompt(userInput: String, context: AgentContext): String {
        return buildString {
            appendLine("你是 PicMe 的指令解析器。把用户输入解析为 JSON 数组。")
            appendLine()
            appendLine("输出硬规则：")
            appendLine("1. 仅输出一个 JSON 数组，禁止任何解释、禁止 markdown、禁止 <think>。")
            appendLine("2. 数组元素格式：{\"action\":\"命令\", 参数...}。")
            appendLine("3. 用户是闲聊时，输出 [{\"action\":\"text_reply\",\"message\":\"...\"}]。")
            appendLine("4. 导航只能是 navigate_to / go_back，destination 只能是 camera|gallery|settings|debug。")
            appendLine("5. 字段名必须使用既定键，不要创造新字段。")
            appendLine()
            appendLine("【当前状态】")
            appendLine(buildStateSection(context, sceneManager.currentScene.value))
            appendLine()
            appendLine("【命令全集】")
            appendLine(buildCapabilitiesSection(scene = null))
            appendLine()
            appendLine("【示例】")
            appendLine("用户: 去相机")
            appendLine("→ [{\"action\":\"navigate_to\",\"destination\":\"camera\"}]")
            appendLine("用户: 磨皮60并拍一张")
            appendLine("→ [{\"action\":\"adjust_beauty\",\"smoothing\":60},{\"action\":\"capture\"}]")
            appendLine("用户: 你好")
            appendLine("→ [{\"action\":\"text_reply\",\"message\":\"你好呀，我是小觅\"}]")
        }
    }

    /**
     * 构建 L3 Plan 模式 Prompt
     *
     * 输出格式为 ExecutionPlan JSON。
     */
    fun buildPlanPrompt(userInput: String, context: AgentContext): String {
        return buildString {
            appendLine("你是 PicMe 的任务编排器。把用户复杂请求转成 ExecutionPlan JSON。")
            appendLine()
            appendLine("输出硬规则：")
            appendLine("1. 只能输出一个 JSON 对象，禁止解释、禁止 markdown、禁止 <think>。")
            appendLine("2. 顶层字段固定：plan_id, description, steps。")
            appendLine("3. steps 每项字段固定：step, action, condition, wait_condition, repeat_count, description, delayMs。")
            appendLine("4. action 字段必须是命令对象：{\"action\":\"...\", ...}。")
            appendLine("5. wait_condition 仅支持：duration(delay_ms), face_detected(timeout_ms), smile_detected(timeout_ms), user_confirm(prompt)。")
            appendLine("6. repeat_count >= 1，delayMs >= 0。")
            appendLine("7. 导航动作严格使用 navigate_to/go_back。")
            appendLine()
            appendLine("【当前状态】")
            appendLine(buildStateSection(context, sceneManager.currentScene.value))
            appendLine()
            appendLine("【命令全集】")
            appendLine(buildCapabilitiesSection(scene = null, forPlan = true))
            appendLine()
            appendLine("【示例】")
            appendLine("用户: 去相机后等1秒连拍3张")
            appendLine("→ {\"plan_id\":\"plan_1\",\"description\":\"切到相机后连拍\",\"steps\":[{\"step\":1,\"action\":{\"action\":\"navigate_to\",\"destination\":\"camera\"},\"condition\":null,\"wait_condition\":null,\"repeat_count\":1,\"description\":\"切换到相机\",\"delayMs\":0},{\"step\":2,\"action\":{\"action\":\"text_reply\",\"message\":\"准备连拍\"},\"condition\":null,\"wait_condition\":{\"type\":\"duration\",\"delay_ms\":1000},\"repeat_count\":1,\"description\":\"等待1秒\",\"delayMs\":0},{\"step\":3,\"action\":{\"action\":\"capture\"},\"condition\":null,\"wait_condition\":null,\"repeat_count\":3,\"description\":\"连拍3张\",\"delayMs\":500}]}")
        }
    }

    /**
     * 构建 L4 Chat 模式 Prompt
     *
     * 纯文本对话，不输出 JSON。
     */
    fun buildChatPrompt(
        userInput: String,
        context: AgentContext,
        history: List<String> = emptyList()
    ): String {
        return buildString {
            appendLine("你是 PicMe 的摄影助手小觅。当前是聊天模式。")
            appendLine()
            appendLine("回复规则：")
            appendLine("1. 只输出中文自然语言，不要 JSON，不要 markdown。")
            appendLine("2. 语气简洁友好，优先给出可执行建议。")
            appendLine("3. 用户问能力范围时，聚焦相机/相册/设置可控能力。")
            appendLine("4. 与产品无关的问题，礼貌引导回拍摄与编辑场景。")
            appendLine()
            appendLine("【当前状态】")
            appendLine(buildStateSection(context, sceneManager.currentScene.value))

            if (history.isNotEmpty()) {
                appendLine()
                appendLine("【最近对话】")
                history.takeLast(5).forEachIndexed { index, message ->
                    appendLine("${index + 1}. $message")
                }
            }
        }
    }

    // ── 内部辅助方法 ────────────────────────────────────────────

    private fun buildStateSection(
        context: AgentContext,
        currentScene: SceneManager.Scene? = null
    ): String {
        val sceneName = currentScene?.name ?: context.scene.name
        return buildString {
            append("scene=")
            append(sceneName)
            append(", beauty=")
            append(if (context.beautySettings.enabled) "on" else "off")
            append(", smoothing=")
            append(context.beautySettings.smoothing.toInt())
            append(", whitening=")
            append(context.beautySettings.whitening.toInt())
            append(", slim_face=")
            append(context.beautySettings.slimFace.toInt())
            append(", big_eyes=")
            append(context.beautySettings.bigEyes.toInt())
            append(", lip_color=")
            append(context.beautySettings.lipColor.toInt())
            append(", blush=")
            append(context.beautySettings.blush.toInt())
            append(", eyebrow=")
            append(context.beautySettings.eyebrow.toInt())
            append(", filter=")
            append(context.filterType.name)
            append(", style=")
            append(context.styleFilter.name)
            append(", zoom=")
            append(context.zoomRatio)
            append(", exposure=")
            append(context.exposureCompensation)
            append(", mode=")
            append(context.captureMode.name)
            append(", recording=")
            append(if (context.isRecording) "1" else "0")
        }
    }

    private fun buildCapabilitiesSection(
        scene: SceneManager.Scene? = null,
        forPlan: Boolean = false
    ): String {
        val includeCamera = scene == null || scene == SceneManager.Scene.CAMERA
        val includeGallery = scene == null || scene == SceneManager.Scene.GALLERY
        val includeSettings = scene == null || scene == SceneManager.Scene.SETTINGS

        return buildString {
            appendLine("action 白名单（只能从下列 action 选择）：")

            if (includeCamera) {
                appendLine("- camera: capture, toggle_recording, flip_camera, switch_mode")
                appendLine("- camera_adjust: adjust_beauty, adjust_exposure, adjust_zoom")
                appendLine("- camera_style: switch_filter, switch_style, switch_scene, switch_ratio")
            }

            if (includeGallery) {
                appendLine("- gallery: view_media, delete_media, share_media, select_media, search_media, switch_view_mode, favorite_media")
            }

            if (includeSettings) {
                appendLine("- settings: change_theme, change_language, download_model, switch_face_engine, toggle_setting")
            }

            appendLine("- navigation: navigate_to(destination=camera|gallery|settings|debug), go_back")
            appendLine("- fallback: text_reply(message)")
            appendLine("参数约束: exposure=-2..2, zoom=0.5..10, ratio=4:3|16:9|full, mode=PHOTO|VIDEO|PORTRAIT|PRO|DOCUMENT")
            appendLine("滤镜: NONE|LEICA_CLASSIC|LEICA_VIBRANT|LEICA_BW|FILM_GOLD|FILM_FUJI|VINTAGE|COOL|WARM")
            appendLine("风格: NONE|TOON|SKETCH|POSTERIZE|EMBOSS|CROSSHATCH")
            appendLine("导航映射: 去相机/回相机/打开相机/去拍照->camera; 去相册/打开相册->gallery; 去设置/打开设置->settings; 返回/上一页/后退->go_back")
            appendLine("导航示例: {\"action\":\"navigate_to\",\"destination\":\"camera\"}")

            if (forPlan) {
                appendLine("Plan 字段约束: step(Int), action(Object), condition(String|null), wait_condition(Object|null), repeat_count(Int>=1), description(String), delayMs(Long>=0)")
                appendLine("wait_condition 示例: {\"type\":\"duration\",\"delay_ms\":1000}")
            }
        }
    }
}
