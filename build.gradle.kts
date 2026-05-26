import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import java.util.Locale

plugins {
    base
    alias(libs.plugins.shadow)
    alias(libs.plugins.mod.publish)
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.fabric.loom) apply false
    alias(libs.plugins.neoforge.moddev) apply false
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val modArtifactBaseName = providers.gradleProperty("moemusic.artifactBaseName").orElse("moemusic").get()
extra["moemusic.artifactBaseName"] = modArtifactBaseName

fun catalogVersion(alias: String): String =
    libsCatalog.findVersion(alias).orElseThrow {
        GradleException("Missing version catalog entry '$alias'.")
    }.requiredVersion

val moduleVersions = mapOf(
    "fabric" to catalogVersion("moemusic-fabric"),
    "neoforge" to catalogVersion("moemusic-neoforge"),
)

val fabricModVersion = catalogVersion("moemusic-fabric")
val neoForgeModVersion = catalogVersion("moemusic-neoforge")
val shadePrefix = "org.lolicode.moemusic.shadow"

fun csvProperty(name: String, defaultValue: String): Set<String> =
    providers.gradleProperty(name).orElse(defaultValue).get()
        .split(",")
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }
        .toSet()

fun requested(name: String, requestedValues: Set<String>): Boolean =
    "all" in requestedValues || name.lowercase(Locale.ROOT) in requestedValues

fun csvValues(csv: String): List<String> =
    csv.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

fun displayMinecraftVersions(csv: String): String {
    val versions = csvValues(csv)
    return when (versions.size) {
        0 -> csv
        1 -> versions.single()
        else -> "${versions.first()}-${versions.last()}"
    }
}

fun baseVersion(version: String, suffixes: List<String>): String =
    suffixes
        .asSequence()
        .filter { it.isNotBlank() }
        .map { version.removeSuffix("-$it") }
        .firstOrNull { it != version }
        ?: version

fun nonBlankEnvironmentVariable(name: String) =
    providers.provider {
        System.getenv(name)?.takeIf { it.isNotBlank() }
    }

val requestedLoaders = csvProperty("moemusic.publish.loaders", "fabric,neoforge")
val requestedDestinations = csvProperty("moemusic.publish.destinations", "github,modrinth")
val publishesExternalKotlinForForge =
    libsCatalog.findLibrary("kotlin-forge").isPresent || libsCatalog.findLibrary("kotlin-neoforge").isPresent

fun requiredPublishDependencies(loader: String): List<String> =
    when (loader) {
        "fabric" -> listOf("fabric-api", "fabric-language-kotlin", "badpackets")
        "forge", "neoforge" -> if (publishesExternalKotlinForForge) {
            listOf("badpackets", "kotlin-for-forge")
        } else {
            listOf("badpackets")
        }
        else -> emptyList()
    }

fun optionalModrinthDependencies(loader: String): List<String> =
    when (loader) {
        "fabric" -> listOf("cloth-config", "fabric-permissions-api", "luckperms", "modmenu")
        "forge", "neoforge" -> listOf("cloth-config", "luckperms")
        else -> emptyList()
    }

fun optionalCurseforgeDependencies(loader: String): List<String> =
    when (loader) {
        "fabric" -> listOf("cloth-config", "luckperms", "modmenu")
        "forge", "neoforge" -> listOf("cloth-config", "luckperms")
        else -> emptyList()
    }

fun incompatiblePublishDependencies(loader: String): List<String> =
    when (loader) {
        "forge", "neoforge" -> if (publishesExternalKotlinForForge) {
            emptyList()
        } else {
            listOf("kotlin-for-forge")
        }
        else -> emptyList()
    }

val shadedLibraries by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

val neoForgeShadedLibraries by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    extendsFrom(shadedLibraries)
}

