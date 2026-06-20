pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") } // 阿里云 Gradle 插件镜像
        maven { url = uri("https://maven.aliyun.com/repository/public") }       // 阿里云公共镜像
        maven { url = uri("https://maven.aliyun.com/repository/google") }       // 阿里云 Google 镜像
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") } // 阿里云镜像（优先）
        maven { url = uri("https://maven.aliyun.com/repository/google") } // 阿里云 Google 镜像
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "PicMe"
include(":app")
include(":beauty-api")
include(":beauty-engine")
include(":agent-core")
include(":mamba-agent")
