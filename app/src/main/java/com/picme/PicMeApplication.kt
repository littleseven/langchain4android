package com.picme

import android.app.Application
import com.picme.data.local.AppDatabase
import com.picme.data.repository.MediaRepository
import com.picme.data.repository.UserPreferencesRepository

class PicMeApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { MediaRepository(database.mediaDao()) }
    val userPreferencesRepository by lazy { UserPreferencesRepository(this) }
}
