package com.picme.features.camera.test

import android.content.Context
import android.content.Intent
import androidx.camera.core.CameraSelector
import com.picme.core.common.Logger
import com.picme.beauty.api.BeautySettings
import com.picme.domain.model.MediaType
import com.picme.features.camera.CameraAspectRatio
import com.picme.features.camera.GridType
import com.picme.features.camera.ScenePreset
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 相机测试命令分发器
 *
 * 单例对象，负责：
 * 1. 接收 [CameraTestCommand] 命令
 * 2. 将命令转换为对 CameraScreen 业务逻辑的调用
 * 3. 通过 [SharedFlow] 发射命令事件，由 CameraScreen 收集并执行
 *
 * 使用方式：
 * ```kotlin
 * // 在 CameraScreen 中收集命令
 * LaunchedEffect(Unit) {
 *     CameraTestCommandDispatcher.commandFlow.collect { command ->
 *         when (command) {
 *             is CameraTestCommand.Capture -> onCaptureClick()
 *             is CameraTestCommand.FlipCamera -> onFlipCamera()
 *             // ...
 *         }
 *     }
 * }
 * ```
 */
object CameraTestCommandDispatcher {

    private const val TAG = "PicMe:CameraTest"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 命令事件流
     * CameraScreen 通过收集此 Flow 来响应测试命令
     */
    private val _commandFlow = MutableSharedFlow<CameraTestCommand>(
        replay = 1,
        extraBufferCapacity = 16
    )
    val commandFlow: SharedFlow<CameraTestCommand> = _commandFlow.asSharedFlow()

    /**
     * 结果事件流（用于命令执行后返回状态）
     */
    private val _resultFlow = MutableSharedFlow<CameraTestResult>(
        extraBufferCapacity = 16
    )
    val resultFlow: SharedFlow<CameraTestResult> = _resultFlow.asSharedFlow()

    /**
     * 当前相机状态快照（由 CameraScreen 定期更新）
     */
    @Volatile
    private var currentState: CameraTestStateSnapshot? = null

    /**
     * 分发命令
     *
     * @param command 要执行的测试命令
     */
    fun dispatch(command: CameraTestCommand) {
        Logger.i(TAG, "Dispatching command: $command")
        val success = _commandFlow.tryEmit(command)
        if (success) {
            Logger.i(TAG, "Command emitted successfully: $command")
        } else {
            Logger.w(TAG, "Command buffer full, command dropped: $command")
        }
    }

    /**
     * 更新当前状态快照
     * 由 CameraScreen 在状态变化时调用
     */
    fun updateState(state: CameraTestStateSnapshot) {
        currentState = state
    }

    /**
     * 处理 GetState 命令，返回当前状态
     */
    fun handleGetState(command: CameraTestCommand.GetState): CameraTestResult {
        val state = currentState
        return if (state != null) {
            CameraTestResult.State(command, state)
        } else {
            CameraTestResult.Error(command, "Camera state not available yet")
        }
    }

    /**
     * 发送命令执行结果
     */
    fun emitResult(result: CameraTestResult) {
        _resultFlow.tryEmit(result)
    }

    /**
     * 解析 Intent 并分发命令
     * 由 BroadcastReceiver 调用
     */
    fun dispatchFromIntent(intent: Intent) {
        val command = CameraTestCommand.fromExtras(intent.extras)
        if (command is CameraTestCommand.Unknown) {
            Logger.w(TAG, "Unknown command received: ${command.rawAction}")
            emitResult(CameraTestResult.Error(command, "Unknown command: ${command.rawAction}"))
            return
        }
        dispatch(command)
    }

    /**
     * 将命令转换为可执行的业务操作描述
     * 用于日志和调试
     */
    fun describeCommand(command: CameraTestCommand): String {
        return when (command) {
            is CameraTestCommand.Capture -> "拍照"
            is CameraTestCommand.FlipCamera -> "切换摄像头"
            is CameraTestCommand.SetMode -> "设置模式: ${command.mode}"
            is CameraTestCommand.SetBeauty -> "设置美颜: smooth=${command.smooth}, whiten=${command.whiten}"
            is CameraTestCommand.SetFilter -> "设置滤镜: ${command.filter}"
            is CameraTestCommand.SetStyle -> "设置风格: ${command.style}"
            is CameraTestCommand.SetScene -> "设置场景: ${command.scene}"
            is CameraTestCommand.SetRatio -> "设置比例: ${command.ratio}"
            is CameraTestCommand.SetExposure -> "设置曝光: ${command.exposure}"
            is CameraTestCommand.SetZoom -> "设置缩放: ${command.zoom}x"
            is CameraTestCommand.ToggleBeautyPanel -> "切换美颜面板"
            is CameraTestCommand.ToggleFilterPanel -> "切换滤镜面板"
            is CameraTestCommand.ToggleSettingsPanel -> "切换设置面板"
            is CameraTestCommand.GetState -> "获取状态"
            is CameraTestCommand.EnterGallery -> "进入相册"
            is CameraTestCommand.OpenPhoto -> "打开照片: index=${command.index}"
            is CameraTestCommand.LongPressPhoto -> "长按照片（触发编辑）"
            is CameraTestCommand.StartEdit -> "进入编辑模式"
            is CameraTestCommand.SaveEdit -> "保存编辑"
            is CameraTestCommand.CancelEdit -> "取消编辑"
            is CameraTestCommand.SetSmooth -> "设置磨皮: ${command.value}"
            is CameraTestCommand.SetWhiten -> "设置美白: ${command.value}"
            is CameraTestCommand.SetEditFilter -> "设置编辑滤镜: ${command.filter}"
            is CameraTestCommand.StartOcr -> "触发 OCR"
            is CameraTestCommand.DismissOcr -> "关闭 OCR"
            is CameraTestCommand.ToggleLandmark -> "切换关键点覆盖层"
            is CameraTestCommand.ToggleInfo -> "切换信息浮层"
            is CameraTestCommand.DeletePhoto -> "删除照片"
            is CameraTestCommand.SharePhoto -> "分享照片"
            is CameraTestCommand.Unknown -> "未知命令: ${command.rawAction}"
        }
    }
}

