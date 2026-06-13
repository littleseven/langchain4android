package com.mamba.picme.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import com.mamba.picme.agent.core.platform.voice.KeywordSpotterEngine
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.render.GlBeautyPreviewProviderFactory
import com.mamba.picme.beauty.api.BeautyProcessor
import com.mamba.picme.core.image.GpuBeautyProcessor
import com.mamba.picme.core.image.ImageProcessor
import com.mamba.picme.core.image.ImageProcessorImpl
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.beauty.api.facedetect.FaceDetectorFactory
import com.mamba.picme.data.local.MlKitOcrProcessor
import com.mamba.picme.data.preferences.UserPreferencesRepository
import com.mamba.picme.data.repository.MediaRepositoryImpl
import com.mamba.picme.domain.repository.MediaRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.data.download.LlmModelDownloadManager
import com.mamba.picme.data.download.ModelPathConfig
import com.mamba.picme.domain.usecase.FindDuplicateMediaUseCase
import com.mamba.picme.domain.usecase.GetGroupedMediaUseCase
import com.mamba.picme.domain.usecase.OcrProcessor
import com.mamba.picme.features.chat.ChatViewModel
import com.mamba.picme.features.chat.ChatViewModelDependencies
import com.mamba.picme.features.gallery.MediaViewModel
import androidx.lifecycle.ViewModel

data class MediaViewModelDependencies(
    val repository: MediaRepository,
    val getGroupedMediaUseCase: GetGroupedMediaUseCase,
    val findDuplicateMediaUseCase: FindDuplicateMediaUseCase,
    val ocrUseCase: OcrProcessor,
    val photoProcessor: PhotoProcessor,
    val faceDetector: FaceDetector
)

