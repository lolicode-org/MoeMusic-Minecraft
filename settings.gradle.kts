pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        mavenCentral()
        maven {
            name = "Spigot Snapshots"
            url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
            content { includeGroup("org.spigotmc") }
        }
        maven {
            name = "Lolicode on Codeberg"
            url = uri("https://codeberg.org/api/packages/lolicode/maven")
            content { includeGroupByRegex("org\\.lolicode.*") }
        }
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.walkyst\\..*") }
        }
    }
}

rootProject.name = "moemusic-spigot"

val sharedBuildDir = file("../shared")
if (sharedBuildDir.resolve("settings.gradle.kts").isFile) {
    includeBuild(sharedBuildDir)
}
