package com.mamba.picme.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mamba.picme.data.local.dao.FtsSearchDao
import com.mamba.picme.data.local.dao.LocationDao
import com.mamba.picme.data.local.dao.OcrWordDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.local.dao.TagDao
import com.mamba.picme.data.local.dao.TagScanTaskDao
import com.mamba.picme.data.local.entity.FaceEmbeddingEntity
import com.mamba.picme.data.local.entity.LocationHierarchyEntity
import com.mamba.picme.data.local.entity.MediaLocationEntity
import com.mamba.picme.data.local.entity.MediaTagCrossRef
import com.mamba.picme.data.local.entity.OcrWordEntity
import com.mamba.picme.data.local.entity.OcrWordOccurrence
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.local.entity.TagEntity
import com.mamba.picme.data.local.entity.TagScanTaskEntity
import com.mamba.picme.data.model.MediaEntity

@Database(
    entities = [
        MediaEntity::class,
        ChatMessageEntity::class,
        ChatSessionEntity::class,
        PersonEntity::class,
        FaceEmbeddingEntity::class,
        TagEntity::class,
        MediaTagCrossRef::class,
        OcrWordEntity::class,
        OcrWordOccurrence::class,
        LocationHierarchyEntity::class,
        MediaLocationEntity::class,
        TagScanTaskEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun tagDao(): TagDao
    abstract fun tagScanTaskDao(): TagScanTaskDao
    abstract fun ocrWordDao(): OcrWordDao
    abstract fun personDao(): PersonDao
    abstract fun locationDao(): LocationDao
    abstract fun ftsSearchDao(): FtsSearchDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "picme_database"
                )
                    .addMigrations(MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Migration 2 → 3：新增 tag_scan_tasks 表，媒体表增加 lastTagScanAt / lastTagScanPasses
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 新增 tag_scan_tasks 表
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tag_scan_tasks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `mediaId` INTEGER NOT NULL,
                        `pass` TEXT NOT NULL,
                        `tagCategories` TEXT,
                        `status` TEXT NOT NULL,
                        `priority` INTEGER NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `scheduledAt` INTEGER,
                        `startedAt` INTEGER,
                        `completedAt` INTEGER,
                        `errorMessage` TEXT
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tag_scan_tasks_status_priority_scheduledAt` ON `tag_scan_tasks` (`status`, `priority`, `scheduledAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tag_scan_tasks_mediaId_pass_status` ON `tag_scan_tasks` (`mediaId`, `pass`, `status`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tag_scan_tasks_sessionId_status` ON `tag_scan_tasks` (`sessionId`, `status`)"
                )

                // 媒体表新增字段
                database.execSQL(
                    "ALTER TABLE `media_assets` ADD COLUMN `lastTagScanAt` INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE `media_assets` ADD COLUMN `lastTagScanPasses` TEXT"
                )
            }
        }
    }
}
