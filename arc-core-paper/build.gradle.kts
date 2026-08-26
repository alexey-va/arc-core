plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Paper — scheduling, transfer, teleport and player-state escrow"

dependencies {
    api(project(":arc-core"))
    api(project(":arc-core-logging"))
    api(project(":arc-core-metrics"))
    compileOnly("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")

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
