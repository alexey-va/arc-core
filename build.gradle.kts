import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.3.0" apply false
}

val releaseVersion = providers.gradleProperty("releaseVersion")
val publicationGroup = providers.gradleProperty("publicationGroup")

group = publicationGroup.getOrElse("ru.arc")
version = releaseVersion.getOrElse("1.0-SNAPSHOT")

if (releaseVersion.isPresent) {
    require(version.toString().matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?"))) {
        "releaseVersion must be an immutable semantic version without a leading v"
    }
    require(group == "ru.ruscrafting.arc") {
        "Release publications must use the Reposilite-authorized ru.ruscrafting.arc group"
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java-library")

    group = rootProject.group
    version = rootProject.version
    val targetJavaVersion = if (name == "arc-core" || name.endsWith("-testing")) 21 else 25

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
        sourceCompatibility = JavaVersion.toVersion(targetJavaVersion)
        targetCompatibility = JavaVersion.toVersion(targetJavaVersion)
        withSourcesJar()
    }

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(25)
        compilerOptions {
            jvmTarget.set(if (targetJavaVersion == 21) JvmTarget.JVM_21 else JvmTarget.JVM_25)
            freeCompilerArgs.addAll(
                "-jvm-default=enable",
                "-opt-in=kotlin.RequiresOptIn",
            )
            if (targetJavaVersion == 21) freeCompilerArgs.add("-Xjdk-release=21")
        }
    }

    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType(MavenPublication::class.java).configureEach {
                pom {
                    name.set(project.description)
                    description.set(project.description)
                    url.set("https://github.com/alexey-va/arc-core")
                    scm {
                        connection.set("scm:git:https://github.com/alexey-va/arc-core.git")
                        developerConnection.set("scm:git:ssh://git@github.com/alexey-va/arc-core.git")
                        url.set("https://github.com/alexey-va/arc-core")
                    }
                }
            }
            repositories {
                maven {
                    name = "releaseStaging"
                    url = rootProject.layout.buildDirectory.dir("release-repository").get().asFile.toURI()
                }
            }
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

tasks.register("stageRelease") {
    group = "publishing"
    description = "Builds every Maven publication into the local immutable release layout."
    dependsOn(subprojects.map { "${it.path}:publishMavenPublicationToReleaseStagingRepository" })
}
