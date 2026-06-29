package com.mamba.picme.navigation

sealed class Screen(val route: String) {
    data object Chat : Screen("chat")
    data object Camera : Screen("camera")
    data object Gallery : Screen("gallery")
    data object TagControl : Screen("tag_control")
    data object Settings : Screen("settings")
    data object Debug : Screen("debug")
    data object SearchTest : Screen("search_test")
    data object SentencePieceTest : Screen("sentencepiece_test")
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
