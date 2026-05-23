pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        maven {
            name = "MinecraftForge"
            url = uri("https://maven.minecraftforge.net/")
        }
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version")
    }
}

rootProject.name = "moemusic-1.18.2"

val sharedBuildDir = file("../shared")
val useCompositeShared =
    providers.gradleProperty("moemusic.useCompositeShared").orNull?.toBooleanStrictOrNull()
        ?: sharedBuildDir.resolve("settings.gradle.kts").isFile

if (useCompositeShared && sharedBuildDir.resolve("settings.gradle.kts").isFile) {
    includeBuild(sharedBuildDir)
}

include(":fabric")
include(":forge")
