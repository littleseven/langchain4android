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
class MediaPipeFaceDetector(context: Context) {

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
        //   72-74: 右眼内/下3点（72=右眼内角, 73=右眼下眼睑中, 74=右眼内下）
        //   75-77: 左眼内/下3点（75=左眼内角, 76=左眼下眼睑中, 77=左眼内下）
        //   78-79: 山根2点（78=山根右/画面左侧, 79=山根左/画面右侧）
        //   80-83: 鼻孔4点（80=右鼻孔左/画面左侧, 81=右鼻孔右, 82=左鼻孔左, 83=左鼻孔右/画面右侧）
        //   84-95: 嘴巴外轮廓12点（84=右嘴角/画面左侧, 94=左嘴角/画面右侧）
        //   96-103: 嘴巴内轮廓8点（96=右内角, 100=左内角）
        //   104-105: 瞳孔2点（104=右瞳孔/画面左侧, 105=左瞳孔/画面右侧）
        // 非轮廓区域映射表（33-105，共73点）
        private val NON_CONTOUR_MAPPING = intArrayOf(
            // === 右眉上部 33-37 (5点) - 画面左侧=实际右脸，从眉头到眉尾 ===
            // MediaPipe "左眉"(70-107)实际在画面左侧=被摄者右脸，对应火山引擎右眉
            //   70=眉头(靠近鼻梁), 63=眉腰前, 105=眉峰, 66=眉腰后, 107=眉尾
            70, 63, 105, 66, 107,

            // === 左眉上部 38-42 (5点) - 画面右侧=实际左脸，从眉头到眉尾 ===
            // MediaPipe "右眉"(296-336)实际在画面右侧=被摄者左脸，对应火山引擎左眉
            //   336=眉头(靠近鼻梁), 296=眉腰前, 334=眉峰, 293=眉腰后, 300=眉尾
            336, 296, 334, 293, 300,

            // === 眉心 43 ===
            168,

            // === 鼻梁 44-46 (3点) - 从上到下 ===
            // 基于 MediaPipe 鼻子语义关键点（MEDIAPIPE_468_POINTS.md）：
            //   44=鼻根/山根(6), 45=鼻梁中(195), 46=鼻梁下(197)
            6, 195, 197,

            // === 鼻尖 47-51 (5点) - 从左到右（47=画面左侧，51=画面右侧） ===
            // 基于 MediaPipe 鼻子语义关键点：
            //   47=右鼻翼上(327), 48=鼻尖右(51), 49=鼻尖中心(4), 50=鼻尖左(48), 51=左鼻翼上(326)
            // 注意：MediaPipe 48=鼻尖左(画面右侧), 51=鼻尖右(画面左侧)
            // 火山引擎从左到右：47(右/画面左) → 48(鼻尖右) → 49(中心) → 50(鼻尖左) → 51(左/画面右)
            327, 51, 4, 48, 326,

            // === 右眼外轮廓 52-57 (6点) - 画面左侧=实际右脸，从外角到内角顺时针 ===
            // 核心点：362=右眼外角(画面左侧), 463=右眼内角(靠近鼻梁)
            // 策略：沿上眼睑从外角到内角
            //   362=外角, 385=上眼睑外, 386=上眼睑中1, 387=上眼睑中2, 388=上眼睑内, 463=内角
            362, 385, 386, 387, 388, 463,

            // === 左眼外轮廓 58-63 (6点) - 画面右侧=实际左脸，从外角到内角顺时针 ===
            // 核心点：33=左眼外角(画面右侧), 133=左眼内角(靠近鼻梁)
            // 策略：沿上眼睑从外角到内角
            //   33=外角, 157=上眼睑外, 158=上眼睑中1, 160=上眼睑中2, 161=上眼睑内, 133=内角
            33, 157, 158, 160, 161, 133,

            // === 右眉下部 64-67 (4点) - 画面左侧=实际右脸，从眉头到眉尾 ===
            // MediaPipe "左眉下部"实际在画面左侧=被摄者右脸，对应火山引擎右眉下部
            //   46=眉头下, 53=眉腰前下, 52=眉峰下, 65=眉尾下
            46, 53, 52, 65,

            // === 左眉下部 68-71 (4点) - 画面右侧=实际左脸，从眉尾到眉头 ===
            // MediaPipe "右眉下部"实际在画面右侧=被摄者左脸，对应火山引擎左眉下部
            //   火山引擎要求从眉尾到眉头：295=眉尾下, 282=眉峰下, 283=眉腰前下, 276=眉头下
            295, 282, 283, 276,

            // === 右眼内/下 72-74 (3点) - 画面左侧 ===
            //   72=右眼内角(靠近鼻梁), 73=右眼下眼睑中, 74=右眼内下
            // MediaPipe对应：463=右眼内角, 374=右眼下眼睑中, 473=右眼内下(478点模式)
            463, 374, 473,

            // === 左眼内/下 75-77 (3点) - 画面右侧 ===
            //   75=左眼内角(靠近鼻梁), 76=左眼下眼睑中, 77=左眼内下
            // MediaPipe对应：133=左眼内角, 145=左眼下眼睑中, 468=左眼内下(478点模式)
            133, 145, 468,

            // === 山根 78-79 (2点) ===
            // 基于 MediaPipe 鼻子语义关键点：
            //   78=山根右(画面左侧), 79=山根左(画面右侧)
            //   使用鼻翼上方点近似：327=右鼻翼上(画面左侧), 326=左鼻翼上(画面右侧)
            327, 326,

            // === 鼻孔 80-83 (4点) ===
            // 火山引擎语义：80=右鼻孔左(画面左侧), 81=右鼻孔右, 82=左鼻孔左, 83=左鼻孔右(画面右侧)
            // MediaPipe对应：327=右鼻孔左(鼻中隔右侧), 358=右鼻孔右, 2=左鼻孔左, 326=左鼻孔右(鼻中隔左侧)
            327, 358, 2, 326,

            // === 嘴巴外轮廓 84-95 (12点) - 顺时针闭合曲线 ===
            // 基于 MEDIAPIPE_468_POINTS.md 嘴部语义关键点：
            //   61=左嘴角, 0=上唇中心/唇珠, 291=右嘴角
            //   62-65=上唇左, 267=上唇中心右, 269-270=上唇右
            //   375=下唇右外, 409=下唇右中, 321=下唇左中, 84=下唇左外
            // 火山引擎106点语义：
            //   84=左嘴角, 85-89=上唇从左到右(5点), 90=右嘴角
            //   91-95=下唇从右到左(5点), 93=下唇中间对称点
            // 固定语义匹配点（严格对称）：
            //   84=61(左嘴角), 87=0(唇珠/上唇中轴), 90=291(右嘴角)
            //   93=0(唇珠/下唇中轴对称点) - 上唇与下唇共享唇珠中轴点
            // 左右对称映射：
            //   85=62(上唇左) ↔ 89=270(上唇右)
            //   86=65(上唇左) ↔ 88=267(上唇右)
            //   91=375(下唇右外) ↔ 95=84(下唇左外)
            //   92=409(下唇右中) ↔ 94=321(下唇左中)
            61, 62, 65, 0, 267, 270, 291, 375, 409, 0, 321, 84,

            // === 嘴巴内轮廓 96-103 (8点) - 顺时针闭合曲线 ===
            // 基于 MEDIAPIPE_468_POINTS.md 嘴部内轮廓语义关键点：
            //   78=上唇内左, 88=上唇内中, 87=上唇内右
            //   308=下唇内左, 324=下唇内中, 310=下唇内右
            // 火山引擎106点语义：
            //   96=左内角, 97-99=上唇内侧从左到右(3点), 98=上唇内侧对称点
            //   100=右内角, 101-103=下唇内侧从右到左(3点), 102=下唇内侧对称点
            // 固定语义匹配点（严格对称）：
            //   96=78(左内角/上唇内左), 98=88(上唇内中/对称点), 99=87(上唇内右)
            //   100=310(右内角/下唇内右), 102=324(下唇内中/对称点), 103=308(下唇内左)
            // 左右对称映射：
            //   97=78(左内角) - 96/97共用起点
            //   101=310(右内角) - 100/101共用角点
            78, 78, 88, 87, 310, 310, 324, 308,

            // === 瞳孔 104-105 (2点) ===
            //   104=右瞳孔（画面左侧，与74位置接近，MediaPipe 473）
            //   105=左瞳孔（画面右侧，与77位置接近，MediaPipe 468）
            473, 468
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
        //
        // 轮廓方向（被摄者真实面部，前置摄像头镜像后）——严格遵循火山引擎106点标准：
        //   M0-M15=右轮廓（画面左侧，从右上到下巴右）
        //   M16=下巴中心（最低点）
        //   M17-M32=左轮廓（画面右侧，从下巴左到左上）
        //   注意：MediaPipe 的 FACE_OVAL 为闭合曲线（含额头），106点为开放曲线（不含额头）

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

        // === 右半边：M0-M15 (画面左侧=实际右脸，从上到下) ===
        // 火山引擎标准：0-16 为右轮廓（画面左侧，从右上到下巴右）
        // 策略：先确定语义对称的固定点，再插值过渡点
        // 固定点（从外到中心）：162→127→234→93→132→58→172→136→150→149→176→148→152
        setPoint(0, getMpPoint(162))     // 右鬓角/太阳穴（画面左侧）
        setPoint(1, midPoint(getMpPoint(162), getMpPoint(127)))   // 过渡：右鬓角到右耳上
        setPoint(2, getMpPoint(127))     // 右耳上
        setPoint(3, getMpPoint(234))     // 右耳
        setPoint(4, midPoint(getMpPoint(234), getMpPoint(93)))    // 过渡：右耳到右耳下
        setPoint(5, getMpPoint(93))      // 右耳下/脸颊起点
        setPoint(6, getMpPoint(132))     // 右脸颊上
        setPoint(7, getMpPoint(58))      // 右脸颊中
        setPoint(8, getMpPoint(172))     // 右脸颊下
        setPoint(9, getMpPoint(136))     // 右下颌角
        setPoint(10, getMpPoint(150))    // 右下巴边缘
        setPoint(11, getMpPoint(149))    // 下巴右侧
        setPoint(12, midPoint(getMpPoint(149), getMpPoint(176)))  // 过渡：下巴右侧到下巴中右
        setPoint(13, getMpPoint(176))    // 下巴中右
        setPoint(14, midPoint(getMpPoint(176), getMpPoint(148)))  // 过渡：下巴中右到下巴中心右侧
        setPoint(15, getMpPoint(148))    // 下巴中心右侧

        // === 下巴中心：M16 ===
        setPoint(16, getMpPoint(152))    // 下巴中心/最低点（中轴点）

        // === 左半边：M17-M32 (画面右侧=实际左脸，从下到上) ===
        // 火山引擎标准：17-32 为左轮廓（画面右侧，从下巴左到左上）
        // 策略：使用与右半边语义对称的MediaPipe固定点，按x坐标递减排列（从中心到左侧），再插值过渡点
        // 固定点顺序（从中心到左侧，x递减）：152→377→378→400→379→397→365→288→361→323→454→389
        // 对称关系：148↔377, 176↔378, 149↔400, 150↔379, 136↔397, 172↔365, 58↔288, 132↔361, 93↔323, 234↔454, 127↔389, 162↔389(近似)
        setPoint(17, getMpPoint(377))    // 下巴中心左侧（对称M15=148）
        setPoint(18, midPoint(getMpPoint(377), getMpPoint(378)))  // 过渡：下巴中心左侧→下巴中左
        setPoint(19, getMpPoint(378))    // 下巴中左（对称M13=176）
        setPoint(20, midPoint(getMpPoint(378), getMpPoint(400)))  // 过渡：下巴中左→下巴左侧
        setPoint(21, getMpPoint(400))    // 下巴左侧（对称M11=149）
        setPoint(22, getMpPoint(379))    // 左下巴边缘（对称M10=150）
        setPoint(23, getMpPoint(397))    // 左下颌角（对称M9=136）
        setPoint(24, getMpPoint(365))    // 左脸颊下（对称M8=172）
        setPoint(25, getMpPoint(288))    // 左脸颊中（对称M7=58）
        setPoint(26, getMpPoint(361))    // 左脸颊上（对称M6=132）
        setPoint(27, getMpPoint(323))    // 左耳下/脸颊起点（对称M5=93）
        setPoint(28, midPoint(getMpPoint(323), getMpPoint(454)))  // 过渡：左耳下→左耳上
        setPoint(29, getMpPoint(454))    // 左耳上（对称M3=234）
        setPoint(30, midPoint(getMpPoint(454), getMpPoint(389)))  // 过渡：左耳上→左鬓角
        setPoint(31, getMpPoint(389))    // 左太阳穴（对称M2=127）
        setPoint(32, getMpPoint(389))    // 左鬓角/轮廓终点（对称M0=162，近似）

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
