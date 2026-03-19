package com.example.picme.ui.screens

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.example.picme.PicMeApplication
import com.example.picme.R
import com.example.picme.data.model.MediaAsset
import com.example.picme.data.model.MediaType
import com.example.picme.ui.model.FilterType
import com.example.picme.ui.viewmodel.MediaViewModel
import com.example.picme.ui.viewmodel.MediaViewModelFactory
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.text.SimpleDateFormat
import java.util.*
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
        ) + if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else emptyList()
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

data class BeautySettings(
    val smoothing: Float = 0f,
    val slimFace: Float = 0f,
    val bigEyes: Float = 0f,
    val youth: Float = 0f
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
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
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    
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
    var cameraInfo: CameraInfo? by remember { mutableStateOf(null) }
    
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    val zoomPresets = listOf(0.6f, 1f, 2.6f, 5f)

    // Face Tracking States
    var facePoint by remember { mutableStateOf<Offset?>(null) }
    var showFocusIndicator by remember { mutableStateOf(false) }
    val focusIndicatorAlpha = remember { Animatable(0f) }

    val faceDetector = remember {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
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
                onFocusStabilized = {
                    showFocusIndicator = false
                }
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
            cameraInfo = camera.cameraInfo
            cameraInfo?.zoomState?.observe(lifecycleOwner) { state -> 
                zoomRatio = state.zoomRatio 
            }
        } catch (e: Exception) { Log.e("CameraScreen", "Use case binding failed", e) }
    }

    LaunchedEffect(showFocusIndicator) {
        if (showFocusIndicator) {
            focusIndicatorAlpha.animateTo(1f, animationSpec = tween(200))
        } else {
            focusIndicatorAlpha.animateTo(0f, animationSpec = tween(500))
        }
    }

    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown(); faceDetector.close(); sound.release() } }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        
        // Face Tracking Indicator
        facePoint?.let { point ->
            FaceFocusIndicator(offset = point, alpha = focusIndicatorAlpha.value)
        }

        // Filter Preview Overlay
        if (selectedFilter != FilterType.NONE) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = Color.White,
                    alpha = 0.15f, 
                    colorFilter = ColorFilter.colorMatrix(selectedFilter.getColorMatrix())
                )
            }
        }

        // Right Sidebar Controls (Settings, Flip, Beauty, Filter)
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 16.dp, end = 16.dp)
                .align(Alignment.TopEnd),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SideBarItem(
                icon = Icons.Default.Settings,
                contentDescription = "Settings",
                onClick = onNavigateToSettings
            )

            SideBarItem(
                icon = Icons.Rounded.FlipCameraAndroid,
                contentDescription = "Flip",
                onClick = { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK }
            )

            SideBarItem(
                icon = Icons.Default.Face,
                contentDescription = "Beauty",
                isSelected = showBeautySelector,
                onClick = { 
                    showBeautySelector = !showBeautySelector 
                    showFilterSelector = false
                }
            )

            SideBarItem(
                icon = Icons.Default.AutoFixHigh,
                contentDescription = "Filters",
                isSelected = showFilterSelector,
                onClick = { 
                    showFilterSelector = !showFilterSelector 
                    showBeautySelector = false
                }
            )
        }

        // Bottom Area
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Zoom Presets
            Row(
                modifier = Modifier.padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                zoomPresets.forEach { preset ->
                    val isSelected = (zoomRatio >= preset - 0.05f && zoomRatio <= preset + 0.05f) || (preset == 1f && zoomRatio < 1.1f && zoomRatio > 0.9f)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f))
                            .clickable { 
                                zoomRatio = preset
                                cameraControl?.setZoomRatio(preset) 
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val text = if (preset < 1f) ".6" else preset.toInt().toString()
                        Text(text = text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            
            // Fixed Bottom Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery Thumbnail
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.DarkGray)
                        .clickable(onClick = onNavigateToGallery)
                ) {
                    if (lastMedia != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(lastMedia.uri)
                                .decoderFactory(VideoFrameDecoder.Factory())
                                .build(),
                            contentDescription = "Gallery",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery", tint = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                }

                // Capture Button
                CaptureButton(
                    isRecording = isRecording,
                    mode = captureMode,
                    onClick = {
                        if (captureMode == MediaType.PHOTO) {
                            sound.play(MediaActionSound.SHUTTER_CLICK)
                            takePhoto(context, imageCapture, viewModel, selectedFilter, beautySettings, lensFacing)
                        } else {
                            if (isRecording) {
                                recording?.stop()
                                isRecording = false
                            } else {
                                isRecording = true
                                recording = startVideoRecording(context, videoCapture, viewModel) {
                                    isRecording = false
                                }
                            }
                        }
                    }
                )
                
                // Spacer to keep CaptureButton centered
                Spacer(modifier = Modifier.size(56.dp))
            }

            // Mode Selector
            Row(
                modifier = Modifier.padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                MediaType.entries.forEach { mode ->
                    val isSelected = captureMode == mode
                    Text(
                        text = mode.name,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable { captureMode = mode }
                    )
                }
            }
        }

        // Expanded Panels
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
fun SideBarItem(icon: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String, isSelected: Boolean = false, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp).background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.3f), CircleShape)
    ) { 
        Icon(icon, contentDescription = contentDescription, tint = Color.White)
    }
}

