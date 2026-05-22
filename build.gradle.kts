import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.provider.Provider
import org.gradle.jvm.tasks.Jar
import java.util.Locale

plugins {
    base
    alias(libs.plugins.shadow)
    alias(libs.plugins.mod.publish)
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.fabric.loom) apply false
    alias(libs.plugins.neoforge.moddev) apply false
    alias(libs.plugins.forge.gradle) apply false
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun catalogVersion(alias: String): String =
    libsCatalog.findVersion(alias).orElseThrow {
        GradleException("Missing version catalog entry '$alias'.")
    }.requiredVersion

val moduleVersions = mapOf(
    "fabric" to catalogVersion("moemusic-fabric"),
    "neoforge" to catalogVersion("moemusic-neoforge"),
    "forge" to catalogVersion("moemusic-forge"),
)

val fabricModVersion = catalogVersion("moemusic-fabric")
val neoForgeModVersion = catalogVersion("moemusic-neoforge")
val forgeModVersion = catalogVersion("moemusic-forge")
val shadePrefix = "org.lolicode.moemusic.shadow"
val forgeMixinConfigs = "moemusic.mixins.json,moemusic.client.mixins.json"

fun csvProperty(name: String, defaultValue: String): Set<String> =
    providers.gradleProperty(name).orElse(defaultValue).get()
        .split(",")
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }
        .toSet()

fun requested(name: String, requestedValues: Set<String>): Boolean =
    "all" in requestedValues || name.lowercase(Locale.ROOT) in requestedValues

fun nonBlankEnvironmentVariable(name: String) =
    providers.provider {
        System.getenv(name)?.takeIf { it.isNotBlank() }
    }

val requestedLoaders = csvProperty("moemusic.publish.loaders", "fabric,forge,neoforge")
val requestedDestinations = csvProperty("moemusic.publish.destinations", "github,modrinth,curseforge")

val shadedLibraries by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

val neoForgeShadedLibraries by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    extendsFrom(shadedLibraries)
}

val forgeShadedLibraries by configurations.creating {
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

val buildNeoForgeFullJar by tasks.registering(ShadowJar::class) {
    group = BasePlugin.BUILD_GROUP
    description = "Builds a shaded NeoForge mod jar with bundled shared runtime libraries."

    val moduleJars = listOf(
        project(":neoforge").tasks.named<Jar>("jar"),
    )

    dependsOn(moduleJars)
    archiveBaseName.set("${rootProject.name}-neoforge")
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

val buildForgeFullJar by tasks.registering(ShadowJar::class) {
    group = BasePlugin.BUILD_GROUP
    description = "Builds a shaded Forge mod jar with bundled shared runtime libraries."

    val moduleJars = listOf(
        project(":forge").tasks.named<Jar>("jar"),
    )

    dependsOn(moduleJars)
    archiveBaseName.set("${rootProject.name}-forge")
    archiveVersion.set(forgeModVersion)
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    configurations = listOf(forgeShadedLibraries)
    manifest {
        attributes["MixinConfigs"] = forgeMixinConfigs
    }

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
    val file: Provider<RegularFile>,
)

val selectedModArtifacts = listOfNotNull(
    if (requested("fabric", requestedLoaders)) {
        ModPublishArtifact(
            "fabric",
            "Fabric",
            fabricModVersion,
            layout.buildDirectory.file("libs/${rootProject.name}-fabric-$fabricModVersion.jar"),
        )
    } else {
        null
    },
    if (requested("forge", requestedLoaders)) {
        ModPublishArtifact("forge", "Forge", forgeModVersion, buildForgeFullJar.flatMap { it.archiveFile })
    } else {
        null
    },
    if (requested("neoforge", requestedLoaders)) {
        ModPublishArtifact("neoforge", "NeoForge", neoForgeModVersion, buildNeoForgeFullJar.flatMap { it.archiveFile })
    } else {
        null
    },
)

if (selectedModArtifacts.isEmpty()) {
    throw GradleException("No mod artifacts selected. Set -Pmoemusic.publish.loaders=fabric,forge,neoforge or all.")
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
    .orElse(catalogVersion("minecraft"))
val publishJavaVersion = providers.gradleProperty("moemusic.publish.javaVersion").orElse("21").get().toInt()
val publishChangelog = providers.provider {
    providers.gradleProperty("moemusic.publish.changelogFile").orNull
        ?.let { path -> file(path).takeIf { it.isFile }?.readText() }
        ?: rootProject.file("CHANGELOG.md").takeIf { it.isFile }?.readText()
        ?: providers.gradleProperty("moemusic.publish.changelog").orNull
        ?: "See repository history for changes."
}
val primaryModArtifact = selectedModArtifacts.first()

publishMods {
    file.set(primaryModArtifact.file)
    type.set(
        when (providers.gradleProperty("moemusic.publish.type").orElse("stable").get().lowercase(Locale.ROOT)) {
            "stable", "release" -> STABLE
            "beta" -> BETA
            "alpha" -> ALPHA
            else -> throw GradleException("Unsupported -Pmoemusic.publish.type. Use stable, beta, or alpha.")
        },
    )
    version.set(publishVersion)
    displayName.set(
        providers.gradleProperty("moemusic.publish.displayName").orElse(
            "MoeMusic ${publishVersion.get()} " +
                "(${publishMinecraftVersions.get()}, ${selectedModArtifacts.joinToString(" + ") { it.displayLoader }})",
        ),
    )
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
                additionalFiles.from(artifact.file)
            }
        }
    }

    if (requested("modrinth", requestedDestinations)) {
        selectedModArtifacts.forEach { artifact ->
            modrinth("modrinth${artifact.displayLoader}") {
                file.set(artifact.file)
                accessToken.set(nonBlankEnvironmentVariable("MODRINTH_TOKEN"))
                projectId.set(
                    providers.gradleProperty("moemusic.publish.modrinth.projectId")
                        .orElse(nonBlankEnvironmentVariable("MODRINTH_PROJECT_ID")),
                )
                modLoaders.set(listOf(artifact.loader))
                minecraftVersionList(publishMinecraftVersions.get())
            }
        }
    }

    if (requested("curseforge", requestedDestinations)) {
        selectedModArtifacts.forEach { artifact ->
            curseforge("curseforge${artifact.displayLoader}") {
                file.set(artifact.file)
                accessToken.set(nonBlankEnvironmentVariable("CURSEFORGE_TOKEN"))
                projectId.set(
                    providers.gradleProperty("moemusic.publish.curseforge.projectId")
                        .orElse(nonBlankEnvironmentVariable("CURSEFORGE_PROJECT_ID")),
                )
                modLoaders.set(listOf(artifact.loader))
                minecraftVersionList(publishMinecraftVersions.get())
                javaVersions.add(JavaVersion.toVersion(publishJavaVersion))
            }
        }
    }
}

tasks.register("buildModJars") {
    group = BasePlugin.BUILD_GROUP
    description = "Builds installable mod jars for all supported loaders."
    dependsOn(":fabric:remapJar", buildNeoForgeFullJar, buildForgeFullJar)
}

tasks.named(BasePlugin.ASSEMBLE_TASK_NAME) {
    dependsOn("buildModJars")
}

tasks.named("publishMods") {
    dependsOn("buildModJars")
}

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
        maven { url = uri("https://libraries.minecraft.net/") }
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.minecraftforge.net/") }
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
