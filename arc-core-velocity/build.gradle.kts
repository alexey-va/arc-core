plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Velocity — Velocity task scheduler"

dependencies {
    api(project(":arc-core"))
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-velocity"
        }
    }
}