@Composable
fun CaptureButton(isRecording: Boolean, mode: MediaType, onClick: () -> Unit) {
    val color = if (mode == MediaType.VIDEO) Color.Red else Color.White
    Box(
        modifier = Modifier
            .size(80.dp)
            .border(width = 4.dp, color = color.copy(alpha = 0.5f), shape = CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isRecording) {
            Box(modifier = Modifier.size(24.dp).background(Color.White, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
fun ControlPanel(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .navigationBarsPadding()
                .padding(vertical = 24.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, color = Color.White, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
                }
                content()
            }
        }
    }
}

@Composable
fun BeautySelector(settings: BeautySettings, onSettingsChanged: (BeautySettings) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BeautySlider(label = stringResource(R.string.smoothing), value = settings.smoothing) { onSettingsChanged(settings.copy(smoothing = it)) }
        BeautySlider(label = stringResource(R.string.slim_face), value = settings.slimFace) { onSettingsChanged(settings.copy(slimFace = it)) }
        BeautySlider(label = stringResource(R.string.big_eyes), value = settings.bigEyes) { onSettingsChanged(settings.copy(bigEyes = it)) }
        BeautySlider(label = stringResource(R.string.youth), value = settings.youth) { onSettingsChanged(settings.copy(youth = it)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeautySlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.width(80.dp), fontSize = 14.sp)
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        )
    }
}

@Composable
fun FaceFocusIndicator(offset: Offset, alpha: Float) {
    Box(modifier = Modifier.fillMaxSize().graphicsLayer(alpha = alpha)) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(60.dp).offset(x = offset.x.dp - 30.dp, y = offset.y.dp - 30.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val strokeWidth = 2.dp.toPx()
            val color = Color.Yellow
            drawLine(color, Offset(0f, center.y), Offset(size.width * 0.3f, center.y), strokeWidth)
            drawLine(color, Offset(size.width * 0.7f, center.y), Offset(size.width, center.y), strokeWidth)
            drawLine(color, Offset(center.x, 0f), Offset(center.x, size.height * 0.3f), strokeWidth)
            drawLine(color, Offset(center.x, size.height * 0.7f), Offset(center.x, size.height), strokeWidth)
            val bracketLen = 10.dp.toPx()
            drawLine(color, Offset(0f, 0f), Offset(bracketLen, 0f), strokeWidth)
            drawLine(color, Offset(0f, 0f), Offset(0f, bracketLen), strokeWidth)
            drawLine(color, Offset(size.width, 0f), Offset(size.width - bracketLen, 0f), strokeWidth)
            drawLine(color, Offset(size.width, 0f), Offset(size.width, bracketLen), strokeWidth)
            drawLine(color, Offset(0f, size.height), Offset(bracketLen, size.height), strokeWidth)
            drawLine(color, Offset(0f, size.height), Offset(0f, size.height - bracketLen), strokeWidth)
            drawLine(color, Offset(size.width, size.height), Offset(size.width - bracketLen, size.height), strokeWidth)
            drawLine(color, Offset(size.width, size.height), Offset(size.width, size.height - bracketLen), strokeWidth)
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
        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val bounds = face.boundingBox
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    onFaceDetected(point.x * previewView.width, point.y * previewView.height) 
                    val action = FocusMeteringAction.Builder(point).setAutoCancelDuration(2, java.util.concurrent.TimeUnit.SECONDS).build()
                    val future = cameraControl?.startFocusAndMetering(action)
                    future?.addListener({ try { if (future.get().isFocusSuccessful) onFocusStabilized() } catch (e: Exception) { onFocusStabilized() } }, ContextCompat.getMainExecutor(previewView.context))
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    } else {
        imageProxy.close()
    }
}

@Composable
fun FilterSelector(selectedFilter: FilterType, onFilterSelected: (FilterType) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 24.dp)) {
        items(FilterType.entries) { filter ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onFilterSelected(filter) }) {
                Box(modifier = Modifier.size(60.dp).clip(CircleShape).background(Color.DarkGray).border(width = 3.dp, color = if (selectedFilter == filter) MaterialTheme.colorScheme.primary else Color.Transparent, shape = CircleShape), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) { drawRect(color = Color.LightGray, colorFilter = ColorFilter.colorMatrix(filter.getColorMatrix())) }
                }
                Text(text = stringResource(filter.displayNameRes), color = if (selectedFilter == filter) MaterialTheme.colorScheme.primary else Color.White, fontSize = 12.sp, fontWeight = if (selectedFilter == filter) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

private fun takePhoto(
    context: Context, 
    imageCapture: ImageCapture, 
    viewModel: MediaViewModel, 
    filter: FilterType, 
    beauty: BeautySettings,
    lensFacing: Int
) {
    val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
    imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val rotationDegrees = image.imageInfo.rotationDegrees
            val originalBitmap = image.toBitmap()
            image.close()

            // Step 1: Physical Rotation Fix
            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    postScale(-1f, 1f) // Mirror fix for front cam
                }
            }
            val rotatedBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)

            // Step 2: High-res Face Detection for Beauty
            val faceDetector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .build()
            )
            
            val inputImage = InputImage.fromBitmap(rotatedBitmap, 0)
            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    val finalBitmap = applyBeautyAndFilter(rotatedBitmap, filter, beauty, faces)
                    saveBitmapToMediaStore(context, finalBitmap, name, viewModel)
                }
                .addOnFailureListener {
                    saveBitmapToMediaStore(context, rotatedBitmap, name, viewModel)
                }
        }
        override fun onError(exc: ImageCaptureException) { Log.e("CameraScreen", "Photo capture failed", exc) }
    })
}

