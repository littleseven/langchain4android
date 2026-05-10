package com.picme.features.gallery

import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.picme.beauty.api.PhotoProcessor
import com.picme.beauty.api.facedetect.FaceDetectionSource
import com.picme.beauty.api.facedetect.FaceDetector
import com.picme.beauty.api.toBeautyParams
import com.picme.beauty.internal.facedetect.Face106ToWarpParams
import com.picme.domain.model.DuplicateGroup
import com.picme.domain.model.GroupedMedia
import com.picme.domain.model.GroupingMode
import com.picme.domain.model.MediaAsset
import com.picme.domain.repository.MediaRepository
import com.picme.domain.usecase.FindDuplicateMediaUseCase
import com.picme.domain.usecase.GetGroupedMediaUseCase
import com.picme.domain.usecase.OcrProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaViewModel(
    private val repository: MediaRepository,
    private val getGroupedMediaUseCase: GetGroupedMediaUseCase,
    private val findDuplicateMediaUseCase: FindDuplicateMediaUseCase,
    private val ocrUseCase: OcrProcessor,
    private val photoProcessor: PhotoProcessor,
    private val faceDetector: FaceDetector
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

    // ========================================
    // 静态图美颜编辑（相册预览页）
    // ========================================

    sealed class PhotoEditState {
        object Idle : PhotoEditState()
        object Analyzing : PhotoEditState()
        object Processing : PhotoEditState()
        data class Ready(val bitmap: Bitmap, val faceData: com.picme.beauty.api.FaceData?) : PhotoEditState()
        data class Error(val message: String) : PhotoEditState()
    }

    private val _photoEditState = MutableStateFlow<PhotoEditState>(PhotoEditState.Idle)
    val photoEditState: StateFlow<PhotoEditState> = _photoEditState.asStateFlow()

    private var cachedEditFaceData: com.picme.beauty.api.FaceData? = null

    /**
     * 进入编辑模式时预处理：加载图片并执行一次人脸检测，缓存 FaceData 供后续复用
     */
    fun preparePhotoEdit(bitmap: Bitmap, lensFacing: Int = 1) {
        viewModelScope.launch(Dispatchers.Default) {
            _photoEditState.value = PhotoEditState.Analyzing
            try {
                val detectionResult = faceDetector.detectPhoto(bitmap, lensFacing)
                val faceData = detectionResult?.landmarks106?.toFaceData(bitmap.width, bitmap.height)
                cachedEditFaceData = faceData

                if (faceData != null) {
                    Log.d("PicMe:Gallery", "Face detected for editing, landmarks=${detectionResult.landmarks106.size}")
                } else {
                    Log.w("PicMe:Gallery", "No face detected for editing")
                }

                _photoEditState.value = PhotoEditState.Ready(bitmap, faceData)
            } catch (e: Exception) {
                Log.e("PicMe:Gallery", "Face detection failed: ${e.message}", e)
                _photoEditState.value = PhotoEditState.Error("人脸检测失败：${e.message}")
            }
        }
    }

    /**
     * 对静态 Bitmap 应用美颜处理（GPU 离屏渲染）
     * 若已调用 [preparePhotoEdit] 缓存 FaceData，则跳过重复检测
     *
     * @param bitmap 原始图片
     * @param settings 美颜设置
     * @param lensFacing 镜头方向（影响人脸检测镜像）
     */
    fun processPhoto(bitmap: Bitmap, settings: com.picme.beauty.api.BeautySettings, lensFacing: Int = 1) {
        viewModelScope.launch(Dispatchers.Default) {
            _photoEditState.value = PhotoEditState.Processing
            try {
                Log.d("PicMe:Gallery", "Processing photo: smoothing=${settings.smoothing}, filter=${settings.colorFilter}, style=${settings.styleFilter}")

                val faceData = cachedEditFaceData ?: run {
                    val detectionResult = faceDetector.detectPhoto(bitmap, lensFacing)
                    detectionResult?.landmarks106?.toFaceData(bitmap.width, bitmap.height)
                }

                val params = settings.toBeautyParams()
                val processedBitmap = photoProcessor.process(bitmap, params, faceData)

                Log.d("PicMe:Gallery", "Photo processing completed")
                _photoEditState.value = PhotoEditState.Ready(processedBitmap, faceData)
            } catch (e: Exception) {
                Log.e("PicMe:Gallery", "Photo processing failed: ${e.message}", e)
                _photoEditState.value = PhotoEditState.Error("处理失败：${e.message}")
            }
        }
    }

    /**
     * 将处理后的 Bitmap 保存为新的图片文件
     */
    fun saveProcessedPhoto(context: Context, bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val contentValues = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "EDITED_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                uri?.let { imageUri ->
                    context.contentResolver.openOutputStream(imageUri)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                    }
                    Log.d("PicMe:Gallery", "Saved edited photo to $imageUri")
                    // 刷新媒体库
                    repository.refreshMediaLibrary()
                }
            } catch (e: Exception) {
                Log.e("PicMe:Gallery", "Failed to save edited photo: ${e.message}", e)
            }
        }
    }

    fun clearPhotoEditState() {
        _photoEditState.value = PhotoEditState.Idle
        cachedEditFaceData = null
    }

    /**
     * 106 点 landmarks → FaceData 转换（用于静态图编辑路径）
     */
    private fun FloatArray.toFaceData(imageWidth: Int, imageHeight: Int): com.picme.beauty.api.FaceData? {
        if (this.size < 212) return null
        val warpParams = Face106ToWarpParams.convert(this, FaceDetectionSource.MEDIAPIPE)
        return com.picme.beauty.api.FaceData(
            faceCenterX = warpParams.faceCenterX,
            faceCenterY = warpParams.faceCenterY,
            leftEyeX = warpParams.leftEyeX,
            leftEyeY = warpParams.leftEyeY,
            rightEyeX = warpParams.rightEyeX,
            rightEyeY = warpParams.rightEyeY,
            mouthCenterX = warpParams.mouthCenterX,
            mouthCenterY = warpParams.mouthCenterY,
            mouthLeftX = warpParams.mouthLeftX,
            mouthLeftY = warpParams.mouthLeftY,
            mouthRightX = warpParams.mouthRightX,
            mouthRightY = warpParams.mouthRightY,
            upperLipCenterX = warpParams.upperLipCenterX,
            upperLipCenterY = warpParams.upperLipCenterY,
            lowerLipCenterX = warpParams.lowerLipCenterX,
            lowerLipCenterY = warpParams.lowerLipCenterY,
            faceRadius = warpParams.faceRadius,
            hasFace = true,
            landmarks106 = this
        )
    }
}
