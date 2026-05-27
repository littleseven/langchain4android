package com.picme.testing.agent.bridge

import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.picme.core.common.Logger

/**
 * Agent 测试命令集成
 *
 * 在 CameraScreen / GalleryScreen 中通过 Compose DisposableEffect 注册 AgentTestBroadcastReceiver，
 * 使 AI Agent 可以在任意界面发送测试命令。
 *
 * 使用方式：
 * ```kotlin
 * @Composable
 * fun CameraScreen(...) {
 *     AgentTestCommandIntegration()
 *     // ... 其他 UI
 * }
 * ```
 */
@Composable
fun AgentTestCommandIntegration() {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        Logger.i("PicMe:AgentTest", "Registering AgentTestBroadcastReceiver")

        val receiver = AgentTestBroadcastReceiver()
        val filter = IntentFilter(AgentTestBroadcastReceiver.ACTION_AGENT_TEST)
        context.registerReceiver(receiver, filter)

        onDispose {
            Logger.i("PicMe:AgentTest", "Unregistering AgentTestBroadcastReceiver")
            context.unregisterReceiver(receiver)
        }
    }
}
