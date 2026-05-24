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
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/public") } // 阿里云镜像
    }
}

rootProject.name = "PicMe"
include(":app")
include(":beauty-engine")
// GPUPixel 模块已移除，自研引擎已覆盖全部能力

