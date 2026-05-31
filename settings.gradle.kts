pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") } // 阿里云 Gradle 插件镜像
        maven { url = uri("https://maven.aliyun.com/repository/public") }       // 阿里云公共镜像
        maven { url = uri("https://maven.aliyun.com/repository/google") }       // 阿里云 Google 镜像
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
