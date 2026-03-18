package com.example.picme

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.picme.data.repository.AppLanguage
import com.example.picme.ui.navigation.Screen
import com.example.picme.ui.screens.CameraScreen
import com.example.picme.ui.screens.GalleryScreen
import com.example.picme.ui.screens.SettingsScreen
import com.example.picme.ui.theme.PicMeTheme
import com.example.picme.ui.viewmodel.*
import java.util.*

class MainActivity : ComponentActivity() {
    
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            val app = application as PicMeApplication
            val mediaViewModel: MediaViewModel = viewModel(
                factory = MediaViewModelFactory(app.repository)
            )
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModelFactory(app.userPreferencesRepository)
            )

            val themeMode by settingsViewModel.themeMode.collectAsState()
            val appLanguage by settingsViewModel.appLanguage.collectAsState()

            // Handle language change
            LaunchedLanguageEffect(appLanguage)

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
                        enterTransition = {
                            fadeIn(animationSpec = tween(400)) + 
                            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(400))
                        },
                        exitTransition = {
                            fadeOut(animationSpec = tween(400)) + 
                            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(400))
                        },
                        popEnterTransition = {
                            fadeIn(animationSpec = tween(400)) + 
                            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(400))
                        },
                        popExitTransition = {
                            fadeOut(animationSpec = tween(400)) + 
                            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(400))
                        }
                    ) {
                        composable(Screen.Camera.route) {
                            CameraScreen(
                                onNavigateToGallery = {
                                    navController.navigate(Screen.Gallery.route)
                                },
                                onNavigateToSettings = {
                                    navController.navigate(Screen.Settings.route)
                                },
                                viewModel = mediaViewModel
                            )
                        }
                        composable(Screen.Gallery.route) {
                            GalleryScreen(
                                viewModel = mediaViewModel,
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                        composable(Screen.Settings.route) {
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun updateLocale(language: AppLanguage) {
        val locale = when (language) {
            AppLanguage.ENGLISH -> Locale.ENGLISH
            AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.SYSTEM -> Locale.getDefault()
        }
        
        Locale.setDefault(locale)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    @androidx.compose.runtime.Composable
    private fun LaunchedLanguageEffect(language: AppLanguage) {
        androidx.compose.runtime.LaunchedEffect(language) {
            updateLocale(language)
        }
    }
}
