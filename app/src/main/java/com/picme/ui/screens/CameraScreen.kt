package com.picme.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.picme.PicMeApplication
import com.picme.R
import com.picme.data.model.MediaAsset
import com.picme.data.model.MediaType
import com.picme.domain.BeautySettings
import com.picme.domain.ImageProcessor
import com.picme.domain.ImageProcessorImpl
import com.picme.ui.components.*
import com.picme.ui.model.FilterType
import com.picme.ui.theme.PicMeTheme
import com.picme.ui.viewmodel.MediaViewModel
import com.picme.ui.viewmodel.MediaViewModelFactory
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.*
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDebug: () -> Unit,
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
        CameraContent(viewModel, onNavigateToGallery, onNavigateToSettings, onNavigateToDebug)
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
    onNavigateToSettings: () -> Unit,
    onNavigateToDebug: () -> Unit
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
    var aspectRatio by remember { mutableIntStateOf(AspectRatio.RATIO_4_3) }

    var showFilterSelector by remember { mutableStateOf(false) }
    var showBeautySelector by remember { mutableStateOf(false) }
    var showRatioSelector by remember { mutableStateOf(false) }
    var showCameraInfo by remember { mutableStateOf(false) }
    
    // Pro Mode States
    var exposureCompensation by remember { mutableIntStateOf(0) }
    var exposureRange by remember { mutableStateOf(-2..2) }
    var whiteBalanceMode by remember { mutableIntStateOf(0) }

    val previewView = remember { PreviewView(context) }
    
    // Camera Use Cases
    val imageCapture = remember(aspectRatio) { 
        ImageCapture.Builder()
            .setTargetAspectRatio(aspectRatio)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build() 
    }
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

    LaunchedEffect(lensFacing, captureMode, aspectRatio) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = androidx.camera.core.Preview.Builder()
            .setTargetAspectRatio(aspectRatio)
            .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetAspectRatio(aspectRatio)
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
            
            // Get exposure range
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
        showFilterSelector = showFilterSelector,
        showBeautySelector = showBeautySelector,
        showRatioSelector = showRatioSelector,
        showCameraInfo = showCameraInfo,
        beautySettings = beautySettings,
        aspectRatio = aspectRatio,
        lensFacing = lensFacing,
        exposureCompensation = exposureCompensation,
        exposureRange = exposureRange,
        whiteBalanceMode = whiteBalanceMode,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToDebug = onNavigateToDebug,
        onFlipCamera = { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK },
        onToggleBeauty = { showBeautySelector = !showBeautySelector; showFilterSelector = false; showRatioSelector = false },
        onToggleFilter = { showFilterSelector = !showFilterSelector; showBeautySelector = false; showRatioSelector = false },
        onToggleRatio = { showRatioSelector = !showRatioSelector; showFilterSelector = false; showBeautySelector = false },
        onToggleCameraInfo = { showCameraInfo = !showCameraInfo },
        onZoomPresetClick = { cameraControl?.setZoomRatio(it) },
        onExposureChange = { 
            exposureCompensation = it
            cameraControl?.setExposureCompensationIndex(it)
        },
        onWhiteBalanceChange = { whiteBalanceMode = it },
        onGalleryClick = onNavigateToGallery,
        onCaptureClick = {
            if (captureMode == MediaType.PHOTO || captureMode == MediaType.PORTRAIT || captureMode == MediaType.PRO) {
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
        onFilterSelected = { selectedFilter = it },
        onBeautySettingsChanged = { beautySettings = it },
        onRatioSelected = { aspectRatio = it; showRatioSelector = false },
        onDismissPanels = { showFilterSelector = false; showBeautySelector = false; showRatioSelector = false }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraPreviewContent(
    previewView: @Composable () -> Unit,
    selectedFilter: FilterType,
    facePoint: Offset?,
    focusIndicatorAlpha: Float,
    lastMedia: MediaAsset?,
    zoomRatio: Float,
    captureMode: MediaType,
    isRecording: Boolean,
    showFilterSelector: Boolean,
    showBeautySelector: Boolean,
    showRatioSelector: Boolean,
    showCameraInfo: Boolean,
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
    onZoomPresetClick: (Float) -> Unit,
    onExposureChange: (Int) -> Unit,
    onWhiteBalanceChange: (Int) -> Unit,
    onGalleryClick: () -> Unit,
    onCaptureClick: () -> Unit,
    onModeChange: (MediaType) -> Unit,
    onFilterSelected: (FilterType) -> Unit,
    onBeautySettingsChanged: (BeautySettings) -> Unit,
    onRatioSelected: (Int) -> Unit,
    onDismissPanels: () -> Unit
) {
    val isAnyPanelOpen = showFilterSelector || showBeautySelector || showRatioSelector

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1. Camera Preview
        previewView()

        // 2. Filter Overlay
        if (selectedFilter != FilterType.NONE) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(color = Color.White, alpha = 0.15f, colorFilter = ColorFilter.colorMatrix(selectedFilter.getColorMatrix()))
            }
        }

        // 3. Face Focus Indicator
        facePoint?.let { FaceFocusIndicator(offset = it, alpha = focusIndicatorAlpha) }

        // 4. Camera Info Overlay
        if (showCameraInfo) {
            CameraInfoOverlay(
                lensFacing = lensFacing,
                zoomRatio = zoomRatio,
                aspectRatio = aspectRatio,
                filter = selectedFilter,
                beautySettings = beautySettings,
                exposureCompensation = exposureCompensation,
                whiteBalanceMode = whiteBalanceMode,
                modifier = Modifier.align(Alignment.TopStart).padding(top = 100.dp, start = 16.dp)
            )
        }

        // 5. Left Controls (Settings & Debug)
        CameraLeftControls(
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToDebug = onNavigateToDebug,
            modifier = Modifier.align(Alignment.TopStart)
        )

        // 6. Right Controls (Capture Configs)
        CameraRightControls(
            onToggleBeauty = onToggleBeauty,
            onToggleFilter = onToggleFilter,
            onToggleRatio = onToggleRatio,
            onToggleCameraInfo = onToggleCameraInfo,
            isBeautySelected = showBeautySelector,
            isFilterSelected = showFilterSelector,
            isRatioSelected = showRatioSelector,
            isCameraInfoSelected = showCameraInfo,
            currentRatio = aspectRatio,
            modifier = Modifier.align(Alignment.TopEnd)
        )

        // 7. Pro Mode Controls
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

        // 8. Bottom Controls
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

        // 9. Panel (Filters/Beauty/Ratio)
        AnimatedVisibility(
            visible = isAnyPanelOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val title = when {
                showFilterSelector -> stringResource(R.string.filters)
                showBeautySelector -> stringResource(R.string.beauty)
                else -> stringResource(R.string.aspect_ratio)
            }
            
            ControlPanel(
                title = title,
                onDismiss = onDismissPanels
            ) {
                when {
                    showFilterSelector -> FilterSelector(selectedFilter) { onFilterSelected(it) }
                    showBeautySelector -> BeautySelector(beautySettings) { onBeautySettingsChanged(it) }
                    showRatioSelector -> RatioSelector(aspectRatio) { onRatioSelected(it) }
                }
            }
        }
    }
}

