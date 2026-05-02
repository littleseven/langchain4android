package com.picme.features.gallery.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.scale
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.picme.R
import com.picme.core.common.Logger
import com.picme.features.camera.facedetect.InsightFace2D106Detector
import com.picme.features.camera.facedetect.FaceDetectorManager
import com.picme.features.camera.facedetect.adapter.InsightFaceAdapter
import com.pixpark.gpupixel.FaceDetector
import com.pixpark.gpupixel.GPUPixel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.max

private const val TAG = "PicMe:GalleryLandmark"

private class LandmarkDetectionSnapshot(
    val mediaPipe468Points: List<Pair<Float, Float>>?,
    val bigBeauty106Points: FloatArray?,
    val insightFace106Points: FloatArray?,
    val gpupixel106Points: FloatArray?,
    val mpDetectTime: Float,
    val insightDetectTime: Float,
    val gpDetectTime: Float
)

// 与 FaceMakeupPass 中的腮红三角网格保持一致，便于对齐静态图左右脸区域。
private val BLUSH_TRIANGLE_INDICES = intArrayOf(
    2, 3, 78,
    3, 78, 44,
    3, 4, 44,
    4, 44, 45,
    4, 5, 45,
    5, 45, 46,
    5, 6, 46,
    29, 30, 79,
    79, 29, 44,
    28, 29, 44,
    44, 28, 45,
    27, 28, 45,
    45, 27, 46,
    26, 27, 46
)

class FaceLandmarkDetectionState(
    val imageWidth: Int,
    val imageHeight: Int,
    val mediaPipe468Points: List<Pair<Float, Float>>?,
    val bigBeauty106Points: FloatArray?,
    val insightFace106Points: FloatArray?,
    val gpupixel106Points: FloatArray?,
    val mpDetectTime: Float,
    val insightDetectTime: Float,
    val gpDetectTime: Float,
    val isLoading: Boolean,
    val errorMessage: String?
)

