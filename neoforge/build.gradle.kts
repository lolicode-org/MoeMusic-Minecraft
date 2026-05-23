import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.JavaExec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.neoforge.moddev)
    idea
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val modArtifactBaseName = rootProject.extra["moemusic.artifactBaseName"] as String
val modId = "moemusic"
val modName = "MoeMusic"
val modLicense = "AGPL-3.0-or-later"
val minecraftVersion = libsCatalog.findVersion("minecraft").get().requiredVersion
val minecraftVersionRange = libsCatalog.findVersion("minecraft-version-range").get().requiredVersion
val neoVersion = libsCatalog.findVersion("neoforge").get().requiredVersion
val sharedApiArtifact = libsCatalog.findLibrary("moemusic-api-artifact").get()
val sharedCoreArtifact = libsCatalog.findLibrary("moemusic-core-artifact").get()
val sharedClientCoreArtifact = libsCatalog.findLibrary("moemusic-client-core-artifact").get()
val platformCommonVersion = libsCatalog.findVersion("moemusic-platform-common").get().requiredVersion
val generatedBuildInfoDir = layout.buildDirectory.dir("generated/sources/moemusicBuildInfo/main/kotlin")

val commonMainKotlinDir = rootProject.layout.projectDirectory.dir("platform-common/src/main/kotlin")
val commonClientKotlinDir = rootProject.layout.projectDirectory.dir("platform-common/src/client/kotlin")
val commonClientJavaDir = rootProject.layout.projectDirectory.dir("platform-common/src/client/java")

val sharedAssetResources by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

val generateMoeMusicNeoForgeBuildInfo by tasks.registering {
    val modVersion = project.version.toString()
    inputs.property("modVersion", modVersion)
    outputs.dir(generatedBuildInfoDir)

    doLast {
        val outputFile = generatedBuildInfoDir.get()
            .file("org/lolicode/moemusic/MoeMusicNeoForgeBuildInfo.kt")
            .asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package org.lolicode.moemusic

            public object MoeMusicNeoForgeBuildInfo {
                public const val LOADER_ID: String = "neoforge"
                public const val MOD_VERSION: String = "$modVersion"
            }
            """.trimIndent() + "\n",
        )
    }
}

val generateMoeMusicPlatformBuildInfo by tasks.registering {
    inputs.property("platformCommonVersion", platformCommonVersion)
    outputs.dir(generatedBuildInfoDir)

    doLast {
        val outputFile = generatedBuildInfoDir.get()
            .file("org/lolicode/moemusic/platform/MoeMusicPlatformBuildInfo.kt")
            .asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package org.lolicode.moemusic.platform

            internal object MoeMusicPlatformBuildInfo {
                internal const val PLATFORM_COMMON_VERSION: String = "$platformCommonVersion"
            }
            """.trimIndent() + "\n",
        )
    }
}

base {
    archivesName.set("$modArtifactBaseName-neoforge-dev")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    withSourcesJar()
}

tasks.named("sourcesJar") {
    dependsOn(generateMoeMusicNeoForgeBuildInfo, generateMoeMusicPlatformBuildInfo)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }

    sourceSets.named("main") {
        kotlin.srcDir(generatedBuildInfoDir)
        kotlin.srcDir(commonMainKotlinDir)
        kotlin.srcDir(commonClientKotlinDir)
    }
}

sourceSets.main {
    java.srcDir(commonClientJavaDir)
}

tasks.named("compileKotlin") {
    dependsOn(generateMoeMusicNeoForgeBuildInfo, generateMoeMusicPlatformBuildInfo)
}

neoForge {
    version = neoVersion

    runs {
        create("client") {
            client()
        }

        create("server") {
            server()
            programArgument("--nogui")
        }

        create("gameTestServer") {
            type = "gameTestServer"
        }

        create("clientData") {
            clientData()
            programArguments.addAll(
                "--mod", modId,
                "--all",
                "--output", file("src/generated/resources").absolutePath,
                "--existing", file("src/main/resources").absolutePath,
            )
        }

        configureEach {
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
            systemProperty("forge.logging.markers", "REGISTRIES")
            logLevel = org.slf4j.event.Level.DEBUG
        }
    }

    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    implementation(libs.kotlin.neoforge)
    implementation(libs.badpackets.neoforge)

    implementation(sharedClientCoreArtifact)
    implementation(sharedCoreArtifact)
    implementation(sharedApiArtifact)
    add(sharedAssetResources.name, sharedCoreArtifact)

    implementation(libs.kotlinx.coroutines.core)
    compileOnly(libs.cloth.config.neoforge)
    compileOnly(libs.luckperms.api)
    compileOnly(libs.sponge.mixin)
}

tasks.processResources {
    val replaceProperties = mapOf(
        "minecraft_version" to minecraftVersion,
        "minecraft_version_range" to minecraftVersionRange,
        "neo_version" to neoVersion,
        "mod_id" to modId,
        "mod_name" to modName,
        "mod_license" to modLicense,
        "mod_version" to version.toString(),
    )

    inputs.properties(replaceProperties)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(replaceProperties)
    }
    from({ sharedAssetResources.map { zipTree(it) } }) {
        include("assets/moemusic/**")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named<JavaExec>("runServer") {
    // Gradle's JavaExec defaults stdin to an empty stream, which leaves the server
    // running but unable to accept console commands from the run task.
    standardInput = System.`in`
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.jar {
    from(rootProject.file("LICENSE")) {
        rename { "${it}_moemusic" }
    }
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}
