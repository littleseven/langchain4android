package com.picme.beauty.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 大美丽视频录制器
 *
 * 功能：
 * 1. 基于 MediaCodec (H.264) + MediaMuxer 实现视频编码和封装
 * 2. 提供输入 Surface，供 OpenGL 管线直接渲染
 * 3. 独立线程管理编码器生命周期和输出缓冲
 *
 * Phase 1：仅支持视频（无音频），输出 MP4 文件。
 */
class BeautyVideoRecorder {

    companion object {
        private const val TAG = "PicMe:BeautyRecorder"
        private const val MIME_TYPE = "video/avc"
        private const val FRAME_RATE = 30
        private const val IFRAME_INTERVAL = 1 // 1秒一个I帧
        private const val VIDEO_BITRATE_BASE = 8_000_000 // 8Mbps 基础码率
    }

    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var encoderSurface: Surface? = null
    private var muxerStarted = false
    private var videoTrackIndex = -1

    private val isRecording = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)

    private var outputFile: File? = null
    private var callback: Callback? = null

    private var drainThread: Thread? = null

    interface Callback {
        fun onStarted()
        fun onFinished(outputPath: String)
        fun onError(error: Throwable)
    }

    /**
     * 开始录制
     *
     * @param outputFile 输出 MP4 文件路径
     * @param width 视频宽度
     * @param height 视频高度
     * @param callback 生命周期回调
     */
    fun start(outputFile: File, width: Int, height: Int, callback: Callback) {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording, ignore start request")
            return
        }

        this.outputFile = outputFile
        this.callback = callback

        try {
            val codec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec = codec

            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, calculateBitrate(width, height))
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
            }

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = codec.createInputSurface()
            codec.start()

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mediaMuxer = muxer

            isRecording.set(true)
            isStopping.set(false)

            drainThread = Thread {
                try {
                    drainEncoder()
                } catch (e: Exception) {
                    Log.e(TAG, "Drain thread error", e)
                    callback.onError(e)
                }
            }.apply {
                name = "BeautyVideoDrain"
                start()
            }

            callback.onStarted()
            Log.i(TAG, "Recording started: ${outputFile.absolutePath}, ${width}x${height}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            release()
            callback.onError(e)
        }
    }

    /**
     * 获取编码器输入 Surface
     *
     * OpenGL 管线将美颜后的帧渲染到此 Surface。
     */
    fun getInputSurface(): Surface {
        return encoderSurface ?: throw IllegalStateException("Recorder not started")
    }

    /**
     * 停止录制
     *
     * 发送 EOS 信号，等待编码器 drain 完成，输出最终文件。
     */
    fun stop() {
        if (!isRecording.get() || isStopping.get()) {
            Log.w(TAG, "Not recording or already stopping, ignore stop request")
            return
        }

        isStopping.set(true)
        Log.i(TAG, "Stopping recording...")

        // 在编码器线程发送 EOS
        mediaCodec?.signalEndOfInputStream()
    }

    private fun drainEncoder() {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        var tryAgainCount = 0
        val maxTryAgainCount = 1000 // 10秒超时（1000 * 10ms）
        var outputFrameCount = 0
        var configFrameCount = 0
        var eosReached = false

        while (true) {
            val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10_000)

            when {
                outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (isStopping.get()) {
                        tryAgainCount++
                        if (tryAgainCount > maxTryAgainCount) {
                            Log.w(TAG, "Drain timeout after EOS, forcing stop. muxerStarted=$muxerStarted, outputFrames=$outputFrameCount, configFrames=$configFrameCount")
                            break
                        }
                        if (tryAgainCount % 100 == 0) {
                            Log.d(TAG, "Drain waiting... tryAgainCount=$tryAgainCount, muxerStarted=$muxerStarted")
                        }
                        continue
                    }
                }

                outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    Log.d(TAG, "Encoder output format changed: $newFormat")
                    if (!muxerStarted) {
                        videoTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                        Log.i(TAG, "Muxer started, videoTrackIndex=$videoTrackIndex")
                    }
                }

                outputBufferId >= 0 -> {
                    tryAgainCount = 0
                    val outputBuffer = codec.getOutputBuffer(outputBufferId)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (isConfig) {
                            configFrameCount++
                            Log.d(TAG, "Codec config frame received, size=${bufferInfo.size}, dropping for muxer")
                        } else if (muxerStarted) {
                            muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                            outputFrameCount++
                            if (outputFrameCount <= 5 || outputFrameCount % 30 == 0) {
                                Log.d(TAG, "Encoded frame written: count=$outputFrameCount, pts=${bufferInfo.presentationTimeUs}us, size=${bufferInfo.size}")
                            }
                        } else {
                            Log.w(TAG, "Muxer not started yet, dropping encoded frame. size=${bufferInfo.size}, flags=${bufferInfo.flags}")
                        }
                    } else {
                        Log.d(TAG, "Empty output buffer, size=${bufferInfo.size}, flags=${bufferInfo.flags}")
                    }

                    codec.releaseOutputBuffer(outputBufferId, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.i(TAG, "Encoder EOS reached, totalOutputFrames=$outputFrameCount, configFrames=$configFrameCount")
                        eosReached = true
                        break
                    }
                }
            }
        }

        // [关键修复] 确保 Muxer 正确停止，写入 MP4 文件尾
        if (muxerStarted) {
            try {
                Log.i(TAG, "Stopping muxer...")
                muxer.stop()
                Log.i(TAG, "Muxer stopped successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop muxer", e)
            }
        }

        // 录制完成回调
        val path = outputFile?.absolutePath
        if (path != null) {
            callback?.onFinished(path)
        }

        release()
    }

    private fun release() {
        isRecording.set(false)

        drainThread?.join(2000)
        drainThread = null

        try {
            mediaMuxer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaMuxer?.release()
        } catch (_: Exception) {
        }
        mediaMuxer = null

        try {
            mediaCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaCodec?.release()
        } catch (_: Exception) {
        }
        mediaCodec = null

        encoderSurface = null
        muxerStarted = false
        videoTrackIndex = -1

        Log.i(TAG, "Recorder released")
    }

    private fun calculateBitrate(width: Int, height: Int): Int {
        val pixels = width * height
        return when {
            pixels >= 3840 * 2160 -> 40_000_000 // 4K
            pixels >= 1920 * 1080 -> VIDEO_BITRATE_BASE // 1080p
            pixels >= 1280 * 720 -> 4_000_000 // 720p
            else -> 2_000_000
        }
    }
}