/**
 * 命令执行器接口
 * 将命令映射到具体的业务逻辑
 */
interface CameraTestCommandExecutor {
    fun executeCapture()
    fun executeFlipCamera()
    fun executeSetMode(mode: MediaType)
    fun executeSetBeauty(settings: BeautySettings)
    fun executeSetFilter(filter: FilterType)
    fun executeSetStyle(style: StyleFilter)
    fun executeSetScene(scene: ScenePreset)
    fun executeSetRatio(ratio: Int)
    fun executeSetExposure(exposure: Int)
    fun executeSetZoom(zoom: Float)
    fun executeToggleBeautyPanel()
    fun executeToggleFilterPanel()
    fun executeToggleSettingsPanel()
    fun getCurrentState(): CameraTestStateSnapshot
}

/**
 * 命令与业务类型的转换辅助函数
 */
object CameraTestCommandConverters {

    fun parseMediaType(mode: String): MediaType {
        return when (mode.lowercase()) {
            "photo" -> MediaType.PHOTO
            "video" -> MediaType.VIDEO
            "pro" -> MediaType.PRO
            else -> MediaType.PHOTO
        }
    }

    fun parseFilterType(filter: String): FilterType {
        return when (filter.lowercase()) {
            "none" -> FilterType.NONE
            "leica_classic" -> FilterType.LEICA_CLASSIC
            "leica_vibrant" -> FilterType.LEICA_VIBRANT
            "leica_bw" -> FilterType.LEICA_BW
            "film_gold" -> FilterType.FILM_GOLD
            "film_fuji" -> FilterType.FILM_FUJI
            "vintage" -> FilterType.VINTAGE
            "cool" -> FilterType.COOL
            "warm" -> FilterType.WARM
            else -> FilterType.NONE
        }
    }

    fun parseStyleFilter(style: String): StyleFilter {
        return when (style.lowercase()) {
            "none" -> StyleFilter.NONE
            "toon" -> StyleFilter.TOON
            "sketch" -> StyleFilter.SKETCH
            "posterize" -> StyleFilter.POSTERIZE
            "emboss" -> StyleFilter.EMBOSS
            "crosshatch" -> StyleFilter.CROSSHATCH
            else -> StyleFilter.NONE
        }
    }

    fun parseScenePreset(scene: String): ScenePreset {
        return when (scene.lowercase()) {
            "none" -> ScenePreset.NONE
            "night" -> ScenePreset.NIGHT
            "moon" -> ScenePreset.MOON
            else -> ScenePreset.NONE
        }
    }

    fun parseAspectRatio(ratio: String): Int {
        return when (ratio.lowercase()) {
            "4_3", "4:3" -> 0
            "16_9", "16:9" -> 1
            "full" -> 2
            else -> 0
        }
    }

    fun mediaTypeToString(mode: MediaType): String {
        return when (mode) {
            MediaType.PHOTO -> "photo"
            MediaType.VIDEO -> "video"
            MediaType.PRO -> "pro"
            else -> "photo"
        }
    }

    fun filterTypeToString(filter: FilterType): String {
        return when (filter) {
            FilterType.NONE -> "none"
            FilterType.LEICA_CLASSIC -> "leica_classic"
            FilterType.LEICA_VIBRANT -> "leica_vibrant"
            FilterType.LEICA_BW -> "leica_bw"
            FilterType.FILM_GOLD -> "film_gold"
            FilterType.FILM_FUJI -> "film_fuji"
            FilterType.VINTAGE -> "vintage"
            FilterType.COOL -> "cool"
            FilterType.WARM -> "warm"
        }
    }

    fun styleFilterToString(style: StyleFilter): String {
        return when (style) {
            StyleFilter.NONE -> "none"
            StyleFilter.TOON -> "toon"
            StyleFilter.SKETCH -> "sketch"
            StyleFilter.POSTERIZE -> "posterize"
            StyleFilter.EMBOSS -> "emboss"
            StyleFilter.CROSSHATCH -> "crosshatch"
        }
    }

    fun scenePresetToString(scene: ScenePreset): String {
        return when (scene) {
            ScenePreset.NONE -> "none"
            ScenePreset.NIGHT -> "night"
            ScenePreset.MOON -> "moon"
        }
    }

    fun aspectRatioToString(ratio: Int): String {
        return when (ratio) {
            0 -> "4_3"
            1 -> "16_9"
            2 -> "full"
            else -> "4_3"
        }
    }

    fun lensFacingToString(lensFacing: Int): String {
        return when (lensFacing) {
            CameraSelector.LENS_FACING_FRONT -> "front"
            CameraSelector.LENS_FACING_BACK -> "back"
            else -> "unknown"
        }
    }
}
