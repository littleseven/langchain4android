package com.picme.features.camera.facedetect

import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.picme.core.common.Logger
import java.nio.ByteBuffer

/**
 * MediaPipe Face Landmarker 封装器
 *
 * 功能：
 * 1. 检测人脸 468 个 3D 关键点
 * 2. 将 468 点映射为与 GPUPixel/字节火山引擎兼容的 106 点格式
 * 3. 输出归一化坐标（0.0 ~ 1.0）
 *
 * 字节火山引擎 106 点标准（索引 0-105）：
 * - 0-32: 轮廓33点 | 33-105: 五官73点
 *
 * @since 2026-04 替代 ML Kit Face Detection（大美丽模式）
 */
class MediaPipeFaceDetector(context: Context) {

    companion object {
        private const val TAG = "PicMe:MediaPipeFace"

        // MediaPipe 468 → 字节火山引擎 106 点映射表
        // 基于字节火山引擎人脸关键点标准定义（索引 0-105）
        // 106点标准拓扑（与图片 img.png 对照）：
        //   0-16: 左轮廓17点（从左上到下巴左）
        //   17-32: 右轮廓16点（从下巴右到右上）
        //   33-37: 左眉上部5点（从外到内）
        //   38-42: 右眉上部5点（从内到外）
        //   43: 眉心
        //   44-46: 鼻梁3点（从上到下）
        //   47-51: 鼻尖5点（从左到右）
        //   52-57: 左眼外轮廓6点（左眼左上→左眼右上）
        //   58-63: 右眼外轮廓6点（右眼左上→右眼右上）
        //   64-67: 左眉下部4点（从左到右）
        //   68-71: 右眉下部4点（从左到右）
        //   72-74: 左眼内/下3点（72=内角，73=下中，74=瞳孔）
        //   75-77: 右眼内/下3点（75=内角，76=下中，77=瞳孔）
        //   78-79: 鼻梁底部2点（鼻孔上边缘）
        //   80-83: 鼻孔4点（80=左鼻孔左，81=左鼻孔右，82=右鼻孔左，83=右鼻孔右）
        //   84-95: 嘴巴外轮廓12点（84=左嘴角，87=上唇中，91=右嘴角，93=下唇中）
        //   96-103: 嘴巴内轮廓8点（96=左内角，97=上内中，100=右内角，102=下内中）
        //   104: 左瞳孔（与74同位置）
        //   105: 右瞳孔（与77同位置）
        // 非轮廓区域映射表（33-105，共73点）
        // 基于字节火山引擎106点标准定义（与图片 img.png 对照）
        private val NON_CONTOUR_MAPPING = intArrayOf(
            // === 左眉上部 33-37 (5点) - 从左外到左内 ===
            70, 63, 105, 66, 55,

            // === 右眉上部 38-42 (5点) - 从右内到右外 ===
            285, 293, 334, 296, 300,

            // === 眉心 43 ===
            168,

            // === 鼻梁 44-46 (3点) - 从上到下 ===
            6, 197, 195,

            // === 鼻尖 47-51 (5点) - 从左到右 ===
            48, 4, 1, 278, 5,

            // === 左眼外轮廓 52-57 (6点) - 从左外角顺时针 ===
            33, 246, 161, 160, 159, 158,

            // === 右眼外轮廓 58-63 (6点) - 从右内角顺时针 ===
            362, 398, 384, 385, 386, 387,

            // === 左眉下部 64-67 (4点) - 从左到右 ===
            156, 157, 173, 155,

            // === 右眉下部 68-71 (4点) - 从左到右 ===
            383, 382, 381, 380,

            // === 左眼内/下 72-74 (3点) - 72=左眼内角, 73=下眼睑中, 74=左瞳孔 ===
            133, 153, 154,

            // === 右眼内/下 75-77 (3点) - 75=右眼内角, 76=下眼睑中, 77=右瞳孔 ===
            388, 466, 263,

            // === 鼻梁底部 78-79 (2点) - 鼻孔上边缘 ===
            98, 326,

            // === 鼻孔 80-83 (4点) - 80=左鼻孔左, 81=左鼻孔右, 82=右鼻孔左, 83=右鼻孔右 ===
            2, 327, 358, 359,

            // === 嘴巴外轮廓 84-95 (12点) - 84=左嘴角, 87=上唇中, 91=右嘴角, 93=下唇中 ===
            61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 375, 321,

            // === 嘴巴内轮廓 96-103 (8点) - 96=左内角, 97=上内中, 100=右内角, 102=下内中 ===
            405, 314, 17, 84, 181, 91, 146, 96,

            // === 瞳孔 104-105 (2点) - 104=左瞳孔(与74同位), 105=右瞳孔(与77同位) ===
            145, 374
        )

        const val POINT_COUNT = 106
        const val CONTOUR_POINT_COUNT = 33
        const val NON_CONTOUR_POINT_COUNT = 73
    }

