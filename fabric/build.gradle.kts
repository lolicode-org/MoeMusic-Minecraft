import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DuplicatesStrategy
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.fabric.loom)
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
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
        jvmTarget = JvmTarget.JVM_25
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
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    withSourcesJar()
}

tasks.named("sourcesJar") {
    dependsOn(generateMoeMusicFabricBuildInfo, generateMoeMusicPlatformBuildInfo)
}

dependencies {
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)
    implementation(libs.fabric.language.kotlin)
    compileOnly(libs.fabric.permissions.api)

    // Bad Packets provides the cross-loader packet transport used by shared platform sources.
    implementation(libs.badpackets.fabric)

    implementation(sharedClientCoreArtifact)
    implementation(sharedCoreArtifact)
    implementation(sharedApiArtifact)
    add(sharedAssetResources.name, sharedCoreArtifact)

    // Coroutines are used by command and client bootstrap adapters.
    implementation(libs.kotlinx.coroutines.core)

    // Cloth Config and Mod Menu are optional client integrations.
    "clientCompileOnly"(libs.cloth.config.fabric)
    "clientCompileOnly"(libs.modmenu)
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

tasks.jar {
    from(rootProject.file("LICENSE")) {
        rename { "${it}_moemusic" }
    }
}
