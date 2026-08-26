plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Ops Paper — Paper console and item handlers"

dependencies {
    api(project(":arc-core-ops"))
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    testImplementation(project(":arc-core-paper-testing"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-ops-paper"
        }
    }
}
