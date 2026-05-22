import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.forge.gradle)
    alias(libs.plugins.forge.renamer)
    alias(libs.plugins.shadow)
    idea
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val modId = "moemusic"
val modName = "MoeMusic"
val modLicense = "AGPL-3.0-or-later"
val minecraftVersion = libsCatalog.findVersion("minecraft").get().requiredVersion
val minecraftVersionRange = libsCatalog.findVersion("minecraft-version-range").get().requiredVersion
val forgeVersion = libsCatalog.findVersion("forge").get().requiredVersion
val mappingsVersion = libsCatalog.findVersion("forge-mappings").get().requiredVersion
val badPacketsForgeVersion = libsCatalog.findVersion("badpackets-forge").get().requiredVersion
val sharedApiArtifact = libsCatalog.findLibrary("moemusic-api-artifact").get()
val sharedCoreArtifact = libsCatalog.findLibrary("moemusic-core-artifact").get()
val sharedClientCoreArtifact = libsCatalog.findLibrary("moemusic-client-core-artifact").get()
val platformCommonVersion = libsCatalog.findVersion("moemusic-platform-common").get().requiredVersion
val forgeMixinConfigs = "moemusic.mixins.json,moemusic.client.mixins.json"
val forgeApiJar = rootProject.layout.projectDirectory.file(
    ".gradle/mavenizer/repo/net/minecraftforge/forge/$forgeVersion/forge-$forgeVersion.jar"
)

val generatedBuildInfoDir = layout.buildDirectory.dir("generated/sources/moemusicBuildInfo/main/kotlin")
val patchedBadPacketsMixinConfig = layout.buildDirectory.file("generated/badpacketsDevJar/badpackets.mixins.json")
val mainResourcesOutputDir = layout.buildDirectory.dir("resources/main")
val forgeDevModRootDir = layout.buildDirectory.dir("forgeDevMod/main")
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

val rawBadPacketsForge by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

val installShade by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = true
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
    archivesName.set("${rootProject.name}-forge-dev")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

tasks.named("sourcesJar") {
    dependsOn(generateMoeMusicForgeBuildInfo, generateMoeMusicPlatformBuildInfo)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
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
}

val cleanupLegacyForgeResourceClasses by tasks.registering(Delete::class) {
    delete(
        fileTree(mainResourcesOutputDir.get().asFile) {
            include("**/*.class")
            include("META-INF/*.kotlin_module")
        },
    )
}

val assembleForgeDevModRoot by tasks.registering(Sync::class) {
    dependsOn(tasks.named("classes"))
    from(sourceSets.main.map { it.output })
    from(sharedRuntimeSourceSet.output)
    into(forgeDevModRootDir)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude(
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/*.SF",
        "META-INF/maven/**",
    )
}

val forgeDevModSourceSet = sourceSets.create("forgeDevMod") {
    output.dir(mapOf("builtBy" to assembleForgeDevModRoot), forgeDevModRootDir)
    output.setResourcesDir(forgeDevModRootDir)
    compileClasspath += sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().runtimeClasspath
}

repositories {
    minecraft.mavenizer(this)
    maven(fg.forgeMaven)
    maven(fg.minecraftLibsMaven)
}

renamer {
    mappings("net.minecraft:mappings_official:$mappingsVersion:map2srg@tsrg.gz")
}

val renameBadPacketsForgeForDev = renamer.classes("renameBadPacketsForgeForDev") {
    input.set(layout.file(rawBadPacketsForge.elements.map { elements ->
        elements.single().asFile
    }))
    libraries.setFrom(files())
    reverse.set(true)
    naiveSrg.set(true)
    accessTransformers.set(true)
    archiveClassifier.set("renamed")
}

val generatePatchedBadPacketsMixinConfig by tasks.registering {
    outputs.file(patchedBadPacketsMixinConfig)

    doLast {
        val outputFile = patchedBadPacketsMixinConfig.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            {
              "required"          : true,
              "minVersion"        : "0.8",
              "package"           : "lol.bai.badpackets.impl.mixin",
              "compatibilityLevel": "JAVA_16",
              "mixins"            : [
                "MixinConnection",
                "MixinPlayerList",
                "MixinServerGamePacketListenerImpl"
              ],
              "client"            : [
                "client.MixinClientPacketListener"
              ],
              "injectors"         : {
                "defaultRequire": 1
              }
            }
            """.trimIndent() + "\n",
        )
    }
}

val patchBadPacketsForgeForDev by tasks.registering(Jar::class) {
    dependsOn(renameBadPacketsForgeForDev, generatePatchedBadPacketsMixinConfig)
    archiveFileName.set("badpackets-$badPacketsForgeVersion-dev.jar")
    destinationDirectory.set(layout.buildDirectory.dir("generated/badpacketsDevJar"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("MixinConfigs" to "badpackets.mixins.json")
    }

    from(renameBadPacketsForgeForDev.flatMap { it.output }.map { zipTree(it.asFile) }) {
        exclude("badpackets.mixins.json")
    }
    from(patchedBadPacketsMixinConfig) {
        rename { "badpackets.mixins.json" }
    }
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
                    source(forgeDevModSourceSet)
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
    add(rawBadPacketsForge.name, libs.badpackets.forge)
    implementation(files(patchBadPacketsForgeForDev.flatMap { it.archiveFile }))

    compileOnly(sharedClientCoreArtifact)
    compileOnly(sharedCoreArtifact)
    compileOnly(sharedApiArtifact)
    compileOnly(libs.kotlinx.coroutines.core)
    add(sharedRuntimeArtifacts.name, sharedClientCoreArtifact)
    add(sharedAssetResources.name, sharedCoreArtifact)
    add(installShade.name, sharedClientCoreArtifact)

    compileOnly(libs.cloth.config.forge)
    compileOnly(libs.luckperms.api)
    compileOnly(libs.sponge.mixin)
}

tasks.processResources {
    dependsOn(cleanupLegacyForgeResourceClasses)

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

val installShadowJar by tasks.registering(ShadowJar::class) {
    dependsOn(tasks.named("jar"))
    archiveBaseName.set("${rootProject.name}-forge")
    archiveClassifier.set("dev-shadow")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    configurations = listOf(installShade)
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
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
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
        "kotlinx.datetime" to "org.lolicode.moemusic.shadow.kotlinx.datetime",
        "com.squareup.wire" to "org.lolicode.moemusic.shadow.com.squareup.wire",
        "okio" to "org.lolicode.moemusic.shadow.okio",
        "dev.arbjerg" to "org.lolicode.moemusic.shadow.dev.arbjerg",
        "com.akuleshov7" to "org.lolicode.moemusic.shadow.com.akuleshov7",
        "org.apache.http" to "org.lolicode.moemusic.shadow.org.apache.http",
        "org.apache.commons.codec" to "org.lolicode.moemusic.shadow.org.apache.commons.codec",
        "org.apache.commons.io" to "org.lolicode.moemusic.shadow.org.apache.commons.io",
        "org.apache.commons.logging" to "org.lolicode.moemusic.shadow.org.apache.commons.logging",
    ).forEach { (sourcePackage, targetPackage) ->
        relocate(sourcePackage, targetPackage)
    }

    from(tasks.named<Jar>("jar").map { zipTree(it.archiveFile) })
}

val reobfInstallJar = renamer.classes("reobfInstallJar", installShadowJar) {
    dependsOn(installShadowJar)
    naiveSrg.set(true)
    accessTransformers.set(true)
    output.set(rootProject.layout.buildDirectory.file("libs/${rootProject.name}-forge-${project.version}.jar"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveClassifier.set("dev")
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
