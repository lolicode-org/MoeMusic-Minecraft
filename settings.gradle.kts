pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://codeberg.org/api/packages/lolicode/maven") {
            content { includeGroupByRegex("org\\.lolicode.*") }
        }
        maven("https://jitpack.io")
    }
}

rootProject.name = "moemusic-platform-velocity"

includeBuild("../shared")
