pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "moemusic-velocity"

val sharedBuildDir = file("../shared")
val useCompositeShared =
    providers.gradleProperty("moemusic.useCompositeShared").orNull?.toBooleanStrictOrNull()
        ?: sharedBuildDir.resolve("settings.gradle.kts").isFile

if (useCompositeShared && sharedBuildDir.resolve("settings.gradle.kts").isFile) {
    includeBuild(sharedBuildDir)
}
