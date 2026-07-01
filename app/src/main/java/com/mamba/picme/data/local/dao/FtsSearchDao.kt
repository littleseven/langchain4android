package com.mamba.picme.data.local.dao

import android.util.Log
import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import com.mamba.picme.data.model.MediaEntity

/**
 * FTS5 全文搜索 DAO
 *
 * 使用 SQLite FTS5 虚拟表 `media_fts` 进行跨字段（标签、OCR、地名、文件名）全文搜索。
 * FTS5 比 LIKE '%query%' 快 10x-100x。
 *
 * ## 降级策略
 * 极少数设备的系统 SQLite 可能未编译 FTS5 支持。
 * FTS5 虚拟表在首次搜索时惰性创建，失败则自动回退到 LIKE 查询。
 *
 * ## 日志噪音控制
 * FTS5 创建失败不输出 WARNING（避免在无 FTS5 设备上每次启动都打印异常堆栈）。
 * 仅在 DEBUG 级别记录可用性状态。
 */
@Dao
interface FtsSearchDao {

    @RawQuery(observedEntities = [MediaEntity::class])
    suspend fun searchFts(query: SupportSQLiteQuery): List<MediaEntity>

    @RawQuery(observedEntities = [MediaEntity::class])
    suspend fun searchFtsCount(query: SupportSQLiteQuery): Int

    /**
     * 执行原始 SQL（用于 FTS5 表创建）。
     * 注意：此方法不返回数据，仅用于 DDL。
     */
    @RawQuery(observedEntities = [])
    suspend fun execRaw(query: SupportSQLiteQuery): Int
}

/**
 * FTS5 查询构造器与可用性管理。
 *
 * 设计决策：
 * - FTS5 虚拟表不在 DB migration 中创建（避免系统 SQLiteLog 输出 error 级别噪音）
 * - 改为首次搜索时惰性创建，失败静默降级
 */
object FtsHelper {

    private const val TAG = "FtsHelper"

    /** FTS5 是否已确认可用（惰性检测，仅检测一次） */
    @Volatile
    var isAvailable: Boolean = false

    /** 是否已完成可用性检测（防止重复创建尝试） */
    @Volatile
    private var checked: Boolean = false

    /**
     * 确保 FTS5 虚拟表已创建。
     * 仅在首次调用时尝试创建，后续直接返回缓存结果。
     *
     * 应在后台线程调用（涉及 DDL）。
     */
    fun ensureAvailable(db: SupportSQLiteDatabase) {
        if (checked) return
        synchronized(this) {
            if (checked) return
            checked = true

            try {
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS `media_fts` USING fts5(
                        `labels`,
                        `ocrText`,
                        `locationName`,
                        `fileName`,
                        `mlKitLabels`,
                        `mlKitLabelsZh`,
                        content='media_assets',
                        content_rowid='id'
                    )
                    """.trimIndent()
                )
                isAvailable = true
                Log.d(TAG, "FTS5 virtual table created successfully")
            } catch (e: Exception) {
                isAvailable = false
                // 使用 DEBUG 级别避免无 FTS5 设备上的日志噪音
                Log.d(TAG, "FTS5 not available, using LIKE fallback")
            }
        }
    }

    /**
     * 构造 FTS5 多字段 MATCH 查询（返回完整实体）。
     *
     * @param query 用户搜索词（多个词用空格分隔，FTS5 自动做 AND/OR 匹配）
     * @param limit 最大返回数
     */
    fun searchQuery(query: String, limit: Int = 100): SimpleSQLiteQuery {
        val escaped = escapeFtsQuery(query)
        val sql = """
            SELECT m.* FROM media_assets m
            INNER JOIN media_fts f ON m.id = f.rowid
            WHERE media_fts MATCH ?
            ORDER BY rank
            LIMIT $limit
        """.trimIndent()
        return SimpleSQLiteQuery(sql, arrayOf(escaped))
    }

    /**
     * 转义 FTS5 查询中的特殊字符。
     * FTS5 特殊字符：* " - ( ) + . , : ; < = > ! @ [ ] { } ^ | ~
     */
    fun escapeFtsQuery(query: String): String {
        val specialChars = setOf(
            '*', '"', '-', '(', ')', '+', '.', ',', ':', ';',
            '<', '=', '>', '!', '@', '[', ']', '{', '}', '^', '|', '~'
        )
        return query.map { c ->
            if (c in specialChars) "\\$c" else c.toString()
        }.joinToString("").trim().let { escaped ->
            if (escaped.isNotBlank()) {
                escaped.split("\\s+".toRegex())
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { "$it*" }
            } else escaped
        }
    }
}
