package com.picme.features.gallery

import android.content.Context
import android.net.Uri
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
    private val ocrUseCase: OcrUseCase // 新增依赖
) : ViewModel() {

    private val _groupingMode = MutableStateFlow(GroupingMode.NONE)
    val groupingMode = _groupingMode.asStateFlow()
    
    private val _showDuplicateManager = MutableStateFlow(false)
    val showDuplicateManager = _showDuplicateManager.asStateFlow()
    
    private val _duplicateGroups = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val duplicateGroups = _duplicateGroups.asStateFlow()
    
    // OCR 功能相关状态
    private val _ocrState = MutableStateFlow<OcrResult?>(null)
    val ocrState: StateFlow<OcrResult?> = _ocrState.asStateFlow()

    sealed class OcrResult {
        object Loading : OcrResult()
        data class Success(val text: String) : OcrResult()
        data class Error(val message: String) : OcrResult()
    }

    fun clearOcrResult() {
        _ocrState.value = null
    }

    // 为当前预览的图片执行OCR识别
    fun recognizeTextFromCurrentImage(context: Context, uri: Uri) {
        viewModelScope.launch {
            _ocrState.value = OcrResult.Loading
            val result = ocrUseCase.recognizeFromUri(context, uri)
            _ocrState.value = if (result != null) {
                OcrResult.Success(result)
            } else {
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
                _duplicateGroups.value = findDuplicateMediaUseCase()
            } catch (e: Exception) {
                _duplicateGroups.value = emptyList()
            } finally {
                _isScanningDuplicates.value = false
            }
        }
    }
    
    fun deleteDuplicateGroup(group: DuplicateGroup, keepIndex: Int = 0) {
        viewModelScope.launch {
            // 获取需要删除的文件 URI
            val urisToDelete = if (keepIndex == 0) {
                group.getDeleteUris()
            } else {
                // 保留指定索引的文件，删除其他
                group.fileUris.filterIndexed { index, _ -> index != keepIndex }
            }
            
            // 从 URI 查找对应的 MediaAsset ID
            val idsToDelete = allMedia.value
                .filter { asset -> asset.uri in urisToDelete }
                .map { it.id }
            
            if (idsToDelete.isNotEmpty()) {
                deleteMediaByIds(idsToDelete)
                // 从列表中移除已处理的组
                _duplicateGroups.value = _duplicateGroups.value.filter { it.id != group.id }
            }
        }
    }
    
    fun deleteAllDuplicatesExceptOne() {
        viewModelScope.launch {
            val allIdsToDelete = mutableListOf<Long>()
            
            _duplicateGroups.value.forEach { group ->
                val idsInGroup = allMedia.value
                    .filter { asset -> asset.uri in group.getDeleteUris() }
                    .map { it.id }
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
    private val ocrUseCase: OcrUseCase // 新增OCR用例的依赖
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
