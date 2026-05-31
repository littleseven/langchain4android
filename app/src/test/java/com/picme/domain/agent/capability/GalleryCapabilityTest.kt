package com.picme.domain.agent.capability

import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.AgentScene
import com.picme.domain.agent.model.PageContext
import com.picme.domain.model.MediaAsset
import com.picme.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GalleryCapability 场景化单元测试
 *
 * 验证：在 GALLERY 场景下所有相册命令均可正确执行，回调被触发，
 * 页面上下文（PageContext.GalleryContext）正确参与决策。
 */
class GalleryCapabilityTest {

    private val defaultContext = AgentContext(scene = AgentScene.GALLERY)

    private fun fakeMediaAsset(id: Long = 1L) = MediaAsset(
        id = id,
        uri = "content://fake/$id",
        type = MediaType.PHOTO,
        captureDate = System.currentTimeMillis(),
        fileName = "IMG_$id.jpg"
    )

    // ------------------------------------------------------------------
    // 1. 场景绑定验证
    // ------------------------------------------------------------------

    @Test
    fun `activeScenes returns only GALLERY`() {
        val capability = GalleryCapability()
        val scenes = capability.activeScenes()
        assertEquals(1, scenes.size)
        assertEquals(com.picme.domain.agent.model.SceneManager.Scene.GALLERY, scenes[0])
    }

    @Test
    fun `supportedCommands contains all gallery commands`() {
        val capability = GalleryCapability()
        val commands = capability.supportedCommands()
        assertEquals(7, commands.size)
        assertTrue(commands.contains("view_media"))
        assertTrue(commands.contains("delete_media"))
        assertTrue(commands.contains("share_media"))
        assertTrue(commands.contains("select_media"))
        assertTrue(commands.contains("search_media"))
        assertTrue(commands.contains("switch_view_mode"))
        assertTrue(commands.contains("favorite_media"))
    }

    // ------------------------------------------------------------------
    // 2. 命令执行验证 — 基础回调触发
    // ------------------------------------------------------------------

    @Test
    fun `execute ViewMedia with mediaId triggers onViewMedia`() = runBlocking {
        var receivedId: String? = null
        val capability = GalleryCapability(
            onViewMedia = { id -> receivedId = id }
        )

        val command = AgentCommand.ViewMedia("123")
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertEquals("123", receivedId)
    }

    @Test
    fun `execute ViewMedia without mediaId uses galleryContext currentMedia`() = runBlocking {
        var receivedId: String? = null
        val capability = GalleryCapability(
            onViewMedia = { id -> receivedId = id }
        )

        val galleryContext = PageContext.GalleryContext(
            currentMedia = fakeMediaAsset(42L),
            selectedItems = emptyList()
        )

        val command = AgentCommand.ViewMedia(null)
        val result = capability.execute(command, defaultContext, galleryContext)

        assertTrue(result.isSuccess)
        assertEquals("42", receivedId)
    }

    @Test
    fun `execute ViewMedia without mediaId and no context returns Error`() = runBlocking {
        // 提供回调，测试参数验证路径（而非回调未设置路径）
        val capability = GalleryCapability(
            onViewMedia = { }
        )

        val command = AgentCommand.ViewMedia(null)
        val result = capability.execute(command, defaultContext, null)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("没有指定要查看的照片", (action as AgentAction.Error).message)
    }

    @Test
    fun `execute DeleteMedia with mediaIds triggers onDeleteMedia`() = runBlocking {
        var receivedIds: List<String>? = null
        val capability = GalleryCapability(
            onDeleteMedia = { ids -> receivedIds = ids }
        )

        val command = AgentCommand.DeleteMedia(listOf("1", "2", "3"))
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertEquals(listOf("1", "2", "3"), receivedIds)
    }

    @Test
    fun `execute DeleteMedia without mediaIds uses galleryContext selectedItems`() = runBlocking {
        var receivedIds: List<String>? = null
        val capability = GalleryCapability(
            onDeleteMedia = { ids -> receivedIds = ids }
        )

        val galleryContext = PageContext.GalleryContext(
            currentMedia = null,
            selectedItems = listOf(fakeMediaAsset(10L), fakeMediaAsset(20L))
        )

        val command = AgentCommand.DeleteMedia(emptyList())
        val result = capability.execute(command, defaultContext, galleryContext)

        assertTrue(result.isSuccess)
        assertEquals(listOf("10", "20"), receivedIds)
    }

    @Test
    fun `execute DeleteMedia without mediaIds and no selection returns Error`() = runBlocking {
        val capability = GalleryCapability(
            onDeleteMedia = { }
        )

        val command = AgentCommand.DeleteMedia(emptyList())
        val result = capability.execute(command, defaultContext, null)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("没有指定要删除的照片，请先选择", (action as AgentAction.Error).message)
    }

    @Test
    fun `execute ShareMedia with mediaIds triggers onShareMedia`() = runBlocking {
        var receivedIds: List<String>? = null
        val capability = GalleryCapability(
            onShareMedia = { ids -> receivedIds = ids }
        )

        val command = AgentCommand.ShareMedia(listOf("5", "6"))
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertEquals(listOf("5", "6"), receivedIds)
    }

