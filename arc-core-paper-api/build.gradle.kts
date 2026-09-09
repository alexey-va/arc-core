plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Paper API — shared cross-plugin service contracts"

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-paper-api"
        }
    }
}
