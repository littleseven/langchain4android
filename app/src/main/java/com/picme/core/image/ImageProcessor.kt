package com.picme.core.image

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.annotation.RequiresPermission
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.picme.beauty.api.FaceData
import com.picme.beauty.api.PhotoProcessor
import com.picme.core.common.Logger
import com.picme.beauty.api.toBeautyParams
import com.picme.beauty.api.BeautySettings
import com.picme.domain.model.BeautyStrategy
import com.picme.agent.core.api.context.MediaAsset
import com.picme.agent.core.api.context.MediaType
import com.picme.beauty.api.BeautyProcessor
import com.picme.beauty.api.Face
import com.picme.beauty.api.FaceContour
import com.picme.beauty.api.FaceLandmark
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.toAndroidColorMatrix
import com.picme.beauty.api.facedetect.FaceDetectionSource
import com.picme.beauty.api.facedetect.FaceDetector
import com.picme.beauty.internal.facedetect.Face106ToWarpParams
import com.picme.features.camera.FaceDetectionCache
import com.picme.features.gallery.MediaViewModel
import com.picme.features.camera.thread.CameraThreadRegistry
import kotlinx.coroutines.CoroutineScope
import java.text.SimpleDateFormat
import java.util.Locale
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

private const val TAG = "ImageProcessor"

interface ImageProcessor {
    fun processPhoto(
        source: Bitmap,
        filter: FilterType,
        beauty: BeautySettings,
        faces: List<Face>,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK
    ): Bitmap

    fun takePhoto(
        context: Context,
        imageCapture: ImageCapture,
        viewModel: MediaViewModel,
        filter: FilterType,
        beauty: BeautySettings,
        lensFacing: Int,
        mode: MediaType,
        cachedFaces: List<Face>,
        beautyStrategy: BeautyStrategy,
        coroutineScope: CoroutineScope?,
        onPhotoFinished: (success: Boolean) -> Unit = {}
    )

    fun takePhoto(
        context: Context,
        imageCapture: ImageCapture,
        viewModel: MediaViewModel,
        filter: FilterType,
        beauty: BeautySettings,
        lensFacing: Int,
        mode: MediaType,
        cachedFaces: List<Face>,
        beautyStrategy: BeautyStrategy,
        executor: Executor,
        onPhotoFinished: (success: Boolean) -> Unit = {}
    )

    fun startVideoRecording(
        context: Context,
        videoCapture: VideoCapture<Recorder>,
        viewModel: MediaViewModel,
        onFinished: () -> Unit
    ): Recording
}

