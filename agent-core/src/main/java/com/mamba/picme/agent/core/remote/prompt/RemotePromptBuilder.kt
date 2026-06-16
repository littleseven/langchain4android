package com.mamba.picme.agent.core.remote.prompt

import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.runtime.state.SceneManager

/**
 * 远程 LLM Prompt 构建器
 *
 * 面向远程大模型（DeepSeek/Kimi via cloud API），使用标准 OpenAI tool_calls 协议。
 * 提供三种 Prompt 模板：
 * - Batch: L2 批量命令解析（tool_calls）
 * - Plan: L3 计划执行（ExecutionPlan JSON，内部步使用 method/params 格式）
 * - Chat: L4 纯文本对话
 */
class RemotePromptBuilder(
    private val sceneManager: SceneManager
) {

    /**
     * 构建 L2 Batch 模式 Prompt（远程 LLM 使用）
     *
     * 模型通过 tools 参数中的 ToolSpecifications 定义以 tool_calls 协议输出命令。
     * 禁止输出 method/params 格式的文本 JSON，只接受标准 OpenAI tool_calls。
     */
    fun buildBatchPrompt(userInput: String, context: AgentContext): String {
        return buildString {
            appendLine("你是 PicMe 的指令解析器。使用 function calling（tool_calls）调用工具来响应用户指令。")
            appendLine()
            appendLine("规则：")
            appendLine("1. 使用 tool_calls 协议输出命令；如果需要先执行 A 再执行 B，在同一个 tool_calls 数组中输出多个工具调用。")
            appendLine("2. 用户说包含时间/延迟的指令（如\"3秒后拍照\"、\"5秒后换滤镜\"）时，delay 必须在数组第一个位置，后面跟后续函数。")
            appendLine("3. 用户说多个美颜参数（如\"美白50磨皮30\"）时，只调用一次 adjust_beauty，传入所有参数。")
            appendLine("4. 用户输入以\"拍照\"结尾时，最后一次调用必须是 capture。")
            appendLine("5. 用户要求拍多张时（如\"拍三张\"、\"连拍\"），调用多次 capture，中间可以插入 delay。")
            appendLine("6. 如果用户是闲聊或无法用现有函数表达，调用 text_reply 回复。")
            appendLine("7. 不要输出文字解释，不要使用<think>标签。")
            appendLine("8. 禁止输出 method/params 格式的 JSON 数组（如 [{\"method\":\"...\",\"params\":{}}]），必须使用标准 tool_calls 格式。")
            appendLine()
            appendLine("【当前状态】")
            appendLine(buildStateSection(context, sceneManager.currentScene.value))
            appendLine()
            appendLine("可用函数列表请参考 tools 参数中的定义。")
            appendLine()
            appendLine("【输出格式】")
            appendLine("在 tool_calls 数组中按顺序排列多个工具调用即可实现连续指令：")
            appendLine("{\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"工具名1\",\"arguments\":{...}}},{\"id\":\"call_2\",\"type\":\"function\",\"function\":{\"name\":\"工具名2\",\"arguments\":{...}}}]}")
            appendLine()
            appendLine("【示例】")
            appendLine("用户: 3秒后拍照")
            appendLine("{\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"delay\",\"arguments\":{\"delay_ms\":3000}}},{\"id\":\"call_2\",\"type\":\"function\",\"function\":{\"name\":\"capture\",\"arguments\":{}}}]}")
            appendLine("用户: 5秒后换暖色滤镜拍照")
            appendLine("{\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"delay\",\"arguments\":{\"delay_ms\":5000}}},{\"id\":\"call_2\",\"type\":\"function\",\"function\":{\"name\":\"switch_filter\",\"arguments\":{\"filter\":\"WARM\"}}},{\"id\":\"call_3\",\"type\":\"function\",\"function\":{\"name\":\"capture\",\"arguments\":{}}}]}")
            appendLine("用户: 你好")
            appendLine("{\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"text_reply\",\"arguments\":{\"message\":\"你好呀，我是小觅\"}}}]}")
            appendLine("用户: 磨皮50美白30")
            appendLine("{\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"adjust_beauty\",\"arguments\":{\"smoothing\":50,\"whitening\":30}}}]}")
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
        val includeSystem = scene == null || scene == SceneManager.Scene.CHAT || scene == SceneManager.Scene.UNKNOWN

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

            if (includeSystem) {
                appendLine("- system: launch_app(params.package_name|app_name), open_system_settings(params.setting=wifi|bluetooth|accessibility|display|location|app_notifications)")
                appendLine("- accessibility: perform_accessibility_action(params.action=click|long_click|input|scroll_forward|scroll_backward|back|home|recent, params.target={type,value}, params.params={text})")
            }

            appendLine("- navigation: navigate_to(params.destination=camera|gallery|settings|debug), go_back")
            appendLine("- fallback: text_reply(params.message)")
            appendLine("params 约束: exposure=-2..2, zoom=0.5..10, ratio=4:3|16:9|full, mode=PHOTO|VIDEO|PRO|DOCUMENT")
            appendLine("滤镜: NONE|LEICA_CLASSIC|LEICA_VIBRANT|LEICA_BW|FILM_GOLD|FILM_FUJI|VINTAGE|COOL|WARM")
            appendLine("风格: NONE|TOON|SKETCH|POSTERIZE|EMBOSS|CROSSHATCH")
            appendLine("滤镜映射: 冷调/冷色/冷滤镜->COOL; 暖调/暖色/暖滤镜->WARM; 复古/怀旧->VINTAGE; 胶片金->FILM_GOLD; 胶片富士/富士->FILM_FUJI")
            appendLine("导航映射: 去相机/回相机/打开相机/去拍照->params.destination=camera; 去相册/打开相册->params.destination=gallery; 去设置/打开设置->params.destination=settings; 返回/上一页/后退->go_back")
            appendLine("系统映射: 打开微信/启动支付宝/打开淘宝->launch_app(app_name=...); 打开WiFi设置/蓝牙设置/通知设置->open_system_settings(setting=wifi|bluetooth|app_notifications)")
            appendLine("无障碍映射: 点击目标文本->perform_accessibility_action(action=click,target={type:text,value:目标}); 输入内容->perform_accessibility_action(action=input,target={type:class_name,value:android.widget.EditText},params={text:内容}); 返回/主页/最近任务->perform_accessibility_action(action=back|home|recent)")
            appendLine("导航示例: {\"method\":\"navigate_to\",\"params\":{\"destination\":\"camera\"}}")
            appendLine("系统示例: {\"method\":\"launch_app\",\"params\":{\"app_name\":\"微信\"}}")
            appendLine("无障碍示例: {\"method\":\"perform_accessibility_action\",\"params\":{\"action\":\"click\",\"target\":{\"type\":\"text\",\"value\":\"通讯录\"}}}")

            if (forPlan) {
                appendLine("Plan 字段约束: step(Int), method(String), params(Object), condition(String|null), wait_condition(Object|null), repeat_count(Int>=1), description(String), delayMs(Long>=0)")
                appendLine("wait_condition 示例: {\"type\":\"duration\",\"delay_ms\":1000}")
            }
        }
    }
}
