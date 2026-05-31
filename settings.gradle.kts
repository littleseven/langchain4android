pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") } // 阿里云镜像（优先）
        maven { url = uri("https://maven.aliyun.com/repository/google") } // 阿里云 Google 镜像
        google()
        mavenCentral()
    }
}

rootProject.name = "PicMe"
include(":app")
include(":beauty-engine")
// GPUPixel 模块已移除，自研引擎已覆盖全部能力

