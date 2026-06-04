package com.picme.core.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.picme.beauty.api.FaceData
import com.picme.beauty.api.PhotoProcessException
import com.picme.beauty.api.PhotoProcessor
import com.picme.core.common.Logger
import com.picme.beauty.api.toBeautyParams
import com.picme.beauty.api.BeautySettings
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.model.MediaAsset
import com.picme.domain.model.MediaType
import com.picme.beauty.api.BeautyProcessor
import com.picme.beauty.api.Face
import com.picme.beauty.api.FaceContour
import com.picme.beauty.api.FaceLandmark
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.facedetect.FaceDetectionSource
import com.picme.beauty.api.facedetect.FaceDetector
import com.picme.beauty.internal.facedetect.Face106ToWarpParams
import com.picme.features.camera.FaceDetectionCache
import com.picme.features.gallery.MediaViewModel
import com.picme.features.camera.thread.CameraThreadRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
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

    private fun createFaceMaskBitmap(
        width: Int,
        height: Int,
        faces: List<Face>,
        featherRadius: Float,
        logPrefix: String
    ): Bitmap? {
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(maskBitmap)
        val maskPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = 0xFFFFFFFF.toInt()
            maskFilter = BlurMaskFilter(featherRadius, BlurMaskFilter.Blur.NORMAL)
        }

        var regionCount = 0
        faces.forEach { face ->
            val contourPoints = face.getContour(FaceContour.FACE)?.points
            if (contourPoints != null && contourPoints.size >= 3) {
                val facePath = Path()
                facePath.moveTo(contourPoints[0].x, contourPoints[0].y)
                for (pointIndex in 1 until contourPoints.size) {
                    val point = contourPoints[pointIndex]
                    facePath.lineTo(point.x, point.y)
                }
                facePath.close()
                maskCanvas.drawPath(facePath, maskPaint)
                regionCount++
                return@forEach
            }

            val bounds = face.boundingBox
            if (bounds.width() <= 1 || bounds.height() <= 1) {
                return@forEach
            }

            // Landmark 模式下可能没有 FaceContour，回退为收敛的人脸椭圆蒙版
            // 仅覆盖面中区域，避免出现整脸或外轮廓一圈发白
            val insetX = bounds.width() * if (logPrefix.contains("Whitening")) 0.22f else 0.16f
            val insetTop = bounds.height() * if (logPrefix.contains("Whitening")) 0.18f else 0.10f
            val insetBottom = bounds.height() * if (logPrefix.contains("Whitening")) 0.24f else 0.16f
            val ovalRect = RectF(
                (bounds.left + insetX).coerceIn(0f, width.toFloat()),
                (bounds.top + insetTop).coerceIn(0f, height.toFloat()),
                (bounds.right - insetX).coerceIn(0f, width.toFloat()),
                (bounds.bottom - insetBottom).coerceIn(0f, height.toFloat())
            )

            if (ovalRect.width() > 1f && ovalRect.height() > 1f) {
                maskCanvas.drawOval(ovalRect, maskPaint)
                regionCount++
            }
        }

        return if (regionCount == 0) {
            Logger.d(TAG, "$logPrefix skipped: no face regions")
            maskBitmap.recycle()
            null
        } else {
            maskBitmap
        }
    }

    private fun applyWhiteningFallback(bitmap: Bitmap, strength: Float, faces: List<Face>): Bitmap {
        val safeStrength = strength.coerceIn(0f, 100f)
        if (safeStrength <= 0f || faces.isEmpty()) {
            return bitmap
        }

        val ratio = safeStrength / 100f
        val gain = 1f + ratio * 0.20f
        val offset = ratio * 58f

        val colorMatrix = ColorMatrix(
            floatArrayOf(
                gain, 0f, 0f, 0f, offset,
                0f, gain, 0f, 0f, offset,
                0f, 0f, gain, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )
        )

        val whitenedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(whitenedBitmap).drawBitmap(
            bitmap,
            0f,
            0f,
            Paint().apply {
                colorFilter = ColorMatrixColorFilter(colorMatrix)
                isAntiAlias = true
            }
        )

        val maskBitmap = createFaceMaskBitmap(
            width = bitmap.width,
            height = bitmap.height,
            faces = faces,
            featherRadius = 16f + ratio * 20f,
            logPrefix = "Whitening fallback"
        ) ?: run {
            whitenedBitmap.recycle()
            return bitmap
        }

        val maskedWhitened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val maskedCanvas = Canvas(maskedWhitened)
        maskedCanvas.drawBitmap(whitenedBitmap, 0f, 0f, null)
        val blendPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        maskedCanvas.drawBitmap(maskBitmap, 0f, 0f, blendPaint)
        blendPaint.xfermode = null

        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(output).drawBitmap(
            maskedWhitened,
            0f,
            0f,
            Paint().apply { alpha = (130 + ratio * 100f).toInt().coerceIn(0, 255) }
        )

        maskBitmap.recycle()
        maskedWhitened.recycle()
        whitenedBitmap.recycle()
        return output
    }

    private fun applySmoothingFallback(bitmap: Bitmap, strength: Float, faces: List<Face>): Bitmap {
        val safeStrength = strength.coerceIn(0f, 100f)
        if (safeStrength <= 0f || faces.isEmpty()) {
            return bitmap
        }

        val ratio = safeStrength / 100f
        val downsampleDivisor = (6f - ratio * 4f).coerceIn(2f, 6f)
        val downscaledWidth = (bitmap.width / downsampleDivisor).toInt().coerceAtLeast(1)
        val downscaledHeight = (bitmap.height / downsampleDivisor).toInt().coerceAtLeast(1)

        val downscaled = Bitmap.createScaledBitmap(bitmap, downscaledWidth, downscaledHeight, true)
        val smoothLayer = Bitmap.createScaledBitmap(downscaled, bitmap.width, bitmap.height, true)
        downscaled.recycle()

        val maskBitmap = createFaceMaskBitmap(
            width = bitmap.width,
            height = bitmap.height,
            faces = faces,
            featherRadius = 14f + ratio * 26f,
            logPrefix = "Smoothing fallback"
        ) ?: run {
            smoothLayer.recycle()
            return bitmap
        }

        val maskedSmooth = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val maskedCanvas = Canvas(maskedSmooth)
        maskedCanvas.drawBitmap(smoothLayer, 0f, 0f, null)
        val maskBlendPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        maskedCanvas.drawBitmap(maskBitmap, 0f, 0f, maskBlendPaint)
        maskBlendPaint.xfermode = null

        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(output).drawBitmap(
            maskedSmooth,
            0f,
            0f,
            Paint().apply { alpha = (120 + ratio * 150).toInt().coerceIn(0, 255) }
        )

        maskBitmap.recycle()
        smoothLayer.recycle()
        maskedSmooth.recycle()
        return output
    }

    private fun applySlimFaceFallback(bitmap: Bitmap, strength: Float, faces: List<Face>): Bitmap {
        if (faces.isEmpty() || strength == 0f) {
            return bitmap
        }

        val meshWidth = 24
        val meshHeight = 24
        val count = (meshWidth + 1) * (meshHeight + 1)
        val verts = FloatArray(count * 2)
        val orig = FloatArray(count * 2)

        var index = 0
        for (rowIndex in 0..meshHeight) {
            val fy = bitmap.height * rowIndex / meshHeight.toFloat()
            for (columnIndex in 0..meshWidth) {
                val fx = bitmap.width * columnIndex / meshWidth.toFloat()
                orig[index * 2] = fx
                orig[index * 2 + 1] = fy
                verts[index * 2] = fx
                verts[index * 2 + 1] = fy
                index++
            }
        }

        val normalizedStrength = (strength / 50f).coerceIn(-1f, 1f)

        faces.forEach { face ->
            val contourPoints = face.getContour(FaceContour.FACE)?.points

            val centerX: Float
            val centerY: Float
            val slimRadius: Float

            if (contourPoints != null && contourPoints.size >= 8) {
                var contourCenterX = 0f
                var contourCenterY = 0f
                contourPoints.forEach { point ->
                    contourCenterX += point.x
                    contourCenterY += point.y
                }
                contourCenterX /= contourPoints.size
                contourCenterY /= contourPoints.size

                var maxRadius = 1f
                contourPoints.forEach { point ->
                    val dx = point.x - contourCenterX
                    val dy = point.y - contourCenterY
                    val distance = sqrt(dx * dx + dy * dy)
                    if (distance > maxRadius) {
                        maxRadius = distance
                    }
                }

                centerX = contourCenterX
                centerY = contourCenterY
                slimRadius = maxRadius * 1.3f
            } else {
                val bounds = face.boundingBox
                if (bounds.width() <= 1 || bounds.height() <= 1) {
                    return@forEach
                }
                centerX = bounds.centerX().toFloat()
                centerY = bounds.centerY().toFloat()
                slimRadius = maxOf(bounds.width(), bounds.height()) * 0.65f
            }

            for (vertexIndex in 0 until count) {
                val vx = orig[vertexIndex * 2]
                val vy = orig[vertexIndex * 2 + 1]
                val dx = vx - centerX
                val dy = vy - centerY
                val distance = sqrt(dx * dx + dy * dy)

                if (distance >= slimRadius) {
                    continue
                }

                // 主要作用在脸中下部，避免额头区域过度形变
                val lowerFaceWeight = (((vy - centerY) / slimRadius) + 0.3f).coerceIn(0f, 1f)
                if (lowerFaceWeight <= 0f) {
                    continue
                }

                val radialWeight = 1f - (distance / slimRadius)
                val pull = normalizedStrength * 0.4f * radialWeight * lowerFaceWeight

                verts[vertexIndex * 2] -= dx * pull
            }
        }

        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmapMesh(bitmap, meshWidth, meshHeight, verts, 0, null, 0, null)
        return output
    }

    private fun applyBigEyesFallback(bitmap: Bitmap, strength: Float, faces: List<Face>): Bitmap {
        val safeStrength = strength.coerceIn(0f, 100f)
        if (safeStrength <= 0f || faces.isEmpty()) {
            return bitmap
        }

        val meshWidth = 30
        val meshHeight = 30
        val pointCount = (meshWidth + 1) * (meshHeight + 1)
        val originalVertices = FloatArray(pointCount * 2)
        val warpedVertices = FloatArray(pointCount * 2)

        var pointIndex = 0
        for (rowIndex in 0..meshHeight) {
            val y = bitmap.height * rowIndex / meshHeight.toFloat()
            for (columnIndex in 0..meshWidth) {
                val x = bitmap.width * columnIndex / meshWidth.toFloat()
                originalVertices[pointIndex * 2] = x
                originalVertices[pointIndex * 2 + 1] = y
                warpedVertices[pointIndex * 2] = x
                warpedVertices[pointIndex * 2 + 1] = y
                pointIndex++
            }
        }

        val normalizedStrength = safeStrength / 100f
        val radiusFactor = 0.14f + normalizedStrength * 0.10f
        val pushFactor = 0.18f + normalizedStrength * 0.30f

        faces.forEach { face ->
            val eyeCenters = listOfNotNull(
                face.getLandmark(FaceLandmark.LEFT_EYE)?.position,
                face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            )
            if (eyeCenters.isEmpty()) {
                return@forEach
            }

            val eyeRadius = face.boundingBox.width().coerceAtLeast(face.boundingBox.height()) * radiusFactor
            if (eyeRadius <= 1f) {
                return@forEach
            }

            for (vertexIndex in 0 until pointCount) {
                val ox = originalVertices[vertexIndex * 2]
                val oy = originalVertices[vertexIndex * 2 + 1]
                var movedX = ox
                var movedY = oy

                eyeCenters.forEach { eyeCenter ->
                    val dx = ox - eyeCenter.x
                    val dy = oy - eyeCenter.y
                    val distance = sqrt(dx * dx + dy * dy)
                    if (distance < eyeRadius) {
                        val radialWeight = 1f - (distance / eyeRadius)
                        val push = pushFactor * radialWeight * radialWeight
                        movedX += dx * push
                        movedY += dy * push
                    }
                }

                warpedVertices[vertexIndex * 2] = movedX.coerceIn(0f, bitmap.width.toFloat())
                warpedVertices[vertexIndex * 2 + 1] = movedY.coerceIn(0f, bitmap.height.toFloat())
            }
        }

        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmapMesh(bitmap, meshWidth, meshHeight, warpedVertices, 0, null, 0, null)
        return output
    }

    override fun processPhoto(
        source: Bitmap,
        filter: FilterType,
        beauty: BeautySettings,
        faces: List<Face>,
        lensFacing: Int
    ): Bitmap {
        // [DEBUG] 记录传入的参数
        Logger.d(TAG, "processPhoto called: enabled=${beauty.enabled}, smoothing=${beauty.smoothing}, whitening=${beauty.whitening}, slimFace=${beauty.slimFace}, bigEyes=${beauty.bigEyes}, faces=${faces.size}")

        // [线程安全修复] 移除嵌套 newSingleThreadExecutor().submit { }.get() 阻塞模式
        // 由调用方（takePhoto）保证在后台线程执行，避免多层线程嵌套导致死锁

        // [关键修复] 在拍照 Bitmap 上重新检测人脸，确保坐标与照片完全匹配
        // 预览缓存的 faces/landmarks106 基于预览帧，可能与拍照帧的裁剪区域不同
        var photoFaceData: FaceData? = null
        val detector = faceDetectorManager
        // [修复] 人脸重检测不再限制于 slimFace/bigEyes，因为 Shader 中磨皮/美白/唇色/腮红
        // 全都依赖 uHasFace uniform。必须确保拍照路径始终有有效的人脸数据。
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

                    // [Day1 线程隔离] 拍照后处理直接在 CameraHandlerThread 同步执行
                    // 不再切到协程/其他线程，避免线程切换导致 EGL 上下文丢失或 Surface 竞争
                    try {
                        val needMakeup = beauty.lipColor > 0f || beauty.blush > 0f || beauty.eyebrow > 0f
                        val useCachedFaces = cachedFaces.isNotEmpty() && !needMakeup

                        val finalBitmap = if (useCachedFaces) {
                            Logger.d(TAG, "Using cached faces from preview: ${cachedFaces.size} faces")
                            processPhoto(rotatedBitmap, filter, beauty, cachedFaces, lensFacing)
                        } else {
                            if (cachedFaces.isNotEmpty()) {
                                Logger.d(TAG, "Makeup required, performing detection on captured photo")
                            } else {
                                Logger.d(TAG, "No cached faces, processing photo directly with FaceDetectorManager")
                            }
                            processPhoto(rotatedBitmap, filter, beauty, emptyList(), lensFacing)
                        }

                        val hasFace = cachedFaces.isNotEmpty() || useCachedFaces
                        val faceId = if (cachedFaces.isNotEmpty()) "person_${cachedFaces.size}" else null

                        // 回主线程保存（MediaStore 操作需要主线程 Handler）
                        Handler(Looper.getMainLooper()).post {
                            saveBitmapToMediaStore(
                                context, finalBitmap, name, viewModel, hasFace, faceId, mode
                            )
                            // [Day2 状态机] 通知拍照完成（成功）
                            onPhotoFinished(true)
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Photo processing failed: ${e.message}", e)
                        Handler(Looper.getMainLooper()).post {
                            saveBitmapToMediaStore(
                                context, rotatedBitmap, name, viewModel, false, null, mode
                            )
                            // [Day2 状态机] 通知拍照完成（失败但保存原图）
                            onPhotoFinished(false)
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

    private fun applySmoothing(source: Bitmap, intensity: Float) : Bitmap {
        val width = source.width
        val height = source.height
        val res = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val blurred = Bitmap.createScaledBitmap(
            source, (width / 4).coerceAtLeast(1), (height / 4).coerceAtLeast(1), true
        )
        val finalBlur = Bitmap.createScaledBitmap(blurred, width, height, true)
        val canvas = Canvas(res)
        canvas.drawBitmap(source, 0f, 0f, null)
        val paint = Paint().apply {
            alpha = (intensity * 180).toInt()
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        }
        canvas.drawBitmap(finalBlur, 0f, 0f, paint)
        return res
    }

    private fun applyMeshWarp(source: Bitmap, faces: List<Face>, beauty: BeautySettings) : Bitmap {
        val width = source.width
        val height = source.height
        val meshWidth = 20
        val meshHeight = 20
        val count = (meshWidth + 1) * (meshHeight + 1)
        val verts = FloatArray(count * 2)
        val orig = FloatArray(count * 2)

        var index = 0
        for (y in 0..meshHeight) {
            val fy = height * y / meshHeight.toFloat()
            for (x in 0..meshWidth) {
                val fx = width * x / meshWidth.toFloat()
                orig[index * 2 + 0] = fx
                orig[index * 2 + 1] = fy
                verts[index * 2 + 0] = fx
                verts[index * 2 + 1] = fy
                index++
            }
        }

        faces.forEach { face ->
            val bounds = face.boundingBox
            val centerX = bounds.centerX().toFloat()
            val chinY = (bounds.bottom - bounds.height() * 0.15f).toFloat()
            val slimRadius = bounds.width() * 0.75f
            val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
            val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            val eyeRadius = bounds.width() * 0.2f

            for (i in 0 until count) {
                val vx = orig[i * 2 + 0]
                val vy = orig[i * 2 + 1]
                if (beauty.slimFace > 0f) {
                    val dx = vx - centerX
                    val dy = vy - chinY
                    val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (dist < slimRadius) {
                        val pull = beauty.slimFace * 0.25f * (1.0f - dist / slimRadius)
                        verts[i * 2 + 0] -= dx * pull
                    }
                }
                if (beauty.bigEyes > 0f) {
                    listOfNotNull(leftEye, rightEye).forEach { eye ->
                        val dx = vx - eye.x
                        val dy = vy - eye.y
                        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                        if (dist < eyeRadius) {
                            val push = beauty.bigEyes * 0.4f * (1.0f - dist / eyeRadius)
                            verts[i * 2 + 0] += dx * push
                            verts[i * 2 + 1] += dy * push
                        }
                    }
                }
            }
        }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmapMesh(source, meshWidth, meshHeight, verts, 0, null, 0, null)
        return output
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

    private fun saveBitmapToMediaStore(
        context: Context,
        bitmap: Bitmap,
        name: String,
        viewModel: MediaViewModel,
        hasFace: Boolean,
        faceId: String?,
        mode: MediaType
    ) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicMe")
            }
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        )
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
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
        }
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

    // [关键修复] 从 FaceDetectionCache 获取预览时缓存的 106 点数据
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
