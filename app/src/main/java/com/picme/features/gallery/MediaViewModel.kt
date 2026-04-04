package com.picme.features.gallery

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.picme.R
import com.picme.di.MediaViewModelDependencies
import com.picme.domain.model.DuplicateGroup
import com.picme.domain.model.GroupTitleType
import com.picme.domain.model.GroupedMedia
import com.picme.domain.model.GroupingMode
import com.picme.domain.model.MediaAsset
import com.picme.domain.repository.MediaRepository
import com.picme.domain.usecase.FindDuplicateMediaUseCase
import com.picme.domain.usecase.GetGroupedMediaUseCase
import com.picme.domain.usecase.OcrUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MediaGroup(
    val title: String,
    val items: List<MediaAsset>
)

class MediaViewModel(
    private val resources: Resources,
    private val repository: MediaRepository,
    private val getGroupedMediaUseCase: GetGroupedMediaUseCase,
    private val findDuplicateMediaUseCase: FindDuplicateMediaUseCase,
    private val ocrUseCase: OcrUseCase
) : ViewModel() {

    private val _groupingMode = MutableStateFlow(GroupingMode.NONE)
    val groupingMode = _groupingMode.asStateFlow()
    
    private val _showDuplicateManager = MutableStateFlow(false)
    val showDuplicateManager = _showDuplicateManager.asStateFlow()
    
    private val _duplicateGroups = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val duplicateGroups = _duplicateGroups.asStateFlow()
    
    private val _ocrState = MutableStateFlow<OcrResult?>(null)
    val ocrState: StateFlow<OcrResult?> = _ocrState.asStateFlow()

    sealed class OcrResult {
        object Loading : OcrResult()
        data class Success(val text: String) : OcrResult()
        data class Error(val message: String) : OcrResult()
    }

    fun clearOcrResult() {
        Log.d("PicMe:Gallery", "Clearing OCR result")
        _ocrState.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        Log.d("PicMe:Gallery", "MediaViewModel cleared, releasing OCR resources")
        ocrUseCase.close()
    }

    fun recognizeTextFromCurrentImage(context: Context, uri: Uri) {
        viewModelScope.launch {
            Log.d("PicMe:Gallery", "Starting OCR for URI: $uri")
            _ocrState.value = OcrResult.Loading
            try {
                val result = ocrUseCase.recognizeFromUri(context, uri)
                _ocrState.value = if (result != null) {
                    Log.d("PicMe:Gallery", "OCR Success: ${result.take(20)}...")
                    OcrResult.Success(result)
                } else {
                    Log.w("PicMe:Gallery", "OCR Failed or no text found")
                    OcrResult.Error("未找到文字")
                }
            } catch (e: Exception) {
                Log.e("PicMe:Gallery", "OCR Exception: ${e.message}", e)
                _ocrState.value = OcrResult.Error("识别失败：${e.message}")
            }
        }
    }

    private val _isScanningDuplicates = MutableStateFlow(false)
    val isScanningDuplicates = _isScanningDuplicates.asStateFlow()

    val groupedMedia: StateFlow<List<MediaGroup>> = combine(
        repository.allMedia,
        _groupingMode
    ) { allMedia, mode ->
        getGroupedMediaUseCase(allMedia, mode).map { groupedMedia ->
            mapToUiGroup(groupedMedia)
        }
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
        Log.d("PicMe:Gallery", "Setting grouping mode to: $mode")
        _groupingMode.value = mode
    }

    private fun mapToUiGroup(groupedMedia: GroupedMedia): MediaGroup {
        val title = when (groupedMedia.titleType) {
            GroupTitleType.NONE -> groupedMedia.titleValue
            GroupTitleType.DATE -> groupedMedia.titleValue
            GroupTitleType.WITH_FACES -> resources.getString(R.string.with_faces)
            GroupTitleType.NO_FACES -> resources.getString(R.string.no_faces)
            GroupTitleType.PERSON -> resources.getString(R.string.person_group, groupedMedia.titleValue)
            GroupTitleType.LANDSCAPE -> resources.getString(R.string.landscape)
            GroupTitleType.SWIMWEAR -> resources.getString(R.string.swimwear)
            GroupTitleType.SEXY -> resources.getString(R.string.sexy)
        }

        return MediaGroup(title = title, items = groupedMedia.items)
    }

    fun insertMedia(mediaAsset: MediaAsset) {
        viewModelScope.launch {
            repository.insertMedia(mediaAsset)
        }
    }

    fun deleteMediaByIds(ids: List<Long>) {
        viewModelScope.launch {
            Log.d("PicMe:Gallery", "Deleting media items: $ids")
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
            Log.d("PicMe:Gallery", "Scanning for duplicates")
            _isScanningDuplicates.value = true
            try {
                _duplicateGroups.value = findDuplicateMediaUseCase()
                Log.d("PicMe:Gallery", "Found ${_duplicateGroups.value.size} duplicate groups")
            } catch (e: Exception) {
                Log.e("PicMe:Gallery", "Error scanning for duplicates", e)
                _duplicateGroups.value = emptyList()
            } finally {
                _isScanningDuplicates.value = false
            }
        }
    }
    
    fun deleteDuplicateGroup(group: DuplicateGroup, keepIndex: Int = 0) {
        viewModelScope.launch {
            val urisToDelete = if (keepIndex == 0) {
                group.getDeleteUris()
            } else {
                group.fileUris.filterIndexed { index, _ -> index != keepIndex }
            }
            
            val idsToDelete = allMedia.value
                .filter { asset -> asset.uri in urisToDelete }
                .map { asset -> asset.id }
            
            if (idsToDelete.isNotEmpty()) {
                deleteMediaByIds(idsToDelete)
                _duplicateGroups.value = _duplicateGroups.value.filter { groupItem -> groupItem.id != group.id }
            }
        }
    }
    
    fun deleteAllDuplicatesExceptOne() {
        viewModelScope.launch {
            Log.d("PicMe:Gallery", "Deleting all duplicates except one per group")
            val allIdsToDelete = mutableListOf<Long>()
            
            _duplicateGroups.value.forEach { group ->
                val idsInGroup = allMedia.value
                    .filter { asset -> asset.uri in group.getDeleteUris() }
                    .map { asset -> asset.id }
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
    private val dependencies: MediaViewModelDependencies
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MediaViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MediaViewModel(
                resources = dependencies.resources,
                repository = dependencies.repository,
                getGroupedMediaUseCase = dependencies.getGroupedMediaUseCase,
                findDuplicateMediaUseCase = dependencies.findDuplicateMediaUseCase,
                ocrUseCase = dependencies.ocrUseCase
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
