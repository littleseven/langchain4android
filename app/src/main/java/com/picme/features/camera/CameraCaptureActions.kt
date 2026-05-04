package com.picme.features.camera

import android.annotation.SuppressLint
import android.content.Context
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.picme.beauty.api.Face
import com.picme.core.image.ImageProcessor
import com.picme.beauty.api.BeautySettings
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.model.MediaAsset
import com.picme.domain.model.MediaType
import com.picme.beauty.api.FilterType
import com.picme.features.gallery.MediaViewModel

@SuppressLint("MissingPermission")
internal fun handleCaptureClick(
    context: Context,
    captureMode: MediaType,
    isRecording: Boolean,
    recording: Recording?,
    videoCapture: VideoCapture<Recorder>,
    viewModel: MediaViewModel,
    imageCapture: ImageCapture?,
    imageProcessor: ImageProcessor,
    selectedFilter: FilterType,
    beautySettings: BeautySettings,
    lensFacing: Int,
    cachedFaces: List<Face> = emptyList(),
    beautyStrategy: BeautyStrategy = BeautyStrategy.BIG_BEAUTY,
    onRecordingChanged: (Recording?) -> Unit,
    onIsRecordingChanged: (Boolean) -> Unit
) {
    if (captureMode != MediaType.VIDEO) {
        imageCapture?.let { capture ->
            imageProcessor.takePhoto(
                context = context,
                imageCapture = capture,
                viewModel = viewModel,
                filter = selectedFilter,
                beauty = beautySettings,
                lensFacing = lensFacing,
                mode = captureMode,
                cachedFaces = cachedFaces,
                beautyStrategy = beautyStrategy
            )
        }
        return
    }

    if (isRecording) {
        recording?.stop()
        onRecordingChanged(null)
        onIsRecordingChanged(false)
        return
    }

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

    onIsRecordingChanged(true)
    val newRecording = videoCapture.output
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
                        onRecordingChanged(null)
                        onIsRecordingChanged(false)
                    }
                }
            }
        }
    onRecordingChanged(newRecording)
}

