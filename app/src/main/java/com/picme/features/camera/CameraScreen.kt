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
import androidx.camera.core.ImageCaptureException
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import kotlin.math.sqrt

enum class ScenePreset { NONE, NIGHT, MOON }
enum class GridType { NONE, THIRDS, GOLDEN }
enum class CameraAspectRatio { RATIO_4_3, RATIO_16_9, RATIO_1_1, RATIO_FULL }

object AspectRatio {
    const val RATIO_4_3 = 0
    const val RATIO_16_9 = 1
    const val RATIO_FULL = 2
}

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
@OptIn(ExperimentalMaterial3Api::class)
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
    
    // Document Detection
    var documentBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var isDocumentDetected by remember { mutableStateOf(false) }

    var exposureCompensation by remember { mutableIntStateOf(0) }
    var exposureRange by remember { mutableStateOf(-2..2) }
    var whiteBalanceMode by remember { mutableIntStateOf(0) }

    // Stability Monitoring
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
                    val diff = kotlin.math.abs(g - SensorManager.GRAVITY_EARTH)
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
            // [CRITICAL] 设置正确的 ScaleType，确保预览与分析器图像一致
            // 使用 FILL_CENTER 避免 FIT_CENTER 产生的黑边和坐标偏移
            scaleType = PreviewView.ScaleType.FILL_CENTER
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

    var facePoint by remember { mutableStateOf<Offset?>(null) }
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
                    exposureCompensation = 1
                    control.setExposureCompensationIndex(1)
                }

                ScenePreset.MOON -> {
                    exposureCompensation = -2
                    control.setExposureCompensationIndex(-2)
                    control.setZoomRatio(3.2f)
                }

                ScenePreset.NONE -> {
                    exposureCompensation = 0
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
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetAspectRatio(
                if (aspectRatio == AspectRatio.RATIO_4_3) {
                    androidx.camera.core.AspectRatio.RATIO_4_3
                } else {
                    androidx.camera.core.AspectRatio.RATIO_16_9
                }
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // [FIXED] ML Kit 仅支持 JPEG 和 YUV_420_888 格式，不能使用 RGBA_8888
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        @OptIn(ExperimentalGetImage::class)
        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            try {
                val mediaImage = imageProxy.image
                if (mediaImage != null && previewView.width > 0 && previewView.height > 0) {
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    faceDetector.process(image)
                        .addOnSuccessListener { faces ->
                            PicMeLogger.d("Camera", "Face detection: ${faces.size} faces found")
                            if (faces.isNotEmpty()) {
                                val face = faces[0]
                                val bounds = face.boundingBox

                                // [DEBUG] 输出原始数据
                                PicMeLogger.d("Camera", "========== Face Debug ==========")
                                PicMeLogger.d("Camera", "PreviewView: ${previewView.width}x${previewView.height}")
                                PicMeLogger.d("Camera", "ImageProxy: ${imageProxy.width}x${imageProxy.height}")
                                PicMeLogger.d("Camera", "Face bounds: left=${bounds.left}, top=${bounds.top}, right=${bounds.right}, bottom=${bounds.bottom}")
                                PicMeLogger.d("Camera", "Face center: (${bounds.centerX()}, ${bounds.centerY()})")
                                
                                // [REWRITTEN] 使用 CameraX 的 CoordinateTransform 进行精确转换
                                val previewWidth = previewView.width.toFloat()
                                val previewHeight = previewView.height.toFloat()
                                val imageWidth = imageProxy.width.toFloat()
                                val imageHeight = imageProxy.height.toFloat()
                                
                                // 1. 计算人脸中心点在 ImageProxy 中的归一化坐标 (0-1)
                                val normalizedX = bounds.centerX().toFloat() / imageWidth
                                val normalizedY = bounds.centerY().toFloat() / imageHeight
                                
                                PicMeLogger.d("Camera", "Normalized: ($normalizedX, $normalizedY)")
                                
                                // 2. 根据旋转角度调整坐标系
                                // CameraX 的 rotation 是传感器相对于设备自然方向的旋转角度
                                // 需要先将归一化坐标映射到预览的物理像素
                                var finalX: Float
                                var finalY: Float
                                
                                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                PicMeLogger.d("Camera", "Rotation: $rotationDegrees, Lens: $lensFacing")
                                
                                when (rotationDegrees) {
                                    0 -> {
                                        // 无旋转，直接映射
                                        finalX = normalizedX * previewWidth
                                        finalY = normalizedY * previewHeight
                                    }
                                    90 -> {
                                        // 顺时针 90 度：ImageProxy 的 Y → PreviewView 的 X, X → Y
                                        finalX = normalizedY * previewWidth
                                        finalY = (1f - normalizedX) * previewHeight
                                    }
                                    180 -> {
                                        // 180 度翻转
                                        finalX = (1f - normalizedX) * previewWidth
                                        finalY = (1f - normalizedY) * previewHeight
                                    }
                                    270 -> {
                                        // 逆时针 90 度：ImageProxy 的 Y → PreviewView 的 X, X → Y
                                        finalX = (1f - normalizedY) * previewWidth
                                        finalY = normalizedX * previewHeight
                                    }
                                    else -> {
                                        finalX = normalizedX * previewWidth
                                        finalY = normalizedY * previewHeight
                                    }
                                }
                                
                                // 3. 前置摄像头水平翻转
                                if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                                    finalX = previewWidth - finalX
                                    PicMeLogger.d("Camera", "Front camera flipped X: $finalX")
                                }
                                
                                PicMeLogger.d("Camera", "After rotation & flip: ($finalX, $finalY)")

                                facePoint = Offset(finalX, finalY)
                                showFocusIndicator = true

                                // Auto Focus on face
                                cameraControl?.let { control ->
                                    val factory = previewView.meteringPointFactory
                                    val point = factory.createPoint(finalX, finalY)
                                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                                        .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                                        .build()
                                    control.startFocusAndMetering(action)
                                }
                            } else {
                                showFocusIndicator = false
                            }
                        }
                        .addOnFailureListener { e ->
                            PicMeLogger.e("Camera", "Face detection failed", e)
                            showFocusIndicator = false
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    PicMeLogger.w("Camera", "Skip frame: mediaImage=${mediaImage != null}, previewSize=${previewView.width}x${previewView.height}")
                    imageProxy.close()
                }
            } catch (e: Exception) {
                PicMeLogger.e("Camera", "Image analysis error", e)
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
            val exposureInfo = camera.cameraInfo.exposureState
            exposureRange = exposureInfo.exposureCompensationRange.run { lower..upper }
            PicMeLogger.i("Camera", "Bound successfully: Mode=$captureMode, Lens=$lensFacing")
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
        exposureCompensation = exposureCompensation,
        exposureRange = exposureRange,
        whiteBalanceMode = whiteBalanceMode,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToDebug = onNavigateToDebug,
        onFlipCamera = {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
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
        onExposureChange = {
            exposureCompensation = it
            cameraControl?.setExposureCompensationIndex(it)
        },
        onWhiteBalanceChange = { whiteBalanceMode = it },
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
                                            PicMeLogger.i("Camera", "Video saved: $name")
                                        } else {
                                            recording?.close()
                                            recording = null
                                            isRecording = false
                                            PicMeLogger.e("Camera", "Video error: ${event.error}")
                                        }
                                    }
                                }
                            }
                    }
                } else {
                    shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                    // 使用 ImageProcessor 处理美颜和滤镜
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

        // [DOCUMENT MODE] Detection Overlay
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

// @Composable
// private fun OcrEntryButton(
//     onClick: () -> Unit,
//     modifier: Modifier = Modifier
// ) {
//     FilledTonalIconButton(
//         onClick = onClick,
//         modifier = modifier.size(56.dp),
//         colors = IconButtonDefaults.filledTonalIconButtonColors(
//             containerColor = Color.Black.copy(alpha = 0.4f),
//             contentColor = Color.White
//         )
//     ) {
//         Icon(
//             imageVector = Icons.AutoMirrored.Rounded.TextSnippet,
//             contentDescription = stringResource(R.string.ocr),
//             modifier = Modifier.size(28.dp)
//         )
//     }
// }

@OptIn(ExperimentalGetImage::class)
private fun processImageProxy(
    imageProxy: ImageProxy,
    detector: com.google.mlkit.vision.face.FaceDetector,
    cameraControl: CameraControl?,
    previewView: PreviewView,
    onFaceDetected: (x: Float, y: Float) -> Unit,
    onFocusStabilized: () -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val bounds = face.boundingBox

                    // Convert coordinates to PreviewView
                    val x = (bounds.centerX().toFloat() / imageProxy.width) * previewView.width
                    val y = (bounds.centerY().toFloat() / imageProxy.height) * previewView.height

                    onFaceDetected(x, y)

                    // Auto Focus on face
                    cameraControl?.let { control ->
                        val factory = previewView.meteringPointFactory
                        val point = factory.createPoint(x, y)
                        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        control.startFocusAndMetering(action)
                    }
                } else {
                    onFocusStabilized()
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}
