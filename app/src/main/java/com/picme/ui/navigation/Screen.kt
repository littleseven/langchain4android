package com.picme.ui.navigation

sealed class Screen(val route: String) {
    object Camera : Screen("camera")
    object Gallery : Screen("gallery")
    object Settings : Screen("settings")
}
