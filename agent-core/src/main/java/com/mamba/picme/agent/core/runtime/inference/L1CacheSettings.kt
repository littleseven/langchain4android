package com.mamba.picme.agent.core.runtime.inference

import java.util.concurrent.atomic.AtomicBoolean

/**
 * L1 意图缓存全局运行时开关
 *
 * 供设置页调试开关使用。关闭后所有 IntentCache 实例都会直接返回未命中，
 * 强制每条指令都走 LLM 推理链路，方便观察完整推理路径。
 */
object L1CacheSettings {
    private val enabled = AtomicBoolean(true)

    fun isEnabled(): Boolean = enabled.get()

    fun setEnabled(value: Boolean) {
        enabled.set(value)
    }
}