@Composable
fun CameraLeftControls(
    onNavigateToSettings: () -> Unit,
    onNavigateToDebug: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        ControlIconItem(icon = Icons.Rounded.Settings, onClick = onNavigateToSettings)
        ControlIconItem(icon = Icons.Rounded.BugReport, onClick = onNavigateToDebug)
    }
}

@Composable
fun CameraRightControls(
    onToggleBeauty: () -> Unit,
    onToggleFilter: () -> Unit,
    onToggleRatio: () -> Unit,
    onToggleCameraInfo: () -> Unit,
    isBeautySelected: Boolean,
    isFilterSelected: Boolean,
    isRatioSelected: Boolean,
    isCameraInfoSelected: Boolean,
    currentRatio: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        ControlIconItem(
            icon = Icons.Rounded.Info, 
            isSelected = isCameraInfoSelected, 
            onClick = onToggleCameraInfo
        )
        
        val ratioIcon = if (currentRatio == AspectRatio.RATIO_4_3) Icons.Rounded.Crop32 else Icons.Rounded.Crop169
        ControlIconItem(
            icon = ratioIcon, 
            isSelected = isRatioSelected, 
            onClick = onToggleRatio
        )

        ControlIconItem(
            icon = Icons.Rounded.Face, 
            isSelected = isBeautySelected, 
            onClick = onToggleBeauty
        )
        
        ControlIconItem(
            icon = Icons.Rounded.AutoFixHigh, 
            isSelected = isFilterSelected, 
            onClick = onToggleFilter
        )
    }
}

