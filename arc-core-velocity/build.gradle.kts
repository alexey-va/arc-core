plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Velocity — Velocity task scheduler"

dependencies {
    api(project(":arc-core"))
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    testImplementation("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    testImplementation("io.mockk:mockk:1.14.6")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-velocity"
        }
    }
}
