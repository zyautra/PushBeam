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
    }
}

rootProject.name = "pushbeam"
include(":shared")
// 서버 이미지 빌드처럼 Android SDK가 없는 곳에서는 -Ppushbeam.serverOnly=true로 앱 모듈을 뺀다.
if (providers.gradleProperty("pushbeam.serverOnly").orNull != "true") {
    include(":android")
}
include(":server")
include(":admin-cli")
