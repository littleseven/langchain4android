package com.picme.features.camera.facedetect

import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.picme.core.common.Logger
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.features.camera.preview.core.FaceDetectionSource
import com.pixpark.gpupixel.FaceDetector
import com.pixpark.gpupixel.GPUPixel
import java.nio.ByteBuffer

/**
 * MediaPipe Face Landmarker 封装器
 *
 * 功能：
 * 1. 检测人脸 468 个 3D 关键点
 * 2. 将 468 点映射为与 GPUPixel/字节火山引擎兼容的 106 点格式
 * 3. 输出归一化坐标（0.0 ~ 1.0）
 *
 * 映射依据：
 * - 字节火山引擎106点标准：docs/VOLCANO_ENGINE_106_POINTS.md
 * - MediaPipe 468点标准：docs/MEDIAPIPE_468_POINTS.md
 * - 映射策略：优先使用对等语义点，缺失点使用插值
 *
 * 106点拓扑方向（被摄者真实面部，前置摄像头镜像后）：
 *   轮廓：从右脸鬓角(0,画面左侧) → 下巴(16) → 左脸鬓角(32,画面右侧)，开放曲线
 *   画面左侧=实际右脸，画面右侧=实际左脸
 *
 * @since 2026-04 替代 ML Kit Face Detection（大美丽模式）
 */