@Composable
private fun ControlIconItem(
    icon: ImageVector,
    isSelected: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.3f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun CameraInfoOverlay(
    lensFacing: Int,
    zoomRatio: Float,
    aspectRatio: Int,
    filter: FilterType,
    beautySettings: BeautySettings,
    exposureCompensation: Int,
    whiteBalanceMode: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.widthIn(max = 200.dp),
        color = Color.Black.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.camera_info),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            
            // Device Group
            InfoGroup(title = stringResource(R.string.info_group_device)) {
                InfoItem(
                    label = stringResource(R.string.info_lens_facing),
                    value = if (lensFacing == CameraSelector.LENS_FACING_BACK) stringResource(R.string.info_back) else stringResource(R.string.info_front)
                )
                InfoItem(
                    label = stringResource(R.string.info_zoom),
                    value = String.format(Locale.US, "%.1fx", zoomRatio)
                )
            }
            
            // Settings Group
            InfoGroup(title = stringResource(R.string.info_group_settings)) {
                InfoItem(
                    label = stringResource(R.string.info_aspect_ratio),
                    value = if (aspectRatio == AspectRatio.RATIO_4_3) "4:3" else "16:9"
                )
                InfoItem(
                    label = stringResource(R.string.info_filter),
                    value = stringResource(filter.displayNameRes)
                )
                InfoItem(
                    label = stringResource(R.string.info_beauty),
                    value = String.format(Locale.US, "%d%%", (beautySettings.smoothing * 100).toInt())
                )
                if (exposureCompensation != 0) {
                    InfoItem(
                        label = stringResource(R.string.ev),
                        value = if (exposureCompensation > 0) "+$exposureCompensation" else exposureCompensation.toString()
                    )
                }
                if (whiteBalanceMode != 0) {
                    val wbLabel = when (whiteBalanceMode) {
                        1 -> stringResource(R.string.wb_sunny)
                        2 -> stringResource(R.string.wb_cloudy)
                        3 -> stringResource(R.string.wb_incandescent)
                        4 -> stringResource(R.string.wb_fluorescent)
                        else -> stringResource(R.string.wb_auto)
                    }
                    InfoItem(label = stringResource(R.string.wb), value = wbLabel)
                }
            }
        }
    }
}

@Composable
fun ProModeControls(
    exposure: Int,
    exposureRange: IntRange,
    onExposureChange: (Int) -> Unit,
    whiteBalance: Int,
    onWhiteBalanceChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedParam by remember { mutableStateOf("EV") }
    
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Parameter Value Slider / Selector
        Box(modifier = Modifier.padding(bottom = 12.dp).height(50.dp), contentAlignment = Alignment.Center) {
            when (selectedParam) {
                "EV" -> {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        contentPadding = PaddingValues(horizontal = 140.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items((exposureRange.first..exposureRange.last).toList()) { value ->
                            val isSelected = value == exposure
                            Text(
                                text = if (value > 0) "+$value" else value.toString(),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = if (isSelected) 18.sp else 14.sp,
                                modifier = Modifier.clickable { onExposureChange(value) }
                            )
                        }
                    }
                }
                "WB" -> {
                    // White Balance modes mapping: 0:Auto, 1:Sunny, 2:Cloudy, 3:Incandescent, 4:Fluorescent
                    val wbModes = listOf(
                        0 to stringResource(R.string.wb_auto),
                        1 to stringResource(R.string.wb_sunny),
                        2 to stringResource(R.string.wb_cloudy),
                        3 to stringResource(R.string.wb_incandescent),
                        4 to stringResource(R.string.wb_fluorescent)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        contentPadding = PaddingValues(horizontal = 120.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(wbModes) { (mode, label) ->
                            val isSelected = mode == whiteBalance
                            Text(
                                text = label.uppercase(),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = if (isSelected) 16.sp else 13.sp,
                                modifier = Modifier.clickable { onWhiteBalanceChange(mode) }
                            )
                        }
                    }
                }
                else -> {
                    Text(text = stringResource(R.string.auto).uppercase(), color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                }
            }
        }

        // Parameter Type Selector
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProParamItem(label = stringResource(R.string.ev), isSelected = selectedParam == "EV") { selectedParam = "EV" }
            ProParamItem(label = stringResource(R.string.iso), isSelected = selectedParam == "ISO") { selectedParam = "ISO" }
            ProParamItem(label = stringResource(R.string.shutter), isSelected = selectedParam == "S") { selectedParam = "S" }
            ProParamItem(label = stringResource(R.string.wb), isSelected = selectedParam == "WB") { selectedParam = "WB" }
            ProParamItem(label = stringResource(R.string.focus), isSelected = selectedParam == "AF") { selectedParam = "AF" }
        }
    }
}

