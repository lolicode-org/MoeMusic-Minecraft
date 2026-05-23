import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.forge.gradle)
    idea
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val modArtifactBaseName = rootProject.extra["moemusic.artifactBaseName"] as String
val modId = "moemusic"
val modName = "MoeMusic"
val modLicense = "AGPL-3.0-or-later"
val minecraftVersion = libsCatalog.findVersion("minecraft").get().requiredVersion
val minecraftVersionRange = libsCatalog.findVersion("minecraft-version-range").get().requiredVersion
val forgeVersion = libsCatalog.findVersion("forge").get().requiredVersion
val sharedApiArtifact = libsCatalog.findLibrary("moemusic-api-artifact").get()
val sharedCoreArtifact = libsCatalog.findLibrary("moemusic-core-artifact").get()
val sharedClientCoreArtifact = libsCatalog.findLibrary("moemusic-client-core-artifact").get()
val platformCommonVersion = libsCatalog.findVersion("moemusic-platform-common").get().requiredVersion
val forgeMixinConfigs = "moemusic.mixins.json,moemusic.client.mixins.json"
val forgeApiJar = rootProject.layout.projectDirectory.file(
    ".gradle/mavenizer/repo/net/minecraftforge/forge/$forgeVersion/forge-$forgeVersion.jar"
)

val generatedBuildInfoDir = layout.buildDirectory.dir("generated/sources/moemusicBuildInfo/main/kotlin")
val forgeDevResourcesDir = layout.buildDirectory.dir("resources/main")
val unpackedSharedRuntimeDir = layout.buildDirectory.dir("generated/sharedRuntimeModClasses")
val commonMainKotlinDir = rootProject.layout.projectDirectory.dir("platform-common/src/main/kotlin")
val commonClientKotlinDir = rootProject.layout.projectDirectory.dir("platform-common/src/client/kotlin")
val commonClientJavaDir = rootProject.layout.projectDirectory.dir("platform-common/src/client/java")
val sharedModuleNames = setOf("api", "core", "client-core")
val ideaCoroutineAgentRunWarning = """
    IntelliJ IDEA Debug + Forge warning:
    If this run crashes on the first coroutine launch with
    "IllegalAccessError: kotlin.stdlib does not read kotlinx.coroutines.core",
    disable IntelliJ's Kotlin debugger option "Attach coroutine agent".
    Path: Settings | Build, Execution, Deployment | Debugger | Kotlin.
""".trimIndent()
val embeddedRuntimeModules = mapOf(
    "com.akuleshov7" to setOf("ktoml-core-jvm", "ktoml-file-jvm", "ktoml-source-jvm"),
    "com.squareup.okio" to setOf("okio-jvm"),
    "com.squareup.wire" to setOf("wire-runtime-jvm"),
    "org.jetbrains.kotlinx" to setOf("kotlinx-datetime-jvm"),
    "org.lolicode" to setOf("lava-common", "lavaplayer"),
)

fun shouldEmbedInForgeDevMod(id: ComponentIdentifier): Boolean =
    when (id) {
        is ModuleComponentIdentifier -> id.group == "org.lolicode.moemusic" && id.module in sharedModuleNames
                || id.module in embeddedRuntimeModules[id.group].orEmpty()

        else -> id.displayName in sharedModuleNames.map { "project :shared:$it" }
    }

val sharedRuntimeArtifacts by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = true
}

val sharedAssetResources by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

