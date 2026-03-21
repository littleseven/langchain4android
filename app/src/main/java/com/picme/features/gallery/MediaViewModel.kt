package com.picme.features.gallery

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.picme.domain.model.MediaAsset
import com.picme.domain.repository.MediaRepository
import com.picme.domain.usecase.GetGroupedMediaUseCase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class GroupingMode {
    NONE, DATE, FACE, PERSON, LANDSCAPE
}

data class MediaGroup(
    val title: String,
    val items: List<MediaAsset>
)

class MediaViewModel(
    private val repository: MediaRepository,
    private val getGroupedMediaUseCase: GetGroupedMediaUseCase
) : ViewModel() {

    private val _groupingMode = MutableStateFlow(GroupingMode.NONE)
    val groupingMode = _groupingMode.asStateFlow()

    val groupedMedia: StateFlow<List<MediaGroup>> = combine(
        repository.allMedia,
        _groupingMode
    ) { allMedia, mode ->
        getGroupedMediaUseCase(allMedia, mode)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allMedia: StateFlow<List<MediaAsset>> = repository.allMedia
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setGroupingMode(mode: GroupingMode) {
        _groupingMode.value = mode
    }

    fun insertMedia(mediaAsset: MediaAsset) = viewModelScope.launch { repository.insertMedia(mediaAsset) }
    fun deleteMediaByIds(ids: List<Long>) = viewModelScope.launch { repository.deleteMediaByIds(ids) }
}

class MediaViewModelFactory(
    private val context: Context,
    private val repository: MediaRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MediaViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MediaViewModel(
                repository,
                GetGroupedMediaUseCase(context)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