@Composable
fun rememberFaceLandmarkDetection(
    imageUri: String,
    enabled: Boolean
): FaceLandmarkDetectionState {
    val context = LocalContext.current
    var imageWidth by remember(imageUri) { mutableIntStateOf(0) }
    var imageHeight by remember(imageUri) { mutableIntStateOf(0) }
    var mediaPipe468Points by remember(imageUri) { mutableStateOf<List<Pair<Float, Float>>?>(null) }
    var bigBeauty106Points by remember(imageUri) { mutableStateOf<FloatArray?>(null) }
    var insightFace106Points by remember(imageUri) { mutableStateOf<FloatArray?>(null) }
    var gpupixel106Points by remember(imageUri) { mutableStateOf<FloatArray?>(null) }
    var isLoading by remember(imageUri) { mutableStateOf(false) }
    var errorMessage by remember(imageUri) { mutableStateOf<String?>(null) }
    var mpDetectTime by remember(imageUri) { mutableFloatStateOf(0f) }
    var insightDetectTime by remember(imageUri) { mutableFloatStateOf(0f) }
    var gpDetectTime by remember(imageUri) { mutableFloatStateOf(0f) }
    var detectionRequestId by remember { mutableIntStateOf(0) }

    var mediaPipeLandmarker by remember { mutableStateOf<FaceLandmarker?>(null) }
    var insightFaceDetector by remember { mutableStateOf<InsightFace2D106Detector?>(null) }
    var gpuPixelDetector by remember { mutableStateOf<FaceDetector?>(null) }

    DisposableEffect(Unit) {
        GPUPixel.Init(context)
        gpuPixelDetector = FaceDetector.Create()

        mediaPipeLandmarker = runCatching {
            createFaceLandmarker(context, Delegate.GPU)
        }.getOrElse { gpuError ->
            Logger.e(TAG, "Failed to init FaceLandmarker with GPU, fallback to CPU", gpuError)
            runCatching {
                createFaceLandmarker(context, Delegate.CPU)
            }.getOrElse { cpuError ->
                Logger.e(TAG, "Failed to init FaceLandmarker", cpuError)
                null
            }
        }

        insightFaceDetector = runCatching {
            InsightFace2D106Detector(context)
        }.getOrElse { error ->
            Logger.e(TAG, "Failed to init InsightFace detector", error)
            null
        }

        onDispose {
            mediaPipeLandmarker?.close()
            insightFaceDetector?.release()
            gpuPixelDetector?.destroy()
        }
    }

    LaunchedEffect(imageUri, enabled, mediaPipeLandmarker, insightFaceDetector, gpuPixelDetector) {
        detectionRequestId += 1
        val requestId = detectionRequestId
        if (!enabled) {
            isLoading = false
            return@LaunchedEffect
        }

        isLoading = true
        errorMessage = null
        mediaPipe468Points = null
        bigBeauty106Points = null
        insightFace106Points = null
        gpupixel106Points = null
        mpDetectTime = 0f
        insightDetectTime = 0f
        gpDetectTime = 0f

        try {
            val bitmap = withContext(Dispatchers.IO) {
                decodeSampledBitmapFromUri(context, imageUri)
            } ?: throw IllegalStateException(context.getString(R.string.load_failed))

            imageWidth = bitmap.width
            imageHeight = bitmap.height

            val snapshot = try {
                withContext(Dispatchers.Default) {
                    var detectedMediaPipe468: List<Pair<Float, Float>>? = null
                    var detectedBigBeauty106: FloatArray? = null
                    var detectedInsight106: FloatArray? = null
                    var detectedGpuPixel106: FloatArray? = null
                    var detectedMpTime = 0f
                    var detectedInsightTime = 0f
                    var detectedGpTime = 0f

                    mediaPipeLandmarker?.let { landmarker ->
                        val mpStart = System.currentTimeMillis()
                        val mpResult = detectMediaPipe468(bitmap, landmarker)
                        detectedMpTime = (System.currentTimeMillis() - mpStart).toFloat()
                        detectedMediaPipe468 = mpResult?.landmarks
                        detectedBigBeauty106 = mpResult?.points106
                    }

                    insightFaceDetector?.let { detector ->
                        val insightStart = System.currentTimeMillis()
                        detectedInsight106 = detectInsightFace106(bitmap, detector)
                        detectedInsightTime = (System.currentTimeMillis() - insightStart).toFloat()
                    }

                    gpuPixelDetector?.let { detector ->
                        val gpStart = System.currentTimeMillis()
                        detectedGpuPixel106 = detectGpuPixel106(bitmap, detector)
                        detectedGpTime = (System.currentTimeMillis() - gpStart).toFloat()
                    }

                    LandmarkDetectionSnapshot(
                        mediaPipe468Points = detectedMediaPipe468,
                        bigBeauty106Points = detectedBigBeauty106,
                        insightFace106Points = detectedInsight106,
                        gpupixel106Points = detectedGpuPixel106,
                        mpDetectTime = detectedMpTime,
                        insightDetectTime = detectedInsightTime,
                        gpDetectTime = detectedGpTime
                    )
                }
            } finally {
                bitmap.recycle()
            }

            if (requestId != detectionRequestId) {
                return@LaunchedEffect
            }

            mediaPipe468Points = snapshot.mediaPipe468Points
            bigBeauty106Points = snapshot.bigBeauty106Points
            insightFace106Points = snapshot.insightFace106Points
            gpupixel106Points = snapshot.gpupixel106Points
            mpDetectTime = snapshot.mpDetectTime
            insightDetectTime = snapshot.insightDetectTime
            gpDetectTime = snapshot.gpDetectTime

            if (
                snapshot.mediaPipe468Points == null &&
                snapshot.insightFace106Points == null &&
                snapshot.gpupixel106Points == null
            ) {
                errorMessage = context.getString(R.string.landmark_no_face_detected)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (requestId == detectionRequestId) {
                errorMessage = error.message ?: context.getString(R.string.load_failed)
            }
            Logger.e(TAG, "Landmark detection failed", error)
        } finally {
            if (requestId == detectionRequestId) {
                isLoading = false
            }
        }
    }

    return FaceLandmarkDetectionState(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        mediaPipe468Points = mediaPipe468Points,
        bigBeauty106Points = bigBeauty106Points,
        insightFace106Points = insightFace106Points,
        gpupixel106Points = gpupixel106Points,
        mpDetectTime = mpDetectTime,
        insightDetectTime = insightDetectTime,
        gpDetectTime = gpDetectTime,
        isLoading = isLoading,
        errorMessage = errorMessage
    )
}

@Composable
fun FaceLandmarkCanvasOverlay(
    state: FaceLandmarkDetectionState,
    show468Points: Boolean,
    showBigBeauty106: Boolean,
    showInsightFace106: Boolean,
    showGpuPixel106: Boolean,
    modifier: Modifier = Modifier
) {
    if (state.imageWidth <= 0 || state.imageHeight <= 0) {
        return
    }

    val drawParams = remember(state.imageWidth, state.imageHeight) {
        object {
            val imageAspect = state.imageWidth.toFloat() / state.imageHeight.toFloat()
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val canvasAspect = canvasWidth / canvasHeight
        val imageAspect = drawParams.imageAspect

        val drawWidth: Float
        val drawHeight: Float
        val drawLeft: Float
        val drawTop: Float

        if (imageAspect > canvasAspect) {
            drawWidth = canvasWidth
            drawHeight = canvasWidth / imageAspect
            drawLeft = 0f
            drawTop = (canvasHeight - drawHeight) / 2f
        } else {
            drawHeight = canvasHeight
            drawWidth = canvasHeight * imageAspect
            drawLeft = (canvasWidth - drawWidth) / 2f
            drawTop = 0f
        }

        fun toCanvasPoint(normX: Float, normY: Float): Offset {
            return Offset(
                x = drawLeft + normX * drawWidth,
                y = drawTop + normY * drawHeight
            )
        }

        fun drawBlushTriangleMesh(points106: FloatArray, color: Color) {
            val pointCount = points106.size / 2
            val fillColor = color.copy(alpha = 0.14f)
            val strokeColor = color.copy(alpha = 0.75f)

            for (index in BLUSH_TRIANGLE_INDICES.indices step 3) {
                val first = BLUSH_TRIANGLE_INDICES[index]
                val second = BLUSH_TRIANGLE_INDICES[index + 1]
                val third = BLUSH_TRIANGLE_INDICES[index + 2]
                if (first >= pointCount || second >= pointCount || third >= pointCount) {
                    continue
                }

                val p0 = toCanvasPoint(points106[first * 2], points106[first * 2 + 1])
                val p1 = toCanvasPoint(points106[second * 2], points106[second * 2 + 1])
                val p2 = toCanvasPoint(points106[third * 2], points106[third * 2 + 1])
                val path = Path().apply {
                    moveTo(p0.x, p0.y)
                    lineTo(p1.x, p1.y)
                    lineTo(p2.x, p2.y)
                    close()
                }

                drawPath(path = path, color = fillColor)
                drawPath(path = path, color = strokeColor, style = Stroke(width = 1.5f))
            }
        }

        if (show468Points && state.mediaPipe468Points != null) {
            val pointColor = Color(0xFF00FF00)
            val faceOvalIndices = setOf(10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109)
            val leftEyeIndices = setOf(33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 133, 155, 154, 153, 145, 144)
            val rightEyeIndices = setOf(362, 363, 364, 365, 366, 367, 368, 369, 370, 371, 372, 373, 374, 375, 463, 380, 381, 382)
            val noseIndices = setOf(1, 2, 3, 4, 5, 6, 19, 94, 168, 195, 196, 197, 198, 174, 217, 236, 275, 248, 278, 279, 280, 281, 282, 283, 284, 285, 286, 287, 288, 289, 290, 291, 292, 293, 294, 295, 296, 297, 298, 299, 300, 301, 302, 303, 304, 305, 306, 307, 308, 309, 310, 311, 312, 313, 314, 315, 316, 317, 318, 319, 320, 321, 322, 323, 324, 325, 326, 327, 328, 329, 330, 331, 332, 333, 334, 335, 336, 337, 338, 339, 340, 341, 342, 343, 344, 345, 346, 347, 348, 349, 350, 351, 352, 353, 354, 355, 356, 357, 358, 359, 360)
            val mouthIndices = setOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 375, 321, 78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95, 84, 183)
            val leftEyebrowIndices = setOf(70, 63, 105, 66, 107, 46, 53, 52, 65)
            val rightEyebrowIndices = setOf(336, 296, 334, 293, 300, 295, 282, 283, 276)
            val allFeatureIndices = faceOvalIndices + leftEyeIndices + rightEyeIndices +
                noseIndices + mouthIndices + leftEyebrowIndices + rightEyebrowIndices

            state.mediaPipe468Points.forEachIndexed { index, point ->
                val canvasPoint = toCanvasPoint(point.first, point.second)
                val radius = when {
                    faceOvalIndices.contains(index) -> 5f
                    leftEyeIndices.contains(index) -> 5f
                    rightEyeIndices.contains(index) -> 5f
                    noseIndices.contains(index) -> 5f
                    mouthIndices.contains(index) -> 5f
                    leftEyebrowIndices.contains(index) -> 5f
                    rightEyebrowIndices.contains(index) -> 5f
                    else -> 2f
                }
                drawCircle(color = pointColor, radius = radius, center = canvasPoint)

                if (allFeatureIndices.contains(index)) {
                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint().apply {
                            color = "#00FF00".toColorInt()
                            textSize = 16f
                            textAlign = android.graphics.Paint.Align.CENTER
                            isFakeBoldText = true
                        }
                        canvas.nativeCanvas.drawText(
                            index.toString(),
                            canvasPoint.x,
                            canvasPoint.y - 10f,
                            paint
                        )
                    }
                }
            }
        }

        if (showBigBeauty106 && state.bigBeauty106Points != null) {
            val blueColor = Color(0xFF4488FF)
            drawBlushTriangleMesh(state.bigBeauty106Points, blueColor)
            for (index in 0 until state.bigBeauty106Points.size / 2) {
                val x = state.bigBeauty106Points[index * 2]
                val y = state.bigBeauty106Points[index * 2 + 1]
                val canvasPoint = toCanvasPoint(x, y)
                drawCircle(color = blueColor, radius = 6f, center = canvasPoint)
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        color = "#4488FF".toColorInt()
                        textSize = 18f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    canvas.nativeCanvas.drawText(
                        index.toString(),
                        canvasPoint.x,
                        canvasPoint.y - 8f,
                        paint
                    )
                }
            }
        }

        if (showInsightFace106 && state.insightFace106Points != null) {
            val amberColor = Color(0xFFFFC107)
            drawBlushTriangleMesh(state.insightFace106Points, amberColor)
            for (index in 0 until state.insightFace106Points.size / 2) {
                val x = state.insightFace106Points[index * 2]
                val y = state.insightFace106Points[index * 2 + 1]
                val canvasPoint = toCanvasPoint(x, y)
                drawCircle(color = amberColor, radius = 7f, center = canvasPoint)
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        color = "#FFC107".toColorInt()
                        textSize = 18f
                        textAlign = android.graphics.Paint.Align.CENTER
                        isFakeBoldText = true
                    }
                    canvas.nativeCanvas.drawText(
                        index.toString(),
                        canvasPoint.x,
                        canvasPoint.y - 9f,
                        paint
                    )
                }
            }
        }

        if (showGpuPixel106 && state.gpupixel106Points != null) {
            val redColor = Color(0xFFFF4444)
            drawBlushTriangleMesh(state.gpupixel106Points, redColor)
            for (index in 0 until state.gpupixel106Points.size / 2) {
                val x = state.gpupixel106Points[index * 2]
                val y = state.gpupixel106Points[index * 2 + 1]
                val canvasPoint = toCanvasPoint(x, y)
                drawCircle(color = redColor, radius = 8f, center = canvasPoint)
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        color = "#FF4444".toColorInt()
                        textSize = 18f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    canvas.nativeCanvas.drawText(
                        index.toString(),
                        canvasPoint.x,
                        canvasPoint.y - 10f,
                        paint
                    )
                }
            }
        }
    }
}

