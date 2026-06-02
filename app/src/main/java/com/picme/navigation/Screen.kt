package com.picme.navigation

sealed class Screen(val route: String) {
    data object Camera : Screen("camera")
    data object Gallery : Screen("gallery")
    data object Settings : Screen("settings")
    data object Debug : Screen("debug")
    data object LlmModelManager : Screen("llm_model_manager")
    data object AsrModelManager : Screen("asr_model_manager")
    data object FaceDetectionModelManager : Screen("face_detection_model_manager")
}