val unpackSharedRuntimeForDev by tasks.registering(Sync::class) {
    val sharedArtifacts = sharedRuntimeArtifacts.incoming.artifactView {
        componentFilter { shouldEmbedInForgeDevMod(it) }
    }.files

    from(sharedArtifacts.elements.map { elements ->
        elements.map { zipTree(it.asFile) }
    })
    into(unpackedSharedRuntimeDir)
    exclude(
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/*.SF",
        "META-INF/MANIFEST.MF",
        "META-INF/maven/**",
        "META-INF/versions/**",
    )
}

val sharedRuntimeSourceSet = sourceSets.create("sharedRuntime")
sharedRuntimeSourceSet.output.dir(mapOf("builtBy" to unpackSharedRuntimeForDev), unpackedSharedRuntimeDir)

val generateMoeMusicForgeBuildInfo by tasks.registering {
    val modVersion = project.version.toString()
    inputs.property("modVersion", modVersion)
    outputs.dir(generatedBuildInfoDir)

    doLast {
        val outputFile = generatedBuildInfoDir.get()
            .file("org/lolicode/moemusic/MoeMusicForgeBuildInfo.kt")
            .asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package org.lolicode.moemusic

            public object MoeMusicForgeBuildInfo {
                public const val LOADER_ID: String = "forge"
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
    archivesName.set("$modArtifactBaseName-forge-dev")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

tasks.named("sourcesJar") {
    dependsOn(generateMoeMusicForgeBuildInfo, generateMoeMusicPlatformBuildInfo)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }

    sourceSets.named("main") {
        kotlin.srcDir(generatedBuildInfoDir)
        kotlin.srcDir(commonMainKotlinDir)
        kotlin.srcDir(commonClientKotlinDir)
    }
}

sourceSets.main {
    java.srcDir(commonClientJavaDir)
    resources.srcDir("src/generated/resources")
}

tasks.named<KotlinJvmCompile>("compileKotlin") {
    dependsOn(generateMoeMusicForgeBuildInfo, generateMoeMusicPlatformBuildInfo)
    destinationDirectory.set(forgeDevResourcesDir)
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(tasks.named("processResources"))
    destinationDirectory.set(forgeDevResourcesDir)
}

val assembleForgeDevModRoot by tasks.registering(Copy::class) {
    dependsOn(tasks.named("classes"))
    from(sharedRuntimeSourceSet.output)
    into(forgeDevResourcesDir)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude(
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/*.SF",
        "META-INF/MANIFEST.MF",
        "META-INF/maven/**",
        "META-INF/versions/**",
    )
}

repositories {
    minecraft.mavenizer(this)
    maven(fg.forgeMaven)
    maven(fg.minecraftLibsMaven)
}

minecraft {
    mappings("official", minecraftVersion)

    runs {
        configureEach {
            workingDir = layout.projectDirectory.dir("run")
            systemProperty("forge.enabledGameTestNamespaces", modId)
            systemProperty("forge.logging.markers", "REGISTRIES")
            mods {
                create(modId) {
                    source(sourceSets.main.get())
                }
            }
        }

        create("client")

        create("server") {
            args("--nogui")
        }

        create("gameTestServer")

        create("data") {
            workingDir = layout.projectDirectory.dir("run-data")
            args(
                "--mod",
                modId,
                "--all",
                "--output",
                file("src/generated/resources").absolutePath,
                "--existing",
                file("src/main/resources").absolutePath,
            )
        }
    }
}

dependencies {
    implementation(minecraft.dependency("net.minecraftforge:forge:$forgeVersion"))
    compileOnly(files(forgeApiJar))
    compileOnly("net.minecraftforge:javafmllanguage:$forgeVersion")
    compileOnly("net.minecraftforge:fmlloader:$forgeVersion")
    compileOnly("net.minecraftforge:fmlcore:$forgeVersion")
    compileOnly(libs.forge.eventbus)
    compileOnly(libs.brigadier)
    compileOnly(libs.slf4j.api)

    implementation(libs.kotlin.forge)
    implementation(libs.badpackets.forge)

    compileOnly(sharedClientCoreArtifact)
    compileOnly(sharedCoreArtifact)
    compileOnly(sharedApiArtifact)
    compileOnly(libs.kotlinx.coroutines.core)
    add(sharedRuntimeArtifacts.name, sharedClientCoreArtifact)
    add(sharedAssetResources.name, sharedCoreArtifact)

    compileOnly(libs.cloth.config.forge)
    compileOnly(libs.luckperms.api)
    compileOnly(libs.sponge.mixin)
}

tasks.processResources {
    mustRunAfter(tasks.named("compileKotlin"))

    val replaceProperties = mapOf(
        "minecraft_version" to minecraftVersion,
        "minecraft_version_range" to minecraftVersionRange,
        "forge_version" to forgeVersion,
        "forge_version_range" to "[${forgeVersion.substringAfterLast('-')},)",
        "mixin_configs" to forgeMixinConfigs,
        "mod_id" to modId,
        "mod_name" to modName,
        "mod_license" to modLicense,
        "mod_version" to version.toString(),
    )

    inputs.properties(replaceProperties)
    filesMatching("META-INF/mods.toml") {
        expand(replaceProperties)
    }
    filesMatching("META-INF/MANIFEST.MF") {
        expand(replaceProperties)
    }
    filesMatching("pack.mcmeta") {
        expand(replaceProperties)
    }
    from({ sharedAssetResources.map { zipTree(it) } }) {
        include("assets/moemusic/**")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<JavaExec>().configureEach {
    if (name in setOf("runClient", "runServer", "runGameTestServer", "runData")) {
        doFirst {
            logger.warn(ideaCoroutineAgentRunWarning)
        }
    }
    if (name in setOf("runClient", "runServer", "runGameTestServer", "runData")) {
        dependsOn(assembleForgeDevModRoot)
    }
    if (name == "runServer") {
        standardInput = System.`in`
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.jar {
    manifest {
        attributes["MixinConfigs"] = forgeMixinConfigs
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude(
        "com/akuleshov7/**",
        "com/squareup/**",
        "dev/arbjerg/**",
        "kotlinx/datetime/**",
        "okio/**",
        "org/lolicode/lava*/**",
        "org/lolicode/moemusic/api/**",
        "org/lolicode/moemusic/core/**",
    )
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
