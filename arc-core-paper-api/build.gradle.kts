plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Paper API — shared cross-plugin service contracts"

val paperApiVersion: String by project

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation(project(":arc-core-paper-testing"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-paper-api"
        }
    }
}
