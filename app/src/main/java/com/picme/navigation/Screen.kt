package com.picme.navigation

sealed class Screen(val route: String) {
    data object Camera : Screen("camera")
    data object Gallery : Screen("gallery")
    data object Settings : Screen("settings")
    data object Debug : Screen("debug")
    data object Ocr : Screen("ocr/{imageUri}") {
        fun createRoute(imageUri: String? = null): String {
            return if (imageUri != null) {
                "ocr/$imageUri"
            } else {
                "ocr"
            }
        }
    }
}
