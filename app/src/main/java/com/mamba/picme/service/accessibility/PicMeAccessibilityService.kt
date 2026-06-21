package com.mamba.picme.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.platform.logging.Logger

/**
 * PicMe 无障碍服务
 *
 * 用于执行用户主动发起的跨应用自动化操作（点击、输入、滚动、返回等）。
 * 默认不开启；用户需在系统设置中手动启用，并可在 PicMe 设置中随时跳转到系统设置关闭。
 *
 * 隐私说明：
 * - 不监听、不记录、不上传任何界面节点文本；
 * - 仅在收到用户显式指令时执行一次性质的动作；
 * - 节点文本仅用于匹配目标，执行完成后立即释放节点引用。
 */
class PicMeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PicMeAccessibilityService"

        /**
         * 跳转到系统无障碍设置页
         */
        fun openSettingsIntent(): Intent {
            return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityController.attachService(this)
        Logger.i(TAG, "Accessibility service connected")
    }

    override fun onInterrupt() {
        Logger.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        AccessibilityController.detachService(this)
        Logger.i(TAG, "Accessibility service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 不常驻监听事件；动作由 Agent 指令显式触发
    }

    /**
     * 执行单个无障碍动作
     */
    fun performAction(action: AccessibilityAction) {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Logger.w(TAG, "No active window content available")
            return
        }

        try {
            when (action.action.lowercase()) {
                "click" -> performClick(rootNode, action.target)
                "long_click" -> performLongClick(rootNode, action.target)
                "input" -> performInput(rootNode, action.target, action.params)
                "scroll_forward" -> performScroll(rootNode, action.target, forward = true)
                "scroll_backward" -> performScroll(rootNode, action.target, forward = false)
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recent" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                else -> Logger.w(TAG, "Unsupported accessibility action: ${action.action}")
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun performClick(rootNode: AccessibilityNodeInfo, target: AgentCommand.AccessibilityTarget?) {
        val node = findTargetNode(rootNode, target) ?: run {
            Logger.w(TAG, "Click target not found: $target")
            return
        }
        val performed = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Logger.i(TAG, "Click performed on $target, success=$performed")
        node.recycle()
    }

    private fun performLongClick(rootNode: AccessibilityNodeInfo, target: AgentCommand.AccessibilityTarget?) {
        val node = findTargetNode(rootNode, target) ?: run {
            Logger.w(TAG, "Long-click target not found: $target")
            return
        }
        val performed = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        Logger.i(TAG, "Long-click performed on $target, success=$performed")
        node.recycle()
    }

    private fun performInput(
        rootNode: AccessibilityNodeInfo,
        target: AgentCommand.AccessibilityTarget?,
        params: Map<String, String>
    ) {
        val text = params["text"] ?: run {
            Logger.w(TAG, "Input action missing text param")
            return
        }

        val node = if (target != null) {
            findTargetNode(rootNode, target)
        } else {
            findFirstInputNode(rootNode)
        }

        if (node == null) {
            Logger.w(TAG, "Input target not found: $target")
            return
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val performed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Logger.i(TAG, "Input performed on $target, success=$performed")
        node.recycle()
    }

    private fun performScroll(
        rootNode: AccessibilityNodeInfo,
        target: AgentCommand.AccessibilityTarget?,
        forward: Boolean
    ) {
        val node = if (target != null) {
            findTargetNode(rootNode, target)
        } else {
            rootNode
        }

        if (node == null) {
            Logger.w(TAG, "Scroll target not found: $target")
            return
        }

        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val performed = node.performAction(action)
        Logger.i(TAG, "Scroll performed on $target, forward=$forward, success=$performed")
        if (node != rootNode) {
            node.recycle()
        }
    }

    /**
     * 在窗口节点树中查找目标节点
     */
    private fun findTargetNode(rootNode: AccessibilityNodeInfo, target: AgentCommand.AccessibilityTarget?): AccessibilityNodeInfo? {
        if (target == null) return null

        val candidates = when (target.type.lowercase()) {
            "text" -> rootNode.findAccessibilityNodeInfosByText(target.value)
            "content_desc" -> findNodesByContentDescription(rootNode, target.value)
            "resource_id" -> rootNode.findAccessibilityNodeInfosByViewId(target.value)
            "class_name" -> findNodesByClassName(rootNode, target.value)
            "bounds" -> findNodesByBounds(rootNode, target.value)
            else -> emptyList()
        }

        val node = candidates.getOrNull(target.index.coerceAtLeast(0))
        // findAccessibilityNodeInfosByText 返回的列表元素不需要单独 recycle，
        // 但返回给调用者的节点需要调用者负责 recycle。
        candidates.forEach { if (it != node) it.recycle() }
        return node
    }

    private fun findFirstInputNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodesByClassName(rootNode, "android.widget.EditText").firstOrNull()
    }

    private fun findNodesByContentDescription(
        rootNode: AccessibilityNodeInfo,
        description: String
    ): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        traverse(rootNode) { node ->
            if (node.contentDescription?.toString() == description) {
                result.add(AccessibilityNodeInfo.obtain(node))
            }
        }
        return result
    }

    private fun findNodesByClassName(
        rootNode: AccessibilityNodeInfo,
        className: String
    ): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        traverse(rootNode) { node ->
            if (node.className?.toString() == className) {
                result.add(AccessibilityNodeInfo.obtain(node))
            }
        }
        return result
    }

    private fun findNodesByBounds(
        rootNode: AccessibilityNodeInfo,
        boundsString: String
    ): List<AccessibilityNodeInfo> {
        val parts = boundsString.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 4) return emptyList()

        val result = mutableListOf<AccessibilityNodeInfo>()
        traverse(rootNode) { node ->
            val rect = android.graphics.Rect().apply { node.getBoundsInScreen(this) }
            if (rect.left == parts[0] && rect.top == parts[1] &&
                rect.right == parts[2] && rect.bottom == parts[3]
            ) {
                result.add(AccessibilityNodeInfo.obtain(node))
            }
        }
        return result
    }

    private fun traverse(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        action(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverse(child, action)
            child.recycle()
        }
    }
}
