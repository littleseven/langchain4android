package com.picme.features.camera.test

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.picme.core.common.Logger

/**
 * 相机测试命令广播接收器
 *
 * 接收通过 adb 发送的测试命令广播，并分发给 [CameraTestCommandDispatcher]。
 *
 * ## 使用方式
 *
 * ### 1. 在 AndroidManifest.xml 中注册（静态注册）
 * ```xml
 * <receiver
 *     android:name=".features.camera.test.CameraTestCommandReceiver"
 *     android:exported="true"
 *     android:permission="android.permission.BROADCAST_STICKY">
 *     <intent-filter>
 *         <action android:name="com.picme.TEST_COMMAND" />
 *     </intent-filter>
 * </receiver>
 * ```
 *
 * ### 2. adb 命令示例
 *
 * 拍照：
 * ```bash
 * adb shell am broadcast -a com.picme.TEST_COMMAND --es action "capture"
 * ```
 *
 * 切换摄像头：
 * ```bash
 * adb shell am broadcast -a com.picme.TEST_COMMAND --es action "flip_camera"
 * ```
 *
 * 切换视频模式：
 * ```bash
 * adb shell am broadcast -a com.picme.TEST_COMMAND --es action "set_mode" --es mode "video"
 * ```
 *
 * 设置美颜参数：
 * ```bash
 * adb shell am broadcast -a com.picme.TEST_COMMAND --es action "set_beauty" --ei smooth 80 --ei whiten 60
 * ```
 *
 * 设置滤镜：
 * ```bash
 * adb shell am broadcast -a com.picme.TEST_COMMAND --es action "set_filter" --es filter "vivid"
 * ```
 *
 * 获取当前状态：
 * ```bash
 * adb shell am broadcast -a com.picme.TEST_COMMAND --es action "get_state"
 * ```
 *
 * 切换场景：
 * ```bash
 * adb shell am broadcast -a com.picme.TEST_COMMAND --es action "set_scene" --es scene "night"
 * ```
 *
 * 设置缩放：
 * ```bash
 * adb shell am broadcast -a com.picme.TEST_COMMAND --es action "set_zoom" --ef zoom 2.0
 * ```
 *
 * 设置曝光：
 * ```bash
 * adb shell am broadcast -a com.picme.TEST_COMMAND --es action "set_exposure" --ei exposure 1
 * ```
 *
 * 切换画幅：
 * ```bash
 * adb shell am broadcast -a com.picme.TEST_COMMAND --es action "set_ratio" --es ratio "16_9"
 * ```
 */
class CameraTestCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CameraTestReceiver"

        /** 广播 Action 名称 */
        const val ACTION_TEST_COMMAND = "com.picme.TEST_COMMAND"

        /** 命令参数字段名 */
        const val EXTRA_ACTION = "action"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TEST_COMMAND) {
            Logger.w(TAG, "Received unexpected action: ${intent.action}")
            return
        }

        val action = intent.getStringExtra(EXTRA_ACTION) ?: "unknown"
        Logger.i(TAG, "Received test command: action=$action")

        try {
            CameraTestCommandDispatcher.dispatchFromIntent(intent)
        } catch (error: Exception) {
            Logger.e(TAG, "Failed to dispatch command: $action", error)
        }
    }
}
