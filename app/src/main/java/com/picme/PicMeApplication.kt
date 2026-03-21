package com.picme

import android.app.Application
import com.picme.data.local.AppDatabase
import com.picme.data.repository.MediaRepositoryImpl
import com.picme.data.preferences.UserPreferencesRepository
import com.picme.domain.repository.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class PicMeApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob())

    val database by lazy { AppDatabase.getDatabase(this) }
    val repository: MediaRepository by lazy { MediaRepositoryImpl(database.mediaDao()) }
    val userPreferencesRepository by lazy { UserPreferencesRepository(this) }
}
