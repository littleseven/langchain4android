package com.picme.features.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaActionSound
import android.provider.MediaStore
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.picme.PicMeApplication
import com.picme.R
import com.picme.core.common.PicMeLogger
import com.picme.domain.model.BeautySettings
import com.picme.domain.model.MediaAsset
import com.picme.domain.model.MediaType
import com.picme.features.camera.components.BeautySelector
import com.picme.features.camera.components.CameraBottomControls
import com.picme.features.camera.components.DocumentDetectionOverlay
import com.picme.features.camera.components.CameraLeftControls
import com.picme.features.camera.components.CameraOverlays
import com.picme.features.camera.components.CameraRightControls
import com.picme.features.camera.components.ControlPanel
import com.picme.features.camera.components.FilterSelector
import com.picme.features.camera.components.GridSelector
import com.picme.features.camera.components.ProModeControls
import com.picme.features.camera.components.RatioSelector
import com.picme.features.camera.components.SceneSelector
import com.picme.features.camera.model.FilterType
import com.picme.features.debug.LogOverlay
import com.picme.domain.usecase.OcrUseCase
import com.picme.features.gallery.MediaViewModel
import com.picme.features.gallery.MediaViewModelFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

enum class ScenePreset { NONE, NIGHT, MOON }
enum class GridType { NONE, THIRDS, GOLDEN }
enum class CameraAspectRatio { RATIO_4_3, RATIO_16_9, RATIO_1_1, RATIO_FULL }

object AspectRatio {
    const val RATIO_4_3 = 0
    const val RATIO_16_9 = 1
    const val RATIO_FULL = 2
}

/**
 * [RD] 人脸坐标转换函数 - 重构版（符合最新文档）
 * 
 * 【核心原理】
 * 1. ML Kit 的 InputImage.fromMediaImage() 已自动处理旋转
 * 2. Face.boundingBox 返回的是相对于【旋转后图像】的坐标
 * 3. 归一化时必须使用【旋转后】的宽高，而不是传感器原始宽高
 * 
 * 【数据流】
 * ImageProxy (width=1280, height=720, rotation=90°)
 *     ↓
 * ML Kit → 检测到人脸在旋转后图像上的坐标 (faceX, faceY)
 *     ↓
 * 归一化 → 使用旋转后的宽高（rotation=90° 时，宽=720, 高=1280）
 *     ↓
 * 旋转变换 → 将图像坐标系映射到屏幕坐标系
 *     ↓
 * 镜像处理 → 前置摄像头需要水平翻转
 *     ↓
 * FIT_CENTER 映射 → PreviewView 自动处理
 * 
 * @param faceX 人脸中心 X 坐标（ML Kit 检测值，已考虑旋转）
 * @param faceY 人脸中心 Y 坐标（ML Kit 检测值，已考虑旋转）
 * @param imageProxyWidth ImageProxy 的宽度（传感器物理宽度，未旋转）
 * @param imageProxyHeight ImageProxy 的高度（传感器物理高度，未旋转）
 * @param previewView PreviewView 实例
 * @param rotationDegrees 旋转角度（0/90/180/270）
 * @param lensFacing 摄像头方向
 * @return 屏幕坐标 Offset
 */
