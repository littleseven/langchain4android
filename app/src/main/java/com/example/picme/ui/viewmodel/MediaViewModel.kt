package com.example.picme.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.picme.data.model.MediaAsset
import com.example.picme.data.repository.MediaRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MediaViewModel(private val repository: MediaRepository) : ViewModel() {

    val allMedia: StateFlow<List<MediaAsset>> = repository.allMedia
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun insertMedia(mediaAsset: MediaAsset) {
        viewModelScope.launch {
            repository.insertMedia(mediaAsset)
        }
    }

    fun deleteMedia(mediaAsset: MediaAsset) {
        viewModelScope.launch {
            repository.deleteMedia(mediaAsset)
        }
    }

    fun deleteMediaByIds(ids: List<Long>) {
        viewModelScope.launch {
            repository.deleteMediaByIds(ids)
        }
    }
}

class MediaViewModelFactory(private val repository: MediaRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MediaViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MediaViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
