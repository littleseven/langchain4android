package com.mamba.picme.data.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.domain.search.FaceClusteringEngine
import com.mamba.picme.domain.search.FaceFeatureVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 人脸聚类 Worker
 *
 * 扫描所有照片，用 ML Kit Face Detection 检测人脸，
 * 提取面部几何特征（眼距、鼻嘴距、宽高比、五官位置），
 * 使用 DBSCAN 聚类并按人物分配 faceId。
 *
 * 使用 ML Kit Face Detection（CPU，无需 GPU 上下文）。
 */
class FaceClusteringWorker(private val context: Context) {

    companion object {
        private const val TAG = "PicMe:FaceCluster"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    val isRunning: Boolean
        get() = currentJob?.isActive == true

    fun start() {
        if (currentJob?.isActive == true) {
            Logger.d(TAG, "Clustering already in progress")
            return
        }
        currentJob = scope.launch {
            Logger.i(TAG, "Face clustering started")
            doCluster()
            Logger.i(TAG, "Face clustering completed")
        }
    }

    private suspend fun doCluster() {
        val db = AppDatabase.getDatabase(context)
        val dao = db.mediaDao()

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .build()

        val faceDetector = FaceDetection.getClient(options)

        try {
            val allMedia = dao.getAllMediaNow()
            Logger.i(TAG, "Scanning ${allMedia.size} photos for faces")

            val features = mutableMapOf<Long, FaceFeatureVector>()
            var faceCount = 0

            for (entity in allMedia) {
                if (!currentJob?.isActive!!) break

                val uri = Uri.parse(entity.uri)
                val bitmap = tryLoadBitmap(uri)
                if (bitmap == null) {
                    Logger.d(TAG, "Skipping unreadable image ${entity.id}")
                    continue
                }

                try {
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    val faces = Tasks.await(faceDetector.process(inputImage))

                    if (faces.isNotEmpty()) {
                        if (!entity.hasFace) {
                            dao.updateHasFace(entity.id, true)
                        }
                        faceCount++
                        val ft = extractFeature(faces[0])
                        if (ft != null) {
                            features[entity.id] = ft
                        }
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to process ${entity.id}: ${e.message}")
                } finally {
                    bitmap.recycle()
                }
            }

            if (faceCount == 0) {
                Logger.i(TAG, "No faces detected")
                return
            }
            Logger.i(TAG, "Detected faces: $faceCount, with features: ${features.size}")

            if (features.size < 2) {
                Logger.i(TAG, "Too few faces, assigning single cluster")
                for ((mediaId) in features) {
                    dao.updateFaceId(mediaId, "1")
                }
                return
            }

            // eps 越大分组越宽松（同人不同角度越容易归为一组）
            // 0.28 = 严格（适合正面照），0.45 = 宽松（适合日常多角度）
            val clusters = FaceClusteringEngine.cluster(features, eps = 0.45f)
            Logger.i(TAG, "DBSCAN: ${clusters.size} clusters (including noise)")

            var assignedCount = 0
            for ((clusterId, mediaIds) in clusters) {
                if (clusterId == -1) continue
                for (mediaId in mediaIds) {
                    dao.updateFaceId(mediaId, clusterId.toString())
                    assignedCount++
                }
            }
            Logger.i(TAG, "Face clustering done: $assignedCount photos clustered")
        } catch (e: Exception) {
            Logger.e(TAG, "Face clustering failed", e)
        } finally {
            faceDetector.close()
        }
    }

    private fun tryLoadBitmap(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 4
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to load bitmap: $uri", e)
            null
        }
    }

    private fun extractFeature(face: com.google.mlkit.vision.face.Face): FaceFeatureVector? {
        val bbox = face.boundingBox
        val faceW = bbox.width().toFloat()
        val faceH = bbox.height().toFloat()
        if (faceW <= 0 || faceH <= 0) return null

        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val noseBase = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        if (leftEye == null || rightEye == null || noseBase == null || mouthBottom == null) return null

        val eyeDist = distance(leftEye, rightEye)
        val noseMouthDist = distance(noseBase, mouthBottom)

        return FaceFeatureVector(
            eyeDistanceRatio = (eyeDist / faceW).coerceIn(0f, 1f),
            noseToMouthRatio = (noseMouthDist / faceH).coerceIn(0f, 1f),
            faceAspectRatio = (faceW / faceH).coerceIn(0.5f, 2f),
            leftEyeX = ((leftEye.x - bbox.left) / faceW).coerceIn(0f, 1f),
            leftEyeY = ((leftEye.y - bbox.top) / faceH).coerceIn(0f, 1f),
            rightEyeX = ((rightEye.x - bbox.left) / faceW).coerceIn(0f, 1f),
            rightEyeY = ((rightEye.y - bbox.top) / faceH).coerceIn(0f, 1f),
            mouthY = ((mouthBottom.y - bbox.top) / faceH).coerceIn(0f, 1f),
            headYaw = face.headEulerAngleY,
            headRoll = face.headEulerAngleZ
        )
    }

    private fun distance(a: android.graphics.PointF, b: android.graphics.PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
