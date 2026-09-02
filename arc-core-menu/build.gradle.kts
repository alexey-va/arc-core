plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Menu — validated configurable inventory layouts and state"

dependencies {
    api(project(":arc-core"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-menu"
        }
    }
}
