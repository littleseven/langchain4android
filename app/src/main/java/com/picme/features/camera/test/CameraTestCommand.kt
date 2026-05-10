package com.picme.features.camera.test

import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter

/**
 * 相机测试命令 Sealed Class
 *
 * 定义所有可通过 adb 命令触发的相机操作。
 * 每个命令对应一个具体的业务操作，可直接映射到 CameraScreen 的 Action。
 *
 * adb 命令格式：
 * ```
 * adb shell am broadcast -a com.picme.TEST_COMMAND --es action "capture"
 * ```
 */
sealed class CameraTestCommand {

    /**
     * 拍照命令
     *
     * adb: --es action "capture"
     */
    data object Capture : CameraTestCommand()

    /**
     * 切换前后摄像头
     *
     * adb: --es action "flip_camera"
     */
    data object FlipCamera : CameraTestCommand()

    /**
     * 切换拍摄模式
     *
     * @param mode 模式名称: "photo" | "video" | "pro"
     *
     * adb: --es action "set_mode" --es mode "video"
     */
    data class SetMode(val mode: String) : CameraTestCommand()

    /**
     * 设置美颜参数
     *
     * @param smooth 磨皮强度 0-100
     * @param whiten 美白强度 0-100
     * @param slimFace 瘦脸强度 0-100
     * @param bigEye 大眼强度 0-100
     *
     * adb: --es action "set_beauty" --ei smooth 80 --ei whiten 60
     */
    data class SetBeauty(
        val smooth: Int? = null,
        val whiten: Int? = null,
        val slimFace: Int? = null,
        val bigEye: Int? = null
    ) : CameraTestCommand()

    /**
     * 设置滤镜
     *
     * @param filter 滤镜名称: "none" | "vivid" | "warm" | "cool" | "bw"
     *
     * adb: --es action "set_filter" --es filter "vivid"
     */
    data class SetFilter(val filter: String) : CameraTestCommand()

    /**
     * 设置风格滤镜
     *
     * @param style 风格名称: "none" | "japanese" | "film" | "retro"
     *
     * adb: --es action "set_style" --es style "japanese"
     */
    data class SetStyle(val style: String) : CameraTestCommand()

    /**
     * 切换场景模式
     *
     * @param scene 场景: "none" | "night" | "moon"
     *
     * adb: --es action "set_scene" --es scene "night"
     */
    data class SetScene(val scene: String) : CameraTestCommand()

    /**
     * 切换画幅比例
     *
     * @param ratio 比例: "4_3" | "16_9" | "full"
     *
     * adb: --es action "set_ratio" --es ratio "16_9"
     */
    data class SetRatio(val ratio: String) : CameraTestCommand()

    /**
     * 设置曝光补偿
     *
     * @param exposure 曝光值 -2 ~ 2
     *
     * adb: --es action "set_exposure" --ei exposure 1
     */
    data class SetExposure(val exposure: Int) : CameraTestCommand()

    /**
     * 设置缩放级别
     *
     * @param zoom 缩放倍数
     *
     * adb: --es action "set_zoom" --ef zoom 2.0
     */
    data class SetZoom(val zoom: Float) : CameraTestCommand()

    /**
     * 打开/关闭美颜面板
     *
     * adb: --es action "toggle_beauty"
     */
    data object ToggleBeautyPanel : CameraTestCommand()

    /**
     * 打开/关闭滤镜面板
     *
     * adb: --es action "toggle_filter"
     */
    data object ToggleFilterPanel : CameraTestCommand()

    /**
     * 打开/关闭设置面板
     *
     * adb: --es action "toggle_settings"
     */
    data object ToggleSettingsPanel : CameraTestCommand()

    /**
     * 获取当前相机状态
     *
     * adb: --es action "get_state"
     */
    data object GetState : CameraTestCommand()

    // ============================================
    // 相册 / 媒体查看器 测试命令
    // ============================================

    /**
     * 进入相册（从相机界面）
     *
     * adb: --es action "enter_gallery"
     */
    data object EnterGallery : CameraTestCommand()

    /**
     * 打开指定索引的照片进行预览
     *
     * @param index 照片索引，从 0 开始
     *
     * adb: --es action "open_photo" --ei index 0
     */
    data class OpenPhoto(val index: Int) : CameraTestCommand()

    /**
     * 长按当前照片（触发编辑模式）
     *
     * adb: --es action "long_press_photo"
     */
    data object LongPressPhoto : CameraTestCommand()

    /**
     * 进入照片编辑模式（通过工具栏按钮）
     *
     * adb: --es action "start_edit"
     */
    data object StartEdit : CameraTestCommand()

    /**
     * 保存编辑后的照片
     *
     * adb: --es action "save_edit"
     */
    data object SaveEdit : CameraTestCommand()

    /**
     * 取消编辑
     *
     * adb: --es action "cancel_edit"
     */
    data object CancelEdit : CameraTestCommand()

    /**
     * 设置编辑模式磨皮强度
     *
     * @param value 强度值 0-100
     *
     * adb: --es action "set_smooth" --ei value 80
     */
    data class SetSmooth(val value: Int) : CameraTestCommand()

