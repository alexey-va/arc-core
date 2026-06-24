plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Paper — Bukkit task scheduler and config extensions"

dependencies {
    api(project(":arc-core"))
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.98.0")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-paper"
        }
    }
}
