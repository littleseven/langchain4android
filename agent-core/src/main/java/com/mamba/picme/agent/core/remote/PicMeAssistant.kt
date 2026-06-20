package com.mamba.picme.agent.core.remote

/**
 * PicMe AI 助手接口契约。
 *
 * <p>定义 Agent 与外部调用者的交互接口，使用 AiServices 模式实现自动工具调用。</p>
 *
 * <p>示例：</p>
 * <pre>{@code
 * val assistant = AiServices.builder(PicMeAssistant::class.java)
 *     .chatModel(chatModel)
 *     .chatMemory(chatMemory)
 *     .tools(picMeToolService)
 *     .systemMessageProvider { SystemMessage.from(systemPrompt) }
 *     .build()
 *
 * val result = assistant.chat("打开相机并切换到暖色滤镜")
 * }</pre>
 */
interface PicMeAssistant {

    /**
     * 发送用户消息并获取 AI 回复（自动处理工具调用）。
     *
     * <p>这是主要的交互方法。AiServices 代理会自动：
     * <ol>
     *   <li>将用户消息添加到 ChatMemory</li>
     *   <li>调用 LLM（传入工具规格）</li>
     *   <li>如果 LLM 决定调用工具，自动执行并继续对话</li>
     *   <li>返回最终结果</li>
     * </ol>
     *
     * @param message 用户输入消息
     * @return AI 最终回复文本
     */
    fun chat(message: String): String
}