@Composable
fun FaceLandmarkControlBar(
    state: FaceLandmarkDetectionState,
    show468Points: Boolean,
    showBigBeauty106: Boolean,
    showInsightFace106: Boolean,
    showGpuPixel106: Boolean,
    onToggle468Points: () -> Unit,
    onToggleBigBeauty106: () -> Unit,
    onToggleInsightFace106: () -> Unit,
    onToggleGpuPixel106: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.58f))
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LandmarkToggle(
                color = Color(0xFF00FF00),
                label = "468",
                subLabel = "${state.mpDetectTime.toInt()}ms",
                enabled = show468Points,
                onClick = onToggle468Points
            )
            LandmarkToggle(
                color = Color(0xFF4488FF),
                label = stringResource(R.string.landmark_big_beauty),
                subLabel = "106",
                enabled = showBigBeauty106,
                onClick = onToggleBigBeauty106
            )
            LandmarkToggle(
                color = Color(0xFFFFC107),
                label = stringResource(R.string.face_detection_engine_mode_insightface),
                subLabel = "${state.insightDetectTime.toInt()}ms",
                enabled = showInsightFace106,
                onClick = onToggleInsightFace106
            )
            LandmarkToggle(
                color = Color(0xFFFF4444),
                label = stringResource(R.string.landmark_compatibility),
                subLabel = "${state.gpDetectTime.toInt()}ms",
                enabled = showGpuPixel106,
                onClick = onToggleGpuPixel106
            )
        }

        when {
            state.isLoading -> {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.landmark_loading),
                        color = Color.White,
                        fontSize = 10.sp
                    )
                }
            }

            state.errorMessage != null -> {
                Text(
                    text = state.errorMessage,
                    color = Color(0xFFFFB4AB),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun LandmarkToggle(
    color: Color,
    label: String,
    subLabel: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    color = if (enabled) color else Color.Gray.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(5.dp)
                )
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = label,
                fontSize = 12.sp,
                color = if (enabled) Color.White else Color.Gray
            )
            Text(
                text = subLabel,
                fontSize = 9.sp,
                color = Color.Gray.copy(alpha = 0.8f)
            )
        }
    }
}

