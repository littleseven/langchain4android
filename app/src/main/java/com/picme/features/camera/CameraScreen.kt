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
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.picme.PicMeApplication
import com.picme.R
import com.picme.core.designsystem.PicMeTheme
import com.picme.domain.model.BeautySettings
import com.picme.domain.model.MediaAsset
import com.picme.domain.model.MediaType
import com.picme.features.gallery.MediaViewModel
import com.picme.features.gallery.MediaViewModelFactory
import com.picme.features.camera.model.FilterType
import com.picme.features.camera.components.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.*
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
            (LocalContext.current.applicationContext as PicMeApplication).repository
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
        CameraContent(viewModel, onNavigateToGallery, onNavigateToSettings, onNavigateToDebug)
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                Text(stringResource(R.string.grant_permissions))
            }
        }
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraContent(
    viewModel: MediaViewModel,
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDebug: () -> Unit
) {
    val context = LocalContext.current
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
    
    var currentScene by remember { mutableStateOf(ScenePreset.NONE) }
    var currentGrid by remember { mutableStateOf(GridType.NONE) }
    
    var exposureCompensation by remember { mutableIntStateOf(0) }
    var exposureRange by remember { mutableStateOf(-2..2) }
    var whiteBalanceMode by remember { mutableIntStateOf(0) }

    // Stability Monitoring
    var isStable by remember { mutableStateOf(true) }
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val accelerometer = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    
    DisposableEffect(Unit) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event?.let {
                    val x = it.values[0]
                    val y = it.values[1]
                    val z = it.values[2]
                    val g = sqrt(x*x + y*y + z*z)
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

    val previewView = remember { PreviewView(context) }
    
    val imageCapture = remember(aspectRatio) { 
        ImageCapture.Builder()
            .setTargetAspectRatio(if (aspectRatio == AspectRatio.RATIO_4_3) androidx.camera.core.AspectRatio.RATIO_4_3 else androidx.camera.core.AspectRatio.RATIO_16_9)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build() 
    }
    val recorder = remember { Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build() }
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
            .setTargetAspectRatio(if (aspectRatio == AspectRatio.RATIO_4_3) androidx.camera.core.AspectRatio.RATIO_4_3 else androidx.camera.core.AspectRatio.RATIO_16_9)
            .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetAspectRatio(if (aspectRatio == AspectRatio.RATIO_4_3) androidx.camera.core.AspectRatio.RATIO_4_3 else androidx.camera.core.AspectRatio.RATIO_16_9)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processImageProxy(
                imageProxy = imageProxy,
                detector = faceDetector,
                cameraControl = cameraControl,
                previewView = previewView,
                onFaceDetected = { x, y ->
                    facePoint = Offset(x, y)
                    showFocusIndicator = true
                },
                onFocusStabilized = { showFocusIndicator = false }
            )
        }

        try {
            cameraProvider.unbindAll()
            val camera = when (captureMode) {
                MediaType.PHOTO, MediaType.PORTRAIT, MediaType.PRO -> {
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture, imageAnalysis)
                }
                MediaType.VIDEO -> {
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture, imageAnalysis)
                }
            }
            cameraControl = camera.cameraControl
            camera.cameraInfo.zoomState.observe(lifecycleOwner) { state -> zoomRatio = state.zoomRatio }
            val exposureInfo = camera.cameraInfo.exposureState
            exposureRange = exposureInfo.exposureCompensationRange.run { lower..upper }
        } catch (e: Exception) { Log.e("CameraScreen", "Use case binding failed", e) }
    }

    LaunchedEffect(showFocusIndicator) {
        focusIndicatorAlpha.animateTo(if (showFocusIndicator) 1f else 0f, animationSpec = tween(if (showFocusIndicator) 200 else 500))
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
        onFlipCamera = { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK },
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
                    shutterSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                    recording?.stop()
                    recording = null
                    isRecording = false
                } else {
                    shutterSound.play(MediaActionSound.START_VIDEO_RECORDING)
                    val name = "PicMe_" + System.currentTimeMillis() + ".mp4"
                    val contentValues = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PicMe")
                    }
                    val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                        .setContentValues(contentValues)
                        .build()

                    recording = videoCapture.output
                        .prepareRecording(context, mediaStoreOutputOptions)
                        .withAudioEnabled()
                        .start(ContextCompat.getMainExecutor(context)) { event ->
                            when(event) {
                                is VideoRecordEvent.Start -> {
                                    isRecording = true
                                }
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
                val name = "PicMe_" + System.currentTimeMillis() + ".jpg"
                val contentValues = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicMe")
                }
                val outputOptions = ImageCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build()
                imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) { 
                        val savedUri = output.savedUri ?: return
                        viewModel.insertMedia(
                            MediaAsset(
                                uri = savedUri.toString(),
                                type = captureMode,
                                captureDate = System.currentTimeMillis(),
                                fileName = name
                            )
                        )
                    }
                    override fun onError(exception: ImageCaptureException) { Log.e("CameraScreen", "Photo capture failed: ${exception.message}", exception) }
                })
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
    val isAnyPanelOpen = showFilterSelector || showBeautySelector || showRatioSelector || showSceneSelector || showGridSelector

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
            whiteBalanceMode = whiteBalanceMode
        )

        CameraLeftControls(
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToDebug = onNavigateToDebug,
            onToggleGrid = onToggleGrid,
            isGridActive = showGridSelector,
            modifier = Modifier.align(Alignment.TopStart)
        )

        CameraRightControls(
            onToggleBeauty = onToggleBeauty,
            onToggleFilter = onToggleFilter,
            onToggleRatio = onToggleRatio,
            onToggleCameraInfo = onToggleCameraInfo,
            onToggleScene = onToggleScene,
            isBeautySelected = showBeautySelector,
            isFilterSelected = showFilterSelector,
            isRatioSelected = showRatioSelector,
            isCameraInfoSelected = showCameraInfo,
            isSceneActive = currentScene != ScenePreset.NONE,
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
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 200.dp)
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
                    showBeautySelector -> BeautySelector(beautySettings) { onSettingsChanged -> onBeautySettingsChanged(onSettingsChanged) }
                    showRatioSelector -> RatioSelector(
                        selectedRatio = when(aspectRatio) {
                            AspectRatio.RATIO_4_3 -> CameraAspectRatio.RATIO_4_3
                            AspectRatio.RATIO_16_9 -> CameraAspectRatio.RATIO_16_9
                            else -> CameraAspectRatio.RATIO_FULL
                        },
                        onRatioSelected = { 
                            onRatioSelected(when(it) {
                                CameraAspectRatio.RATIO_4_3 -> AspectRatio.RATIO_4_3
                                CameraAspectRatio.RATIO_16_9 -> AspectRatio.RATIO_16_9
                                else -> AspectRatio.RATIO_FULL
                            })
                        }
                    )
                    showSceneSelector -> SceneSelector(currentScene) { onSceneSelected(it) }
                    showGridSelector -> GridSelector(currentGrid) { onGridSelected(it) }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
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
        detector.process(image).addOnSuccessListener { faces ->
            if (faces.isNotEmpty()) {
                val bounds = faces[0].boundingBox
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                onFaceDetected(point.x * previewView.width, point.y * previewView.height) 
                val action = FocusMeteringAction.Builder(point)
                    .setAutoCancelDuration(2, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                cameraControl?.startFocusAndMetering(action)?.addListener(
                    { onFocusStabilized() }, 
                    ContextCompat.getMainExecutor(previewView.context)
                )
            }
        }.addOnCompleteListener { imageProxy.close() }
    } else imageProxy.close()
}