class ImageProcessorImpl(
    private val beautyProcessor: BeautyProcessor,
    private val photoProcessor: PhotoProcessor? = null,
    private val faceDetectorManager: FaceDetector? = null
) : ImageProcessor {

    companion object {
        private const val TAG = "ImageProcessor"
    }

    override fun processPhoto(
        source: Bitmap,
        filter: FilterType,
        beauty: BeautySettings,
        faces: List<Face>,
        lensFacing: Int
    ): Bitmap {
        Logger.d(TAG, "processPhoto called: enabled=${beauty.enabled}, smoothing=${beauty.smoothing}, whitening=${beauty.whitening}, slimFace=${beauty.slimFace}, bigEyes=${beauty.bigEyes}, faces=${faces.size}")

        // 拍照路径始终在当前帧重新检测人脸，避免复用预览帧导致坐标偏移。
        var photoFaceData: FaceData? = null
        val detector = faceDetectorManager
        // Shader 管线中的多项效果依赖人脸参数，尽量保证拍照路径有人脸检测结果。
        if (detector != null) {
            Logger.d(TAG, "Re-detecting face on photo bitmap for makeup/skin effects")
            val detectionResult = detector.detectPhoto(source, lensFacing)
            if (detectionResult != null) {
                Logger.d(TAG, "Photo face detection success: ${detectionResult.detectionSource}")
                photoFaceData = detectionResult.landmarks106.toFaceDataFromLandmarks106(source.width, source.height)
            } else {
                Logger.w(TAG, "Photo face detection failed, falling back to cached faces")
            }
        }

        // [GPU路径] 大美丽模式下优先使用 GPU 离屏渲染，确保预览/拍照效果一致
        val gpuProcessor = photoProcessor
        if (gpuProcessor != null) {
            Logger.d(TAG, "Trying GPU photo processing path")
            val params = beauty.toBeautyParams()
            val faceData = photoFaceData ?: faces.toFaceData(source.width, source.height)
            val gpuResult = gpuProcessor.process(source, params, faceData)
            Logger.d(TAG, "GPU photo processing succeeded")

            // GPU 路径已包含滤镜（colorMatrix/styleEffect 在 Shader 中处理），
            // 但 App 层的 FilterType ColorMatrix 滤镜需要额外应用
            return if (filter != FilterType.NONE && params.colorMatrix == null) {
                applyColorMatrixFilter(gpuResult, filter)
            } else {
                gpuResult
            }
        }

        // [调试模式] GPU 处理器不存在时，直接抛出异常
        throw IllegalStateException("PhotoProcessor is null, GPU photo processing not available")
    }

    override fun takePhoto(
        context: Context,
        imageCapture: ImageCapture,
        viewModel: MediaViewModel,
        filter: FilterType,
        beauty: BeautySettings,
        lensFacing: Int,
        mode: MediaType,
        cachedFaces: List<Face>,
        beautyStrategy: BeautyStrategy,
        coroutineScope: CoroutineScope?,
        onPhotoFinished: (success: Boolean) -> Unit
    ) {
        takePhoto(
            context = context,
            imageCapture = imageCapture,
            viewModel = viewModel,
            filter = filter,
            beauty = beauty,
            lensFacing = lensFacing,
            mode = mode,
            cachedFaces = cachedFaces,
            beautyStrategy = beautyStrategy,
            executor = try {
                Executor {
                    CameraThreadRegistry.getCameraHandler().post(it)
                }
            } catch (e: IllegalStateException) {
                Logger.w(TAG, "CameraThreadRegistry not initialized, falling back to MainExecutor")
                ContextCompat.getMainExecutor(context)
            },
            onPhotoFinished = onPhotoFinished
        )
    }

    override fun takePhoto(
        context: Context,
        imageCapture: ImageCapture,
        viewModel: MediaViewModel,
        filter: FilterType,
        beauty: BeautySettings,
        lensFacing: Int,
        mode: MediaType,
        cachedFaces: List<Face>,
        beautyStrategy: BeautyStrategy,
        executor: Executor,
        onPhotoFinished: (success: Boolean) -> Unit
    ) {
        Logger.d(TAG, "takePhoto called with filter=$filter, beauty=$beauty, lensFacing=$lensFacing, cachedFaces=${cachedFaces.size}, beautyStrategy=$beautyStrategy")

        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())

        imageCapture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // [线程断言] 确保回调在 CameraHandlerThread 执行
                    Logger.d(TAG, "[ThreadCheck] onCaptureSuccess running on: ${Thread.currentThread().name}")

                    val rotationDegrees = image.imageInfo.rotationDegrees
                    val cropRect = image.cropRect
                    Logger.d(TAG, "ImageProxy cropRect: $cropRect, imageSize: ${image.width}x${image.height}")

                    val originalBitmap = image.toBitmap()
                    image.close()

                    val croppedBitmap = if (cropRect.width() != originalBitmap.width || cropRect.height() != originalBitmap.height) {
                        Logger.d(TAG, "Applying crop: ${cropRect.width()}x${cropRect.height()}")
                        Bitmap.createBitmap(
                            originalBitmap,
                            cropRect.left,
                            cropRect.top,
                            cropRect.width(),
                            cropRect.height()
                        )
                    } else {
                        originalBitmap
                    }

                    val matrix = Matrix().apply {
                        postRotate(rotationDegrees.toFloat())
                        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                            postScale(-1f, 1f)
                        }
                    }
                    val rotatedBitmap = Bitmap.createBitmap(
                        croppedBitmap, 0, 0, croppedBitmap.width, croppedBitmap.height, matrix, true
                    )

                    Logger.d(TAG, "Final bitmap size: ${rotatedBitmap.width}x${rotatedBitmap.height}")

                    // 仅保留一份稳定副本：处理输入和兜底来源合并。
                    val stableOriginalForSave = rotatedBitmap

                    // 拍照后处理保持在 CameraHandlerThread，同步执行避免上下文切换问题。
                    try {
                        // 仅克隆一次作为处理输入，失败时直接降级保存。
                        val processingInput = prepareBitmapForSaving(rotatedBitmap)
                        if (processingInput == null) {
                            Logger.e(TAG, "Cannot clone processing input bitmap, fallback to direct save")
                            val emergencyBitmapForSave = applyCaptureFallbackEffects(stableOriginalForSave, filter)
                            Handler(Looper.getMainLooper()).post {
                                val saved = saveBitmapToMediaStore(
                                    context, emergencyBitmapForSave, name, viewModel, false, null, mode
                                )
                                onPhotoFinished(saved)
                            }
                            return
                        }

                        val finalBitmap = processPhoto(
                            source = processingInput,
                            filter = filter,
                            beauty = beauty,
                            faces = emptyList(),
                            lensFacing = lensFacing
                        )

                        val hasFace = cachedFaces.isNotEmpty()
                        val faceId = if (cachedFaces.isNotEmpty()) "person_${cachedFaces.size}" else null

                        // 回主线程保存（MediaStore 操作需要主线程 Handler）
                        // 优先保存处理结果；若结果不可用则重跑一轮完整美颜处理。
                        val bitmapForSave = if (finalBitmap.isRecycled) {
                            Logger.w(TAG, "Processed bitmap already recycled, re-processing from stable original")
                            buildFallbackBitmapWithBeauty(
                                source = stableOriginalForSave,
                                filter = filter,
                                beauty = beauty,
                                lensFacing = lensFacing
                            )
                        } else {
                            finalBitmap
                        }
                        Handler(Looper.getMainLooper()).post {
                            val saved = saveBitmapToMediaStore(
                                context, bitmapForSave, name, viewModel, hasFace, faceId, mode
                            )
                            if (bitmapForSave !== stableOriginalForSave && !stableOriginalForSave.isRecycled) {
                                stableOriginalForSave.recycle()
                            }
                            onPhotoFinished(saved)
                        }
                    } catch (e: Throwable) {
                        Logger.e(TAG, "Photo processing failed: ${e.message}", e)
                        val fallbackBitmapForSave = buildFallbackBitmapWithBeauty(
                            source = stableOriginalForSave,
                            filter = filter,
                            beauty = beauty,
                            lensFacing = lensFacing
                        )
                        Handler(Looper.getMainLooper()).post {
                            val saved = saveBitmapToMediaStore(
                                context, fallbackBitmapForSave, name, viewModel, false, null, mode
                            )
                            if (fallbackBitmapForSave !== stableOriginalForSave && !stableOriginalForSave.isRecycled) {
                                stableOriginalForSave.recycle()
                            }
                            onPhotoFinished(saved)
                        }
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Logger.e(TAG, "Photo capture failed", exc)
                    // [Day2 状态机] 通知拍照失败
                    onPhotoFinished(false)
                }
            })
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun startVideoRecording(
        context: Context,
        videoCapture: VideoCapture<Recorder>,
        viewModel: MediaViewModel,
        onFinished: () -> Unit
    ) : Recording {
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PicMe")
            }
        }
        val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        return videoCapture.output
            .prepareRecording(context, mediaStoreOutputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                if (recordEvent is VideoRecordEvent.Finalize) {
                    if (!recordEvent.hasError()) {
                        val asset = MediaAsset(
                            uri = recordEvent.outputResults.outputUri.toString(),
                            type = MediaType.VIDEO,
                            captureDate = System.currentTimeMillis(),
                            fileName = name
                        )
                        viewModel.insertMedia(asset)
                    }
                    onFinished()
                }
            }
    }

    private fun applyColorMatrixFilter(bitmap: Bitmap, filter: FilterType): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(filter.toAndroidColorMatrix())
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }

    private fun prepareBitmapForSaving(bitmap: Bitmap): Bitmap? {
        return runCatching {
            if (bitmap.isRecycled) {
                Logger.e(TAG, "Bitmap already recycled before save")
                return null
            }
            val safeConfig = bitmap.config ?: Bitmap.Config.ARGB_8888
            bitmap.copy(safeConfig, false)
        }.onFailure { error ->
            Logger.e(TAG, "Failed to clone bitmap for saving", error)
        }.getOrNull()
    }

    private fun buildFallbackBitmapWithBeauty(
        source: Bitmap,
        filter: FilterType,
        beauty: BeautySettings,
        lensFacing: Int
    ): Bitmap {
        val reprocessInput = prepareBitmapForSaving(source) ?: source
        val reprocessed = runCatching {
            processPhoto(
                source = reprocessInput,
                filter = filter,
                beauty = beauty,
                faces = emptyList(),
                lensFacing = lensFacing
            )
        }.onFailure { error ->
            Logger.e(TAG, "Fallback full-effect reprocess failed", error)
        }.getOrNull()

        if (reprocessed != null && !reprocessed.isRecycled) {
            return reprocessed
        }

        return applyCaptureFallbackEffects(source, filter)
    }

    private fun applyCaptureFallbackEffects(bitmap: Bitmap, filter: FilterType): Bitmap {
        if (bitmap.isRecycled) {
            return bitmap
        }
        if (filter == FilterType.NONE) {
            return bitmap
        }
        return runCatching {
            applyColorMatrixFilter(bitmap, filter)
        }.onFailure { error ->
            Logger.e(TAG, "Failed to apply fallback filter", error)
        }.getOrDefault(bitmap)
    }

    private fun saveBitmapToMediaStore(
        context: Context,
        bitmap: Bitmap,
        name: String,
        viewModel: MediaViewModel,
        hasFace: Boolean,
        faceId: String?,
        mode: MediaType
    ): Boolean {
        if (bitmap.isRecycled) {
            Logger.e(TAG, "Skip saving image: bitmap already recycled")
            return false
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicMe")
            }
        }
        var saveSucceeded = false
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        )
        uri?.let {
            val compressed = context.contentResolver.openOutputStream(it)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            } ?: false

            if (!compressed) {
                Logger.e(TAG, "Failed to compress bitmap to JPEG")
                context.contentResolver.delete(it, null, null)
                return@let
            }

            val asset = MediaAsset(
                uri = it.toString(),
                type = mode,
                captureDate = System.currentTimeMillis(),
                fileName = name,
                hasFace = hasFace,
                faceId = faceId
            )
            viewModel.insertMedia(asset)
            saveSucceeded = true
        } ?: Logger.e(TAG, "Failed to insert image into MediaStore")

        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
        return saveSucceeded
    }
}