dependencies {
    // Pull the shared kernel from either the included ../shared build or published artifacts.
    add(shadedLibraries.name, libsCatalog.findLibrary("moemusic-api-artifact").get())
    add(shadedLibraries.name, libsCatalog.findLibrary("moemusic-core-artifact").get())
    add(shadedLibraries.name, libsCatalog.findLibrary("moemusic-client-core-artifact").get())
}

val buildFabricFullJar by tasks.registering(ShadowJar::class) {
    group = BasePlugin.BUILD_GROUP
    description = "Builds a shaded Fabric mod jar with bundled shared runtime libraries."

    val moduleJars = listOf(
        project(":fabric").tasks.named<Jar>("jar"),
    )

    dependsOn(moduleJars)
    archiveBaseName.set("$modArtifactBaseName-fabric")
    archiveVersion.set(fabricModVersion)
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    configurations = listOf(shadedLibraries)

    dependencies {
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib.*"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-reflect.*"))
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core.*"))
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-serialization-core.*"))
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-datetime.*"))
        exclude(dependency("org.mozilla:rhino-engine"))
        exclude(dependency("org.mozilla:rhino"))
        exclude(dependency("org.jsoup:jsoup"))
        exclude(dependency("org.json:json"))
        exclude(dependency("net.iharder:base64"))
        exclude(dependency("com.fasterxml.jackson.core:jackson-core"))
        exclude(dependency("com.fasterxml.jackson.core:jackson-databind"))
        exclude(dependency("com.fasterxml.jackson.core:jackson-annotations"))
        exclude(dependency("org.slf4j:slf4j-api"))
    }

    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**")
    exclude(
        "**/lavaplayer/source/bandcamp/**",
        "**/lavaplayer/source/beam/**",
        "**/lavaplayer/source/getyarn/**",
        "**/lavaplayer/source/nico/**",
        "**/lavaplayer/source/soundcloud/**",
        "**/lavaplayer/source/twitch/**",
        "**/lavaplayer/source/vimeo/**",
        "**/lavaplayer/source/yamusic/**",
        "**/lavaplayer/source/youtube/**",
        "natives/android-armhf/**",
        "natives/android-x86/**",
        "natives/android-x86-64/**",
        "natives/linux-aarch32/**",
        "natives/linux-arm/**",
        "natives/linux-armhf/**",
    )

    listOf(
        "com.squareup.wire" to "$shadePrefix.com.squareup.wire",
        "okio" to "$shadePrefix.okio",
        "dev.arbjerg" to "$shadePrefix.dev.arbjerg",
        "com.akuleshov7" to "$shadePrefix.com.akuleshov7",
        "org.apache.http" to "$shadePrefix.org.apache.http",
        "org.apache.commons.codec" to "$shadePrefix.org.apache.commons.codec",
        "org.apache.commons.io" to "$shadePrefix.org.apache.commons.io",
        "org.apache.commons.logging" to "$shadePrefix.org.apache.commons.logging",
    ).forEach { (sourcePackage, targetPackage) ->
        relocate(sourcePackage, targetPackage)
    }

    moduleJars.forEach { jarTask ->
        from(jarTask.map { zipTree(it.archiveFile) })
    }
}

