package com.example.picme.ui.screens

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

enum class EditMode { DOODLE, MOSAIC }

data class DrawAction(
    val path: Path,
    val mode: EditMode,
    val color: Int = android.graphics.Color.RED,
    val strokeWidth: Float = 60f
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
    var mosaicShader by remember { mutableStateOf<BitmapShader?>(null) }
    
    val actions = remember { mutableStateListOf<DrawAction>() }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var currentMode by remember { mutableStateOf(EditMode.DOODLE) }
    var currentColor by remember { mutableIntStateOf(android.graphics.Color.RED) }

    // This state is crucial for forcing recomposition during dragging
    var drawIteration by remember { mutableLongStateOf(0L) }

    val saveSuccessMsg = stringResource(R.string.save_success)
    val saveFailedMsg = stringResource(R.string.save_failed)
    val loadFailedMsg = stringResource(R.string.load_failed)

    LaunchedEffect(asset.uri) {
        withContext(Dispatchers.IO) {
            val uri = asset.uri.toUri()
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val options = BitmapFactory.Options().apply { inMutable = true }
                    val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
                    bitmap?.let {
                        originalBitmap = it
                        val blockSize = (it.width / 40f).coerceAtLeast(10f)
                        val smallW = (it.width / blockSize).toInt().coerceAtLeast(1)
                        val smallH = (it.height / blockSize).toInt().coerceAtLeast(1)
                        val small = Bitmap.createScaledBitmap(it, smallW, smallH, false)
                        val shader = BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                        val matrix = Matrix()
                        matrix.postScale(it.width.toFloat() / smallW, it.height.toFloat() / smallH)
                        shader.setLocalMatrix(matrix)
                        mosaicShader = shader
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, loadFailedMsg, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.edit)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel)) }
                },
                actions = {
                    IconButton(
                        onClick = { if (actions.isNotEmpty()) { actions.removeAt(actions.size - 1); drawIteration++ } },
                        enabled = actions.isNotEmpty()
                    ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.undo)) }
                    IconButton(
                        enabled = originalBitmap != null,
                        onClick = {
                            originalBitmap?.let { base ->
                                val result = applyActions(base, actions, mosaicShader)
                                saveEditedImage(context, result, viewModel, saveSuccessMsg, saveFailedMsg)
                                onDismiss()
                            }
                        }
                    ) { Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save)) }
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                if (currentMode == EditMode.DOODLE) { ColorSelector(selectedColor = currentColor) { currentColor = it } }
                BottomAppBar {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
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
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).background(Color.Black), contentAlignment = Alignment.Center) {
            originalBitmap?.let { bitmap ->
                var viewWidth by remember { mutableFloatStateOf(0f) }
                var viewHeight by remember { mutableFloatStateOf(0f) }

                Box(
                    modifier = Modifier.fillMaxSize()
                        .onGloballyPositioned { viewWidth = it.size.width.toFloat(); viewHeight = it.size.height.toFloat() }
                        .pointerInput(currentMode, viewWidth, viewHeight) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val (drawW, _, offsetX, offsetY) = calculateBitmapLayout(bitmap, viewWidth, viewHeight)
                                    val scale = bitmap.width / drawW
                                    val path = Path()
                                    path.moveTo((offset.x - offsetX) * scale, (offset.y - offsetY) * scale)
                                    currentPath = path
                                    drawIteration++
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val (drawW, _, offsetX, offsetY) = calculateBitmapLayout(bitmap, viewWidth, viewHeight)
                                    val scale = bitmap.width / drawW
                                    currentPath?.lineTo((change.position.x - offsetX) * scale, (change.position.y - offsetY) * scale)
                                    drawIteration++
                                },
                                onDragEnd = {
                                    currentPath?.let { actions.add(DrawAction(it, currentMode, currentColor, if (currentMode == EditMode.MOSAIC) 120f else 60f)) }
                                    currentPath = null
                                    drawIteration++
                                }
                            )
                        }
                ) {
                    ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                        @Suppress("UNUSED_VARIABLE") val iteration = drawIteration
                        drawContext.canvas.nativeCanvas.apply {
                            val (drawW, drawH, offsetX, offsetY) = calculateBitmapLayout(bitmap, size.width, size.height)
                            val scale = drawW / bitmap.width
                            val saveCount = save()
                            translate(offsetX, offsetY)
                            scale(scale, scale)
                            drawBitmap(bitmap, 0f, 0f, null)
                            actions.forEach { drawActionOnCanvas(this, it, mosaicShader) }
                            currentPath?.let { drawActionOnCanvas(this, DrawAction(it, currentMode, currentColor, if (currentMode == EditMode.MOSAIC) 120f else 60f), mosaicShader) }
                            restoreToCount(saveCount)
                        }
                    }
                }
            }
        }
    }
}

private fun calculateBitmapLayout(bitmap: Bitmap, viewW: Float, viewH: Float): FloatArray {
    val bitmapRatio = bitmap.width.toFloat() / bitmap.height
    val viewRatio = viewW / viewH
    return if (bitmapRatio > viewRatio) {
        val h = viewW / bitmapRatio
        floatArrayOf(viewW, h, 0f, (viewH - h) / 2)
    } else {
        val w = viewH * bitmapRatio
        floatArrayOf(w, viewH, (viewW - w) / 2, 0f)
    }
}

@Composable
fun ColorSelector(selectedColor: Int, onColorSelected: (Int) -> Unit) {
    val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow, Color.White, Color.Black, Color.Magenta, Color.Cyan)
    LazyRow(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(colors) { color ->
            Box(
                modifier = Modifier.padding(horizontal = 8.dp).size(32.dp).clip(CircleShape).background(color)
                    .border(width = 2.dp, color = if (selectedColor == color.toArgb()) MaterialTheme.colorScheme.primary else Color.Transparent, shape = CircleShape)
                    .clickable { onColorSelected(color.toArgb()) }
            )
        }
    }
}

private fun Color.toArgb(): Int = (this.alpha * 255).toInt() shl 24 or ((this.red * 255).toInt() shl 16) or ((this.green * 255).toInt() shl 8) or (this.blue * 255).toInt()

private fun drawActionOnCanvas(canvas: Canvas, action: DrawAction, mosaicShader: BitmapShader?) {
    val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = action.strokeWidth
    }
    if (action.mode == EditMode.DOODLE) {
        paint.color = action.color
        paint.isAntiAlias = true
        canvas.drawPath(action.path, paint)
    } else if (action.mode == EditMode.MOSAIC && mosaicShader != null) {
        paint.shader = mosaicShader
        paint.isAntiAlias = false
        paint.isFilterBitmap = false 
        canvas.drawPath(action.path, paint)
    }
}

private fun applyActions(base: Bitmap, actions: List<DrawAction>, mosaicShader: BitmapShader?): Bitmap {
    val result = base.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(result)
    actions.forEach { drawActionOnCanvas(canvas, it, mosaicShader) }
    return result
}

private fun saveEditedImage(context: Context, bitmap: Bitmap, viewModel: MediaViewModel, successMsg: String, failedMsg: String) {
    val name = "EDIT_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicMe")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    uri?.let {
        try {
            context.contentResolver.openOutputStream(it)?.use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream) }
            val asset = MediaAsset(uri = it.toString(), type = MediaType.PHOTO, captureDate = System.currentTimeMillis(), fileName = name)
            viewModel.insertMedia(asset)
            Toast.makeText(context, successMsg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(context, failedMsg, Toast.LENGTH_SHORT).show() }
    }
}
