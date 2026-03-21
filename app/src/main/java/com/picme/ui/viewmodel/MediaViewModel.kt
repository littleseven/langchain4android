package com.picme.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.picme.data.model.MediaAsset
import com.picme.data.repository.MediaRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

enum class GroupingMode {
    NONE, DATE, FACE, PERSON
}

data class MediaGroup(
    val title: String,
    val items: List<MediaAsset>
)

class MediaViewModel(private val repository: MediaRepository) : ViewModel() {

    private val _groupingMode = MutableStateFlow(GroupingMode.NONE)
    val groupingMode = _groupingMode.asStateFlow()

    val groupedMedia: StateFlow<List<MediaGroup>> = combine(
        repository.allMedia,
        _groupingMode
    ) { allMedia, mode ->
        groupMedia(allMedia, mode)
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

    private fun groupMedia(media: List<MediaAsset>, mode: GroupingMode): List<MediaGroup> {
        return when (mode) {
            GroupingMode.NONE -> listOf(MediaGroup("", media))
            GroupingMode.DATE -> {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                media.groupBy { sdf.format(Date(it.captureDate)) }
                    .map { MediaGroup(it.key, it.value) }
            }
            GroupingMode.FACE -> {
                val hasFace = media.filter { it.hasFace }
                val noFace = media.filter { !it.hasFace }
                listOf(
                    MediaGroup("With Faces", hasFace),
                    MediaGroup("No Faces", noFace)
                ).filter { it.items.isNotEmpty() }
            }
            GroupingMode.PERSON -> {
                media.groupBy { it.faceId ?: "Unknown" }
                    .map { MediaGroup(if (it.key == "Unknown") "Unknown" else "Person Group ${it.key}", it.value) }
            }
        }
    }

    fun insertMedia(mediaAsset: MediaAsset) = viewModelScope.launch { repository.insertMedia(mediaAsset) }
    fun deleteMediaByIds(ids: List<Long>) = viewModelScope.launch { repository.deleteMediaByIds(ids) }
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