val buildNeoForgeFullJar by tasks.registering(ShadowJar::class) {
    group = BasePlugin.BUILD_GROUP
    description = "Builds a shaded NeoForge mod jar with bundled shared runtime libraries."

    val moduleJars = listOf(
        project(":neoforge").tasks.named<Jar>("jar"),
    )

    dependsOn(moduleJars)
    archiveBaseName.set("$modArtifactBaseName-neoforge")
    archiveVersion.set(neoForgeModVersion)
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    configurations = listOf(neoForgeShadedLibraries)

    dependencies {
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib.*"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-reflect.*"))
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-.*"))
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-serialization-core.*"))
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-serialization-json.*"))
        exclude(dependency("org.mozilla:rhino-engine"))
        exclude(dependency("org.mozilla:rhino"))
        exclude(dependency("org.jsoup:jsoup"))
        exclude(dependency("org.json:json"))
        exclude(dependency("net.iharder:base64"))
        exclude(dependency("com.fasterxml.jackson.core:jackson-core"))
        exclude(dependency("com.fasterxml.jackson.core:jackson-databind"))
        exclude(dependency("com.fasterxml.jackson.core:jackson-annotations"))
        exclude(dependency("org.slf4j:slf4j-api"))
    }

    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**")
    exclude(
        "**/lavaplayer/source/bandcamp/**",
        "**/lavaplayer/source/beam/**",
        "**/lavaplayer/source/getyarn/**",
        "**/lavaplayer/source/nico/**",
        "**/lavaplayer/source/soundcloud/**",
        "**/lavaplayer/source/twitch/**",
        "**/lavaplayer/source/vimeo/**",
        "**/lavaplayer/source/yamusic/**",
        "**/lavaplayer/source/youtube/**",
        "natives/android-armhf/**",
        "natives/android-x86/**",
        "natives/android-x86-64/**",
        "natives/linux-aarch32/**",
        "natives/linux-arm/**",
        "natives/linux-armhf/**",
    )

    listOf(
        "kotlinx.datetime" to "$shadePrefix.kotlinx.datetime",
        "com.squareup.wire" to "$shadePrefix.com.squareup.wire",
        "okio" to "$shadePrefix.okio",
        "dev.arbjerg" to "$shadePrefix.dev.arbjerg",
        "com.akuleshov7" to "$shadePrefix.com.akuleshov7",
        "org.apache.http" to "$shadePrefix.org.apache.http",
        "org.apache.commons.codec" to "$shadePrefix.org.apache.commons.codec",
        "org.apache.commons.io" to "$shadePrefix.org.apache.commons.io",
        "org.apache.commons.logging" to "$shadePrefix.org.apache.commons.logging",
    ).forEach { (sourcePackage, targetPackage) ->
        relocate(sourcePackage, targetPackage)
    }

    moduleJars.forEach { jarTask ->
        from(jarTask.map { zipTree(it.archiveFile) })
    }
}

data class ModPublishArtifact(
    val loader: String,
    val displayLoader: String,
    val version: String,
    val jarTask: TaskProvider<ShadowJar>,
)

val selectedModArtifacts = listOfNotNull(
    if (requested("fabric", requestedLoaders)) {
        ModPublishArtifact("fabric", "Fabric", fabricModVersion, buildFabricFullJar)
    } else {
        null
    },
    if (requested("neoforge", requestedLoaders)) {
        ModPublishArtifact("neoforge", "NeoForge", neoForgeModVersion, buildNeoForgeFullJar)
    } else {
        null
    },
)

if (selectedModArtifacts.isEmpty()) {
    throw GradleException("No mod artifacts selected. Set -Pmoemusic.publish.loaders=fabric,neoforge or all.")
}

val selectedModVersions = selectedModArtifacts.map { it.version }.distinct()
val defaultPublishVersion = if (selectedModVersions.size == 1) {
    selectedModVersions.single()
} else {
    selectedModVersions.joinToString("+")
}
val publishVersion = providers.gradleProperty("moemusic.publish.version").orElse(defaultPublishVersion)

gradle.taskGraph.whenReady {
    val publishingMods = allTasks.any { it.name == "publishMods" }
    if (
        publishingMods &&
        selectedModVersions.size > 1 &&
        !providers.gradleProperty("moemusic.publish.version").isPresent
    ) {
        throw GradleException(
            "Selected loader versions differ (${selectedModVersions.joinToString()}); " +
                "publish one loader at a time or set -Pmoemusic.publish.version explicitly.",
        )
    }
}
val publishBranch = providers.gradleProperty("moemusic.publish.branch")
    .orElse(providers.environmentVariable("GITHUB_REF_NAME"))
    .orElse("local")
    .get()
val publishBranchVersion = publishBranch
    .removePrefix("refs/heads/")
    .removePrefix("origin/")
    .removePrefix("version/")