@Preview(showBackground = true)
@Composable
fun CameraScreenPreview() {
    PicMeTheme {
        CameraPreviewContent(
            previewView = { Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) { Text("Camera Preview Placeholder", color = Color.White) } },
            selectedFilter = FilterType.VINTAGE,
            facePoint = Offset(500f, 800f),
            focusIndicatorAlpha = 1f,
            lastMedia = null,
            zoomRatio = 1f,
            captureMode = MediaType.PHOTO,
            isRecording = false,
            isStable = true,
            recordingTime = "00:00",
            showFilterSelector = false,
            showBeautySelector = false,
            showRatioSelector = false,
            showCameraInfo = true,
            showSceneSelector = false,
            showGridSelector = false,
            currentScene = ScenePreset.NONE,
            currentGrid = GridType.THIRDS,
            beautySettings = BeautySettings(),
            aspectRatio = AspectRatio.RATIO_4_3,
            lensFacing = CameraSelector.LENS_FACING_BACK,
            exposureCompensation = 0,
            exposureRange = -2..2,
            whiteBalanceMode = 0,
            onNavigateToSettings = {},
            onNavigateToDebug = {},
            onFlipCamera = {},
            onToggleBeauty = {},
            onToggleFilter = {},
            onToggleRatio = {},
            onToggleCameraInfo = {},
            onToggleScene = {},
            onToggleGrid = {},
            onZoomPresetClick = {},
            onExposureChange = {},
            onWhiteBalanceChange = {},
            onSceneSelected = {},
            onGridSelected = {},
            onGalleryClick = {},
            onCaptureClick = {},
            onModeChange = {},
            onFilterSelected = {},
            onBeautySettingsChanged = {},
            onRatioSelected = {},
            onDismissPanels = {}
        )
    }
}
