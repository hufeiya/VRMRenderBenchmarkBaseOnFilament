pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // corelib HEAD(含 MToon)尚未发到 Central,以 0.1.3-SNAPSHOT 发布在本地仓库:
        // 在 AIAvatar-SDK 目录执行
        //   ./gradlew :corelib:publishToMavenLocal -PSDK_VERSION=0.1.3-SNAPSHOT
        mavenLocal()
    }
}

rootProject.name = "FilamentBasedBenchmark"
include(":app")
 