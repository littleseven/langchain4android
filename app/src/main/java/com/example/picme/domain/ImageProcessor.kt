package com.example.picme.domain

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.example.picme.data.model.MediaAsset
import com.example.picme.data.model.MediaType
import com.example.picme.ui.model.FilterType
import com.example.picme.ui.viewmodel.MediaViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.text.SimpleDateFormat
import java.util.*

data class BeautySettings(
    val smoothing: Float = 0f,
    val slimFace: Float = 0f,
    val bigEyes: Float = 0f,
    val youth: Float = 0f
)

interface ImageProcessor {
    fun processPhoto(source: Bitmap, filter: FilterType, beauty: BeautySettings, faces: List<Face>): Bitmap
    fun takePhoto(context: Context, imageCapture: ImageCapture, viewModel: MediaViewModel, filter: FilterType, beauty: BeautySettings, lensFacing: Int)
    fun startVideoRecording(context: Context, videoCapture: VideoCapture<Recorder>, viewModel: MediaViewModel, onFinished: () -> Unit): Recording
}

class ImageProcessorImpl : ImageProcessor {
    override fun processPhoto(source: Bitmap, filter: FilterType, beauty: BeautySettings, faces: List<Face>): Bitmap {
        var processed = source.copy(Bitmap.Config.ARGB_8888, true)
        if (beauty.smoothing > 0f) processed = applySmoothing(processed, beauty.smoothing)
        if (faces.isNotEmpty() && (beauty.slimFace > 0f || beauty.bigEyes > 0f)) processed = applyMeshWarp(processed, faces, beauty)
        val output = Bitmap.createBitmap(processed.width, processed.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(android.graphics.ColorMatrix(filter.getColorMatrix().values))
            if (beauty.youth > 0f) {
                val b = beauty.youth * 25f
                val cm = android.graphics.ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, b, 0f, 1f, 0f, 0f, b * 0.8f, 0f, 0f, 1f, 0f, b * 0.5f, 0f, 0f, 0f, 1f, 0f))
                colorFilter = ColorMatrixColorFilter(cm)
            }
        }
        canvas.drawBitmap(processed, 0f, 0f, paint)
        return output
    }

    override fun takePhoto(context: Context, imageCapture: ImageCapture, viewModel: MediaViewModel, filter: FilterType, beauty: BeautySettings, lensFacing: Int) {
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
        imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotationDegrees = image.imageInfo.rotationDegrees
                val originalBitmap = image.toBitmap()
                image.close()

                val matrix = Matrix().apply {
                    postRotate(rotationDegrees.toFloat())
                    if (lensFacing == 1) postScale(-1f, 1f)
                }
                val rotatedBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)

                val faceDetector = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL).build())
                val inputImage = InputImage.fromBitmap(rotatedBitmap, 0)
                faceDetector.process(inputImage)
                    .addOnSuccessListener { faces ->
                        val finalBitmap = processPhoto(rotatedBitmap, filter, beauty, faces)
                        // Simple Mock Face ID Logic: Use face count as a temporary "person group" id
                        val faceId = if (faces.isNotEmpty()) "person_${faces.size}" else null
                        saveBitmapToMediaStore(context, finalBitmap, name, viewModel, faces.isNotEmpty(), faceId)
                    }
                    .addOnFailureListener { saveBitmapToMediaStore(context, rotatedBitmap, name, viewModel, false, null) }
            }
            override fun onError(exc: ImageCaptureException) { Log.e("ImageProcessor", "Photo capture failed", exc) }
        })
    }

    override fun startVideoRecording(context: Context, videoCapture: VideoCapture<Recorder>, viewModel: MediaViewModel, onFinished: () -> Unit): Recording {
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PicMe")
        }
        val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(contentValues).build()
        return videoCapture.output.prepareRecording(context, mediaStoreOutputOptions).withAudioEnabled().start(ContextCompat.getMainExecutor(context)) { recordEvent ->
            if (recordEvent is VideoRecordEvent.Finalize) {
                if (!recordEvent.hasError()) {
                    val asset = MediaAsset(uri = recordEvent.outputResults.outputUri.toString(), type = MediaType.VIDEO, captureDate = System.currentTimeMillis(), fileName = name)
                    viewModel.insertMedia(asset)
                }
                onFinished()
            }
        }
    }

    private fun applySmoothing(source: Bitmap, intensity: Float): Bitmap {
        val width = source.width
        val height = source.height
        val res = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val blurred = Bitmap.createScaledBitmap(source, (width / 4).coerceAtLeast(1), (height / 4).coerceAtLeast(1), true)
        val finalBlur = Bitmap.createScaledBitmap(blurred, width, height, true)
        val canvas = Canvas(res)
        canvas.drawBitmap(source, 0f, 0f, null)
        val paint = Paint().apply { alpha = (intensity * 180).toInt(); xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER) }
        canvas.drawBitmap(finalBlur, 0f, 0f, paint)
        return res
    }

    private fun applyMeshWarp(source: Bitmap, faces: List<Face>, beauty: BeautySettings): Bitmap {
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

    private fun saveBitmapToMediaStore(context: Context, bitmap: Bitmap, name: String, viewModel: MediaViewModel, hasFace: Boolean, faceId: String?) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicMe")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream) }
            val asset = MediaAsset(uri = it.toString(), type = MediaType.PHOTO, captureDate = System.currentTimeMillis(), fileName = name, hasFace = hasFace, faceId = faceId)
            viewModel.insertMedia(asset)
        }
    }
}
