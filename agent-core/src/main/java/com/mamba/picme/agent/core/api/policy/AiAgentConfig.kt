package com.mamba.picme.agent.core.api.policy

/**
 * AI Agent 推理模式
 * 控制使用本地 MNN-LLM 模型还是远程 API
 */
enum class AiAgentMode {
    OFF,     // 完全关闭 Agent
    LOCAL,   // 本地 MNN-LLM 模型（默认，符合隐私红线）
    REMOTE   // 远程 Kimi/Moonshot API（开发者/高级用户选项）
}

/**
 * AI Agent 隐私级别
 * 控制是否允许远程 API 调用
 */
enum class AiAgentPrivacyLevel {
    STRICT,      // 绝对本地，禁止任何远程调用
    PERMISSIVE   // 允许远程（需用户显式确认）
}

/**
 * AI Agent 推理偏好（在 LOCAL 模式下控制本地/远程路由行为）
 * - AUTO: 自动选择（默认本地，本地模型不可用时回退远程）
 * - FORCE_LOCAL: 强制本地推理（即使本地模型不可用也拒绝远程）
 * - FORCE_REMOTE: 强制远程推理（绕过本地模型检查直接走远程）
 */
enum class AiAgentInferencePreference {
    AUTO,         // 自动选择（默认）
    FORCE_LOCAL,  // 强制本地
    FORCE_REMOTE  // 强制远程
}
