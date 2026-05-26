package com.picme.domain.agent

import com.picme.domain.model.AiAgentMode
import com.picme.domain.model.AiAgentPrivacyLevel

/**
 * 隐私守卫（预留接口）
 *
 * 负责运行时隐私策略检查和本地模式断言。
 * 像素隔离（StrictMode Bitmap 检测）和网络层审计（KimiApiClient 拦截）
 * 优先级降低，后续迭代实现。
 *
 * @property privacyLevel 当前隐私级别
 * @property agentMode 当前 Agent 模式
 */
class PrivacyGuard(
    private var privacyLevel: AiAgentPrivacyLevel = AiAgentPrivacyLevel.STRICT,
    private var agentMode: AiAgentMode = AiAgentMode.LOCAL
) {

    /**
     * 断言当前处于本地模式
     *
     * @throws SecurityException 如果当前模式违反本地-only 策略
     */
    fun assertLocalOnly() {
        if (privacyLevel == AiAgentPrivacyLevel.STRICT && agentMode != AiAgentMode.LOCAL) {
            throw SecurityException(
                "PrivacyGuard: STRICT mode requires LOCAL inference, but current mode is $agentMode"
            )
        }
    }

    /**
     * 检查是否允许远程 API 调用
     */
    fun isRemoteAllowed(): Boolean {
        return privacyLevel == AiAgentPrivacyLevel.PERMISSIVE && agentMode == AiAgentMode.REMOTE
    }

    /**
     * 更新隐私配置
     */
    fun updateConfig(level: AiAgentPrivacyLevel, mode: AiAgentMode) {
        this.privacyLevel = level
        this.agentMode = mode
    }
}
