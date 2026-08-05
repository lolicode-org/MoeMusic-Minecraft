import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.shadow)
}

group = "org.lolicode.moemusic"
version = libs.versions.moemusic.velocity.get()

val velocityApiVersion = providers.gradleProperty("velocityApiVersion")
    .orElse(libs.versions.velocity.api)
    .get()

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
    kapt("com.velocitypowered:velocity-api:$velocityApiVersion")

    implementation("org.lolicode.moemusic:api")
    implementation("org.lolicode.moemusic:core")
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    runtimeOnly(libs.kotlinx.serialization.json)
    implementation(libs.lavaplayer)
    implementation(libs.ktoml.core)
    implementation(libs.ktoml.file)
    compileOnly(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testCompileOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
    testRuntimeOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.jar {
    enabled = false
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

    // Velocity owns its API, Brigadier, and Adventure classes; they are compile-only here.
    // The standalone MoeMusic plugin loader deliberately parent-provides Kotlin, kotlinx,
    // SLF4J, and org.lolicode.moemusic.api to source plugins, so those packages stay at their
    // original names. All other bundled implementation libraries are isolated from Velocity
    // and from unrelated proxy plugins.
    relocate("com.akuleshov7.ktoml", "org.lolicode.moemusic.velocity.shadow.ktoml")
    relocate("com.squareup.wire", "org.lolicode.moemusic.velocity.shadow.wire")
    relocate("okio", "org.lolicode.moemusic.velocity.shadow.okio")
    relocate("org.apache", "org.lolicode.moemusic.velocity.shadow.apache")
    relocate("com.fasterxml.jackson", "org.lolicode.moemusic.velocity.shadow.jackson")
    relocate("org.json", "org.lolicode.moemusic.velocity.shadow.json")
    relocate("org.jsoup", "org.lolicode.moemusic.velocity.shadow.jsoup")
    relocate("org.intellij", "org.lolicode.moemusic.velocity.shadow.intellij")
    relocate("org.jetbrains.annotations", "org.lolicode.moemusic.velocity.shadow.annotations")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}
