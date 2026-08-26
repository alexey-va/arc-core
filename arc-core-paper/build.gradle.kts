plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Paper — scheduling, transfer, teleport and player-state escrow"

val paperApiVersion: String by project

dependencies {
    api(project(":arc-core"))
    api(project(":arc-core-logging"))
    api(project(":arc-core-metrics"))
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    testImplementation(project(":arc-core-paper-testing"))
    testImplementation("io.mockk:mockk:1.14.7")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-paper"
        }
    }
}