class MediaPipeFaceDetector(
    context: Context,
    private val detectionEngineMode: FaceDetectionEngineMode = FaceDetectionEngineMode.MEDIAPIPE
) {

    companion object {
        private const val TAG = "PicMe:MediaPipeFace"

        // MediaPipe 468 → 字节火山引擎 106 点映射表
        // 映射依据：docs/VOLCANO_ENGINE_106_POINTS.md + docs/MEDIAPIPE_468_POINTS.md
        // 106点标准拓扑（被摄者真实面部，前置摄像头镜像后）——严格遵循火山引擎标准：
        //   0-16: 右轮廓17点（画面左侧，从右上到下巴右）
        //   17-32: 左轮廓16点（画面右侧，从下巴左到左上）
        //   33-37: 右眉上部5点（画面左侧，从眉头到眉尾）
        //   38-42: 左眉上部5点（画面右侧，从眉头到眉尾）
        //   43: 眉心
        //   44-46: 鼻梁3点（从上到下：44=鼻根/山根, 45=鼻梁中, 46=鼻梁下）
        //   47-51: 鼻尖5点（从左到右，47=右鼻翼上/画面左侧, 48=鼻尖右, 49=鼻尖中心, 50=鼻尖左, 51=左鼻翼上/画面右侧）
        //   52-57: 右眼外轮廓6点（画面左侧，从外角到内角）
        //   58-63: 左眼外轮廓6点（画面右侧，从外角到内角）
        //   64-67: 右眉下部4点（画面左侧，从眉头到眉尾）
        //   68-71: 左眉下部4点（画面右侧，从眉尾到眉头）
        //   72-74: 右眼补充3点（72=右眼下眼睑中, 73=右眼下眼睑外, 74=右瞳孔）
        //   75-77: 左眼补充3点（75=左眼下眼睑中, 76=左眼下眼睑外, 77=左瞳孔）
        //   78-79: 山根2点（78=山根右/画面左侧, 79=山根左/画面右侧）
        //   80-83: 鼻孔4点（80=右鼻孔左/画面左侧, 81=右鼻孔右, 82=左鼻孔左, 83=左鼻孔右/画面右侧）
        //   84-95: 嘴巴外轮廓12点（84=右嘴角/画面左侧, 94=左嘴角/画面右侧）
        //   96-103: 嘴巴内轮廓8点（96=右内角, 100=左内角）
        //   104-105: 瞳孔2点（104=右瞳孔/画面左侧, 105=左瞳孔/画面右侧）
        // 非轮廓区域映射表（33-105，共73点）
        private val NON_CONTOUR_MAPPING = intArrayOf(
            // === 右眉上部 33-37 (5点) - 画面左侧=实际右脸，从眉头到眉尾 ===
            70, 63, 105, 66, 107,

            // === 左眉上部 38-42 (5点) - 画面右侧=实际左脸，从眉头到眉尾 ===
            336, 296, 334, 293, 300,

            // === 眉心 43 ===
            168,

            // === 鼻梁 44-46 (3点) - 从上到下 ===
            197, 5, 4,

            // === 鼻尖 47-51 (5点) - 从左到右，严格对称 ===
            98, 241, 2, 461, 327,

            // === 右眼 52-57 (6点) - 画面左侧=实际右脸 ===
            226, 30, 56, 133, 26, 110,

            // === 左眼 58-63 (6点) - 画面右侧=实际左脸 ===
            362, 286, 260, 446, 339, 256,

            // === 右眉下部 64-67 (4点) - 画面左侧=实际右脸，从眉头到眉尾 ===
            53, 52, 65, 55,

            // === 左眉下部 68-71 (4点) - 画面右侧=实际左脸，从眉尾到眉头 ===
            285, 295, 282, 283,

            // === 右眼补充 72-74 (3点) - 画面左侧 ===
            374, 375, 473,

            // === 左眼补充 75-77 (3点) - 画面右侧 ===
            44, 45, 468,

            // === 山根 78-79 (2点) ===
            193, 417,

            // === 鼻孔 80-83 (4点) ===
            198, 420, 49, 279,

            // === 嘴巴外轮廓 84-95 (12点) - 顺时针闭合曲线 ===
            61, 40, 37, 0, 267, 270, 291, 321, 314, 17, 84, 91,

            // === 嘴巴内轮廓 96-103 (8点) - 顺时针闭合曲线 ===
            78, 81, 13, 311, 308, 178, 14, 402,

            // === 瞳孔 104-105 (2点) ===
            473, 468
        )

        const val POINT_COUNT = 106
        const val CONTOUR_POINT_COUNT = 33
        const val NON_CONTOUR_POINT_COUNT = 73
    }

    private val appContext: Context = context.applicationContext
    private var faceLandmarker: FaceLandmarker? = null
    private var insightFaceDetector: InsightFace2D106Detector? = null
    private var gpupixelFaceDetector: FaceDetector? = null
    private var lastProcessTimeMs: Long = 0
    private var lastDetectionSource: FaceDetectionSource = FaceDetectionSource.NONE

    init {
        initialize(context)
    }

    private fun initialize(context: Context) {
        try {
            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.GPU)
                .setModelAssetPath("mediapipe/face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(false)
                .setRunningMode(RunningMode.VIDEO)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.i(TAG, "MediaPipe FaceLandmarker initialized (GPU delegate)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FaceLandmarker with GPU, fallback to CPU", e)
            try {
                val baseOptions = BaseOptions.builder()
                    .setDelegate(Delegate.CPU)
                    .setModelAssetPath("mediapipe/face_landmarker.task")
                    .build()
                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinFaceDetectionConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setMinFacePresenceConfidence(0.5f)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(false)
                    .setRunningMode(RunningMode.VIDEO)
                    .build()
                faceLandmarker = FaceLandmarker.createFromOptions(context, options)
                Log.i(TAG, "MediaPipe FaceLandmarker initialized (CPU delegate)")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to initialize FaceLandmarker", e2)
            }
        }
    }

    /**
     * 处理相机帧，检测人脸并返回 106 点归一化坐标
     *
     * @param imageProxy CameraX ImageProxy
     * @param lensFacing 镜头方向，用于坐标镜像
     * @return 106 点归一化坐标列表（FloatArray，偶数索引=x，奇数索引=y），无人脸返回 null
     */
    @ExperimentalGetImage
    fun detect(imageProxy: ImageProxy, lensFacing: Int): DetectionResult? {
        val startTime = SystemClock.elapsedRealtime()
        val bitmap = imageProxyToBitmap(imageProxy) ?: return null
        lastDetectionSource = FaceDetectionSource.NONE

        return try {
            when (detectionEngineMode) {
                FaceDetectionEngineMode.MEDIAPIPE -> {
                    val mediaPipeResult = detectWithMediaPipe(bitmap, lensFacing)
                    lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
                    if (mediaPipeResult != null) {
                        lastDetectionSource = FaceDetectionSource.MEDIAPIPE
                        Logger.d(TAG, "Face detected by MediaPipe in ${lastProcessTimeMs}ms")
                        DetectionResult(mediaPipeResult, lastDetectionSource)
                    } else {
                        Logger.d(TAG, "No face detected by MediaPipe (${lastProcessTimeMs}ms)")
                        null
                    }
                }

                FaceDetectionEngineMode.INSIGHTFACE -> {
                    val insightFaceResult = ensureInsightFaceDetector()?.detect(bitmap, lensFacing)
                    lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
                    if (insightFaceResult != null) {
                        lastDetectionSource = FaceDetectionSource.INSIGHTFACE
                        Logger.d(TAG, "Face detected by InsightFace in ${lastProcessTimeMs}ms")
                        DetectionResult(insightFaceResult, lastDetectionSource)
                    } else {
                        Logger.d(TAG, "No face detected by InsightFace (${lastProcessTimeMs}ms)")
                        null
                    }
                }

                FaceDetectionEngineMode.GPUPIXEL -> {
                    val gpupixelResult = detectWithGpupixel(bitmap, lensFacing)
                    lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
                    if (gpupixelResult != null) {
                        lastDetectionSource = FaceDetectionSource.GPUPIXEL
                        Logger.d(TAG, "Face detected by GPUPixel in ${lastProcessTimeMs}ms")
                        DetectionResult(gpupixelResult, lastDetectionSource)
                    } else {
                        Logger.d(TAG, "No face detected by GPUPixel (${lastProcessTimeMs}ms)")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
            Logger.e(TAG, "Face detection failed in ${detectionEngineMode.name} mode", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 将 MediaPipe 468 点转换为字节火山引擎 106 点 FloatArray
     *
     * @param landmarks MediaPipe 468 个 NormalizedLandmark
     * @param lensFacing 镜头方向，用于水平镜像
     * @return FloatArray(212) = [x0,y0, x1,y1, ..., x105,y105]
     */
    private fun convert468To106(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        lensFacing: Int
    ): FloatArray {
        val result = FloatArray(POINT_COUNT * 2)
        val isFrontCamera = lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT

        // 辅助函数：获取 MediaPipe 点坐标
        fun getMpPoint(index: Int): Pair<Float, Float>? {
            if (index >= landmarks.size) return null
            val landmark = landmarks[index]
            var x = landmark.x()
            val y = landmark.y()
            if (isFrontCamera) {
                x = 1f - x
            }
            return Pair(x, y)
        }

        // 辅助函数：计算两点中点
        fun midPoint(p1: Pair<Float, Float>?, p2: Pair<Float, Float>?): Pair<Float, Float>? {
            if (p1 == null || p2 == null) return null
            return Pair((p1.first + p2.first) / 2f, (p1.second + p2.second) / 2f)
        }

        // 辅助函数：设置106点坐标
        fun setPoint(idx: Int, point: Pair<Float, Float>?) {
            if (point == null) return
            result[idx * 2] = point.first.coerceIn(0f, 1f)
            result[idx * 2 + 1] = point.second.coerceIn(0f, 1f)
        }

        // === 生成33个轮廓点（0-32）===
        // 规则：M0=127, M16=152, M32=356，沿 FACE_OVAL 路径均匀插值
        // MediaPipe FACE_OVAL 点序：[10,338,297,332,284,251,389,356,454,323,361,288,397,365,379,378,400,377,152,148,176,149,150,136,172,58,132,93,234,127,162,21,54,103,67,109]
        // 注意：106点为开放曲线（不含额头），M0和M32与上眼皮位置齐平

        // M0-M16：从 127 沿 FACE_OVAL 逆时针到 152
        // FACE_OVAL 从 127 到 152 的路径（逆时针）：127→234→93→132→58→172→136→150→149→176→148→152
        val leftContourBasePoints = listOf(127, 234, 93, 132, 58, 172, 136, 150, 149, 176, 148, 152)
            .mapNotNull { idx -> getMpPoint(idx) }

        // M16-M32：从 152 沿 FACE_OVAL 逆时针到 356
        // FACE_OVAL 从 152 继续：152→377→400→378→379→365→397→288→361→323→454→356
        val rightContourBasePoints = listOf(152, 377, 400, 378, 379, 365, 397, 288, 361, 323, 454, 356)
            .mapNotNull { idx -> getMpPoint(idx) }

        // 沿路径均匀插值生成 33 点
        // M0-M16 (17点)
        for (i in 0..16) {
            val t = i.toFloat() / 16f
            val pos = t * (leftContourBasePoints.size - 1)
            val idx = pos.toInt().coerceIn(0, leftContourBasePoints.size - 2)
            val frac = pos - idx

            val p1 = leftContourBasePoints[idx]
            val p2 = leftContourBasePoints[idx + 1]
            val x = p1.first + (p2.first - p1.first) * frac
            val y = p1.second + (p2.second - p1.second) * frac
            setPoint(i, Pair(x, y))
        }

        // M16-M32 (17点，M16已设置，所以从M17开始)
        for (i in 1..16) {
            val t = i.toFloat() / 16f
            val pos = t * (rightContourBasePoints.size - 1)
            val idx = pos.toInt().coerceIn(0, rightContourBasePoints.size - 2)
            val frac = pos - idx

            val p1 = rightContourBasePoints[idx]
            val p2 = rightContourBasePoints[idx + 1]
            val x = p1.first + (p2.first - p1.first) * frac
            val y = p1.second + (p2.second - p1.second) * frac
            setPoint(16 + i, Pair(x, y))
        }

        // 日志输出轮廓点坐标，用于调试对齐
        val sb = StringBuilder("Contour33: ")
        for (i in 0 until 33) {
            sb.append("M$i=(${String.format("%.3f", result[i * 2])},${String.format("%.3f", result[i * 2 + 1])}) ")
        }
        Logger.d(TAG, sb.toString())

        // === 生成非轮廓区域点（33-105）===
        for (i in 0 until NON_CONTOUR_POINT_COUNT) {
            val mpIndex = NON_CONTOUR_MAPPING[i]
            if (mpIndex < landmarks.size) {
                val landmark = landmarks[mpIndex]
                var x = landmark.x()
                var y = landmark.y()

                if (isFrontCamera) {
                    x = 1f - x
                }

                result[(33 + i) * 2] = x.coerceIn(0f, 1f)
                result[(33 + i) * 2 + 1] = y.coerceIn(0f, 1f)
            }
        }

        return result
    }

    /**
     * 插值生成指定数量的轮廓点
     * @param basePoints 基础点列表（已排序，从左到右）
     * @param targetCount 目标点数量（33）
     * @return 插值后的点列表
     */
    private fun interpolateContourPoints(
        basePoints: List<Pair<Float, Float>>,
        targetCount: Int
    ): List<Pair<Float, Float>> {
        val n = basePoints.size
        if (n >= targetCount) {
            // 如果基础点足够，均匀选择
            val step = n.toFloat() / targetCount
            return List(targetCount) { i ->
                basePoints[(i * step).toInt().coerceIn(0, n - 1)]
            }
        }

        // 需要插值：在相邻点之间插入新点
        val result = mutableListOf<Pair<Float, Float>>()
        val insertCount = targetCount - n  // 需要插入的点数
        val segmentCount = n - 1  // 线段数

        // 计算每段需要插入的点数（优先在较长的线段插入）
        val segmentLengths = mutableListOf<Float>()
        for (i in 0 until segmentCount) {
            val dx = basePoints[i + 1].first - basePoints[i].first
            val dy = basePoints[i + 1].second - basePoints[i].second
            segmentLengths.add(kotlin.math.sqrt(dx * dx + dy * dy))
        }

        val totalLength = segmentLengths.sum()
        val insertsPerSegment = IntArray(segmentCount) { 0 }

        // 按长度比例分配插入点
        var remainingInserts = insertCount
        for (i in 0 until segmentCount) {
            val ratio = if (totalLength > 0) segmentLengths[i] / totalLength else 1f / segmentCount
            val inserts = (ratio * insertCount).toInt()
            insertsPerSegment[i] = inserts
            remainingInserts -= inserts
        }

        // 处理剩余插入点（四舍五入误差）
        var idx = 0
        while (remainingInserts > 0 && idx < segmentCount) {
            insertsPerSegment[idx]++
            remainingInserts--
            idx++
        }

        // 生成插值点
        for (i in 0 until segmentCount) {
            // 添加起点
            result.add(basePoints[i])

            // 插入新点
            val inserts = insertsPerSegment[i]
            for (j in 1..inserts) {
                val t = j.toFloat() / (inserts + 1)
                val x = basePoints[i].first + (basePoints[i + 1].first - basePoints[i].first) * t
                val y = basePoints[i].second + (basePoints[i + 1].second - basePoints[i].second) * t
                result.add(Pair(x, y))
            }
        }

        // 添加最后一个点
        result.add(basePoints[n - 1])

        return result
    }

    /**
     * 将 ImageProxy 转换为 Bitmap（MediaPipe 需要 Bitmap 输入）
     *
     * 使用标准 YUV_420_888 → RGBA 转换，然后创建 Bitmap。
     * 正确处理旋转和前置摄像头镜像。
     */
    @ExperimentalGetImage
    private fun imageProxyToBitmap(imageProxy: ImageProxy): android.graphics.Bitmap? {
        val image = imageProxy.image ?: return null
        val width = imageProxy.width
        val height = imageProxy.height
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // 使用 GPUPixel 的 Native 方法将 YUV 转换为 RGBA ByteBuffer
        val rgbaBuffer: java.nio.ByteBuffer = try {
            val buffers = com.pixpark.gpupixel.GPUPixel.YUV_420_888toI420AndRGBA(image, rotationDegrees)
                ?: return null
            // buffers[3] 是 RGBA
            buffers[3]
        } catch (e: Exception) {
            Log.e(TAG, "YUV to RGBA conversion failed", e)
            return null
        }

        // 计算旋转后的尺寸
        val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
            90, 270 -> Pair(height, width)
            else -> Pair(width, height)
        }

        // 从 RGBA ByteBuffer 创建 Bitmap
        val bitmap = android.graphics.Bitmap.createBitmap(rotatedWidth, rotatedHeight, android.graphics.Bitmap.Config.ARGB_8888)
        rgbaBuffer.rewind()
        bitmap.copyPixelsFromBuffer(rgbaBuffer)

        return bitmap
    }

    private fun detectWithMediaPipe(bitmap: android.graphics.Bitmap, lensFacing: Int): FloatArray? {
        val landmarker = faceLandmarker ?: return null
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detectForVideo(mpImage, SystemClock.uptimeMillis())
        if (result.faceLandmarks().isEmpty()) {
            return null
        }
        return convert468To106(result.faceLandmarks()[0], lensFacing)
    }

    private fun detectWithGpupixel(bitmap: android.graphics.Bitmap, lensFacing: Int): FloatArray? {
        val detector = ensureGpupixelFaceDetector() ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val rgbaLandmarks = detector.detect(
            buildDirectColorBuffer(pixels, FaceDetector.GPUPIXEL_FRAME_TYPE_RGBA),
            bitmap.width,
            bitmap.height,
            bitmap.width * 4,
            FaceDetector.GPUPIXEL_MODE_FMT_VIDEO,
            FaceDetector.GPUPIXEL_FRAME_TYPE_RGBA
        )
        val rawLandmarks: FloatArray = if (rgbaLandmarks.isNotEmpty()) {
            rgbaLandmarks
        } else {
            detector.detect(
                buildDirectColorBuffer(pixels, FaceDetector.GPUPIXEL_FRAME_TYPE_BGRA),
                bitmap.width,
                bitmap.height,
                bitmap.width * 4,
                FaceDetector.GPUPIXEL_MODE_FMT_VIDEO,
                FaceDetector.GPUPIXEL_FRAME_TYPE_BGRA
            )
        }
        if (rawLandmarks.isEmpty()) {
            return null
        }

        val result = FloatArray(POINT_COUNT * 2)
        val isFrontCamera = lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT
        for (index in 0 until POINT_COUNT) {
            val x = rawLandmarks[index * 2]
            val y = rawLandmarks[index * 2 + 1]
            result[index * 2] = if (isFrontCamera) 1f - x else x
            result[index * 2 + 1] = y
        }
        return result
    }

    private fun buildDirectColorBuffer(pixels: IntArray, frameType: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(pixels.size * 4)
        for (pixel in pixels) {
            when (frameType) {
                FaceDetector.GPUPIXEL_FRAME_TYPE_BGRA -> {
                    buffer.put((pixel and 0xFF).toByte())
                    buffer.put((pixel shr 8 and 0xFF).toByte())
                    buffer.put((pixel shr 16 and 0xFF).toByte())
                    buffer.put((pixel shr 24 and 0xFF).toByte())
                }

                else -> {
                    buffer.put((pixel shr 16 and 0xFF).toByte())
                    buffer.put((pixel shr 8 and 0xFF).toByte())
                    buffer.put((pixel and 0xFF).toByte())
                    buffer.put((pixel shr 24 and 0xFF).toByte())
                }
            }
        }
        buffer.flip()
        return buffer
    }

    private fun ensureInsightFaceDetector(): InsightFace2D106Detector? {
        val cached = insightFaceDetector
        if (cached != null && cached.isReady()) {
            return cached
        }
        return runCatching {
            InsightFace2D106Detector(appContext)
        }.onSuccess { detector ->
            insightFaceDetector = detector.takeIf { instance -> instance.isReady() }
        }.onFailure { error ->
            Logger.e(TAG, "Failed to initialize InsightFace 2D106 detector", error)
        }.getOrNull()?.takeIf { detector -> detector.isReady() }
    }

    private fun ensureGpupixelFaceDetector(): FaceDetector? {
        val cached = gpupixelFaceDetector
        if (cached != null) {
            return cached
        }
        return runCatching {
            GPUPixel.Init(appContext)
            FaceDetector.Create()
        }.onSuccess { detector ->
            gpupixelFaceDetector = detector
        }.onFailure { error ->
            Logger.e(TAG, "Failed to initialize GPUPixel face detector", error)
        }.getOrNull()
    }

    /**
     * 获取最近一次检测耗时
     */
    fun getLastProcessTimeMs(): Long = lastProcessTimeMs

    fun getLastDetectionSource(): FaceDetectionSource = lastDetectionSource

    /**
     * 从 106 点 FloatArray 中提取指定索引的点
     */
    fun getPoint(landmarks106: FloatArray, index: Int): PointF {
        require(index in 0 until POINT_COUNT)
        return PointF(landmarks106[index * 2], landmarks106[index * 2 + 1])
    }

    /**
     * 释放资源
     */
    fun release() {
        faceLandmarker?.close()
        faceLandmarker = null
        insightFaceDetector?.release()
        insightFaceDetector = null
        gpupixelFaceDetector?.destroy()
        gpupixelFaceDetector = null
        lastDetectionSource = FaceDetectionSource.NONE
        Log.i(TAG, "MediaPipeFaceDetector released")
    }

    data class DetectionResult(
        val landmarks106: FloatArray,
        val detectionSource: FaceDetectionSource
    )
}