/**
 * 106点 landmarks → FaceData 转换（用于拍照路径重新检测）
 */
private fun FloatArray.toFaceDataFromLandmarks106(imageWidth: Int, imageHeight: Int): FaceData? {
    if (this.size < 212) return null
    val warpParams = Face106ToWarpParams.convert(
        this, FaceDetectionSource.MEDIAPIPE
    )
    return FaceData(
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
        lipOuterPoints = warpParams.lipOuterContourPoints.map { it.x to it.y },
        lipInnerPoints = warpParams.lipInnerContourPoints.map { it.x to it.y },
        leftCheekPoints = warpParams.leftCheekContourPoints.map { it.x to it.y },
        rightCheekPoints = warpParams.rightCheekContourPoints.map { it.x to it.y },
        landmarks106 = this
    )
}

/**
 * ML Kit Face → FaceData 转换扩展
 *
 * 将 ML Kit 人脸检测结果转换为 beauty-engine 模块所需的 FaceData 格式。
 * 坐标标准化为 0.0~1.0 范围（基于图片宽高）。
 */
private fun List<Face>.toFaceData(imageWidth: Int, imageHeight: Int): FaceData? {
    if (isEmpty()) return null

    val face = first()
    val w = imageWidth.toFloat()
    val h = imageHeight.toFloat()

    val bounds = face.boundingBox
    val faceCenterX = bounds.centerX() / w
    val faceCenterY = bounds.centerY() / h
    val faceRadius = maxOf(bounds.width(), bounds.height()) / maxOf(w, h) * 0.5f

    val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
    val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
    val leftEyeX = leftEye?.x?.div(w) ?: (faceCenterX - 0.1f)
    val leftEyeY = leftEye?.y?.div(h) ?: (faceCenterY - 0.05f)
    val rightEyeX = rightEye?.x?.div(w) ?: (faceCenterX + 0.1f)
    val rightEyeY = rightEye?.y?.div(h) ?: (faceCenterY - 0.05f)

    val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
    val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
    val mouthCenterX = ((mouthLeft?.x ?: 0f) + (mouthRight?.x ?: 0f)) / 2f / w
    val mouthCenterY = ((mouthLeft?.y ?: 0f) + (mouthRight?.y ?: 0f)) / 2f / h

    // 嘴唇轮廓点（从 FaceContour 提取）
    val lipOuterPoints = mutableListOf<Pair<Float, Float>>()
    val lipInnerPoints = mutableListOf<Pair<Float, Float>>()
    face.getContour(FaceContour.UPPER_LIP_TOP)?.points?.forEach { pt ->
        lipOuterPoints.add(pt.x / w to pt.y / h)
    }
    face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points?.forEach { pt ->
        lipInnerPoints.add(pt.x / w to pt.y / h)
    }
    face.getContour(FaceContour.LOWER_LIP_TOP)?.points?.forEach { pt ->
        lipInnerPoints.add(pt.x / w to pt.y / h)
    }
    face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points?.forEach { pt ->
        lipOuterPoints.add(pt.x / w to pt.y / h)
    }

    // 脸颊轮廓点
    val leftCheekPoints = mutableListOf<Pair<Float, Float>>()
    val rightCheekPoints = mutableListOf<Pair<Float, Float>>()
    face.getContour(FaceContour.LEFT_CHEEK)?.points?.forEach { pt ->
        leftCheekPoints.add(pt.x / w to pt.y / h)
    }
    face.getContour(FaceContour.RIGHT_CHEEK)?.points?.forEach { pt ->
        rightCheekPoints.add(pt.x / w to pt.y / h)
    }

    // 从预览缓存读取 106 点数据，供拍照兜底路径复用。
    val landmarks106 = FaceDetectionCache.getCachedLandmarks106()
    if (landmarks106 != null) {
        Logger.d(TAG, "Using cached landmarks106 from FaceDetectionCache")
    } else {
        Logger.w(TAG, "No cached landmarks106 available, fallback to bitmap processing")
    }

    return FaceData(
        faceCenterX = faceCenterX.coerceIn(0f, 1f),
        faceCenterY = faceCenterY.coerceIn(0f, 1f),
        leftEyeX = leftEyeX.coerceIn(0f, 1f),
        leftEyeY = leftEyeY.coerceIn(0f, 1f),
        rightEyeX = rightEyeX.coerceIn(0f, 1f),
        rightEyeY = rightEyeY.coerceIn(0f, 1f),
        mouthCenterX = mouthCenterX.coerceIn(0f, 1f),
        mouthCenterY = mouthCenterY.coerceIn(0f, 1f),
        mouthLeftX = (mouthLeft?.x?.div(w) ?: (mouthCenterX - 0.05f)).coerceIn(0f, 1f),
        mouthLeftY = (mouthLeft?.y?.div(h) ?: mouthCenterY).coerceIn(0f, 1f),
        mouthRightX = (mouthRight?.x?.div(w) ?: (mouthCenterX + 0.05f)).coerceIn(0f, 1f),
        mouthRightY = (mouthRight?.y?.div(h) ?: mouthCenterY).coerceIn(0f, 1f),
        upperLipCenterX = mouthCenterX,
        upperLipCenterY = mouthCenterY - 0.02f,
        lowerLipCenterX = mouthCenterX,
        lowerLipCenterY = mouthCenterY + 0.02f,
        faceRadius = faceRadius.coerceIn(0.08f, 0.45f),
        hasFace = true,
        lipOuterPoints = lipOuterPoints,
        lipInnerPoints = lipInnerPoints,
        leftCheekPoints = leftCheekPoints,
        rightCheekPoints = rightCheekPoints,
        landmarks106 = landmarks106
    )
}
