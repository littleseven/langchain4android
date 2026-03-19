package com.example.picme.ui.screens

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.picme.R
import com.example.picme.data.model.MediaAsset
import com.example.picme.data.model.MediaType
import com.example.picme.ui.viewmodel.MediaViewModel
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

enum class EditMode { DOODLE, MOSAIC }

data class DrawAction(
    val path: Path,
    val mode: EditMode,
    val color: Int = android.graphics.Color.RED,
    val strokeWidth: Float = 50f
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageEditScreen(
    asset: MediaAsset,
    viewModel: MediaViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var mosaicBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var mosaicShader by remember { mutableStateOf<BitmapShader?>(null) }
    
    val actions = remember { mutableStateListOf<DrawAction>() }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var currentMode by remember { mutableStateOf(EditMode.DOODLE) }
    var currentColor by remember { mutableIntStateOf(android.graphics.Color.RED) }

    // Recomposition trigger
    var drawTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(asset.uri) {
        val uri = asset.uri.toUri()
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        val options = BitmapFactory.Options().apply { inMutable = true }
        val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
        originalBitmap = bitmap
        
        bitmap?.let {
            // Create mosaic bitmap (pixelated)
            val scaleFactor = 0.04f
            val small = Bitmap.createScaledBitmap(it, (it.width * scaleFactor).toInt().coerceAtLeast(1), (it.height * scaleFactor).toInt().coerceAtLeast(1), false)
            val mosaic = Bitmap.createScaledBitmap(small, it.width, it.height, false)
            mosaicBitmap = mosaic
            mosaicShader = BitmapShader(mosaic, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.edit)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { if (actions.isNotEmpty()) actions.removeAt(actions.size - 1) },
                        enabled = actions.isNotEmpty()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.undo))
                    }
                    IconButton(onClick = {
                        originalBitmap?.let { base ->
                            val result = applyActions(base, actions)
                            saveEditedImage(context, result, viewModel)
                            onDismiss()
                        }
                    }) {
                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save))
                    }
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                if (currentMode == EditMode.DOODLE) {
                    ColorSelector(selectedColor = currentColor) { currentColor = it }
                }
                BottomAppBar {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        FilterChip(
                            selected = currentMode == EditMode.DOODLE,
                            onClick = { currentMode = EditMode.DOODLE },
                            label = { Text(stringResource(R.string.doodle)) },
                            leadingIcon = { Icon(Icons.Default.Brush, contentDescription = null) }
                        )
                        FilterChip(
                            selected = currentMode == EditMode.MOSAIC,
                            onClick = { currentMode = EditMode.MOSAIC },
                            label = { Text(stringResource(R.string.mosaic)) },
                            leadingIcon = { Icon(Icons.Default.BlurOn, contentDescription = null) }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            originalBitmap?.let { bitmap ->
                var canvasWidth by remember { mutableFloatStateOf(0f) }
                var canvasHeight by remember { mutableFloatStateOf(0f) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .onGloballyPositioned { 
                            canvasWidth = it.size.width.toFloat()
                            canvasHeight = it.size.height.toFloat()
                        }
                        .pointerInput(currentMode, currentColor, canvasWidth, canvasHeight) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val bitmapRatio = bitmap.width.toFloat() / bitmap.height
                                    val canvasRatio = canvasWidth / canvasHeight
                                    
                                    val (drawW, drawH, left, top) = if (bitmapRatio > canvasRatio) {
                                        val h = canvasWidth / bitmapRatio
                                        listOf(canvasWidth, h, 0f, (canvasHeight - h) / 2)
                                    } else {
                                        val w = canvasHeight * bitmapRatio
                                        listOf(w, canvasHeight, (canvasWidth - w) / 2, 0f)
                                    }

                                    val scaleX = bitmap.width / drawW
                                    val scaleY = bitmap.height / drawH
                                    
                                    val path = Path()
                                    path.moveTo((offset.x - left) * scaleX, (offset.y - top) * scaleY)
                                    currentPath = path
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val bitmapRatio = bitmap.width.toFloat() / bitmap.height
                                    val canvasRatio = canvasWidth / canvasHeight
                                    
                                    val (drawW, drawH, left, top) = if (bitmapRatio > canvasRatio) {
                                        val h = canvasWidth / bitmapRatio
                                        listOf(canvasWidth, h, 0f, (canvasHeight - h) / 2)
                                    } else {
                                        val w = canvasHeight * bitmapRatio
                                        listOf(w, canvasHeight, (canvasWidth - w) / 2, 0f)
                                    }

                                    val scaleX = bitmap.width / drawW
                                    val scaleY = bitmap.height / drawH
                                    
                                    currentPath?.lineTo((change.position.x - left) * scaleX, (change.position.y - top) * scaleY)
                                    drawTrigger++
                                },
                                onDragEnd = {
                                    currentPath?.let {
                                        actions.add(DrawAction(it, currentMode, currentColor))
                                    }
                                    currentPath = null
                                }
                            )
                        }
                ) {
                    val trigger = drawTrigger
                    ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                        drawContext.canvas.nativeCanvas.apply {
                            val bitmapRatio = bitmap.width.toFloat() / bitmap.height
                            val canvasRatio = size.width / size.height
                            
                            val drawRect = if (bitmapRatio > canvasRatio) {
                                val h = size.width / bitmapRatio
                                android.graphics.RectF(0f, (size.height - h) / 2, size.width, (size.height + h) / 2)
                            } else {
                                val w = size.height * bitmapRatio
                                android.graphics.RectF((size.width - w) / 2, 0f, (size.width + w) / 2, size.height)
                            }

                            val scale = drawRect.width() / bitmap.width
                            save()
                            translate(drawRect.left, drawRect.top)
                            scale(scale, scale)

                            // Draw original bitmap
                            drawBitmap(bitmap, 0f, 0f, null)
                            
                            // Draw existing actions
                            actions.forEach { action ->
                                drawActionOnCanvas(this, action, null, bitmap)
                            }
                            
                            // Draw current active path
                            currentPath?.let {
                                drawActionOnCanvas(this, DrawAction(it, currentMode, currentColor), null, bitmap)
                            }
                            
                            restore()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ColorSelector(selectedColor: Int, onColorSelected: (Int) -> Unit) {
    val colors = listOf(
        android.graphics.Color.RED,
        android.graphics.Color.GREEN,
        android.graphics.Color.BLUE,
        android.graphics.Color.YELLOW,
        android.graphics.Color.WHITE,
        android.graphics.Color.BLACK,
        android.graphics.Color.MAGENTA,
        android.graphics.Color.CYAN
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(colors) { color ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(
                        width = 2.dp,
                        color = if (selectedColor == color) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = CircleShape
                    )
                    .clickable { onColorSelected(color) }
            )
        }
    }
}

private fun drawActionOnCanvas(canvas: Canvas, action: DrawAction, mosaicShader: BitmapShader?, originalBitmap: Bitmap?) {
    val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = action.strokeWidth
        isAntiAlias = true
    }

    if (action.mode == EditMode.DOODLE) {
        paint.color = action.color
        canvas.drawPath(action.path, paint)
    } else if (action.mode == EditMode.MOSAIC && originalBitmap != null) {
        // 使用 PathMeasure 沿路径绘制马赛克效果
        val pathMeasure = android.graphics.PathMeasure(action.path, false)
        val length = pathMeasure.length
        
        if (length > 0) {
            val pos = FloatArray(2)
            val tan = FloatArray(2)
            val step = action.strokeWidth / 3 // 减小步进值使马赛克更连续
            var distance = 0f
            
            while (distance < length) {
                pathMeasure.getPosTan(distance, pos, tan)
                
                // 计算当前点的法线方向
                val normalX = -tan[1]
                val normalY = tan[0]
                
                // 创建圆形马赛克区域
                val radius = action.strokeWidth / 2
                
                // 计算采样区域（在原图中对应的区域）
                val sampleRadius = radius * 0.5f // 缩小采样区域以增强马赛克效果
                
                // 创建圆形路径
                val circlePath = Path()
                circlePath.addCircle(pos[0], pos[1], radius, Path.Direction.CW)
                
                // 创建 BitmapShader 用于当前圆形区域
                val currentShader = BitmapShader(
                    originalBitmap,
                    Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP
                )
                
                // 创建 Matrix 来实现像素化效果
                val matrix = android.graphics.Matrix()
                // 缩小采样
                matrix.preScale(0.1f, 0.1f)
                // 再放大回来
                matrix.postScale(10f, 10f)
                // 偏移到当前位置
                matrix.preTranslate(pos[0] - sampleRadius, pos[1] - sampleRadius)
                
                currentShader.setLocalMatrix(matrix)
                
                paint.shader = currentShader
                canvas.drawPath(circlePath, paint)
                
                distance += step
            }
        }
    }
}

private fun applyActions(base: Bitmap, actions: List<DrawAction>): Bitmap {
    val result = base.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(result)
    actions.forEach { action ->
        drawActionOnCanvas(canvas, action, null, base)
    }
    return result
}

private fun saveEditedImage(context: Context, bitmap: Bitmap, viewModel: MediaViewModel) {
    val name = "EDIT_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicMe")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    uri?.let {
        context.contentResolver.openOutputStream(it)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
        }
        val asset = MediaAsset(
            uri = it.toString(),
            type = MediaType.PHOTO,
            captureDate = System.currentTimeMillis(),
            fileName = name
        )
        viewModel.insertMedia(asset)
    }
}
