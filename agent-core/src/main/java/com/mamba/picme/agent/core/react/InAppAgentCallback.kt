package com.mamba.picme.agent.core.react

interface InAppAgentCallback {
    /** Agent 开始新的循环轮次 */
    fun onLoopStart(iteration: Int)

    /** LLM 返回部分内容（流式）或本轮思考内容 */
    fun onContent(iteration: Int, content: String)

    /** Agent 决定调用工具 */
    fun onToolCall(iteration: Int, toolName: String, args: String)

    /** 工具执行完成 */
    fun onToolResult(iteration: Int, toolName: String, result: String)

    /** Agent 任务完成 */
    fun onComplete(iteration: Int, summary: String, totalTokens: Int)

    /** Agent 出错 */
    fun onError(iteration: Int, error: Throwable, totalTokens: Int)
}
