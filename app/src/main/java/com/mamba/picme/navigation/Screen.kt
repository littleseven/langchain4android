package com.mamba.picme.navigation

sealed class Screen(val route: String) {
    data object Camera : Screen("camera")
    data object Gallery : Screen("gallery")
    data object Settings : Screen("settings")
    data object Debug : Screen("debug")
    data object ModelCenter : Screen("model_center/{categoryTag}") {
        fun createRoute(categoryTag: String): String {
            return if (categoryTag.isNotBlank()) {
                "model_center/$categoryTag"
            } else {
                "model_center/"
            }
        }
    }
}
