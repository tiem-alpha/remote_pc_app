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

rootProject.name = "RemotePc"
include(":app")
include(":freeRDPCore")
project(":freeRDPCore").projectDir = file("third_party/freerdp/client/Android/Studio/freeRDPCore")

project(":freeRDPCore").buildFileName = "../../../../../../freerdp-core.gradle"
