package com.mamba.picme.data.local.dao

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.mamba.picme.data.model.MediaEntity

/**
 * FTS5 全文搜索 DAO
 *
 * 使用 SQLite FTS5 虚拟表 [media_fts] 进行跨字段（文件名、标签、OCR、地名）全文搜索。
 * FTS5 比 LIKE '%query%' 快 10x-100x，特别适合中文前缀查询。
 *
 * FTS5 表内容通过触发器与 [media_assets] 保持同步。
 */
@Dao
interface FtsSearchDao {

    @RawQuery(observedEntities = [MediaEntity::class])
    suspend fun searchFts(query: SupportSQLiteQuery): List<MediaEntity>

    @RawQuery(observedEntities = [MediaEntity::class])
    suspend fun searchFtsCount(query: SupportSQLiteQuery): Int
}
