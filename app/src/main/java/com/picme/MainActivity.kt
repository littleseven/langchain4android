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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
import com.picme.navigation.Screen
import com.picme.core.common.Logger
import com.picme.domain.agent.model.SceneManager
import com.picme.domain.agent.AgentOrchestrator
import com.picme.domain.agent.capability.NavigationCapability
import com.picme.domain.agent.capability.CameraCapability
import com.picme.domain.agent.capability.GalleryCapability
import com.picme.domain.agent.capability.SettingsCapability
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var currentLanguage: AppLanguage? = null

    override fun attachBaseContext(newBase: Context) {
        val repository = UserPreferencesRepository(newBase)
        val language = repository.getAppLanguageBlocking()

        val locale = getLocaleFromLanguage(language)
        val context = updateLocale(newBase, locale)
        super.attachBaseContext(context)
    }

    @androidx.camera.core.ExperimentalGetImage
    @Suppress("OPT_IN_USAGE_ERROR")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val app = application as PicMeApplication
            val context = LocalContext.current
            val mediaViewModel: MediaViewModel = viewModel(
                factory = app.container.createMediaViewModelFactory()
            )
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModelFactory(
                    app.container.userPreferencesRepository,
                    app.container.llmModelDownloadManager
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
                androidx.compose.ui.platform.LocalConfiguration provides Configuration(context.resources.configuration).apply {
                    setLocale(getLocaleFromLanguage(appLanguage))
                }
            ) {
                PicMeTheme(themeMode = themeMode) {
                    val navController = rememberNavController()

                    // 绑定 NavigationCapability 的 NavController（应用级单例）
                    LaunchedEffect(navController) {
                        Logger.i(TAG, "Binding NavController to NavigationCapability")
                        NavigationCapability.getInstance().bindNavController(navController)
                        Logger.i(TAG, "NavController bound successfully")
                    }

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
                                    viewModel = mediaViewModel
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
                                    onNavigateToModelCenter = { navController.navigate(Screen.ModelCenter.route) }
                                )
                            }
                            composable(Screen.ModelCenter.route) {
                                // 场景管理：进入 Settings 子页面（复用 SETTINGS 场景）
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.SETTINGS)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.SETTINGS)
                                    }
                                }
                                ModelCenterScreen(
                                    viewModel = settingsViewModel,
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
            }
        }
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