private fun transformFaceCoordinate(
    faceX: Float,
    faceY: Float,
    imageProxyWidth: Int,
    imageProxyHeight: Int,
    previewView: PreviewView,
    rotationDegrees: Int,
    lensFacing: Int
): Offset {
    // Step 1: 确定旋转后图像的宽高
    // ML Kit 已经根据 rotationDegrees 旋转了图像
    // Face.boundingBox 是相对于旋转后图像的坐标
    val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
        90, 270 -> Pair(imageProxyHeight, imageProxyWidth)  // 横屏时宽高互换
        else -> Pair(imageProxyWidth, imageProxyHeight)     // 竖屏时保持不变
    }
    
    PicMeLogger.d(
        "PicMe:Camera",
        "Step1 Size: sensor=${imageProxyWidth}x${imageProxyHeight}, rotated=${rotatedWidth}x${rotatedHeight}, rot=$rotationDegrees"
    )
    
    // Step 2: 归一化坐标（使用旋转后的宽高）
    // 这是关键修复：必须使用旋转后的尺寸进行归一化
    val normX = faceX / rotatedWidth
    val normY = faceY / rotatedHeight
    
    PicMeLogger.d(
        "PicMe:Camera",
        "Step2 Norm: face=($faceX,$faceY), rotatedSize=${rotatedWidth}x${rotatedHeight}, norm=($normX,$normY)"
    )
    
    // Step 3: 旋转变换 + 镜像补偿
    // 目的：将【已旋转的图像坐标系】映射到【屏幕显示坐标系】
    val (adjustedX, adjustedY) = when (rotationDegrees) {
        0 -> {
            // 竖屏状态
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                // 前置摄像头：需要水平镜像
                // ML Kit 返回的是传感器坐标（未镜像），PreviewView 显示的是镜像画面
                // 所以需要将坐标镜像一次才能匹配预览
                Pair(1f - normX, normY)
            } else {
                // 后置摄像头：不需要镜像
                Pair(normX, normY)
            }
        }
        90 -> {
            // 传感器顺时针旋转 90°
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                // 后置 90 度：坐标系旋转即可
                Pair(normY, 1f - normX)
            } else {
                // 前置 90 度：先旋转，再镜像
                // 旋转后的 X 轴对应原来的 Y 轴，所以镜像 Y 方向
                Pair(normY, normX)
            }
        }
        180 -> {
            // 倒立状态
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                // 前置 180 度：倒立 + 镜像
                Pair(normX, 1f - normY)
            } else {
                // 后置 180 度：只需倒立
                Pair(1f - normX, 1f - normY)
            }
        }
        270 -> {
            // 传感器逆时针旋转 90°（设备横屏，顶部朝左）
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                // 前置 270 度：左右相反，上下正确
                // 需要翻转 X 轴
                Pair(1f - normX, normY)
            } else {
                // 后置 270 度：只需旋转
                // 旋转公式：(x, y) → (y, 1-x)
                Pair(normY, 1f - normX)
            }
        }
        else -> Pair(normX, normY)
    }
    
    PicMeLogger.d(
        "PicMe:Camera",
        "Step3 Adjust: rot=$rotationDegrees, lens=$lensFacing, adj=($adjustedX,$adjustedY)"
    )
    
    // Step 4: 将归一化坐标转换为 PreviewView 的物理像素坐标
    // 注意：PreviewView 使用 FIT_CENTER，需要考虑 letterbox 效应
    val previewWidth = previewView.width.toFloat()
    val previewHeight = previewView.height.toFloat()
    
    PicMeLogger.d(
        "PicMe:Camera",
        "Step4 Screen: adj=($adjustedX,$adjustedY), previewSize=${previewWidth.toInt()}x${previewHeight.toInt()}"
    )
    
    // TODO: 处理 letterbox 效应 - 暂时先直接相乘
    val screenX = adjustedX * previewWidth
    val screenY = adjustedY * previewHeight
    
    PicMeLogger.d(
        "PicMe:Camera",
        "Transform: face=($faceX, $faceY), rotatedSize=${rotatedWidth}x${rotatedHeight}, " +
            "norm=($normX, $normY), adj=($adjustedX, $adjustedY), " +
            "screen=($screenX, $screenY), rot=$rotationDegrees, lens=$lensFacing"
    )
    
    return Offset(screenX, screenY)
}

/**
 * 四元组数据类，用于返回多个值
 */
private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDebug: () -> Unit,
    viewModel: MediaViewModel = viewModel(
        factory = MediaViewModelFactory(
            LocalContext.current,
            (LocalContext.current.applicationContext as PicMeApplication).repository,
            OcrUseCase()
        )
    )
) {
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )

    if (permissionsState.allPermissionsGranted) {
        CameraContent(
            viewModel = viewModel,
            onNavigateToGallery = onNavigateToGallery,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToDebug = onNavigateToDebug
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                Text(stringResource(R.string.grant_permissions))
            }
        }
    }
}

