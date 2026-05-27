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
        EDITOR,      // 照片编辑页
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
     * 切换到指定场景
     *
     * @param scene 目标场景
     * @param saveToHistory 是否保存到历史（用于返回导航）
     */
    fun transitionTo(scene: Scene, saveToHistory: Boolean = true) {
        if (saveToHistory && _currentScene.value != Scene.UNKNOWN) {
            sceneHistory.add(_currentScene.value)
        }
        _currentScene.value = scene
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
        Scene.EDITOR -> listOf("edit", "navigation")
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
        Scene.EDITOR -> "照片编辑页面，可以编辑照片"
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
