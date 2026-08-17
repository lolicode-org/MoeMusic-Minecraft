import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Locale

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.shadow)
    alias(libs.plugins.mod.publish)
}

group = "org.lolicode.moemusic"
version = libs.versions.moemusic.velocity.get()

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "Lolicode Releases"
        url = uri("https://maven.lolicode.org/releases")
        content { includeGroupByRegex("org\\.lolicode.*") }
    }
    maven {
        name = "Lolicode Snapshots"
        url = uri("https://maven.lolicode.org/snapshots")
        content { includeGroupByRegex("org\\.lolicode.*") }
    }
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/lolicode-org/MoeMusic")
        credentials {
            username = providers.gradleProperty("gpr.user")
                .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                .orElse("")
                .get()
            password = providers.gradleProperty("gpr.key")
                .orElse(providers.environmentVariable("GITHUB_PACKAGES_TOKEN"))
                .orElse(providers.environmentVariable("PACKAGES_READ_TOKEN"))
                .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                .orElse("")
                .get()
        }
        content { includeGroupByRegex("org\\.lolicode.*") }
    }
    maven {
        url = uri("https://jitpack.io")
    }
}

val velocityApiVersion = providers.gradleProperty("velocityApiVersion")
    .orElse(libs.versions.velocity.api)
    .get()
val pluginVersion = version.toString()
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
    .orElse("velocity")
val publishTag = providers.gradleProperty("moemusic.publish.tag")
    .orElse(publishVersion.map { "velocity/v$it" })

fun requested(destination: String): Boolean =
    "all" in requestedDestinations || destination in requestedDestinations

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
    kapt("com.velocitypowered:velocity-api:$velocityApiVersion")

    implementation(libs.moemusic.api)
    implementation(libs.moemusic.core)
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    runtimeOnly(libs.kotlinx.serialization.json)
    implementation(libs.ktoml.core)
    implementation(libs.ktoml.file)
    compileOnly(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testCompileOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
    testRuntimeOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.jar {
    enabled = false
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

    // Velocity owns its API, Brigadier, and Adventure classes; they are compile-only here.
    // The standalone MoeMusic plugin loader deliberately parent-provides Kotlin, kotlinx,
    // SLF4J, and org.lolicode.moemusic.api to source plugins, so those packages stay at their
    // original names. All other bundled implementation libraries are isolated from Velocity
    // and from unrelated proxy plugins.
    relocate("com.akuleshov7.ktoml", "org.lolicode.moemusic.velocity.shadow.ktoml")
    relocate("com.squareup.wire", "org.lolicode.moemusic.velocity.shadow.wire")
    relocate("okio", "org.lolicode.moemusic.velocity.shadow.okio")
    relocate("org.apache", "org.lolicode.moemusic.velocity.shadow.apache")
    relocate("com.fasterxml.jackson", "org.lolicode.moemusic.velocity.shadow.jackson")
    relocate("org.json", "org.lolicode.moemusic.velocity.shadow.json")
    relocate("org.jsoup", "org.lolicode.moemusic.velocity.shadow.jsoup")
    relocate("org.intellij", "org.lolicode.moemusic.velocity.shadow.intellij")
    relocate("org.jetbrains.annotations", "org.lolicode.moemusic.velocity.shadow.annotations")
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
    displayName.set(publishVersion.map { "MoeMusic $it for Velocity" })
    changelog.set(publishChangelog)
    modLoaders.add("velocity")
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
            modLoaders.set(listOf("velocity"))
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
            modLoaders.set(listOf("velocity"))
            minecraftVersionList(publishMinecraftVersions)
            javaVersions.add(JavaVersion.toVersion(25))
            server.set(true)
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