private class MediaPipeResult(
    val landmarks: List<Pair<Float, Float>>,
    val points106: FloatArray
)

private fun createFaceLandmarker(context: Context, delegate: Delegate): FaceLandmarker {
    val baseOptions = BaseOptions.builder()
        .setDelegate(delegate)
        .setModelAssetPath("mediapipe/face_landmarker.task")
        .build()
    val options = FaceLandmarker.FaceLandmarkerOptions.builder()
        .setBaseOptions(baseOptions)
        .setMinFaceDetectionConfidence(0.5f)
        .setMinTrackingConfidence(0.5f)
        .setMinFacePresenceConfidence(0.5f)
        .setNumFaces(1)
        .setOutputFaceBlendshapes(false)
        .setRunningMode(RunningMode.IMAGE)
        .build()
    return FaceLandmarker.createFromOptions(context, options)
}

private fun decodeSampledBitmapFromUri(context: Context, imageUri: String, maxDimension: Int = 2048): Bitmap? {
    val uri = imageUri.toUri()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        BitmapFactory.decodeStream(inputStream, null, bounds)
    }

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
        sampleSize *= 2
    }

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    val decodedBitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
        BitmapFactory.decodeStream(inputStream, null, decodeOptions)
    } ?: return null

    return normalizeBitmapOrientation(context, uri, decodedBitmap)
}

