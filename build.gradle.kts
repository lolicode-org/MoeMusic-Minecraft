import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

group = "org.lolicode.moemusic"
version = libs.versions.moemusic.spigot.get()
val spigotApiVersion = providers.gradleProperty("spigotApiVersion").orElse(libs.versions.spigot.api)
val pluginVersion = version.toString()
val kotlinRuntimeVersion = libs.versions.kotlin.get()
val coroutinesVersion = libs.versions.kotlinx.coroutines.get()
val serializationVersion = libs.versions.kotlinx.serialization.get()
val slf4jVersion = libs.versions.slf4j.get()

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

tasks.test {
    useJUnitPlatform()
}
