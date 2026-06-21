package com.mamba.picme.service.accessibility

import com.mamba.picme.agent.core.model.command.AgentCommand

/**
 * 无障碍动作任务
 *
 * 由 Agent 命令转换而来，投递到 [AccessibilityController] 执行队列。
 *
 * @property commandId 关联的 AgentCommand ID，用于结果回调关联
 * @property action 动作类型
 * @property target 目标节点描述（可选）
 * @property params 动作参数（如 input 的 text）
 */
data class AccessibilityAction(
    val commandId: Int,
    val action: String,
    val target: AgentCommand.AccessibilityTarget? = null,
    val params: Map<String, String> = emptyMap()
)
