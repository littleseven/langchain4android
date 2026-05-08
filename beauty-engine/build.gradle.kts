plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.picme.beauty"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // GPUPixel 已移除，全部能力由自研引擎提供

    // 人脸检测依赖（从 app 模块迁移）
    implementation(libs.mediapipe.face.landmarker)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
}