private fun normalizeBitmapOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = context.contentResolver.openInputStream(uri)?.use { inputStream ->
        ExifInterface(inputStream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } ?: ExifInterface.ORIENTATION_NORMAL

    val transform = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
            transform.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_180 -> {
            transform.postRotate(180f)
        }

        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            transform.postScale(1f, -1f)
        }

        ExifInterface.ORIENTATION_TRANSPOSE -> {
            transform.postRotate(90f)
            transform.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_90 -> {
            transform.postRotate(90f)
        }

        ExifInterface.ORIENTATION_TRANSVERSE -> {
            transform.postRotate(270f)
            transform.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_270 -> {
            transform.postRotate(270f)
        }
    }

    if (transform.isIdentity) {
        return bitmap
    }

    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
    }.onSuccess { transformedBitmap ->
        if (transformedBitmap !== bitmap) {
            bitmap.recycle()
        }
    }.getOrElse { error ->
        Logger.w(TAG, "Failed to normalize bitmap orientation, using original", error)
        bitmap
    }
}

private fun detectMediaPipe468(bitmap: Bitmap, landmarker: FaceLandmarker): MediaPipeResult? {
    return try {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detect(mpImage)
        if (result.faceLandmarks().isEmpty()) {
            Log.d(TAG, "No face detected by MediaPipe")
            return null
        }

        val landmarks = result.faceLandmarks()[0]
        val points468 = landmarks.map { landmark -> Pair(landmark.x(), landmark.y()) }
        val points106 = convert468To106ForDebug(landmarks)
        MediaPipeResult(points468, points106)
    } catch (error: Exception) {
        Logger.e(TAG, "MediaPipe detection failed", error)
        null
    }
}

