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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
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
import kotlin.math.abs
import kotlin.math.sqrt

interface ImageProcessor {
    fun processPhoto(
        source: Bitmap,
        filter: FilterType,
        beauty: BeautySettings,
        faces: List<Face>,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK
    ) : Bitmap

    fun takePhoto(
        context: Context,
        imageCapture: ImageCapture,
        viewModel: MediaViewModel,
        filter: FilterType,
        beauty: BeautySettings,
        lensFacing: Int,
        mode: MediaType = MediaType.PHOTO,
        cachedFaces: List<Face> = emptyList(),
        gpupixelProvider: com.picme.beauty.gpupixel.GpupixelBeautyPreviewProvider? = null
    )

    fun startVideoRecording(
        context: Context,
        videoCapture: VideoCapture<Recorder>,
        viewModel: MediaViewModel,
        onFinished: () -> Unit
    ) : Recording
}

class ImageProcessorImpl(private val beautyProcessor: BeautyProcessor) : ImageProcessor {

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
            Logger.d("ImageProcessor", "$logPrefix skipped: no face regions")
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
    ) : Bitmap {
        // [DEBUG] 记录传入的参数
        Logger.d("ImageProcessor", "processPhoto called: enabled=${beauty.enabled}, smoothing=${beauty.smoothing}, whitening=${beauty.whitening}, slimFace=${beauty.slimFace}, bigEyes=${beauty.bigEyes}, faces=${faces.size}")

        // 使用协程在后台线程处理
        return java.util.concurrent.Executors.newSingleThreadExecutor().submit<Bitmap> {
            var processed = source.copy(Bitmap.Config.ARGB_8888, true)
            
            // 检查美颜是否启用
            if (beauty.enabled && beauty.hasAnyEffect()) {
                Logger.d("ImageProcessor", "Starting beauty processing...")

                // 使用 BeautyProcessor 处理所有美颜效果
                kotlinx.coroutines.runBlocking {
                // 面部精修
                if (beauty.smoothing > 0f) {
                    Logger.d("ImageProcessor", "Applying smoothing: ${beauty.smoothing}")
                    processed = beautyProcessor.applySmoothing(processed, beauty.smoothing)
                }
                if (beauty.whitening > 0f) {
                    if (faces.isNotEmpty()) {
                        Logger.d("ImageProcessor", "Applying whitening on faces: ${beauty.whitening}, faceCount=${faces.size}")
                        processed = beautyProcessor.applyWhitening(processed, beauty.whitening)
                    } else {
                        Logger.d("ImageProcessor", "Whitening skipped: no face detected")
                    }
                }
                if (faces.isNotEmpty()) {
                    Logger.d("ImageProcessor", "Processing face beautification for ${faces.size} faces")
                    if (beauty.slimFace != 0f) {
                        Logger.d("ImageProcessor", "Applying slim face: ${beauty.slimFace}")
                        val isFrontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT
                        processed = beautyProcessor.applySlimFace(processed, beauty.slimFace, faces, isFrontCamera)
                    }
                    if (beauty.bigEyes > 0f) {
                        Logger.d("ImageProcessor", "Applying big eyes: ${beauty.bigEyes}")
                        processed = beautyProcessor.applyBigEyes(processed, beauty.bigEyes, faces)
                    }
                } else {
                    Logger.d("ImageProcessor", "No faces detected, skipping face beautification")
                }

                // 妆容调节不依赖人脸检测结果，避免检测波动导致唇色/腮红/眉毛完全失效。
                if (beauty.lipColor > 0f) {
                    Logger.d("ImageProcessor", "Applying lip color: ${beauty.lipColor}")
                    processed = beautyProcessor.applyLipColor(processed, beauty.lipColor, beauty.lipColorIndex, faces)
                }
                if (beauty.blush > 0f) {
                    Logger.d(
                        "ImageProcessor",
                        "Applying blush: ${beauty.blush}, family=${beauty.blushColorFamily}"
                    )
                    processed = beautyProcessor.applyBlush(
                        processed,
                        beauty.blush,
                        beauty.blushColorFamily
                    )
                }
                if (beauty.eyebrow > 0f) {
                    Logger.d("ImageProcessor", "Applying eyebrow: ${beauty.eyebrow}")
                    processed = beautyProcessor.applyEyebrow(processed, beauty.eyebrow)
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
            } else {
                Logger.d("ImageProcessor", "Beauty processing skipped (enabled=${beauty.enabled}, hasEffect=${beauty.hasAnyEffect()})")
            }
            
            // 应用滤镜
            val output = Bitmap.createBitmap(processed.width, processed.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(filter.toAndroidColorMatrix())
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
        mode: MediaType,
        cachedFaces: List<Face>,
        gpupixelProvider: com.picme.beauty.gpupixel.GpupixelBeautyPreviewProvider?
    ) {
        Logger.d("ImageProcessor", "takePhoto called with filter=$filter, beauty=$beauty, lensFacing=$lensFacing, cachedFaces=${cachedFaces.size}, gpupixelProvider=${gpupixelProvider != null}")

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

                    // GPUPixel 模式：使用 GPUPixel 滤镜链处理拍照，确保与预览效果一致
                    if (gpupixelProvider != null) {
                        Logger.d("ImageProcessor", "GPUPixel mode: processing photo with GPUPixel filter chain")
                        val finalBitmap = gpupixelProvider.processPhoto(rotatedBitmap)
                        // 色调滤镜在 GPUPixel 中已通过 whiteBalanceFilter 等处理，
                        // 但用户选择的 ColorMatrix 滤镜（如 LEICA_CLASSIC）是 App 层独立实现的，
                        // 如果 GPUPixel 未接入该滤镜，仍需在 Bitmap 上应用。
                        val output = if (filter != FilterType.NONE) {
                            applyColorMatrixFilter(finalBitmap, filter)
                        } else {
                            finalBitmap
                        }
                        saveBitmapToMediaStore(
                            context, output, name, viewModel, cachedFaces.isNotEmpty(), null, mode
                        )
                        return
                    }

                    // [方案 B 变种] 优先使用缓存的人脸检测结果进行美颜（磨皮/美白/瘦脸/大眼）
                    // 但妆容（唇色/腮红/眉毛）需要在拍照后的图片上重新检测人脸，以确保坐标正确
                    if (cachedFaces.isNotEmpty()) {
                        Logger.d("ImageProcessor", "Using cached faces from preview: ${cachedFaces.size} faces")

                        // 检查是否需要妆容处理
                        val needMakeup = beauty.lipColor > 0f || beauty.blush > 0f || beauty.eyebrow > 0f

                        if (!needMakeup) {
                            // 不需要妆容，直接使用缓存的人脸进行美颜处理
                            val finalBitmap = processPhoto(rotatedBitmap, filter, beauty, cachedFaces, lensFacing)
                            val faceId = if (cachedFaces.isNotEmpty()) "person_${cachedFaces.size}" else null
                            saveBitmapToMediaStore(
                                context, finalBitmap, name, viewModel, cachedFaces.isNotEmpty(), faceId, mode
                            )
                            return
                        }

                        // 需要妆容，必须在拍照后的图片上重新检测人脸
                        Logger.d("ImageProcessor", "Makeup required, performing detection on captured photo")
                    }

                    // 没有缓存时，进行实时检测（兜底）
                    Logger.d("ImageProcessor", "No cached faces, performing real-time detection")
                val faceDetector = FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                        .build()
                )
                    val inputImage = InputImage.fromBitmap(rotatedBitmap, 0)
                    Logger.d("ImageProcessor", "Starting face detection on bitmap ${rotatedBitmap.width}x${rotatedBitmap.height}")

                    faceDetector.process(inputImage)
                        .addOnSuccessListener { faces ->
                            Logger.d("ImageProcessor", "Face detection success: ${faces.size} faces found")
                            val finalBitmap = processPhoto(rotatedBitmap, filter, beauty, faces, lensFacing)
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