    /**
     * 设置编辑模式美白强度
     *
     * @param value 强度值 0-100
     *
     * adb: --es action "set_whiten" --ei value 60
     */
    data class SetWhiten(val value: Int) : CameraTestCommand()

    /**
     * 设置编辑模式滤镜
     *
     * @param filter 滤镜名称
     *
     * adb: --es action "set_edit_filter" --es filter "leica_classic"
     */
    data class SetEditFilter(val filter: String) : CameraTestCommand()

    /**
     * 触发 OCR 识别
     *
     * adb: --es action "start_ocr"
     */
    data object StartOcr : CameraTestCommand()

    /**
     * 关闭 OCR 结果浮层
     *
     * adb: --es action "dismiss_ocr"
     */
    data object DismissOcr : CameraTestCommand()

    /**
     * 切换人脸关键点覆盖层显示
     *
     * adb: --es action "toggle_landmark"
     */
    data object ToggleLandmark : CameraTestCommand()

    /**
     * 切换照片信息浮层
     *
     * adb: --es action "toggle_info"
     */
    data object ToggleInfo : CameraTestCommand()

    /**
     * 删除当前照片
     *
     * adb: --es action "delete_photo"
     */
    data object DeletePhoto : CameraTestCommand()

    /**
     * 分享当前照片
     *
     * adb: --es action "share_photo"
     */
    data object SharePhoto : CameraTestCommand()

    /**
     * 未知/无效命令
     */
    data class Unknown(val rawAction: String) : CameraTestCommand()

    companion object {
        private const val TAG = "PicMe:CameraTestCommand"

        /**
         * 从 Intent extras 解析命令
         */
        fun fromExtras(extras: android.os.Bundle?): CameraTestCommand {
            if (extras == null) return Unknown("null_extras")

            val action = extras.getString("action") ?: return Unknown("missing_action")

            return when (action.lowercase()) {
                "capture" -> Capture
                "flip_camera" -> FlipCamera
                "set_mode" -> {
                    val mode = extras.getString("mode") ?: "photo"
                    SetMode(mode)
                }
                "set_beauty" -> SetBeauty(
                    smooth = extras.getInt("smooth", -1).takeIf { it >= 0 },
                    whiten = extras.getInt("whiten", -1).takeIf { it >= 0 },
                    slimFace = extras.getInt("slim_face", -1).takeIf { it >= 0 },
                    bigEye = extras.getInt("big_eye", -1).takeIf { it >= 0 }
                )
                "set_filter" -> SetFilter(extras.getString("filter") ?: "none")
                "set_style" -> SetStyle(extras.getString("style") ?: "none")
                "set_scene" -> SetScene(extras.getString("scene") ?: "none")
                "set_ratio" -> SetRatio(extras.getString("ratio") ?: "4_3")
                "set_exposure" -> SetExposure(extras.getInt("exposure", 0))
                "set_zoom" -> SetZoom(extras.getFloat("zoom", 1.0f))
                "toggle_beauty" -> ToggleBeautyPanel
                "toggle_filter" -> ToggleFilterPanel
                "toggle_settings" -> ToggleSettingsPanel
                "get_state" -> GetState
                // Gallery / MediaPager commands
                "enter_gallery" -> EnterGallery
                "open_photo" -> OpenPhoto(extras.getInt("index", 0))
                "long_press_photo" -> LongPressPhoto
                "start_edit" -> StartEdit
                "save_edit" -> SaveEdit
                "cancel_edit" -> CancelEdit
                "set_smooth" -> SetSmooth(extras.getInt("value", 0))
                "set_whiten" -> SetWhiten(extras.getInt("value", 0))
                "set_edit_filter" -> SetEditFilter(extras.getString("filter") ?: "none")
                "start_ocr" -> StartOcr
                "dismiss_ocr" -> DismissOcr
                "toggle_landmark" -> ToggleLandmark
                "toggle_info" -> ToggleInfo
                "delete_photo" -> DeletePhoto
                "share_photo" -> SharePhoto
                else -> Unknown(action)
            }
        }
    }
}

/**
 * 命令执行结果
 */
sealed class CameraTestResult {
    abstract val command: CameraTestCommand

    data class Success(
        override val command: CameraTestCommand,
        val message: String = "OK"
    ) : CameraTestResult()

    data class Error(
        override val command: CameraTestCommand,
        val error: String
    ) : CameraTestResult()

    data class State(
        override val command: CameraTestCommand,
        val state: CameraTestStateSnapshot
    ) : CameraTestResult()
}

/**
 * 相机状态快照（用于 get_state 命令返回）
 */
data class CameraTestStateSnapshot(
    val lensFacing: String,
    val captureMode: String,
    val aspectRatio: String,
    val zoomRatio: Float,
    val exposureCompensation: Int,
    val currentScene: String,
    val currentFilter: String,
    val currentStyle: String,
    val beautyEnabled: Boolean,
    val beautySmooth: Float,
    val beautyWhiten: Float,
    val isRecording: Boolean,
    val isAnyPanelOpen: Boolean
)
