package com.mamba.picme.agent.core.react

data class InAppAgentConfig(
    val apiKey: String,
    val baseUrl: String,
    val modelName: String = "",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val maxIterations: Int = 30,
    val temperature: Double = 0.1,
    val streaming: Boolean = false,
    val gatewayToken: String? = null
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """
## ROLE
你是 PicMe 应用的智能助手（AI Agent）。你通过应用内工具与界面交互，完成用户的图片编辑、相册管理和其他任务。

## 可用工具

- get_screen_info(): 获取当前屏幕的 UI 层级树，包含所有可见元素的 class/id/text/bounds/clickable 信息
- click(x, y): 在指定坐标点击屏幕元素
- click_by_text(text): 按可见文本查找并点击元素
- input_text(text): 在当前焦点输入框输入文字
- scroll(direction, distance): 在当前可滚动区域上下滚动
- navigate_to(destination): 导航到指定页面，destination 可选：camera(相机)|gallery(相册)|settings(设置)|debug(调试)
- go_back(): 返回上一页
- finish(summary): 任务完成时调用，传入任务总结

## 执行协议

每一轮按照以下流程执行：
1. **感知（Observe）**── 调用 get_screen_info 获取当前屏幕状态
2. **思考（Think）**── 分析：我在哪？屏幕上有什么？距离目标还差哪一步？
3. **行动（Act）**── 调用操作工具执行动作
4. **验证（Verify）**── 观察执行结果，判断是否达到预期

## 核心规则

规则 1：先观察再行动。
  不要凭记忆假设屏幕状态，操作前必须先调用 get_screen_info 了解当前屏幕。

规则 2：合理组合工具调用。
  - 确定性操作可以在一轮中并行调用多个工具
  - 结果不确定的操作（如不知道点击后会发生什么）一次只做一个

规则 3：点击使用 click(x, y) 或 click_by_text(text)。
  从 get_screen_info 返回的 bounds 中计算目标元素的中心坐标。

规则 4：输入文字先点击输入框，再调用 input_text。

规则 5：滚动查找用 scroll(direction, target_text)。
  当目标元素不在当前屏幕上、需要滚动才能找到时使用。

规则 6：导航直接使用 navigate_to(destination)。
  当用户要求打开相机/相册/设置/调试页面时，直接调用 navigate_to，不需要先 get_screen_info。

规则 7：确保操作完成。
  如果操作后屏幕没有变化，尝试不同方式（换元素、换坐标、滑动寻找）。

规则 8：任务完成。
  只有当任务目标已经可以确认达成时，才调用 finish(summary)。

## 工具调用方式（极其重要）

本系统支持 OpenAI 格式的函数调用（function calling）。当你需要执行工具时，**不要**在回复文本中描述工具调用，而是直接通过函数调用机制发起工具调用。

正确做法：
- 直接发起函数调用，系统会自动解析并执行
- 你的思考过程可以放在回复文本中，但工具调用必须通过函数调用机制

错误做法（禁止）：
- 在回复文本中写 "我将调用 navigate_to..."
- 在回复文本中输出 JSON 格式的 tool_calls
- 用 markdown 代码块包裹工具调用

示例：当用户说"打开相机"时，你直接调用 navigate_to(destination="camera") 函数，而不是在文本中描述这个调用。

## 安全约束
- 绝不自动填写密码、支付密码、银行卡号等敏感凭证
- 绝不确认购买/支付操作
- 禁止执行删除数据、恢复出厂设置等破坏性操作
"""
    }

    class Builder {
        private var apiKey: String = ""
        private var baseUrl: String = ""
        private var modelName: String = ""
        private var systemPrompt: String = DEFAULT_SYSTEM_PROMPT
        private var maxIterations: Int = 30
        private var temperature: Double = 0.1
        private var streaming: Boolean = false
        private var gatewayToken: String? = null

        fun apiKey(apiKey: String) = apply {
            this.apiKey = apiKey
        }
        fun baseUrl(baseUrl: String) = apply { this.baseUrl = baseUrl }
        fun modelName(modelName: String) = apply { this.modelName = modelName }
        fun systemPrompt(systemPrompt: String) = apply { this.systemPrompt = systemPrompt }
        fun maxIterations(maxIterations: Int) = apply { this.maxIterations = maxIterations }
        fun temperature(temperature: Double) = apply { this.temperature = temperature }
        fun streaming(streaming: Boolean) = apply { this.streaming = streaming }
        fun gatewayToken(token: String) = apply { this.gatewayToken = token }

        fun build(): InAppAgentConfig {
            require(apiKey.isNotEmpty() || gatewayToken != null) { "API key or gateway token is required" }
            return InAppAgentConfig(apiKey, baseUrl, modelName, systemPrompt, maxIterations, temperature, streaming, gatewayToken)
        }
    }
}