private fun applyBeautyAndFilter(source: Bitmap, filter: FilterType, beauty: BeautySettings, faces: List<Face>): Bitmap {
    var processed = source.copy(Bitmap.Config.ARGB_8888, true)
    
    // 1. Smoothing (Skin)
    if (beauty.smoothing > 0f) {
        processed = applySmoothing(processed, beauty.smoothing)
    }

    // 2. Mesh Warp (Slim Face & Big Eyes)
    if (faces.isNotEmpty() && (beauty.slimFace > 0f || beauty.bigEyes > 0f)) {
        processed = applyMeshWarp(processed, faces, beauty)
    }

    // 3. Color Filter
    val output = createBitmap(processed.width, processed.height)
    val canvas = Canvas(output)
    val paint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(android.graphics.ColorMatrix(filter.getColorMatrix().values))
        // Apply Youth/Brightness tweak here if needed
        if (beauty.youth > 0f) {
            val b = beauty.youth * 25f
            val cm = android.graphics.ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, b,
                0f, 1f, 0f, 0f, b * 0.8f,
                0f, 0f, 1f, 0f, b * 0.5f,
                0f, 0f, 0f, 1f, 0f
            ))
            colorFilter = ColorMatrixColorFilter(cm)
        }
    }
    canvas.drawBitmap(processed, 0f, 0f, paint)
    return output
}

private fun applySmoothing(source: Bitmap, intensity: Float): Bitmap {
    val width = source.width
    val height = source.height
    val res = createBitmap(width, height)
    // Fast blur via scaling
    val blurred = Bitmap.createScaledBitmap(source, width / 4, height / 4, true)
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
        // Face region for slimming
        val bounds = face.boundingBox
        val centerX = bounds.centerX().toFloat()
        val chinY = (bounds.bottom - bounds.height() * 0.15f).toFloat()
        val slimRadius = bounds.width() * 0.75f
        
        // Eye landmarks for Big Eyes
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val eyeRadius = bounds.width() * 0.2f // Increased radius slightly

        for (i in 0 until count) {
            val vx = orig[i * 2 + 0]
            val vy = orig[i * 2 + 1]

            // Slim Face Logic (Inward Pull) - Increased power
            if (beauty.slimFace > 0f) {
                val dx = vx - centerX
                val dy = vy - chinY
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (dist < slimRadius) {
                    val pull = beauty.slimFace * 0.25f * (1.0f - dist / slimRadius) // Increased from 0.15f
                    verts[i * 2 + 0] -= dx * pull
                }
            }

            // Big Eyes Logic (Outward Push) - Increased power
            if (beauty.bigEyes > 0f) {
                listOfNotNull(leftEye, rightEye).forEach { eye ->
                    val dx = vx - eye.x
                    val dy = vy - eye.y
                    val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (dist < eyeRadius) {
                        val push = beauty.bigEyes * 0.4f * (1.0f - dist / eyeRadius) // Increased from 0.2f
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

private fun saveBitmapToMediaStore(context: Context, bitmap: Bitmap, name: String, viewModel: MediaViewModel) {
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicMe")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    uri?.let {
        context.contentResolver.openOutputStream(it)?.use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream) }
        saveMediaToDb(viewModel, it, MediaType.PHOTO, name)
    }
}

private fun startVideoRecording(context: Context, videoCapture: VideoCapture<Recorder>, viewModel: MediaViewModel, onFinished: () -> Unit): Recording {
    val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PicMe")
    }
    val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(contentValues).build()
    return videoCapture.output.prepareRecording(context, mediaStoreOutputOptions).withAudioEnabled().start(ContextCompat.getMainExecutor(context)) { recordEvent ->
        if (recordEvent is VideoRecordEvent.Finalize) {
            if (!recordEvent.hasError()) saveMediaToDb(viewModel, recordEvent.outputResults.outputUri, MediaType.VIDEO, name)
            onFinished()
        }
    }
}

private fun saveMediaToDb(viewModel: MediaViewModel, uri: Uri, type: MediaType, name: String) {
    val asset = MediaAsset(uri = uri.toString(), type = type, captureDate = System.currentTimeMillis(), fileName = name)
    viewModel.insertMedia(asset)
}