@SuppressLint("MissingPermission", "UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGetImage::class)
@Composable
fun CameraContent(
    viewModel: MediaViewModel,
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDebug: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PicMeApplication
    val imageProcessor = app.container.imageProcessor
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val shutterSound = remember { MediaActionSound() }

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var captureMode by remember { mutableStateOf(MediaType.PHOTO) }
    var isRecording by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(FilterType.NONE) }
    var beautySettings by remember { mutableStateOf(BeautySettings()) }
    var aspectRatio by remember { mutableIntStateOf(AspectRatio.RATIO_FULL) }

    var showFilterSelector by remember { mutableStateOf(false) }
    var showBeautySelector by remember { mutableStateOf(false) }
    var showRatioSelector by remember { mutableStateOf(false) }
    var showCameraInfo by remember { mutableStateOf(false) }
    var showSceneSelector by remember { mutableStateOf(false) }
    var showGridSelector by remember { mutableStateOf(false) }
    var showLogOverlay by remember { mutableStateOf(false) }

    var currentScene by remember { mutableStateOf(ScenePreset.NONE) }
    var currentGrid by remember { mutableStateOf(GridType.NONE) }
    
    var isStable by remember { mutableStateOf(true) }
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val accelerometer = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }

    DisposableEffect(Unit) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event?.let {
                    val x = it.values[0]
                    val y = it.values[1]
                    val z = it.values[2]
                    val g = sqrt(x * x + y * y + z * z)
                    val diff = abs(g - SensorManager.GRAVITY_EARTH)
                    isStable = diff < 0.5f
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            sensorManager.unregisterListener(listener)
            shutterSound.release()
        }
    }

    val previewView = remember { 
        PreviewView(context).apply {
            // [RD] 使用 FIT_CENTER 确保预览画面不失真
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    val imageCapture = remember(aspectRatio) {
        ImageCapture.Builder()
            .setTargetAspectRatio(
                if (aspectRatio == AspectRatio.RATIO_4_3) {
                    androidx.camera.core.AspectRatio.RATIO_4_3
                } else {
                    androidx.camera.core.AspectRatio.RATIO_16_9
                }
            )
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val recorder = remember {
        Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    var recording: Recording? by remember { mutableStateOf(null) }
    val recordingTime by remember { mutableStateOf("00:00") }

    var cameraControl: CameraControl? by remember { mutableStateOf(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    
    var actualLensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }

    var facePoint by remember { mutableStateOf(Offset.Zero) }
    var showFocusIndicator by remember { mutableStateOf(false) }
    val focusIndicatorAlpha = remember { Animatable(0f) }

    val faceDetector = remember {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        FaceDetection.getClient(options)
    }

    val mediaAssets by viewModel.allMedia.collectAsState()
    val lastMedia = mediaAssets.firstOrNull()

    LaunchedEffect(currentScene) {
        cameraControl?.let { control ->
            PicMeLogger.i("Camera", "Applying scene: $currentScene")
            when (currentScene) {
                ScenePreset.NIGHT -> {
                    control.setExposureCompensationIndex(1)
                }
                ScenePreset.MOON -> {
                    control.setExposureCompensationIndex(-2)
                    control.setZoomRatio(3.2f)
                }
                ScenePreset.NONE -> {
                    control.setExposureCompensationIndex(0)
                }
            }
        }
    }

    LaunchedEffect(lensFacing, captureMode, aspectRatio) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = androidx.camera.core.Preview.Builder()
            .setTargetAspectRatio(
                if (aspectRatio == AspectRatio.RATIO_4_3) {
                    androidx.camera.core.AspectRatio.RATIO_4_3
                } else {
                    androidx.camera.core.AspectRatio.RATIO_16_9
                }
            )
            .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        // [FIXED] 不要在这里设置 actualLensFacing，等待相机绑定完成后从 cameraInfo 获取
        // actualLensFacing = lensFacing  // ❌ 错误：这里的值可能不准确
        
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetAspectRatio(
                if (aspectRatio == AspectRatio.RATIO_4_3) {
                    androidx.camera.core.AspectRatio.RATIO_4_3
                } else {
                    androidx.camera.core.AspectRatio.RATIO_16_9
                }
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            try {
                val mediaImage = imageProxy.image
                if (mediaImage != null && previewView.width > 0 && previewView.height > 0) {
                    val image = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees
                    )
                    faceDetector.process(image)
                        .addOnSuccessListener { faces ->
                            if (faces.isNotEmpty()) {
                                val face = faces[0]
                                val bounds = face.boundingBox
                                
                                PicMeLogger.d(
                                    "PicMe:Camera",
                                    "Face detected: bounds=(${bounds.centerX()},${bounds.centerY()}), " +
                                        "imageSize=${imageProxy.width}x${imageProxy.height}, " +
                                        "rot=${imageProxy.imageInfo.rotationDegrees}, " +
                                        "lens=$lensFacing"  // [FIXED] 使用 UI 状态的 lensFacing
                                )
                                
                                // [RD] 坐标转换：将人脸检测坐标映射到屏幕坐标
                                val screenPoint = transformFaceCoordinate(
                                    faceX = bounds.centerX().toFloat(),
                                    faceY = bounds.centerY().toFloat(),
                                    imageProxyWidth = imageProxy.width,
                                    imageProxyHeight = imageProxy.height,
                                    previewView = previewView,
                                    rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                                    lensFacing = lensFacing  // [FIXED] 使用 UI 状态的 lensFacing
                                )
                                
                                facePoint = screenPoint
                                showFocusIndicator = true
                                
                                PicMeLogger.d(
                                    "PicMe:Camera",
                                    "Face detected at screen: (${screenPoint.x.toInt()}, " +
                                        "${screenPoint.y.toInt()}), " +
                                        "confidence: ${face.trackingId}"
                                )

                                // [RD] 自动聚焦：使用屏幕坐标
                                cameraControl?.let { control ->
                                    val factory = previewView.meteringPointFactory
                                    val point = factory.createPoint(screenPoint.x, screenPoint.y)
                                    val action = FocusMeteringAction.Builder(
                                        point,
                                        FocusMeteringAction.FLAG_AF
                                    )
                                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                        .build()
                                    control.startFocusAndMetering(action)
                                }
                            } else {
                                showFocusIndicator = false
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            } catch (e: Exception) {
                PicMeLogger.e("PicMe:Camera", "Face detection error", e)
                imageProxy.close()
            }
        }

        try {
            cameraProvider.unbindAll()
            val camera = when (captureMode) {
                MediaType.PHOTO, MediaType.PORTRAIT, MediaType.PRO, MediaType.DOCUMENT -> {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture,
                        imageAnalysis
                    )
                }
                MediaType.VIDEO -> {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        videoCapture,
                        imageAnalysis
                    )
                }
            }
            cameraControl = camera.cameraControl
            camera.cameraInfo.zoomState.observe(lifecycleOwner) { state ->
                zoomRatio = state.zoomRatio
            }
            actualLensFacing = camera.cameraInfo.lensFacing
            PicMeLogger.d(
                "PicMe:Camera",
                "Camera bound: lensFacing=$actualLensFacing, selector=$lensFacing"
            )
        } catch (e: Exception) {
            PicMeLogger.e("Camera", "Binding failed", e)
        }
    }

    LaunchedEffect(showFocusIndicator) {
        focusIndicatorAlpha.animateTo(
            if (showFocusIndicator) 1f else 0f,
            animationSpec = tween(if (showFocusIndicator) 200 else 500)
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            faceDetector.close()
        }
    }

    CameraPreviewContent(
        previewView = { AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize()) },
        selectedFilter = selectedFilter,
        facePoint = facePoint,
        focusIndicatorAlpha = focusIndicatorAlpha.value,
        lastMedia = lastMedia,
        zoomRatio = zoomRatio,
        captureMode = captureMode,
        isRecording = isRecording,
        isStable = isStable,
        recordingTime = recordingTime,
        showFilterSelector = showFilterSelector,
        showBeautySelector = showBeautySelector,
        showRatioSelector = showRatioSelector,
        showCameraInfo = showCameraInfo,
        showSceneSelector = showSceneSelector,
        showGridSelector = showGridSelector,
        currentScene = currentScene,
        currentGrid = currentGrid,
        beautySettings = beautySettings,
        aspectRatio = aspectRatio,
        lensFacing = lensFacing,
        exposureCompensation = 0,
        exposureRange = -2..2,
        whiteBalanceMode = 0,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToDebug = onNavigateToDebug,
        onFlipCamera = {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            // [FIXED] 同时重置 actualLensFacing，等待重新绑定后更新
            actualLensFacing = lensFacing
        },
        onToggleBeauty = {
            showBeautySelector = !showBeautySelector
            showFilterSelector = false
            showRatioSelector = false
            showSceneSelector = false
            showGridSelector = false
        },
        onToggleFilter = {
            showFilterSelector = !showFilterSelector
            showBeautySelector = false
            showRatioSelector = false
            showSceneSelector = false
            showGridSelector = false
        },
        onToggleRatio = {
            showRatioSelector = !showRatioSelector
            showFilterSelector = false
            showBeautySelector = false
            showSceneSelector = false
            showGridSelector = false
        },
        onToggleCameraInfo = { showCameraInfo = !showCameraInfo },
        onToggleScene = {
            showSceneSelector = !showSceneSelector
            showFilterSelector = false
            showBeautySelector = false
            showRatioSelector = false
            showGridSelector = false
        },
        onToggleGrid = {
            showGridSelector = !showGridSelector
            showFilterSelector = false
            showBeautySelector = false
            showRatioSelector = false
            showSceneSelector = false
        },
        onToggleLogs = { showLogOverlay = !showLogOverlay },
        onZoomPresetClick = { cameraControl?.setZoomRatio(it) },
        onExposureChange = { cameraControl?.setExposureCompensationIndex(it) },
        onWhiteBalanceChange = { /* TODO */ },
        onSceneSelected = {
            currentScene = it
            showSceneSelector = false
        },
        onGridSelected = {
            currentGrid = it
            showGridSelector = false
        },
        onGalleryClick = onNavigateToGallery,
        onCaptureClick = {
            if (captureMode == MediaType.VIDEO) {
                if (isRecording) {
                    recording?.stop()
                    recording = null
                    isRecording = false
                } else {
                    val name = "PicMe_" + System.currentTimeMillis() + ".mp4"
                    val contentValues = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PicMe")
                    }
                    val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
                        context.contentResolver,
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    )
                        .setContentValues(contentValues)
                        .build()

                        isRecording = true
                        recording = videoCapture.output
                            .prepareRecording(context, mediaStoreOutputOptions)
                            .withAudioEnabled()
                            .start(ContextCompat.getMainExecutor(context)) { event ->
                                when (event) {
                                    is VideoRecordEvent.Finalize -> {
                                        if (!event.hasError()) {
                                            viewModel.insertMedia(
                                                MediaAsset(
                                                    uri = event.outputResults.outputUri.toString(),
                                                    type = MediaType.VIDEO,
                                                    captureDate = System.currentTimeMillis(),
                                                    fileName = name
                                                )
                                            )
                                        } else {
                                            recording?.close()
                                            recording = null
                                            isRecording = false
                                        }
                                    }
                                }
                            }
                    }
                } else {
                    shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                    imageProcessor.takePhoto(
                        context = context,
                        imageCapture = imageCapture,
                        viewModel = viewModel,
                        filter = selectedFilter,
                        beauty = beautySettings,
                        lensFacing = lensFacing,
                        mode = captureMode
                    )
                }
            },
            onModeChange = { captureMode = it },
            onFilterSelected = { selectedFilter = it },
            onBeautySettingsChanged = { beautySettings = it },
            onRatioSelected = {
                aspectRatio = it
                showRatioSelector = false
            },
            onDismissPanels = {
                showFilterSelector = false
                showBeautySelector = false
                showRatioSelector = false
                showSceneSelector = false
                showGridSelector = false
            }
        )

    if (showLogOverlay) {
        LogOverlay(onDismiss = { showLogOverlay = false })
    }
}

