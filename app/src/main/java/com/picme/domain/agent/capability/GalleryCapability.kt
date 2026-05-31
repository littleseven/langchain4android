package com.picme.domain.agent.capability

import com.picme.core.common.Logger
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager

/**
 * Gallery 相册控制 Capability
 *
 * 负责相册相关操作：查看、删除、分享、搜索、选择照片
 * 仅在 GALLERY 场景可用
 */
class GalleryCapability(
    private val onViewMedia: ((String?) -> Unit)? = null,
    private val onDeleteMedia: ((List<String>) -> Unit)? = null,
    private val onShareMedia: ((List<String>) -> Unit)? = null,
    private val onSelectMedia: ((String, Boolean) -> Unit)? = null,
    private val onSearch: ((String) -> Unit)? = null,
    private val onSwitchViewMode: ((ViewMode) -> Unit)? = null,
    private val onFavoriteMedia: ((String, Boolean) -> Unit)? = null,
) : BaseCapability() {

    private val tag = "PicMe:GalleryCapability"

    override val name: String = "gallery"
    override val description: String = "相册操作：查看、删除、分享、搜索、选择照片和视频"

    override fun activeScenes(): List<SceneManager.Scene> {
        // Gallery 能力只在相册场景可用
        return listOf(SceneManager.Scene.GALLERY)
    }

    override fun supportedCommands(): List<String> = listOf(
        "view_media",
        "delete_media",
        "share_media",
        "select_media",
        "search_media",
        "switch_view_mode",
        "favorite_media"
    )

    override fun getCommandDescription(command: String): String = when (command) {
        "view_media" -> "查看指定照片，参数: media_id (可选，不传则查看当前选中)"
        "delete_media" -> "删除照片，参数: media_ids (数组，不传则删除当前选中)"
        "share_media" -> "分享照片，参数: media_ids (数组，不传则分享当前选中)"
        "select_media" -> "选择/取消选择照片，参数: media_id, selected (true/false)"
        "search_media" -> "搜索照片，参数: query (搜索关键词如'昨天'、'上海'等)"
        "switch_view_mode" -> "切换视图模式，参数: mode (grid/list/timeline)"
        "favorite_media" -> "收藏/取消收藏，参数: media_id, favorite (true/false)"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        Logger.d(tag, "Executing command: ${command::class.simpleName}")

        val galleryContext = pageContext as? PageContext.GalleryContext

        return when (command) {
            is AgentCommand.ViewMedia -> {
                handleViewMedia(command, galleryContext)
            }

            is AgentCommand.DeleteMedia -> {
                handleDeleteMedia(command, galleryContext)
            }

            is AgentCommand.ShareMedia -> {
                handleShareMedia(command, galleryContext)
            }

            is AgentCommand.SelectMedia -> {
                handleSelectMedia(command)
            }

            is AgentCommand.SearchMedia -> {
                handleSearchMedia(command)
            }

            is AgentCommand.SwitchViewMode -> {
                handleSwitchViewMode(command)
            }

            is AgentCommand.FavoriteMedia -> {
                handleFavoriteMedia(command)
            }

            else -> {
                Logger.w(tag, "Unsupported command: ${command::class.simpleName}")
                Result.success(AgentAction.Error("Gallery 不支持此命令"))
            }
        }
    }

    private fun handleViewMedia(
        command: AgentCommand.ViewMedia,
        galleryContext: PageContext.GalleryContext?
    ): Result<AgentAction> {
        val mediaId = command.mediaId ?: galleryContext?.currentMedia?.id?.toString()

        if (mediaId != null) {
            onViewMedia?.invoke(mediaId)
            return Result.success(AgentAction.Success(command))
        } else {
            return Result.success(AgentAction.Error("没有指定要查看的照片"))
        }
    }

    private fun handleDeleteMedia(
        command: AgentCommand.DeleteMedia,
        galleryContext: PageContext.GalleryContext?
    ): Result<AgentAction> {
        val mediaIds = command.mediaIds.ifEmpty {
            // 如果没有指定 ID，使用当前选中的
            galleryContext?.selectedItems?.map { it.id.toString() } ?: emptyList()
        }

        return if (mediaIds.isNotEmpty()) {
            onDeleteMedia?.invoke(mediaIds)
            Result.success(AgentAction.Success(command))
        } else {
            Result.success(AgentAction.Error("没有指定要删除的照片，请先选择"))
        }
    }

    private fun handleShareMedia(
        command: AgentCommand.ShareMedia,
        galleryContext: PageContext.GalleryContext?
    ): Result<AgentAction> {
        val mediaIds = command.mediaIds.ifEmpty {
            galleryContext?.selectedItems?.map { it.id.toString() } ?: emptyList()
        }

        return if (mediaIds.isNotEmpty()) {
            onShareMedia?.invoke(mediaIds)
            Result.success(AgentAction.Success(command))
        } else {
            Result.success(AgentAction.Error("没有指定要分享的照片，请先选择"))
        }
    }

    private fun handleSelectMedia(command: AgentCommand.SelectMedia): Result<AgentAction> {
        onSelectMedia?.invoke(command.mediaId, command.selected)
        return Result.success(AgentAction.Success(command))
    }

    private fun handleSearchMedia(command: AgentCommand.SearchMedia): Result<AgentAction> {
        return if (command.query.isNotBlank()) {
            onSearch?.invoke(command.query)
            Result.success(AgentAction.Success(command))
        } else {
            Result.success(AgentAction.Error("搜索关键词不能为空"))
        }
    }

    private fun handleSwitchViewMode(command: AgentCommand.SwitchViewMode): Result<AgentAction> {
        val mode = when (command.mode.lowercase()) {
            "grid", "网格" -> ViewMode.GRID
            "list", "列表" -> ViewMode.LIST
            "timeline", "时间线" -> ViewMode.TIMELINE
            else -> ViewMode.GRID
        }
        onSwitchViewMode?.invoke(mode)
        return Result.success(AgentAction.Success(command))
    }

    private fun handleFavoriteMedia(command: AgentCommand.FavoriteMedia): Result<AgentAction> {
        onFavoriteMedia?.invoke(command.mediaId, command.favorite)
        return Result.success(AgentAction.Success(command))
    }

    /**
     * 视图模式
     */
    enum class ViewMode {
        GRID,       // 网格视图
        LIST,       // 列表视图
        TIMELINE    // 时间线视图
    }
}
