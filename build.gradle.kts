plugins {
    kotlin("jvm") version "2.3.0"
    `maven-publish`
}

group = "ru.arc"
version = "1.0-SNAPSHOT"
description = "ARC Core — platform-agnostic config, scheduling, events (Kotlin only)"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
        freeCompilerArgs.addAll(
            "-jvm-default=all",
            "-opt-in=kotlin.RequiresOptIn",
        )
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.yaml:snakeyaml:2.2")
    implementation("net.kyori:adventure-api:4.17.0")
    implementation("net.kyori:adventure-text-minimessage:4.17.0")
    implementation("net.kyori:adventure-text-serializer-plain:4.17.0")
    implementation("net.kyori:adventure-text-serializer-legacy:4.17.0")
    implementation("org.slf4j:slf4j-api:2.0.13")

    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

/** Fail build if any .java sources appear — Kotlin only. */
tasks.register("assertKotlinOnly") {
    doLast {
        val javaSources = fileTree("src") { include("**/*.java") }.files
        check(javaSources.isEmpty()) {
            "arc-core is Kotlin-only; remove Java sources: ${javaSources.joinToString()}"
        }
    }
}

tasks.named("check") { dependsOn("assertKotlinOnly") }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "ru.arc"
            artifactId = "arc-core"
        }
    }
}
