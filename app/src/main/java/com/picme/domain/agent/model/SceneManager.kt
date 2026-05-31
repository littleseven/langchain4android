package com.picme.domain.agent.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 场景管理器
 *
 * 负责：
 * 1. 跟踪当前活跃场景
 * 2. 根据场景获取对应的 Capability 集合
 * 3. 管理场景切换时的上下文保存/恢复
 */
class SceneManager private constructor() {

    companion object {
        @Volatile
        private var instance: SceneManager? = null

        fun getInstance(): SceneManager {
            return instance ?: synchronized(this) {
                instance ?: SceneManager().also { instance = it }
            }
        }
    }

    /**
     * 应用场景枚举
     */
    enum class Scene {
        CAMERA,      // 相机拍摄页
        GALLERY,     // 相册浏览页
        SETTINGS,    // 设置页
        DEBUG,       // 调试页
        UNKNOWN      // 未知/初始状态
    }

    private val _currentScene = MutableStateFlow(Scene.UNKNOWN)

    /**
     * 当前活跃场景
     */
    val currentScene: StateFlow<Scene> = _currentScene.asStateFlow()

    /**
     * 场景切换历史（用于上下文恢复）
     */
    private val sceneHistory = mutableListOf<Scene>()

    /**
     * 活跃场景引用计数
     *
     * 解决 Compose 导航时旧页面 onDispose 与新页面 DisposableEffect 的时序竞争问题。
     * 当导航发生时，旧页面的 onDispose 可能在新页面的 DisposableEffect 之前执行，
     * 导致中间状态为 UNKNOWN。
     *
     * 使用引用计数：只有当一个场景的引用计数降为 0 时，才真正切换到 UNKNOWN。
     */
    private val activeSceneRefs = mutableMapOf<Scene, Int>()

    /**
     * 切换到指定场景
     *
     * @param scene 目标场景
     * @param saveToHistory 是否保存到历史（用于返回导航）
     */
    fun transitionTo(scene: Scene, saveToHistory: Boolean = true) {
        if (saveToHistory && _currentScene.value != Scene.UNKNOWN) {
            sceneHistory.add(_currentScene.value)
        }
        // 增加目标场景的引用计数
        activeSceneRefs[scene] = (activeSceneRefs[scene] ?: 0) + 1
        _currentScene.value = scene
    }

    /**
     * 离开指定场景
     *
     * 使用引用计数：只有引用计数降为 0 时才切换到 UNKNOWN。
     * 这避免了 Compose 导航时的时序竞争问题。
     */
    fun leaveScene(scene: Scene) {
        val currentCount = activeSceneRefs[scene] ?: 0
        if (currentCount > 0) {
            activeSceneRefs[scene] = currentCount - 1
        }
        // 只有当该场景的引用计数降为 0，且当前场景就是它时，才切换到 UNKNOWN
        if ((activeSceneRefs[scene] ?: 0) == 0 && _currentScene.value == scene) {
            _currentScene.value = Scene.UNKNOWN
        }
    }

    /**
     * 返回上一场景
     *
     * @return 是否成功返回（历史不为空时返回true）
     */
    fun navigateBack(): Boolean {
        return if (sceneHistory.isNotEmpty()) {
            val previous = sceneHistory.removeAt(sceneHistory.size - 1)
            _currentScene.value = previous
            true
        } else {
            false
        }
    }

    /**
     * 清空场景历史
     */
    fun clearHistory() {
        sceneHistory.clear()
        activeSceneRefs.clear()
    }

    /**
     * 获取场景对应的 Capability 名称列表
     *
     * @param scene 目标场景
     * @return 该场景下可用的 Capability 名称列表
     */
    fun getCapabilitiesForScene(scene: Scene): List<String> = when (scene) {
        Scene.CAMERA -> listOf("camera", "navigation")
        Scene.GALLERY -> listOf("gallery", "navigation")
        Scene.SETTINGS -> listOf("settings", "navigation")
        // Scene.EDITOR -> listOf("edit", "navigation") // 预留：照片编辑页暂未实现独立路由
        Scene.DEBUG -> listOf("navigation")
        Scene.UNKNOWN -> listOf("navigation")
    }

    /**
     * 获取场景的友好描述（用于 system prompt）
     */
    fun getSceneDescription(scene: Scene): String = when (scene) {
        Scene.CAMERA -> "相机拍摄页面，可以拍照、录像、调节美颜参数"
        Scene.GALLERY -> "相册页面，可以查看、删除、分享照片"
        Scene.SETTINGS -> "设置页面，可以调整应用配置"
        // Scene.EDITOR -> "照片编辑页面，可以编辑照片" // 预留：照片编辑页暂未实现独立路由
        Scene.DEBUG -> "调试页面"
        Scene.UNKNOWN -> "未知页面"
    }

    /**
     * 检查某 Capability 在指定场景是否可用
     */
    fun isCapabilityAvailableInScene(capabilityName: String, scene: Scene): Boolean {
        return getCapabilitiesForScene(scene).contains(capabilityName)
    }
}