val publishTag = providers.gradleProperty("moemusic.publish.tag")
    .orElse("mod/$publishBranchVersion/v${publishVersion.get()}")
val publishCommitish = providers.gradleProperty("moemusic.publish.commitish")
    .orElse(providers.environmentVariable("GITHUB_SHA"))
    .orElse(publishBranch)
val publishMinecraftVersions = providers.gradleProperty("moemusic.publish.minecraftVersions")
    .orElse(catalogVersion("minecraft-publish-versions"))
val publishMinecraftVersionDisplay = providers.gradleProperty("moemusic.publish.minecraftVersionDisplay")
    .orElse(providers.provider { displayMinecraftVersions(publishMinecraftVersions.get()) })
val publishDisplayVersion = providers.gradleProperty("moemusic.publish.displayVersion")
    .orElse(
        providers.provider {
            baseVersion(
                publishVersion.get(),
                listOf(publishBranchVersion, catalogVersion("minecraft")) + csvValues(publishMinecraftVersions.get()),
            )
        },
    )
val publishDisplayNameOverride = providers.gradleProperty("moemusic.publish.displayName")
val githubDisplayName = providers.gradleProperty("moemusic.publish.github.displayName")
    .orElse(publishDisplayNameOverride)
    .orElse(providers.provider { "MoeMusic ${publishDisplayVersion.get()} for Minecraft ${publishMinecraftVersionDisplay.get()}" })
val publishJavaVersion = providers.gradleProperty("moemusic.publish.javaVersion").orElse("25").get().toInt()
val publishChangelog = providers.provider {
    providers.gradleProperty("moemusic.publish.changelogFile").orNull
        ?.let { path -> file(path).takeIf { it.isFile }?.readText() }
        ?: rootProject.file("CHANGELOG.md").takeIf { it.isFile }?.readText()
        ?: providers.gradleProperty("moemusic.publish.changelog").orNull
        ?: "See repository history for changes."
}
val primaryModArtifact = selectedModArtifacts.first()

fun siteDisplayName(artifact: ModPublishArtifact): Provider<String> =
    providers.gradleProperty("moemusic.publish.${artifact.loader}.displayName")
        .orElse(providers.gradleProperty("moemusic.publish.site.displayName"))
        .orElse(publishDisplayNameOverride)
        .orElse(
            providers.provider {
                "MoeMusic ${publishDisplayVersion.get()} for Minecraft ${publishMinecraftVersionDisplay.get()} " +
                    "(${artifact.displayLoader})"
            },
        )

