plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Paper — scheduling, transfer, teleport and player-state escrow"

val paperApiVersion: String by project

dependencies {
    api(project(":arc-core"))
    compileOnlyApi(project(":arc-core-logging"))
    compileOnlyApi(project(":arc-core-metrics"))
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    testImplementation(project(":arc-core-paper-testing"))
    testImplementation(project(":arc-core-logging"))
    testImplementation(project(":arc-core-metrics"))
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
