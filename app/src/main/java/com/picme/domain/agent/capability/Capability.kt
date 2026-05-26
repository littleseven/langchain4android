package com.picme.domain.agent.capability

import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext

/**
 * Capability 接口
 *
 * Agent Runtime 通过 Capability 抽象与具体业务逻辑解耦。
 * 每个 Capability 代表一个可被执行的领域能力（如相机控制、相册管理）。
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
     * 返回该 Capability 支持的所有命令类型标识
     */
    fun supportedCommands(): List<String>

    /**
     * 执行命令
     *
     * @param command 解析后的 Agent 命令
     * @param context 当前 Agent 上下文
     * @return 执行结果
     */
    suspend fun execute(command: AgentCommand, context: AgentContext): Result<AgentAction>
}
