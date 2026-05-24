import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.fabric.loom)
    alias(libs.plugins.shadow)
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val modArtifactBaseName = rootProject.extra["moemusic.artifactBaseName"] as String
val generatedBuildInfoDir = layout.buildDirectory.dir("generated/sources/moemusicBuildInfo/main/kotlin")

val commonMainKotlinDir = rootProject.layout.projectDirectory.dir("platform-common/src/main/kotlin")
val commonClientKotlinDir = rootProject.layout.projectDirectory.dir("platform-common/src/client/kotlin")
val commonClientJavaDir = rootProject.layout.projectDirectory.dir("platform-common/src/client/java")
val commonTestKotlinDir = rootProject.layout.projectDirectory.dir("platform-common/src/test/kotlin")
val commonTestResourcesDir = rootProject.layout.projectDirectory.dir("platform-common/src/test/resources")

val sharedApiArtifact = libsCatalog.findLibrary("moemusic-api-artifact").get()
val sharedCoreArtifact = libsCatalog.findLibrary("moemusic-core-artifact").get()
val sharedClientCoreArtifact = libsCatalog.findLibrary("moemusic-client-core-artifact").get()
val platformCommonVersion = libsCatalog.findVersion("moemusic-platform-common").get().requiredVersion

val sharedAssetResources by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

val installShade by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = true
}

val generateMoeMusicFabricBuildInfo by tasks.registering {
    val modVersion = project.version.toString()
    inputs.property("modVersion", modVersion)
    outputs.dir(generatedBuildInfoDir)

    doLast {
        val outputFile = generatedBuildInfoDir.get()
            .file("org/lolicode/moemusic/MoeMusicFabricBuildInfo.kt")
            .asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package org.lolicode.moemusic

            public object MoeMusicFabricBuildInfo {
                public const val LOADER_ID: String = "fabric"
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

loom {
    splitEnvironmentSourceSets()

    mods {
        register("moemusic") {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets.getByName("client"))
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }

    sourceSets.named("main") {
        kotlin.srcDir(generatedBuildInfoDir)
        kotlin.srcDir(commonMainKotlinDir)
    }

    sourceSets.named("client") {
        kotlin.srcDir(commonClientKotlinDir)
    }

    sourceSets.named("test") {
        kotlin.srcDir(commonTestKotlinDir)
    }
}

sourceSets.named("client") {
    java.srcDir(commonClientJavaDir)
}

sourceSets.named("test") {
    resources.srcDir(commonTestResourcesDir)
}

tasks.named("compileKotlin") {
    dependsOn(generateMoeMusicFabricBuildInfo, generateMoeMusicPlatformBuildInfo)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

tasks.named("sourcesJar") {
    dependsOn(generateMoeMusicFabricBuildInfo, generateMoeMusicPlatformBuildInfo)
}

dependencies {
    minecraft(libs.minecraft)
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api)
    modLocalRuntime(libs.fabric.language.kotlin)
    modCompileOnly(libs.fabric.permissions.api)

    // Bad Packets provides the cross-loader packet transport used by shared platform sources.
    modImplementation(libs.badpackets.fabric)

    implementation(sharedClientCoreArtifact)
    implementation(sharedCoreArtifact)
    implementation(sharedApiArtifact)
    add(sharedAssetResources.name, sharedCoreArtifact)
    add(installShade.name, sharedClientCoreArtifact)

    // Coroutines are used by command and client bootstrap adapters.
    implementation(libs.kotlinx.coroutines.core)

    // Cloth Config and Mod Menu are optional client integrations.
    "modClientCompileOnly"(libs.cloth.config.fabric)
    "modClientCompileOnly"(libs.modmenu)
    "clientCompileOnly"(libs.sponge.mixin)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
    from({ sharedAssetResources.map { zipTree(it) } }) {
        include("assets/moemusic/**")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val installShadowJar by tasks.registering(ShadowJar::class) {
    dependsOn(tasks.named("jar"))
    archiveClassifier.set("dev-shadow")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    configurations = listOf(installShade)

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
        "natives/android-armhf/**",
        "natives/android-x86/**",
        "natives/android-x86-64/**",
        "natives/linux-aarch32/**",
        "natives/linux-arm/**",
        "natives/linux-armhf/**",
    )

    listOf(
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

tasks.remapJar {
    dependsOn(installShadowJar)
    inputFile.set(installShadowJar.flatMap { it.archiveFile })
    archiveBaseName.set("$modArtifactBaseName-fabric")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
}

tasks.jar {
    archiveClassifier.set("dev")
    from(rootProject.file("LICENSE")) {
        rename { "${it}_moemusic" }
    }
}
