plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Ops Paper — Paper console and item handlers"

val paperApiVersion: String by project

dependencies {
    api(project(":arc-core-ops"))
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

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
