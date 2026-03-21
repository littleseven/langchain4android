package com.example.picme.ui.screens

import android.Manifest
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.picme.PicMeApplication
import com.example.picme.R
import com.example.picme.data.model.MediaAsset
import com.example.picme.data.model.MediaType
import com.example.picme.domain.BeautySettings
import com.example.picme.domain.ImageProcessor
import com.example.picme.domain.ImageProcessorImpl
import com.example.picme.ui.components.*
import com.example.picme.ui.model.FilterType
import com.example.picme.ui.viewmodel.MediaViewModel
import com.example.picme.ui.viewmodel.MediaViewModelFactory
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: MediaViewModel = viewModel(
        factory = MediaViewModelFactory((LocalContext.current.applicationContext as PicMeApplication).repository)
    )
) {
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )

    if (permissionsState.allPermissionsGranted) {
        CameraContent(viewModel, onNavigateToGallery, onNavigateToSettings)
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                Text(stringResource(R.string.grant_permissions))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraContent(
    viewModel: MediaViewModel,
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val imageProcessor: ImageProcessor = remember { ImageProcessorImpl() }

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var captureMode by remember { mutableStateOf(MediaType.PHOTO) }
    var isRecording by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(FilterType.NONE) }
    var beautySettings by remember { mutableStateOf(BeautySettings()) }

    var showFilterSelector by remember { mutableStateOf(false) }
    var showBeautySelector by remember { mutableStateOf(false) }

    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val recorder = remember { Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build() }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    var recording: Recording? by remember { mutableStateOf(null) }

    var cameraControl: CameraControl? by remember { mutableStateOf(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }

    // Face Tracking States
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

    LaunchedEffect(lensFacing, captureMode) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

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
            val camera = if (captureMode == MediaType.PHOTO) {
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture, imageAnalysis)
            } else {
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture, imageAnalysis)
            }
            cameraControl = camera.cameraControl
            camera.cameraInfo.zoomState.observe(lifecycleOwner) { state -> zoomRatio = state.zoomRatio }
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1. Camera Preview
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // 2. Filter Overlay
        if (selectedFilter != FilterType.NONE) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(color = Color.White, alpha = 0.15f, colorFilter = ColorFilter.colorMatrix(selectedFilter.getColorMatrix()))
            }
        }

        // 3. Face Focus Indicator
        facePoint?.let { FaceFocusIndicator(offset = it, alpha = focusIndicatorAlpha.value) }

        // 4. Sidebar Controls
        CameraSideBar(
            onNavigateToSettings = onNavigateToSettings,
            onFlipCamera = { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK },
            onToggleBeauty = { showBeautySelector = !showBeautySelector; showFilterSelector = false },
            onToggleFilter = { showFilterSelector = !showFilterSelector; showBeautySelector = false },
            isBeautySelected = showBeautySelector,
            isFilterSelected = showFilterSelector,
            modifier = Modifier.align(Alignment.TopEnd)
        )

        // 5. Bottom Controls
        CameraBottomControls(
            lastMedia = lastMedia,
            zoomRatio = zoomRatio,
            captureMode = captureMode,
            isRecording = isRecording,
            onZoomPresetClick = { cameraControl?.setZoomRatio(it) },
            onGalleryClick = onNavigateToGallery,
            onCaptureClick = {
                if (captureMode == MediaType.PHOTO) {
                    imageProcessor.takePhoto(context, imageCapture, viewModel, selectedFilter, beautySettings, lensFacing)
                } else {
                    if (isRecording) {
                        recording?.stop()
                        isRecording = false
                    } else {
                        isRecording = true
                        recording = imageProcessor.startVideoRecording(context, videoCapture, viewModel) { isRecording = false }
                    }
                }
            },
            onModeChange = { captureMode = it },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // 6. Panel (Filters/Beauty)
        AnimatedVisibility(
            visible = showFilterSelector || showBeautySelector,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ControlPanel(
                title = if (showFilterSelector) stringResource(R.string.filters) else stringResource(R.string.beauty),
                onDismiss = { showFilterSelector = false; showBeautySelector = false }
            ) {
                if (showFilterSelector) {
                    FilterSelector(selectedFilter) { selectedFilter = it }
                } else {
                    BeautySelector(beautySettings) { beautySettings = it }
                }
            }
        }
    }
}

