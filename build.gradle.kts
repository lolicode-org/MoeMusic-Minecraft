import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    id("com.gradleup.shadow") version "9.4.2"
}

group = "org.lolicode.moemusic"
version = "1.3.0"
val spigotApiVersion = providers.gradleProperty("spigotApiVersion").orElse("1.18.2-R0.1-SNAPSHOT")
val pluginVersion = version.toString()

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:${spigotApiVersion.get()}")
    implementation("org.lolicode.moemusic:api:2.1.1")
    implementation("org.lolicode.moemusic:core:1.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    runtimeOnly("org.slf4j:slf4j-jdk14:2.0.18")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.0")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    relocate("kotlin", "org.lolicode.moemusic.spigot.shadow.kotlin")
    relocate("kotlinx", "org.lolicode.moemusic.spigot.shadow.kotlinx")
    relocate("com.squareup", "org.lolicode.moemusic.spigot.shadow.com.squareup")
    relocate("okio", "org.lolicode.moemusic.spigot.shadow.okio")
    relocate("com.akuleshov7", "org.lolicode.moemusic.spigot.shadow.com.akuleshov7")
    relocate("org.apache.http", "org.lolicode.moemusic.spigot.shadow.org.apache.http")
    relocate("org.apache.commons", "org.lolicode.moemusic.spigot.shadow.org.apache.commons")
    relocate("org.jsoup", "org.lolicode.moemusic.spigot.shadow.org.jsoup")
    relocate("org.json", "org.lolicode.moemusic.spigot.shadow.org.json")
    relocate("com.fasterxml.jackson", "org.lolicode.moemusic.spigot.shadow.com.fasterxml.jackson")
    relocate("org.slf4j", "org.lolicode.moemusic.spigot.shadow.org.slf4j")
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
