package com.mamba.picme.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object ChatDatabaseMigrations {

    /**
     * 4 → 5：新增 chat_sessions 表，并从已有消息反填充会话标题和时间戳
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chat_sessions (
                    sessionId TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )

            // 用已有 chat_messages 中的 sessionId 初始化 chat_sessions
            db.execSQL(
                """
                INSERT OR IGNORE INTO chat_sessions (sessionId, title, createdAt, updatedAt)
                SELECT DISTINCT sessionId, sessionId, MIN(timestamp), MAX(timestamp)
                FROM chat_messages
                GROUP BY sessionId
                """.trimIndent()
            )
        }
    }
}