@Composable
fun ProParamItem(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 4.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp
        )
        if (isSelected) {
            Box(modifier = Modifier.size(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        } else {
            Spacer(modifier = Modifier.height(3.dp))
        }
    }
}

@Composable
private fun InfoGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title.uppercase(), color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = "$label:", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        Text(text = value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun CameraBottomControls(
    lastMedia: MediaAsset?,
    zoomRatio: Float,
    captureMode: MediaType,
    isRecording: Boolean,
    isAnyPanelOpen: Boolean,
    onZoomPresetClick: (Float) -> Unit,
    onGalleryClick: () -> Unit,
    onCaptureClick: () -> Unit,
    onFlipCamera: () -> Unit,
    onModeChange: (MediaType) -> Unit,
    modifier: Modifier = Modifier
) {
    val zoomPresets = listOf(0.6f, 1f, 2.6f, 5f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Zoom Selectors (Hidden in PRO mode or when panel open to avoid overlap)
        AnimatedVisibility(
            visible = captureMode != MediaType.PRO && !isAnyPanelOpen,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
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
        }
        
        // 2. Capture Main Row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GalleryThumbnail(
                lastMedia = lastMedia,
                onClick = onGalleryClick
            )
            CameraCaptureButton(
                isRecording = isRecording,
                mode = captureMode,
                onClick = onCaptureClick
            )
            // Flip Camera Button (Symmetric with GalleryThumbnail)
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { onFlipCamera() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Cached,
                    contentDescription = "Flip Camera",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // 3. Mode Switcher
        Row(modifier = Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            MediaType.entries.forEach { mode ->
                val label = when (mode) {
                    MediaType.PHOTO -> stringResource(R.string.photo)
                    MediaType.VIDEO -> stringResource(R.string.video)
                    MediaType.PORTRAIT -> stringResource(R.string.portrait)
                    MediaType.PRO -> stringResource(R.string.pro)
                }
                Text(
                    text = label.uppercase(),
                    color = if (captureMode == mode) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                    fontWeight = if (captureMode == mode) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onModeChange(mode) }
                )
            }
        }
    }
}

@Composable
fun RatioSelector(
    currentRatio: Int,
    onRatioSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        RatioOption(
            label = stringResource(R.string.ratio_4_3),
            isSelected = currentRatio == AspectRatio.RATIO_4_3,
            onClick = { onRatioSelected(AspectRatio.RATIO_4_3) }
        )
        RatioOption(
            label = stringResource(R.string.ratio_16_9),
            isSelected = currentRatio == AspectRatio.RATIO_16_9,
            onClick = { onRatioSelected(AspectRatio.RATIO_16_9) }
        )
    }
}

@Composable
fun RatioOption(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
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
            showFilterSelector = false,
            showBeautySelector = false,
            showRatioSelector = false,
            showCameraInfo = true,
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
            onZoomPresetClick = {},
            onExposureChange = {},
            onWhiteBalanceChange = {},
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
