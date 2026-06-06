@file:Suppress("OPT_IN_USAGE_ERROR")

package com.picme

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.picme.core.designsystem.PicMeTheme
import com.picme.data.preferences.UserPreferencesRepository
import com.picme.domain.model.AppLanguage
import com.picme.features.camera.CameraScreen
import com.picme.features.debug.DebugScreen
import com.picme.features.gallery.GalleryScreen
import com.picme.features.gallery.MediaViewModel
import com.picme.features.settings.ModelCenterScreen
import com.picme.features.settings.SettingsScreen
import com.picme.features.settings.SettingsViewModel
import com.picme.features.settings.SettingsViewModelFactory
import com.picme.features.debug.LogOverlay
import com.picme.navigation.Screen
import com.picme.core.common.Logger
import com.picme.domain.agent.model.SceneManager
import com.picme.domain.agent.AgentOrchestrator
import com.picme.domain.agent.CapabilityHost
import com.picme.domain.agent.GlobalCapabilityHost
import com.picme.domain.agent.LocalCapabilityHost
import com.picme.domain.agent.capability.NavigationCapability
import com.picme.testing.agent.bridge.TestEntryPoint
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.camera.core.ExperimentalGetImage
import androidx.compose.ui.platform.LocalConfiguration

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var currentLanguage: AppLanguage? = null
    private var testEntryPoint: TestEntryPoint? = null

    override fun attachBaseContext(newBase: Context) {
        val repository = UserPreferencesRepository(newBase)
        val language = repository.getAppLanguageBlocking()

        val locale = getLocaleFromLanguage(language)
        val context = updateLocale(newBase, locale)
        super.attachBaseContext(context)
    }

    @ExperimentalGetImage
    @Suppress("OPT_IN_USAGE_ERROR")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始化测试入口点（如果通过测试脚本启动）
        testEntryPoint = TestEntryPoint.fromIntent(this, savedInstanceState)

        setContent {
            val app = application as PicMeApplication
            val context = LocalContext.current
            val mediaViewModel: MediaViewModel = viewModel(
                factory = app.container.createMediaViewModelFactory()
            )
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModelFactory(
                    app.container.userPreferencesRepository,
                    app.container.llmModelDownloadManager,
                    context
                )
            )

            val themeMode by settingsViewModel.themeMode.collectAsState()
            val appLanguage by settingsViewModel.appLanguage.collectAsState()

            LaunchedEffect(appLanguage) {
                if (currentLanguage != null && currentLanguage != appLanguage) {
                    recreate()
                }
                currentLanguage = appLanguage
            }

            CompositionLocalProvider(
                LocalConfiguration provides Configuration(context.resources.configuration).apply {
                    setLocale(getLocaleFromLanguage(appLanguage))
                }
            ) {
                PicMeTheme(themeMode = themeMode) {
                    val navController = rememberNavController()

                    // 创建 Activity 级 CapabilityHost，注入 NavigationCapability
                    val navigationCapability = remember { NavigationCapability(navController) }
                    val rootCapabilityHost = remember { CapabilityHost().apply { register(navigationCapability) } }

                    // 设置全局引用，供非 Composable 代码访问
                    DisposableEffect(rootCapabilityHost) {
                        GlobalCapabilityHost.set(rootCapabilityHost)
                        onDispose { GlobalCapabilityHost.clear() }
                    }

                    LaunchedEffect(navController) {
                        Logger.i(TAG, "NavigationCapability initialized with NavController")
                        // 通知测试入口点应用已就绪
                        testEntryPoint?.onAppReady()
                    }

                    CompositionLocalProvider(LocalCapabilityHost provides rootCapabilityHost) {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            contentWindowInsets = WindowInsets(0, 0, 0, 0)
                        ) { innerPadding ->
                            NavHost(
                                navController = navController,
                                startDestination = Screen.Camera.route,
                                modifier = Modifier.padding(innerPadding),
                                enterTransition = {
                                    fadeIn(tween(400)) + slideIntoContainer(
                                        AnimatedContentTransitionScope.SlideDirection.Start,
                                        tween(400)
                                    )
                                },
                                exitTransition = {
                                    fadeOut(tween(400)) + slideOutOfContainer(
                                        AnimatedContentTransitionScope.SlideDirection.Start,
                                        tween(400)
                                    )
                                },
                                popEnterTransition = {
                                    fadeIn(tween(400)) + slideIntoContainer(
                                        AnimatedContentTransitionScope.SlideDirection.End,
                                        tween(400)
                                    )
                                },
                                popExitTransition = {
                                    fadeOut(tween(400)) + slideOutOfContainer(
                                        AnimatedContentTransitionScope.SlideDirection.End,
                                        tween(400)
                                    )
                                }
                            ) {
                            composable(Screen.Camera.route) {
                                // 场景管理：进入 Camera 页面
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.CAMERA)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.CAMERA)
                                    }
                                }
                                CameraScreen(
                                    onNavigateToGallery = { navController.navigate(Screen.Gallery.route) },
                                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                                    viewModel = mediaViewModel,
                                    settingsViewModel = settingsViewModel
                                )
                            }
                            composable(Screen.Gallery.route) {
                                // 场景管理：进入 Gallery 页面
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.GALLERY)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.GALLERY)
                                    }
                                }
                                GalleryScreen(
                                    viewModel = mediaViewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                                    onNavigateToDebug = { navController.navigate(Screen.Debug.route) }
                                )
                            }
                            composable(Screen.Settings.route) {
                                // 场景管理：进入 Settings 页面
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.SETTINGS)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.SETTINGS)
                                    }
                                }
                                SettingsScreen(
                                    viewModel = settingsViewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToModelCenter = { categoryTag ->
                                        navController.navigate(Screen.ModelCenter.createRoute(categoryTag))
                                    }
                                )
                            }
                            composable(
                                route = Screen.ModelCenter.route,
                                arguments = listOf(
                                    navArgument("categoryTag") {
                                        type = NavType.StringType
                                        defaultValue = ""
                                    }
                                )
                            ) { backStackEntry ->
                                // 场景管理：进入 Settings 子页面（复用 SETTINGS 场景）
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.SETTINGS)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.SETTINGS)
                                    }
                                }
                                val categoryTag = backStackEntry.arguments?.getString("categoryTag") ?: ""
                                ModelCenterScreen(
                                    viewModel = settingsViewModel,
                                    initialCategoryTag = categoryTag,
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
                            composable(Screen.Debug.route) {
                                // 场景管理：进入 Debug 页面
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.DEBUG)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.DEBUG)
                                    }
                                }
                                DebugScreen(
                                    onNavigateBack = { navController.popBackStack() },
                                    mediaViewModel = mediaViewModel
                                )
                            }
                            }
                        }
                    }

                    // 全局日志浮层：跨越页面生命周期
                    val showLogOverlay by settingsViewModel.showLogOverlay.collectAsState()
                    if (showLogOverlay) {
                        LogOverlay(onDismiss = { settingsViewModel.setShowLogOverlay(false) })
                    }

                    // 必要模型一键下载提示（由 CameraScreen 在进入相机 3 秒后触发）
                    EssentialModelsDownloadDialog(
                        showPrompt = settingsViewModel.showEssentialModelsPrompt.collectAsState().value,
                        isDownloading = settingsViewModel.isBatchDownloading.collectAsState().value,
                        onDownload = { settingsViewModel.startBatchDownload() },
                        onDismiss = { settingsViewModel.dismissDownloadPrompt() }
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        testEntryPoint?.saveState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        testEntryPoint?.release()
    }

    @Composable
    private fun EssentialModelsDownloadDialog(
        showPrompt: Boolean,
        isDownloading: Boolean,
        onDownload: () -> Unit,
        onDismiss: () -> Unit
    ) {
        if (!showPrompt) return
        AlertDialog(
            onDismissRequest = { if (!isDownloading) onDismiss() },
            title = {
                Text(text = stringResource(R.string.essential_models_download_title))
            },
            text = {
                Text(text = stringResource(R.string.essential_models_download_message))
            },
            confirmButton = {
                Button(
                    onClick = onDownload,
                    enabled = !isDownloading
                ) {
                    Text(
                        text = if (isDownloading) {
                            stringResource(R.string.essential_models_download_progress)
                        } else {
                            stringResource(R.string.essential_models_download_button)
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isDownloading
                ) {
                    Text(text = stringResource(R.string.essential_models_download_later))
                }
            }
        )
    }

    private fun getLocaleFromLanguage(language: AppLanguage): Locale {
        return when (language) {
            AppLanguage.ENGLISH -> Locale.ENGLISH
            AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.TRADITIONAL_CHINESE -> Locale.TRADITIONAL_CHINESE
            AppLanguage.SYSTEM -> Locale.getDefault()
        }
    }

    private fun updateLocale(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        // Update resources for old context
        context.resources.updateConfiguration(config, context.resources.displayMetrics)

        return context.createConfigurationContext(config)
    }
}
