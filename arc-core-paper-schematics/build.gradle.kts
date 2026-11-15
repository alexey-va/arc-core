plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Paper Schematics — bounded async schematic loading, transforms and native block paste"
val paperApiVersion: String by project

dependencies {
    api(project(":arc-core-paper"))
    compileOnlyApi(project(":arc-core-paper-api"))
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    // Reuse the NBT library already shipped by arc-core-paper.
    implementation("net.kyori:adventure-nbt:4.26.1")
    testImplementation(project(":arc-core-paper-api"))
    testImplementation(project(":arc-core-paper-testing"))
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
