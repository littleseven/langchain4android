package com.picme.domain.agent.capability

import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager

/**
 * Capability 接口
 *
 * Agent Runtime 通过 Capability 抽象与具体业务逻辑解耦。
 * 每个 Capability 代表一个可被执行的领域能力（如相机控制、相册管理）。
 *
 * 架构原则：
 * - Capability 是应用级单例，在 Application.onCreate() 中注册一次，永不注销
 * - 页面通过绑定/解绑 delegate 来激活/停用 Capability
 * - 跨页面指令可以排队执行，当目标页面激活时自动处理
 *
 * 扩展能力：
 * - 场景绑定：声明该 Capability 活跃的场景
 * - 上下文感知：接收页面特定的上下文数据
 * - 自描述命令参数：自动生成详细的命令说明
 * - 委托代理：页面通过 delegate 提供实际执行逻辑
 */
interface Capability {

    /**
     * Capability 唯一标识名
     */
    val name: String

    /**
     * Capability 描述（用于 system prompt 生成）
     */
    val description: String

    /**
     * 该 Capability 在哪些场景下可用
     *
     * @return 活跃场景列表，空列表表示在所有场景都可用
     */
    fun activeScenes(): List<SceneManager.Scene>

    /**
     * 返回该 Capability 支持的所有命令类型标识
     */
    fun supportedCommands(): List<String>

    /**
     * 获取命令的详细描述（用于 system prompt）
     *
     * @param command 命令类型
     * @return 命令的详细说明，包括参数说明
     */
    fun getCommandDescription(command: String): String

    /**
     * 检查该 Capability 当前是否可用（delegate 是否已绑定）
     *
     * 应用级 Capability（如 NavigationCapability）始终返回 true
     * 页面级 Capability（如 CameraCapability）在页面激活时返回 true
     */
    fun isAvailable(): Boolean

    /**
     * 执行命令
     *
     * @param command 解析后的 Agent 命令
     * @param context 当前 Agent 上下文
     * @param pageContext 页面特定上下文（可选）
     * @return 执行结果
     */
    suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext? = null
    ): Result<AgentAction>

    /**
     * 检查该 Capability 是否支持指定命令
     */
    fun supportsCommand(command: AgentCommand): Boolean {
        return supportedCommands().contains(AgentCommand.getMethodName(command))
    }

    /**
     * 构建 Capability 描述文本（用于 system prompt）
     */
    fun buildCapabilityDescription(): String {
        val sb = StringBuilder()
        sb.appendLine("- $name: $description")
        supportedCommands().forEach { cmd ->
            sb.appendLine("  • $cmd - ${getCommandDescription(cmd)}")
        }
        return sb.toString()
    }
}

/**
 * Capability 抽象基类
 *
 * 提供默认实现：默认在所有场景可用，命令描述为简单文本。
 * 默认始终可用（适用于应用级 Capability）
 */
abstract class BaseCapability : Capability {

    override fun activeScenes(): List<SceneManager.Scene> {
        // 默认在所有场景可用
        return SceneManager.Scene.entries.toList()
    }

    override fun getCommandDescription(command: String): String {
        return "执行 $command 操作"
    }

    override fun isAvailable(): Boolean {
        // 默认始终可用（应用级 Capability）
        return true
    }
}
