package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.runtime.state.SceneManager

/**
 * Capability 宿主接口
 *
 * 用于解耦 CapabilityRegistry 对 Compose UI 的依赖。
 * 纯 Kotlin 接口，无 Android/Compose 依赖，可在 :agent-core 模块独立使用。
 */
interface CapabilityHost {
    /**
     * 查找指定场景的 Capability 列表
     */
    fun findForScene(scene: SceneManager.Scene): List<Capability>?

    /**
     * 根据命令名查找 Capability
     */
    fun findForCommand(commandName: String): Capability?

    companion object {
        @Volatile
        private var instance: CapabilityHost? = null

        fun set(host: CapabilityHost) {
            instance = host
        }

        fun get(): CapabilityHost? = instance
    }
}
