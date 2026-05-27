package com.picme.domain.agent.capability

import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager

/**
 * Capability 适配器（将旧版 Capability 适配为 CapabilityV2）
 *
 * 用于向后兼容，让旧版 Capability 能在 V2 架构中工作。
 */
class CapabilityAdapter(
    private val legacyCapability: Capability,
    private val activeSceneList: List<SceneManager.Scene> = emptyList()
) : CapabilityV2 {

    override val name: String = legacyCapability.name
    override val description: String = legacyCapability.description

    override fun activeScenes(): List<SceneManager.Scene> = activeSceneList

    override fun supportedCommands(): List<String> = legacyCapability.supportedCommands()

    override fun getCommandDescription(command: String): String {
        return "执行 $command 操作"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        // 忽略 pageContext，旧版 Capability 不支持
        return legacyCapability.execute(command, context)
    }
}

/**
 * 为 Camera Capability 提供默认场景绑定
 */
fun CameraCapability.toV2(): CapabilityV2 {
    return CapabilityAdapter(
        legacyCapability = this,
        activeSceneList = listOf(SceneManager.Scene.CAMERA)
    )
}

/**
 * 为通用 Capability 提供 V2 适配
 */
fun Capability.toV2(activeScenes: List<SceneManager.Scene> = emptyList()): CapabilityV2 {
    return CapabilityAdapter(
        legacyCapability = this,
        activeSceneList = activeScenes
    )
}
