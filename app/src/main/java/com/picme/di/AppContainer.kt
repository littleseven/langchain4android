package com.picme.di

import android.content.Context
import com.picme.data.local.AppDatabase
import com.picme.data.preferences.UserPreferencesRepository
import com.picme.data.repository.MediaRepositoryImpl
import com.picme.domain.repository.MediaRepository

interface AppContainer {
    val repository: MediaRepository
    val userPreferencesRepository: UserPreferencesRepository
}

class AppContainerImpl(private val context: Context) : AppContainer {
    private val database by lazy { AppDatabase.getDatabase(context) }
    
    override val repository: MediaRepository by lazy {
        MediaRepositoryImpl(database.mediaDao())
    }
    
    override val userPreferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(context)
    }
}