publishMods {
    file.set(primaryModArtifact.jarTask.flatMap { it.archiveFile })
    type.set(
        when (providers.gradleProperty("moemusic.publish.type").orElse("stable").get().lowercase(Locale.ROOT)) {
            "stable", "release" -> STABLE
            "beta" -> BETA
            "alpha" -> ALPHA
            else -> throw GradleException("Unsupported -Pmoemusic.publish.type. Use stable, beta, or alpha.")
        },
    )
    version.set(publishVersion)
    displayName.set(githubDisplayName)
    changelog.set(publishChangelog)
    modLoaders.addAll(selectedModArtifacts.map { it.loader })
    dryRun.set(providers.gradleProperty("moemusic.publish.dryRun").map { it.toBoolean() }.orElse(false))

    if (requested("github", requestedDestinations)) {
        github {
            repository.set(
                providers.gradleProperty("moemusic.publish.github.repository")
                    .orElse(nonBlankEnvironmentVariable("GITHUB_REPOSITORY"))
                    .orElse("lolicode-org/MoeMusic-Minecraft"),
            )
            accessToken.set(nonBlankEnvironmentVariable("GITHUB_TOKEN"))
            commitish.set(publishCommitish)
            tagName.set(publishTag)
            selectedModArtifacts.drop(1).forEach { artifact ->
                additionalFiles.from(artifact.jarTask.flatMap { it.archiveFile })
            }
        }
    }

    if (requested("modrinth", requestedDestinations)) {
        selectedModArtifacts.forEach { artifact ->
            modrinth("modrinth${artifact.displayLoader}") {
                file.set(artifact.jarTask.flatMap { it.archiveFile })
                displayName.set(siteDisplayName(artifact))
                accessToken.set(nonBlankEnvironmentVariable("MODRINTH_TOKEN"))
                projectId.set(
                    providers.gradleProperty("moemusic.publish.modrinth.projectId")
                        .orElse(nonBlankEnvironmentVariable("MODRINTH_PROJECT_ID")),
                )
                modLoaders.set(listOf(artifact.loader))
                minecraftVersionList(publishMinecraftVersions.get())
                requiredPublishDependencies(artifact.loader).takeIf { it.isNotEmpty() }?.let {
                    requires(*it.toTypedArray())
                }
                optionalModrinthDependencies(artifact.loader).takeIf { it.isNotEmpty() }?.let {
                    optional(*it.toTypedArray())
                }
                incompatiblePublishDependencies(artifact.loader).takeIf { it.isNotEmpty() }?.let {
                    incompatible(*it.toTypedArray())
                }
            }
        }
    }

    if (requested("curseforge", requestedDestinations)) {
        selectedModArtifacts.forEach { artifact ->
            curseforge("curseforge${artifact.displayLoader}") {
                file.set(artifact.jarTask.flatMap { it.archiveFile })
                displayName.set(siteDisplayName(artifact))
                accessToken.set(nonBlankEnvironmentVariable("CURSEFORGE_TOKEN"))
                projectId.set(
                    providers.gradleProperty("moemusic.publish.curseforge.projectId")
                        .orElse(nonBlankEnvironmentVariable("CURSEFORGE_PROJECT_ID")),
                )
                modLoaders.set(listOf(artifact.loader))
                minecraftVersionList(publishMinecraftVersions.get())
                javaVersions.add(JavaVersion.toVersion(publishJavaVersion))
                requiredPublishDependencies(artifact.loader).takeIf { it.isNotEmpty() }?.let {
                    requires(*it.toTypedArray())
                }
                optionalCurseforgeDependencies(artifact.loader).takeIf { it.isNotEmpty() }?.let {
                    optional(*it.toTypedArray())
                }
                incompatiblePublishDependencies(artifact.loader).takeIf { it.isNotEmpty() }?.let {
                    incompatible(*it.toTypedArray())
                }
            }
        }
    }
}

tasks.register("buildModJars") {
    group = BasePlugin.BUILD_GROUP
    description = "Builds installable mod jars for all supported loaders."
    dependsOn(buildFabricFullJar, buildNeoForgeFullJar)
}

tasks.named(BasePlugin.ASSEMBLE_TASK_NAME) {
    dependsOn("buildModJars")
}

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
        maven { url = uri("https://maven.fabricmc.net/") }
        maven {
            url = uri("https://maven2.bai.lol")
            content { includeGroup("lol.bai") }
        }
        maven {
            url = uri("https://maven.terraformersmc.com/")
            content { includeGroup("com.terraformersmc") }
        }
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.walkyst\\..*") }
        }
        maven {
            url = uri("https://maven.shedaniel.me/")
            content { includeGroup("me.shedaniel.cloth") }
        }
        maven {
            name = "Lolicode on Codeberg"
            url = uri("https://codeberg.org/api/packages/lolicode/maven")
            content {
                includeGroupByRegex("org\\.lolicode.*")
            }
        }
        maven {
            name = "Kotlin for Forge"
            setUrl("https://thedarkcolour.github.io/KotlinForForge/")
            content { includeGroup("thedarkcolour") }
        }
    }
}

subprojects {
    group = "org.lolicode.moemusic"
    version = moduleVersions[name]
        ?: throw GradleException("No MoeMusic version configured for platform module '$name'.")
}