@Composable
fun CameraSideBar(
    onNavigateToSettings: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleBeauty: () -> Unit,
    onToggleFilter: () -> Unit,
    isBeautySelected: Boolean,
    isFilterSelected: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SideBarItem(icon = Icons.Default.Settings, contentDescription = "Settings", onClick = onNavigateToSettings)
        SideBarItem(icon = Icons.Rounded.FlipCameraAndroid, contentDescription = "Flip", onClick = onFlipCamera)
        SideBarItem(icon = Icons.Default.Face, contentDescription = "Beauty", isSelected = isBeautySelected, onClick = onToggleBeauty)
        SideBarItem(icon = Icons.Default.AutoFixHigh, contentDescription = "Filters", isSelected = isFilterSelected, onClick = onToggleFilter)
    }
}

@Composable
fun CameraBottomControls(
    lastMedia: MediaAsset?,
    zoomRatio: Float,
    captureMode: MediaType,
    isRecording: Boolean,
    onZoomPresetClick: (Float) -> Unit,
    onGalleryClick: () -> Unit,
    onCaptureClick: () -> Unit,
    onModeChange: (MediaType) -> Unit,
    modifier: Modifier = Modifier
) {
    val zoomPresets = listOf(0.6f, 1f, 2.6f, 5f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Zoom Selectors
        Row(modifier = Modifier.padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            zoomPresets.forEach { preset ->
                val isSelected = (zoomRatio >= preset - 0.05f && zoomRatio <= preset + 0.05f) || (preset == 1f && zoomRatio < 1.1f && zoomRatio > 0.9f)
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape)
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f))
                        .clickable { onZoomPresetClick(preset) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = if (preset < 1f) ".6" else preset.toInt().toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        
        // Capture Main Row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GalleryThumbnail(lastMedia = lastMedia, onClick = onGalleryClick)
            CameraCaptureButton(isRecording = isRecording, mode = captureMode, onClick = onCaptureClick)
            Spacer(modifier = Modifier.size(56.dp))
        }

        // Mode Switcher
        Row(modifier = Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            MediaType.entries.forEach { mode ->
                Text(
                    text = mode.name,
                    color = if (captureMode == mode) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                    fontWeight = if (captureMode == mode) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable { onModeChange(mode) }
                )
            }
        }
    }
}

@Composable
fun FaceFocusIndicator(offset: Offset, alpha: Float) {
    val density = LocalDensity.current
    val sizeDp = 60.dp
    val sizePx = with(density) { sizeDp.toPx() }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(alpha = alpha)
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .size(sizeDp)
                .offset {
                    IntOffset(
                        (offset.x - sizePx / 2).toInt(),
                        (offset.y - sizePx / 2).toInt()
                    )
                }
        ) {
            val color = Color.Yellow
            val strokeWidth = 2.dp.toPx()
            val bracketLen = 10.dp.toPx()
            
            // Crosshair lines
            drawLine(color, Offset(0f, size.height/2), Offset(size.width*0.3f, size.height/2), strokeWidth)
            drawLine(color, Offset(size.width*0.7f, size.height/2), Offset(size.width, size.height/2), strokeWidth)
            drawLine(color, Offset(size.width/2, 0f), Offset(size.width/2, size.height*0.3f), strokeWidth)
            drawLine(color, Offset(size.width/2, size.height*0.7f), Offset(size.width/2, size.height), strokeWidth)
            
            // Corners
            drawLine(color, Offset(0f, 0f), Offset(bracketLen, 0f), strokeWidth)
            drawLine(color, Offset(0f, 0f), Offset(0f, bracketLen), strokeWidth)
            drawLine(color, Offset(size.width, 0f), Offset(size.width-bracketLen, 0f), strokeWidth)
            drawLine(color, Offset(size.width, 0f), Offset(size.width, bracketLen), strokeWidth)
            drawLine(color, Offset(0f, size.height), Offset(bracketLen, size.height), strokeWidth)
            drawLine(color, Offset(0f, size.height), Offset(0f, size.height-bracketLen), strokeWidth)
            drawLine(color, Offset(size.width, size.height), Offset(size.width-bracketLen, size.height), strokeWidth)
            drawLine(color, Offset(size.width, size.height), Offset(size.width, size.height-bracketLen), strokeWidth)
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
                
                // Scale back to view coordinates for UI
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
