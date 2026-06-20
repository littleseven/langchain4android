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
    fun onComplete(iteration: Int, summary: String, totalTokens: Int, metrics: AgentExecutionMetrics? = null)

    /** Agent 出错 */
    fun onError(iteration: Int, error: Throwable, totalTokens: Int, metrics: AgentExecutionMetrics? = null)
}

/**
 * Agent 执行性能指标
 *
 * @property latencyMs 总耗时（毫秒）
 * @property promptTokens 输入 token 数
 * @property completionTokens 输出 token 数
 * @property modelName 使用的模型名称
 */
data class AgentExecutionMetrics(
    val latencyMs: Long = 0L,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val modelName: String? = null
)