    private var faceLandmarker: FaceLandmarker? = null
    private var lastProcessTimeMs: Long = 0

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
    fun detect(imageProxy: ImageProxy, lensFacing: Int): FloatArray? {
        val landmarker = faceLandmarker ?: return null

        val startTime = SystemClock.elapsedRealtime()

        return try {
            val bitmap = imageProxyToBitmap(imageProxy) ?: return null
            val mpImage = BitmapImageBuilder(bitmap).build()

            val result = landmarker.detectForVideo(mpImage, SystemClock.uptimeMillis())

            val processTime = SystemClock.elapsedRealtime() - startTime
            lastProcessTimeMs = processTime

            if (result.faceLandmarks().isEmpty()) {
                Logger.d(TAG, "No face detected (${processTime}ms)")
                return null
            }

            val landmarks = result.faceLandmarks()[0]
            val points106 = convert468To106(landmarks, lensFacing)

            Logger.d(TAG, "Face detected: 106 points in ${processTime}ms")
            points106

        } catch (e: Exception) {
            Logger.e(TAG, "Face detection failed", e)
            null
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

        // === 生成33个轮廓点（0-32）===
        // 基于精确语义映射表：有直接语义对应的 MediaPipe 点使用精确值，
        // 过渡点使用相邻两点的坐标平均

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

        // === 左半边：M0-M16 (162 → 152) ===
        setPoint(0, getMpPoint(162))     // 左太阳穴/轮廓起点
        setPoint(1, midPoint(getMpPoint(162), getMpPoint(127)))   // 左鬓角过渡
        setPoint(2, getMpPoint(127))     // 左耳上方
        setPoint(3, getMpPoint(234))     // 左耳
        setPoint(4, midPoint(getMpPoint(234), getMpPoint(93)))    // 左耳下到脸颊过渡
        setPoint(5, getMpPoint(93))      // 左耳下/脸颊起点
        setPoint(6, getMpPoint(132))     // 左脸颊上
        setPoint(7, getMpPoint(58))      // 左脸颊中
        setPoint(8, midPoint(getMpPoint(58), getMpPoint(172)))    // 左脸颊下过渡
        setPoint(9, getMpPoint(172))     // 左脸颊下
        setPoint(10, getMpPoint(136))    // 左下颌角
        setPoint(11, getMpPoint(150))    // 左下巴边缘
        setPoint(12, getMpPoint(149))    // 下巴左侧
        setPoint(13, getMpPoint(176))    // 下巴中左
        setPoint(14, getMpPoint(148))    // 下巴中心
        setPoint(15, midPoint(getMpPoint(148), getMpPoint(152)))  // 下巴中心到尖过渡
        setPoint(16, getMpPoint(152))    // 下巴尖

        // === 右半边：M17-M32 (152 → 389) ===
        setPoint(17, getMpPoint(377))    // 下巴中右
        setPoint(18, midPoint(getMpPoint(377), getMpPoint(400)))  // 下巴右过渡
        setPoint(19, getMpPoint(400))    // 下巴右侧
        setPoint(20, getMpPoint(378))    // 右下巴边缘
        setPoint(21, getMpPoint(379))    // 右下颌角
        setPoint(22, getMpPoint(397))    // 右脸颊下
        setPoint(23, getMpPoint(365))    // 右脸颊中
        setPoint(24, getMpPoint(288))    // 右脸颊上
        setPoint(25, midPoint(getMpPoint(288), getMpPoint(361)))  // 右脸颊到耳过渡
        setPoint(26, getMpPoint(361))    // 右耳前
        setPoint(27, getMpPoint(323))    // 右耳
        setPoint(28, getMpPoint(454))    // 右耳上
        setPoint(29, midPoint(getMpPoint(454), getMpPoint(356)))  // 右耳上到额侧过渡
        setPoint(30, getMpPoint(356))    // 右额侧
        setPoint(31, midPoint(getMpPoint(356), getMpPoint(389)))  // 右鬓角过渡
        setPoint(32, getMpPoint(389))    // 右太阳穴/轮廓终点

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

    /**
     * 获取最近一次检测耗时
     */
    fun getLastProcessTimeMs(): Long = lastProcessTimeMs

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
        Log.i(TAG, "MediaPipeFaceDetector released")
    }
}
