package com.example.picme.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.picme.data.model.MediaAsset

@Database(entities = [MediaAsset::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

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
                .fallbackToDestructiveMigration() // 当版本不匹配时重建数据库，解决 schema 变更导致的 crash
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