class MediaViewModelFactory(
    private val dependencies: MediaViewModelDependencies
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MediaViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MediaViewModel(
                repository = dependencies.repository,
                getGroupedMediaUseCase = dependencies.getGroupedMediaUseCase,
                findDuplicateMediaUseCase = dependencies.findDuplicateMediaUseCase,
                ocrUseCase = dependencies.ocrUseCase,
                photoProcessor = dependencies.photoProcessor,
                faceDetector = dependencies.faceDetector
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class ChatViewModelFactory(
    private val dependencies: ChatViewModelDependencies
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(dependencies) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

interface AppContainer {
    val repository: MediaRepository
    val userPreferencesRepository: UserSettingsRepository
    val imageProcessor: ImageProcessor
    val faceDetector: FaceDetector
    val llmModelDownloadManager: LlmModelDownloadManager
    val kwsEngine: KeywordSpotterEngine?  // 【新增】KWS 唤醒词引擎（可选）

    fun createMediaViewModelFactory(): ViewModelProvider.Factory
    fun createChatViewModelFactory(): ViewModelProvider.Factory
}

class AppContainerImpl(private val context: Context) : AppContainer {

    private val database by lazy { AppDatabase.getDatabase(context) }

    /**
     * 静态 Bitmap 美颜处理器（拍照后 CPU 路径）。
     * ⚠️ 仅用于拍照后的静态图像后处理，与实时预览无关。
     * 实时预览美颜由 beauty-engine 模块的 BeautyPreviewEngine（GPU 路径）负责。
     */
    private val beautyProcessor: BeautyProcessor by lazy {
        GpuBeautyProcessor(context)
    }

    override val repository: MediaRepository by lazy {
        MediaRepositoryImpl(database.mediaDao(), context)
    }

    override val userPreferencesRepository: UserSettingsRepository by lazy {
        UserPreferencesRepository(context)
    }

    override val faceDetector: FaceDetector by lazy {
        FaceDetectorFactory.create(context)
    }

    private val photoProcessor: PhotoProcessor by lazy {
        GlBeautyPreviewProviderFactory().createPhotoProcessor(context)
    }

    override val imageProcessor: ImageProcessor by lazy {
        ImageProcessorImpl(beautyProcessor, photoProcessor, faceDetector)
    }

    override val llmModelDownloadManager: LlmModelDownloadManager by lazy {
        LlmModelDownloadManager(context)
    }

    /**
     * 【新增】KWS 唤醒词引擎（sherpa-onnx 版）
     *
     * 用于低功耗的 always-on 唤醒词检测。
     * 【重要】不允许自动降级，如果 KWS 初始化失败就直接抛异常！
     *
     * 【模型路径管理】
     * 使用 ModelPathConfig 统一管理模型路径，避免硬编码。
     * 支持灵活扩展新模型，只需在 ModelPathConfig 中添加配置。
     */
    override val kwsEngine: KeywordSpotterEngine? by lazy {
        // KWS 引擎初始化：必须成功，否则抛异常
        // 【强制要求】不允许任何自动降级或 fallback

        val kwsModelDir = ModelPathConfig.getKwsModelDir(context)
        Logger.i("AppContainer", "【KWS 初始化】Starting KWS engine initialization...")
        Logger.i("AppContainer", "  Model path: ${kwsModelDir.absolutePath}")

        // 【安全保护】在调用 native 构造前，先验证模型文件完整性
        // KeywordSpotter(null, config) 内部会启动 ONNX Runtime 线程池，
        // 如果模型不兼容或损坏，native 层会触发 FORTIFY pthread_mutex_lock
        // on destroyed mutex 崩溃（SIGABRT），此崩溃不可被 Kotlin try-catch 捕获。
        val missingFiles = ModelPathConfig.getMissingFiles(kwsModelDir, ModelPathConfig.KWS_MODEL_FILES)
        if (missingFiles.isNotEmpty()) {
            val errorMsg = buildString {
                append("❌ KWS 模型文件缺失，跳过 native 初始化以避免崩溃\n")
                append("  Model dir: ${kwsModelDir.absolutePath}\n")
                append("  Missing files: ${missingFiles.joinToString(", ")}\n")
                append("  Expected ${ModelPathConfig.KWS_MODEL_FILES.size} files, " +
                    "found ${ModelPathConfig.KWS_MODEL_FILES.size - missingFiles.size}\n")
                append("【安全降级】返回 null，KWS 唤醒词功能不可用")
            }
            Logger.w("AppContainer", errorMsg)
            return@lazy null
        }

        // 【安全保护】验证模型文件非空（损坏文件可能导致 native crash）
        val emptyFiles = ModelPathConfig.KWS_MODEL_FILES.filter { fileName ->
            val file = java.io.File(kwsModelDir, fileName)
            file.exists() && file.length() == 0L
        }
        if (emptyFiles.isNotEmpty()) {
            val errorMsg = buildString {
                append("❌ KWS 模型文件为空（可能损坏），跳过 native 初始化以避免崩溃\n")
                append("  Model dir: ${kwsModelDir.absolutePath}\n")
                append("  Empty files: ${emptyFiles.joinToString(", ")}\n")
                append("【安全降级】返回 null，KWS 唤醒词功能不可用")
            }
            Logger.w("AppContainer", errorMsg)
            return@lazy null
        }

        Logger.i("AppContainer", "✓ KWS model files validated, creating KeywordSpotterEngine...")
        val kwsEngine = KeywordSpotterEngine(kwsModelDir.absolutePath)

        // 检查模型可用性（内部调用 KeywordSpotter native 构造）
        if (!kwsEngine.isAvailable()) {
            val errorMsg = buildString {
                append("❌ KWS 引擎初始化失败 - native 构造返回不可用\n")
                append("  Model dir: ${kwsModelDir.absolutePath}\n")
                append("【安全降级】返回 null，KWS 唤醒词功能不可用")
            }
            Logger.w("AppContainer", errorMsg)
            return@lazy null
        }

        Logger.i("AppContainer", "✓ KWS engine initialized successfully")
        Logger.i("AppContainer", "  Keywords: ${kwsEngine.getKeywords().joinToString(", ")}")

        kwsEngine
    }

    private val ocrProcessor: OcrProcessor by lazy {
        MlKitOcrProcessor()
    }

    private val mediaViewModelDependencies: MediaViewModelDependencies by lazy {
        MediaViewModelDependencies(
            repository = repository,
            getGroupedMediaUseCase = GetGroupedMediaUseCase(),
            findDuplicateMediaUseCase = FindDuplicateMediaUseCase(repository),
            ocrUseCase = ocrProcessor,
            photoProcessor = photoProcessor,
            faceDetector = faceDetector
        )
    }

    private val mediaViewModelFactory: ViewModelProvider.Factory by lazy {
        MediaViewModelFactory(mediaViewModelDependencies)
    }

    private val chatViewModelDependencies: ChatViewModelDependencies by lazy {
        ChatViewModelDependencies(
            context = context,
            chatMessageDao = database.chatMessageDao(),
            chatSessionDao = database.chatSessionDao()
        )
    }

    private val chatViewModelFactory: ViewModelProvider.Factory by lazy {
        ChatViewModelFactory(chatViewModelDependencies)
    }

    override fun createMediaViewModelFactory(): ViewModelProvider.Factory {
        return mediaViewModelFactory
    }

    override fun createChatViewModelFactory(): ViewModelProvider.Factory {
        return chatViewModelFactory
    }
}
