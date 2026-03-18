package com.example.picme.ui.screens

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.OutputStream
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
    
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var captureMode by remember { mutableStateOf(MediaType.PHOTO) }
    var isRecording by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(FilterType.NONE) }
    
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val recorder = remember { Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build() }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    var recording: Recording? by remember { mutableStateOf(null) }
    
    var cameraControl: CameraControl? by remember { mutableStateOf(null) }
    var cameraInfo: CameraInfo? by remember { mutableStateOf(null) }
    
    var zoomState by remember { mutableStateOf(0f) }

    val faceDetector = remember {
        val options = FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
        FaceDetection.getClient(options)
    }

    LaunchedEffect(lensFacing, captureMode) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy -> processImageProxy(imageProxy, faceDetector, cameraControl, previewView) }

        try {
            cameraProvider.unbindAll()
            val camera = if (captureMode == MediaType.PHOTO) {
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture, imageAnalysis)
            } else {
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture, imageAnalysis)
            }
            cameraControl = camera.cameraControl
            cameraInfo = camera.cameraInfo
            cameraInfo?.zoomState?.observe(lifecycleOwner) { state -> zoomState = state.linearZoom }
        } catch (e: Exception) { Log.e("CameraScreen", "Use case binding failed", e) }
    }

    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown(); faceDetector.close(); sound.release() } }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        
        if (selectedFilter != FilterType.NONE) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(color = Color.White, alpha = 0f, colorFilter = ColorFilter.colorMatrix(selectedFilter.getColorMatrix()))
            }
        }

        IconButton(
            onClick = onNavigateToSettings,
            modifier = Modifier.statusBarsPadding().padding(16.dp).align(Alignment.TopEnd).background(Color.Black.copy(0.3f), CircleShape)
        ) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White) }
        
        // Zoom Slider: Perfectly mapped
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .height(240.dp)
                .width(48.dp)
                .background(Color.Black.copy(0.2f), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Slider(
                value = zoomState,
                onValueChange = { cameraControl?.setLinearZoom(it) },
                modifier = Modifier
                    .rotate(-90f)
                    .width(200.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White.copy(0.5f),
                    inactiveTrackColor = Color.White.copy(0.2f)
                ),
                thumb = { Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(Color.White)) }
            )
        }

        Column(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FilterSelector(selectedFilter, { selectedFilter = it })
            Spacer(modifier = Modifier.height(24.dp))
            Surface(shape = CircleShape, color = Color.Black.copy(0.5f), modifier = Modifier.padding(bottom = 24.dp)) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { captureMode = MediaType.PHOTO }) { Text(text = stringResource(R.string.photo), color = if (captureMode == MediaType.PHOTO) MaterialTheme.colorScheme.primary else Color.White) }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { captureMode = MediaType.VIDEO }) { Text(text = stringResource(R.string.video), color = if (captureMode == MediaType.VIDEO) MaterialTheme.colorScheme.primary else Color.White) }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK }, modifier = Modifier.size(56.dp).background(Color.Black.copy(0.3f), CircleShape)) {
                    Icon(Icons.Rounded.FlipCameraAndroid, contentDescription = null, tint = Color.White)
                }
                Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(Color.White.copy(0.5f)).clickable { 
                    sound.play(MediaActionSound.SHUTTER_CLICK)
                    takePhoto(context, imageCapture, viewModel, selectedFilter)
                }.padding(4.dp), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.fillMaxSize().clip(CircleShape).background(Color.White))
                }
                IconButton(onClick = onNavigateToGallery, modifier = Modifier.size(56.dp).background(Color.Black.copy(0.3f), CircleShape)) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color.White)
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processImageProxy(imageProxy: ImageProxy, detector: com.google.mlkit.vision.face.FaceDetector, cameraControl: CameraControl?, previewView: PreviewView) {
    imageProxy.image?.let { mediaImage ->
        detector.process(InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees))
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val bounds = faces[0].boundingBox
                    val point = previewView.meteringPointFactory.createPoint(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    } ?: imageProxy.close()
}

@Composable
fun FilterSelector(selectedFilter: FilterType, onFilterSelected: (FilterType) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(FilterType.values()) { filter ->
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onFilterSelected(filter) }
                    .border(2.dp, if (selectedFilter == filter) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = Color.LightGray, colorFilter = ColorFilter.colorMatrix(filter.getColorMatrix()))
                }
            }
        }
    }
}

private fun takePhoto(context: Context, imageCapture: ImageCapture, viewModel: MediaViewModel, filter: FilterType) {
    val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
    imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val bitmap = image.toBitmap()
            image.close()
            val filteredBitmap = if (filter == FilterType.NONE) bitmap else applyFilterToBitmap(bitmap, filter)
            saveBitmapToMediaStore(context, filteredBitmap, name, viewModel)
        }
        override fun onError(exc: ImageCaptureException) {}
    })
}

private fun applyFilterToBitmap(source: Bitmap, filter: FilterType): Bitmap {
    val bitmap = createBitmap(source.width, source.height)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    paint.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(filter.getColorMatrix().values))
    canvas.drawBitmap(source, 0f, 0f, paint)
    return bitmap
}

private fun saveBitmapToMediaStore(context: Context, bitmap: Bitmap, name: String, viewModel: MediaViewModel) {
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicMe")
    }
    context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)?.let { uri ->
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        viewModel.insertMedia(MediaAsset(uri = uri.toString(), type = MediaType.PHOTO, captureDate = System.currentTimeMillis(), fileName = name))
    }
}

private fun startVideoRecording(context: Context, videoCapture: VideoCapture<Recorder>, viewModel: MediaViewModel, onFinished: () -> Unit): Recording {
    return videoCapture.output.prepareRecording(context, MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).build()).start(ContextCompat.getMainExecutor(context)) { event -> if (event is VideoRecordEvent.Finalize) onFinished() }
}
