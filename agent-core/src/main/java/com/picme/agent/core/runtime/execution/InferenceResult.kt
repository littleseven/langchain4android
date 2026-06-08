package com.picme.agent.core.runtime.execution

import com.picme.agent.core.api.execution.ExecutionPlan
import com.picme.agent.core.api.command.AgentCommand

/**
 * 推理结果密封类
 *
 * 表示 LLM 推理后的解析结果，支持多种输出模式。
 */
sealed class InferenceResult {

    /**
     * 本地单命令模式（L1 Single Tool Call）
     *
     * @property command 单个 Agent 命令
     */
    data class Local(val command: AgentCommand) : InferenceResult()

    /**
     * 批量命令模式（L2 Batch Function Calling）
     *
     * @property commands 命令列表
     */
    data class Batch(val commands: List<AgentCommand>) : InferenceResult()

    /**
     * 计划执行模式（L3 Plan-and-Execute）
     *
     * @property plan 执行计划
     */
    data class Plan(val plan: ExecutionPlan) : InferenceResult()

    /**
     * 聊天回复模式（纯文本对话，无操作）
     *
     * @property message 回复消息
     */
    data class Chat(val message: String) : InferenceResult()
}