private fun convert468To106ForDebug(
    landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
): FloatArray {
    val result = FloatArray(FaceDetectorManager.POINT_COUNT * 2)

    fun getMpPoint(index: Int): Pair<Float, Float>? {
        if (index >= landmarks.size) return null
        return Pair(landmarks[index].x(), landmarks[index].y())
    }

    fun setPoint(index: Int, point: Pair<Float, Float>?) {
        if (point == null) return
        result[index * 2] = point.first.coerceIn(0f, 1f)
        result[index * 2 + 1] = point.second.coerceIn(0f, 1f)
    }

    val leftContourBasePoints = listOf(127, 234, 93, 132, 58, 172, 136, 150, 149, 176, 148, 152)
        .mapNotNull(::getMpPoint)
    val rightContourBasePoints = listOf(152, 377, 400, 378, 379, 365, 397, 288, 361, 323, 454, 356)
        .mapNotNull(::getMpPoint)

    for (index in 0..16) {
        val t = index.toFloat() / 16f
        val position = t * (leftContourBasePoints.size - 1)
        val baseIndex = position.toInt().coerceIn(0, leftContourBasePoints.size - 2)
        val fraction = position - baseIndex
        val p1 = leftContourBasePoints[baseIndex]
        val p2 = leftContourBasePoints[baseIndex + 1]
        setPoint(
            index,
            Pair(
                p1.first + (p2.first - p1.first) * fraction,
                p1.second + (p2.second - p1.second) * fraction
            )
        )
    }

    for (index in 1..16) {
        val t = index.toFloat() / 16f
        val position = t * (rightContourBasePoints.size - 1)
        val baseIndex = position.toInt().coerceIn(0, rightContourBasePoints.size - 2)
        val fraction = position - baseIndex
        val p1 = rightContourBasePoints[baseIndex]
        val p2 = rightContourBasePoints[baseIndex + 1]
        setPoint(
            16 + index,
            Pair(
                p1.first + (p2.first - p1.first) * fraction,
                p1.second + (p2.second - p1.second) * fraction
            )
        )
    }

    val nonContourMapping = intArrayOf(
        70, 63, 105, 66, 107,
        336, 296, 334, 293, 300,
        168,
        197, 5, 4,
        98, 241, 2, 461, 327,
        226, 30, 56, 133, 26, 110,
        362, 286, 260, 446, 339, 256,
        53, 52, 65, 55,
        285, 295, 282, 283,
        27, 23, 473,
        257, 253, 468,
        193, 417,
        198, 420, 49, 279,
        61, 40, 37, 0, 267, 270, 291, 321, 314, 17, 84, 91,
        78, 81, 13, 311, 308, 178, 14, 402,
        473, 468
    )

    for (index in 0 until FaceDetectorManager.NON_CONTOUR_POINT_COUNT) {
        val mpIndex = nonContourMapping[index]
        if (mpIndex < landmarks.size) {
            val landmark = landmarks[mpIndex]
            result[(33 + index) * 2] = landmark.x().coerceIn(0f, 1f)
            result[(33 + index) * 2 + 1] = landmark.y().coerceIn(0f, 1f)
        }
    }

    return result
}

