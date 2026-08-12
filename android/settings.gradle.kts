pluginManagement {
    repositories {
        maven(url = "https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven(url = "https://mirrors.cloud.tencent.com/gradle/")
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
        maven(url = "https://maven.aliyun.com/repository/central")
        maven(url = "https://repo.huaweicloud.com/repository/maven/")
        maven(url = "https://maven-central-asia.storage-download.googleapis.com/maven2/")
        maven(url = "https://mirrors.cloud.tencent.com/gradle/")
        maven(url = "https://www.jitpack.io")
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        maven(url = "https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven(url = "https://mirrors.cloud.tencent.com/gradle/")
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
        maven(url = "https://maven.aliyun.com/repository/central")
        maven(url = "https://repo.huaweicloud.com/repository/maven/")
        maven(url = "https://maven-central-asia.storage-download.googleapis.com/maven2/")
        maven(url = "https://mirrors.cloud.tencent.com/gradle/")
        maven(url = "https://www.jitpack.io")
        mavenCentral()
    }
}

rootProject.name = "DouYinDownloader"
include(":app")
