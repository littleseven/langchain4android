package com.picme.features.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.view.TextureView
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
import com.picme.domain.usecase.OcrUseCase
import com.picme.features.gallery.MediaViewModel
import com.picme.features.gallery.MediaViewModelFactory
import com.picme.features.camera.model.FilterType
import com.picme.features.debug.LogOverlay
import com.picme.core.image.pixelfree.PixelFreeGLSurfaceView
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
 * [RD] 人脸坐标转换函数 - 简化版（分离关注点）
 * 
 * 【核心思路】将复杂的变换分解为三个独立步骤：
 * 1. 归一化：ML Kit 坐标 → [0,1] 归一化坐标
 * 2. 镜像处理：前置摄像头需要水平翻转
 * 3. 旋转补偿：根据设备旋转角度调整坐标系
 * 
 * 【关键发现】所有坐标系都使用左上角原点，X 向右，Y 向下
 * 因此只需要关注旋转和镜像，不需要考虑坐标轴翻转
 */
internal fun transformFaceCoordinate(
    faceX: Float,
    faceY: Float,
    imageProxyWidth: Int,
    imageProxyHeight: Int,
    previewView: PreviewView,
    rotationDegrees: Int,
    lensFacing: Int
): Offset {
    // ========== Step 1: 归一化 ==========
    val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
        90, 270 -> Pair(imageProxyHeight, imageProxyWidth)
        else -> Pair(imageProxyWidth, imageProxyHeight)
    }
    
    val normX = faceX / rotatedWidth
    val normY = faceY / rotatedHeight
    
    PicMeLogger.d(
        "PicMe:Camera",
        "Step1 [归一化]: face=($faceX,$faceY), rotatedSize=${rotatedWidth}x${rotatedHeight}, " +
            "norm=($normX,$normY)"
    )
    
    // ========== Step 2: 镜像处理（前置摄像头） ==========
    // 在旋转之前先镜像 X 轴（传感器坐标系）
    val mirroredX = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
        1f - normX
    } else {
        normX
    }
    
    PicMeLogger.d(
        "PicMe:Camera",
        "Step2 [镜像]: lens=${if (lensFacing == CameraSelector.LENS_FACING_FRONT) "前" else "后"}, " +
            "norm=($normX,$normY), mirrored=($mirroredX,$normY)"
    )
    
    // ========== Step 3: 旋转补偿 ==========
    // 根据旋转角度调整坐标方向
    val (adjustedX, adjustedY) = when (rotationDegrees) {
        0 -> Pair(mirroredX, normY)      // 竖屏：不需要调整
        90 -> Pair(mirroredX, normY)     // 顺时针 90°: 不交换 XY
        180 -> Pair(1f - mirroredX, 1f - normY) // 倒立：XY 都翻转
        270 -> Pair(mirroredX, normY)    // 逆时针 90°: 不交换 XY
        else -> Pair(mirroredX, normY)
    }
    
    PicMeLogger.d(
        "PicMe:Camera",
        "Step3 [旋转补偿]: rot=$rotationDegrees, mirrored=($mirroredX,$normY), " +
            "adjusted=($adjustedX,$adjustedY)"
    )
    
    // ========== Step 4: 转换为像素坐标 ==========
    // 将归一化坐标转换为 PreviewView 的物理像素坐标
    val previewWidth = previewView.width.toFloat()
    val previewHeight = previewView.height.toFloat()
    
    val screenX = adjustedX * previewWidth
    val screenY = adjustedY * previewHeight
    
    PicMeLogger.d(
        "PicMe:Camera",
        "Step4 [像素转换]: adj=($adjustedX,$adjustedY), previewSize=${previewWidth.toInt()}x${previewHeight.toInt()}, " +
            "screen=($screenX,$screenY)"
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
    
    // [RD] 监听 SurfaceTexture 准备状态
    var surfaceTextureReady by remember { mutableStateOf(false) }
    
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var captureMode by remember { mutableStateOf(MediaType.PHOTO) }
    var isRecording by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(FilterType.NONE) }
    var beautySettings by remember { mutableStateOf(BeautySettings()) }
    var aspectRatio by remember { mutableIntStateOf(AspectRatio.RATIO_FULL) }

    // [RD] 临时方案：先使用 TextureView 显示原始相机预览
    // PixelFree SDK 实时美颜预览实现比较复杂，后续完善
    val textureView = remember {
        object : android.view.TextureView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // 【原理】相机预览比例的正確处理方式
                // 
                // 1. 传感器输出（横向）:
                //    - 4:3 模式：640x480 (1.33)
                //    - 16:9/FULL 模式：864x480 (1.8)
                //
                // 2. CameraX Preview 旋转 90°后（竖屏）:
                //    - 4:3: 480x640 (0.75)
                //    - 16:9/FULL: 480x864 (0.555)
                //
                // 3. 关键：CameraX 会自动缩放到匹配 Surface
                //    - 如果 Surface 比例 = 画面比例 → 完美显示
                //    - 如果 Surface 比例 ≠ 画面比例 → 会拉伸!
                //
                // 4. 正确策略：让 TextureView 的比例 = 传感器输出比例
                
                val width = MeasureSpec.getSize(widthMeasureSpec)
                val height = MeasureSpec.getSize(heightMeasureSpec)
                
                when (aspectRatio) {
                    AspectRatio.RATIO_4_3 -> {
                        // 4:3 模式：传感器 640x480 → 旋转后 480x640 → 比例 0.75
                        // 竖屏时：按宽度 1080 计算，高度应为 1080/0.75 = 1440
                        if (height > width) {
                            setMeasuredDimension(width, (width / 0.75f).toInt())
                        } else {
                            setMeasuredDimension((height * 0.75f).toInt(), height)
                        }
                    }
                    AspectRatio.RATIO_16_9, AspectRatio.RATIO_FULL -> {
                        // FULL mode: sensor output may be 1920x1080 or 864x480
                        // CameraX rotates 90° for portrait
                        // We need to match the ACTUAL output ratio from CameraX
                        // 
                        // If CameraX outputs 1920x1080 (ratio=16:9=1.777...)
                        // After rotation: 1080x1920 (ratio=1.777...)
                        // TextureView should be: width=1080, height=1080×(1920/1080)=1920
                        //
                        // If CameraX outputs 864x480 (ratio=1.8)
                        // After rotation: 480x864 (ratio=1.8)
                        // TextureView should be: width=1080, height=1080×(864/480)=1944
                        //
                        // [FIXED] Use 1920/1080 = 16:9 ratio to match actual CameraX output
                        if (height > width) {
                            // Portrait: fill width, height proportional
                            val newHeight = (width * (1920f / 1080f)).toInt()
                            setMeasuredDimension(width, newHeight)
                        } else {
                            // Landscape: fill height, width proportional
                            val newWidth = (height * (1080f / 1920f)).toInt()
                            setMeasuredDimension(newWidth, height)
                        }
                    }
                    else -> setMeasuredDimension(width, height)
                }
                
                // [调试] 输出测量结果
                android.util.Log.d("PicMe:Camera", 
                    "TextureView measured: ${measuredWidth}x${measuredHeight}, " +
                    "ratio=${measuredHeight.toFloat()/measuredWidth}, expected=1.8"
                )
            }
        }.apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            android.util.Log.d("PicMe:Camera", "TextureView created")
        }
    }

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
    
    // [RD] 监听美颜参数变化（暂时注释，后续实现）
    // LaunchedEffect(beautySettings) {
    //     pixelFreeView.setSmoothingStrength(beautySettings.smoothing.toFloat())
    //     pixelFreeView.setWhiteningStrength(beautySettings.whitening.toFloat())
    //     pixelFreeView.setSlimFaceStrength(beautySettings.slimFace.toFloat())
    //     pixelFreeView.setBigEyesStrength(beautySettings.bigEyes.toFloat())
    //     android.util.Log.d("PicMe:Camera", "PixelFree beauty updated: smoothing=${beautySettings.smoothing}, whitening=${beautySettings.whitening}")
    // }
    
    // [RD] PixelFreeEffects 初始化
    LaunchedEffect(Unit) {
        // 等待 GLSurface 创建和初始化
        kotlinx.coroutines.delay(500)
        android.util.Log.d("PicMe:Camera", "PixelFree initialization requested")
    }

    LaunchedEffect(lensFacing, captureMode, aspectRatio) {
        val cameraProvider = cameraProviderFuture.get()
        
        // [RD] 临时方案：先使用普通 Preview 显示相机画面
        // PixelFreeEffects 将在拍照时应用美颜效果
        android.util.Log.d("PicMe:Camera", "Binding camera (temporary preview mode)")
            
        val preview = androidx.camera.core.Preview.Builder()
            .setTargetAspectRatio(
                when (aspectRatio) {
                    AspectRatio.RATIO_4_3 -> androidx.camera.core.AspectRatio.RATIO_4_3
                    // [关键修复] FULL 和 16:9 都使用 RATIO_16_9
                    // 但实际分辨率由相机硬件决定，可能是 1920x1080 或 864x480
                    AspectRatio.RATIO_16_9, AspectRatio.RATIO_FULL -> androidx.camera.core.AspectRatio.RATIO_16_9
                    else -> androidx.camera.core.AspectRatio.RATIO_4_3
                }
            )
            .build()
            .also { previewBuilder ->
                // [关键修复] 使用自动适配的 SurfaceProvider
                // CameraX 会根据 Surface 的尺寸自动缩放画面
                previewBuilder.setSurfaceProvider(cameraExecutor) { surfaceRequest ->
                    android.util.Log.d("PicMe:Camera", 
                        "Preview SurfaceRequest: ${surfaceRequest.resolution?.width}x${surfaceRequest.resolution?.height}"
                    )
                    textureView.surfaceTexture?.let { surfaceTexture ->
                        val surface = android.view.Surface(surfaceTexture)
                        surfaceRequest.provideSurface(surface, cameraExecutor) {}
                        android.util.Log.d("PicMe:Camera", 
                            "Preview provided surface to TextureView(${textureView.width}x${textureView.height})"
                        )
                    } ?: run {
                        android.util.Log.e("PicMe:Camera", "TextureView surfaceTexture is null")
                    }
                }
            }

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
                // [调试] 输出实际帧信息
                android.util.Log.d("PicMe:Camera", 
                    "Frame: ${imageProxy.width}x${imageProxy.height}, " +
                    "ratio=${imageProxy.width.toFloat()/imageProxy.height.toFloat()}, " +
                    "rot=${imageProxy.imageInfo.rotationDegrees}, " +
                    "TextureView: ${textureView.width}x${textureView.height}"
                )
                if (mediaImage != null && textureView.width > 0 && textureView.height > 0) {
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
                                val screenPoint = transformFaceCoordinateSimple(
                                    faceX = bounds.centerX().toFloat(),
                                    faceY = bounds.centerY().toFloat(),
                                    imageProxyWidth = imageProxy.width,
                                    imageProxyHeight = imageProxy.height,
                                    previewWidth = textureView.width.toFloat(),
                                    previewHeight = textureView.height.toFloat(),
                                    rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                                    lensFacing = lensFacing
                                )
                                
                                facePoint = screenPoint
                                showFocusIndicator = true
                                
                                // [RD] PixelFreeGLSurfaceView 不支持自动对焦
                                PicMeLogger.d(
                                    "PicMe:Camera",
                                    "Face detected at screen: (${screenPoint.x.toInt()}, ${screenPoint.y.toInt()})"
                                )
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
            
            // [RD] 关键修复：使用 TextureView 显示预览
            android.util.Log.d("PicMe:Camera", "Binding camera with TextureView display")
            
            // [RD] 复用 imageAnalysis 同时用于人脸检测和美颜预览
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    android.util.Log.d("PicMe:Camera", "ImageAnalysis received frame: ${imageProxy.width}x${imageProxy.height}")
                    
                    val mediaImage = imageProxy.image
                    if (mediaImage != null && textureView.width > 0 && textureView.height > 0) {
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
                                            "lens=$lensFacing"
                                    )
                                    
                                    // [RD] 坐标转换：将人脸检测坐标映射到屏幕坐标
                                    val screenPoint = transformFaceCoordinateSimple(
                                        faceX = bounds.centerX().toFloat(),
                                        faceY = bounds.centerY().toFloat(),
                                        imageProxyWidth = imageProxy.width,
                                        imageProxyHeight = imageProxy.height,
                                        previewWidth = textureView.width.toFloat(),
                                        previewHeight = textureView.height.toFloat(),
                                        rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                                        lensFacing = lensFacing
                                    )
                                    
                                    facePoint = screenPoint
                                    showFocusIndicator = true
                                    
                                    PicMeLogger.d(
                                        "PicMe:Camera",
                                        "Face detected at screen: (${screenPoint.x.toInt()}, ${screenPoint.y.toInt()})"
                                    )
                                } else {
                                    showFocusIndicator = false
                                }
                            }
                            .addOnCompleteListener {
                                // [RD] 人脸检测完成后，关闭 imageProxy
                                // TODO: 后续实现美颜预览
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

    // [RD] 处理美颜预览
    fun processBeautyPreview(imageProxy: androidx.camera.core.ImageProxy, pixelFreeView: com.picme.core.image.pixelfree.PixelFreeGLSurfaceView) {
        try {
            val buffer = imageProxy.planes[0].buffer
            val pixelCount = buffer.remaining() / 4 // RGBA, 每个像素 4 字节
            
            if (pixelCount > 0 && pixelFreeView.isCreate()) {
                // 创建一个纹理用于存储图像数据
                val textureIds = IntArray(1)
                android.opengl.GLES20.glGenTextures(1, textureIds, 0)
                val textureId = textureIds[0]
                
                // 绑定纹理
                android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, textureId)
                
                // 设置纹理参数
                android.opengl.GLES20.glTexParameteri(
                    android.opengl.GLES20.GL_TEXTURE_2D,
                    android.opengl.GLES20.GL_TEXTURE_MIN_FILTER,
                    android.opengl.GLES20.GL_LINEAR
                )
                android.opengl.GLES20.glTexParameteri(
                    android.opengl.GLES20.GL_TEXTURE_2D,
                    android.opengl.GLES20.GL_TEXTURE_MAG_FILTER,
                    android.opengl.GLES20.GL_LINEAR
                )
                
                // 加载图像数据到纹理 - 使用 ByteBuffer 版本
                buffer.rewind()
                android.opengl.GLES20.glTexImage2D(
                    android.opengl.GLES20.GL_TEXTURE_2D,
                    0,
                    android.opengl.GLES20.GL_RGBA,
                    imageProxy.width,
                    imageProxy.height,
                    0,
                    android.opengl.GLES20.GL_RGBA,
                    android.opengl.GLES20.GL_UNSIGNED_BYTE,
                    buffer
                )
                
                // 设置纹理到 PixelFreeGLSurfaceView
                pixelFreeView.setCameraTextureId(textureId, imageProxy.width, imageProxy.height)
                
                android.util.Log.d("PicMe:Camera", "Beauty preview frame: ${imageProxy.width}x${imageProxy.height}, texture=$textureId")
                
                // 清理纹理
                android.opengl.GLES20.glDeleteTextures(1, textureIds, 0)
            }
        } catch (e: Exception) {
            android.util.Log.e("PicMe:Camera", "Beauty preview error: ${e.message}", e)
        } finally {
            imageProxy.close()
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
        textureView = textureView,
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
                    // [RD] 临时注释掉拍照音
                    // shutterSound.play(MediaActionSound.SHUTTER_CLICK)
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
    textureView: android.view.TextureView,
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
        // 使用 TextureView 显示相机预览
        AndroidView(
            factory = { textureView },
            modifier = Modifier.fillMaxSize()
        )

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

/**
 * [RD] 简化版人脸坐标转换（用于 BeautyTextureView）
 */
/**
 * [FIX] 简化版人脸坐标转换函数
 * 
 * 【问题】原函数没有考虑相机画面的实际显示比例，导致坐标偏移
 * 【解决】使用与 transformFaceCoordinate 相同的逻辑，但适配 TextureView
 */
private fun transformFaceCoordinateSimple(
    faceX: Float,
    faceY: Float,
    imageProxyWidth: Int,
    imageProxyHeight: Int,
    previewWidth: Float,
    previewHeight: Float,
    rotationDegrees: Int,
    lensFacing: Int
): Offset {
    // ========== Step 1: 归一化 ==========
    val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
        90, 270 -> Pair(imageProxyHeight, imageProxyWidth)
        else -> Pair(imageProxyWidth, imageProxyHeight)
    }
    
    val normX = faceX / rotatedWidth
    val normY = faceY / rotatedHeight
    
    android.util.Log.d(
        "PicMe:Camera",
        "Step1 [归一化]: face=($faceX,$faceY), rotatedSize=${rotatedWidth}x${rotatedHeight}, " +
            "norm=($normX,$normY)"
    )
    
    // ========== Step 2: 镜像处理（前置摄像头）==========
    val mirroredX = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
        1f - normX
    } else {
        normX
    }
    
    android.util.Log.d(
        "PicMe:Camera",
        "Step2 [镜像]: lens=${if (lensFacing == CameraSelector.LENS_FACING_FRONT) "前" else "后"}, " +
            "norm=($normX,$normY), mirrored=($mirroredX,$normY)"
    )
    
    // ========== Step 3: 旋转补偿 ==========
    val (adjustedX, adjustedY) = when (rotationDegrees) {
        0 -> Pair(mirroredX, normY)      // 竖屏：不需要调整
        90 -> Pair(mirroredX, normY)     // 顺时针 90°: 不交换 XY
        180 -> Pair(1f - mirroredX, 1f - normY) // 倒立：XY 都翻转
        270 -> Pair(mirroredX, normY)    // 逆时针 90°: 不交换 XY
        else -> Pair(mirroredX, normY)
    }
    
    android.util.Log.d(
        "PicMe:Camera",
        "Step3 [旋转补偿]: rot=$rotationDegrees, mirrored=($mirroredX,$normY), " +
            "adjusted=($adjustedX,$adjustedY)"
    )
    
    // ========== Step 4: 转换为像素坐标 ==========
    val screenX = adjustedX * previewWidth
    val screenY = adjustedY * previewHeight
    
    android.util.Log.d(
        "PicMe:Camera",
        "Step4 [像素转换]: adj=($adjustedX,$adjustedY), previewSize=${previewWidth.toInt()}x${previewHeight.toInt()}, " +
            "screen=($screenX,$screenY)"
    )
    
    return Offset(screenX, screenY)
}
