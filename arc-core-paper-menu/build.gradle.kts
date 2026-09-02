plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Paper Menu — configurable Inventory Framework menus"

val paperApiVersion: String by project

dependencies {
    api(project(":arc-core-menu"))
    compileOnlyApi("io.papermc.paper:paper-api:$paperApiVersion")
    implementation("com.github.stefvanschie.inventoryframework:IF:0.12.0")

    testImplementation(project(":arc-core-paper-testing"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-paper-menu"
        }
    }
}
