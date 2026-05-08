package com.picme.features.gallery

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.picme.domain.model.DuplicateGroup
import com.picme.domain.model.GroupedMedia
import com.picme.domain.model.GroupingMode
import com.picme.domain.model.MediaAsset
import com.picme.domain.repository.MediaRepository
import com.picme.domain.usecase.FindDuplicateMediaUseCase
import com.picme.domain.usecase.GetGroupedMediaUseCase
import com.picme.domain.usecase.OcrProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MediaViewModel(
    private val repository: MediaRepository,
    private val getGroupedMediaUseCase: GetGroupedMediaUseCase,
    private val findDuplicateMediaUseCase: FindDuplicateMediaUseCase,
    private val ocrUseCase: OcrProcessor
) : ViewModel() {

    private val _groupingMode = MutableStateFlow(GroupingMode.NONE)
    val groupingMode = _groupingMode.asStateFlow()
    
    private val _showDuplicateManager = MutableStateFlow(false)
    val showDuplicateManager = _showDuplicateManager.asStateFlow()
    
    private val _duplicateGroups = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val duplicateGroups = _duplicateGroups.asStateFlow()
    
    private val _ocrState = MutableStateFlow<OcrResult?>(null)
    val ocrState: StateFlow<OcrResult?> = _ocrState.asStateFlow()

    private val _deleteAuthRequest = MutableStateFlow<DeleteAuthRequest?>(null)
    val deleteAuthRequest: StateFlow<DeleteAuthRequest?> = _deleteAuthRequest.asStateFlow()

    sealed class OcrResult {
        object Loading : OcrResult()
        data class Success(val text: String) : OcrResult()
        data class Error(val message: String) : OcrResult()
    }

    sealed class DeleteAuthRequest {
        data class Api29(val intentSender: IntentSender) : DeleteAuthRequest()
        data class Api30(val uris: List<Uri>) : DeleteAuthRequest()
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

    val groupedMedia: StateFlow<List<GroupedMedia>> = combine(
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
            repository.refreshMediaLibrary()
        }
    }

    fun refreshMediaLibrary() {
        viewModelScope.launch {
            Log.d("PicMe:Gallery", "Refreshing media library")
            repository.refreshMediaLibrary()
        }
    }

    fun deleteMediaByIds(ids: List<Long>) {
        viewModelScope.launch {
            Log.d("PicMe:Gallery", "Deleting media items: $ids")
            repository.deleteMediaByIds(ids)

            // 协程完成后检查是否需要用户授权，避免 GalleryScreen 同步调用导致竞态条件
            val recoverableSender = repository.getPendingRecoverableIntentSender()
            if (recoverableSender != null) {
                _deleteAuthRequest.value = DeleteAuthRequest.Api29(recoverableSender)
                return@launch
            }

            val pendingUris = repository.getPendingDeleteUris()
            if (pendingUris.isNotEmpty()) {
                _deleteAuthRequest.value = DeleteAuthRequest.Api30(pendingUris)
            }
        }
    }

    fun consumeDeleteAuthRequest() {
        _deleteAuthRequest.value = null
    }
    
    /**
     * 获取待删除的 URI 列表（用于权限请求）
     */
    fun getPendingDeleteUris() = repository.getPendingDeleteUris()
    
    /**
     * 清除待删除的 URI 列表
     */
    fun clearPendingDeleteUris() {
        repository.clearPendingDeleteUris()
    }
    
    /**
     * 获取 Android 10 恢复性删除的 IntentSender
     */
    fun getPendingRecoverableIntentSender() = repository.getPendingRecoverableIntentSender()

    /**
     * 清除 Android 10 恢复性删除状态
     */
    fun clearPendingRecoverable() {
        repository.clearPendingRecoverable()
    }

    /**
     * 在用户授权后执行删除操作
     */
    fun executePendingDeletes() {
        viewModelScope.launch {
            Log.d("PicMe:Gallery", "Executing pending deletes after user authorization")
            repository.executePendingDeletes()
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
