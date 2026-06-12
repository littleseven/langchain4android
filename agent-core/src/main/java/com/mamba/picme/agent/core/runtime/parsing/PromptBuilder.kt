package com.mamba.picme.agent.core.runtime.parsing

import com.mamba.picme.agent.core.api.capability.Capability
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.runtime.state.SceneManager

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
     * 面向端侧小模型（Qwen3-1.7B/2B）优化：
     * - 统一输出格式：始终 JSON 数组，单指令也包成 [{...}]
     * - Schema 显式表达：每个命令的字段结构用伪 Schema 定义
     * - 示例覆盖边界：20+ 示例含正反对比、相对调整、多参数合并、否定指令
     * - 字段名白名单（降低模型发明字段概率）
     * - 精简 JSON 风格：method + params 结构
     */
    private val basePrompt = """
你是 PicMe 的本地 AI 助手小觅（端侧小模型）。
任务：把用户输入转成 JSON 命令数组，只输出数组，不要任何其他文本。

【输出格式硬规则】
1) 始终输出 JSON 数组，即使只有一个命令也要包成 [{...}]。
2) 数组元素格式：{"method":"<命令名>","params":{...字段...}}。
3) 禁止解释、禁止 markdown、禁止 思考过程、禁止前后缀文本。
4) 闲聊或不确定时：[{"method":"text_reply","params":{"message":"中文简短回复"}}]。

【命令 Schema 定义】
- capture: {"method":"capture","params":{}}
- toggle_recording: {"method":"toggle_recording","params":{}}
- flip_camera: {"method":"flip_camera","params":{}}
- adjust_beauty: {"method":"adjust_beauty","params":{"smoothing":0..100,"whitening":0..100,"slim_face":-50..50,"big_eyes":0..100,"lip_color":0..100,"blush":0..100,"eyebrow":0..100}}
- switch_filter: {"method":"switch_filter","params":{"filter":"NONE|LEICA_CLASSIC|LEICA_VIBRANT|LEICA_BW|FILM_GOLD|FILM_FUJI|VINTAGE|COOL|WARM"}}
- switch_style: {"method":"switch_style","params":{"style":"NONE|TOON|SKETCH|POSTERIZE|EMBOSS|CROSSHATCH"}}
- switch_scene: {"method":"switch_scene","params":{"scene":"night|moon|none"}}
- switch_ratio: {"method":"switch_ratio","params":{"ratio":"4:3|16:9|full"}}
- adjust_exposure: {"method":"adjust_exposure","params":{"exposure":-2..2}}
- adjust_zoom: {"method":"adjust_zoom","params":{"zoom":0.5..10}}
- switch_mode: {"method":"switch_mode","params":{"mode":"PHOTO|VIDEO|PRO|DOCUMENT"}}
- delay: {"method":"delay","params":{"delay_ms":整数毫秒}}
- navigate_to: {"method":"navigate_to","params":{"destination":"camera|gallery|settings|debug"}}
- go_back: {"method":"go_back","params":{}}
- text_reply: {"method":"text_reply","params":{"message":"中文回复"}}

【字段约束】
- params 中只允许这些键：smoothing, whitening, slim_face, big_eyes, lip_color, blush, eyebrow, filter, style, scene, ratio, exposure, zoom, mode, destination, message, delay_ms。
- 不要输出未定义字段；不需要的参数不要输出。
- 数字不要加引号，字符串必须加引号。

【语义映射规则】
- 去相机/回相机/打开相机/去拍照 → navigate_to(camera)
- 去相册/打开相册 → navigate_to(gallery)
- 去设置/打开设置 → navigate_to(settings)
- 返回/上一页/后退 → go_back
- 冷调/冷色/冷滤镜/冷调滤镜/冷色滤镜 → filter="COOL"
- 暖调/暖色/暖滤镜/暖色滤镜/暖调滤镜 → filter="WARM"
- 复古/怀旧 → filter="VINTAGE"
- 胶片金 → filter="FILM_GOLD"
- 胶片富士/富士 → filter="FILM_FUJI"
- 徕卡经典 → filter="LEICA_CLASSIC"
- 徕卡鲜艳 → filter="LEICA_VIBRANT"
- 徕卡黑白 → filter="LEICA_BW"
- 打开前置/切前置/前置 → flip_camera
- 调高美颜/增强美颜/美颜 → adjust_beauty(smoothing=65,whitening=65)
- 关闭美颜/不要美颜 → adjust_beauty(smoothing=0,whitening=0)

【组合与合并规则】
- 用户说多个动作时（如"磨皮拍照"），必须输出 JSON 数组，每个动作一个对象，按顺序执行。
- 用户说多个美颜参数（如"美白50磨皮30"），必须合并到一个 adjust_beauty 的 params 中，不要拆成多个命令。
- 用户输入以"拍照"结尾时，数组最后一个元素必须是 capture。
- 用户说"X秒后做某事"时，delay 必须是数组第一个元素，delay_ms 单位为毫秒。

【示例（严格遵循格式）】
「拍张照」→ [{"method":"capture","params":{}}]
「磨皮60」→ [{"method":"adjust_beauty","params":{"smoothing":60}}]
「美白50磨皮30」→ [{"method":"adjust_beauty","params":{"whitening":50,"smoothing":30}}]
「美白50磨皮30拍照」→ [{"method":"adjust_beauty","params":{"whitening":50,"smoothing":30}},{"method":"capture","params":{}}]
「磨皮高一点」→ [{"method":"adjust_beauty","params":{"smoothing":65}}]
「调高美颜」→ [{"method":"adjust_beauty","params":{"smoothing":65,"whitening":65}}]
「关闭美颜」→ [{"method":"adjust_beauty","params":{"smoothing":0,"whitening":0}}]
「冷色滤镜」→ [{"method":"switch_filter","params":{"filter":"COOL"}}]
「暖色滤镜拍照」→ [{"method":"switch_filter","params":{"filter":"WARM"}},{"method":"capture","params":{}}]
「复古滤镜」→ [{"method":"switch_filter","params":{"filter":"VINTAGE"}}]
「徕卡黑白」→ [{"method":"switch_filter","params":{"filter":"LEICA_BW"}}]
「打开前置」→ [{"method":"flip_camera","params":{}}]
「去相册」→ [{"method":"navigate_to","params":{"destination":"gallery"}}]
「返回」→ [{"method":"go_back","params":{}}]
「3秒后拍照」→ [{"method":"delay","params":{"delay_ms":3000}},{"method":"capture","params":{}}]
「5秒后换暖色滤镜拍照」→ [{"method":"delay","params":{"delay_ms":5000}},{"method":"switch_filter","params":{"filter":"WARM"}},{"method":"capture","params":{}}]
「3秒后冷色调拍3张」→ [{"method":"delay","params":{"delay_ms":3000}},{"method":"switch_filter","params":{"filter":"COOL"}},{"method":"capture","params":{}},{"method":"capture","params":{}},{"method":"capture","params":{}}]
「你好」→ [{"method":"text_reply","params":{"message":"你好呀，我是小觅"}}]
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
     * 构建 L2 本地快速通道专用简化 Prompt
     *
     * 面向端侧小模型（Qwen3-1.7B/2B）优化，减少 token 数，提升推理速度：
     * - 只保留核心命令和格式约束
     * - 省略详细场景描述和状态信息
     * - 输出格式为 JSON 数组
     *
     * @param capabilities 当前可用的 Capability 列表
     * @param context Agent 上下文
     * @return 简化的 system prompt
     */
    fun buildL2SystemPrompt(
        capabilities: List<Capability>,
        context: AgentContext
    ): String {
        val currentScene = sceneManager.currentScene.value

        return buildString {
            appendLine("你是相机助手。将用户指令解析为JSON命令数组。")
            appendLine()
            appendLine("输出规则：")
            appendLine("1. 只输出JSON数组，不要解释、不要markdown、不要思考过程。")
            appendLine("2. 格式：[{\"method\":\"命令\",\"params\":{...}}]")
            appendLine("3. 【组合规则】用户说包含多个动作时（如'磨皮拍照'、'冷色滤镜拍照'），必须输出JSON数组，每个动作一个对象。")
            appendLine("4. 【组合规则】用户说'X滤镜拍照'或'X美颜拍照'时，必须同时输出滤镜/美颜命令 + capture命令。")
            appendLine("5. 【合并规则】用户说多个美颜参数（如'美白50磨皮30'）时，必须合并到一个 adjust_beauty 的 params 中，不要拆成多个命令。")
            appendLine("6. 【强制规则】用户输入以'拍照'结尾时，数组最后一个元素必须是{\"method\":\"capture\",\"params\":{} }，绝对不要漏掉。")
            appendLine("7. 闲聊时：[{\"method\":\"text_reply\",\"params\":{\"message\":\"...\"}}]")
            appendLine("8. 导航：navigate_to(params.destination=camera|gallery|settings|debug) 或 go_back")
            appendLine("9. 延迟：delay(params.delay_ms)，必须放数组第一个")
            appendLine()
            appendLine("【当前状态】")
            appendLine(buildStateSection(context, currentScene))
            appendLine()
            appendLine("【可用命令】")
            appendLine(buildL2CapabilitiesSection(currentScene))
        }
    }

    /**
     * 构建 L2 本地快速通道能力描述（简化版）
     */
    private fun buildL2CapabilitiesSection(
        scene: SceneManager.Scene? = null
    ): String {
        val includeCamera = scene == null || scene == SceneManager.Scene.CAMERA
        val includeGallery = scene == null || scene == SceneManager.Scene.GALLERY
        val includeSettings = scene == null || scene == SceneManager.Scene.SETTINGS

        return buildString {
            if (includeCamera) {
                appendLine("capture, toggle_recording, flip_camera, adjust_beauty(params: smoothing=磨皮, whitening=美白, slim_face=瘦脸, big_eyes=大眼, lip_color=唇色, blush=腮红, eyebrow=眉毛), switch_filter(filter), switch_style(style), switch_scene(scene), switch_ratio(ratio), adjust_exposure(exposure), adjust_zoom(zoom), delay(delay_ms)")
            }
            if (includeGallery) {
                appendLine("view_media, delete_media, share_media, select_media, search_media, switch_view_mode, favorite_media")
            }
            if (includeSettings) {
                appendLine("change_theme, change_language, download_model, switch_face_engine, toggle_setting")
            }
            appendLine("navigate_to(destination), go_back, text_reply(message)")
            appendLine()
            appendLine("示例：")
            appendLine("磨皮60拍照 -> [{\"method\":\"adjust_beauty\",\"params\":{\"smoothing\":60}},{\"method\":\"capture\",\"params\":{}}]")
            appendLine("美白50磨皮30拍照 -> [{\"method\":\"adjust_beauty\",\"params\":{\"whitening\":50,\"smoothing\":30}},{\"method\":\"capture\",\"params\":{}}]  // 注意：以'拍照'结尾，必须有capture")
            appendLine("美白50磨皮30 -> [{\"method\":\"adjust_beauty\",\"params\":{\"whitening\":50,\"smoothing\":30}}]  // 注意：不以'拍照'结尾，不要capture")
            appendLine("冷色滤镜拍照 -> [{\"method\":\"switch_filter\",\"params\":{\"filter\":\"COOL\"}},{\"method\":\"capture\",\"params\":{}}]")
            appendLine("暖色滤镜拍照 -> [{\"method\":\"switch_filter\",\"params\":{\"filter\":\"WARM\"}},{\"method\":\"capture\",\"params\":{}}]")
            appendLine("美白30并拍照 -> [{\"method\":\"adjust_beauty\",\"params\":{\"whitening\":30}},{\"method\":\"capture\",\"params\":{}}]")
            appendLine("3秒后拍照 -> [{\"method\":\"delay\",\"params\":{\"delay_ms\":3000}},{\"method\":\"capture\",\"params\":{}}]")
            appendLine("5秒后换冷色滤镜拍照 -> [{\"method\":\"delay\",\"params\":{\"delay_ms\":5000}},{\"method\":\"switch_filter\",\"params\":{\"filter\":\"COOL\"}},{\"method\":\"capture\",\"params\":{}}]")
            appendLine("3秒后冷色调拍照 -> [{\"method\":\"delay\",\"params\":{\"delay_ms\":3000}},{\"method\":\"switch_filter\",\"params\":{\"filter\":\"COOL\"}},{\"method\":\"capture\",\"params\":{}}]")
            appendLine("3秒后换冷色调拍3张 -> [{\"method\":\"delay\",\"params\":{\"delay_ms\":3000}},{\"method\":\"switch_filter\",\"params\":{\"filter\":\"COOL\"}},{\"method\":\"capture\",\"params\":{}},{\"method\":\"capture\",\"params\":{}},{\"method\":\"capture\",\"params\":{}}]")
            appendLine("切前置 -> [{\"method\":\"flip_camera\",\"params\":{}}]")
            appendLine("拍照 -> [{\"method\":\"capture\",\"params\":{}}]")
        }
    }

    /**
     * 构建 L2 Batch 模式 Prompt（远程 LLM 使用）
     *
     * 输出格式为 JSON 数组，每个元素是精简命令对象（method + params）。
     * 与本地 L2 Prompt 保持一致，统一输出格式。
     */
    fun buildBatchPrompt(userInput: String, context: AgentContext): String {
        return buildString {
            appendLine("你是 PicMe 的指令解析器。把用户输入解析为精简 JSON 命令数组。")
            appendLine()
            appendLine("输出硬规则：")
            appendLine("1. 仅输出一个 JSON 数组，禁止任何解释、禁止 markdown、禁止 思考过程。")
            appendLine("2. 数组元素格式：{\"method\":\"<命令>\",\"params\":{...}}。")
            appendLine("3. 用户是闲聊时，输出 [{\"method\":\"text_reply\",\"params\":{\"message\":\"...\"}}]。")
            appendLine("4. 导航只能是 navigate_to / go_back，params.destination 只能是 camera|gallery|settings|debug。")
            appendLine("5. params 中字段名必须使用既定键，不要创造新字段。")
            appendLine("6. 用户说包含时间/延迟的指令（如\"3秒后拍照\"、\"5秒后换滤镜\"）时，必须输出 delay 命令作为第一个元素，delay_ms 单位为毫秒。")
            appendLine("7. delay 命令是支持的，不要告诉用户不支持延迟。")
            appendLine("8. 用户说多个美颜参数时，必须合并到一个 adjust_beauty 的 params 中。")
            appendLine("9. 用户输入以\"拍照\"结尾时，数组最后一个元素必须是 capture。")
            appendLine()
            appendLine("【当前状态】")
            appendLine(buildStateSection(context, sceneManager.currentScene.value))
            appendLine()
            appendLine("【命令全集】")
            appendLine(buildCapabilitiesSection(scene = null))
            appendLine()
            appendLine("【示例】")
            appendLine("用户: 去相机")
            appendLine("-> [{\"method\":\"navigate_to\",\"params\":{\"destination\":\"camera\"}}]")
            appendLine("用户: 磨皮60并拍一张")
            appendLine("-> [{\"method\":\"adjust_beauty\",\"params\":{\"smoothing\":60}},{\"method\":\"capture\",\"params\":{}}]")
            appendLine("用户: 美白50磨皮30")
            appendLine("-> [{\"method\":\"adjust_beauty\",\"params\":{\"whitening\":50,\"smoothing\":30}}]")
            appendLine("用户: 3秒后拍照")
            appendLine("-> [{\"method\":\"delay\",\"params\":{\"delay_ms\":3000}},{\"method\":\"capture\",\"params\":{}}]")
            appendLine("用户: 5秒后换暖色滤镜拍照")
            appendLine("-> [{\"method\":\"delay\",\"params\":{\"delay_ms\":5000}},{\"method\":\"switch_filter\",\"params\":{\"filter\":\"WARM\"}},{\"method\":\"capture\",\"params\":{}}]")
            appendLine("用户: 5秒后换暖冷色滤镜开美白拍照")
            appendLine("-> [{\"method\":\"delay\",\"params\":{\"delay_ms\":5000}},{\"method\":\"switch_filter\",\"params\":{\"filter\":\"WARM\"}},{\"method\":\"adjust_beauty\",\"params\":{\"whitening\":60}},{\"method\":\"capture\",\"params\":{}}]")
            appendLine("用户: 你好")
            appendLine("-> [{\"method\":\"text_reply\",\"params\":{\"message\":\"你好呀，我是小觅\"}}]")
        }
    }

    /**
     * 构建 L3 Plan 模式 Prompt
     *
     * 输出格式为 ExecutionPlan JSON，steps 中的命令使用精简 JSON 风格（method + params）。
     */
    fun buildPlanPrompt(userInput: String, context: AgentContext): String {
        return buildString {
            appendLine("你是 PicMe 的任务编排器。把用户复杂请求转成 ExecutionPlan JSON。")
            appendLine()
            appendLine("输出硬规则：")
            appendLine("1. 只能输出一个 JSON 对象，禁止解释、禁止 markdown、禁止 <think>。")
            appendLine("2. 顶层字段固定：plan_id, description, steps。")
            appendLine("3. steps 每项字段固定：step, method, params, condition, wait_condition, repeat_count, description, delayMs。")
            appendLine("4. method 字段是命令名，params 是命令参数对象。")
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
            appendLine("-> {plan_id:plan_1,description:切到相机后连拍,steps:[{step:1,method:navigate_to,params:{destination:camera},condition:null,wait_condition:null,repeat_count:1,description:切换到相机,delayMs:0},{step:2,method:text_reply,params:{message:准备连拍},condition:null,wait_condition:{type:duration,delay_ms:1000},repeat_count:1,description:等待1秒,delayMs:0},{step:3,method:capture,params:{},condition:null,wait_condition:null,repeat_count:3,description:连拍3张,delayMs:500}]}")
            appendLine("用户: 3秒后调暖色调拍照")
            appendLine("-> {plan_id:plan_2,description:延迟后调暖色调拍照,steps:[{step:1,method:delay,params:{delay_ms:3000},condition:null,wait_condition:null,repeat_count:1,description:等待3秒,delayMs:0},{step:2,method:switch_filter,params:{filter:WARM},condition:null,wait_condition:null,repeat_count:1,description:切换暖色调,delayMs:0},{step:3,method:capture,params:{},condition:null,wait_condition:null,repeat_count:1,description:拍照,delayMs:0}]}")
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
            appendLine("method 白名单（只能从下列 method 选择，参数放在 params 对象中）：")

            if (includeCamera) {
                appendLine("- camera: capture, toggle_recording, flip_camera, switch_mode")
                appendLine("- camera_adjust: adjust_beauty, adjust_exposure, adjust_zoom")
                appendLine("- camera_style: switch_filter, switch_style, switch_scene, switch_ratio")
                appendLine("- delay: delay(params.delay_ms) — 通用延迟原语，必须与其他命令组合使用。用户说\"X秒后做某事\"时，delay 必须是数组第一个元素。例：3秒后拍照 -> [{method:delay,params:{delay_ms:3000}},{method:capture,params:{}}]；5秒后换暖色滤镜拍照 -> [{method:delay,params:{delay_ms:5000}},{method:switch_filter,params:{filter:WARM}},{method:capture,params:{}}]")
            }

            if (includeGallery) {
                appendLine("- gallery: view_media, delete_media, share_media, select_media, search_media, switch_view_mode, favorite_media")
            }

            if (includeSettings) {
                appendLine("- settings: change_theme, change_language, download_model, switch_face_engine, toggle_setting")
            }

            appendLine("- navigation: navigate_to(params.destination=camera|gallery|settings|debug), go_back")
            appendLine("- fallback: text_reply(params.message)")
            appendLine("params 约束: exposure=-2..2, zoom=0.5..10, ratio=4:3|16:9|full, mode=PHOTO|VIDEO|PRO|DOCUMENT")
            appendLine("滤镜: NONE|LEICA_CLASSIC|LEICA_VIBRANT|LEICA_BW|FILM_GOLD|FILM_FUJI|VINTAGE|COOL|WARM")
            appendLine("风格: NONE|TOON|SKETCH|POSTERIZE|EMBOSS|CROSSHATCH")
            appendLine("滤镜映射: 冷调/冷色/冷滤镜->COOL; 暖调/暖色/暖滤镜->WARM; 复古/怀旧->VINTAGE; 胶片金->FILM_GOLD; 胶片富士/富士->FILM_FUJI")
            appendLine("导航映射: 去相机/回相机/打开相机/去拍照->params.destination=camera; 去相册/打开相册->params.destination=gallery; 去设置/打开设置->params.destination=settings; 返回/上一页/后退->go_back")
            appendLine("导航示例: {\"method\":\"navigate_to\",\"params\":{\"destination\":\"camera\"}}")

            if (forPlan) {
                appendLine("Plan 字段约束: step(Int), method(String), params(Object), condition(String|null), wait_condition(Object|null), repeat_count(Int>=1), description(String), delayMs(Long>=0)")
                appendLine("wait_condition 示例: {\"type\":\"duration\",\"delay_ms\":1000}")
            }
        }
    }
}
