package com.picme.features.gallery

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.picme.core.common.DuplicateImageDetector
import com.picme.domain.model.MediaAsset
import com.picme.domain.repository.MediaRepository
import com.picme.domain.usecase.GetGroupedMediaUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class GroupingMode {
    NONE, DATE, FACE, PERSON, LANDSCAPE, SWIMWEAR, SEXY
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
    
    private val _showDuplicateManager = MutableStateFlow(false)
    val showDuplicateManager = _showDuplicateManager.asStateFlow()
    
    private val _duplicateGroups = MutableStateFlow<List<Any>>(emptyList())
    val duplicateGroups = _duplicateGroups.asStateFlow()
    
    private val _isScanningDuplicates = MutableStateFlow(false)
    val isScanningDuplicates = _isScanningDuplicates.asStateFlow()

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
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun setGroupingMode(mode: GroupingMode) {
        _groupingMode.value = mode
    }

    fun insertMedia(mediaAsset: MediaAsset) {
        viewModelScope.launch {
            repository.insertMedia(mediaAsset)
        }
    }

    fun deleteMediaByIds(ids: List<Long>) {
        viewModelScope.launch {
            repository.deleteMediaByIds(ids)
        }
    }
    
    fun toggleDuplicateManager(show: Boolean) {
        _showDuplicateManager.value = show
        if (show && _duplicateGroups.value.isEmpty()) {
            scanForDuplicates()
        }
    }
    
    private fun scanForDuplicates() {
        viewModelScope.launch {
            _isScanningDuplicates.value = true
            try {
                _duplicateGroups.value = repository.findDuplicateMedia()
            } catch (e: Exception) {
                _duplicateGroups.value = emptyList()
            } finally {
                _isScanningDuplicates.value = false
            }
        }
    }
    
    fun deleteDuplicateGroup(group: Any, keepIndex: Int = 0) {
        viewModelScope.launch {
            // 需要转换为 DuplicateGroup
            val duplicateGroup = group as? DuplicateImageDetector.DuplicateGroup ?: return@launch
            
            // 保留第一个，删除其他重复的
            val idsToDelete = duplicateGroup.files.mapIndexedNotNull { index, file ->
                if (index != keepIndex) {
                    // 从 URI 查找对应的 MediaAsset ID
                    allMedia.value.firstOrNull { asset ->
                        asset.uri == "file://${file.absolutePath}"
                    }?.id
                } else null
            }
            if (idsToDelete.isNotEmpty()) {
                deleteMediaByIds(idsToDelete)
            }
            // 从列表中移除已处理的组
            _duplicateGroups.value = _duplicateGroups.value.filter { it !== group }
        }
    }
    
    fun deleteAllDuplicatesExceptOne() {
        viewModelScope.launch {
            val allIdsToDelete = mutableListOf<Long>()
            
            _duplicateGroups.value.forEach { group ->
                val duplicateGroup = group as? DuplicateImageDetector.DuplicateGroup ?: return@forEach
                val idsInGroup = duplicateGroup.files.mapIndexedNotNull { index, file ->
                    if (index > 0) { // 保留每组的第一张
                        allMedia.value.firstOrNull { asset ->
                            asset.uri == "file://${file.absolutePath}"
                        }?.id
                    } else null
                }
                allIdsToDelete.addAll(idsInGroup)
            }
            
            if (allIdsToDelete.isNotEmpty()) {
                deleteMediaByIds(allIdsToDelete)
                _duplicateGroups.value = emptyList()
            }
        }
    }
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
