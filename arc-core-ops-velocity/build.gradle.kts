plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Ops Velocity — Velocity console and proxy handlers"

dependencies {
    api(project(":arc-core-ops"))
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-ops-velocity"
        }
    }
}
