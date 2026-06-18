package com.mamba.picme.agent.core.api.policy

/**
 * AI Agent 推理模式
 * 控制使用本地 MNN-LLM 模型还是远程 API
 *
 * 自 2026-06-17 起，默认模式切换为 REMOTE（远程推理优先策略）。
 * 原因：应用复杂度增加 + 本地 LLM 引擎（Qwen3-2B）推理能力有限。
 * 所有新功能开发先保证远程推理可用，再做本地模型适配。
 */
enum class AiAgentMode {
    OFF,     // 完全关闭 Agent
    LOCAL,   // 本地 MNN-LLM 模型（离线兜底，不再默认）
    REMOTE,  // 远程 API 推理（默认，优先使用，支持 OpenAI/Claude 协议）
    FEISHU   // 飞书远程控制专用模式（ReAct 循环，应用内 UI 自动化）
}

/**
 * AI Agent 隐私级别
 * 控制是否允许远程 API 调用
 *
 * 在远程推理优先策略下，PERMISSIVE 为实际默认行为。
 * STRICT 保留作为极端隐私场景的选项。
 */
enum class AiAgentPrivacyLevel {
    STRICT,      // 绝对本地，禁止任何远程调用
    PERMISSIVE   // 允许远程（需用户显式确认）
}

/**
 * AI Agent 推理偏好（在 LOCAL 模式下控制本地/远程路由行为）
 * - AUTO: 自动选择（先尝试本地，本地不可用时回退远程）
 * - FORCE_LOCAL: 强制本地推理（即使本地模型不可用也拒绝远程）
 * - FORCE_REMOTE: 强制远程推理（默认，绕过本地模型检查直接走远程）
 *
 * 随远程推理优先策略变更，默认值从 FORCE_LOCAL 改为 FORCE_REMOTE。
 */
enum class AiAgentInferencePreference {
    AUTO,         // 自动选择
    FORCE_LOCAL,  // 强制本地（离线兜底）
    FORCE_REMOTE  // 强制远程（默认）
}
