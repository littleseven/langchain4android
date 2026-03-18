package com.example.picme

import android.app.Application
import com.example.picme.data.local.AppDatabase
import com.example.picme.data.repository.MediaRepository
import com.example.picme.data.repository.UserPreferencesRepository

class PicMeApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { MediaRepository(database.mediaDao()) }
    val userPreferencesRepository by lazy { UserPreferencesRepository(this) }
}