@Composable
fun CameraPreviewContent(
    previewView: @Composable () -> Unit,
    selectedFilter: FilterType,
    facePoint: Offset?,
    focusIndicatorAlpha: Float,
    lastMedia: MediaAsset?,
    zoomRatio: Float,
    captureMode: MediaType,
    isRecording: Boolean,
    isStable: Boolean,
    recordingTime: String,
    showFilterSelector: Boolean,
    showBeautySelector: Boolean,
    showRatioSelector: Boolean,
    showCameraInfo: Boolean,
    showSceneSelector: Boolean,
    showGridSelector: Boolean,
    currentScene: ScenePreset,
    currentGrid: GridType,
    beautySettings: BeautySettings,
    aspectRatio: Int,
    lensFacing: Int,
    exposureCompensation: Int,
    exposureRange: IntRange,
    whiteBalanceMode: Int,
    onNavigateToSettings: () -> Unit,
    onNavigateToDebug: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleBeauty: () -> Unit,
    onToggleFilter: () -> Unit,
    onToggleRatio: () -> Unit,
    onToggleCameraInfo: () -> Unit,
    onToggleScene: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggleLogs: () -> Unit,
    onZoomPresetClick: (Float) -> Unit,
    onExposureChange: (Int) -> Unit,
    onWhiteBalanceChange: (Int) -> Unit,
    onSceneSelected: (ScenePreset) -> Unit,
    onGridSelected: (GridType) -> Unit,
    onGalleryClick: () -> Unit,
    onCaptureClick: () -> Unit,
    onModeChange: (MediaType) -> Unit,
    onFilterSelected: (FilterType) -> Unit,
    onBeautySettingsChanged: (BeautySettings) -> Unit,
    onRatioSelected: (Int) -> Unit,
    onDismissPanels: () -> Unit
) {
    val isAnyPanelOpen = showFilterSelector || showBeautySelector || showRatioSelector ||
            showSceneSelector || showGridSelector

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        previewView()

        CameraOverlays(
            isStable = isStable,
            gridType = currentGrid,
            facePoint = facePoint,
            focusAlpha = focusIndicatorAlpha,
            showInfo = showCameraInfo,
            lensFacing = lensFacing,
            captureMode = captureMode,
            zoomRatio = zoomRatio,
            aspectRatio = aspectRatio,
            selectedFilter = selectedFilter,
            beautySettings = beautySettings,
            exposureCompensation = exposureCompensation,
            whiteBalanceMode = whiteBalanceMode,
            currentScene = currentScene
        )

        CameraLeftControls(
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToDebug = onNavigateToDebug,
            modifier = Modifier.align(Alignment.TopStart)
        )

        CameraRightControls(
            onToggleBeauty = onToggleBeauty,
            onToggleFilter = onToggleFilter,
            onToggleRatio = onToggleRatio,
            onToggleCameraInfo = onToggleCameraInfo,
            onToggleScene = onToggleScene,
            onToggleGrid = onToggleGrid,
            onToggleLogs = onToggleLogs,
            isBeautySelected = showBeautySelector,
            isFilterSelected = showFilterSelector,
            isRatioSelected = showRatioSelector,
            isCameraInfoSelected = showCameraInfo,
            isSceneActive = currentScene != ScenePreset.NONE,
            isGridActive = showGridSelector,
            currentRatio = aspectRatio,
            modifier = Modifier.align(Alignment.TopEnd)
        )

        if (captureMode == MediaType.PRO && !isAnyPanelOpen) {
            ProModeControls(
                exposure = exposureCompensation,
                exposureRange = exposureRange,
                onExposureChange = onExposureChange,
                whiteBalance = whiteBalanceMode,
                onWhiteBalanceChange = onWhiteBalanceChange,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 200.dp)
            )
        }

        CameraBottomControls(
            lastMedia = lastMedia,
            zoomRatio = zoomRatio,
            captureMode = captureMode,
            isRecording = isRecording,
            isAnyPanelOpen = isAnyPanelOpen,
            onZoomPresetClick = onZoomPresetClick,
            onGalleryClick = onGalleryClick,
            onCaptureClick = onCaptureClick,
            onFlipCamera = onFlipCamera,
            onModeChange = onModeChange,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (captureMode == MediaType.DOCUMENT && !isAnyPanelOpen) {
            DocumentDetectionOverlay(
                documentBounds = androidx.compose.ui.geometry.Rect.Zero,
                modifier = Modifier.fillMaxSize()
            )
        }

        AnimatedVisibility(
            visible = isAnyPanelOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val title = when {
                showFilterSelector -> stringResource(R.string.filters)
                showBeautySelector -> stringResource(R.string.beauty)
                showRatioSelector -> stringResource(R.string.aspect_ratio)
                showSceneSelector -> stringResource(R.string.scene)
                showGridSelector -> stringResource(R.string.grid)
                else -> ""
            }

            ControlPanel(
                title = title,
                onDismiss = onDismissPanels
            ) {
                when {
                    showFilterSelector -> FilterSelector(selectedFilter) { onFilterSelected(it) }
                    showBeautySelector -> BeautySelector(beautySettings) { settings ->
                        onBeautySettingsChanged(settings)
                    }

                    showRatioSelector -> RatioSelector(
                        selectedRatio = when (aspectRatio) {
                            AspectRatio.RATIO_4_3 -> CameraAspectRatio.RATIO_4_3
                            AspectRatio.RATIO_16_9 -> CameraAspectRatio.RATIO_16_9
                            else -> CameraAspectRatio.RATIO_FULL
                        },
                        onRatioSelected = {
                            onRatioSelected(
                                when (it) {
                                    CameraAspectRatio.RATIO_4_3 -> AspectRatio.RATIO_4_3
                                    CameraAspectRatio.RATIO_16_9 -> AspectRatio.RATIO_16_9
                                    else -> AspectRatio.RATIO_FULL
                                }
                            )
                        }
                    )

                    showSceneSelector -> SceneSelector(currentScene) { onSceneSelected(it) }
                    showGridSelector -> GridSelector(currentGrid) { onGridSelected(it) }
                }
            }
        }
    }
}

