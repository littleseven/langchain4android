package com.picme.domain.agent.capability

import com.picme.core.common.Logger
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导航 Capability
 *
 * 应用级单例，负责页面导航：切换页面、返回上一页
 * 在所有场景都可用
 *
 * **架构设计**：
 * - 在 Application.onCreate() 中注册一次，永不注销
 * - 通过 bindNavController() 绑定导航控制器
 * - 支持跨页面指令：导航命令可以在任何场景执行
 *
 * **注意**：导航回调涉及 Compose NavController 操作，必须在主线程执行。
 * execute() 内部会自动切换到 Main 线程。
 */
class NavigationCapability : BaseCapability() {

    companion object {
        @Volatile
        private var instance: NavigationCapability? = null

        fun getInstance(): NavigationCapability {
            return instance ?: synchronized(this) {
                instance ?: NavigationCapability().also { instance = it }
            }
        }
    }

    private val tag = "NavigationCapability"

    override val name: String = "navigation"
    override val description: String = "页面导航：切换页面、返回上一页"

    /**
     * 导航控制器引用，由 MainActivity 绑定
     */
    private var navController: androidx.navigation.NavController? = null

    /**
     * 绑定 NavController（由 MainActivity 调用）
     */
    fun bindNavController(navController: androidx.navigation.NavController) {
        this.navController = navController
        Logger.i(tag, "NavController bound, isAvailable=${isAvailable()}")
    }

    /**
     * 解绑 NavController（由 MainActivity onDispose 调用）
     */
    fun unbindNavController() {
        this.navController = null
        Logger.i(tag, "NavController unbound")
    }

    override fun isAvailable(): Boolean {
        // NavigationCapability 只有在 NavController 绑定后才可用
        return navController != null
    }

    override fun activeScenes(): List<SceneManager.Scene> {
        // 导航能力在所有场景都可用
        return SceneManager.Scene.entries.toList()
    }

    override fun supportedCommands(): List<String> = listOf(
        "navigate_to",
        "go_back"
    )

    override fun getCommandDescription(command: String): String = when (command) {
        "navigate_to" -> "导航到指定页面，参数: destination (camera|gallery|settings|debug|model_center)"
        "go_back" -> "返回上一页，无参数"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        Logger.d(tag, "Executing command: ${command::class.simpleName}")

        val nav = navController
            ?: return Result.success(
                AgentAction.Error("导航系统未初始化")
            )

        return when (command) {
            is AgentCommand.NavigateTo -> {
                val destination = parseDestination(command.destination)
                if (destination != null) {
                    withContext(Dispatchers.Main) {
                        navigateTo(nav, destination)
                    }
                    Result.success(AgentAction.Success(command))
                } else {
                    Result.success(
                        AgentAction.Error("未知页面: ${command.destination}，可用页面: camera, gallery, settings, debug, model_center")
                    )
                }
            }

            is AgentCommand.GoBack -> {
                withContext(Dispatchers.Main) {
                    nav.popBackStack()
                }
                Result.success(AgentAction.Success(command))
            }

            else -> {
                Logger.w(tag, "Unsupported command: ${command::class.simpleName}")
                Result.success(AgentAction.Error("导航 Capability 不支持此命令"))
            }
        }
    }

    /**
     * 执行页面导航
     */
    private fun navigateTo(nav: androidx.navigation.NavController, destination: Destination) {
        val route = when (destination) {
            Destination.CAMERA -> "camera"
            Destination.GALLERY -> "gallery"
            Destination.SETTINGS -> "settings"
            Destination.DEBUG -> "debug"
            Destination.MODEL_CENTER -> "model_center"
            Destination.LLM_MODEL_MANAGER -> "model_center"
            Destination.ASR_MODEL_MANAGER -> "model_center"
        }
        try {
            nav.navigate(route)
        } catch (e: Exception) {
            Logger.e(tag, "Navigation failed to $route", e)
        }
    }

    private fun parseDestination(destination: String): Destination? {
        return when (destination.lowercase()) {
            "camera", "相机", "拍照", "拍摄" -> Destination.CAMERA
            "gallery", "相册", "照片", "图库" -> Destination.GALLERY
            "settings", "设置", "配置" -> Destination.SETTINGS
            "debug", "调试" -> Destination.DEBUG
            "model_center", "模型中心", "模型管理" -> Destination.MODEL_CENTER
            "llm_model_manager", "llm模型管理", "大模型管理" -> Destination.LLM_MODEL_MANAGER
            "asr_model_manager", "asr模型管理", "语音模型管理" -> Destination.ASR_MODEL_MANAGER
            else -> null
        }
    }

    /**
     * 导航目标
     */
    enum class Destination {
        CAMERA,             // 相机页
        GALLERY,            // 相册页
        SETTINGS,           // 设置页
        DEBUG,              // 调试页
        MODEL_CENTER,       // 模型中心
        LLM_MODEL_MANAGER,  // 旧：LLM模型管理页（已废弃，路由到模型中心）
        ASR_MODEL_MANAGER   // 旧：ASR模型管理页（已废弃，路由到模型中心）
    }
}
