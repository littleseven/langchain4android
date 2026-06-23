package com.mamba.picme.domain.agent.capability

import android.content.Context
import com.mamba.picme.agent.core.capability.Capability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.AgentIdGenerator
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.domain.tag.TagGenerationScheduler

/**
 * 自动 Tag 生成 Capability
 *
 * 将标签系统作为 Agent 可编排的 Capability 暴露出来。
 * 支持 Agent 通过 Tool Calling 触发全量标签扫描和查询特定照片的标签。
 */
class AutoTagCapability(
    private val context: Context,
    private val tagScheduler: TagGenerationScheduler = TagGenerationScheduler(context)
) : Capability {

    companion object {
        private const val TAG = "AutoTagCapability"
    }

    override val name = "auto_tag"
    override val description = "相册标签自动生成：触发全量标签扫描、查询照片标签、管理扫描进度"

    override fun supportedCommands(): List<String> = listOf(
        "scan_all_tags",
        "get_photo_tags",
        "get_tag_progress",
        "cancel_tag_scan"
    )

    override fun getCommandDescription(command: String): String = when (command) {
        "scan_all_tags" -> "扫描所有照片并自动生成标签"
        "get_photo_tags" -> "获取指定照片的标签信息"
        "get_tag_progress" -> "获取标签扫描进度"
        "cancel_tag_scan" -> "取消正在进行的标签扫描"
        else -> "执行 $command 操作"
    }

    override fun isAvailable(): Boolean = true

    override fun activeScenes(): List<SceneManager.Scene> =
        listOf(SceneManager.Scene.GALLERY)

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val methodName = AgentCommand.getMethodName(command)
        Logger.d(TAG, "Executing command: $methodName")

        return when {
            methodName == "unknown" && command is AgentCommand.Unknown -> {
                // 从 raw 文本解析命令名
                val cmdId = AgentIdGenerator.nextId()
                when {
                    command.raw.contains("scan_all_tags") -> handleScanAll(cmdId)
                    command.raw.contains("get_photo_tags") -> handleGetPhotoTags(cmdId, command.raw)
                    command.raw.contains("get_tag_progress") -> handleGetProgress(cmdId)
                    command.raw.contains("cancel_tag_scan") -> handleCancelScan(cmdId)
                    else -> Result.success(
                        AgentAction.Error(cmdId, AgentErrorCode.METHOD_NOT_FOUND, "未知的标签命令")
                    )
                }
            }
            else -> Result.success(
                AgentAction.Error(
                    AgentIdGenerator.nextId(),
                    AgentErrorCode.METHOD_NOT_FOUND,
                    "不支持的标签命令: $methodName"
                )
            )
        }
    }

    private suspend fun handleScanAll(cmdId: Int): Result<AgentAction> {
        Logger.i(TAG, "Starting full tag scan")
        tagScheduler.scanAll { processed, total ->
            Logger.d(TAG, "Tag scan progress: $processed/$total")
        }
        return Result.success(AgentAction.Success(cmdId, AgentCommand.TextReply(cmdId, "已启动全量标签扫描")))
    }

    private suspend fun handleGetPhotoTags(cmdId: Int, raw: String): Result<AgentAction> {
        // 尝试从 raw 中提取 photoId: "get_photo_tags:123"
        val photoId = raw.substringAfter(":").trim().toLongOrNull()
        if (photoId == null) {
            return Result.success(
                AgentAction.Error(cmdId, AgentErrorCode.INVALID_PARAMS, "缺少 photoId 参数，格式: get_photo_tags:photoId")
            )
        }

        val db = AppDatabase.getDatabase(context)
        val entity = db.mediaDao().getMediaById(photoId)
        if (entity == null) {
            return Result.success(
                AgentAction.Error(cmdId, AgentErrorCode.INVALID_REQUEST, "未找到照片: id=$photoId")
            )
        }

        val labels = entity.labels ?: "{}"
        return Result.success(AgentAction.Success(cmdId, AgentCommand.TextReply(cmdId, labels)))
    }

    private suspend fun handleGetProgress(cmdId: Int): Result<AgentAction> {
        val progress = tagScheduler.progress.value
        val scanning = tagScheduler.isScanning.value
        val message = if (scanning) {
            "扫描中: ${progress?.processed ?: 0}/${progress?.total ?: 0}"
        } else {
            "未在扫描"
        }
        return Result.success(AgentAction.Success(cmdId, AgentCommand.TextReply(cmdId, message)))
    }

    private suspend fun handleCancelScan(cmdId: Int): Result<AgentAction> {
        tagScheduler.cancel()
        return Result.success(AgentAction.Success(cmdId, AgentCommand.TextReply(cmdId, "已取消")))
    }
}
