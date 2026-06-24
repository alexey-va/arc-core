import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.3.0" apply false
}

group = "ru.arc"
version = "1.0-SNAPSHOT"

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java-library")

    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    }

    dependencies {
        val implementation by configurations
        val testImplementation by configurations
        val testRuntimeOnly by configurations

        implementation(kotlin("stdlib"))

        testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
        testImplementation("io.kotest:kotest-assertions-core:6.0.7")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(25)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
            freeCompilerArgs.addAll(
                "-jvm-default=enable",
                "-opt-in=kotlin.RequiresOptIn",
            )
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    tasks.register("assertKotlinOnly") {
        doLast {
            val javaSources = fileTree("src") { include("**/*.java") }.files
            check(javaSources.isEmpty()) {
                "${project.name} is Kotlin-only; remove Java sources: ${javaSources.joinToString()}"
            }
        }
    }

    tasks.named("check") { dependsOn("assertKotlinOnly") }
}

tasks.register("testAll") {
    dependsOn(subprojects.map { it.tasks.named("test") })
}
