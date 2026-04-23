package com.picme.features.debug

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.picme.core.common.Logger
import com.picme.features.camera.facedetect.MediaPipeFaceDetector
import com.pixpark.gpupixel.FaceDetector
import com.pixpark.gpupixel.GPUPixel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "PicMe:FaceLandmarkDebug"

@Composable
fun FaceLandmarkDebugScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    // 图像加载状态
    var imageBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var imageWidth by remember { mutableIntStateOf(0) }
    var imageHeight by remember { mutableIntStateOf(0) }

    // 检测结果
    var mediaPipe468Points by remember { mutableStateOf<List<Pair<Float, Float>>?>(null) }
    var bigBeauty106Points by remember { mutableStateOf<FloatArray?>(null) }
    var gpupixel106Points by remember { mutableStateOf<FloatArray?>(null) }

    // 开关状态 - 使用 remember 确保状态在重组时保持
    var show468Points by remember { mutableStateOf(true) }
    var showBigBeauty106 by remember { mutableStateOf(true) }
    var showGpuPixel106 by remember { mutableStateOf(true) }

    // 加载状态
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 检测耗时
    var mpDetectTime by remember { mutableFloatStateOf(0f) }
    var gpDetectTime by remember { mutableFloatStateOf(0f) }

    // 初始化检测器（GPUPixel 必须先 Init 才能创建 FaceDetector）
    var mediaPipeLandmarker by remember { mutableStateOf<FaceLandmarker?>(null) }
    var gpuPixelDetector by remember { mutableStateOf<FaceDetector?>(null) }

    DisposableEffect(Unit) {
        GPUPixel.Init(context)
        gpuPixelDetector = FaceDetector.Create()

        // 独立创建 IMAGE 模式的 FaceLandmarker（调试用，不依赖 MediaPipeFaceDetector）
        try {
            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.GPU)
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
            mediaPipeLandmarker = FaceLandmarker.createFromOptions(context, options)
            Logger.d(TAG, "MediaPipe FaceLandmarker (IMAGE mode) initialized for debug")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to init FaceLandmarker with GPU, fallback to CPU", e)
            try {
                val baseOptions = BaseOptions.builder()
                    .setDelegate(Delegate.CPU)
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
                mediaPipeLandmarker = FaceLandmarker.createFromOptions(context, options)
                Logger.d(TAG, "MediaPipe FaceLandmarker (IMAGE mode, CPU) initialized for debug")
            } catch (e2: Exception) {
                Logger.e(TAG, "Failed to init FaceLandmarker", e2)
            }
        }

        onDispose {
            mediaPipeLandmarker?.close()
            gpuPixelDetector?.destroy()
        }
    }

    // 当前图片 URI（null 表示使用默认 assets 图片）
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }

    // 相册选择器
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedImageUri = it }
    }

    // 加载图片并检测
    LaunchedEffect(selectedImageUri) {
        Log.d(TAG, "LaunchedEffect triggered, uri=$selectedImageUri")
        isLoading = true
        errorMessage = null
        mediaPipe468Points = null
        bigBeauty106Points = null
        gpupixel106Points = null
        try {
            val bitmap = if (selectedImageUri != null) {
                // 从相册加载
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(selectedImageUri!!)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    } ?: throw IllegalStateException("无法打开图片")
                }
            } else {
                // 从 assets 加载默认图片
                withContext(Dispatchers.IO) {
                    val inputStream = context.assets.open("img.png")
                    val bmp = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    bmp
                }
            }

            if (bitmap == null) {
                errorMessage = "图片解码失败"
                isLoading = false
                return@LaunchedEffect
            }

            imageWidth = bitmap.width
            imageHeight = bitmap.height
            imageBitmap = bitmap.asImageBitmap()

            // 执行检测
            withContext(Dispatchers.IO) {
                Log.d(TAG, "Starting detection on ${bitmap.width}x${bitmap.height} bitmap")

                // 1. MediaPipe 468 点检测
                val landmarker = mediaPipeLandmarker
                Log.d(TAG, "MediaPipe landmarker is ${if (landmarker != null) "initialized" else "NULL"}")
                if (landmarker != null) {
                    val mpStart = System.currentTimeMillis()
                    val mpResult = detectMediaPipe468(bitmap, landmarker)
                    mpDetectTime = (System.currentTimeMillis() - mpStart).toFloat()

                    mpResult?.let { result ->
                        mediaPipe468Points = result.landmarks
                        bigBeauty106Points = result.points106
                    }
                } else {
                    Logger.e(TAG, "MediaPipe FaceLandmarker not initialized")
                }

                // 2. GPUPixel 106 点检测
                val gpDetector = gpuPixelDetector
                Log.d(TAG, "GPUPixel detector is ${if (gpDetector != null) "initialized" else "NULL"}")
                if (gpDetector != null) {
                    val gpStart = System.currentTimeMillis()
                    gpupixel106Points = detectGpuPixel106(bitmap, gpDetector)
                    gpDetectTime = (System.currentTimeMillis() - gpStart).toFloat()
                    Log.d(
                        TAG,
                        "GPUPixel result: ${gpupixel106Points?.size?.div(2) ?: 0} points, ${gpDetectTime.toInt()}ms"
                    )
                } else {
                    Logger.e(TAG, "GPUPixel detector not initialized")
                }
            }

            Logger.d(
                TAG,
                "Detection complete - MediaPipe: ${mpDetectTime}ms, GPUPixel: ${gpDetectTime}ms"
            )
        } catch (e: Exception) {
            errorMessage = "加载或检测失败: ${e.message}"
            Logger.e(TAG, "Debug screen error", e)
        } finally {
            isLoading = false
        }
    }

    // 开关状态变化时记录日志
    LaunchedEffect(showGpuPixel106) {
        Log.d(TAG, "GPUPixel switch toggled: $showGpuPixel106, points=${gpupixel106Points?.size?.div(2) ?: 0}")
    }
    LaunchedEffect(show468Points) {
        Log.d(TAG, "468 switch toggled: $show468Points, points=${mediaPipe468Points?.size ?: 0}")
    }
    LaunchedEffect(showBigBeauty106) {
        Log.d(TAG, "BigBeauty switch toggled: $showBigBeauty106, points=${bigBeauty106Points?.size?.div(2) ?: 0}")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 全屏图片显示
        AsyncImage(
            model = selectedImageUri ?: ImageRequest.Builder(context)
                .data("file:///android_asset/img.png")
                .build(),
            contentDescription = "测试图片",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        // 关键点绘制层 - 使用 key 确保状态变化触发重绘
        DebugLandmarkCanvas(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            mediaPipe468Points = mediaPipe468Points,
            bigBeauty106Points = bigBeauty106Points,
            gpupixel106Points = gpupixel106Points,
            show468Points = show468Points,
            showBigBeauty106 = showBigBeauty106,
            showGpuPixel106 = showGpuPixel106
        )

        // 顶部按钮行：返回(左侧) + 相册(右侧)
        Box(modifier = Modifier.fillMaxSize()) {
            // 返回按钮 - 左上，增加顶部间距避免状态栏重叠
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 40.dp)
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }

            // 相册按钮 - 右上，增加顶部间距避免状态栏重叠
            IconButton(
                onClick = { pickImageLauncher.launch("image/*") },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 8.dp, top = 40.dp)
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            ) {
                Icon(
                    Icons.Filled.PhotoLibrary,
                    contentDescription = "从相册选择",
                    tint = Color.White
                )
            }
        }

        // 加载中
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }

        // 错误提示
        errorMessage?.let { error ->
            Text(
                text = error,
                color = Color.Red,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(16.dp)
            )
        }

        // 底部简化控制面板：彩色圆点 + 文字标签
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DebugToggle(
                    color = Color(0xFF00FF00),
                    label = "468",
                    subLabel = "${mpDetectTime.toInt()}ms",
                    enabled = show468Points,
                    onClick = { show468Points = !show468Points }
                )
                DebugToggle(
                    color = Color(0xFF4488FF),
                    label = "大美丽",
                    subLabel = "106",
                    enabled = showBigBeauty106,
                    onClick = { showBigBeauty106 = !showBigBeauty106 }
                )
                DebugToggle(
                    color = Color(0xFFFF4444),
                    label = "GPUPixel",
                    subLabel = "${gpDetectTime.toInt()}ms",
                    enabled = showGpuPixel106,
                    onClick = { showGpuPixel106 = !showGpuPixel106 }
                )
            }

            // GPUPixel 检测失败提示
            if (showGpuPixel106 && gpupixel106Points == null && !isLoading) {
                Text(
                    text = "GPUPixel 未检测到人脸",
                    color = Color(0xFFFF8888),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun DebugToggle(
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
        // 彩色指示圆点
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    if (enabled) color else Color.Gray.copy(alpha = 0.4f),
                    RoundedCornerShape(5.dp)
                )
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(label, fontSize = 12.sp, color = if (enabled) Color.White else Color.Gray)
            Text(subLabel, fontSize = 9.sp, color = Color.Gray.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun DebugLandmarkCanvas(
    imageWidth: Int,
    imageHeight: Int,
    mediaPipe468Points: List<Pair<Float, Float>>?,
    bigBeauty106Points: FloatArray?,
    gpupixel106Points: FloatArray?,
    show468Points: Boolean,
    showBigBeauty106: Boolean,
    showGpuPixel106: Boolean
) {
    // 使用 remember 缓存坐标转换参数，避免每次重组重新计算
    val drawParams = remember(imageWidth, imageHeight) {
        if (imageWidth <= 0 || imageHeight <= 0) return@remember null
        val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()
        // 返回 lambda 用于坐标转换，实际参数在 Canvas drawScope 中获取
        object {
            val imgAspect = imageAspect
        }
    }

    if (drawParams == null) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val imageAspect = drawParams.imgAspect
        val canvasAspect = canvasWidth / canvasHeight

        // 计算图片在 Canvas 中的实际显示区域（ContentScale.Fit 模式）
        val drawWidth: Float
        val drawHeight: Float
        val drawLeft: Float
        val drawTop: Float

        if (imageAspect > canvasAspect) {
            // 图片更宽，以宽度为准，高度居中
            drawWidth = canvasWidth
            drawHeight = canvasWidth / imageAspect
            drawLeft = 0f
            drawTop = (canvasHeight - drawHeight) / 2
        } else {
            // 图片更高，以高度为准，宽度居中
            drawHeight = canvasHeight
            drawWidth = canvasHeight * imageAspect
            drawLeft = (canvasWidth - drawWidth) / 2
            drawTop = 0f
        }

        // 转换归一化坐标到 Canvas 坐标
        fun toCanvasPoint(normX: Float, normY: Float): Offset {
            return Offset(
                x = drawLeft + normX * drawWidth,
                y = drawTop + normY * drawHeight
            )
        }

        // 绘制 MediaPipe 468 点
        if (show468Points && mediaPipe468Points != null) {
            val pointColor = Color(0xFF00FF00)
            // 五官轮廓索引定义（用于标注显示）
            val faceOvalIndices = setOf(10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109)
            val leftEyeIndices = setOf(33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 133, 155, 154, 153, 145, 144)
            val rightEyeIndices = setOf(362, 363, 364, 365, 366, 367, 368, 369, 370, 371, 372, 373, 374, 375, 463, 380, 381, 382)
            val noseIndices = setOf(1, 2, 3, 4, 5, 6, 19, 94, 168, 195, 196, 197, 198, 174, 217, 236, 275, 248, 278, 279, 280, 281, 282, 283, 284, 285, 286, 287, 288, 289, 290, 291, 292, 293, 294, 295, 296, 297, 298, 299, 300, 301, 302, 303, 304, 305, 306, 307, 308, 309, 310, 311, 312, 313, 314, 315, 316, 317, 318, 319, 320, 321, 322, 323, 324, 325, 326, 327, 328, 329, 330, 331, 332, 333, 334, 335, 336, 337, 338, 339, 340, 341, 342, 343, 344, 345, 346, 347, 348, 349, 350, 351, 352, 353, 354, 355, 356, 357, 358, 359, 360)
            val mouthIndices = setOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 375, 321, 78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95, 84, 183)
            // 眉毛索引
            val leftEyebrowIndices = setOf(70, 63, 105, 66, 107, 46, 53, 52, 65)
            val rightEyebrowIndices = setOf(336, 296, 334, 293, 300, 295, 282, 283, 276)

            // 所有五官轮廓点集合（用于判断是否显示索引号）
            val allFeatureIndices = faceOvalIndices + leftEyeIndices + rightEyeIndices +
                noseIndices + mouthIndices + leftEyebrowIndices + rightEyebrowIndices

            mediaPipe468Points.forEachIndexed { index, point ->
                val canvasPoint = toCanvasPoint(point.first, point.second)
                // 关键区域点用大一点的圆，其他用小点
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

                // 标注所有五官轮廓点索引号
                if (allFeatureIndices.contains(index)) {
                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#00FF00")
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

        // 绘制大美丽 106 点
        if (showBigBeauty106 && bigBeauty106Points != null) {
            val blueColor = Color(0xFF0000FF)
            for (i in 0 until bigBeauty106Points.size / 2) {
                val x = bigBeauty106Points[i * 2]
                val y = bigBeauty106Points[i * 2 + 1]
                val canvasPoint = toCanvasPoint(x, y)
                drawCircle(color = blueColor, radius = 6f, center = canvasPoint)
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#0000FF")
                        textSize = 18f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    canvas.nativeCanvas.drawText(
                        i.toString(),
                        canvasPoint.x,
                        canvasPoint.y - 8f,
                        paint
                    )
                }
            }
        }

        // 绘制 GPUPixel 106 点
        if (showGpuPixel106 && gpupixel106Points != null) {
            val redColor = Color(0xFFFF0000)
            for (i in 0 until gpupixel106Points.size / 2) {
                val x = gpupixel106Points[i * 2]
                val y = gpupixel106Points[i * 2 + 1]
                val canvasPoint = toCanvasPoint(x, y)
                drawCircle(color = redColor, radius = 8f, center = canvasPoint)
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#FF0000")
                        textSize = 18f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    canvas.nativeCanvas.drawText(
                        i.toString(),
                        canvasPoint.x,
                        canvasPoint.y - 10f,
                        paint
                    )
                }
            }
        }
    }
}

/**
 * MediaPipe 468 点检测结果
 */
private data class MediaPipeResult(
    val landmarks: List<Pair<Float, Float>>,
    val points106: FloatArray
)

/**
 * 使用 MediaPipe 检测图片的 468 点，并转换为 106 点
 */
private fun detectMediaPipe468(
    bitmap: Bitmap,
    landmarker: FaceLandmarker
): MediaPipeResult? {
    return try {
        Logger.d(TAG, "Running MediaPipe detection with IMAGE mode landmarker...")

        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detect(mpImage)

        if (result.faceLandmarks().isEmpty()) {
            Logger.d(TAG, "No face detected by MediaPipe")
            return null
        }

        val landmarks = result.faceLandmarks()[0]
        Logger.d(TAG, "MediaPipe detected ${landmarks.size} landmarks")

        val points468 = landmarks.map { landmark ->
            Pair(landmark.x(), landmark.y())
        }
        val points106 = convert468To106ForDebug(landmarks)

        MediaPipeResult(points468, points106)
    } catch (e: Exception) {
        Logger.e(TAG, "MediaPipe detection failed: ${e.message}", e)
        null
    }
}

/**
 * 将 MediaPipe 468 点转换为 106 点（调试用，不做镜像）
 * 与生产环境 MediaPipeFaceDetector.convert468To106 保持一致的映射逻辑
 */
private fun convert468To106ForDebug(
    landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
): FloatArray {
    val result = FloatArray(MediaPipeFaceDetector.POINT_COUNT * 2)

    fun getMpPoint(index: Int): Pair<Float, Float>? {
        if (index >= landmarks.size) return null
        return Pair(landmarks[index].x(), landmarks[index].y())
    }

    fun midPoint(p1: Pair<Float, Float>?, p2: Pair<Float, Float>?): Pair<Float, Float>? {
        if (p1 == null || p2 == null) return null
        return Pair((p1.first + p2.first) / 2f, (p1.second + p2.second) / 2f)
    }

    fun setPoint(idx: Int, point: Pair<Float, Float>?) {
        if (point == null) return
        result[idx * 2] = point.first.coerceIn(0f, 1f)
        result[idx * 2 + 1] = point.second.coerceIn(0f, 1f)
    }

    // === 轮廓 33 点（0-32）===
    // 规则：M0=127, M16=152, M32=386，沿 FACE_OVAL 路径均匀插值
    // MediaPipe FACE_OVAL 点序：[10,338,297,332,284,251,389,356,454,323,361,288,397,365,379,378,400,377,152,148,176,149,150,136,172,58,132,93,234,127,162,21,54,103,67,109]
    // M0=127 (索引29) → M16=152 (索引18) → M32=386 (需要插值)
    // 注意：这是闭合曲线，需要选择正确的路径段
    
    // M0-M16：从 127 沿 FACE_OVAL 逆时针到 152
    // FACE_OVAL 从 127 到 152 的路径（逆时针）：127→234→93→132→58→172→136→150→149→176→148→152
    val leftContourBasePoints = listOf(127, 234, 93, 132, 58, 172, 136, 150, 149, 176, 148, 152)
        .mapNotNull { idx -> getMpPoint(idx) }
    
    // M16-M32：从 152 沿 FACE_OVAL 逆时针到 356
    // FACE_OVAL 从 152 继续：152→377→400→378→379→365→397→288→361→323→454→356
    val rightContourBasePoints = listOf(152, 377, 400, 378, 379, 365, 397, 288, 361, 323, 454, 356)
        .mapNotNull { idx -> getMpPoint(idx) }
    
    // 沿路径均匀插值生成 33 点
    // M0-M16 (17点)
    for (i in 0..16) {
        val t = i.toFloat() / 16f
        val pos = t * (leftContourBasePoints.size - 1)
        val idx = pos.toInt().coerceIn(0, leftContourBasePoints.size - 2)
        val frac = pos - idx
        
        val p1 = leftContourBasePoints[idx]
        val p2 = leftContourBasePoints[idx + 1]
        val x = p1.first + (p2.first - p1.first) * frac
        val y = p1.second + (p2.second - p1.second) * frac
        setPoint(i, Pair(x, y))
    }
    
    // M16-M32 (17点，M16已设置，所以从M17开始)
    for (i in 1..16) {
        val t = i.toFloat() / 16f
        val pos = t * (rightContourBasePoints.size - 1)
        val idx = pos.toInt().coerceIn(0, rightContourBasePoints.size - 2)
        val frac = pos - idx
        
        val p1 = rightContourBasePoints[idx]
        val p2 = rightContourBasePoints[idx + 1]
        val x = p1.first + (p2.first - p1.first) * frac
        val y = p1.second + (p2.second - p1.second) * frac
        setPoint(16 + i, Pair(x, y))
    }

    // 非轮廓区域 33-105 - 与生产环境 NON_CONTOUR_MAPPING 完全一致
    val nonContourMapping = intArrayOf(
        // === 右眉上部 33-37 (5点) - 画面左侧=实际右脸，从眉头到眉尾 ===
        70, 63, 105, 66, 107,
        // === 左眉上部 38-42 (5点) - 画面右侧=实际左脸，从眉头到眉尾 ===
        336, 296, 334, 293, 300,
        // === 眉心 43 ===
        168,
        // === 鼻梁 44-46 (3点) - 从上到下 ===
        6, 195, 197,
        // === 鼻尖 47-51 (5点) - 从左到右（47=画面左侧，51=画面右侧）===
        327, 51, 4, 48, 326,
        // === 右眼 52-57 (6点) - 画面左侧=实际右脸 ===
        // 眼周8点结构：外角 + 上眼睑3点 + 内角 + 下眼睑内角
        // MediaPipe右眼外轮廓（从外角到内角）：362→363→364→365→366→367→368→369→370→371→463
        //   52=362(外角), 53=363(上眼睑外), 54=367(上眼睑中), 55=370(上眼睑内)
        //   56=463(内角), 57=372(下眼睑内)
        362, 363, 367, 370, 463, 372,
        // === 左眼 58-63 (6点) - 画面右侧=实际左脸 ===
        // MediaPipe左眼外轮廓（从外角到内角）：33→34→35→36→37→38→39→40→41→42→133
        //   58=33(外角), 59=35(上眼睑外), 60=38(上眼睑中), 61=41(上眼睑内)
        //   62=133(内角), 63=43(下眼睑内)
        33, 35, 38, 41, 133, 43,
        // === 右眉下部 64-67 (4点) - 画面左侧，从眉头到眉尾 ===
        46, 53, 52, 65,
        // === 左眉下部 68-71 (4点) - 画面右侧，从眉尾到眉头 ===
        295, 282, 283, 276,
        // === 右眼内/下 72-74 (3点) - 画面左侧 ===
        // 下眼睑3点(不含角)：72=下眼睑中, 73=下眼睑外, 74=瞳孔
        374, 375, 473,
        // === 左眼内/下 75-77 (3点) - 画面右侧 ===
        // 下眼睑3点(不含角)：75=下眼睑中, 76=下眼睑外, 77=瞳孔
        44, 45, 468,
        // === 山根 78-79 (2点) ===
        //   使用鼻梁两侧中部点：116=鼻梁右侧中, 136=鼻梁左侧中
        116, 136,
        // === 鼻孔 80-83 (4点) ===
        327, 358, 2, 326,
        // === 嘴巴外轮廓 84-95 (12点) - 顺时针闭合曲线 ===
        // 基于 VOLCANO_ENGINE_106_POINTS.md + MEDIAPIPE_468_POINTS.md 语义映射：
        //   上唇外轮廓(MediaPipe): 61→185→40→39→37→0→267→269→270→409→291
        //   下唇外轮廓(MediaPipe): 61→146→91→181→84→17→314→405→321→375→291
        //   84=61(左嘴角), 90=291(右嘴角)
        //   85-89=上唇5点(不含嘴角): 40→37→0(唇珠)→267→270
        //     87=0 唇珠, 86/88 关于唇珠对称, 85/89 关于唇珠对称
        //   91-95=下唇5点(不含嘴角): 321→314→17(下唇中心)→84→91
        //     93=17 下唇中心, 92/94 关于中心对称, 91/95 关于中心对称
        //     91/95 距离中心3步(最外), 92/94 距离中心1步(最内)
        // 拓扑顺序：84→85→86→87→88→89→90→91→92→93→94→95→84
        61, 40, 37, 0, 267, 270, 291, 321, 314, 17, 84, 91,
        // === 嘴巴内轮廓 96-103 (8点) ===
        // 基于 MEDIAPIPE_468_POINTS.md + 搜索结果 lipsUpperInner/lipsLowerInner：
        //   上唇内轮廓: 78→191→80→81→82→13→312→311→310→415→308
        //   下唇内轮廓: 78→95→88→178→87→14→317→402→318→324→308
        // 对称映射（以中心点为对称轴，选择距离中心相同的对称点）：
        //   上唇: 97=81(左2), 98=13(中心), 99=311(右2) —— 81/311 关于13对称
        //   下唇: 101=178(右2), 102=14(中心), 103=402(左2) —— 178/402 关于14对称
        78, 81, 13, 311, 308, 178, 14, 402,
        // === 瞳孔 104-105 (2点) ===
        473, 468
    )

    for (i in 0 until MediaPipeFaceDetector.NON_CONTOUR_POINT_COUNT) {
        val mpIndex = nonContourMapping[i]
        if (mpIndex < landmarks.size) {
            val landmark = landmarks[mpIndex]
            result[(33 + i) * 2] = landmark.x().coerceIn(0f, 1f)
            result[(33 + i) * 2 + 1] = landmark.y().coerceIn(0f, 1f)
        }
    }

    return result
}

/**
 * 使用 GPUPixel 检测图片的 106 点
 */
private fun detectGpuPixel106(bitmap: Bitmap, detector: FaceDetector): FloatArray? {
    return try {
        // GPUPixel/mars-face-kit 对大图支持有限，限制最大边长为 1024
        val maxSize = 1024
        val scaledBitmap = if (bitmap.width > maxSize || bitmap.height > maxSize) {
            val scale = maxSize.toFloat() / kotlin.math.max(bitmap.width, bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Log.d(TAG, "GPUPixel scaling: ${bitmap.width}x${bitmap.height} -> ${newWidth}x${newHeight}")
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        val width = scaledBitmap.width
        val height = scaledBitmap.height
        Log.d(TAG, "GPUPixel detecting: ${width}x${height}")

        val pixels = IntArray(width * height)
        scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 尝试 RGBA 格式
        val rgbaData = ByteArray(width * height * 4)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            rgbaData[i * 4] = (pixel shr 16 and 0xFF).toByte()     // R
            rgbaData[i * 4 + 1] = (pixel shr 8 and 0xFF).toByte()  // G
            rgbaData[i * 4 + 2] = (pixel and 0xFF).toByte()        // B
            rgbaData[i * 4 + 3] = (pixel shr 24 and 0xFF).toByte() // A
        }

        var landmarks = detector.detect(
            rgbaData,
            width,
            height,
            width * 4,
            FaceDetector.GPUPIXEL_MODE_FMT_PICTURE,
            FaceDetector.GPUPIXEL_FRAME_TYPE_RGBA
        )

        Log.d(TAG, "GPUPixel RGBA result: ${landmarks.size} floats = ${landmarks.size / 2} points")

        // 如果 RGBA 失败，尝试 BGRA 格式
        if (landmarks.isEmpty()) {
            val bgraData = ByteArray(width * height * 4)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                bgraData[i * 4] = (pixel and 0xFF).toByte()        // B
                bgraData[i * 4 + 1] = (pixel shr 8 and 0xFF).toByte() // G
                bgraData[i * 4 + 2] = (pixel shr 16 and 0xFF).toByte() // R
                bgraData[i * 4 + 3] = (pixel shr 24 and 0xFF).toByte() // A
            }

            landmarks = detector.detect(
                bgraData,
                width,
                height,
                width * 4,
                FaceDetector.GPUPIXEL_MODE_FMT_PICTURE,
                FaceDetector.GPUPIXEL_FRAME_TYPE_BGRA
            )
            Log.d(TAG, "GPUPixel BGRA result: ${landmarks.size} floats = ${landmarks.size / 2} points")
        }

        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle()
        }

        if (landmarks.isEmpty()) {
            Log.d(TAG, "No face detected by GPUPixel (RGBA and BGRA both failed)")
            return null
        }

        Log.d(TAG, "GPUPixel detected ${landmarks.size / 2} points")
        landmarks
    } catch (e: Exception) {
        Logger.e(TAG, "GPUPixel detection failed", e)
        null
    }
}
