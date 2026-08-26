plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Paper Testing — canonical MockBukkit runtime and fixtures"

java {
    withSourcesJar()
}

dependencies {
    api("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    api("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.98.0")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-paper-testing"
        }
    }
}
