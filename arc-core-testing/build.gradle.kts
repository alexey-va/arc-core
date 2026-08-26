plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Testing — deterministic clocks, executors and failure injection"

java {
    withSourcesJar()
}

dependencies {
    api(project(":arc-core"))
    testImplementation("io.kotest:kotest-property:6.0.7")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-testing"
        }
    }
}
