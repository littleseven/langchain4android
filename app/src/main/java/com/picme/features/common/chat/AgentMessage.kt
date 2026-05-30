package com.picme.features.common.chat

import com.picme.domain.agent.remote.ExecutionPlan

/**
 * Agent 消息类型定义
 * 
 * 支持的消息类型：
 * - UserText: 用户输入的文字消息
 * - AgentText: AI Agent 回复的文字消息
 * - PlanPreview: 计划预览（显示将要执行的操作）
 * - PlanProgress: 计划执行进度
 * - PlanResult: 计划执行结果
 */
sealed class AgentMessage {
    data class UserText(val content: String) : AgentMessage()
    data class AgentText(val content: String) : AgentMessage()
    data class PlanPreview(val content: String, val plan: ExecutionPlan? = null) : AgentMessage()
    data class PlanProgress(val content: String) : AgentMessage()
    data class PlanResult(val content: String) : AgentMessage()
}
