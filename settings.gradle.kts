pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "moemusic-spigot"

val sharedBuildDir = file("../shared")
val useCompositeShared =
    providers.gradleProperty("moemusic.useCompositeShared").orNull?.toBooleanStrictOrNull()
        ?: sharedBuildDir.resolve("settings.gradle.kts").isFile

if (useCompositeShared && sharedBuildDir.resolve("settings.gradle.kts").isFile) {
    includeBuild(sharedBuildDir)
}
