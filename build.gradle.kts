import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.provider.Provider
import java.util.Locale

plugins {
    base
    alias(libs.plugins.mod.publish)
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.fabric.loom) apply false
    alias(libs.plugins.forge.gradle) apply false
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun catalogVersion(alias: String): String =
    libsCatalog.findVersion(alias).orElseThrow {
        GradleException("Missing version catalog entry '$alias'.")
    }.requiredVersion

val moduleVersions = mapOf(
    "fabric" to catalogVersion("moemusic-fabric"),
    "forge" to catalogVersion("moemusic-forge"),
)

val fabricModVersion = catalogVersion("moemusic-fabric")
val forgeModVersion = catalogVersion("moemusic-forge")

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

val requestedLoaders = csvProperty("moemusic.publish.loaders", "fabric,forge")
val requestedDestinations = csvProperty("moemusic.publish.destinations", "github,modrinth,curseforge")

data class ModPublishArtifact(
    val loader: String,
    val displayLoader: String,
    val version: String,
    val file: Provider<RegularFile>,
    val buildTask: Any,
)

val selectedModArtifacts = listOfNotNull(
    if (requested("fabric", requestedLoaders)) {
        ModPublishArtifact(
            "fabric",
            "Fabric",
            fabricModVersion,
            layout.buildDirectory.file("libs/${rootProject.name}-fabric-$fabricModVersion.jar"),
            ":fabric:remapJar",
        )
    } else {
        null
    },
    if (requested("forge", requestedLoaders)) {
        ModPublishArtifact(
            "forge",
            "Forge",
            forgeModVersion,
            layout.buildDirectory.file("libs/${rootProject.name}-forge-$forgeModVersion.jar"),
            ":forge:reobfInstallJar",
        )
    } else {
        null
    },
)

if (selectedModArtifacts.isEmpty()) {
    throw GradleException("No mod artifacts selected. Set -Pmoemusic.publish.loaders=fabric,forge or all.")
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
val publishJavaVersion = providers.gradleProperty("moemusic.publish.javaVersion").orElse("17").get().toInt()
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
    dependsOn(":fabric:remapJar", ":forge:reobfInstallJar")
}

tasks.named(BasePlugin.ASSEMBLE_TASK_NAME) {
    dependsOn("buildModJars")
}

tasks.named("publishMods") {
    dependsOn(selectedModArtifacts.map { it.buildTask })
}

tasks.matching { it.name == "publishGithub" }.configureEach {
    dependsOn(selectedModArtifacts.map { it.buildTask })
}

selectedModArtifacts.forEach { artifact ->
    tasks.matching {
        it.name == "publishModrinth${artifact.displayLoader}" ||
            it.name == "publishCurseforge${artifact.displayLoader}"
    }.configureEach {
        dependsOn(artifact.buildTask)
    }
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
