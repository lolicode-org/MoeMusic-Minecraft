import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Locale

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    alias(libs.plugins.mod.publish)
}

group = "org.lolicode.moemusic"
version = libs.versions.moemusic.spigot.get()
val spigotApiVersion = providers.gradleProperty("spigotApiVersion").orElse(libs.versions.spigot.api)
val pluginVersion = version.toString()
val kotlinRuntimeVersion = libs.versions.kotlin.get()
val coroutinesVersion = libs.versions.kotlinx.coroutines.get()
val serializationVersion = libs.versions.kotlinx.serialization.get()
val slf4jVersion = libs.versions.slf4j.get()
val requestedDestinations = providers.gradleProperty("moemusic.publish.destinations")
    .orElse("github,modrinth")
    .get()
    .split(",")
    .map { it.trim().lowercase(Locale.ROOT) }
    .filter { it.isNotEmpty() }
    .toSet()
val publishMinecraftVersions = providers.gradleProperty("moemusic.publish.minecraftVersions")
    .orElse("1.18.2,1.19,1.19.1,1.19.2,1.19.3,1.19.4,1.20,1.20.1,1.20.2,1.20.3,1.20.4,1.20.5,1.20.6,1.21,1.21.1,1.21.2,1.21.3,1.21.4,1.21.5,1.21.6,1.21.7,1.21.8,1.21.9,1.21.10,1.21.11,26.1,26.1.1,26.1.2,26.2")
    .get()
val publishVersion = providers.gradleProperty("moemusic.publish.version").orElse(version.toString())
val publishChangelog = providers.gradleProperty("moemusic.publish.changelogFile")
    .map { file(it).readText() }
    .orElse("See repository history for changes.")
val publishCommitish = providers.gradleProperty("moemusic.publish.commitish")
    .orElse(providers.environmentVariable("GITHUB_SHA"))
    .orElse("spigot")
val publishTag = providers.gradleProperty("moemusic.publish.tag")
    .orElse(publishVersion.map { "spigot/v$it" })

fun requested(destination: String): Boolean =
    "all" in requestedDestinations || destination in requestedDestinations

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.spigot.api) {
        version { require(spigotApiVersion.get()) }
    }
    implementation(libs.moemusic.api)
    implementation(libs.moemusic.core)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.slf4j.jdk14)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.spigot.api) {
        version { require(spigotApiVersion.get()) }
    }
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand(
            "version" to pluginVersion,
            "kotlinRuntimeVersion" to kotlinRuntimeVersion,
            "coroutinesVersion" to coroutinesVersion,
            "serializationVersion" to serializationVersion,
            "slf4jVersion" to slf4jVersion,
        )
    }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude(
        "natives/android-aarch64/**",
        "natives/android-armhf/**",
        "natives/android-x86/**",
        "natives/android-x86-64/**",
        "natives/linux-aarch32/**",
        "natives/linux-arm/**",
        "natives/linux-armhf/**",
        "natives/linux-musl-aarch64/**",
        "natives/linux-musl-x86-64/**",
        "natives/linux-x86/**",
        "natives/win-aarch64/**",
        "natives/win-x86/**",
    )
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib:.*"))
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-.*:.*"))
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-serialization-.*:.*"))
        exclude(dependency("org.slf4j:.*:.*"))
    }
    relocate("kotlinx.datetime", "org.lolicode.moemusic.spigot.shadow.kotlinx.datetime")
    relocate("com.squareup", "org.lolicode.moemusic.spigot.shadow.com.squareup")
    relocate("okio", "org.lolicode.moemusic.spigot.shadow.okio")
    relocate("com.akuleshov7", "org.lolicode.moemusic.spigot.shadow.com.akuleshov7")
    relocate("org.apache.http", "org.lolicode.moemusic.spigot.shadow.org.apache.http")
    relocate("org.apache.commons", "org.lolicode.moemusic.spigot.shadow.org.apache.commons")
    relocate("org.jsoup", "org.lolicode.moemusic.spigot.shadow.org.jsoup")
    relocate("org.json", "org.lolicode.moemusic.spigot.shadow.org.json")
    relocate("com.fasterxml.jackson", "org.lolicode.moemusic.spigot.shadow.com.fasterxml.jackson")
}

tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

publishMods {
    file.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
    type.set(
        when (providers.gradleProperty("moemusic.publish.type").orElse("stable").get().lowercase(Locale.ROOT)) {
            "stable", "release" -> STABLE
            "beta" -> BETA
            "alpha" -> ALPHA
            else -> throw GradleException("Unsupported -Pmoemusic.publish.type. Use stable, beta, or alpha.")
        },
    )
    version.set(publishVersion)
    displayName.set(publishVersion.map { "MoeMusic $it for Spigot" })
    changelog.set(publishChangelog)
    modLoaders.add("spigot")
    modLoaders.add("paper")
    modLoaders.add("purpur")
    dryRun.set(providers.gradleProperty("moemusic.publish.dryRun").map { it.toBoolean() }.orElse(false))

    if (requested("github")) {
        github {
            repository.set(
                providers.gradleProperty("moemusic.publish.github.repository")
                    .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
                    .orElse("lolicode-org/MoeMusic-Minecraft"),
            )
            accessToken.set(providers.environmentVariable("GITHUB_TOKEN"))
            commitish.set(publishCommitish)
            tagName.set(publishTag)
        }
    }

    if (requested("modrinth")) {
        modrinth {
            file.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
            accessToken.set(providers.environmentVariable("MODRINTH_TOKEN"))
            projectId.set(
                providers.gradleProperty("moemusic.publish.modrinth.projectId")
                    .orElse(providers.environmentVariable("MODRINTH_PROJECT_ID")),
            )
            modLoaders.set(listOf("spigot", "paper", "purpur"))
            minecraftVersionList(publishMinecraftVersions)
        }
    }

    if (requested("curseforge")) {
        curseforge {
            file.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
            accessToken.set(providers.environmentVariable("CURSEFORGE_TOKEN"))
            projectId.set(
                providers.gradleProperty("moemusic.publish.curseforge.projectId")
                    .orElse(providers.environmentVariable("CURSEFORGE_PROJECT_ID")),
            )
            modLoaders.set(listOf("spigot", "paper", "purpur"))
            minecraftVersionList(publishMinecraftVersions)
            javaVersions.add(JavaVersion.VERSION_17)
            server.set(true)
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