/**
 * [RD] 人脸坐标转换函数 - 重构版
 * 
 * 将 ML Kit 人脸检测坐标转换为屏幕坐标，用于十字星定位。
 * 
 * 处理流程：
 * 1. 归一化：将人脸坐标转换为 0-1 范围
 * 2. 旋转补偿：根据设备旋转角度调整坐标
 * 3. 镜像处理：前置摄像头需要水平翻转
 * 4. FIT_CENTER 映射：计算 PreviewView 实际渲染区域
 * 5. 输出屏幕坐标
 * 
 * @param faceX 人脸中心 X 坐标（图像坐标系）
 * @param faceY 人脸中心 Y 坐标（图像坐标系）
 * @param imageWidth 图像宽度
 * @param imageHeight 图像高度
 * @param previewView PreviewView 实例
 * @param rotationDegrees 图像旋转角度
 * @param lensFacing 摄像头方向
 * @return 屏幕坐标 Offset
 */
private fun transformCoordinate(
    x: Float,
    y: Float,
    imageWidth: Int,
    imageHeight: Int,
    previewWidth: Int,
    previewHeight: Int,
    rotationDegrees: Int,
    lensFacing: Int
): Pair<Float, Float> {
    // 简化版坐标转换（用于兼容性调用）
    val normalizedX = x / imageWidth
    val normalizedY = y / imageHeight
    
    val (rotatedX, rotatedY) = when (rotationDegrees) {
        90 -> Pair(normalizedY, 1f - normalizedX)
        180 -> Pair(1f - normalizedX, 1f - normalizedY)
        270 -> Pair(1f - normalizedY, normalizedX)
        else -> Pair(normalizedX, normalizedY)
    }
    
    val mirroredX = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
        1f - rotatedX
    } else {
        rotatedX
    }
    
    val finalX = mirroredX * previewWidth
    val finalY = rotatedY * previewHeight
    
    return Pair(finalX, finalY)
}
