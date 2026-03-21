package com.picme

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.picme.data.repository.AppLanguage
import com.picme.ui.navigation.Screen
import com.picme.ui.screens.CameraScreen
import com.picme.ui.screens.GalleryScreen
import com.picme.ui.screens.SettingsScreen
import com.picme.ui.screens.DebugScreen
import com.picme.ui.theme.PicMeTheme
import com.picme.ui.viewmodel.*
import java.util.*

class MainActivity : ComponentActivity() {
    
    // Maintain a reference to the current language to detect changes
    private var currentLanguage: AppLanguage? = null

    override fun attachBaseContext(newBase: Context) {
        // Read language from preferences synchronously for initial launch
        val sharedPrefs = newBase.getSharedPreferences("user_preferences", Context.MODE_PRIVATE)
        val languageName = sharedPrefs.getString("app_language", AppLanguage.SYSTEM.name) ?: AppLanguage.SYSTEM.name
        val language = try { AppLanguage.valueOf(languageName) } catch (e: Exception) { AppLanguage.SYSTEM }
        
        val locale = getLocaleFromLanguage(language)
        val context = updateLocale(newBase, locale)
        super.attachBaseContext(context)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            val app = application as PicMeApplication
            val mediaViewModel: MediaViewModel = viewModel(factory = MediaViewModelFactory(app.repository))
            val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModelFactory(app.userPreferencesRepository))

            val themeMode by settingsViewModel.themeMode.collectAsState()
            val appLanguage by settingsViewModel.appLanguage.collectAsState()

            // Crucial: Recreate activity when language changes in settings
            LaunchedEffect(appLanguage) {
                if (currentLanguage != null && currentLanguage != appLanguage) {
                    recreate()
                }
                currentLanguage = appLanguage
            }

            PicMeTheme(themeMode = themeMode) {
                val navController = rememberNavController()
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Camera.route,
                        modifier = Modifier.padding(innerPadding),
                        enterTransition = { fadeIn(tween(400)) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(400)) },
                        exitTransition = { fadeOut(tween(400)) + slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(400)) },
                        popEnterTransition = { fadeIn(tween(400)) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(400)) },
                        popExitTransition = { fadeOut(tween(400)) + slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(400)) }
                    ) {
                        composable(Screen.Camera.route) {
                            CameraScreen(
                                onNavigateToGallery = { navController.navigate(Screen.Gallery.route) },
                                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                                onNavigateToDebug = { navController.navigate(Screen.Debug.route) },
                                viewModel = mediaViewModel
                            )
                        }
                        composable(Screen.Gallery.route) {
                            GalleryScreen(viewModel = mediaViewModel, onNavigateBack = { navController.popBackStack() })
                        }
                        composable(Screen.Settings.route) {
                            SettingsScreen(viewModel = settingsViewModel, onNavigateBack = { navController.popBackStack() })
                        }
                        composable(Screen.Debug.route) {
                            DebugScreen(onNavigateBack = { navController.popBackStack() })
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
        return context.createConfigurationContext(config)
    }
}
