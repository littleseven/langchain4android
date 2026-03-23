package com.picme.features.gallery

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.picme.domain.model.DuplicateGroup
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

enum class GroupingMode {
    NONE, DATE, FACE, PERSON, LANDSCAPE, SWIMWEAR, SEXY
}

data class MediaGroup(
    val title: String,
    val items: List<MediaAsset>
)

class MediaViewModel(
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

    fun recognizeTextFromCurrentImage(context: Context, uri: Uri) {
        viewModelScope.launch {
            Log.d("PicMe:Gallery", "Starting OCR for URI: $uri")
            _ocrState.value = OcrResult.Loading
            val result = ocrUseCase.recognizeFromUri(context, uri)
            _ocrState.value = if (result != null) {
                Log.d("PicMe:Gallery", "OCR Success: ${result.take(20)}...")
                OcrResult.Success(result)
            } else {
                Log.w("PicMe:Gallery", "OCR Failed or no text found")
                OcrResult.Error("识别失败")
            }
        }
    }

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
        Log.d("PicMe:Gallery", "Setting grouping mode to: $mode")
        _groupingMode.value = mode
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
                group.fileUris.filterIndexed { index, uri -> index != keepIndex }
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
    private val context: Context,
    private val repository: MediaRepository,
    private val ocrUseCase: OcrUseCase
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MediaViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MediaViewModel(
                repository,
                GetGroupedMediaUseCase(context),
                FindDuplicateMediaUseCase(repository),
                ocrUseCase
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