    @Test
    fun `execute SelectMedia triggers onSelectMedia`() = runBlocking {
        var receivedId: String? = null
        var receivedSelected: Boolean? = null
        val capability = GalleryCapability(
            onSelectMedia = { id, selected ->
                receivedId = id
                receivedSelected = selected
            }
        )

        val command = AgentCommand.SelectMedia("99", true)
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertEquals("99", receivedId)
        assertEquals(true, receivedSelected)
    }

    @Test
    fun `execute SearchMedia with query triggers onSearch`() = runBlocking {
        var receivedQuery: String? = null
        val capability = GalleryCapability(
            onSearch = { query -> receivedQuery = query }
        )

        val command = AgentCommand.SearchMedia("上海")
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertEquals("上海", receivedQuery)
    }

    @Test
    fun `execute SearchMedia with blank query returns Error`() = runBlocking {
        val capability = GalleryCapability(
            onSearch = { }
        )

        val command = AgentCommand.SearchMedia("   ")
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("搜索关键词不能为空", (action as AgentAction.Error).message)
    }

    @Test
    fun `execute SwitchViewMode grid triggers onSwitchViewMode`() = runBlocking {
        var receivedMode: GalleryCapability.ViewMode? = null
        val capability = GalleryCapability(
            onSwitchViewMode = { mode -> receivedMode = mode }
        )

        val command = AgentCommand.SwitchViewMode("grid")
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertEquals(GalleryCapability.ViewMode.GRID, receivedMode)
    }

    @Test
    fun `execute SwitchViewMode list triggers onSwitchViewMode`() = runBlocking {
        var receivedMode: GalleryCapability.ViewMode? = null
        val capability = GalleryCapability(
            onSwitchViewMode = { mode -> receivedMode = mode }
        )

        val command = AgentCommand.SwitchViewMode("list")
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertEquals(GalleryCapability.ViewMode.LIST, receivedMode)
    }

    @Test
    fun `execute SwitchViewMode timeline triggers onSwitchViewMode`() = runBlocking {
        var receivedMode: GalleryCapability.ViewMode? = null
        val capability = GalleryCapability(
            onSwitchViewMode = { mode -> receivedMode = mode }
        )

        val command = AgentCommand.SwitchViewMode("timeline")
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertEquals(GalleryCapability.ViewMode.TIMELINE, receivedMode)
    }

    @Test
    fun `execute SwitchViewMode unknown defaults to GRID`() = runBlocking {
        var receivedMode: GalleryCapability.ViewMode? = null
        val capability = GalleryCapability(
            onSwitchViewMode = { mode -> receivedMode = mode }
        )

        val command = AgentCommand.SwitchViewMode("unknown")
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertEquals(GalleryCapability.ViewMode.GRID, receivedMode)
    }

    @Test
    fun `execute FavoriteMedia triggers onFavoriteMedia`() = runBlocking {
        var receivedId: String? = null
        var receivedFavorite: Boolean? = null
        val capability = GalleryCapability(
            onFavoriteMedia = { id, favorite ->
                receivedId = id
                receivedFavorite = favorite
            }
        )

        val command = AgentCommand.FavoriteMedia("77", true)
        val result = capability.execute(command, defaultContext)

        assertTrue(result.isSuccess)
        assertEquals("77", receivedId)
        assertEquals(true, receivedFavorite)
    }

    // ------------------------------------------------------------------
    // 3. 回调未设置时必须返回 Error（不允许静默失败）
    // ------------------------------------------------------------------

    @Test
    fun `execute ViewMedia without callback returns Error`() = runBlocking {
        val capability = GalleryCapability()

        val result = capability.execute(AgentCommand.ViewMedia("123"), defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue("回调为空时应返回 Error", action is AgentAction.Error)
        assertEquals("相册查看功能未初始化", (action as AgentAction.Error).message)
    }

    @Test
    fun `execute DeleteMedia without callback returns Error`() = runBlocking {
        val capability = GalleryCapability()

        val result = capability.execute(AgentCommand.DeleteMedia(listOf("1")), defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("相册删除功能未初始化", (action as AgentAction.Error).message)
    }

    @Test
    fun `execute SearchMedia without callback returns Error`() = runBlocking {
        val capability = GalleryCapability()

        val result = capability.execute(AgentCommand.SearchMedia("测试"), defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("相册搜索功能未初始化", (action as AgentAction.Error).message)
    }

    // ------------------------------------------------------------------
    // 4. 不支持命令
    // ------------------------------------------------------------------

    @Test
    fun `execute unsupported command returns Error`() = runBlocking {
        val capability = GalleryCapability()

        val result = capability.execute(AgentCommand.CapturePhoto, defaultContext)

        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Error)
        assertEquals("Gallery 不支持此命令", (action as AgentAction.Error).message)
    }

    // ------------------------------------------------------------------
    // 4. 自描述能力
    // ------------------------------------------------------------------

    @Test
    fun `buildCapabilityDescription contains all commands`() {
        val capability = GalleryCapability()
        val description = capability.buildCapabilityDescription()

        assertTrue(description.contains("gallery"))
        assertTrue(description.contains("view_media"))
        assertTrue(description.contains("delete_media"))
        assertTrue(description.contains("search_media"))
    }
}