private fun detectInsightFace106(bitmap: Bitmap, detector: InsightFace2D106Detector): FloatArray? {
    val rawLandmarks = detector.detect(bitmap, androidx.camera.core.CameraSelector.LENS_FACING_BACK)
        ?: return null
    // 将 InsightFace 原始 106 点映射为统一 106 标准
    val adapter = InsightFaceAdapter()
    val result = adapter.adapt(rawLandmarks, androidx.camera.core.CameraSelector.LENS_FACING_BACK)
    return result.getOrNull()
}

private fun detectGpuPixel106(bitmap: Bitmap, detector: FaceDetector): FloatArray? {
    return try {
        val maxSize = 1024
        val scaledBitmap = if (bitmap.width > maxSize || bitmap.height > maxSize) {
            val scale = maxSize.toFloat() / max(bitmap.width, bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Log.d(TAG, "GPUPixel scaling: ${bitmap.width}x${bitmap.height} -> ${newWidth}x${newHeight}")
            bitmap.scale(newWidth, newHeight, true)
        } else {
            bitmap
        }

        val scaledLandmarks = detectGpuPixel106DirectBuffer(scaledBitmap, detector)
        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle()
        }
        if (scaledLandmarks != null) {
            return scaledLandmarks
        }

        if (scaledBitmap !== bitmap) {
            Log.d(TAG, "GPUPixel retrying original bitmap for small-face detection")
            return detectGpuPixel106DirectBuffer(bitmap, detector)
        }

        null
    } catch (error: Exception) {
        Logger.e(TAG, "GPUPixel detection failed", error)
        null
    }
}

private fun detectGpuPixel106DirectBuffer(bitmap: Bitmap, detector: FaceDetector): FloatArray? {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    // 与实时预览链路保持一致：DirectByteBuffer + VIDEO mode 更稳定。
    val rgbaLandmarks = detector.detect(
        buildDirectColorBuffer(pixels, FaceDetector.GPUPIXEL_FRAME_TYPE_RGBA),
        width,
        height,
        width * 4,
        FaceDetector.GPUPIXEL_MODE_FMT_VIDEO,
        FaceDetector.GPUPIXEL_FRAME_TYPE_RGBA
    )
    if (rgbaLandmarks.isNotEmpty()) {
        return rgbaLandmarks
    }

    val bgraLandmarks = detector.detect(
        buildDirectColorBuffer(pixels, FaceDetector.GPUPIXEL_FRAME_TYPE_BGRA),
        width,
        height,
        width * 4,
        FaceDetector.GPUPIXEL_MODE_FMT_VIDEO,
        FaceDetector.GPUPIXEL_FRAME_TYPE_BGRA
    )
    return bgraLandmarks.takeIf { it.isNotEmpty() }
}

private fun buildDirectColorBuffer(pixels: IntArray, frameType: Int): ByteBuffer {
    val buffer = ByteBuffer.allocateDirect(pixels.size * 4)
    for (index in pixels.indices) {
        val pixel = pixels[index]
        when (frameType) {
            FaceDetector.GPUPIXEL_FRAME_TYPE_BGRA -> {
                buffer.put((pixel and 0xFF).toByte())
                buffer.put((pixel shr 8 and 0xFF).toByte())
                buffer.put((pixel shr 16 and 0xFF).toByte())
                buffer.put((pixel shr 24 and 0xFF).toByte())
            }

            else -> {
                buffer.put((pixel shr 16 and 0xFF).toByte())
                buffer.put((pixel shr 8 and 0xFF).toByte())
                buffer.put((pixel and 0xFF).toByte())
                buffer.put((pixel shr 24 and 0xFF).toByte())
            }
        }
    }
    buffer.flip()
    return buffer
}

