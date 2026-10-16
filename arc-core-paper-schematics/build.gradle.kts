plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Paper Schematics — bounded async schematic loading, transforms and native block paste"
val paperApiVersion: String by project

repositories { maven("https://maven.enginehub.org/repo/") }

dependencies {
    api(project(":arc-core-paper"))
    compileOnlyApi(project(":arc-core-paper-api"))
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.18")
    testImplementation(project(":arc-core-paper-api"))
    testImplementation(project(":arc-core-paper-testing"))
    testImplementation("com.sk89q.worldedit:worldedit-bukkit:7.3.18")
    testImplementation("io.mockk:mockk:1.14.7")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-paper-schematics"
        }
    }
}
