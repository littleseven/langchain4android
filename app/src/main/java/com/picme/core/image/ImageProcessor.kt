package com.picme.core.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.picme.core.common.Logger
import com.picme.domain.model.BeautySettings
import com.picme.domain.model.MediaAsset
import com.picme.domain.model.MediaType
import com.picme.features.camera.model.FilterType
import com.picme.features.gallery.MediaViewModel
import java.text.SimpleDateFormat
import java.util.Locale

interface ImageProcessor {
    fun processPhoto(
        source: Bitmap,
        filter: FilterType,
        beauty: BeautySettings,
        faces: List<Face>
    ) : Bitmap

    fun takePhoto(
        context: Context,
        imageCapture: ImageCapture,
        viewModel: MediaViewModel,
        filter: FilterType,
        beauty: BeautySettings,
        lensFacing: Int,
        mode: MediaType = MediaType.PHOTO
    )

    fun startVideoRecording(
        context: Context,
        videoCapture: VideoCapture<Recorder>,
        viewModel: MediaViewModel,
        onFinished: () -> Unit
    ) : Recording
}

class ImageProcessorImpl(private val beautyProcessor: BeautyProcessor) : ImageProcessor {
    override fun processPhoto(
        source: Bitmap,
        filter: FilterType,
        beauty: BeautySettings,
        faces: List<Face>
    ) : Bitmap {
        // [DEBUG] 记录传入的参数
        Logger.d("ImageProcessor", "processPhoto called: smoothing=${beauty.smoothing}, whitening=${beauty.whitening}, slimFace=${beauty.slimFace}, bigEyes=${beauty.bigEyes}, faces=${faces.size}")
        
        // 使用协程在后台线程处理
        return java.util.concurrent.Executors.newSingleThreadExecutor().submit<Bitmap> {
            var processed = source.copy(Bitmap.Config.ARGB_8888, true)
            
            Logger.d("ImageProcessor", "Starting beauty processing...")
            
            // 使用 GpuBeautyProcessor 处理所有美颜效果
            kotlinx.coroutines.runBlocking {
                // 面部精修
                if (beauty.smoothing > 0f) {
                    Logger.d("ImageProcessor", "Applying smoothing: ${beauty.smoothing}")
                    processed = beautyProcessor.applySmoothing(processed, beauty.smoothing)
                }
                if (beauty.whitening > 0f) {
                    Logger.d("ImageProcessor", "Applying whitening: ${beauty.whitening}")
                    processed = beautyProcessor.applyWhitening(processed, beauty.whitening)
                }
                if (faces.isNotEmpty()) {
                    Logger.d("ImageProcessor", "Processing face beautification for ${faces.size} faces")
                    if (beauty.slimFace != 0f) {
                        Logger.d("ImageProcessor", "Applying slim face: ${beauty.slimFace}")
                        processed = beautyProcessor.applySlimFace(processed, beauty.slimFace, faces)
                    }
                    if (beauty.bigEyes > 0f) {
                        Logger.d("ImageProcessor", "Applying big eyes: ${beauty.bigEyes}")
                        processed = beautyProcessor.applyBigEyes(processed, beauty.bigEyes, faces)
                    }
                    if (beauty.youth > 0f) {
                        Logger.d("ImageProcessor", "Applying youth: ${beauty.youth}")
                        processed = beautyProcessor.applyYouth(processed, beauty.youth)
                    }
                    // 妆容调节
                    if (beauty.lipColor > 0f) {
                        Logger.d("ImageProcessor", "Applying lip color: ${beauty.lipColor}")
                        processed = beautyProcessor.applyLipColor(processed, beauty.lipColor, beauty.lipColorIndex)
                    }
                    if (beauty.blush > 0f) {
                        Logger.d("ImageProcessor", "Applying blush: ${beauty.blush}")
                        processed = beautyProcessor.applyBlush(processed, beauty.blush)
                    }
                    if (beauty.eyebrow > 0f) {
                        Logger.d("ImageProcessor", "Applying eyebrow: ${beauty.eyebrow}")
                        processed = beautyProcessor.applyEyebrow(processed, beauty.eyebrow)
                    }
                } else {
                    Logger.d("ImageProcessor", "No faces detected, skipping face beautification")
                }
                // 身材管理 (需要全身检测，当前仅当有人脸时应用)
                if (faces.isNotEmpty() && (beauty.bodyEnhancement != 0f || beauty.legExtension > 0f)) {
                    if (beauty.bodyEnhancement != 0f) {
                        Logger.d("ImageProcessor", "Applying body enhancement: ${beauty.bodyEnhancement}")
                        processed = beautyProcessor.applyBodyEnhancement(processed, beauty.bodyEnhancement)
                    }
                    if (beauty.legExtension > 0f) {
                        Logger.d("ImageProcessor", "Applying leg extension: ${beauty.legExtension}")
                        processed = beautyProcessor.applyLegExtension(processed, beauty.legExtension)
                    }
                }
            }
            
            Logger.d("ImageProcessor", "Beauty processing completed")
            
            // 应用滤镜
            val output = Bitmap.createBitmap(processed.width, processed.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix(filter.getColorMatrix().values))
                if (beauty.youth > 0f) {
                    val b = beauty.youth * 25f
                    val cm = ColorMatrix(
                        floatArrayOf(
                            1f, 0f, 0f, 0f, b,
                            0f, 1f, 0f, 0f, b * 0.8f,
                            0f, 0f, 1f, 0f, b * 0.5f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                    colorFilter = ColorMatrixColorFilter(cm)
                }
            }
            canvas.drawBitmap(processed, 0f, 0f, paint)
            output
        }.get()
    }

    override fun takePhoto(
        context: Context,
        imageCapture: ImageCapture,
        viewModel: MediaViewModel,
        filter: FilterType,
        beauty: BeautySettings,
        lensFacing: Int,
        mode: MediaType
    ) {
        Logger.d("ImageProcessor", "takePhoto called with filter=$filter, beauty=$beauty, lensFacing=$lensFacing")
        
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    Logger.d("ImageProcessor", "Photo captured successfully, rotation=${image.imageInfo.rotationDegrees}")
                    
                    val rotationDegrees = image.imageInfo.rotationDegrees

                    // [关键] 检查ViewPort的CropRect
                    val cropRect = image.cropRect
                    Logger.d("ImageProcessor", "ImageProxy cropRect: $cropRect, imageSize: ${image.width}x${image.height}")

                    val originalBitmap = image.toBitmap()
                    image.close()

                    // [修复1:1模式] 应用ViewPort的CropRect裁剪
                    val croppedBitmap = if (cropRect.width() != originalBitmap.width || cropRect.height() != originalBitmap.height) {
                        // 有裁剪区域，应用裁剪
                        Logger.d("ImageProcessor", "Applying crop: ${cropRect.width()}x${cropRect.height()}")
                        Bitmap.createBitmap(
                            originalBitmap,
                            cropRect.left,
                            cropRect.top,
                            cropRect.width(),
                            cropRect.height()
                        )
                    } else {
                        // 没有裁剪
                        originalBitmap
                    }

                    val matrix = Matrix().apply {
                        postRotate(rotationDegrees.toFloat())
                        // [FIXED] 前置摄像头需要水平翻转以匹配预览效果
                        // 前置摄像头的预览是镜像的，拍照也需要保持镜像
                        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                            postScale(-1f, 1f)
                        }
                    }
                    val rotatedBitmap = Bitmap.createBitmap(
                        croppedBitmap, 0, 0, croppedBitmap.width, croppedBitmap.height, matrix, true
                    )

                    Logger.d("ImageProcessor", "Final bitmap size: ${rotatedBitmap.width}x${rotatedBitmap.height}")

                    val faceDetector = FaceDetection.getClient(
                        FaceDetectorOptions.Builder()
                            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                            .build()
                    )
                    val inputImage = InputImage.fromBitmap(rotatedBitmap, 0)
                    Logger.d("ImageProcessor", "Starting face detection on bitmap ${rotatedBitmap.width}x${rotatedBitmap.height}")
                    
                    faceDetector.process(inputImage)
                        .addOnSuccessListener { faces ->
                            Logger.d("ImageProcessor", "Face detection success: ${faces.size} faces found")
                            val finalBitmap = processPhoto(rotatedBitmap, filter, beauty, faces)
                            // Simple Mock Face ID Logic: Use face count as a temporary "person group" id
                            val faceId = if (faces.isNotEmpty()) "person_${faces.size}" else null
                            saveBitmapToMediaStore(
                                context, finalBitmap, name, viewModel, faces.isNotEmpty(), faceId, mode
                            )
                        }
                        .addOnFailureListener { e ->
                            Logger.e("ImageProcessor", "Face detection failed: ${e.message}", e)
                            saveBitmapToMediaStore(
                                context, rotatedBitmap, name, viewModel, false, null, mode
                            )
                        }
                }

                override fun onError(exc: ImageCaptureException) {
                    Logger.e("ImageProcessor", "Photo capture failed", exc)
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
