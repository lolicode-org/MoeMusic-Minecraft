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
        maven("https://maven.lolicode.org/releases") {
            name = "Lolicode Releases"
            content { includeGroupByRegex("org\\.lolicode.*") }
        }
        maven("https://maven.lolicode.org/snapshots") {
            name = "Lolicode Snapshots"
            content { includeGroupByRegex("org\\.lolicode.*") }
        }
        maven("https://jitpack.io")
    }
}

rootProject.name = "moemusic-velocity"

includeBuild("../shared")
